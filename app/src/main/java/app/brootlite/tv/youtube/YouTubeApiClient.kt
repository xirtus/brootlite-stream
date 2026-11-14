package app.brootlite.tv.youtube

import android.content.Context
import android.util.LruCache
import app.brootlite.tv.BrootLiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.OffsetDateTime

/**
 * Logged-in & anonymous ad-free YouTube InnerTube engine for BrootLite.
 *
 * Capabilities:
 * - Browsable feeds: Home, Subscriptions (OAuth + RSS fallback), History, Library Playlists,
 *   Playlist Videos (`WL`, `LL`, `PL...`), Channel feeds + Subscribe/Unsubscribe, Search, Related.
 * - Multi-client stream resolution (`ANDROID_VR` -> `TV_TIZEN` -> `TV_SIMPLY` -> `VISIONOS` ->
 *   `IOS` -> `WEB_SAFARI` -> `WEB_EMBED`) with QuickJS `sig`/`n` deciphering, on-demand `:potoken`
 *   minting (killed before playback), 4K/1440p/1080p60/720p60/480p/360p quality variants, DASH MPD,
 *   progressive `muxedUrl` fallback, and VTT/SRT subtitles.
 * - Built-in SponsorBlock, ReturnYouTubeDislike, DeArrow, and two-way Google Watch History sync.
 */
object YouTubeApiClient {

    private const val YT_API_BASE = "https://www.youtube.com/youtubei/v1"
    private const val SPONSORBLOCK_API = "https://sponsor.ajay.app/api"
    private const val DEARROW_THUMB_API = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
    private const val RYD_API = "https://returnyoutubedislikeapi.com/votes"
    private const val SUGGEST_API = "https://suggestqueries-clients6.youtube.com/complete/search"

