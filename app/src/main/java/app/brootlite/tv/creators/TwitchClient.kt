package app.brootlite.tv.creators

import app.brootlite.tv.BrootLiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Pure-Kotlin Twitch client for BrootLite:
 * - Official Twitch TV OAuth2 Device Code flow (`https://www.twitch.tv/activate`) with single-use
 *   `refresh_token` rotation safety under [Mutex].
 * - Browsable feeds via `https://gql.twitch.tv/gql`: Top Live, Top Categories, Category Streams,
 *   Followed Live (OAuth + local `followedChannelLogins`), Channel Details + VODs, and Search.
 * - Ad-free Live & VOD HLS stream resolution (`usher.ttvnw.net`) paired with [TwitchAdFreeHlsParserFactory].
 *
 * Security invariant: NEVER logs OAuth tokens, refresh tokens, device codes, or Authorization headers!
 */
object TwitchClient {

    const val WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    const val TV_CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa"
    const val ACTIVATION_URL = "https://www.twitch.tv/activate"

    private const val GQL_URL = "https://gql.twitch.tv/gql"
    private const val OAUTH_DEVICE_URL = "https://id.twitch.tv/oauth2/device"
    private const val OAUTH_TOKEN_URL = "https://id.twitch.tv/oauth2/token"
    private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    private const val EXPIRY_BUFFER_MS = 60_000L

    val DEFAULT_SCOPES = listOf("user:read:follows", "user:read:subscriptions")

    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private const val PLAYBACK_TOKEN_QUERY =
        "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) { " +
            "streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) { value signature } " +
            "videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) { value signature } " +
            "}"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val tokenMutex = Mutex()
    // Maps both prior & rotated single-use refresh tokens -> latest TwitchOAuthTokens so concurrent
    // callers never re-submit an already-consumed Twitch refresh_token!
    private val rotatedTokenCache = LinkedHashMap<String, TwitchOAuthTokens>(8, 0.75f, true)

    private val httpClient: OkHttpClient
        get() = BrootLiteApp.sharedHttpClient

    // =========================================================================
    // OAuth2 Device Code Flow (`https://www.twitch.tv/activate`)
    // =========================================================================

    suspend fun requestDeviceCode(
        scopes: List<String> = DEFAULT_SCOPES,
        clientId: String = TV_CLIENT_ID
    ): TwitchDeviceCode = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scopes", scopes.joinToString(" "))
            .build()

        val req = Request.Builder()
            .url(OAUTH_DEVICE_URL)
            .header("User-Agent", WEB_UA)
            .post(form)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Twitch device code HTTP ${resp.code}")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val devCode = root["device_code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IOException("Missing device_code in Twitch OAuth response")
            val userCode = root["user_code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IOException("Missing user_code in Twitch OAuth response")
            val rawUri = root["verification_uri"]?.jsonPrimitive?.contentOrNull ?: ACTIVATION_URL
            val cleanUri = if (rawUri.contains("twitch.tv/activate")) ACTIVATION_URL else rawUri
            val interval = root["interval"]?.jsonPrimitive?.intOrNull?.coerceIn(2, 60) ?: 5
            val expiresIn = root["expires_in"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(60) ?: 1800

            TwitchDeviceCode(
                deviceCode = devCode,
                userCode = userCode,
                verificationUri = cleanUri,
                intervalSec = interval,
                expiresInSec = expiresIn,
                clientId = clientId
            )
        }
    }

    suspend fun pollDeviceToken(
        deviceCode: String,
        clientId: String = TV_CLIENT_ID
    ): TwitchOAuthTokens? = when (val res = pollDeviceTokenDetailed(deviceCode, clientId)) {
        is TwitchDevicePollResult.Authorized -> res.tokens
        is TwitchDevicePollResult.Pending,
        is TwitchDevicePollResult.SlowDown -> null
        is TwitchDevicePollResult.Error -> throw IOException("Twitch sign-in failed: ${res.message}")
    }

