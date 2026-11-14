package app.brootlite.tv.youtube

import app.brootlite.tv.BrootLiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Official YouTube TV OAuth2 Device Code Sign-In & Brand Account manager.
 *
 * Security invariant: NEVER logs OAuth2 access tokens, refresh tokens, device codes,
 * client secrets, or Authorization headers!
 */
object YouTubeAuthClient {

    const val DEFAULT_TV_CLIENT_ID = ""
    const val DEFAULT_TV_CLIENT_SECRET = ""

    private const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
    private const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
    private const val ACCOUNTS_LIST_URL = "https://www.youtube.com/youtubei/v1/account/accounts_list?prettyPrint=false"
    private const val TV_PAGE_URL = "https://www.youtube.com/tv"
    const val ACTIVATION_URL = "https://www.youtube.com/activate"

    private const val OAUTH_SCOPE =
        "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
    private const val GRANT_TYPE_DEVICE = "http://oauth.net/grant_type/device/1.0"
    private const val GRANT_TYPE_REFRESH = "refresh_token"
    private const val MODEL_NAME = "ytlr::"
    private const val EXPIRY_BUFFER_MS = 60_000L

    internal const val TV_COBALT_UA =
        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
    internal const val TV_CLIENT_VERSION = "7.20260901.15.00"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val BASE_JS_PATTERNS = listOf(
        Regex("""id="base-js"[^>]*?src="([^"]+)""""),
        Regex("""src="([^"]+)"[^>]*?id="base-js""""),
        Regex("""\.src\s*=\s*'([^']*m=base[^']*)'""")
    )
    private val CLIENT_CRED_REGEX =
        Regex("""clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[$\w]+:"([\w-]+)"""")

    private data class CachedAccessToken(
        val accessToken: String,
        val expiresAtMs: Long,
        val clientId: String,
        val clientSecret: String
    )

    private val tokenMutex = Mutex()
    private val accessTokenCache = LinkedHashMap<String, CachedAccessToken>(4, 0.75f, true)

    @Volatile
    private var bootstrappedCredentials: Pair<String, String>? = null

    private val httpClient: OkHttpClient
        get() = BrootLiteApp.sharedHttpClient

    /**
     * Requests a YouTube TV OAuth2 device code & user activation code (`https://www.youtube.com/activate`).
     * Automatically falls back to live-bootstrapped `youtube.com/tv` client credentials if Google ever rotates them.
     */
    suspend fun requestDeviceCode(
        deviceId: String = UUID.randomUUID().toString(),
        preferredClientId: String? = null,
        preferredClientSecret: String? = null
    ): YouTubeDeviceCode = withContext(Dispatchers.IO) {
        val creds = if (!preferredClientId.isNullOrBlank() && !preferredClientSecret.isNullOrBlank()) {
            preferredClientId to preferredClientSecret
        } else {
            bootstrappedCredentials ?: bootstrapTvCredentials() ?: (DEFAULT_TV_CLIENT_ID to DEFAULT_TV_CLIENT_SECRET)
        }

        if (creds.first.isBlank() || creds.second.isBlank()) {
            throw IOException("Failed to obtain YouTube TV client credentials")
        }

        executeDeviceCodeRequest(deviceId, creds.first, creds.second)
    }

    private fun executeDeviceCodeRequest(
        deviceId: String,
        clientId: String,
        clientSecret: String
    ): YouTubeDeviceCode {
        val payload = buildJsonObject {
            put("client_id", clientId)
            put("device_id", deviceId)
            put("model_name", MODEL_NAME)
            put("scope", OAUTH_SCOPE)
        }.toString()

        val req = Request.Builder()
            .url(DEVICE_CODE_URL)
            .header("User-Agent", TV_COBALT_UA)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("YouTube device code HTTP ${resp.code}")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val devCode = root["device_code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IOException("Missing device_code in YouTube OAuth response")
            val usrCode = root["user_code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IOException("Missing user_code in YouTube OAuth response")
            val rawVerUrl = root["verification_url"]?.jsonPrimitive?.contentOrNull
                ?: root["verification_uri"]?.jsonPrimitive?.contentOrNull
                ?: ACTIVATION_URL
            val verUrl = if (rawVerUrl.contains("youtube.com/activate")) ACTIVATION_URL else rawVerUrl
            val interval = root["interval"]?.jsonPrimitive?.intOrNull?.coerceIn(2, 60) ?: 5
            val expiresIn = root["expires_in"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(60) ?: 1800

            return YouTubeDeviceCode(
                deviceCode = devCode,
                userCode = usrCode,
                verificationUrl = verUrl,
                intervalSec = interval,
                expiresInSec = expiresIn,
                clientId = clientId,
                clientSecret = clientSecret
            )
        }
    }

    /**
     * Single-shot poll of `https://www.youtube.com/o/oauth2/token` for [deviceCode].
     * Returns `null` while the user has not yet completed activation (`authorization_pending` / `slow_down`),
     * returns [YouTubeOAuthTokens] once authorized, and throws [IOException] if expired or denied.
     */
    suspend fun pollDeviceToken(
        deviceCode: String,
        clientId: String = bootstrappedCredentials?.first ?: DEFAULT_TV_CLIENT_ID,
        clientSecret: String = bootstrappedCredentials?.second ?: DEFAULT_TV_CLIENT_SECRET
    ): YouTubeOAuthTokens? = when (val state = pollDeviceTokenDetailed(deviceCode, clientId, clientSecret)) {
        is YouTubeDevicePollResult.Authorized -> state.tokens
        is YouTubeDevicePollResult.Pending,
        is YouTubeDevicePollResult.SlowDown -> null
        is YouTubeDevicePollResult.Error -> throw IOException("YouTube OAuth sign-in failed: ${state.errorCode}")
    }

    /**
     * Detailed single-tick poll returning [YouTubeDevicePollResult] (handles `slow_down` interval backoff cleanly).
     */
    suspend fun pollDeviceTokenDetailed(
        deviceCode: String,
        clientId: String = bootstrappedCredentials?.first ?: DEFAULT_TV_CLIENT_ID,
        clientSecret: String = bootstrappedCredentials?.second ?: DEFAULT_TV_CLIENT_SECRET
    ): YouTubeDevicePollResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("code", deviceCode)
            put("client_id", clientId)
            put("client_secret", clientSecret)
            put("grant_type", GRANT_TYPE_DEVICE)
        }.toString()

        val req = Request.Builder()
            .url(TOKEN_URL)
            .header("User-Agent", TV_COBALT_UA)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                ?: return@withContext YouTubeDevicePollResult.Error("parse_error", "Invalid OAuth response (${resp.code})")

            val errCode = root["error"]?.jsonPrimitive?.contentOrNull
            if (errCode != null) {
                return@withContext when (errCode) {
                    "authorization_pending" -> YouTubeDevicePollResult.Pending
                    "slow_down" -> YouTubeDevicePollResult.SlowDown(5)
                    else -> YouTubeDevicePollResult.Error(
                        errorCode = errCode,
                        message = root["error_description"]?.jsonPrimitive?.contentOrNull ?: errCode
                    )
                }
            }

            if (!resp.isSuccessful) {
                return@withContext YouTubeDevicePollResult.Error("http_${resp.code}", "HTTP ${resp.code}")
            }

            val access = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@withContext YouTubeDevicePollResult.Error("missing_access_token", "Missing access_token")
            val refresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@withContext YouTubeDevicePollResult.Error("missing_refresh_token", "Missing refresh_token")
            val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(60L) ?: 3600L
            val nowMs = System.currentTimeMillis()
            val tokens = YouTubeOAuthTokens(
                accessToken = access,
                refreshToken = refresh,
                expiresInSec = expiresIn,
                clientId = clientId,
                clientSecret = clientSecret,
                expiresAtMs = nowMs + expiresIn * 1000L
            )
            cacheTokenInRam(refresh, tokens)
            YouTubeDevicePollResult.Authorized(tokens)
        }
    }

    /**
     * Suspend helper that polls [deviceCodeInfo] at its server-specified interval until authorized, cancelled, or expired.
     */
    suspend fun awaitDeviceToken(deviceCodeInfo: YouTubeDeviceCode): YouTubeOAuthTokens {
        var intervalMs = deviceCodeInfo.intervalSec.coerceAtLeast(3) * 1000L
        val deadlineMs = System.currentTimeMillis() + deviceCodeInfo.expiresInSec * 1000L
        val cId = deviceCodeInfo.clientId.ifBlank { DEFAULT_TV_CLIENT_ID }
        val cSecret = deviceCodeInfo.clientSecret.ifBlank { DEFAULT_TV_CLIENT_SECRET }

        while (coroutineContext.isActive && System.currentTimeMillis() < deadlineMs) {
            delay(intervalMs)
            when (val res = pollDeviceTokenDetailed(deviceCodeInfo.deviceCode, cId, cSecret)) {
                is YouTubeDevicePollResult.Authorized -> return res.tokens
                is YouTubeDevicePollResult.Pending -> Unit
                is YouTubeDevicePollResult.SlowDown -> intervalMs += res.extraDelaySec * 1000L
                is YouTubeDevicePollResult.Error -> throw IOException("YouTube sign-in failed: ${res.errorCode}")
            }
        }
        throw IOException("YouTube activation code expired")
    }

    /**
     * Refreshes a short-lived `access_token` from [refreshToken], caching it in RAM with a 60s expiry buffer.
     */
    suspend fun refreshAccessToken(
        refreshToken: String,
        clientId: String = DEFAULT_TV_CLIENT_ID,
        clientSecret: String = DEFAULT_TV_CLIENT_SECRET
    ): YouTubeOAuthTokens = tokenMutex.withLock {
        val effClientId = clientId.ifBlank { DEFAULT_TV_CLIENT_ID }
        val effSecret = clientSecret.ifBlank { DEFAULT_TV_CLIENT_SECRET }
        val nowMs = System.currentTimeMillis()

        val cached = accessTokenCache[refreshToken]
        if (cached != null && cached.expiresAtMs > nowMs + EXPIRY_BUFFER_MS) {
            val remainingSec = ((cached.expiresAtMs - nowMs) / 1000L).coerceAtLeast(60L)
            return@withLock YouTubeOAuthTokens(
                accessToken = cached.accessToken,
                refreshToken = refreshToken,
                expiresInSec = remainingSec,
                clientId = cached.clientId,
                clientSecret = cached.clientSecret,
                expiresAtMs = cached.expiresAtMs
            )
        }

        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("refresh_token", refreshToken)
                put("client_id", effClientId)
                put("client_secret", effSecret)
                put("grant_type", GRANT_TYPE_REFRESH)
            }.toString()

            val req = Request.Builder()
                .url(TOKEN_URL)
                .header("User-Agent", TV_COBALT_UA)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    accessTokenCache.remove(refreshToken)
                    throw IOException("YouTube token refresh HTTP ${resp.code}")
                }
                val root = json.parseToJsonElement(bodyStr).jsonObject
                val access = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Missing access_token on refresh")
                val rotatedRefresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: refreshToken
                val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(60L) ?: 3600L
                val expiresAt = System.currentTimeMillis() + expiresIn * 1000L

                val tokens = YouTubeOAuthTokens(
                    accessToken = access,
                    refreshToken = rotatedRefresh,
                    expiresInSec = expiresIn,
                    clientId = effClientId,
                    clientSecret = effSecret,
                    expiresAtMs = expiresAt
                )
                cacheTokenInRamLocked(rotatedRefresh, tokens)
                if (rotatedRefresh != refreshToken) {
                    cacheTokenInRamLocked(refreshToken, tokens)
                }
                tokens
            }
        }
    }

    /**
     * Resolves a valid non-expired `access_token` for [authContext], using RAM cache or refreshing
     * automatically with a 60s expiry buffer and notifying [YouTubeAuthContext.onTokenRefreshed].
     */
    suspend fun getValidAccessToken(authContext: YouTubeAuthContext?): String? {
        if (authContext == null || !authContext.isSignedIn) return null
        val nowMs = System.currentTimeMillis()
        if (!authContext.accessToken.isNullOrBlank() && authContext.expiresAtMs > nowMs + EXPIRY_BUFFER_MS) {
            return authContext.accessToken
        }
        val refresh = authContext.refreshToken?.takeIf { it.isNotBlank() }
            ?: return authContext.accessToken?.takeIf { it.isNotBlank() }

        val refreshed = runCatching {
            refreshAccessToken(
                refreshToken = refresh,
                clientId = authContext.clientId ?: DEFAULT_TV_CLIENT_ID,
                clientSecret = authContext.clientSecret ?: DEFAULT_TV_CLIENT_SECRET
            )
        }.getOrNull() ?: return authContext.accessToken?.takeIf { it.isNotBlank() }

        if (refreshed.accessToken != authContext.accessToken) {
            runCatching { authContext.onTokenRefreshed?.invoke(refreshed) }
        }
        return refreshed.accessToken
    }

    /**
     * Lists the signed-in Google user's Personal + Brand YouTube channels via `/youtubei/v1/account/accounts_list`.
     * Brand Accounts return a non-null [YouTubeAccountItem.pageId] (`onBehalfOfUser`) so switching channels works seamlessly.
     */
    suspend fun listAccounts(accessToken: String): List<YouTubeAccountItem> = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext emptyList()

        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "TVHTML5")
                    put("clientVersion", TV_CLIENT_VERSION)
                    put("clientScreen", "WATCH")
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            putJsonObject("accountReadMask") {
                put("returnOwner", true)
                put("returnBrandAccounts", true)
                put("returnPersonaAccounts", false)
            }
        }.toString()

        val req = Request.Builder()
            .url(ACCOUNTS_LIST_URL)
            .header("User-Agent", TV_COBALT_UA)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "7")
            .header("X-Youtube-Client-Version", TV_CLIENT_VERSION)
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || bodyStr.isBlank()) return@withContext emptyList()
            val root = runCatching { json.parseToJsonElement(bodyStr) }.getOrNull() ?: return@withContext emptyList()

            val itemNodes = ArrayList<JsonObject>()
            collectRendererObjects(root, setOf("accountItem", "accountItemRenderer"), itemNodes)

            itemNodes.mapNotNull { item ->
                if (item["isDisabled"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
                val pageId = findFirstStringByKey(item["serviceEndpoint"] ?: item, "pageId")
                val name = extractRunsText(item["accountName"]) ?: return@mapNotNull null
                val byline = extractRunsText(item["accountByline"])
                val handle = extractRunsText(item["channelHandle"])
                    ?: byline?.takeIf { it.startsWith("@") }
                val avatar = extractBestThumbnailUrl(item["accountPhoto"])
                val selected = item["isSelected"]?.jsonPrimitive?.booleanOrNull == true
                val id = pageId
                    ?: findFirstStringByKey(item, "externalChannelId")
                    ?: handle
                    ?: name

                YouTubeAccountItem(
                    accountId = id,
                    accountName = name,
                    channelHandle = handle,
                    email = byline?.takeIf { it.contains("@") && !it.startsWith("@") },
                    avatarUrl = avatar,
                    pageId = pageId,
                    isSelected = selected
                )
            }.distinctBy { "${it.accountId}|${it.pageId.orEmpty()}" }
        }
    }

    fun clearRamTokenCache() {
        synchronized(accessTokenCache) {
            accessTokenCache.clear()
        }
    }

    private suspend fun cacheTokenInRam(refreshKey: String, tokens: YouTubeOAuthTokens) {
        tokenMutex.withLock {
            cacheTokenInRamLocked(refreshKey, tokens)
        }
    }

    private fun cacheTokenInRamLocked(refreshKey: String, tokens: YouTubeOAuthTokens) {
        accessTokenCache[refreshKey] = CachedAccessToken(
            accessToken = tokens.accessToken,
            expiresAtMs = tokens.expiresAtMs,
            clientId = tokens.clientId,
            clientSecret = tokens.clientSecret
        )
        while (accessTokenCache.size > 8) {
            val eldest = accessTokenCache.keys.firstOrNull() ?: break
            accessTokenCache.remove(eldest)
        }
    }

    private fun bootstrapTvCredentials(): Pair<String, String>? {
        return runCatching {
            val tvReq = Request.Builder()
                .url(TV_PAGE_URL)
                .header("User-Agent", TV_COBALT_UA)
                .get()
                .build()
            val html = httpClient.newCall(tvReq).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string().orEmpty()
            }
            val rawJsUrl = BASE_JS_PATTERNS.firstNotNullOfOrNull { rx ->
                rx.find(html)?.groupValues?.getOrNull(1)
            } ?: return null

            val absJsUrl = when {
                rawJsUrl.startsWith("//") -> "https:$rawJsUrl"
                rawJsUrl.startsWith("/") -> "https://www.youtube.com$rawJsUrl"
                rawJsUrl.startsWith("https://www.youtube.com/") -> rawJsUrl
                else -> return null
            }

            val jsReq = Request.Builder()
                .url(absJsUrl)
                .header("User-Agent", TV_COBALT_UA)
                .get()
                .build()
            val jsText = httpClient.newCall(jsReq).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string().orEmpty()
            }
            val match = CLIENT_CRED_REGEX.find(jsText) ?: return null
            val creds = match.groupValues[1] to match.groupValues[2]
            bootstrappedCredentials = creds
            creds
        }.getOrNull()
    }

    private fun collectRendererObjects(
        element: JsonElement,
        targetKeys: Set<String>,
        out: MutableList<JsonObject>
    ) {
        when (element) {
            is JsonObject -> {
                for ((k, v) in element) {
                    if (k in targetKeys && v is JsonObject) {
                        out.add(v)
                    } else {
                        collectRendererObjects(v, targetKeys, out)
                    }
                }
            }
            is JsonArray -> element.forEach { collectRendererObjects(it, targetKeys, out) }
            else -> Unit
        }
    }

    private fun findFirstStringByKey(element: JsonElement?, targetKey: String): String? {
        if (element == null) return null
        when (element) {
            is JsonObject -> {
                (element[targetKey] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                for ((_, v) in element) {
                    findFirstStringByKey(v, targetKey)?.let { return it }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    findFirstStringByKey(item, targetKey)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun extractRunsText(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return (element as? JsonPrimitive)?.contentOrNull
        obj["simpleText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj["runs"] as? JsonArray ?: return null
        val joined = buildString {
            for (r in runs) {
                val t = (r as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                if (!t.isNullOrEmpty()) append(t)
            }
        }.trim()
        return joined.takeIf { it.isNotBlank() }
    }

    private fun extractBestThumbnailUrl(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        val thumbs = (obj["thumbnails"] as? JsonArray)
            ?: (obj["image"] as? JsonObject)?.get("sources") as? JsonArray
            ?: return null
        val raw = thumbs.lastOrNull()?.let { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
            ?: return null
        return if (raw.startsWith("//")) "https:$raw" else raw
    }
}