    private const val WEB_CLIENT_VERSION = "2.20260301.01.00"
    private const val WEB_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15"
    private const val TV_TIZEN_VERSION = "5.20260707"
    private const val TV_TIZEN_UA =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15"
    private const val ANDROID_VR_VERSION = "1.65.10"
    private const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 14; en_US; Quest 3; Build/UP1A.231005.007.A1; Cronet/132.0.6808.3)"
    private const val ANDROID_REEL_VERSION = "21.03.36"
    private const val ANDROID_REEL_UA =
        "com.google.android.youtube/21.03.36 (Linux; U; Android 14) gzip"
    private const val VISIONOS_VERSION = "1.02"
    private const val IOS_VERSION = "20.10.4"
    private const val IOS_UA =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iPhone OS 18_3_2 like Mac OS X;)"

    // Standard YouTube browse tab param for Channel -> Videos tab
    const val CHANNEL_VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
    const val CHANNEL_LIVE_TAB_PARAMS = "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"
    const val CHANNEL_SHORTS_TAB_PARAMS = "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"

    val DEFAULT_SPONSOR_CATEGORIES = listOf(
        "sponsor",
        "selfpromo",
        "interaction",
        "intro",
        "outro",
        "preview"
    )

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cpnChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedVisitorData: String? = null

    private data class DeArrowEntry(
        val title: String?,
        val thumbnailUrl: String?
    )

    private data class WatchSessionState(
        var playbackPingSent: Boolean = false,
        var lastReportedSec: Float = 0f,
        val startedAtMs: Long = System.currentTimeMillis()
    )

    private val deArrowCache = LruCache<String, DeArrowEntry>(256)
    private val watchSessions = LinkedHashMap<String, WatchSessionState>(16, 0.75f, true)

    private val httpClient: OkHttpClient
        get() = BrootLiteApp.sharedHttpClient

    private enum class PlayerProfile(
        val label: String,
        val clientName: String,
        val clientVersion: String,
        val clientIdHeader: String,
        val userAgent: String,
        val supportsTvOAuth: Boolean,
        val usesSigTimestamp: Boolean,
        val canUsePoToken: Boolean,
        val isEmbedded: Boolean = false
    ) {
        ANDROID_VR(
            label = "ANDROID_VR",
            clientName = "ANDROID_VR",
            clientVersion = ANDROID_VR_VERSION,
            clientIdHeader = "28",
            userAgent = ANDROID_VR_UA,
            supportsTvOAuth = false,
            usesSigTimestamp = false,
            canUsePoToken = false
        ),
        TV_TIZEN(
            label = "TV",
            clientName = "TVHTML5",
            clientVersion = TV_TIZEN_VERSION,
            clientIdHeader = "7",
            userAgent = TV_TIZEN_UA,
            supportsTvOAuth = true,
            usesSigTimestamp = true,
            canUsePoToken = true
        ),
        TV_SIMPLY(
            label = "TV_SIMPLY",
            clientName = "TVHTML5_SIMPLY",
            clientVersion = "1.0",
            clientIdHeader = "74",
            userAgent = YouTubeAuthClient.TV_COBALT_UA,
            supportsTvOAuth = true,
            usesSigTimestamp = true,
            canUsePoToken = true,
            isEmbedded = true
        ),
        VISIONOS(
            label = "VISIONOS",
            clientName = "VISIONOS",
            clientVersion = VISIONOS_VERSION,
            clientIdHeader = "101",
            userAgent = WEB_UA,
            supportsTvOAuth = false,
            usesSigTimestamp = false,
            canUsePoToken = false
        ),
        IOS(
            label = "IOS",
            clientName = "IOS",
            clientVersion = IOS_VERSION,
            clientIdHeader = "5",
            userAgent = IOS_UA,
            supportsTvOAuth = false,
            usesSigTimestamp = false,
            canUsePoToken = false
        ),
        WEB_SAFARI(
            label = "WEB_SAFARI",
            clientName = "WEB",
            clientVersion = WEB_CLIENT_VERSION,
            clientIdHeader = "1",
            userAgent = WEB_UA,
            supportsTvOAuth = false,
            usesSigTimestamp = true,
            canUsePoToken = true
        ),
        WEB_EMBED(
            label = "WEB_EMBED",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20260301.01.00",
            clientIdHeader = "56",
            userAgent = WEB_UA,
            supportsTvOAuth = true,
            usesSigTimestamp = true,
            canUsePoToken = false,
            isEmbedded = true
        )
    }

    // =========================================================================
    // 1B. Browsable Feeds (Signed-In & Anonymous Fallback)
    // =========================================================================

    /**
     * Personalized Home feed (`FEwhat_to_watch`) when signed in, or clean trending/recommended
     * Home feed when anonymous. Supports pagination via [continuation].
     */
    suspend fun getHomeFeed(
        authContext: YouTubeAuthContext? = null,
        continuation: String? = null
    ): YouTubeFeedPage = withContext(Dispatchers.IO) {
        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext)
        val useTv = !accessToken.isNullOrBlank()

        var page = executeBrowseFeed(
            browseId = if (continuation == null) "FEwhat_to_watch" else null,
            continuation = continuation,
            params = null,
            authContext = authContext,
            accessToken = accessToken,
            useTvClient = useTv
        )

        // Anonymous WEB FEwhat_to_watch occasionally returns an empty prompt without visitor history;
        // fall back cleanly to TVHTML5 FEwhat_to_watch or Trending ("FEtrending") so Home is never blank!
        if (page.items.isEmpty() && continuation == null) {
            page = executeBrowseFeed(
                browseId = "FEwhat_to_watch",
                continuation = null,
                params = null,
                authContext = authContext,
                accessToken = accessToken,
                useTvClient = !useTv
            )
        }
        if (page.items.isEmpty() && continuation == null && accessToken == null) {
            page = executeBrowseFeed(
                browseId = "FEtrending",
                continuation = null,
                params = null,
                authContext = authContext,
                accessToken = null,
                useTvClient = false
            )
        }

        maybeApplyDeArrowToPage(page, authContext)
    }

    /**
     * Subscriptions chronological feed (`FEsubscriptions`) when signed in, or zero-login RSS/Atom
     * feed aggregation (`https://www.youtube.com/feeds/videos.xml?channel_id=...`) for locally
     * followed channels when not signed in.
     */
    suspend fun getSubscriptionsFeed(
        authContext: YouTubeAuthContext? = null,
        continuation: String? = null,
        followedChannelIds: List<String> = emptyList()
    ): YouTubeFeedPage = withContext(Dispatchers.IO) {
        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext)
        if (!accessToken.isNullOrBlank()) {
            val page = executeBrowseFeed(
                browseId = if (continuation == null) "FEsubscriptions" else null,
                continuation = continuation,
                params = null,
                authContext = authContext,
                accessToken = accessToken,
                useTvClient = true
            )
            return@withContext maybeApplyDeArrowToPage(page, authContext)
        }

        if (followedChannelIds.isEmpty() || continuation != null) {
            return@withContext YouTubeFeedPage(items = emptyList())
        }

        val aggregated = fetchAnonymousRssSubscriptions(followedChannelIds)
        val finalItems = applyDeArrowIfEnabled(aggregated, authContext?.deArrowEnabled == true)
        YouTubeFeedPage(items = finalItems)
    }

    /**
     * Watch History feed (`FEhistory`) with resume progress percentages (`resumePercent`).
     */
    suspend fun getHistoryFeed(
        authContext: YouTubeAuthContext,
        continuation: String? = null
    ): YouTubeFeedPage = withContext(Dispatchers.IO) {
        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext)
            ?: return@withContext YouTubeFeedPage(items = emptyList())

        val page = executeBrowseFeed(
            browseId = if (continuation == null) "FEhistory" else null,
            continuation = continuation,
            params = null,
            authContext = authContext,
            accessToken = accessToken,
            useTvClient = true
        )
        maybeApplyDeArrowToPage(page, authContext)
    }

    /**
     * Lists the signed-in user's Library Playlists (Watch Later `WL`, Liked Videos `LL`, and saved playlists).
     */
    suspend fun getLibraryPlaylists(
        authContext: YouTubeAuthContext
    ): List<YouTubePlaylistCard> = withContext(Dispatchers.IO) {
        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext)
            ?: return@withContext emptyList()

        val collected = LinkedHashMap<String, YouTubePlaylistCard>()
        // Always expose Watch Later & Liked Videos first for TV 1-click access
        collected["WL"] = YouTubePlaylistCard(
            playlistId = "WL",
            title = "Watch Later",
            author = "YouTube Library"
        )
        collected["LL"] = YouTubePlaylistCard(
            playlistId = "LL",
            title = "Liked Videos",
            author = "YouTube Library"
        )

        for (browseId in listOf("FEplaylist_aggregation", "FElibrary", "FEmy_youtube")) {
            val page = runCatching {
                executeBrowseFeed(
                    browseId = browseId,
                    continuation = null,
                    params = null,
                    authContext = authContext,
                    accessToken = accessToken,
                    useTvClient = true
                )
            }.getOrNull() ?: continue

            for (pl in page.playlists) {
                collected[pl.playlistId] = pl
            }
            if (page.playlists.isNotEmpty()) break
        }

        collected.values.toList()
    }

    /**
     * Fetches videos inside any playlist (`"WL"`, `"LL"`, `"PL..."`, `"RD..."`, or `"VL..."`), with pagination.
     */
    suspend fun getPlaylistVideos(
        playlistId: String,
        authContext: YouTubeAuthContext? = null,
        continuation: String? = null
    ): YouTubeFeedPage = withContext(Dispatchers.IO) {
        val cleanId = playlistId.trim()
        if (cleanId.isEmpty() && continuation.isNullOrBlank()) {
            return@withContext YouTubeFeedPage(items = emptyList())
        }
        val browseId = when {
            continuation != null -> null
            cleanId.startsWith("VL") || cleanId.startsWith("FE") -> cleanId
            else -> "VL$cleanId"
        }

        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext)
        val useTv = !accessToken.isNullOrBlank() || cleanId == "WL" || cleanId == "LL"

        var page = executeBrowseFeed(
            browseId = browseId,
            continuation = continuation,
            params = null,
            authContext = authContext,
            accessToken = accessToken,
            useTvClient = useTv
        )

        if (page.items.isEmpty() && continuation == null && accessToken == null) {
            page = executeBrowseFeed(
                browseId = browseId,
                continuation = null,
                params = null,
                authContext = authContext,
                accessToken = null,
                useTvClient = !useTv
            )
        }

        maybeApplyDeArrowToPage(page, authContext)
    }

    /**
     * Fetches a YouTube Channel's header, subscribe status, and Videos/Live/Shorts feeds.
     * Supports both `UC...` channel IDs and `@handle` / channel URLs!
     */
    suspend fun getChannelFeed(
        channelId: String,
        authContext: YouTubeAuthContext? = null,
        continuation: String? = null,
        tabParams: String? = CHANNEL_VIDEOS_TAB_PARAMS
    ): YouTubeChannelFeed = withContext(Dispatchers.IO) {
        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext)
        val resolvedBrowseId = if (continuation == null && !channelId.startsWith("UC")) {
            resolveChannelHandleOrUrl(channelId, authContext) ?: channelId
        } else {
            channelId
        }

        val body = buildInnerTubeBody(
            authContext = authContext,
            useTvClient = false,
            visitorData = authContext?.visitorData ?: cachedVisitorData
        ) {
            if (!continuation.isNullOrBlank()) {
                put("continuation", continuation)
            } else {
                put("browseId", resolvedBrowseId)
                if (!tabParams.isNullOrBlank()) {
                    put("params", tabParams)
                }
            }
        }

        // Use WEB structure for rich Channel header + tabs, passing OAuth Bearer if signed in on TV fallback if needed
        val root = postInnerTubeJson(
            endpoint = "browse",
            bodyJson = body,
            useTvClient = false,
            accessToken = null,
            pageId = null,
            visitorData = authContext?.visitorData ?: cachedVisitorData
        ) ?: return@withContext YouTubeChannelFeed(channelId = resolvedBrowseId, title = resolvedBrowseId)

        YouTubeInnerTubeParser.extractVisitorData(root)?.let { cachedVisitorData = it }
        var feed = YouTubeInnerTubeParser.parseChannelFeed(root, resolvedBrowseId)

        // If signed in, check subscription state via TV client if header didn't include it
        if (!accessToken.isNullOrBlank() && feed.isSubscribed == null && continuation == null) {
            val tvCheckBody = buildInnerTubeBody(
                authContext = authContext,
                useTvClient = true,
                visitorData = authContext?.visitorData ?: cachedVisitorData
            ) {
                put("browseId", feed.channelId)
            }
            val tvRoot = postInnerTubeJson(
                endpoint = "browse",
                bodyJson = tvCheckBody,
                useTvClient = true,
                accessToken = accessToken,
                pageId = authContext?.onBehalfOfUser,
                visitorData = authContext?.visitorData ?: cachedVisitorData
            )
            if (tvRoot != null) {
                val subState = YouTubeInnerTubeParser.parseChannelFeed(tvRoot, feed.channelId).isSubscribed
                if (subState != null) {
                    feed = feed.copy(isSubscribed = subState)
                }
            }
        }

        if (authContext?.deArrowEnabled == true && feed.videos.isNotEmpty()) {
            feed = feed.copy(videos = applyDeArrowIfEnabled(feed.videos, true))
        }
        feed
    }

    /**
     * Subscribes the signed-in YouTube account (or Brand channel) to [channelId].
     */
    suspend fun subscribe(
        channelId: String,
        authContext: YouTubeAuthContext,
        params: String? = null
    ): Boolean = mutateSubscription(
        endpoint = "subscription/subscribe",
        channelId = channelId,
        authContext = authContext,
        params = params
    )

    /**
     * Unsubscribes the signed-in YouTube account (or Brand channel) from [channelId].
     */
    suspend fun unsubscribe(
        channelId: String,
        authContext: YouTubeAuthContext,
        params: String? = null
    ): Boolean = mutateSubscription(
        endpoint = "subscription/unsubscribe",
        channelId = channelId,
        authContext = authContext,
        params = params
    )

    /**
     * Full YouTube search supporting [YouTubeSearchFilter.ALL], [YouTubeSearchFilter.VIDEOS],
     * [YouTubeSearchFilter.CHANNELS], [YouTubeSearchFilter.PLAYLISTS], and [YouTubeSearchFilter.LIVE].
     */
    suspend fun search(
        query: String,
        authContext: YouTubeAuthContext? = null,
        filter: YouTubeSearchFilter = YouTubeSearchFilter.ALL,
        continuation: String? = null
    ): YouTubeFeedPage = withContext(Dispatchers.IO) {
        if (query.isBlank() && continuation.isNullOrBlank()) {
            return@withContext YouTubeFeedPage(items = emptyList())
        }

        val body = buildInnerTubeBody(
            authContext = authContext,
            useTvClient = false,
            visitorData = authContext?.visitorData ?: cachedVisitorData
        ) {
            if (!continuation.isNullOrBlank()) {
                put("continuation", continuation)
            } else {
                put("query", query.trim())
                filter.params?.let { put("params", it) }
            }
        }

        val root = postInnerTubeJson(
            endpoint = "search",
            bodyJson = body,
            useTvClient = false,
            accessToken = null,
            pageId = null,
            visitorData = authContext?.visitorData ?: cachedVisitorData
        ) ?: return@withContext YouTubeFeedPage(items = emptyList())

        YouTubeInnerTubeParser.extractVisitorData(root)?.let { cachedVisitorData = it }
        val page = YouTubeInnerTubeParser.parseFeedPage(root)
        maybeApplyDeArrowToPage(page, authContext)
    }

    /**
     * Instant search autocomplete suggestions for TV D-pad keyboard.
     */
    suspend fun getSearchSuggestions(
        query: String,
        language: String = "en",
        region: String = "US"
    ): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val url = SUGGEST_API.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("client", "youtube")
            ?.addQueryParameter("ds", "yt")
            ?.addQueryParameter("hl", language)
            ?.addQueryParameter("gl", region)
            ?.addQueryParameter("q", trimmed)
            ?.build() ?: return@withContext emptyList()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .get()
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val raw = resp.body?.string().orEmpty()
                val startIdx = raw.indexOf('[')
                val endIdx = raw.lastIndexOf(']')
                if (startIdx < 0 || endIdx <= startIdx) return@use emptyList()
                val arr = json.parseToJsonElement(raw.substring(startIdx, endIdx + 1)).jsonArray
                val suggestions = arr.getOrNull(1) as? JsonArray ?: return@use emptyList()
                suggestions.mapNotNull { item ->
                    (item as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                }.distinct()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Related / Up-Next videos for [videoId] via `/youtubei/v1/next`.
     */
    suspend fun getRelatedVideos(
        videoId: String,
        authContext: YouTubeAuthContext? = null,
        continuation: String? = null
    ): YouTubeFeedPage = withContext(Dispatchers.IO) {
        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext)
        val useTv = !accessToken.isNullOrBlank()
        val body = buildInnerTubeBody(
            authContext = authContext,
            useTvClient = useTv,
            visitorData = authContext?.visitorData ?: cachedVisitorData
        ) {
            if (!continuation.isNullOrBlank()) {
                put("continuation", continuation)
            } else {
                put("videoId", videoId)
            }
        }

        val root = postInnerTubeJson(
            endpoint = "next",
            bodyJson = body,
            useTvClient = useTv,
            accessToken = accessToken,
            pageId = authContext?.onBehalfOfUser,
            visitorData = authContext?.visitorData ?: cachedVisitorData
        ) ?: return@withContext YouTubeFeedPage(items = emptyList())

        val page = YouTubeInnerTubeParser.parseFeedPage(root)
        maybeApplyDeArrowToPage(page, authContext)
    }

    // =========================================================================
    // 1C. Multi-Client Ad-Free Stream Resolver
    // =========================================================================

    /**
     * Resolves an ad-free YouTube stream for [videoId] by walking a resilient multi-client chain:
     * `ANDROID_VR` -> `TV_TIZEN` -> `TV_SIMPLY` -> `VISIONOS` -> `IOS` -> `WEB_SAFARI` -> `WEB_EMBED`.
     *
     * - Defaults to `<= 1080p60` ([preferredMaxHeight]) for Android TV while exposing all selectable
     *   variants (`2160p 4K`, `1440p`, `1080p60`, `720p60`, `480p`, `360p`) in [ResolvedYouTubeStream.qualityVariants].
     * - Uses [QuickJsSignatureSolver] to decipher `sig` + `n` and immediately closes QuickJS.
     * - Uses [PoTokenClient] ONLY if fallback clients hit bot-check, killing `:potoken` before returning!
     */
    suspend fun resolveStream(
        videoId: String,
        authContext: YouTubeAuthContext? = null,
        preferredMaxHeight: Int = 1080,
        context: Context = BrootLiteApp.instance
    ): ResolvedYouTubeStream = withContext(Dispatchers.IO) {
        val cleanVideoId = extractCleanVideoId(videoId)
        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext)
        val cpn = generateCpn()

        val playerMeta = QuickJsSignatureSolver.ensurePlayerMetadata(context, cleanVideoId)
        val sigTimestamp = playerMeta?.signatureTimestamp
        var visitorData = authContext?.visitorData?.takeIf { it.isNotBlank() }
            ?: playerMeta?.visitorData?.takeIf { it.isNotBlank() }
            ?: cachedVisitorData

        val clientOrder = listOf(
            PlayerProfile.ANDROID_VR,
            PlayerProfile.TV_TIZEN,
            PlayerProfile.TV_SIMPLY,
            PlayerProfile.VISIONOS,
            PlayerProfile.IOS,
            PlayerProfile.WEB_SAFARI,
            PlayerProfile.WEB_EMBED
        )

        var lastPlayabilityReason: String? = null

        for (profile in clientOrder) {
            val tokenForProfile = if (profile.supportsTvOAuth) accessToken else null

            // Only mint a disposable poToken when reaching WEB_SAFARI fallback (or if TV requires it)
            val poToken: String? = if (profile == PlayerProfile.WEB_SAFARI) {
                val id = authContext?.accountId ?: visitorData.orEmpty()
                PoTokenClient.getOrMintPoToken(
                    context = context,
                    identifier = id,
                    visitorData = visitorData,
                    isTvClient = false
                )
            } else {
                null
            }

            val playerJson = requestPlayerJson(
                videoId = cleanVideoId,
                profile = profile,
                cpn = cpn,
                visitorData = visitorData,
                signatureTimestamp = if (profile.usesSigTimestamp) sigTimestamp else null,
                poToken = poToken,
                accessToken = tokenForProfile,
                pageId = if (tokenForProfile != null) authContext?.onBehalfOfUser else null,
                language = authContext?.language ?: "en",
                region = authContext?.region ?: "US"
            ) ?: continue

            YouTubeInnerTubeParser.extractVisitorData(playerJson)?.let {
                visitorData = it
                cachedVisitorData = it
            }

            val playability = playerJson["playabilityStatus"] as? JsonObject
            val status = playability?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                lastPlayabilityReason = playability?.get("reason")?.jsonPrimitive?.contentOrNull ?: status
                continue
            }

            val resolved = buildResolvedStreamFromPlayerJson(
                videoId = cleanVideoId,
                playerJson = playerJson,
                profile = profile,
                cpn = cpn,
                preferredMaxHeight = preferredMaxHeight,
                context = context
            ) ?: continue

            // Enrich with progressive muxed floor if winning client returned adaptive-only formats
            val withMuxed = if (!resolved.isLive && resolved.muxedUrl.isNullOrBlank()) {
                val reelMuxed = fetchReelMuxedFallback(cleanVideoId, visitorData)
                if (reelMuxed != null) {
                    resolved.copy(muxedUrl = reelMuxed.first, muxedHeight = reelMuxed.second)
                } else {
                    resolved
                }
            } else {
                resolved
            }

            // If signed in, ensure playbackTracking URLs carry authenticated user session binding
            // even when media URLs came from anonymous ANDROID_VR / VISIONOS!
            if (!accessToken.isNullOrBlank() && !profile.supportsTvOAuth) {
                val authTracking = fetchAuthenticatedTrackingUrls(
                    videoId = cleanVideoId,
                    cpn = cpn,
                    accessToken = accessToken,
                    pageId = authContext?.onBehalfOfUser,
                    visitorData = visitorData,
                    sigTimestamp = sigTimestamp,
                    language = authContext?.language ?: "en",
                    region = authContext?.region ?: "US"
                )
                if (authTracking != null) {
                    return@withContext withMuxed.copy(
                        playbackTrackingUrl = authTracking.first ?: withMuxed.playbackTrackingUrl,
                        watchtimeTrackingUrl = authTracking.second ?: withMuxed.watchtimeTrackingUrl
                    )
                }
            }

            return@withContext withMuxed
        }

        throw IOException(
            "Unable to resolve playable YouTube stream for $cleanVideoId (${lastPlayabilityReason ?: "all clients exhausted"})"
        )
    }

    // =========================================================================
    // 1D. Built-In SponsorBlock, ReturnYouTubeDislike, DeArrow & Watch History
    // =========================================================================

    /**
     * Fetches SponsorBlock skip segments (`sponsor`, `selfpromo`, `interaction`, `intro`, `outro`, `preview`).
     * Returns `emptyList()` cleanly when a video has no segments (HTTP 404) or when offline.
     */
    suspend fun fetchSponsorBlockSegments(
        videoId: String,
        categories: List<String> = DEFAULT_SPONSOR_CATEGORIES
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        val cleanId = extractCleanVideoId(videoId)
        val catsJson = json.encodeToString(categories)
        val url = "$SPONSORBLOCK_API/skipSegments".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("videoID", cleanId)
            ?.addQueryParameter("categories", catsJson)
            ?.build() ?: return@withContext emptyList()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "BrootLite-TV/1.0")
            .get()
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList()
                val dtos = json.decodeFromString<List<SponsorBlockItemDto>>(body)
                dtos.mapNotNull { dto ->
                    if (dto.segment.size < 2) return@mapNotNull null
                    val startMs = (dto.segment[0] * 1000.0).toLong().coerceAtLeast(0L)
                    val endMs = (dto.segment[1] * 1000.0).toLong()
                    if (endMs <= startMs) return@mapNotNull null
                    SponsorSegment(
                        uuid = dto.uuid,
                        category = dto.category,
                        startMs = startMs,
                        endMs = endMs,
                        actionType = dto.actionType
                    )
                }.sortedBy { it.startMs }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Fetches community like/dislike counts & rating from ReturnYouTubeDislike.
     */
    suspend fun fetchReturnYouTubeDislike(videoId: String): RydVotes? = withContext(Dispatchers.IO) {
        val cleanId = extractCleanVideoId(videoId)
        val url = RYD_API.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("videoId", cleanId)
            ?.build() ?: return@withContext null

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "BrootLite-TV/1.0")
            .get()
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                val dto = json.decodeFromString<RydApiResponse>(body)
                RydVotes(
                    likes = dto.likes,
                    dislikes = dto.dislikes,
                    rating = dto.rating,
                    viewCount = dto.viewCount
                )
            }
        }.getOrNull()
    }

    /**
     * Optional DeArrow clickbait-free titles & thumbnails with RAM [LruCache].
     * Zero network calls when [deArrowEnabled] is `false`.
     */
    suspend fun applyDeArrowIfEnabled(
        videoItems: List<YouTubeVideoCard>,
        deArrowEnabled: Boolean
    ): List<YouTubeVideoCard> = withContext(Dispatchers.IO) {
        if (!deArrowEnabled || videoItems.isEmpty()) return@withContext videoItems

        // Resolve up to 24 visible cards per batch with bounded concurrency of 6
        val candidates = videoItems.take(24)
        val uncachedIds = candidates.map { it.videoId }.distinct().filter { id ->
            synchronized(deArrowCache) { deArrowCache.get(id) == null }
        }

        if (uncachedIds.isNotEmpty()) {
            coroutineScope {
                for (chunk in uncachedIds.chunked(6)) {
                    chunk.map { vid ->
                        async {
                            val entry = fetchSingleDeArrowBranding(vid)
                            synchronized(deArrowCache) {
                                deArrowCache.put(vid, entry)
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        videoItems.map { card ->
            val hit = synchronized(deArrowCache) { deArrowCache.get(card.videoId) }
            if (hit == null || (hit.title == null && hit.thumbnailUrl == null)) {
                card
            } else {
                card.copy(
                    title = hit.title ?: card.title,
                    thumbnailUrl = hit.thumbnailUrl ?: card.thumbnailUrl
                )
            }
        }
    }

    /**
     * Two-way Google Watch History & red-bar resume position sync.
     *
     * Sends `/api/stats/playback` on the first report for a [cpn] session, followed by
     * `/api/stats/watchtime` intervals chaining `st` -> `et` with OAuth2 Bearer authorization.
     */
    suspend fun reportWatchTime(
        playbackTrackingUrl: String?,
        watchtimeTrackingUrl: String?,
        cpn: String,
        positionSec: Float,
        durationSec: Float,
        authContext: YouTubeAuthContext?,
        videoId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (authContext == null || !authContext.isSignedIn) return@withContext false
        if (playbackTrackingUrl.isNullOrBlank() && watchtimeTrackingUrl.isNullOrBlank()) return@withContext false

        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext) ?: return@withContext false
        val effCpn = cpn.ifBlank { generateCpn() }
        val safePos = positionSec.coerceAtLeast(0f)
        val safeDur = durationSec.coerceAtLeast(safePos.coerceAtLeast(1f))
        val posStr = "%.2f".format(java.util.Locale.US, safePos)
        val durStr = "%.2f".format(java.util.Locale.US, safeDur)

        val session = synchronized(watchSessions) {
            val existing = watchSessions.getOrPut(effCpn) { WatchSessionState() }
            while (watchSessions.size > 16) {
                val eldest = watchSessions.keys.firstOrNull { it != effCpn } ?: break
                watchSessions.remove(eldest)
            }
            existing
        }

        val elapsedSessionSec = "%.1f".format(
            java.util.Locale.US,
            ((System.currentTimeMillis() - session.startedAtMs).coerceAtLeast(100L)) / 1000.0
        )

        var ok = true

        // Step 1: Fire `/api/stats/playback` once per session to record the video in Watch History
        if (!session.playbackPingSent && !playbackTrackingUrl.isNullOrBlank()) {
            val pbUrl = buildTrackingPingUrl(
                rawBaseUrl = playbackTrackingUrl,
                params = buildMap {
                    put("ver", "2")
                    put("cpn", effCpn)
                    put("cmt", posStr)
                    put("len", durStr)
                    put("rt", elapsedSessionSec)
                    put("euri", "")
                    put("lact", "1000")
                    put("state", "playing")
                    put("volume", "100")
                    if (!videoId.isNullOrBlank()) put("docid", videoId)
                }
            )
            if (pbUrl != null && executeAuthenticatedTrackingGet(pbUrl, accessToken, authContext.onBehalfOfUser)) {
                session.playbackPingSent = true
            } else {
                ok = false
            }
        }

        // Step 2: Fire `/api/stats/watchtime` with `st` -> `et` to update red resume progress bar
        if (!watchtimeTrackingUrl.isNullOrBlank()) {
            val startSec = if (safePos >= session.lastReportedSec) session.lastReportedSec else safePos
            val stStr = "%.2f".format(java.util.Locale.US, startSec)
            val wtUrl = buildTrackingPingUrl(
                rawBaseUrl = watchtimeTrackingUrl,
                params = buildMap {
                    put("ver", "2")
                    put("cpn", effCpn)
                    put("cmt", posStr)
                    put("len", durStr)
                    put("st", stStr)
                    put("et", posStr)
                    put("rt", elapsedSessionSec)
                    put("state", "playing")
                    put("lact", "1000")
                    if (!videoId.isNullOrBlank()) put("docid", videoId)
                }
            )
            if (wtUrl != null && executeAuthenticatedTrackingGet(wtUrl, accessToken, authContext.onBehalfOfUser)) {
                session.lastReportedSec = safePos
            } else {
                ok = false
            }
        }

        ok
    }

    // =========================================================================
    // Internal Helpers: Stream Parsing, Cipher Solving & InnerTube Transport
    // =========================================================================

    private suspend fun buildResolvedStreamFromPlayerJson(
        videoId: String,
        playerJson: JsonObject,
        profile: PlayerProfile,
        cpn: String,
        preferredMaxHeight: Int,
        context: Context
    ): ResolvedYouTubeStream? {
        val streamingData = playerJson["streamingData"] as? JsonObject ?: return null
        val videoDetails = playerJson["videoDetails"] as? JsonObject

        val title = videoDetails?.get("title")?.jsonPrimitive?.contentOrNull ?: videoId
        val author = videoDetails?.get("author")?.jsonPrimitive?.contentOrNull ?: ""
        val channelId = videoDetails?.get("channelId")?.jsonPrimitive?.contentOrNull
        val durationSec = videoDetails?.get("lengthSeconds")?.jsonPrimitive?.longOrNull ?: 0L
        val viewCount = videoDetails?.get("viewCount")?.jsonPrimitive?.longOrNull
        val shortDesc = videoDetails?.get("shortDescription")?.jsonPrimitive?.contentOrNull
        val isLiveContent = videoDetails?.get("isLiveContent")?.jsonPrimitive?.booleanOrNull == true
        val isLiveNow = videoDetails?.get("isLive")?.jsonPrimitive?.booleanOrNull == true
        val thumbUrl = YouTubeInnerTubeParser.extractThumbnailUrl(videoDetails?.get("thumbnail"))
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        val hlsManifestUrl = streamingData["hlsManifestUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val isLive = isLiveNow || (isLiveContent && durationSec == 0L && !hlsManifestUrl.isNullOrBlank())

        val muxedFormats = (streamingData["formats"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val adaptiveFormats = (streamingData["adaptiveFormats"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        // Collect all pending `sig` and `n` challenges across all formats and solve in one QuickJS pass
        val pendingSigs = LinkedHashSet<String>()
        val pendingNs = LinkedHashSet<String>()
        for (fmt in muxedFormats + adaptiveFormats) {
            QuickJsSignatureSolver.extractFormatPendingChallenges(
                directUrl = fmt["url"]?.jsonPrimitive?.contentOrNull,
                signatureCipher = (fmt["signatureCipher"] ?: fmt["cipher"])?.jsonPrimitive?.contentOrNull,
                outSignatures = pendingSigs,
                outNParams = pendingNs
            )
        }

        val solved = if (pendingSigs.isNotEmpty() || pendingNs.isNotEmpty()) {
            QuickJsSignatureSolver.solveChallenges(
                context = context,
                signatures = pendingSigs,
                nParams = pendingNs,
                videoIdForBootstrap = videoId
            )
        } else {
            QuickJsSignatureSolver.SolvedChallenges()
        }

        // Parse adaptive video & audio formats
        val rawVideoVariants = ArrayList<YouTubeQualityVariant>()
        val rawAudioTracks = ArrayList<YouTubeAudioTrack>()

        for (fmt in adaptiveFormats) {
            // Skip OTF (On-The-Fly chunked live initialization) formats that lack seekable ranges
            if (fmt["type"]?.jsonPrimitive?.contentOrNull == "FORMAT_STREAM_TYPE_OTF") continue

            val mimeFull = fmt["mimeType"]?.jsonPrimitive?.contentOrNull ?: continue
            val resolvedUrl = QuickJsSignatureSolver.decipherFormatUrl(
                directUrl = fmt["url"]?.jsonPrimitive?.contentOrNull,
                signatureCipher = (fmt["signatureCipher"] ?: fmt["cipher"])?.jsonPrimitive?.contentOrNull,
                solved = solved
            ) ?: continue

            val itag = fmt["itag"]?.jsonPrimitive?.intOrNull ?: 0
            val codecs = Regex("""codecs="([^"]+)"""").find(mimeFull)?.groupValues?.getOrNull(1)
            val bitrate = fmt["bitrate"]?.jsonPrimitive?.longOrNull
                ?: fmt["averageBitrate"]?.jsonPrimitive?.longOrNull
                ?: 0L
            val contentLength = fmt["contentLength"]?.jsonPrimitive?.longOrNull
            val approxDurMs = fmt["approxDurationMs"]?.jsonPrimitive?.longOrNull
            val initRange = parseByteRange(fmt["initRange"])
            val indexRange = parseByteRange(fmt["indexRange"])

            if (mimeFull.startsWith("video/")) {
                val width = fmt["width"]?.jsonPrimitive?.intOrNull ?: continue
                val height = fmt["height"]?.jsonPrimitive?.intOrNull ?: continue
                val fps = fmt["fps"]?.jsonPrimitive?.intOrNull ?: 30
                val colorTransfer = (fmt["colorInfo"] as? JsonObject)
                    ?.get("transferCharacteristics")?.jsonPrimitive?.contentOrNull.orEmpty()
                val isHdr = colorTransfer.contains("SMPTEST2084", true) || colorTransfer.contains("ARIB_STD_B67", true)
                val label = formatQualityLabel(height, fps, isHdr)

                rawVideoVariants.add(
                    YouTubeQualityVariant(
                        label = label,
                        itag = itag,
                        videoUrl = resolvedUrl,
                        mimeType = mimeFull,
                        codecs = codecs,
                        width = width,
                        height = height,
                        fps = fps,
                        bitrate = bitrate,
                        contentLength = contentLength,
                        initRange = initRange,
                        indexRange = indexRange,
                        approxDurationMs = approxDurMs,
                        isHdr = isHdr
                    )
                )
            } else if (mimeFull.startsWith("audio/")) {
                val audioTrackObj = fmt["audioTrack"] as? JsonObject
                val isDefaultAudio = audioTrackObj?.get("audioIsDefault")?.jsonPrimitive?.booleanOrNull ?: true
                val langCode = audioTrackObj?.get("id")?.jsonPrimitive?.contentOrNull?.substringBefore(".")
                val dispName = audioTrackObj?.get("displayName")?.jsonPrimitive?.contentOrNull
                val sampleRate = fmt["audioSampleRate"]?.jsonPrimitive?.intOrNull
                val channels = fmt["audioChannels"]?.jsonPrimitive?.intOrNull

                rawAudioTracks.add(
                    YouTubeAudioTrack(
                        itag = itag,
                        audioUrl = resolvedUrl,
                        mimeType = mimeFull,
                        codecs = codecs,
                        bitrate = bitrate,
                        sampleRate = sampleRate,
                        channels = channels,
                        languageCode = langCode,
                        displayName = dispName,
                        contentLength = contentLength,
                        initRange = initRange,
                        indexRange = indexRange,
                        approxDurationMs = approxDurMs,
                        isDefault = isDefaultAudio
                    )
                )
            }
        }

        // Deduplicate video quality variants per label (e.g. 2160p 4K, 1440p, 1080p60, 720p60, 480p, 360p),
        // preferring hardware-accelerated H.264 (avc1) / VP9 (vp9) SDR over software AV1 (av01) on Android TV!
        val deduplicatedVariants = rawVideoVariants
            .sortedWith(
                compareByDescending<YouTubeQualityVariant> { it.height }
                    .thenByDescending { it.fps > 30 }
                    .thenBy { it.isHdr } // Prefer SDR on non-HDR projectors/displays
                    .thenByDescending { codecHardwareScore(it.codecs) }
                    .thenByDescending { it.bitrate }
            )
            .distinctBy { "${it.height}_${if (it.fps > 30) 60 else 30}" }

        // Sort audio tracks: default language track first, Opus (251) / AAC (140) stereo preferred over 5.1 surround
        val sortedAudioTracks = rawAudioTracks.sortedWith(
            compareByDescending<YouTubeAudioTrack> { it.isDefault }
                .thenByDescending { (it.channels ?: 2) <= 2 }
                .thenByDescending { it.itag == 251 || it.itag == 140 }
                .thenByDescending { it.bitrate }
        )

        // Parse muxed progressive formats (itag 22 = 720p MP4, itag 18 = 360p MP4)
        var bestMuxedUrl: String? = null
        var bestMuxedHeight: Int? = null
        for (mFmt in muxedFormats.sortedByDescending { it["height"]?.jsonPrimitive?.intOrNull ?: 0 }) {
            val mUrl = QuickJsSignatureSolver.decipherFormatUrl(
                directUrl = mFmt["url"]?.jsonPrimitive?.contentOrNull,
                signatureCipher = (mFmt["signatureCipher"] ?: mFmt["cipher"])?.jsonPrimitive?.contentOrNull,
                solved = solved
            ) ?: continue
            bestMuxedUrl = mUrl
            bestMuxedHeight = mFmt["height"]?.jsonPrimitive?.intOrNull
            break
        }

        // Must have at least HLS, split adaptive video+audio, or muxed progressive URL
        val hasSplit = deduplicatedVariants.isNotEmpty() && sortedAudioTracks.isNotEmpty()
        if (hlsManifestUrl == null && !hasSplit && bestMuxedUrl == null) {
            return null
        }

        val chosenVideo = deduplicatedVariants.firstOrNull { it.height <= preferredMaxHeight }
            ?: deduplicatedVariants.lastOrNull()
        val chosenAudio = sortedAudioTracks.firstOrNull()

        val captions = parseCaptionTracks(playerJson)
        val trackingObj = playerJson["playbackTracking"] as? JsonObject
        val playbackTrackingUrl = (trackingObj?.get("videostatsPlaybackUrl") as? JsonObject)
            ?.get("baseUrl")?.jsonPrimitive?.contentOrNull
        val watchtimeTrackingUrl = (trackingObj?.get("videostatsWatchtimeUrl") as? JsonObject)
            ?.get("baseUrl")?.jsonPrimitive?.contentOrNull

        val streamHeaders = buildMap {
            put("User-Agent", profile.userAgent)
            if (profile == PlayerProfile.WEB_SAFARI || profile == PlayerProfile.WEB_EMBED ||
                profile == PlayerProfile.TV_TIZEN || profile == PlayerProfile.TV_SIMPLY
            ) {
                put("Origin", "https://www.youtube.com")
                put("Referer", "https://www.youtube.com/")
            }
        }

        return ResolvedYouTubeStream(
            videoId = videoId,
            title = title,
            author = author,
            channelId = channelId,
            durationSec = durationSec,
            viewCount = viewCount,
            shortDescription = shortDesc,
            thumbnailUrl = thumbUrl,
            isLive = isLive,
            clientUsed = profile.label,
            userAgent = profile.userAgent,
            headers = streamHeaders,
            hlsManifestUrl = hlsManifestUrl,
            adaptiveVideoUrl = chosenVideo?.videoUrl,
            mimeType = chosenVideo?.mimeType,
            codecs = chosenVideo?.codecs,
            width = chosenVideo?.width,
            height = chosenVideo?.height,
            fps = chosenVideo?.fps,
            bitrate = chosenVideo?.bitrate,
            contentLength = chosenVideo?.contentLength,
            initRange = chosenVideo?.initRange,
            indexRange = chosenVideo?.indexRange,
            adaptiveAudioUrl = chosenAudio?.audioUrl,
            adaptiveAudioMimeType = chosenAudio?.mimeType,
            adaptiveAudioCodecs = chosenAudio?.codecs,
            adaptiveAudioBitrate = chosenAudio?.bitrate,
            adaptiveAudioContentLength = chosenAudio?.contentLength,
            adaptiveAudioInitRange = chosenAudio?.initRange,
            adaptiveAudioIndexRange = chosenAudio?.indexRange,
            audioTracks = sortedAudioTracks,
            muxedUrl = bestMuxedUrl,
            muxedHeight = bestMuxedHeight,
            qualityVariants = deduplicatedVariants,
            captionTracks = captions,
            playbackTrackingUrl = playbackTrackingUrl,
            watchtimeTrackingUrl = watchtimeTrackingUrl,
            cpn = cpn
        )
    }

    private fun parseCaptionTracks(playerJson: JsonObject): List<YouTubeCaptionTrack> {
        val trackList = ((playerJson["captions"] as? JsonObject)
            ?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks") as? JsonArray ?: return emptyList()

        return trackList.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val langCode = obj["languageCode"]?.jsonPrimitive?.contentOrNull ?: "und"
            val name = YouTubeInnerTubeParser.extractText(obj["name"]) ?: langCode
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
            val isAuto = kind == "asr"
            val translatable = obj["isTranslatable"]?.jsonPrimitive?.booleanOrNull == true
            val vttUrl = QuickJsSignatureSolver.appendOrReplaceQueryParam(baseUrl, "fmt", "vtt")
            val srtUrl = QuickJsSignatureSolver.appendOrReplaceQueryParam(baseUrl, "fmt", "srt1")

            YouTubeCaptionTrack(
                languageCode = langCode,
                name = name,
                vttUrl = vttUrl,
                srtUrl = srtUrl,
                baseUrl = baseUrl,
                isAutoGenerated = isAuto,
                isTranslatable = translatable
            )
        }
    }

    private fun parseByteRange(element: JsonElement?): ByteRange? {
        val obj = element as? JsonObject ?: return null
        val start = obj["start"]?.jsonPrimitive?.longOrNull ?: return null
        val end = obj["end"]?.jsonPrimitive?.longOrNull ?: return null
        if (end < start) return null
        return ByteRange(start, end)
    }

    private fun formatQualityLabel(height: Int, fps: Int, isHdr: Boolean): String {
        val base = when {
            height >= 2160 -> "2160p 4K"
            height >= 1440 -> "1440p"
            fps > 30 -> "${height}p60"
            else -> "${height}p"
        }
        return if (isHdr) "$base HDR" else base
    }

    private fun codecHardwareScore(codecs: String?): Int {
        val c = codecs?.lowercase().orEmpty()
        return when {
            c.startsWith("avc1") -> 3 // Best hardware decode at <=1080p60 on 32-bit MediaTek/Amlogic
            c.startsWith("vp9") || c.startsWith("vp09") -> 2 // Hardware decode for 1440p/4K
            c.startsWith("av01") -> 1 // Software decode fallback
            else -> 0
        }
    }

    private fun requestPlayerJson(
        videoId: String,
        profile: PlayerProfile,
        cpn: String,
        visitorData: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        accessToken: String?,
        pageId: String?,
        language: String,
        region: String
    ): JsonObject? {
        val payload = buildJsonObject {
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", profile.clientName)
                    put("clientVersion", profile.clientVersion)
                    put("hl", language)
                    put("gl", region)
                    if (!visitorData.isNullOrBlank()) {
                        put("visitorData", visitorData)
                    }
                    when (profile) {
                        PlayerProfile.ANDROID_VR -> {
                            put("deviceMake", "Oculus")
                            put("deviceModel", "Quest 3")
                            put("osName", "Android")
                            put("osVersion", "14")
                            put("androidSdkVersion", 34)
                        }
                        PlayerProfile.TV_TIZEN -> {
                            put("clientScreen", "WATCH")
                            put("deviceMake", "Samsung")
                            put("deviceModel", "UKS9800")
                            put("osName", "Tizen")
                            put("osVersion", "4.0.0.2")
                            put("webpSupport", false)
                        }
                        PlayerProfile.TV_SIMPLY -> {
                            put("clientScreen", "EMBED")
                        }
                        PlayerProfile.VISIONOS -> {
                            put("deviceMake", "Apple")
                            put("deviceModel", "RealityDevice14,1")
                            put("osName", "visionOS")
                            put("osVersion", "2.3.22N842")
                        }
                        PlayerProfile.IOS -> {
                            put("deviceMake", "Apple")
                            put("deviceModel", "iPhone16,2")
                            put("osName", "iPhone")
                            put("osVersion", "18.3.2.22D82")
                        }
                        PlayerProfile.WEB_SAFARI -> {
                            put("platform", "DESKTOP")
                        }
                        PlayerProfile.WEB_EMBED -> {
                            put("clientScreen", "EMBED")
                        }
                    }
                }
                if (profile.isEmbedded) {
                    putJsonObject("thirdParty") {
                        put("embedUrl", if (profile == PlayerProfile.TV_SIMPLY) "https://www.youtube.com/tv" else "https://www.youtube.com/")
                    }
                }
                if (!pageId.isNullOrBlank()) {
                    putJsonObject("user") {
                        put("onBehalfOfUser", pageId)
                        put("lockedSafetyMode", false)
                    }
                }
            }
            if (signatureTimestamp != null) {
                putJsonObject("playbackContext") {
                    putJsonObject("contentPlaybackContext") {
                        put("signatureTimestamp", signatureTimestamp)
                        put("html5Preference", "HTML5_PREF_WANTS")
                    }
                }
            }
            if (!poToken.isNullOrBlank()) {
                putJsonObject("serviceIntegrityDimensions") {
                    put("poToken", poToken)
                }
            }
        }.toString()

        val reqBuilder = Request.Builder()
            .url("$YT_API_BASE/player?prettyPrint=false")
            .header("User-Agent", profile.userAgent)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", profile.clientIdHeader)
            .header("X-Youtube-Client-Version", profile.clientVersion)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))

        if (!visitorData.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)
        }
        if (!accessToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $accessToken")
            if (!pageId.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Pageid", pageId)
            }
        }

        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) null else json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull()
    }

    private fun fetchReelMuxedFallback(videoId: String, visitorData: String?): Pair<String, Int?>? {
        val nonce = generateCpn().take(12)
        val url = "$YT_API_BASE/reel/reel_item_watch?prettyPrint=false&t=$nonce&id=$videoId&\$fields=playerResponse"
        val payload = buildJsonObject {
            putJsonObject("playerRequest") {
                put("videoId", videoId)
                put("racyCheckOk", true)
                put("contentCheckOk", true)
            }
            put("disablePlayerResponse", false)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID")
                    put("clientVersion", ANDROID_REEL_VERSION)
                    put("androidSdkVersion", 34)
                    put("osName", "Android")
                    put("osVersion", "14")
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                }
            }
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ANDROID_REEL_UA)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "3")
            .header("X-Youtube-Client-Version", ANDROID_REEL_VERSION)
            .apply {
                if (!visitorData.isNullOrBlank()) header("X-Goog-Visitor-Id", visitorData)
            }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val pr = root["playerResponse"] as? JsonObject ?: return@use null
                val formats = ((pr["streamingData"] as? JsonObject)?.get("formats") as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.sortedByDescending { it["height"]?.jsonPrimitive?.intOrNull ?: 0 }
                    .orEmpty()
                val best = formats.firstOrNull { !it["url"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
                    ?: return@use null
                val mUrl = best["url"]!!.jsonPrimitive.content
                val mHeight = best["height"]?.jsonPrimitive?.intOrNull
                mUrl to mHeight
            }
        }.getOrNull()
    }

    private fun fetchAuthenticatedTrackingUrls(
        videoId: String,
        cpn: String,
        accessToken: String,
        pageId: String?,
        visitorData: String?,
        sigTimestamp: Int?,
        language: String,
        region: String
    ): Pair<String?, String?>? {
        val pr = requestPlayerJson(
            videoId = videoId,
            profile = PlayerProfile.TV_TIZEN,
            cpn = cpn,
            visitorData = visitorData,
            signatureTimestamp = sigTimestamp,
            poToken = null,
            accessToken = accessToken,
            pageId = pageId,
            language = language,
            region = region
        ) ?: return null
        val tracking = pr["playbackTracking"] as? JsonObject ?: return null
        val pb = (tracking["videostatsPlaybackUrl"] as? JsonObject)?.get("baseUrl")?.jsonPrimitive?.contentOrNull
        val wt = (tracking["videostatsWatchtimeUrl"] as? JsonObject)?.get("baseUrl")?.jsonPrimitive?.contentOrNull
        if (pb == null && wt == null) return null
        return pb to wt
    }

    private fun executeBrowseFeed(
        browseId: String?,
        continuation: String?,
        params: String?,
        authContext: YouTubeAuthContext?,
        accessToken: String?,
        useTvClient: Boolean
    ): YouTubeFeedPage {
        val vData = authContext?.visitorData ?: cachedVisitorData
        val body = buildInnerTubeBody(authContext, useTvClient, vData) {
            if (!continuation.isNullOrBlank()) {
                put("continuation", continuation)
            } else if (!browseId.isNullOrBlank()) {
                put("browseId", browseId)
                if (!params.isNullOrBlank()) put("params", params)
            }
        }
        val root = postInnerTubeJson(
            endpoint = "browse",
            bodyJson = body,
            useTvClient = useTvClient,
            accessToken = if (useTvClient) accessToken else null,
            pageId = if (useTvClient) authContext?.onBehalfOfUser else null,
            visitorData = vData
        ) ?: return YouTubeFeedPage(items = emptyList())

        YouTubeInnerTubeParser.extractVisitorData(root)?.let { cachedVisitorData = it }
        return YouTubeInnerTubeParser.parseFeedPage(root)
    }

    private suspend fun mutateSubscription(
        endpoint: String,
        channelId: String,
        authContext: YouTubeAuthContext,
        params: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanChannel = channelId.trim()
        if (cleanChannel.isEmpty()) return@withContext false
        val accessToken = YouTubeAuthClient.getValidAccessToken(authContext) ?: return@withContext false

        val body = buildInnerTubeBody(authContext, useTvClient = true, visitorData = authContext.visitorData) {
            putJsonArray("channelIds") { add(JsonPrimitive(cleanChannel)) }
            if (!params.isNullOrBlank()) put("params", params)
        }

        val resp = postInnerTubeJson(
            endpoint = endpoint,
            bodyJson = body,
            useTvClient = true,
            accessToken = accessToken,
            pageId = authContext.onBehalfOfUser,
            visitorData = authContext.visitorData
        )
        resp != null && !resp.containsKey("error")
    }

    private fun resolveChannelHandleOrUrl(handleOrUrl: String, authContext: YouTubeAuthContext?): String? {
        val fullUrl = when {
            handleOrUrl.startsWith("http://") || handleOrUrl.startsWith("https://") -> handleOrUrl
            handleOrUrl.startsWith("@") -> "https://www.youtube.com/$handleOrUrl"
            else -> "https://www.youtube.com/@$handleOrUrl"
        }
        val body = buildInnerTubeBody(authContext, useTvClient = false, visitorData = cachedVisitorData) {
            put("url", fullUrl)
        }
        val root = postInnerTubeJson(
            endpoint = "navigation/resolve_url",
            bodyJson = body,
            useTvClient = false,
            accessToken = null,
            pageId = null,
            visitorData = cachedVisitorData
        ) ?: return null
        return YouTubeInnerTubeParser.findFirstStringByKey(root["endpoint"], "browseId")
    }

    private fun buildInnerTubeBody(
        authContext: YouTubeAuthContext?,
        useTvClient: Boolean,
        visitorData: String?,
        extraBuilder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ): String = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                if (useTvClient) {
                    put("clientName", "TVHTML5")
                    put("clientVersion", YouTubeAuthClient.TV_CLIENT_VERSION)
                    put("clientScreen", "BROWSE")
                } else {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_CLIENT_VERSION)
                    put("platform", "DESKTOP")
                }
                put("hl", authContext?.language ?: "en")
                put("gl", authContext?.region ?: "US")
                if (!visitorData.isNullOrBlank()) {
                    put("visitorData", visitorData)
                }
            }
            val pageId = authContext?.onBehalfOfUser
            if (useTvClient && !pageId.isNullOrBlank()) {
                putJsonObject("user") {
                    put("onBehalfOfUser", pageId)
                    put("lockedSafetyMode", false)
                }
            }
        }
        extraBuilder()
    }.toString()

    private fun postInnerTubeJson(
        endpoint: String,
        bodyJson: String,
        useTvClient: Boolean,
        accessToken: String?,
        pageId: String?,
        visitorData: String?
    ): JsonObject? {
        val reqBuilder = Request.Builder()
            .url("$YT_API_BASE/$endpoint?prettyPrint=false")
            .header("User-Agent", if (useTvClient) YouTubeAuthClient.TV_COBALT_UA else WEB_UA)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", if (useTvClient) "7" else "1")
            .header(
                "X-Youtube-Client-Version",
                if (useTvClient) YouTubeAuthClient.TV_CLIENT_VERSION else WEB_CLIENT_VERSION
            )
            .header("Origin", "https://www.youtube.com")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))

        if (!visitorData.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)
        }
        if (!accessToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $accessToken")
            if (!pageId.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Pageid", pageId)
            }
        }

        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) null else json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull()
    }

    private suspend fun maybeApplyDeArrowToPage(
        page: YouTubeFeedPage,
        authContext: YouTubeAuthContext?
    ): YouTubeFeedPage {
        if (authContext?.deArrowEnabled != true || page.items.isEmpty()) return page
        val updatedItems = applyDeArrowIfEnabled(page.items, true)
        val byId = updatedItems.associateBy { it.videoId }
        val updatedShelves = page.shelves.map { shelf ->
            shelf.copy(videos = shelf.videos.map { byId[it.videoId] ?: it })
        }
        return page.copy(items = updatedItems, shelves = updatedShelves)
    }

    private fun fetchSingleDeArrowBranding(videoId: String): DeArrowEntry {
        val url = "$SPONSORBLOCK_API/branding?videoID=${URLEncoder.encode(videoId, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "BrootLite-TV/1.0")
            .get()
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use DeArrowEntry(null, null)
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val titles = root["titles"] as? JsonArray
                val bestTitleObj = titles?.firstOrNull()?.let { it as? JsonObject }
                val votes = bestTitleObj?.get("votes")?.jsonPrimitive?.intOrNull ?: 0
                val locked = bestTitleObj?.get("locked")?.jsonPrimitive?.booleanOrNull == true
                val cleanTitle = if (locked || votes >= 0) {
                    bestTitleObj?.get("title")?.jsonPrimitive?.contentOrNull
                        ?.replace(">", "")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                } else null

                val thumbs = root["thumbnails"] as? JsonArray
                val bestThumbObj = thumbs?.firstOrNull()?.let { it as? JsonObject }
                val thumbVotes = bestThumbObj?.get("votes")?.jsonPrimitive?.intOrNull ?: 0
                val thumbLocked = bestThumbObj?.get("locked")?.jsonPrimitive?.booleanOrNull == true
                val original = bestThumbObj?.get("original")?.jsonPrimitive?.booleanOrNull == true
                val timestamp = bestThumbObj?.get("timestamp")?.jsonPrimitive?.doubleOrNull
                val thumbUrl = if (!original && (thumbLocked || thumbVotes >= 0) && timestamp != null) {
                    "$DEARROW_THUMB_API?videoID=$videoId&time=$timestamp"
                } else null

                DeArrowEntry(cleanTitle, thumbUrl)
            }
        }.getOrDefault(DeArrowEntry(null, null))
    }

    private suspend fun fetchAnonymousRssSubscriptions(channelIds: List<String>): List<YouTubeVideoCard> =
        coroutineScope {
            data class DatedCard(val epochSec: Long, val card: YouTubeVideoCard)

            val results = ArrayList<DatedCard>()
            for (chunk in channelIds.distinct().take(48).chunked(6)) {
                val chunkItems = chunk.map { cid ->
                    async { parseChannelAtomFeed(cid) }
                }.awaitAll()
                for (list in chunkItems) {
                    for ((ts, card) in list) results.add(DatedCard(ts, card))
                }
            }
            results.sortedByDescending { it.epochSec }
                .map { it.card }
                .distinctBy { it.videoId }
        }

    private fun parseChannelAtomFeed(channelId: String): List<Pair<Long, YouTubeVideoCard>> {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=${URLEncoder.encode(channelId, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .get()
            .build()

        return runCatching {
            val xml = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            } ?: return emptyList()

            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val entries = ArrayList<Pair<Long, YouTubeVideoCard>>()
            var inEntry = false
            var inAuthor = false
            var currentTag = ""
            var videoId = ""
            var title = ""
            var author = ""
            var publishedRaw = ""
            var thumbUrl: String? = null
            var views: String? = null

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.orEmpty()
                        if (currentTag == "entry") {
                            inEntry = true
                            videoId = ""
                            title = ""
                            author = ""
                            publishedRaw = ""
                            thumbUrl = null
                            views = null
                        } else if (inEntry && currentTag == "author") {
                            inAuthor = true
                        } else if (inEntry && currentTag == "thumbnail") {
                            thumbUrl = parser.getAttributeValue(null, "url") ?: thumbUrl
                        } else if (inEntry && currentTag == "statistics") {
                            val rawV = parser.getAttributeValue(null, "views")?.toLongOrNull()
                            if (rawV != null) views = formatCompactCount(rawV) + " views"
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inEntry) {
                            val txt = parser.text?.trim().orEmpty()
                            if (txt.isNotEmpty()) {
                                when {
                                    currentTag == "videoId" -> videoId = txt
                                    currentTag == "title" && title.isEmpty() -> title = txt
                                    inAuthor && currentTag == "name" -> author = txt
                                    currentTag == "published" -> publishedRaw = txt
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val endName = parser.name.orEmpty()
                        if (endName == "author") inAuthor = false
                        if (endName == "entry" && inEntry) {
                            inEntry = false
                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                val epoch = runCatching { OffsetDateTime.parse(publishedRaw).toEpochSecond() }
                                    .getOrDefault(0L)
                                val pubDate = publishedRaw.substringBefore("T").takeIf { it.isNotBlank() }
                                entries.add(
                                    epoch to YouTubeVideoCard(
                                        videoId = videoId,
                                        title = title,
                                        channelName = author,
                                        channelId = channelId,
                                        thumbnailUrl = thumbUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                        viewCountText = views,
                                        publishedText = pubDate
                                    )
                                )
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
            entries
        }.getOrDefault(emptyList())
    }

    private fun buildTrackingPingUrl(rawBaseUrl: String, params: Map<String, String>): String? {
        val normalized = rawBaseUrl.replace("youtube.com/api/stats/atr", "youtube.com/api/stats/playback")
        val builder = normalized.toHttpUrlOrNull()?.newBuilder() ?: return null
        for ((k, v) in params) {
            builder.removeAllQueryParameters(k)
            builder.addQueryParameter(k, v)
        }
        return builder.build().toString()
    }

    private fun executeAuthenticatedTrackingGet(
        url: String,
        accessToken: String,
        pageId: String?
    ): Boolean {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", TV_TIZEN_UA)
            .header("Authorization", "Bearer $accessToken")
            .get()
        if (!pageId.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Pageid", pageId)
        }
        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                resp.code in 200..299
            }
        }.getOrDefault(false)
    }

    private fun extractCleanVideoId(videoIdOrUrl: String): String {
        val trimmed = videoIdOrUrl.trim()
        if (!trimmed.contains("/") && !trimmed.contains("?")) return trimmed
        QuickJsSignatureSolver.extractQueryParam(trimmed, "v")?.takeIf { it.isNotBlank() }?.let { return it }
        return trimmed.substringBefore("?").substringBefore("&").substringAfterLast("/")
    }

    private fun formatCompactCount(count: Long): String = when {
        count >= 1_000_000_000L -> "%.1fB".format(java.util.Locale.US, count / 1_000_000_000.0)
        count >= 1_000_000L -> "%.1fM".format(java.util.Locale.US, count / 1_000_000.0)
        count >= 1_000L -> "%.1fK".format(java.util.Locale.US, count / 1_000.0)
        else -> count.toString()
    }

    private fun generateCpn(): String = buildString(16) {
        repeat(16) {
            append(cpnChars[secureRandom.nextInt(cpnChars.length)])
        }
    }
}
