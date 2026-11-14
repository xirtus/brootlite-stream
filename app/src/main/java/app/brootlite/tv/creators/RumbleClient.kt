package app.brootlite.tv.creators

import app.brootlite.tv.BrootLiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure-Kotlin Rumble client for BrootLite:
 * - Browsable feeds: Editor Picks / Featured (`/editor-picks`), Live (`/browse/live`),
 *   Categories (`/category/{slug}` & `/category/{slug}/videos` parsing `<rum-videos-grid>` JSON
 *   + `.videostream` HTML cards), Channel feeds (`/c/...` & `/user/...`), Followed channels, and Search.
 * - Zero-Ad Direct Stream Resolver: resolves public page path -> `embedId` via
 *   `https://wn0.rumble.com/api/Media/oembed.json` -> direct ad-free HLS & MP4 streams via
 *   `https://wn0.rumble.com/embedJS/u3/?request=video&ver=2&v={embedId}` (ignoring VAST `ad_tags`).
 */
object RumbleClient {

    private const val RUMBLE_BASE = "https://rumble.com"
    private const val WN0_OEMBED_URL = "https://wn0.rumble.com/api/Media/oembed.json"
    private const val WN0_EMBED_JS_URL = "https://wn0.rumble.com/embedJS/u3/"

    private const val WEB_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Bounded in-memory CookieJar so Rumble's 307 session-cookie redirects resolve cleanly with ~0 RAM
    private val rumbleCookieJar = object : CookieJar {
        private val store = LinkedHashMap<String, Cookie>(32, 0.75f, true)

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                store["${c.domain}|${c.path}|${c.name}"] = c
            }
            while (store.size > 64) {
                val eldest = store.keys.firstOrNull() ?: break
                store.remove(eldest)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store.values.filter { it.expiresAt >= now && it.matches(url) }
        }
    }

    private val httpClient: OkHttpClient by lazy {
        BrootLiteApp.sharedHttpClient.newBuilder()
            .cookieJar(rumbleCookieJar)
            .build()
    }

    private val RUM_VIDEOS_GRID_REGEX = Regex(
        """<rum-videos-grid[^>]*>\s*<script[^>]*type="application/json"[^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE
    )
    private val VIDEOSTREAM_SPLIT_REGEX = Regex("""<div[^>]*class="[^"]*\bvideostream\b[^"]*"""")
    private val VIDEO_HREF_REGEX = Regex("""href="(/v[a-z0-9]{4,}[^"?]*\.html)[^"]*"""")
    private val THUMB_IMG_REGEX = Regex("""<img[^>]*class="[^"]*thumbnail__image[^"]*"[^>]*src="([^"]+)"""")
    private val TITLE_ATTR_REGEX = Regex("""<h3[^>]*class="[^"]*thumbnail__title[^"]*"[^>]*title="([^"]+)"""")
    private val TITLE_TEXT_REGEX = Regex("""<h3[^>]*class="[^"]*thumbnail__title[^"]*"[^>]*>([\s\S]*?)</h3>""")
    private val DURATION_BADGE_REGEX = Regex(
        """videostream__status--duration[^>]*>\s*([^<]+?)\s*</div>"""
    )
    private val LIVE_BADGE_REGEX = Regex("""videostream__status--live|thumbnail__thumb--live""")
    private val CHANNEL_LINK_REGEX = Regex(
        """<a[^>]*class="[^"]*channel__link[^"]*"[^>]*href="(/(?:c|user)/[^"?]+)[^"]*""""
    )
    private val CHANNEL_IMG_REGEX = Regex(
        """<img[^>]*class="[^"]*channel__image[^"]*"[^>]*src="([^"]+)"(?:[^>]*title="([^"]*)")?"""
    )
    private val CHANNEL_NAME_SPAN_REGEX = Regex(
        """class="[^"]*(?:channel__name|user-row-name)[^"]*"[^>]*>\s*([^<]+?)\s*<"""
    )
    private val VIEWS_BADGE_REGEX = Regex(
        """videostream__number[^>]*>\s*([^<]+?)\s*<"""
    )
    private val EMBED_ID_IFRAME_REGEX = Regex("""rumble\.com/embed/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val EMBED_ID_JS_REGEX = Regex(""""video"\s*:\s*"(v[a-z0-9]+)"""")
    private val EMBED_CONFIG_HTML_REGEX = Regex("""Rumble\(\s*"play"\s*,\s*(\{[\s\S]*?\})\s*\)\s*;""")
    private val DDG_RUMBLE_LINK_REGEX = Regex("""rumble\.com(/v[a-z0-9]{4,}[^"&?#\s]*\.html)""", RegexOption.IGNORE_CASE)

    val DEFAULT_CATEGORIES = listOf(
        CreatorCategory("news", CreatorPlatform.RUMBLE, "News", "news"),
        CreatorCategory("gaming", CreatorPlatform.RUMBLE, "Gaming", "gaming"),
        CreatorCategory("technology", CreatorPlatform.RUMBLE, "Technology", "technology"),
        CreatorCategory("viral", CreatorPlatform.RUMBLE, "Viral", "viral"),
        CreatorCategory("podcasts", CreatorPlatform.RUMBLE, "Podcasts", "podcasts"),
        CreatorCategory("sports", CreatorPlatform.RUMBLE, "Sports", "sports"),
        CreatorCategory("science", CreatorPlatform.RUMBLE, "Science", "science"),
        CreatorCategory("entertainment", CreatorPlatform.RUMBLE, "Entertainment", "entertainment"),
        CreatorCategory("finance", CreatorPlatform.RUMBLE, "Finance", "finance")
    )

    fun getCategories(): List<CreatorCategory> = DEFAULT_CATEGORIES

    // =========================================================================
    // Browsable Feeds (Featured, Live, Category, Channel, Followed, Search)
    // =========================================================================

    suspend fun getFeaturedFeed(page: Int = 1): CreatorFeedPage = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val url = if (safePage == 1) "$RUMBLE_BASE/editor-picks" else "$RUMBLE_BASE/editor-picks?page=$safePage"
        val html = fetchHtml(url) ?: return@withContext CreatorFeedPage(emptyList())
        val items = parseRumblePageVideos(html)
        CreatorFeedPage(
            items = items,
            categories = if (safePage == 1) DEFAULT_CATEGORIES else emptyList(),
            nextCursor = if (items.size >= 12) (safePage + 1).toString() else null
        )
    }

    suspend fun getLiveStreams(page: Int = 1): CreatorFeedPage = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val url = if (safePage == 1) "$RUMBLE_BASE/browse/live" else "$RUMBLE_BASE/browse/live?page=$safePage"
        val html = fetchHtml(url) ?: return@withContext CreatorFeedPage(emptyList())
        val items = parseRumblePageVideos(html).map { it.copy(isLive = true, durationText = "LIVE") }
        CreatorFeedPage(
            items = items,
            nextCursor = if (items.size >= 12) (safePage + 1).toString() else null
        )
    }

    suspend fun getCategoryFeed(
        categorySlug: String,
        liveOnly: Boolean = false
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val cleanSlug = categorySlug.trim().removePrefix("/category/").trim('/')
        if (cleanSlug.isEmpty()) return@withContext CreatorFeedPage(emptyList())

        val targetUrl = if (liveOnly) {
            "$RUMBLE_BASE/category/$cleanSlug"
        } else {
            "$RUMBLE_BASE/category/$cleanSlug/videos"
        }
        val html = fetchHtml(targetUrl) ?: return@withContext CreatorFeedPage(emptyList())
        var items = parseRumblePageVideos(html)

        // If recorded videos tab was empty, also include active livestreams in that category
        if (items.isEmpty() && !liveOnly) {
            val liveHtml = fetchHtml("$RUMBLE_BASE/category/$cleanSlug")
            if (liveHtml != null) {
                items = parseRumblePageVideos(liveHtml)
            }
        }

        CreatorFeedPage(items = items)
    }

    /**
     * Fetches a Rumble channel (`/c/Name` or `/user/Name`) header + paginated uploads/livestreams.
     */
    suspend fun getChannelFeed(
        channelPathOrName: String,
        page: Int = 1
    ): Pair<CreatorChannel?, CreatorFeedPage> = withContext(Dispatchers.IO) {
        val normalizedPath = normalizeChannelPath(channelPathOrName)
        val safePage = page.coerceAtLeast(1)
        val url = if (safePage == 1) {
            "$RUMBLE_BASE$normalizedPath"
        } else {
            "$RUMBLE_BASE$normalizedPath?page=$safePage"
        }

        val html = fetchHtml(url) ?: return@withContext null to CreatorFeedPage(emptyList())

        val pageTitle = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.getOrNull(1)
            ?.let { decodeHtmlEntities(it).removeSuffix(" - Rumble").trim() }
            ?: normalizedPath.substringAfterLast("/")

        val avatar = Regex("""<img[^>]*class="[^"]*channel-header--img[^"]*"[^>]*src="([^"]+)"""").find(html)
            ?.groupValues?.getOrNull(1)
        val banner = Regex("""<img[^>]*class="[^"]*channel-header--backsplash-img[^"]*"[^>]*src="([^"]+)"""").find(html)
            ?.groupValues?.getOrNull(1)
        val followersTxt = Regex("""([\d.,]+[KMB]?)\s*Followers""", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.getOrNull(0)

        val videos = parseRumblePageVideos(
            html = html,
            defaultChannelName = pageTitle,
            defaultChannelId = normalizedPath,
            defaultChannelAvatar = avatar
        )
        val activeLive = videos.firstOrNull { it.isLive }

        val channel = CreatorChannel(
            id = normalizedPath,
            platform = CreatorPlatform.RUMBLE,
            displayName = pageTitle,
            handle = normalizedPath,
            avatarUrl = avatar,
            bannerUrl = banner,
            followerCountText = followersTxt,
            isLive = activeLive != null,
            liveVideo = activeLive
        )

        channel to CreatorFeedPage(
            items = videos,
            channels = listOf(channel),
            nextCursor = if (videos.size >= 12) (safePage + 1).toString() else null
        )
    }

    /**
     * Aggregates recent uploads & active livestreams from locally followed Rumble channels (`/c/...` or `/user/...`).
     */
    suspend fun getFollowedFeed(
        followedChannelPaths: List<String>
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val cleanPaths = followedChannelPaths.map { normalizeChannelPath(it) }.distinct().take(24)
        if (cleanPaths.isEmpty()) return@withContext CreatorFeedPage(emptyList())

        val allCards = ArrayList<CreatorVideoCard>()
        coroutineScope {
            for (chunk in cleanPaths.chunked(4)) {
                val pages = chunk.map { path ->
                    async { runCatching { getChannelFeed(path, page = 1).second.items.take(8) }.getOrDefault(emptyList()) }
                }.awaitAll()
                pages.forEach { allCards.addAll(it) }
            }
        }

        // Prioritize live streams at the top, followed by interleaved recent uploads
        val sorted = allCards.distinctBy { it.id }.sortedByDescending { it.isLive }
        CreatorFeedPage(items = sorted)
    }

    /**
     * Searches Rumble videos & channels.
     * Tries direct Rumble search first, with automatic Cloudflare-resilient fallback via
     * DuckDuckGo `site:rumble.com` + parallel `wn0.rumble.com/api/Media/oembed.json` enrichment!
     */
    suspend fun search(
        query: String,
        page: Int = 1
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext CreatorFeedPage(emptyList())

        val encodedQ = URLEncoder.encode(q, "UTF-8")
        val safePage = page.coerceAtLeast(1)
        val directUrl = if (safePage == 1) {
            "$RUMBLE_BASE/search/video?q=$encodedQ"
        } else {
            "$RUMBLE_BASE/search/video?q=$encodedQ&page=$safePage"
        }

        val directHtml = fetchHtml(directUrl)
        if (directHtml != null && !directHtml.contains("<title>Just a moment...</title>")) {
            val parsed = parseRumblePageVideos(directHtml)
            if (parsed.isNotEmpty()) {
                return@withContext CreatorFeedPage(
                    items = parsed,
                    nextCursor = if (parsed.size >= 12) (safePage + 1).toString() else null
                )
            }
        }

        // Resilient fallback when Cloudflare JS challenge blocks `/search/video`:
        if (safePage > 1) return@withContext CreatorFeedPage(emptyList())
        val ddgItems = searchViaDdgAndOembed(q)
        CreatorFeedPage(items = ddgItems)
    }

    // =========================================================================
    // Zero-Ad Direct Stream Resolver (`wn0.rumble.com/embedJS/u3/`)
    // =========================================================================

    /**
     * Resolves an ad-free Rumble stream (Live or VOD) with all quality variants (`1080p`, `720p`,
     * `480p`, `360p`, `240p` HLS & MP4) and WebVTT closed captions (`cc`).
     *
     * Accepts a relative path (`/v7i3zv4-....html`), full URL (`https://rumble.com/v...`),
     * or `embed:{embedId}` (`embed:v7fxmxm`).
     */
    suspend fun resolveStream(videoIdOrUrl: String): ResolvedCreatorStream = withContext(Dispatchers.IO) {
        val cleanInput = videoIdOrUrl.trim()
        if (cleanInput.isEmpty()) throw IOException("Empty Rumble video identifier")

        var oembedTitle: String? = null
        var oembedAuthor: String? = null
        var oembedChannelPath: String? = null
        var oembedThumb: String? = null

        val embedId: String = if (cleanInput.startsWith("embed:")) {
            cleanInput.removePrefix("embed:").trim()
        } else {
            val fullPageUrl = when {
                cleanInput.startsWith("http://") || cleanInput.startsWith("https://") -> cleanInput
                cleanInput.startsWith("/") -> "$RUMBLE_BASE$cleanInput"
                else -> "$RUMBLE_BASE/$cleanInput"
            }

            // 1. Primary: fast Cloudflare-exempt wn0 oembed.json lookup
            val oembedObj = fetchOembedJson(fullPageUrl)
            if (oembedObj != null) {
                oembedTitle = oembedObj["title"]?.jsonPrimitive?.contentOrNull
                oembedAuthor = oembedObj["author_name"]?.jsonPrimitive?.contentOrNull
                oembedChannelPath = oembedObj["author_url"]?.jsonPrimitive?.contentOrNull
                    ?.removePrefix(RUMBLE_BASE)
                oembedThumb = oembedObj["thumbnail_url"]?.jsonPrimitive?.contentOrNull
                val iframeHtml = oembedObj["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val extracted = EMBED_ID_IFRAME_REGEX.find(iframeHtml)?.groupValues?.getOrNull(1)
                if (!extracted.isNullOrBlank()) {
                    extracted
                } else {
                    extractEmbedIdFromVideoPage(fullPageUrl)
                }
            } else {
                extractEmbedIdFromVideoPage(fullPageUrl)
            }
        } ?: throw IOException("Unable to extract Rumble embed ID for $cleanInput")

        // 2. Fetch player stream manifest JSON from wn0.rumble.com/embedJS/u3/ (fallback to /embed/{id}/)
        val embedJson = fetchEmbedStreamJson(embedId)
            ?: throw IOException("Failed to fetch Rumble stream JSON for embedId=$embedId")

        val title = embedJson["title"]?.jsonPrimitive?.contentOrNull ?: oembedTitle ?: embedId
        val authorObj = embedJson["author"] as? JsonObject
        val authorName = authorObj?.get("name")?.jsonPrimitive?.contentOrNull ?: oembedAuthor ?: "Rumble"
        val channelPath = authorObj?.get("url")?.jsonPrimitive?.contentOrNull?.removePrefix(RUMBLE_BASE)
            ?: oembedChannelPath
        val thumb = embedJson["i"]?.jsonPrimitive?.contentOrNull ?: oembedThumb
        val liveCode = embedJson["live"]?.jsonPrimitive?.intOrNull ?: 0
        val isLive = liveCode == 1 || liveCode == 2

        val hlsVariants = ArrayList<CreatorQualityVariant>()
        val mp4Variants = ArrayList<CreatorQualityVariant>()
        var autoHlsMasterUrl: String? = null

        // Parse 2026 `ua` object: `ua.hls`, `ua.mp4`, `ua.tar`
        val uaObj = embedJson["ua"] as? JsonObject
        if (uaObj != null) {
            val uaHls = uaObj["hls"] as? JsonObject
            if (uaHls != null) {
                for ((key, valEl) in uaHls) {
                    val obj = valEl as? JsonObject ?: continue
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                    if (key.equals("auto", ignoreCase = true)) {
                        autoHlsMasterUrl = url
                    } else {
                        val meta = obj["meta"] as? JsonObject
                        val h = key.filter { it.isDigit() }.toIntOrNull()
                            ?: meta?.get("h")?.jsonPrimitive?.intOrNull
                        val w = meta?.get("w")?.jsonPrimitive?.intOrNull
                        val br = meta?.get("bitrate")?.jsonPrimitive?.longOrNull?.times(1000L)
                        val fps = meta?.get("fps")?.jsonPrimitive?.intOrNull
                        val label = if (h != null) "${h}p" else key
                        hlsVariants.add(
                            CreatorQualityVariant(
                                label = label,
                                url = url,
                                mimeType = "application/x-mpegURL",
                                width = w,
                                height = h,
                                fps = fps,
                                bitrate = br
                            )
                        )
                    }
                }
            }

            val uaMp4 = uaObj["mp4"] as? JsonObject
            if (uaMp4 != null) {
                for ((key, valEl) in uaMp4) {
                    val obj = valEl as? JsonObject ?: continue
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                    val meta = obj["meta"] as? JsonObject
                    val h = key.filter { it.isDigit() }.toIntOrNull()
                        ?: meta?.get("h")?.jsonPrimitive?.intOrNull
                    val w = meta?.get("w")?.jsonPrimitive?.intOrNull
                    val br = meta?.get("bitrate")?.jsonPrimitive?.longOrNull?.times(1000L)
                    val fps = meta?.get("fps")?.jsonPrimitive?.intOrNull
                    val label = if (h != null) "${h}p (MP4)" else "$key (MP4)"
                    mp4Variants.add(
                        CreatorQualityVariant(
                            label = label,
                            url = url,
                            mimeType = "video/mp4",
                            width = w,
                            height = h,
                            fps = fps,
                            bitrate = br
                        )
                    )
                }
            }

            // `ua.tar` chunklist fallback if `ua.hls` was absent
            if (hlsVariants.isEmpty() && autoHlsMasterUrl == null) {
                val uaTar = uaObj["tar"] as? JsonObject
                if (uaTar != null) {
                    for ((key, valEl) in uaTar) {
                        val obj = valEl as? JsonObject ?: continue
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val h = key.filter { it.isDigit() }.toIntOrNull()
                        hlsVariants.add(
                            CreatorQualityVariant(
                                label = if (h != null) "${h}p" else key,
                                url = url,
                                mimeType = "application/x-mpegURL",
                                height = h
                            )
                        )
                    }
                }
            }
        }

        // Legacy `u` object fallback (`u.hls.url`, `u.mp4.url`)
        val legacyU = embedJson["u"] as? JsonObject
        if (legacyU != null) {
            val legHls = (legacyU["hls"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            if (!legHls.isNullOrBlank() && autoHlsMasterUrl == null) {
                autoHlsMasterUrl = legHls
            }
            val legMp4 = (legacyU["mp4"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            if (!legMp4.isNullOrBlank() && mp4Variants.isEmpty()) {
                mp4Variants.add(
                    CreatorQualityVariant(
                        label = "Default MP4",
                        url = legMp4,
                        mimeType = "video/mp4"
                    )
                )
            }
        }

        val sortedHls = hlsVariants.sortedByDescending { it.height ?: 0 }
        val sortedMp4 = mp4Variants.sortedByDescending { it.height ?: 0 }
        val allVariants = sortedHls + sortedMp4

        // Pick default <= 1080p stream for Android TV
        val bestMp4Under1080 = sortedMp4.firstOrNull { (it.height ?: 720) <= 1080 } ?: sortedMp4.lastOrNull()
        val bestHlsUnder1080 = sortedHls.firstOrNull { (it.height ?: 720) <= 1080 } ?: sortedHls.lastOrNull()

        val primaryUrl: String
        val primaryMime: String
        when {
            isLive && !autoHlsMasterUrl.isNullOrBlank() -> {
                primaryUrl = autoHlsMasterUrl!!
                primaryMime = "application/x-mpegURL"
            }
            bestHlsUnder1080 != null -> {
                primaryUrl = autoHlsMasterUrl ?: bestHlsUnder1080.url
                primaryMime = "application/x-mpegURL"
            }
            !autoHlsMasterUrl.isNullOrBlank() -> {
                primaryUrl = autoHlsMasterUrl!!
                primaryMime = "application/x-mpegURL"
            }
            bestMp4Under1080 != null -> {
                primaryUrl = bestMp4Under1080.url
                primaryMime = "video/mp4"
            }
            else -> throw IOException("No playable HLS or MP4 URLs found for Rumble video $embedId")
        }

        // Parse closed captions (`cc`)
        val subtitles = ArrayList<CreatorSubtitleTrack>()
        val ccObj = embedJson["cc"] as? JsonObject
        if (ccObj != null) {
            for ((langCode, ccEl) in ccObj) {
                val trackObj = ccEl as? JsonObject ?: continue
                val subUrl = trackObj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val label = trackObj["language"]?.jsonPrimitive?.contentOrNull ?: langCode
                subtitles.add(
                    CreatorSubtitleTrack(
                        languageCode = langCode,
                        label = label,
                        url = subUrl,
                        mimeType = "text/vtt"
                    )
                )
            }
        }

        ResolvedCreatorStream(
            id = cleanInput,
            platform = CreatorPlatform.RUMBLE,
            title = title,
            channelName = authorName,
            channelId = channelPath,
            thumbnailUrl = thumb,
            isLive = isLive,
            primaryUrl = primaryUrl,
            primaryMimeType = primaryMime,
            hlsManifestUrl = autoHlsMasterUrl ?: bestHlsUnder1080?.url,
            mp4FallbackUrl = bestMp4Under1080?.url,
            qualityVariants = allVariants,
            subtitleTracks = subtitles,
            headers = mapOf(
                "User-Agent" to WEB_UA,
                "Origin" to RUMBLE_BASE,
                "Referer" to "$RUMBLE_BASE/"
            )
        )
    }

    // =========================================================================
    // Internal Helpers: JSON + HTML Card Extraction
    // =========================================================================

    private fun parseRumblePageVideos(
        html: String,
        defaultChannelName: String? = null,
        defaultChannelId: String? = null,
        defaultChannelAvatar: String? = null
    ): List<CreatorVideoCard> {
        val out = LinkedHashMap<String, CreatorVideoCard>()

        // 1. Parse embedded `<rum-videos-grid><script type="application/json">` blocks (used on Category pages!)
        for (match in RUM_VIDEOS_GRID_REGEX.findAll(html)) {
            val jsonText = match.groupValues[1]
            val gridObj = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: continue
            val items = gridObj["items"] as? JsonArray ?: continue
            for (el in items) {
                val itemObj = el as? JsonObject ?: continue
                val relUrl = itemObj["relative_url"]?.jsonPrimitive?.contentOrNull
                    ?: itemObj["url"]?.jsonPrimitive?.contentOrNull?.removePrefix(RUMBLE_BASE)
                    ?: continue
                val cleanPath = relUrl.substringBefore("?")
                val title = itemObj["title"]?.jsonPrimitive?.contentOrNull ?: continue
                val thumb = itemObj["thumb"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val durSec = itemObj["duration"]?.jsonPrimitive?.longOrNull
                val isLive = itemObj["live"]?.jsonPrimitive?.booleanOrNull == true
                val watching = itemObj["watching_now"]?.jsonPrimitive?.longOrNull
                val views = itemObj["views"]?.jsonPrimitive?.longOrNull
                val uploadDate = itemObj["upload_date"]?.jsonPrimitive?.contentOrNull?.substringBefore("T")

                val byObj = itemObj["by"] as? JsonObject
                val chName = byObj?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: byObj?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: defaultChannelName
                    ?: "Rumble Creator"
                val chPath = byObj?.get("relative_url")?.jsonPrimitive?.contentOrNull
                    ?: defaultChannelId
                    ?: ""
                val chThumb = byObj?.get("thumb")?.jsonPrimitive?.contentOrNull ?: defaultChannelAvatar

                val countVal = if (isLive) watching else views
                val countText = when {
                    isLive && watching != null -> "$watching watching"
                    views != null && views > 0 -> "${formatCount(views)} views"
                    else -> null
                }

                out.putIfAbsent(
                    cleanPath,
                    CreatorVideoCard(
                        id = cleanPath,
                        platform = CreatorPlatform.RUMBLE,
                        title = title,
                        channelName = chName,
                        channelId = chPath,
                        channelAvatarUrl = chThumb,
                        thumbnailUrl = thumb,
                        durationSec = if (isLive) null else durSec,
                        durationText = if (isLive) "LIVE" else durSec?.let { formatDuration(it) },
                        viewerOrViewCount = countVal,
                        viewCountText = countText,
                        publishedText = uploadDate,
                        isLive = isLive,
                        streamUrlHint = cleanPath
                    )
                )
            }
        }

        // 2. Parse `.videostream` HTML cards (used on /editor-picks, /browse/live, /c/..., /user/...)
        val bodyHtml = html.substringAfter("</head>", html)
        val chunks = bodyHtml.split(VIDEOSTREAM_SPLIT_REGEX).drop(1)
        for (rawChunk in chunks) {
            val chunk = if (rawChunk.length > 4000) rawChunk.substring(0, 4000) else rawChunk
            val path = VIDEO_HREF_REGEX.find(chunk)?.groupValues?.getOrNull(1) ?: continue
            val rawTitle = TITLE_ATTR_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                ?: TITLE_TEXT_REGEX.find(chunk)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")
                ?: continue
            val title = decodeHtmlEntities(rawTitle).trim().takeIf { it.isNotEmpty() } ?: continue

            val thumb = THUMB_IMG_REGEX.find(chunk)?.groupValues?.getOrNull(1).orEmpty()
            val isLive = LIVE_BADGE_REGEX.containsMatchIn(chunk)
            val durTxt = if (isLive) {
                "LIVE"
            } else {
                DURATION_BADGE_REGEX.find(chunk)?.groupValues?.getOrNull(1)?.trim()
            }
            val durSec = parseDurationText(durTxt)

            val chPath = CHANNEL_LINK_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                ?: defaultChannelId
                ?: ""
            val chImgMatch = CHANNEL_IMG_REGEX.find(chunk)
            val chAvatar = chImgMatch?.groupValues?.getOrNull(1) ?: defaultChannelAvatar
            val chNameRaw = chImgMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: CHANNEL_NAME_SPAN_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                ?: defaultChannelName
                ?: chPath.substringAfterLast("/")
            val chName = decodeHtmlEntities(chNameRaw).trim()

            val viewsBadge = VIEWS_BADGE_REGEX.find(chunk)?.groupValues?.getOrNull(1)?.trim()
            val viewsLabel = viewsBadge?.let {
                if (isLive) "$it watching" else "$it views"
            }

            out.putIfAbsent(
                path,
                CreatorVideoCard(
                    id = path,
                    platform = CreatorPlatform.RUMBLE,
                    title = title,
                    channelName = chName,
                    channelId = chPath,
                    channelAvatarUrl = chAvatar,
                    thumbnailUrl = thumb,
                    durationSec = durSec,
                    durationText = durTxt,
                    viewCountText = viewsLabel,
                    isLive = isLive,
                    streamUrlHint = path
                )
            )
        }

        return out.values.toList()
    }

    private suspend fun searchViaDdgAndOembed(query: String): List<CreatorVideoCard> = coroutineScope {
        val ddgUrl = "https://html.duckduckgo.com/html/?q=" +
            URLEncoder.encode("site:rumble.com $query", "UTF-8")
        val req = Request.Builder()
            .url(ddgUrl)
            .header("User-Agent", WEB_UA)
            .get()
            .build()

        val ddgHtml = runCatching {
            httpClient.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull() ?: return@coroutineScope emptyList()

        val decodedHtml = runCatching { URLDecoder.decode(ddgHtml, "UTF-8") }.getOrDefault(ddgHtml)
        val paths = DDG_RUMBLE_LINK_REGEX.findAll(decodedHtml)
            .map { it.groupValues[1] }
            .distinct()
            .take(16)
            .toList()

        if (paths.isEmpty()) return@coroutineScope emptyList()

        val cards = ArrayList<CreatorVideoCard>()
        for (chunk in paths.chunked(6)) {
            val resolved = chunk.map { path ->
                async {
                    val oembed = fetchOembedJson("$RUMBLE_BASE$path") ?: return@async null
                    val title = oembed["title"]?.jsonPrimitive?.contentOrNull ?: return@async null
                    val author = oembed["author_name"]?.jsonPrimitive?.contentOrNull ?: "Rumble"
                    val chPath = oembed["author_url"]?.jsonPrimitive?.contentOrNull
                        ?.removePrefix(RUMBLE_BASE).orEmpty()
                    val thumb = oembed["thumbnail_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val durSec = oembed["duration"]?.jsonPrimitive?.longOrNull
                    val iframe = oembed["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val embId = EMBED_ID_IFRAME_REGEX.find(iframe)?.groupValues?.getOrNull(1)

                    CreatorVideoCard(
                        id = path,
                        platform = CreatorPlatform.RUMBLE,
                        title = title,
                        channelName = author,
                        channelId = chPath,
                        thumbnailUrl = thumb,
                        durationSec = durSec,
                        durationText = durSec?.let { formatDuration(it) },
                        isLive = false,
                        streamUrlHint = embId?.let { "embed:$it" } ?: path
                    )
                }
            }.awaitAll()
            cards.addAll(resolved.filterNotNull())
        }
        cards
    }

    private fun fetchOembedJson(fullVideoUrl: String): JsonObject? {
        val url = WN0_OEMBED_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("url", fullVideoUrl)
            ?.build() ?: return null
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            }
        }.getOrNull()
    }

    private fun extractEmbedIdFromVideoPage(fullVideoUrl: String): String? {
        val html = fetchHtml(fullVideoUrl) ?: return null
        EMBED_ID_IFRAME_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        EMBED_ID_JS_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun fetchEmbedStreamJson(embedId: String): JsonObject? {
        val url = WN0_EMBED_JS_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("request", "video")
            ?.addQueryParameter("ver", "2")
            ?.addQueryParameter("v", embedId)
            ?.build()

        if (url != null) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", WEB_UA)
                .header("Referer", "$RUMBLE_BASE/embed/$embedId/")
                .get()
                .build()

            val primary = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val text = resp.body?.string().orEmpty()
                    if (text.startsWith("{")) json.parseToJsonElement(text).jsonObject else null
                }
            }.getOrNull()
            if (primary != null && (primary.containsKey("ua") || primary.containsKey("u"))) {
                return primary
            }
        }

        // Fallback: parse inline Rumble("play", {...}) JSON from `/embed/{embedId}/` HTML
        val embedHtml = fetchHtml("$RUMBLE_BASE/embed/$embedId/") ?: return null
        val cfgJson = EMBED_CONFIG_HTML_REGEX.find(embedHtml)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { json.parseToJsonElement(cfgJson).jsonObject }.getOrNull()
    }

    private fun fetchHtml(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    private fun normalizeChannelPath(raw: String): String {
        val clean = raw.trim()
            .removePrefix("https://rumble.com")
            .removePrefix("http://rumble.com")
            .substringBefore("?")
            .trimEnd('/')
        return when {
            clean.startsWith("/c/") || clean.startsWith("/user/") -> clean
            clean.startsWith("c/") || clean.startsWith("user/") -> "/$clean"
            else -> "/c/${clean.removePrefix("@")}"
        }
    }

    private fun parseDurationText(txt: String?): Long? {
        val clean = txt?.trim()?.takeIf { it.isNotEmpty() && it[0].isDigit() } ?: return null
        val parts = clean.split(':').map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> null
        }
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000L -> "%.1fM".format(java.util.Locale.US, n / 1_000_000.0)
        n >= 1_000L -> "%.1fK".format(java.util.Locale.US, n / 1_000.0)
        else -> n.toString()
    }

    private fun decodeHtmlEntities(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&#34;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}
