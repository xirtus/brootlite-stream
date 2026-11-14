package app.brootlite.tv.jellyfin

import android.content.Context
import android.os.Build
import app.brootlite.tv.BrootLiteApp
import app.brootlite.tv.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.UUID

/**
 * Zero-dependency Multi-Server Jellyfin REST & Playback client.
 *
 * Keeps multiple Jellyfin servers strictly isolated per `JellyfinServerProfile`, reuses
 * `BrootLiteApp.sharedHttpClient` / `sharedPermissiveHttpClient` connection pools, and runs
 * playback session / `ActiveEncodings` stop calls on an un-cancellable `SupervisorJob` scope.
 */
object JellyfinApiClient {

    const val CLIENT_NAME = "BrootLite"
    const val CLIENT_VERSION = "2.0.0"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val EMPTY_JSON_BODY by lazy { "{}".toRequestBody(JSON_MEDIA) }

    private const val LIST_FIELDS =
        "Overview,Genres,PrimaryImageAspectRatio,ProductionYear,OfficialRating,CommunityRating,ProviderIds"
    private const val DETAIL_FIELDS =
        "Overview,Genres,PrimaryImageAspectRatio,MediaSources,Chapters,ProviderIds,ProductionYear,OfficialRating,CommunityRating"

    // Dedicated application-lifetime scope so stop reporting & ffmpeg encoding teardown never get cancelled
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Servers where Jellyfin 10.10 `/MediaSegments/{id}` returned 404 (cached to hit legacy Intro Skipper directly)
    private val legacyIntroEndpointServers: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim()?.takeIf { it.isNotBlank() } ?: "Android TV"
        return sanitizeHeaderValue("$model · BrootLite")
    }

    fun buildAuthorizationHeader(
        deviceId: String,
        accessToken: String? = null,
        deviceName: String = defaultDeviceName()
    ): String {
        val cleanDev = sanitizeHeaderValue(deviceName).ifBlank { "BrootLite TV" }
        val cleanId = sanitizeHeaderValue(deviceId).ifBlank { "brootlite-tv" }
        val base = "MediaBrowser Client=\"$CLIENT_NAME\", Device=\"$cleanDev\", DeviceId=\"$cleanId\", Version=\"$CLIENT_VERSION\""
        val cleanToken = accessToken?.let(::sanitizeHeaderValue)?.takeIf { it.isNotBlank() }
        return if (cleanToken != null) "$base, Token=\"$cleanToken\"" else base
    }

    private fun sanitizeHeaderValue(raw: String): String =
        raw.replace("\"", "")
            .replace(",", " ")
            .replace("\r", "")
            .replace("\n", "")
            .trim()

    private fun clientFor(allowInsecureTls: Boolean): OkHttpClient =
        if (allowInsecureTls) BrootLiteApp.sharedPermissiveHttpClient else BrootLiteApp.sharedHttpClient

    private fun urlEnc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    // =========================================================================
    // AUTHENTICATION & QUICKCONNECT (+ ZERO-PASSWORD MULTIPASS BOOTSTRAP)
    // =========================================================================

    suspend fun probeServerPublicInfo(
        rawUrl: String,
        allowInsecureTls: Boolean = false
    ): JfPublicSystemInfo? = withContext(Dispatchers.IO) {
        val candidates = JellyfinEndpointRouter.candidateProbeUrls(rawUrl)
        val client = clientFor(allowInsecureTls)
        for (base in candidates) {
            val hit = runCatching {
                val req = Request.Builder()
                    .url("$base/System/Info/Public")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    val parsed = AppPrefs.json.decodeFromString<JfPublicSystemInfo>(body)
                    if (parsed.Id.isBlank()) null else parsed.copy(resolvedBaseUrl = base)
                }
            }.getOrNull()
            if (hit != null) return@withContext hit
        }
        null
    }

    suspend fun isQuickConnectEnabled(
        baseUrl: String,
        deviceId: String,
        allowInsecureTls: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanBase = JellyfinEndpointRouter.normalizeServerUrl(baseUrl)
        if (cleanBase.isEmpty()) return@withContext false
        runCatching {
            val req = Request.Builder()
                .url("$cleanBase/QuickConnect/Enabled")
                .header("Accept", "application/json")
                .header("Authorization", buildAuthorizationHeader(deviceId))
                .get()
                .build()
            clientFor(allowInsecureTls).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val raw = resp.body?.string()?.trim().orEmpty()
                raw.equals("true", ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    suspend fun initiateQuickConnect(
        baseUrl: String,
        deviceId: String,
        allowInsecureTls: Boolean = false,
        deviceName: String = defaultDeviceName()
    ): JfQuickConnectState? = withContext(Dispatchers.IO) {
        val cleanBase = JellyfinEndpointRouter.normalizeServerUrl(baseUrl)
        if (cleanBase.isEmpty()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("$cleanBase/QuickConnect/Initiate")
                .header("Accept", "application/json")
                .header("Authorization", buildAuthorizationHeader(deviceId = deviceId, deviceName = deviceName))
                .post("".toRequestBody(null))
                .build()
            clientFor(allowInsecureTls).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@use null
                AppPrefs.json.decodeFromString<JfQuickConnectState>(raw)
                    .takeIf { it.Secret.isNotBlank() && it.Code.isNotBlank() }
            }
        }.getOrNull()
    }

    suspend fun pollQuickConnect(
        baseUrl: String,
        secret: String,
        deviceId: String,
        allowInsecureTls: Boolean = false,
        deviceName: String = defaultDeviceName()
    ): JfAuthResult? = withContext(Dispatchers.IO) {
        val cleanBase = JellyfinEndpointRouter.normalizeServerUrl(baseUrl)
        if (cleanBase.isEmpty() || secret.isBlank()) return@withContext null
        runCatching {
            val authHeader = buildAuthorizationHeader(deviceId = deviceId, deviceName = deviceName)
            val connectReq = Request.Builder()
                .url("$cleanBase/QuickConnect/Connect?secret=${urlEnc(secret.trim())}")
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .get()
                .build()

            val qcState = clientFor(allowInsecureTls).newCall(connectReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                AppPrefs.json.decodeFromString<JfQuickConnectState>(body)
            } ?: return@runCatching null

            if (!qcState.Authenticated) return@runCatching null

            val exchangeJson = buildJsonObject {
                put("Secret", secret.trim())
            }.toString()
            val exchangeReq = Request.Builder()
                .url("$cleanBase/Users/AuthenticateWithQuickConnect")
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .post(exchangeJson.toRequestBody(JSON_MEDIA))
                .build()

            clientFor(allowInsecureTls).newCall(exchangeReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                AppPrefs.json.decodeFromString<JfAuthResult>(body)
                    .takeIf { it.AccessToken.isNotBlank() && it.User.Id.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * Uses an existing `bootstrapToken` from Multipass E2EE vault to self-authorize a newly paired
     * BrootLite TV's QuickConnect `code` so the new TV mints its OWN isolated per-device `AccessToken`
     * in `<1s` without sharing `DeviceId` or requiring password entry.
     */
    suspend fun authorizeQuickConnectWithBootstrapToken(
        baseUrl: String,
        code: String,
        bootstrapToken: String,
        allowInsecureTls: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanBase = JellyfinEndpointRouter.normalizeServerUrl(baseUrl)
        val cleanCode = code.trim()
        val cleanToken = sanitizeHeaderValue(bootstrapToken)
        if (cleanBase.isEmpty() || cleanCode.isEmpty() || cleanToken.isEmpty()) return@withContext false
        runCatching {
            val req = Request.Builder()
                .url("$cleanBase/QuickConnect/Authorize?code=${urlEnc(cleanCode)}")
                .header("Accept", "application/json")
                .header("X-Emby-Token", cleanToken)
                .header(
                    "Authorization",
                    buildAuthorizationHeader(
                        deviceId = "brootlite-multipass-bootstrap",
                        accessToken = cleanToken,
                        deviceName = "Multipass Vault Bootstrap"
                    )
                )
                .post("".toRequestBody(null))
                .build()
            clientFor(allowInsecureTls).newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    suspend fun mintDeviceTokenViaBootstrap(
        context: Context,
        baseUrl: String,
        bootstrapToken: String,
        allowInsecureTls: Boolean = false
    ): JfAuthResult? = withContext(Dispatchers.IO) {
        if (bootstrapToken.isBlank()) return@withContext null
        val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(context)
        val state = initiateQuickConnect(baseUrl, deviceId, allowInsecureTls) ?: return@withContext null
        val ok = authorizeQuickConnectWithBootstrapToken(
            baseUrl = baseUrl,
            code = state.Code,
            bootstrapToken = bootstrapToken,
            allowInsecureTls = allowInsecureTls
        )
        if (!ok) return@withContext null
        pollQuickConnect(baseUrl, state.Secret, deviceId, allowInsecureTls)
    }

    suspend fun authenticateByName(
        baseUrl: String,
        username: String,
        password: String,
        deviceId: String,
        allowInsecureTls: Boolean = false,
        deviceName: String = defaultDeviceName()
    ): JfAuthResult? = withContext(Dispatchers.IO) {
        val cleanBase = JellyfinEndpointRouter.normalizeServerUrl(baseUrl)
        if (cleanBase.isEmpty() || username.isBlank()) return@withContext null
        runCatching {
            val payload = buildJsonObject {
                put("Username", username.trim())
                put("Pw", password)
            }.toString()
            val req = Request.Builder()
                .url("$cleanBase/Users/AuthenticateByName")
                .header("Accept", "application/json")
                .header("Authorization", buildAuthorizationHeader(deviceId = deviceId, deviceName = deviceName))
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            clientFor(allowInsecureTls).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                AppPrefs.json.decodeFromString<JfAuthResult>(body)
                    .takeIf { it.AccessToken.isNotBlank() && it.User.Id.isNotBlank() }
            }
        }.getOrNull()
    }

    // =========================================================================
    // BROWSE, LIBRARY VIEWS, FILTERS, SERIES EPISODES & SEARCH (PER-SERVER)
    // =========================================================================

    suspend fun getViews(
        context: Context,
        profile: JellyfinServerProfile
    ): List<JfView> = withContext(Dispatchers.IO) {
        if (!profile.isAuthenticated) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val url = "$baseUrl/UserViews?userId=${urlEnc(profile.userId)}"
        val raw = authedGet(context, profile, url) ?: return@withContext emptyList()
        runCatching {
            AppPrefs.json.decodeFromString<JfItemsEnvelope<JfView>>(raw).Items
                .filter { view ->
                    val ct = view.normalizedType
                    ct != "playlists" && ct != "livetv" && ct != "books"
                }
        }.getOrDefault(emptyList())
    }

    suspend fun getResumeItems(
        context: Context,
        profile: JellyfinServerProfile,
        limit: Int = 16
    ): List<JfItem> = withContext(Dispatchers.IO) {
        if (!profile.isAuthenticated) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val url = "$baseUrl/Users/${urlEnc(profile.userId)}/Items/Resume" +
            "?Limit=${limit.coerceIn(1, 50)}&Recursive=true&MediaTypes=Video" +
            "&Fields=$LIST_FIELDS&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb"
        decodeItemsList(authedGet(context, profile, url))
    }

    suspend fun getNextUp(
        context: Context,
        profile: JellyfinServerProfile,
        limit: Int = 20
    ): List<JfItem> = withContext(Dispatchers.IO) {
        if (!profile.isAuthenticated) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val url = "$baseUrl/Shows/NextUp?userId=${urlEnc(profile.userId)}" +
            "&Limit=${limit.coerceIn(1, 50)}" +
            "&Fields=$LIST_FIELDS&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb"
        decodeItemsList(authedGet(context, profile, url))
    }

    suspend fun getLatestInView(
        context: Context,
        profile: JellyfinServerProfile,
        viewId: String,
        limit: Int = 20
    ): List<JfItem> = withContext(Dispatchers.IO) {
        if (!profile.isAuthenticated || viewId.isBlank()) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val url = "$baseUrl/Users/${urlEnc(profile.userId)}/Items/Latest" +
            "?ParentId=${urlEnc(viewId)}&Limit=${limit.coerceIn(1, 60)}" +
            "&Fields=$LIST_FIELDS&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb"
        val raw = authedGet(context, profile, url) ?: return@withContext emptyList()
        runCatching {
            val elem = AppPrefs.json.parseToJsonElement(raw)
            when (elem) {
                is JsonArray -> AppPrefs.json.decodeFromString<List<JfItem>>(raw)
                is JsonObject -> AppPrefs.json.decodeFromString<JfItemsEnvelope<JfItem>>(raw).Items
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getLibraryItems(
        context: Context,
        profile: JellyfinServerProfile,
        viewId: String,
        includeItemTypes: String? = null,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        genre: String? = null,
        year: String? = null,
        startIndex: Int = 0,
        limit: Int = 48
    ): Pair<List<JfItem>, Int> = withContext(Dispatchers.IO) {
        if (!profile.isAuthenticated) return@withContext emptyList<JfItem>() to 0
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList<JfItem>() to 0

        val sb = StringBuilder("$baseUrl/Users/${urlEnc(profile.userId)}/Items?")
        if (viewId.isNotBlank()) {
            sb.append("ParentId=").append(urlEnc(viewId)).append('&')
        }
        sb.append("Recursive=true")
        sb.append("&StartIndex=").append(startIndex.coerceAtLeast(0))
        sb.append("&Limit=").append(limit.coerceIn(1, 200))
        sb.append("&SortBy=").append(urlEnc(sortBy.ifBlank { "SortName" }))
        sb.append("&SortOrder=").append(urlEnc(sortOrder.ifBlank { "Ascending" }))
        sb.append("&Fields=").append(LIST_FIELDS)
        sb.append("&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb")
        if (!includeItemTypes.isNullOrBlank()) {
            sb.append("&IncludeItemTypes=").append(urlEnc(includeItemTypes))
        }
        if (!genre.isNullOrBlank()) {
            sb.append("&Genres=").append(urlEnc(genre))
        }
        if (!year.isNullOrBlank()) {
            sb.append("&Years=").append(urlEnc(year))
        }

        val raw = authedGet(context, profile, sb.toString()) ?: return@withContext emptyList<JfItem>() to 0
        runCatching {
            val env = AppPrefs.json.decodeFromString<JfItemsEnvelope<JfItem>>(raw)
            env.Items to env.TotalRecordCount.coerceAtLeast(env.Items.size)
        }.getOrDefault(emptyList<JfItem>() to 0)
    }

    suspend fun getGenres(
        context: Context,
        profile: JellyfinServerProfile,
        viewId: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (!profile.isAuthenticated) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val parentQuery = if (viewId.isNotBlank()) "&ParentId=${urlEnc(viewId)}" else ""
        val url = "$baseUrl/Genres?userId=${urlEnc(profile.userId)}$parentQuery&SortBy=SortName&SortOrder=Ascending"
        decodeItemsList(authedGet(context, profile, url))
            .mapNotNull { it.Name.trim().takeIf(String::isNotBlank) }
            .distinct()
    }

    suspend fun getYears(
        context: Context,
        profile: JellyfinServerProfile,
        viewId: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (!profile.isAuthenticated) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val parentQuery = if (viewId.isNotBlank()) "&ParentId=${urlEnc(viewId)}" else ""
        val url = "$baseUrl/Years?userId=${urlEnc(profile.userId)}$parentQuery&SortBy=SortName&SortOrder=Descending"
        decodeItemsList(authedGet(context, profile, url))
            .mapNotNull { it.Name.trim().takeIf(String::isNotBlank) }
            .distinct()
    }

    suspend fun getItemDetail(
        context: Context,
        profile: JellyfinServerProfile,
        itemId: String
    ): JfItem? = withContext(Dispatchers.IO) {
        val rawItemId = JellyfinIds.rawItemId(itemId)
        if (!profile.isAuthenticated || rawItemId.isEmpty()) return@withContext null
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext null
        val url = "$baseUrl/Users/${urlEnc(profile.userId)}/Items/${urlEnc(rawItemId)}?Fields=$DETAIL_FIELDS"
        val raw = authedGet(context, profile, url) ?: return@withContext null
        runCatching {
            AppPrefs.json.decodeFromString<JfItem>(raw).takeIf { it.Id.isNotBlank() }
        }.getOrNull()
    }

    suspend fun getSeasons(
        context: Context,
        profile: JellyfinServerProfile,
        seriesId: String
    ): List<JfItem> = withContext(Dispatchers.IO) {
        val rawSeriesId = JellyfinIds.rawItemId(seriesId)
        if (!profile.isAuthenticated || rawSeriesId.isEmpty()) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val url = "$baseUrl/Shows/${urlEnc(rawSeriesId)}/Seasons?userId=${urlEnc(profile.userId)}&Fields=$LIST_FIELDS"
        decodeItemsList(authedGet(context, profile, url))
    }

    suspend fun getEpisodes(
        context: Context,
        profile: JellyfinServerProfile,
        seriesId: String,
        seasonId: String? = null
    ): List<JfItem> = withContext(Dispatchers.IO) {
        val rawSeriesId = JellyfinIds.rawItemId(seriesId)
        if (!profile.isAuthenticated || rawSeriesId.isEmpty()) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val seasonParam = seasonId?.takeIf { it.isNotBlank() }?.let {
            "&seasonId=${urlEnc(JellyfinIds.rawItemId(it))}"
        }.orEmpty()
        val url = "$baseUrl/Shows/${urlEnc(rawSeriesId)}/Episodes?userId=${urlEnc(profile.userId)}$seasonParam" +
            "&Fields=$LIST_FIELDS&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb"
        decodeItemsList(authedGet(context, profile, url))
    }

    suspend fun searchServer(
        context: Context,
        profile: JellyfinServerProfile,
        query: String,
        limit: Int = 24
    ): List<JfItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (!profile.isAuthenticated || cleanQuery.isEmpty()) return@withContext emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext emptyList()
        val url = "$baseUrl/Users/${urlEnc(profile.userId)}/Items" +
            "?SearchTerm=${urlEnc(cleanQuery)}&Recursive=true" +
            "&IncludeItemTypes=Movie,Series,Episode,Video,MusicVideo" +
            "&Limit=${limit.coerceIn(1, 80)}&Fields=$LIST_FIELDS" +
            "&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb"
        decodeItemsList(authedGet(context, profile, url))
    }

    // =========================================================================
    // PLAYBACK RESOLUTION (ANDROID TV DEVICE PROFILE + INTRO SEGMENTS)
    // =========================================================================

    suspend fun resolvePlaybackSession(
        context: Context,
        profile: JellyfinServerProfile,
        itemId: String,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        startPositionMs: Long? = null,
        maxBitrateBpsOverride: Long? = null,
        forceTranscode: Boolean = false
    ): JfPlaybackSession? = withContext(Dispatchers.IO) {
        val rawItemId = JellyfinIds.rawItemId(itemId)
        if (!profile.isAuthenticated || rawItemId.isEmpty()) return@withContext null
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
        if (baseUrl.isEmpty()) return@withContext null

        val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(context)
        val authHeader = buildAuthorizationHeader(deviceId, profile.accessToken)
        val effectiveBitrateBps = (maxBitrateBpsOverride ?: profile.effectiveMaxBitrateBps)
            .coerceIn(500_000L, 250_000_000L)

        coroutineScope {
            val itemDeferred = async { getItemDetail(context, profile, rawItemId) }
            val segmentsDeferred = async { fetchSkipSegments(baseUrl, profile, rawItemId, authHeader) }

            val reqBodyJson = buildPlaybackInfoRequestBody(
                userId = profile.userId,
                maxBitrateBps = effectiveBitrateBps,
                mediaSourceId = mediaSourceId,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                startPositionMs = startPositionMs,
                forceTranscode = forceTranscode
            )

            val pbReq = Request.Builder()
                .url("$baseUrl/Items/${urlEnc(rawItemId)}/PlaybackInfo")
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .post(reqBodyJson.toRequestBody(JSON_MEDIA))
                .build()

            val pbInfo = runCatching {
                clientFor(profile.allowInsecureTls).newCall(pbReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    AppPrefs.json.decodeFromString<JfPlaybackInfoResponse>(body)
                }
            }.getOrNull() ?: return@coroutineScope null

            val source = if (!mediaSourceId.isNullOrBlank()) {
                pbInfo.MediaSources.firstOrNull { it.Id == mediaSourceId } ?: pbInfo.MediaSources.firstOrNull()
            } else {
                pbInfo.MediaSources.firstOrNull()
            } ?: return@coroutineScope null

            val playSessionId = pbInfo.PlaySessionId?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().replace("-", "")

            val headers = LinkedHashMap<String, String>().apply {
                put("Authorization", authHeader)
                putAll(source.RequiredHttpHeaders)
            }

            val (playMethod, streamUrl) = resolveStreamUrlAndMethod(
                baseUrl = baseUrl,
                rawItemId = rawItemId,
                deviceId = deviceId,
                source = source,
                forceTranscode = forceTranscode
            )

            val audioTracks = source.MediaStreams
                .filter { it.Type.equals("Audio", ignoreCase = true) }
                .map { s ->
                    val ch = s.Channels ?: 2
                    val chLabel = when (ch) {
                        8 -> "7.1"
                        6 -> "5.1"
                        2 -> "2.0"
                        1 -> "Mono"
                        else -> "${ch}ch"
                    }
                    val lang = s.Language?.takeIf { it.isNotBlank() } ?: "und"
                    val label = s.DisplayTitle?.takeIf { it.isNotBlank() }
                        ?: listOfNotNull(
                            s.Title?.takeIf { it.isNotBlank() },
                            lang.uppercase(Locale.US),
                            s.Codec.uppercase(Locale.US).takeIf { it.isNotBlank() },
                            chLabel
                        ).joinToString(" · ")
                    JfAudioStream(
                        index = s.Index,
                        language = lang,
                        label = label,
                        codec = s.Codec.lowercase(Locale.US),
                        channels = ch,
                        isDefault = s.IsDefault || s.Index == source.DefaultAudioStreamIndex
                    )
                }

            val subtitleTracks = source.MediaStreams
                .filter { it.Type.equals("Subtitle", ignoreCase = true) }
                .map { s ->
                    val codecLower = s.Codec.lowercase(Locale.US)
                    val isImage = codecLower in IMAGE_SUB_CODECS
                    val delivery = s.DeliveryMethod?.takeIf { it.isNotBlank() }
                        ?: if (!isImage && s.IsExternal) "External" else "Embed"
                    val extUrl = resolveExternalSubtitleUrl(
                        baseUrl = baseUrl,
                        rawItemId = rawItemId,
                        mediaSourceId = source.Id,
                        stream = s,
                        deliveryMethod = delivery,
                        isImage = isImage
                    )
                    val lang = s.Language?.takeIf { it.isNotBlank() } ?: "und"
                    val label = s.DisplayTitle?.takeIf { it.isNotBlank() }
                        ?: buildList {
                            s.Title?.takeIf { it.isNotBlank() }?.let(::add)
                            add(lang.uppercase(Locale.US))
                            if (s.IsForced) add("Forced")
                            if (isImage) add(codecLower.uppercase(Locale.US))
                        }.joinToString(" · ")
                    JfSubtitleStream(
                        index = s.Index,
                        language = lang,
                        label = label,
                        codec = codecLower,
                        deliveryMethod = delivery,
                        externalUrl = extUrl,
                        mimeType = subtitleMimeType(codecLower),
                        isDefault = s.IsDefault || s.Index == source.DefaultSubtitleStreamIndex,
                        isForced = s.IsForced,
                        isImageBased = isImage
                    )
                }

            val itemDetail = itemDeferred.await()
            val skipSegments = segmentsDeferred.await()

            val videoStream = source.MediaStreams.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
            val summaryBadge = buildVideoSummaryBadge(videoStream, playMethod)
            val resumeMs = startPositionMs
                ?: itemDetail?.resumePositionMs
                ?: 0L
            val runtimeMs = JellyfinIds.ticksToMs(source.RunTimeTicks)
                .takeIf { it > 0L }
                ?: itemDetail?.runtimeMs
                ?: 0L

            JfPlaybackSession(
                streamUrl = streamUrl,
                headers = headers,
                playMethod = playMethod,
                playSessionId = playSessionId,
                mediaSourceId = source.Id,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                chapters = itemDetail?.Chapters.orEmpty(),
                introStartMs = skipSegments.introStartMs,
                introEndMs = skipSegments.introEndMs,
                creditsStartMs = skipSegments.creditsStartMs,
                resumePositionMs = resumeMs,
                runtimeMs = runtimeMs,
                ignoreSsl = profile.allowInsecureTls,
                selectedAudioStreamIndex = audioStreamIndex ?: source.DefaultAudioStreamIndex,
                selectedSubtitleStreamIndex = subtitleStreamIndex ?: source.DefaultSubtitleStreamIndex,
                videoSummaryLabel = summaryBadge
            )
        }
    }

    // =========================================================================
    // FIRE-AND-FORGET SESSION REPORTING (UN-CANCELLABLE BACKGROUND SCOPE)
    // =========================================================================

    fun reportPlaybackStart(
        context: Context,
        profile: JellyfinServerProfile,
        itemId: String,
        session: JfPlaybackSession,
        positionMs: Long = 0L,
        isPaused: Boolean = false
    ) {
        if (!profile.isAuthenticated) return
        val appCtx = context.applicationContext
        val rawItemId = JellyfinIds.rawItemId(itemId)
        reportScope.launch {
            runCatching {
                val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
                if (baseUrl.isEmpty()) return@launch
                val body = buildJsonObject {
                    put("ItemId", rawItemId)
                    put("MediaSourceId", session.mediaSourceId)
                    put("PlaySessionId", session.playSessionId)
                    put("PlayMethod", session.playMethod)
                    put("PositionTicks", JellyfinIds.msToTicks(positionMs))
                    put("IsPaused", isPaused)
                    put("CanSeek", true)
                    session.selectedAudioStreamIndex?.let { put("AudioStreamIndex", it) }
                    session.selectedSubtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
                }.toString()
                authedPostNoResponse(appCtx, profile, "$baseUrl/Sessions/Playing", body)
            }
        }
    }

    fun reportPlaybackProgress(
        context: Context,
        profile: JellyfinServerProfile,
        itemId: String,
        session: JfPlaybackSession,
        positionMs: Long,
        isPaused: Boolean,
        eventName: String = "timeupdate"
    ) {
        if (!profile.isAuthenticated) return
        val appCtx = context.applicationContext
        val rawItemId = JellyfinIds.rawItemId(itemId)
        reportScope.launch {
            runCatching {
                val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
                if (baseUrl.isEmpty()) return@launch
                val body = buildJsonObject {
                    put("ItemId", rawItemId)
                    put("MediaSourceId", session.mediaSourceId)
                    put("PlaySessionId", session.playSessionId)
                    put("PlayMethod", session.playMethod)
                    put("PositionTicks", JellyfinIds.msToTicks(positionMs))
                    put("IsPaused", isPaused)
                    put("EventName", eventName)
                    put("CanSeek", true)
                    session.selectedAudioStreamIndex?.let { put("AudioStreamIndex", it) }
                    session.selectedSubtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
                }.toString()
                authedPostNoResponse(appCtx, profile, "$baseUrl/Sessions/Playing/Progress", body)
            }
        }
    }

    fun reportPlaybackStopped(
        context: Context,
        profile: JellyfinServerProfile,
        itemId: String,
        session: JfPlaybackSession,
        positionMs: Long
    ) {
        if (!profile.isAuthenticated) return
        val appCtx = context.applicationContext
        val rawItemId = JellyfinIds.rawItemId(itemId)
        reportScope.launch {
            runCatching {
                val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
                if (baseUrl.isEmpty()) return@launch
                val body = buildJsonObject {
                    put("ItemId", rawItemId)
                    put("MediaSourceId", session.mediaSourceId)
                    put("PlaySessionId", session.playSessionId)
                    put("PositionTicks", JellyfinIds.msToTicks(positionMs))
                }.toString()
                authedPostNoResponse(appCtx, profile, "$baseUrl/Sessions/Playing/Stopped", body)

                if (!session.playMethod.equals("DirectPlay", ignoreCase = true) &&
                    session.playSessionId.isNotBlank()
                ) {
                    deleteActiveEncodingInternal(appCtx, profile, baseUrl, session.playSessionId)
                }
            }
        }
    }

    fun stopActiveEncoding(
        context: Context,
        profile: JellyfinServerProfile,
        playSessionId: String
    ) {
        if (!profile.isAuthenticated || playSessionId.isBlank()) return
        val appCtx = context.applicationContext
        reportScope.launch {
            runCatching {
                val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
                if (baseUrl.isEmpty()) return@launch
                deleteActiveEncodingInternal(appCtx, profile, baseUrl, playSessionId)
            }
        }
    }

    // =========================================================================
    // INTERNAL HELPERS & ANDROID TV DEVICE PROFILE BUILDER
    // =========================================================================

    private val IMAGE_SUB_CODECS = setOf("pgssub", "pgs", "dvdsub", "vobsub", "dvbsub", "hdmv_pgs_subtitle")

    private data class SkipTimestamps(
        val introStartMs: Long? = null,
        val introEndMs: Long? = null,
        val creditsStartMs: Long? = null
    )

    private fun fetchSkipSegments(
        baseUrl: String,
        profile: JellyfinServerProfile,
        rawItemId: String,
        authHeader: String
    ): SkipTimestamps {
        val client = clientFor(profile.allowInsecureTls)
        if (profile.serverId !in legacyIntroEndpointServers) {
            val segmentsUrl = "$baseUrl/MediaSegments/${urlEnc(rawItemId)}?includeSegmentTypes=Intro,Outro,Recap"
            val req = Request.Builder()
                .url(segmentsUrl)
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .get()
                .build()
            val nativeHit = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 404) {
                        if (profile.serverId.isNotBlank()) {
                            legacyIntroEndpointServers.add(profile.serverId)
                        }
                        return@use null
                    }
                    if (!resp.isSuccessful) return@use SkipTimestamps()
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use SkipTimestamps()
                    val parsed = AppPrefs.json.decodeFromString<JfMediaSegmentsResponse>(body)
                    val intro = parsed.Items.firstOrNull {
                        it.Type.equals("Intro", ignoreCase = true) || it.Type.equals("Recap", ignoreCase = true)
                    }
                    val outro = parsed.Items.firstOrNull {
                        it.Type.equals("Outro", ignoreCase = true)
                    }
                    SkipTimestamps(
                        introStartMs = intro?.let { JellyfinIds.ticksToMs(it.StartTicks) },
                        introEndMs = intro?.let { JellyfinIds.ticksToMs(it.EndTicks) }?.takeIf { it > 0L },
                        creditsStartMs = outro?.let { JellyfinIds.ticksToMs(it.StartTicks) }?.takeIf { it > 0L }
                    )
                }
            }.getOrNull()
            if (nativeHit != null) return nativeHit
        }

        // Fallback to Jellyfin 10.8 / 10.9 Intro Skipper plugin (`/Episode/{id}/IntroTimestamps?mode=v1`)
        return runCatching {
            val pluginReq = Request.Builder()
                .url("$baseUrl/Episode/${urlEnc(rawItemId)}/IntroTimestamps?mode=v1")
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .get()
                .build()
            client.newCall(pluginReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use SkipTimestamps()
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@use SkipTimestamps()
                val obj = AppPrefs.json.parseToJsonElement(raw).jsonObject

                val introNode = (obj["Introduction"] as? JsonObject) ?: obj
                val creditsNode = obj["Credits"] as? JsonObject

                var iStart: Long? = null
                var iEnd: Long? = null
                if (introNode["Valid"]?.jsonPrimitive?.booleanOrNull == true) {
                    val sSec = introNode["IntroStart"]?.jsonPrimitive?.doubleOrNull
                    val eSec = introNode["IntroEnd"]?.jsonPrimitive?.doubleOrNull
                    if (sSec != null && eSec != null && eSec > sSec) {
                        iStart = (sSec * 1000.0).toLong()
                        iEnd = (eSec * 1000.0).toLong()
                    }
                }
                var cStart: Long? = null
                if (creditsNode?.get("Valid")?.jsonPrimitive?.booleanOrNull == true) {
                    val cs = creditsNode["IntroStart"]?.jsonPrimitive?.doubleOrNull
                        ?: creditsNode["CreditsStart"]?.jsonPrimitive?.doubleOrNull
                    if (cs != null && cs > 0.0) {
                        cStart = (cs * 1000.0).toLong()
                    }
                }
                SkipTimestamps(introStartMs = iStart, introEndMs = iEnd, creditsStartMs = cStart)
            }
        }.getOrDefault(SkipTimestamps())
    }

    private fun resolveStreamUrlAndMethod(
        baseUrl: String,
        rawItemId: String,
        deviceId: String,
        source: JfMediaSource,
        forceTranscode: Boolean
    ): Pair<String, String> {
        if (!forceTranscode && source.SupportsDirectPlay) {
            val ext = source.Container
                ?.substringBefore(',')
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?: "mkv"
            val tagPart = source.ETag?.takeIf { it.isNotBlank() }?.let { "&Tag=${urlEnc(it)}" }.orEmpty()
            val url = "$baseUrl/Videos/${urlEnc(rawItemId)}/stream.$ext" +
                "?Static=true&mediaSourceId=${urlEnc(source.Id)}&deviceId=${urlEnc(deviceId)}$tagPart"
            return "DirectPlay" to url
        }

        if (!source.TranscodingUrl.isNullOrBlank()) {
            val fullUrl = resolveRelativeOrAbsoluteUrl(baseUrl, source.TranscodingUrl)
            val isRemux = source.SupportsDirectStream &&
                (source.TranscodingUrl.contains("VideoCodec=copy", ignoreCase = true) ||
                    source.TranscodingUrl.contains("allowVideoStreamCopy=true", ignoreCase = true) && !forceTranscode)
            val method = if (isRemux && !forceTranscode) "DirectStream" else "Transcode"
            return method to fullUrl
        }

        if (!source.DirectStreamUrl.isNullOrBlank()) {
            return "DirectStream" to resolveRelativeOrAbsoluteUrl(baseUrl, source.DirectStreamUrl)
        }

        val fallbackUrl = "$baseUrl/Videos/${urlEnc(rawItemId)}/stream?Static=true&mediaSourceId=${urlEnc(source.Id)}"
        return "DirectPlay" to fallbackUrl
    }

    private fun resolveExternalSubtitleUrl(
        baseUrl: String,
        rawItemId: String,
        mediaSourceId: String,
        stream: JfMediaStream,
        deliveryMethod: String,
        isImage: Boolean
    ): String? {
        if (isImage) return null
        if (!deliveryMethod.equals("External", ignoreCase = true) && !stream.SupportsExternalStream && !stream.IsExternal) {
            return null
        }
        if (!stream.DeliveryUrl.isNullOrBlank()) {
            return resolveRelativeOrAbsoluteUrl(baseUrl, stream.DeliveryUrl)
        }
        val ext = when (stream.Codec.lowercase(Locale.US)) {
            "subrip", "srt" -> "srt"
            "vtt", "webvtt" -> "vtt"
            "ass" -> "ass"
            "ssa" -> "ssa"
            else -> "srt"
        }
        return "$baseUrl/Videos/${urlEnc(rawItemId)}/${urlEnc(mediaSourceId)}/Subtitles/${stream.Index}/0/Stream.$ext"
    }

    private fun subtitleMimeType(codecLower: String): String = when (codecLower) {
        "vtt", "webvtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "pgssub", "pgs", "hdmv_pgs_subtitle" -> "application/pgs"
        "dvdsub", "vobsub" -> "application/vobsub"
        "dvbsub" -> "application/dvbsubs"
        else -> "application/x-subrip"
    }

    private fun buildVideoSummaryBadge(video: JfMediaStream?, playMethod: String): String {
        val parts = mutableListOf<String>()
        val w = video?.Width ?: 0
        val h = video?.Height ?: 0
        when {
            w >= 3800 || h >= 2100 -> parts.add("4K")
            w >= 1900 || h >= 1060 -> parts.add("1080p")
            w >= 1260 || h >= 700 -> parts.add("720p")
            h > 0 -> parts.add("${h}p")
        }
        video?.Codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)?.let(parts::add)
        val range = video?.VideoRangeType ?: video?.VideoRange
        if (!range.isNullOrBlank() && !range.equals("SDR", ignoreCase = true)) {
            parts.add(range)
        }
        parts.add(playMethod)
        return parts.joinToString(" · ")
    }

    private fun buildPlaybackInfoRequestBody(
        userId: String,
        maxBitrateBps: Long,
        mediaSourceId: String?,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        startPositionMs: Long?,
        forceTranscode: Boolean
    ): String {
        val allAudioCodecs = "aac,ac3,eac3,dts,dca,truehd,flac,alac,mp3,opus,vorbis,pcm_s16le,pcm_s24le"
        return buildJsonObject {
            put("UserId", userId)
            put("MaxStreamingBitrate", maxBitrateBps)
            put("EnableDirectPlay", !forceTranscode)
            put("EnableDirectStream", !forceTranscode)
            put("EnableTranscoding", true)
            put("AllowVideoStreamCopy", !forceTranscode)
            put("AllowAudioStreamCopy", true)
            put("AutoOpenLiveStream", true)
            if (!mediaSourceId.isNullOrBlank()) {
                put("MediaSourceId", mediaSourceId)
            }
            if (audioStreamIndex != null) {
                put("AudioStreamIndex", audioStreamIndex)
            }
            if (subtitleStreamIndex != null) {
                put("SubtitleStreamIndex", subtitleStreamIndex)
            }
            if (startPositionMs != null && startPositionMs > 0L) {
                put("StartTimeTicks", JellyfinIds.msToTicks(startPositionMs))
            }
            put(
                "DeviceProfile",
                buildJsonObject {
                    put("Name", "BrootLite Android TV")
                    put("MaxStreamingBitrate", maxBitrateBps)
                    put("MaxStaticBitrate", maxBitrateBps)
                    put("MusicStreamingTranscodingBitrate", 384_000)
                    put(
                        "DirectPlayProfiles",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("Container", "mkv,webm")
                                    put("Type", "Video")
                                    // Strictly excludes av1 so Jellyfin hardware/software transcodes AV1 cleanly
                                    put("VideoCodec", "h264,hevc,vp8,vp9,mpeg2video,mpeg4,vc1")
                                    put("AudioCodec", allAudioCodecs)
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("Container", "mp4,m4v,mov")
                                    put("Type", "Video")
                                    put("VideoCodec", "h264,hevc,vp8,vp9,mpeg4")
                                    put("AudioCodec", allAudioCodecs)
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("Container", "ts,mpegts,m2ts,avi")
                                    put("Type", "Video")
                                    put("VideoCodec", "h264,hevc,mpeg2video,mpeg4,vc1")
                                    put("AudioCodec", allAudioCodecs)
                                }
                            )
                        }
                    )
                    put(
                        "TranscodingProfiles",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("Container", "ts")
                                    put("Type", "Video")
                                    put("VideoCodec", "hevc,h264")
                                    put("AudioCodec", "aac,eac3,ac3")
                                    put("Protocol", "hls")
                                    put("Context", "Streaming")
                                    put("MaxAudioChannels", "8")
                                    put("MinSegments", 1)
                                    put("BreakOnNonKeyFrames", true)
                                }
                            )
                        }
                    )
                    put(
                        "CodecProfiles",
                        buildJsonArray {
                            // Force Hi10P 10-bit H.264 anime to transcode cleanly
                            add(
                                buildJsonObject {
                                    put("Type", "Video")
                                    put("Codec", "h264")
                                    put(
                                        "Conditions",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("Condition", "LessThanEqual")
                                                    put("Property", "VideoBitDepth")
                                                    put("Value", "8")
                                                    put("IsRequired", false)
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                            // Direct Play HDR10/HDR10+/HLG & dual-layer DV fallback; tone-map single-layer DV Profile 5
                            add(
                                buildJsonObject {
                                    put("Type", "Video")
                                    put("Codec", "hevc")
                                    put(
                                        "Conditions",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("Condition", "EqualsAny")
                                                    put("Property", "VideoRangeType")
                                                    put(
                                                        "Value",
                                                        "SDR|HDR10|HDR10Plus|HLG|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR"
                                                    )
                                                    put("IsRequired", false)
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put(
                        "SubtitleProfiles",
                        buildJsonArray {
                            for (fmt in listOf("srt", "subrip", "vtt", "ass", "ssa")) {
                                add(
                                    buildJsonObject {
                                        put("Format", fmt)
                                        put("Method", "External")
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("Format", fmt)
                                        put("Method", "Embed")
                                    }
                                )
                            }
                            for (imgFmt in listOf("pgssub", "dvdsub", "dvbsub")) {
                                add(
                                    buildJsonObject {
                                        put("Format", imgFmt)
                                        put("Method", "Embed")
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("Format", imgFmt)
                                        put("Method", "Encode")
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }.toString()
    }

    private fun resolveRelativeOrAbsoluteUrl(baseUrl: String, pathOrUrl: String): String {
        val trimmed = pathOrUrl.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "${baseUrl.trimEnd('/')}/${trimmed.trimStart('/')}"
        }
    }

    private fun decodeItemsList(rawJson: String?): List<JfItem> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            AppPrefs.json.decodeFromString<JfItemsEnvelope<JfItem>>(rawJson).Items
        }.getOrDefault(emptyList())
    }

    private fun authedGet(
        context: Context,
        profile: JellyfinServerProfile,
        url: String
    ): String? {
        return runCatching {
            val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(context)
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", buildAuthorizationHeader(deviceId, profile.accessToken))
                .get()
                .build()
            clientFor(profile.allowInsecureTls).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    private fun authedPostNoResponse(
        context: Context,
        profile: JellyfinServerProfile,
        url: String,
        bodyJson: String? = null
    ): Boolean {
        return runCatching {
            val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(context)
            val body = bodyJson?.toRequestBody(JSON_MEDIA) ?: EMPTY_JSON_BODY
            val req = Request.Builder()
                .url(url)
                .header("Authorization", buildAuthorizationHeader(deviceId, profile.accessToken))
                .post(body)
                .build()
            clientFor(profile.allowInsecureTls).newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    private fun deleteActiveEncodingInternal(
        context: Context,
        profile: JellyfinServerProfile,
        baseUrl: String,
        playSessionId: String
    ) {
        runCatching {
            val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(context)
            val url = "$baseUrl/Videos/ActiveEncodings?deviceId=${urlEnc(deviceId)}&playSessionId=${urlEnc(playSessionId)}"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", buildAuthorizationHeader(deviceId, profile.accessToken))
                .delete()
                .build()
            clientFor(profile.allowInsecureTls).newCall(req).execute().close()
        }
    }
}