    suspend fun pollDeviceTokenDetailed(
        deviceCode: String,
        clientId: String = TV_CLIENT_ID
    ): TwitchDevicePollResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT_TYPE)
            .build()

        val req = Request.Builder()
            .url(OAUTH_TOKEN_URL)
            .header("User-Agent", WEB_UA)
            .post(form)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                ?: return@withContext TwitchDevicePollResult.Error(resp.code, "Invalid OAuth response")

            val msg = root["message"]?.jsonPrimitive?.contentOrNull
            if (!resp.isSuccessful || msg != null) {
                return@withContext when (msg) {
                    "authorization_pending" -> TwitchDevicePollResult.Pending
                    "slow_down" -> TwitchDevicePollResult.SlowDown(5)
                    else -> TwitchDevicePollResult.Error(resp.code, msg ?: "HTTP ${resp.code}")
                }
            }

            val access = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@withContext TwitchDevicePollResult.Error(resp.code, "Missing access_token")
            val refresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(60L) ?: 14_400L
            val scopes = (root["scope"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

            val tokens = TwitchOAuthTokens(
                accessToken = access,
                refreshToken = refresh,
                expiresInSec = expiresIn,
                clientId = clientId,
                scopes = scopes,
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
            )
            if (refresh.isNotBlank()) {
                tokenMutex.withLock { cacheRotatedTokensLocked(refresh, refresh, tokens) }
            }
            TwitchDevicePollResult.Authorized(tokens)
        }
    }

    suspend fun awaitDeviceToken(deviceCodeInfo: TwitchDeviceCode): TwitchOAuthTokens {
        var intervalMs = deviceCodeInfo.intervalSec.coerceAtLeast(3) * 1000L
        val deadlineMs = System.currentTimeMillis() + deviceCodeInfo.expiresInSec * 1000L
        val cid = deviceCodeInfo.clientId.ifBlank { TV_CLIENT_ID }

        while (coroutineContext.isActive && System.currentTimeMillis() < deadlineMs) {
            delay(intervalMs)
            when (val res = pollDeviceTokenDetailed(deviceCodeInfo.deviceCode, cid)) {
                is TwitchDevicePollResult.Authorized -> return res.tokens
                is TwitchDevicePollResult.Pending -> Unit
                is TwitchDevicePollResult.SlowDown -> intervalMs += res.extraDelaySec * 1000L
                is TwitchDevicePollResult.Error -> throw IOException("Twitch sign-in error: ${res.message}")
            }
        }
        throw IOException("Twitch activation code expired")
    }

    /**
     * Refreshes a Twitch OAuth `access_token` safely.
     * Because Twitch `refresh_token`s are single-use (rotated on every refresh), this method
     * serializes refreshes via [tokenMutex] and maps both the old and new `refresh_token` in RAM.
     */
    suspend fun refreshAccessToken(
        refreshToken: String,
        clientId: String = TV_CLIENT_ID
    ): TwitchOAuthTokens = tokenMutex.withLock {
        val effClientId = clientId.ifBlank { TV_CLIENT_ID }
        val now = System.currentTimeMillis()

        val cached = rotatedTokenCache[refreshToken]
        if (cached != null && cached.expiresAtMs > now + EXPIRY_BUFFER_MS) {
            return@withLock cached
        }
        val latestRefreshToken = cached?.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken

        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", latestRefreshToken)
                .add("client_id", effClientId)
                .build()

            val req = Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .header("User-Agent", WEB_UA)
                .post(form)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    rotatedTokenCache.remove(refreshToken)
                    throw IOException("Twitch token refresh HTTP ${resp.code}")
                }
                val root = json.parseToJsonElement(bodyStr).jsonObject
                val access = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Missing access_token in Twitch refresh response")
                val rotatedRefresh = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: latestRefreshToken
                val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(60L) ?: 14_400L
                val scopes = (root["scope"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

                val tokens = TwitchOAuthTokens(
                    accessToken = access,
                    refreshToken = rotatedRefresh,
                    expiresInSec = expiresIn,
                    clientId = effClientId,
                    scopes = scopes,
                    expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
                )
                cacheRotatedTokensLocked(refreshToken, rotatedRefresh, tokens)
                tokens
            }
        }
    }

    suspend fun getValidAccessToken(authContext: TwitchAuthContext?): String? {
        if (authContext == null || !authContext.isSignedIn) return null
        val now = System.currentTimeMillis()
        if (!authContext.accessToken.isNullOrBlank() && authContext.expiresAtMs > now + EXPIRY_BUFFER_MS) {
            return authContext.accessToken
        }
        val refresh = authContext.refreshToken?.takeIf { it.isNotBlank() }
            ?: return authContext.accessToken?.takeIf { it.isNotBlank() }

        val refreshed = runCatching {
            refreshAccessToken(refresh, authContext.clientId ?: TV_CLIENT_ID)
        }.getOrNull() ?: return authContext.accessToken?.takeIf { it.isNotBlank() }

        // Always invoke onTokenRefreshed immediately when Twitch rotates single-use refresh_token!
        if (refreshed.accessToken != authContext.accessToken || refreshed.refreshToken != authContext.refreshToken) {
            runCatching { authContext.onTokenRefreshed?.invoke(refreshed) }
        }
        return refreshed.accessToken
    }

    // =========================================================================
    // Browsable Feeds (Top Live, Categories, Followed, Channel VODs, Search)
    // =========================================================================

    suspend fun getTopLiveStreams(
        limit: Int = 30,
        cursor: String? = null
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 100)
        val afterClause = cursor?.let { """, after: ${jsonQuote(it)}""" }.orEmpty()
        val query = """
            query {
              streams(first: $safeLimit$afterClause) {
                edges {
                  cursor
                  node {
                    id
                    title
                    viewersCount
                    createdAt
                    previewImageURL(width: 640, height: 360)
                    game { id name slug }
                    broadcaster { id login displayName profileImageURL(width: 150) }
                  }
                }
                pageInfo { hasNextPage }
              }
            }
        """.trimIndent()

        val data = executeGqlQuery(query) ?: return@withContext CreatorFeedPage(emptyList())
        val streamsConn = data["streams"] as? JsonObject ?: return@withContext CreatorFeedPage(emptyList())
        parseStreamsConnection(streamsConn)
    }

    suspend fun getTopCategories(
        limit: Int = 30,
        cursor: String? = null
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 100)
        val afterClause = cursor?.let { """, after: ${jsonQuote(it)}""" }.orEmpty()
        val query = """
            query {
              games(first: $safeLimit$afterClause) {
                edges {
                  cursor
                  node {
                    id
                    name
                    slug
                    boxArtURL(width: 285, height: 380)
                    viewersCount
                  }
                }
                pageInfo { hasNextPage }
              }
            }
        """.trimIndent()

        val data = executeGqlQuery(query) ?: return@withContext CreatorFeedPage(emptyList())
        val gamesConn = data["games"] as? JsonObject ?: return@withContext CreatorFeedPage(emptyList())
        val edges = gamesConn["edges"] as? JsonArray ?: return@withContext CreatorFeedPage(emptyList())
        val hasNext = (gamesConn["pageInfo"] as? JsonObject)?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true
        var lastCursor: String? = null

        val categories = edges.mapNotNull { el ->
            val edge = el as? JsonObject ?: return@mapNotNull null
            edge["cursor"]?.jsonPrimitive?.contentOrNull?.let { lastCursor = it }
            val node = edge["node"] as? JsonObject ?: return@mapNotNull null
            val id = node["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = node["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val slug = node["slug"]?.jsonPrimitive?.contentOrNull ?: name.lowercase().replace(" ", "-")
            val boxArt = node["boxArtURL"]?.jsonPrimitive?.contentOrNull
            val viewers = node["viewersCount"]?.jsonPrimitive?.longOrNull
            CreatorCategory(
                id = id,
                platform = CreatorPlatform.TWITCH,
                name = name,
                slug = slug,
                boxArtUrl = boxArt,
                viewerCount = viewers,
                viewerCountText = viewers?.let { "${formatCount(it)} viewers" }
            )
        }

        CreatorFeedPage(
            items = emptyList(),
            categories = categories,
            nextCursor = if (hasNext) lastCursor else null
        )
    }

    suspend fun getCategoryStreams(
        categorySlugOrName: String,
        limit: Int = 30,
        cursor: String? = null
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val clean = categorySlugOrName.trim()
        if (clean.isEmpty()) return@withContext CreatorFeedPage(emptyList())
        val safeLimit = limit.coerceIn(1, 100)
        val afterClause = cursor?.let { """, after: ${jsonQuote(it)}""" }.orEmpty()
        val selector = if (clean.contains(" ") || clean.any { it.isUpperCase() }) {
            "name: ${jsonQuote(clean)}"
        } else {
            "slug: ${jsonQuote(clean)}"
        }

        val query = """
            query {
              game($selector) {
                id
                name
                slug
                streams(first: $safeLimit$afterClause) {
                  edges {
                    cursor
                    node {
                      id
                      title
                      viewersCount
                      createdAt
                      previewImageURL(width: 640, height: 360)
                      game { id name slug }
                      broadcaster { id login displayName profileImageURL(width: 150) }
                    }
                  }
                  pageInfo { hasNextPage }
                }
              }
            }
        """.trimIndent()

        val data = executeGqlQuery(query) ?: return@withContext CreatorFeedPage(emptyList())
        val gameObj = data["game"] as? JsonObject ?: return@withContext CreatorFeedPage(emptyList())
        val streamsConn = gameObj["streams"] as? JsonObject ?: return@withContext CreatorFeedPage(emptyList())
        parseStreamsConnection(streamsConn, fallbackCategoryName = gameObj["name"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * Returns live streams from the signed-in Twitch user's followed channels AND/OR locally
     * followed channel logins (`followedChannelLogins` when anonymous or merged).
     */
    suspend fun getFollowedLiveStreams(
        authContext: TwitchAuthContext? = null,
        followedChannelLogins: List<String> = emptyList()
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, CreatorVideoCard>()

        val accessToken = getValidAccessToken(authContext)
        if (!accessToken.isNullOrBlank()) {
            val oauthQuery = """
                query {
                  currentUser {
                    id
                    login
                    followedLiveUsers(first: 60) {
                      nodes {
                        id
                        login
                        displayName
                        profileImageURL(width: 150)
                        stream {
                          id
                          title
                          viewersCount
                          createdAt
                          previewImageURL(width: 640, height: 360)
                          game { id name slug }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val data = executeGqlQuery(
                query = oauthQuery,
                accessToken = accessToken,
                clientId = authContext?.clientId ?: TV_CLIENT_ID
            )
            val nodes = (((data?.get("currentUser") as? JsonObject)
                ?.get("followedLiveUsers") as? JsonObject)
                ?.get("nodes") as? JsonArray)
            if (nodes != null) {
                for (el in nodes) {
                    val userNode = el as? JsonObject ?: continue
                    parseLiveCardFromUserNode(userNode)?.let { merged[it.channelId.lowercase()] = it }
                }
            }
        }

        val cleanLocal = followedChannelLogins
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !merged.containsKey(it) }
            .distinct()

        for (chunk in cleanLocal.chunked(35)) {
            val loginsJson = chunk.joinToString(", ") { jsonQuote(it) }
            val localQuery = """
                query {
                  users(logins: [$loginsJson]) {
                    id
                    login
                    displayName
                    profileImageURL(width: 150)
                    stream {
                      id
                      title
                      viewersCount
                      createdAt
                      previewImageURL(width: 640, height: 360)
                      game { id name slug }
                    }
                  }
                }
            """.trimIndent()

            val data = executeGqlQuery(localQuery) ?: continue
            val usersArr = data["users"] as? JsonArray ?: continue
            for (el in usersArr) {
                val userNode = el as? JsonObject ?: continue
                parseLiveCardFromUserNode(userNode)?.let { merged[it.channelId.lowercase()] = it }
            }
        }

        val sorted = merged.values.sortedByDescending { it.viewerOrViewCount ?: 0L }
        CreatorFeedPage(items = sorted)
    }

    /**
     * Fetches a Twitch channel's header, current live status, and past broadcast VODs (`ARCHIVE`).
     */
    suspend fun getChannelDetailsAndVods(
        channelLogin: String,
        limit: Int = 30,
        cursor: String? = null
    ): Pair<CreatorChannel?, CreatorFeedPage> = withContext(Dispatchers.IO) {
        val cleanLogin = channelLogin.trim().removePrefix("@").lowercase()
        if (cleanLogin.isEmpty()) return@withContext null to CreatorFeedPage(emptyList())

        val safeLimit = limit.coerceIn(1, 100)
        val afterClause = cursor?.let { """, after: ${jsonQuote(it)}""" }.orEmpty()
        val query = """
            query {
              user(login: ${jsonQuote(cleanLogin)}) {
                id
                login
                displayName
                description
                profileImageURL(width: 150)
                bannerImageURL
                followers { totalCount }
                stream {
                  id
                  title
                  viewersCount
                  createdAt
                  previewImageURL(width: 640, height: 360)
                  game { id name slug }
                }
                videos(first: $safeLimit, type: ARCHIVE$afterClause) {
                  edges {
                    cursor
                    node {
                      id
                      title
                      lengthSeconds
                      viewCount
                      publishedAt
                      previewThumbnailURL(width: 640, height: 360)
                      game { name }
                    }
                  }
                  pageInfo { hasNextPage }
                }
              }
            }
        """.trimIndent()

        val data = executeGqlQuery(query) ?: return@withContext null to CreatorFeedPage(emptyList())
        val userObj = data["user"] as? JsonObject ?: return@withContext null to CreatorFeedPage(emptyList())

        val login = userObj["login"]?.jsonPrimitive?.contentOrNull ?: cleanLogin
        val displayName = userObj["displayName"]?.jsonPrimitive?.contentOrNull ?: login
        val avatar = userObj["profileImageURL"]?.jsonPrimitive?.contentOrNull
        val banner = userObj["bannerImageURL"]?.jsonPrimitive?.contentOrNull
        val desc = userObj["description"]?.jsonPrimitive?.contentOrNull
        val followers = (userObj["followers"] as? JsonObject)?.get("totalCount")?.jsonPrimitive?.longOrNull
        val liveCard = parseLiveCardFromUserNode(userObj)

        val channel = CreatorChannel(
            id = login,
            platform = CreatorPlatform.TWITCH,
            displayName = displayName,
            handle = "@$login",
            avatarUrl = avatar,
            bannerUrl = banner,
            description = desc,
            followerCount = followers,
            followerCountText = followers?.let { "${formatCount(it)} followers" },
            isLive = liveCard != null,
            liveVideo = liveCard
        )

        val videosConn = userObj["videos"] as? JsonObject
        val edges = videosConn?.get("edges") as? JsonArray
        val hasNext = (videosConn?.get("pageInfo") as? JsonObject)?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true
        var lastCursor: String? = null
        val vodCards = ArrayList<CreatorVideoCard>()

        if (cursor == null && liveCard != null) {
            vodCards.add(liveCard)
        }

        if (edges != null) {
            for (el in edges) {
                val edge = el as? JsonObject ?: continue
                edge["cursor"]?.jsonPrimitive?.contentOrNull?.let { lastCursor = it }
                val node = edge["node"] as? JsonObject ?: continue
                val vodId = node["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val title = node["title"]?.jsonPrimitive?.contentOrNull ?: "Past Broadcast"
                val durSec = node["lengthSeconds"]?.jsonPrimitive?.longOrNull ?: 0L
                val views = node["viewCount"]?.jsonPrimitive?.longOrNull
                val pub = node["publishedAt"]?.jsonPrimitive?.contentOrNull?.substringBefore("T")
                val thumb = node["previewThumbnailURL"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val gameName = (node["game"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

                vodCards.add(
                    CreatorVideoCard(
                        id = vodId,
                        platform = CreatorPlatform.TWITCH,
                        title = title,
                        channelName = displayName,
                        channelId = login,
                        channelAvatarUrl = avatar,
                        thumbnailUrl = thumb,
                        durationSec = durSec,
                        durationText = formatDuration(durSec),
                        viewerOrViewCount = views,
                        viewCountText = views?.let { "${formatCount(it)} views" },
                        publishedText = pub,
                        categoryName = gameName,
                        isLive = false
                    )
                )
            }
        }

        channel to CreatorFeedPage(
            items = vodCards,
            channels = listOf(channel),
            nextCursor = if (hasNext) lastCursor else null
        )
    }

    suspend fun search(
        query: String,
        limit: Int = 25
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext CreatorFeedPage(emptyList())
        val safeLimit = limit.coerceIn(1, 50)

        val gql = """
            query {
              searchUsers(userQuery: ${jsonQuote(q)}, first: $safeLimit) {
                edges {
                  node {
                    id
                    login
                    displayName
                    description
                    profileImageURL(width: 150)
                    followers { totalCount }
                    stream {
                      id
                      title
                      viewersCount
                      createdAt
                      previewImageURL(width: 640, height: 360)
                      game { id name slug }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val data = executeGqlQuery(gql) ?: return@withContext CreatorFeedPage(emptyList())
        val edges = ((data["searchUsers"] as? JsonObject)?.get("edges") as? JsonArray)
            ?: return@withContext CreatorFeedPage(emptyList())

        val liveCards = ArrayList<CreatorVideoCard>()
        val channels = ArrayList<CreatorChannel>()

        for (el in edges) {
            val node = (el as? JsonObject)?.get("node") as? JsonObject ?: continue
            val login = node["login"]?.jsonPrimitive?.contentOrNull ?: continue
            val displayName = node["displayName"]?.jsonPrimitive?.contentOrNull ?: login
            val avatar = node["profileImageURL"]?.jsonPrimitive?.contentOrNull
            val desc = node["description"]?.jsonPrimitive?.contentOrNull
            val followers = (node["followers"] as? JsonObject)?.get("totalCount")?.jsonPrimitive?.longOrNull
            val liveCard = parseLiveCardFromUserNode(node)
            if (liveCard != null) liveCards.add(liveCard)

            channels.add(
                CreatorChannel(
                    id = login,
                    platform = CreatorPlatform.TWITCH,
                    displayName = displayName,
                    handle = "@$login",
                    avatarUrl = avatar,
                    description = desc,
                    followerCount = followers,
                    followerCountText = followers?.let { "${formatCount(it)} followers" },
                    isLive = liveCard != null,
                    liveVideo = liveCard
                )
            )
        }

        CreatorFeedPage(
            items = liveCards,
            channels = channels
        )
    }

    suspend fun getCurrentUserProfile(authContext: TwitchAuthContext): CreatorChannel? = withContext(Dispatchers.IO) {
        val accessToken = getValidAccessToken(authContext) ?: return@withContext null
        val gql = """
            query {
              currentUser {
                id
                login
                displayName
                profileImageURL(width: 150)
              }
            }
        """.trimIndent()
        val data = executeGqlQuery(gql, accessToken, authContext.clientId ?: TV_CLIENT_ID) ?: return@withContext null
        val u = data["currentUser"] as? JsonObject ?: return@withContext null
        val login = u["login"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val disp = u["displayName"]?.jsonPrimitive?.contentOrNull ?: login
        val avatar = u["profileImageURL"]?.jsonPrimitive?.contentOrNull
        CreatorChannel(
            id = login,
            platform = CreatorPlatform.TWITCH,
            displayName = disp,
            handle = "@$login",
            avatarUrl = avatar
        )
    }

    // =========================================================================
    // Ad-Free Live & VOD HLS Stream Resolution (`usher.ttvnw.net`)
    // =========================================================================

    /**
     * Auto-dispatches to [resolveVodStream] if [idOrLogin] is a numeric VOD ID (unless [isLive] == true),
     * or [resolveLiveStream] for a channel login.
     */
    suspend fun resolveStream(
        idOrLogin: String,
        isLive: Boolean? = null,
        authContext: TwitchAuthContext? = null
    ): ResolvedCreatorStream {
        val clean = idOrLogin.trim().removePrefix("v")
        val treatAsVod = isLive == false || (isLive == null && clean.all { it.isDigit() } && clean.length >= 7)
        return if (treatAsVod) {
            resolveVodStream(clean, authContext)
        } else {
            resolveLiveStream(idOrLogin.trim().removePrefix("@").lowercase(), authContext)
        }
    }

    /**
     * Resolves an ad-free Twitch Live HLS stream for [channelLogin].
     *
     * Requests a clean `embed` playerType access token (which avoids pre-roll ad stitching on
     * Twitch's Usher edge) AND a secondary `site` / `thunderdome` backup token so
     * [TwitchAdFreeHlsParserFactory] can seamlessly swap or strip any mid-roll `stitched-ad` segments!
     */
    suspend fun resolveLiveStream(
        channelLogin: String,
        authContext: TwitchAuthContext? = null
    ): ResolvedCreatorStream = withContext(Dispatchers.IO) {
        val login = channelLogin.trim().removePrefix("@").lowercase()
        val accessToken = getValidAccessToken(authContext)
        val authClientId = if (accessToken != null) (authContext?.clientId ?: TV_CLIENT_ID) else WEB_CLIENT_ID

        // Primary token uses `embed` playerType (ad-exempt on Usher for pre-rolls), falling back to `site`
        val primaryToken = fetchPlaybackAccessToken(
            login = login,
            vodId = "",
            isLive = true,
            playerType = "embed",
            accessToken = accessToken,
            clientId = authClientId
        ) ?: fetchPlaybackAccessToken(
            login = login,
            vodId = "",
            isLive = true,
            playerType = "site",
            accessToken = accessToken,
            clientId = authClientId
        ) ?: throw IOException("Failed to obtain Twitch PlaybackAccessToken for $login")

        val primaryMasterUrl = buildUsherLiveMasterUrl(login, primaryToken.first, primaryToken.second)

        // Secondary backup token (`thunderdome` / `Picture-by-Picture`) for mid-roll ad-break swapping
        val backupMasterUrl = runCatching {
            fetchPlaybackAccessToken(
                login = login,
                vodId = "",
                isLive = true,
                playerType = "thunderdome",
                accessToken = null,
                clientId = WEB_CLIENT_ID
            )?.let { (v, s) -> buildUsherLiveMasterUrl(login, v, s) }
        }.getOrNull()

        val variants = fetchAndParseHlsVariants(primaryMasterUrl)
        if (variants.isEmpty()) {
            throw IOException("Twitch channel '$login' is currently offline")
        }

        ResolvedCreatorStream(
            id = login,
            platform = CreatorPlatform.TWITCH,
            title = "$login (Live)",
            channelName = login,
            channelId = login,
            thumbnailUrl = "https://static-cdn.jtvnw.net/previews-ttv/live_user_${login}-640x360.jpg",
            isLive = true,
            primaryUrl = primaryMasterUrl,
            primaryMimeType = "application/x-mpegURL",
            hlsManifestUrl = primaryMasterUrl,
            backupAdFreeHlsUrl = backupMasterUrl,
            qualityVariants = variants,
            headers = mapOf(
                "User-Agent" to WEB_UA,
                "Origin" to "https://player.twitch.tv",
                "Referer" to "https://player.twitch.tv/"
            )
        )
    }

    /**
     * Resolves an ad-free Twitch VOD HLS stream for [vodId].
     */
    suspend fun resolveVodStream(
        vodId: String,
        authContext: TwitchAuthContext? = null
    ): ResolvedCreatorStream = withContext(Dispatchers.IO) {
        val cleanVodId = vodId.trim().removePrefix("v")
        val accessToken = getValidAccessToken(authContext)
        val authClientId = if (accessToken != null) (authContext?.clientId ?: TV_CLIENT_ID) else WEB_CLIENT_ID

        val token = fetchPlaybackAccessToken(
            login = "",
            vodId = cleanVodId,
            isLive = false,
            playerType = "embed",
            accessToken = accessToken,
            clientId = authClientId
        ) ?: fetchPlaybackAccessToken(
            login = "",
            vodId = cleanVodId,
            isLive = false,
            playerType = "site",
            accessToken = accessToken,
            clientId = authClientId
        ) ?: throw IOException("Failed to obtain Twitch VOD PlaybackAccessToken for $cleanVodId")

        val masterUrl = buildUsherVodMasterUrl(cleanVodId, token.first, token.second)
        val variants = fetchAndParseHlsVariants(masterUrl)
        if (variants.isEmpty()) {
            throw IOException("Twitch VOD $cleanVodId unavailable or subscriber-only")
        }

        ResolvedCreatorStream(
            id = cleanVodId,
            platform = CreatorPlatform.TWITCH,
            title = "Twitch VOD #$cleanVodId",
            channelName = "Twitch",
            isLive = false,
            primaryUrl = masterUrl,
            primaryMimeType = "application/x-mpegURL",
            hlsManifestUrl = masterUrl,
            qualityVariants = variants,
            headers = mapOf(
                "User-Agent" to WEB_UA,
                "Origin" to "https://player.twitch.tv",
                "Referer" to "https://player.twitch.tv/"
            )
        )
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    internal fun fetchPlaybackAccessToken(
        login: String,
        vodId: String,
        isLive: Boolean,
        playerType: String,
        accessToken: String? = null,
        clientId: String = WEB_CLIENT_ID
    ): Pair<String, String>? {
        val payload = buildJsonObject {
            put("operationName", "PlaybackAccessToken_Template")
            put("query", PLAYBACK_TOKEN_QUERY)
            putJsonObject("variables") {
                put("isLive", isLive)
                put("login", login)
                put("isVod", !isLive)
                put("vodID", vodId)
                put("playerType", playerType)
            }
        }.toString()

        val reqBuilder = Request.Builder()
            .url(GQL_URL)
            .header("User-Agent", WEB_UA)
            .header("Client-Id", clientId)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))

        if (!accessToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "OAuth $accessToken")
        }

        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val data = root["data"] as? JsonObject ?: return@use null
                val tokenObj = (if (isLive) data["streamPlaybackAccessToken"] else data["videoPlaybackAccessToken"])
                    as? JsonObject ?: return@use null
                val value = tokenObj["value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@use null
                val sig = tokenObj["signature"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@use null
                value to sig
            }
        }.getOrNull()
    }

    internal fun buildUsherLiveMasterUrl(login: String, tokenValue: String, signature: String): String {
        return "https://usher.ttvnw.net/api/channel/hls/$login.m3u8".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("player", "twitchweb")
            .addQueryParameter("p", Random.nextInt(100_000, 999_999).toString())
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("allow_spectre", "false")
            .addQueryParameter("fast_bread", "true")
            .addQueryParameter("sig", signature)
            .addQueryParameter("token", tokenValue)
            .build()
            .toString()
    }

    private fun buildUsherVodMasterUrl(vodId: String, tokenValue: String, signature: String): String {
        return "https://usher.ttvnw.net/vod/$vodId.m3u8".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("player", "twitchweb")
            .addQueryParameter("p", Random.nextInt(100_000, 999_999).toString())
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("nauthsig", signature)
            .addQueryParameter("nauth", tokenValue)
            .build()
            .toString()
    }

    internal fun fetchAndParseHlsVariants(masterUrl: String): List<CreatorQualityVariant> {
        val req = Request.Builder()
            .url(masterUrl)
            .header("User-Agent", WEB_UA)
            .header("Origin", "https://player.twitch.tv")
            .get()
            .build()

        val text = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()?.takeIf { it.contains("#EXTM3U") } ?: return emptyList()

        return parseMultivariantM3u8(masterUrl, text)
    }

    internal fun parseMultivariantM3u8(baseUrl: String, m3u8Text: String): List<CreatorQualityVariant> {
        val lines = m3u8Text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val mediaNames = HashMap<String, String>()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                val groupId = Regex("""GROUP-ID="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                val name = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                if (groupId != null && name != null) {
                    mediaNames[groupId] = name
                }
            }
        }

        val variants = ArrayList<CreatorQualityVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:") && i + 1 < lines.size) {
                val urlLine = lines[i + 1]
                if (!urlLine.startsWith("#")) {
                    val resolvedUrl = resolveRelativeUrl(baseUrl, urlLine)
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val width = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val height = resMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    val fps = Regex("""FRAME-RATE=([\d.]+)""").find(line)?.groupValues?.getOrNull(1)
                        ?.toDoubleOrNull()?.toInt()
                    val videoGroup = Regex("""VIDEO="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                    val groupDisplay = videoGroup?.let { mediaNames[it] }

                    val label = when {
                        videoGroup == "audio_only" -> "Audio Only"
                        !groupDisplay.isNullOrBlank() -> groupDisplay
                        height != null && fps != null && fps > 30 -> "${height}p$fps"
                        height != null -> "${height}p"
                        else -> "Auto"
                    }

                    variants.add(
                        CreatorQualityVariant(
                            label = label,
                            url = resolvedUrl,
                            mimeType = "application/x-mpegURL",
                            width = width,
                            height = height,
                            fps = fps,
                            bitrate = bw
                        )
                    )
                    i += 2
                    continue
                }
            }
            i++
        }

        return variants.sortedWith(
            compareByDescending<CreatorQualityVariant> { it.height ?: 0 }
                .thenByDescending { it.fps ?: 0 }
                .thenByDescending { it.bitrate ?: 0L }
        )
    }

    private fun executeGqlQuery(
        query: String,
        accessToken: String? = null,
        clientId: String = WEB_CLIENT_ID
    ): JsonObject? {
        val payload = buildJsonObject {
            put("query", query)
        }.toString()

        val reqBuilder = Request.Builder()
            .url(GQL_URL)
            .header("User-Agent", WEB_UA)
            .header("Client-Id", clientId)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))

        if (!accessToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "OAuth $accessToken")
        }

        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                root["data"] as? JsonObject
            }
        }.getOrNull()
    }

    private fun parseStreamsConnection(
        streamsConn: JsonObject,
        fallbackCategoryName: String? = null
    ): CreatorFeedPage {
        val edges = streamsConn["edges"] as? JsonArray ?: return CreatorFeedPage(emptyList())
        val hasNext = (streamsConn["pageInfo"] as? JsonObject)?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull == true
        var lastCursor: String? = null

        val items = edges.mapNotNull { el ->
            val edge = el as? JsonObject ?: return@mapNotNull null
            edge["cursor"]?.jsonPrimitive?.contentOrNull?.let { lastCursor = it }
            val node = edge["node"] as? JsonObject ?: return@mapNotNull null
            val broadcaster = node["broadcaster"] as? JsonObject ?: return@mapNotNull null
            val login = broadcaster["login"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val displayName = broadcaster["displayName"]?.jsonPrimitive?.contentOrNull ?: login
            val avatar = broadcaster["profileImageURL"]?.jsonPrimitive?.contentOrNull
            val title = node["title"]?.jsonPrimitive?.contentOrNull ?: "$displayName Live"
            val viewers = node["viewersCount"]?.jsonPrimitive?.longOrNull
            val thumb = node["previewImageURL"]?.jsonPrimitive?.contentOrNull
                ?: "https://static-cdn.jtvnw.net/previews-ttv/live_user_${login}-640x360.jpg"
            val gameName = (node["game"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                ?: fallbackCategoryName

            CreatorVideoCard(
                id = login,
                platform = CreatorPlatform.TWITCH,
                title = title,
                channelName = displayName,
                channelId = login,
                channelAvatarUrl = avatar,
                thumbnailUrl = thumb,
                durationText = "LIVE",
                viewerOrViewCount = viewers,
                viewCountText = viewers?.let { "${formatCount(it)} watching" },
                categoryName = gameName,
                isLive = true
            )
        }

        return CreatorFeedPage(
            items = items,
            nextCursor = if (hasNext) lastCursor else null
        )
    }

    private fun parseLiveCardFromUserNode(userNode: JsonObject): CreatorVideoCard? {
        val stream = userNode["stream"] as? JsonObject ?: return null
        val login = userNode["login"]?.jsonPrimitive?.contentOrNull ?: return null
        val displayName = userNode["displayName"]?.jsonPrimitive?.contentOrNull ?: login
        val avatar = userNode["profileImageURL"]?.jsonPrimitive?.contentOrNull
        val title = stream["title"]?.jsonPrimitive?.contentOrNull ?: "$displayName Live"
        val viewers = stream["viewersCount"]?.jsonPrimitive?.longOrNull
        val thumb = stream["previewImageURL"]?.jsonPrimitive?.contentOrNull
            ?: "https://static-cdn.jtvnw.net/previews-ttv/live_user_${login}-640x360.jpg"
        val gameName = (stream["game"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

        return CreatorVideoCard(
            id = login,
            platform = CreatorPlatform.TWITCH,
            title = title,
            channelName = displayName,
            channelId = login,
            channelAvatarUrl = avatar,
            thumbnailUrl = thumb,
            durationText = "LIVE",
            viewerOrViewCount = viewers,
            viewCountText = viewers?.let { "${formatCount(it)} watching" },
            categoryName = gameName,
            isLive = true
        )
    }

    private fun cacheRotatedTokensLocked(
        oldRefresh: String,
        newRefresh: String,
        tokens: TwitchOAuthTokens
    ) {
        rotatedTokenCache[oldRefresh] = tokens
        rotatedTokenCache[newRefresh] = tokens
        while (rotatedTokenCache.size > 12) {
            val first = rotatedTokenCache.keys.firstOrNull { it != oldRefresh && it != newRefresh } ?: break
            rotatedTokenCache.remove(first)
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeOrAbs: String): String {
        if (relativeOrAbs.startsWith("http://") || relativeOrAbs.startsWith("https://")) return relativeOrAbs
        return runCatching { URI(baseUrl).resolve(relativeOrAbs).toString() }.getOrDefault(relativeOrAbs)
    }

    private fun jsonQuote(raw: String): String = JsonPrimitive(raw).toString()

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000L -> "%.1fM".format(java.util.Locale.US, n / 1_000_000.0)
        n >= 1_000L -> "%.1fK".format(java.util.Locale.US, n / 1_000.0)
        else -> n.toString()
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
