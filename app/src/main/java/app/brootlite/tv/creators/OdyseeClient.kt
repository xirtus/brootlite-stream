package app.brootlite.tv.creators

import app.brootlite.tv.BrootLiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure-Kotlin Odysee (LBRY) client for BrootLite:
 * - Trending / Featured & Category feeds via `https://api.na-backend.odysee.com/api/v1/proxy` (`claim_search`).
 * - Fast autocomplete & full-text search via `https://lighthouse.odysee.tv/search` + batch `resolve`.
 * - Live stream discovery via `https://api.odysee.live/livestream/all` & `/is_live`.
 * - Channel header + uploads & Followed Channels chronological feed (`channel_ids`).
 * - 100% ad-free direct HLS (`player.odycdn.com/v6/streams/{claimId}/{sdHash}/master.m3u8` &
 *   `cloud.odysee.live/content/{channelClaimId}/master.m3u8`) + direct MP4 fallback (`get`).
 */
object OdyseeClient {

    private const val PROXY_API_URL = "https://api.na-backend.odysee.com/api/v1/proxy"
    private const val LIGHTHOUSE_SEARCH_URL = "https://lighthouse.odysee.tv/search"
    private const val LIVESTREAM_ALL_URL = "https://api.odysee.live/livestream/all"
    private const val LIVESTREAM_IS_LIVE_URL = "https://api.odysee.live/livestream/is_live"
    private const val ODYCDN_STREAM_BASE = "https://player.odycdn.com/v6/streams"
    private const val ODYSEE_LIVE_CDN_BASE = "https://cloud.odysee.live/content"

    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE

    private val httpClient: OkHttpClient
        get() = BrootLiteApp.sharedHttpClient

    data class OdyseeCategoryDef(
        val id: String,
        val name: String,
        val tags: List<String>
    )

    val DEFAULT_CATEGORIES = listOf(
        OdyseeCategoryDef("gaming", "Gaming", listOf("gaming", "video games")),
        OdyseeCategoryDef("tech", "Science & Tech", listOf("science & technology", "technology", "tech", "linux")),
        OdyseeCategoryDef("news", "News & Politics", listOf("news", "current events", "politics")),
        OdyseeCategoryDef("finance", "Finance 2.0", listOf("finance 2.0", "economics", "crypto", "investing")),
        OdyseeCategoryDef("education", "Learning", listOf("learning", "education", "history", "science")),
        OdyseeCategoryDef("entertainment", "Entertainment", listOf("entertainment", "pop culture", "comedy")),
        OdyseeCategoryDef("music", "Music", listOf("music", "audio"))
    )

    fun getCategories(): List<CreatorCategory> = DEFAULT_CATEGORIES.map { def ->
        CreatorCategory(
            id = def.id,
            platform = CreatorPlatform.ODYSEE,
            name = def.name,
            slug = def.id
        )
    }

    // =========================================================================
    // Browsable Feeds (Trending, Category, Live, Channel, Followed, Search)
    // =========================================================================

    suspend fun getTrendingFeed(
        page: Int = 1,
        pageSize: Int = 30
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)

        val result = callLbryRpc("claim_search") {
            put("page", safePage)
            put("page_size", safeSize)
            put("no_totals", true)
            put("has_source", true)
            put("fee_amount", "<=0")
            putJsonArray("value_types") { add(JsonPrimitive("stream")) }
            putJsonArray("stream_types") { add(JsonPrimitive("video")) }
            putJsonArray("not_tags") {
                add(JsonPrimitive("porn"))
                add(JsonPrimitive("nsfw"))
                add(JsonPrimitive("xxx"))
                add(JsonPrimitive("mature"))
            }
            putJsonArray("order_by") {
                add(JsonPrimitive("trending_group"))
                add(JsonPrimitive("trending_mixed"))
            }
        } ?: return@withContext CreatorFeedPage(emptyList())

        val items = parseClaimItems(result["items"] as? JsonArray)
        CreatorFeedPage(
            items = items,
            categories = if (safePage == 1) getCategories() else emptyList(),
            nextCursor = if (items.size >= safeSize) (safePage + 1).toString() else null
        )
    }

    suspend fun getCategoryFeed(
        categorySlugOrTag: String,
        page: Int = 1,
        pageSize: Int = 30
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        val matchedDef = DEFAULT_CATEGORIES.firstOrNull {
            it.id.equals(categorySlugOrTag, ignoreCase = true) ||
                it.name.equals(categorySlugOrTag, ignoreCase = true)
        }
        val tags = matchedDef?.tags ?: listOf(categorySlugOrTag.lowercase().trim())

        val result = callLbryRpc("claim_search") {
            put("page", safePage)
            put("page_size", safeSize)
            put("no_totals", true)
            put("has_source", true)
            put("fee_amount", "<=0")
            putJsonArray("value_types") { add(JsonPrimitive("stream")) }
            putJsonArray("stream_types") { add(JsonPrimitive("video")) }
            putJsonArray("any_tags") {
                tags.forEach { add(JsonPrimitive(it)) }
            }
            putJsonArray("not_tags") {
                add(JsonPrimitive("porn"))
                add(JsonPrimitive("nsfw"))
                add(JsonPrimitive("mature"))
            }
            putJsonArray("order_by") {
                add(JsonPrimitive("trending_group"))
                add(JsonPrimitive("trending_mixed"))
            }
        } ?: return@withContext CreatorFeedPage(emptyList())

        val items = parseClaimItems(result["items"] as? JsonArray)
        CreatorFeedPage(
            items = items,
            nextCursor = if (items.size >= safeSize) (safePage + 1).toString() else null
        )
    }

    /**
     * Discovers currently live Odysee streams sorted by concurrent viewer count.
     */
    suspend fun getLiveStreams(limit: Int = 36): CreatorFeedPage = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LIVESTREAM_ALL_URL)
            .header("User-Agent", WEB_UA)
            .header("Origin", "https://odysee.com")
            .header("Referer", "https://odysee.com/")
            .get()
            .build()

        val rawData = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                root["data"] as? JsonArray
            }
        }.getOrNull() ?: return@withContext CreatorFeedPage(emptyList())

        val activeEntries = rawData
            .mapNotNull { it as? JsonObject }
            .filter { entry ->
                entry["Live"]?.jsonPrimitive?.booleanOrNull == true &&
                    ((entry["ActiveClaim"] as? JsonObject)?.get("Protected")?.jsonPrimitive?.booleanOrNull != true)
            }
            .sortedByDescending { it["ViewerCount"]?.jsonPrimitive?.longOrNull ?: 0L }
            .take(limit.coerceIn(1, 60))

        if (activeEntries.isEmpty()) return@withContext CreatorFeedPage(emptyList())

        // Batch-resolve active claim canonical URLs so we get rich titles, thumbnails, and channel names!
        val canonicalUrls = activeEntries.mapNotNull { entry ->
            (entry["ActiveClaim"] as? JsonObject)?.get("CanonicalURL")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }
        val resolvedMap = resolveLbryUrls(canonicalUrls)

        val cards = activeEntries.mapNotNull { entry ->
            val channelClaimId = entry["ChannelClaimID"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val videoUrl = entry["VideoURL"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: "$ODYSEE_LIVE_CDN_BASE/$channelClaimId/master.m3u8"
            val viewers = entry["ViewerCount"]?.jsonPrimitive?.longOrNull ?: 0L
            val activeClaim = entry["ActiveClaim"] as? JsonObject
            val claimId = activeClaim?.get("ClaimID")?.jsonPrimitive?.contentOrNull ?: channelClaimId
            val canonical = activeClaim?.get("CanonicalURL")?.jsonPrimitive?.contentOrNull
            val resolvedClaim = canonical?.let { resolvedMap[it] }

            val valueObj = resolvedClaim?.get("value") as? JsonObject
            val signingChannel = resolvedClaim?.get("signing_channel") as? JsonObject
            val chValue = signingChannel?.get("value") as? JsonObject

            val title = valueObj?.get("title")?.jsonPrimitive?.contentOrNull
                ?: resolvedClaim?.get("name")?.jsonPrimitive?.contentOrNull
                ?: canonical?.substringAfterLast("/")?.substringBefore("#")
                ?: "Live on Odysee"

            val channelName = chValue?.get("title")?.jsonPrimitive?.contentOrNull
                ?: signingChannel?.get("name")?.jsonPrimitive?.contentOrNull
                ?: canonical?.removePrefix("lbry://")?.substringBefore("/")?.substringBefore("#")
                ?: "Odysee Creator"

            val thumb = entry["ThumbnailURL"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (valueObj?.get("thumbnail") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                ?: (chValue?.get("thumbnail") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                ?: ""

            val avatar = (chValue?.get("thumbnail") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull

            CreatorVideoCard(
                id = claimId,
                platform = CreatorPlatform.ODYSEE,
                title = title,
                channelName = channelName,
                channelId = channelClaimId,
                channelAvatarUrl = avatar,
                thumbnailUrl = thumb,
                durationText = "LIVE",
                viewerOrViewCount = viewers,
                viewCountText = "$viewers watching",
                isLive = true,
                streamUrlHint = "live|$videoUrl"
            )
        }

        CreatorFeedPage(items = cards)
    }

    /**
     * Fetches an Odysee channel's metadata, active livestream (if live), and recent uploads.
     * Accepts either a 40-char `claim_id` OR an `@handle` / `lbry://@handle` URL!
     */
    suspend fun getChannelFeed(
        channelIdOrHandle: String,
        page: Int = 1,
        pageSize: Int = 30
    ): Pair<CreatorChannel?, CreatorFeedPage> = withContext(Dispatchers.IO) {
        val raw = channelIdOrHandle.trim()
        if (raw.isEmpty()) return@withContext null to CreatorFeedPage(emptyList())

        var channelObj: JsonObject? = null
        var channelClaimId: String = raw

        if (raw.startsWith("@") || raw.startsWith("lbry://") || raw.startsWith("https://")) {
            val lbryUri = when {
                raw.startsWith("lbry://") -> raw
                raw.startsWith("https://odysee.com/") -> "lbry://" + raw.removePrefix("https://odysee.com/").replace(':', '#')
                else -> "lbry://$raw"
            }
            channelObj = resolveLbryUrls(listOf(lbryUri)).values.firstOrNull()
            channelClaimId = channelObj?.get("claim_id")?.jsonPrimitive?.contentOrNull ?: raw
        } else if (page == 1) {
            val searchCh = callLbryRpc("claim_search") {
                putJsonArray("claim_ids") { add(JsonPrimitive(raw)) }
                put("page_size", 1)
            }
            channelObj = (searchCh?.get("items") as? JsonArray)?.firstOrNull() as? JsonObject
        }

        val chVal = channelObj?.get("value") as? JsonObject
        val handle = channelObj?.get("name")?.jsonPrimitive?.contentOrNull
        val dispName = chVal?.get("title")?.jsonPrimitive?.contentOrNull ?: handle ?: channelClaimId
        val avatar = (chVal?.get("thumbnail") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        val banner = (chVal?.get("cover") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        val desc = chVal?.get("description")?.jsonPrimitive?.contentOrNull

        val liveCard = if (page == 1) checkChannelLiveCard(channelClaimId, dispName, avatar) else null

        val channelModel = CreatorChannel(
            id = channelClaimId,
            platform = CreatorPlatform.ODYSEE,
            displayName = dispName,
            handle = handle,
            avatarUrl = avatar,
            bannerUrl = banner,
            description = desc,
            isLive = liveCard != null,
            liveVideo = liveCard
        )

        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        val videosRes = callLbryRpc("claim_search") {
            put("page", safePage)
            put("page_size", safeSize)
            put("no_totals", true)
            put("has_source", true)
            put("fee_amount", "<=0")
            putJsonArray("channel_ids") { add(JsonPrimitive(channelClaimId)) }
            putJsonArray("value_types") { add(JsonPrimitive("stream")) }
            putJsonArray("stream_types") { add(JsonPrimitive("video")) }
            putJsonArray("order_by") { add(JsonPrimitive("release_time")) }
        }

        val uploads = parseClaimItems(videosRes?.get("items") as? JsonArray)
        val combined = if (liveCard != null) listOf(liveCard) + uploads else uploads

        channelModel to CreatorFeedPage(
            items = combined,
            channels = listOf(channelModel),
            nextCursor = if (uploads.size >= safeSize) (safePage + 1).toString() else null
        )
    }

    /**
     * Chronological feed of videos from locally followed Odysee channel `claim_id`s.
     */
    suspend fun getFollowedFeed(
        followedChannelClaimIds: List<String>,
        page: Int = 1,
        pageSize: Int = 30
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val cleanIds = followedChannelClaimIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanIds.isEmpty()) return@withContext CreatorFeedPage(emptyList())

        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)

        val result = callLbryRpc("claim_search") {
            put("page", safePage)
            put("page_size", safeSize)
            put("no_totals", true)
            put("has_source", true)
            put("fee_amount", "<=0")
            putJsonArray("channel_ids") {
                cleanIds.take(50).forEach { add(JsonPrimitive(it)) }
            }
            putJsonArray("value_types") { add(JsonPrimitive("stream")) }
            putJsonArray("stream_types") { add(JsonPrimitive("video")) }
            putJsonArray("order_by") { add(JsonPrimitive("release_time")) }
        } ?: return@withContext CreatorFeedPage(emptyList())

        val items = parseClaimItems(result["items"] as? JsonArray)
        CreatorFeedPage(
            items = items,
            nextCursor = if (items.size >= safeSize) (safePage + 1).toString() else null
        )
    }

    /**
     * Fast Odysee search via Lighthouse (`https://lighthouse.odysee.tv/search`) + batch JSON-RPC `resolve`.
     */
    suspend fun search(
        query: String,
        page: Int = 0,
        pageSize: Int = 25
    ): CreatorFeedPage = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext CreatorFeedPage(emptyList())
        val safePage = page.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(1, 50)

        val searchUrl = LIGHTHOUSE_SEARCH_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("s", q)
            ?.addQueryParameter("size", safeSize.toString())
            ?.addQueryParameter("from", (safePage * safeSize).toString())
            ?.addQueryParameter("nsfw", "false")
            ?.addQueryParameter("free_only", "true")
            ?.build() ?: return@withContext CreatorFeedPage(emptyList())

        val req = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", WEB_UA)
            .header("Origin", "https://odysee.com")
            .get()
            .build()

        val uris = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val arr = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonArray
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val cid = obj["claimId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    "lbry://$name#$cid"
                }
            }
        }.getOrDefault(emptyList())

        if (uris.isEmpty()) return@withContext CreatorFeedPage(emptyList())

        val resolvedMap = resolveLbryUrls(uris)
        val orderedClaims = uris.mapNotNull { resolvedMap[it] }

        val videos = ArrayList<CreatorVideoCard>()
        val channels = ArrayList<CreatorChannel>()

        for (claim in orderedClaims) {
            when (claim["value_type"]?.jsonPrimitive?.contentOrNull) {
                "channel" -> parseChannelClaim(claim)?.let { channels.add(it) }
                "stream" -> parseSingleStreamClaim(claim)?.let { videos.add(it) }
            }
        }

        CreatorFeedPage(
            items = videos,
            channels = channels,
            nextCursor = if (uris.size >= safeSize) (safePage + 1).toString() else null
        )
    }

    // =========================================================================
    // Ad-Free Direct VOD & Live Stream Resolution
    // =========================================================================

    /**
     * Resolves an ad-free Odysee VOD or Live stream.
     *
     * - Live streams resolve to `https://cloud.odysee.live/content/{channelClaimId}/master.m3u8`
     *   (plus `#EXT-X-STREAM-INF` quality variants).
     * - VODs resolve to Odysee's direct v6 CDN HLS (`https://player.odycdn.com/v6/streams/{claimId}/{sdHash}/master.m3u8`)
     *   plus a direct progressive MP4 fallback URL via JSON-RPC `get` (`https://player.odycdn.com/v6/streams/{claimId}/{sdHash6}.mp4`)!
     */
    suspend fun resolveStream(
        claimIdOrUri: String,
        streamUrlHint: String? = null
    ): ResolvedCreatorStream = withContext(Dispatchers.IO) {
        // Fast path if caller passed a live card with streamUrlHint = "live|https://...m3u8"
        if (streamUrlHint != null && streamUrlHint.startsWith("live|")) {
            val liveHls = streamUrlHint.removePrefix("live|")
            val variants = fetchHlsVariants(liveHls)
            return@withContext ResolvedCreatorStream(
                id = claimIdOrUri,
                platform = CreatorPlatform.ODYSEE,
                title = "Odysee Live",
                channelName = "Odysee",
                isLive = true,
                primaryUrl = liveHls,
                primaryMimeType = "application/x-mpegURL",
                hlsManifestUrl = liveHls,
                qualityVariants = variants,
                headers = defaultHeaders()
            )
        }

        val cleanInput = claimIdOrUri.trim()
        val claimObj = if (cleanInput.startsWith("lbry://")) {
            resolveLbryUrls(listOf(cleanInput)).values.firstOrNull()
        } else {
            val searchRes = callLbryRpc("claim_search") {
                putJsonArray("claim_ids") { add(JsonPrimitive(cleanInput)) }
                put("page_size", 1)
            }
            (searchRes?.get("items") as? JsonArray)?.firstOrNull() as? JsonObject
        } ?: throw IOException("Odysee claim not found: $cleanInput")

        val claimId = claimObj["claim_id"]?.jsonPrimitive?.contentOrNull ?: cleanInput
        val canonicalUrl = claimObj["canonical_url"]?.jsonPrimitive?.contentOrNull
        val valueObj = claimObj["value"] as? JsonObject
        val title = valueObj?.get("title")?.jsonPrimitive?.contentOrNull ?: claimId
        val thumb = (valueObj?.get("thumbnail") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        val signingCh = claimObj["signing_channel"] as? JsonObject
        val channelId = signingCh?.get("claim_id")?.jsonPrimitive?.contentOrNull
        val channelName = ((signingCh?.get("value") as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull)
            ?: signingCh?.get("name")?.jsonPrimitive?.contentOrNull
            ?: "Odysee"

        val sdHash = (valueObj?.get("source") as? JsonObject)?.get("sd_hash")?.jsonPrimitive?.contentOrNull
            ?: streamUrlHint?.takeIf { it.length >= 48 && !it.contains("|") }

        // If claim has no source sd_hash, check if it's an active livestream!
        if (sdHash.isNullOrBlank()) {
            val chIdForLive = channelId ?: claimId
            val liveCard = checkChannelLiveCard(chIdForLive, channelName, null)
            if (liveCard != null && liveCard.streamUrlHint != null) {
                val liveUrl = liveCard.streamUrlHint.removePrefix("live|")
                val variants = fetchHlsVariants(liveUrl)
                return@withContext ResolvedCreatorStream(
                    id = claimId,
                    platform = CreatorPlatform.ODYSEE,
                    title = title,
                    channelName = channelName,
                    channelId = channelId,
                    thumbnailUrl = thumb,
                    isLive = true,
                    primaryUrl = liveUrl,
                    primaryMimeType = "application/x-mpegURL",
                    hlsManifestUrl = liveUrl,
                    qualityVariants = variants,
                    headers = defaultHeaders()
                )
            }
            throw IOException("Odysee stream has no playable media source ($claimId)")
        }

        val hlsMasterUrl = "$ODYCDN_STREAM_BASE/$claimId/$sdHash/master.m3u8"
        val mp4FallbackUrl = fetchDirectMp4Url(canonicalUrl, claimId, sdHash)

        // Probe whether Odysee CDN has transcoded HLS ready; otherwise use direct MP4 as primary!
        val hlsVariants = fetchHlsVariants(hlsMasterUrl)
        val allVariants = ArrayList<CreatorQualityVariant>(hlsVariants.size + 1)
        allVariants.addAll(hlsVariants)
        if (!mp4FallbackUrl.isNullOrBlank()) {
            val vMeta = valueObj?.get("video") as? JsonObject
            val w = vMeta?.get("width")?.jsonPrimitive?.intOrNull
            val h = vMeta?.get("height")?.jsonPrimitive?.intOrNull
            allVariants.add(
                CreatorQualityVariant(
                    label = if (h != null) "${h}p (Original MP4)" else "Original MP4",
                    url = mp4FallbackUrl,
                    mimeType = "video/mp4",
                    width = w,
                    height = h
                )
            )
        }

        val useHlsPrimary = hlsVariants.isNotEmpty()
        val primaryUrl = if (useHlsPrimary) hlsMasterUrl else (mp4FallbackUrl ?: hlsMasterUrl)
        val primaryMime = if (useHlsPrimary) "application/x-mpegURL" else "video/mp4"

        ResolvedCreatorStream(
            id = claimId,
            platform = CreatorPlatform.ODYSEE,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumb,
            isLive = false,
            primaryUrl = primaryUrl,
            primaryMimeType = primaryMime,
            hlsManifestUrl = if (useHlsPrimary) hlsMasterUrl else null,
            mp4FallbackUrl = mp4FallbackUrl,
            qualityVariants = allVariants,
            headers = defaultHeaders()
        )
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    private fun fetchDirectMp4Url(canonicalUrl: String?, claimId: String, sdHash: String): String? {
        if (!canonicalUrl.isNullOrBlank()) {
            val getRes = callLbryRpc("get") {
                put("uri", canonicalUrl)
                put("save_file", false)
            }
            val streamingUrl = getRes?.get("streaming_url")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("https://") }
            if (streamingUrl != null) return streamingUrl
        }
        return if (sdHash.length >= 6) "$ODYCDN_STREAM_BASE/$claimId/${sdHash.take(6)}.mp4" else null
    }

    private fun checkChannelLiveCard(
        channelClaimId: String,
        channelName: String,
        avatarUrl: String?
    ): CreatorVideoCard? {
        val url = LIVESTREAM_IS_LIVE_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("channel_claim_id", channelClaimId)
            ?.build() ?: return null

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .header("Origin", "https://odysee.com")
            .header("Referer", "https://odysee.com/")
            .get()
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val data = root["data"] as? JsonObject ?: return@use null
                if (data["Live"]?.jsonPrimitive?.booleanOrNull != true) return@use null
                val videoUrl = data["VideoURL"]?.jsonPrimitive?.contentOrNull
                    ?: "$ODYSEE_LIVE_CDN_BASE/$channelClaimId/master.m3u8"
                val viewers = data["ViewerCount"]?.jsonPrimitive?.longOrNull ?: 0L
                val thumb = data["ThumbnailURL"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val activeId = (data["ActiveClaim"] as? JsonObject)?.get("ClaimID")?.jsonPrimitive?.contentOrNull
                    ?: channelClaimId

                CreatorVideoCard(
                    id = activeId,
                    platform = CreatorPlatform.ODYSEE,
                    title = "$channelName (Live)",
                    channelName = channelName,
                    channelId = channelClaimId,
                    channelAvatarUrl = avatarUrl,
                    thumbnailUrl = thumb,
                    durationText = "LIVE",
                    viewerOrViewCount = viewers,
                    viewCountText = "$viewers watching",
                    isLive = true,
                    streamUrlHint = "live|$videoUrl"
                )
            }
        }.getOrNull()
    }

    private fun resolveLbryUrls(urls: List<String>): Map<String, JsonObject> {
        if (urls.isEmpty()) return emptyMap()
        val res = callLbryRpc("resolve") {
            putJsonArray("urls") {
                urls.distinct().forEach { add(JsonPrimitive(it)) }
            }
        } ?: return emptyMap()

        val out = LinkedHashMap<String, JsonObject>()
        for ((k, v) in res) {
            val obj = v as? JsonObject ?: continue
            if (obj.containsKey("error")) continue
            out[k] = obj
            obj["canonical_url"]?.jsonPrimitive?.contentOrNull?.let { out[it] = obj }
        }
        return out
    }

    private fun callLbryRpc(
        method: String,
        paramsBuilder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ): JsonObject? {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis())
            put("method", method)
            putJsonObject("params", paramsBuilder)
        }.toString()

        val req = Request.Builder()
            .url(PROXY_API_URL)
            .header("User-Agent", WEB_UA)
            .header("Origin", "https://odysee.com")
            .header("Referer", "https://odysee.com/")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                root["result"] as? JsonObject
            }
        }.getOrNull()
    }

    private fun parseClaimItems(items: JsonArray?): List<CreatorVideoCard> {
        if (items == null) return emptyList()
        return items.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            parseSingleStreamClaim(obj)
        }
    }

    private fun parseSingleStreamClaim(obj: JsonObject): CreatorVideoCard? {
        if (obj["value_type"]?.jsonPrimitive?.contentOrNull != "stream") return null
        val claimId = obj["claim_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val valueObj = obj["value"] as? JsonObject ?: return null
        if (valueObj["stream_type"]?.jsonPrimitive?.contentOrNull.let { it != null && it != "video" }) {
            return null
        }
        // Skip paid/rental content
        val feeObj = valueObj["fee"] as? JsonObject
        if (feeObj != null) {
            val amt = feeObj["amount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            if (amt > 0.0) return null
        }

        val title = valueObj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val thumb = (valueObj["thumbnail"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
        val durSec = (valueObj["video"] as? JsonObject)?.get("duration")?.jsonPrimitive?.longOrNull
        val sdHash = (valueObj["source"] as? JsonObject)?.get("sd_hash")?.jsonPrimitive?.contentOrNull

        val releaseEpoch = valueObj["release_time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: obj["timestamp"]?.jsonPrimitive?.longOrNull
        val publishedText = releaseEpoch?.let {
            runCatching { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).format(DATE_FMT) }.getOrNull()
        }

        val signingCh = obj["signing_channel"] as? JsonObject
        val chVal = signingCh?.get("value") as? JsonObject
        val chId = signingCh?.get("claim_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val chName = chVal?.get("title")?.jsonPrimitive?.contentOrNull
            ?: signingCh?.get("name")?.jsonPrimitive?.contentOrNull
            ?: "Odysee Creator"
        val chAvatar = (chVal?.get("thumbnail") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull

        return CreatorVideoCard(
            id = claimId,
            platform = CreatorPlatform.ODYSEE,
            title = title,
            channelName = chName,
            channelId = chId,
            channelAvatarUrl = chAvatar,
            thumbnailUrl = thumb,
            durationSec = durSec,
            durationText = durSec?.let { formatDuration(it) },
            publishedText = publishedText,
            isLive = false,
            streamUrlHint = sdHash
        )
    }

    private fun parseChannelClaim(obj: JsonObject): CreatorChannel? {
        val claimId = obj["claim_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val handle = obj["name"]?.jsonPrimitive?.contentOrNull
        val valueObj = obj["value"] as? JsonObject
        val title = valueObj?.get("title")?.jsonPrimitive?.contentOrNull ?: handle ?: return null
        val avatar = (valueObj?.get("thumbnail") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        val banner = (valueObj?.get("cover") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        val desc = valueObj?.get("description")?.jsonPrimitive?.contentOrNull

        return CreatorChannel(
            id = claimId,
            platform = CreatorPlatform.ODYSEE,
            displayName = title,
            handle = handle,
            avatarUrl = avatar,
            bannerUrl = banner,
            description = desc
        )
    }

    private fun fetchHlsVariants(masterUrl: String): List<CreatorQualityVariant> {
        val req = Request.Builder()
            .url(masterUrl)
            .header("User-Agent", WEB_UA)
            .header("Origin", "https://odysee.com")
            .header("Referer", "https://odysee.com/")
            .get()
            .build()

        val m3u8 = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()?.takeIf { it.contains("#EXTM3U") } ?: return emptyList()

        val lines = m3u8.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val out = ArrayList<CreatorQualityVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:") && i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                val rel = lines[i + 1]
                val resolved = if (rel.startsWith("http")) rel else runCatching {
                    URI(masterUrl).resolve(rel).toString()
                }.getOrDefault(rel)
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                val w = res?.groupValues?.getOrNull(1)?.toIntOrNull()
                val h = res?.groupValues?.getOrNull(2)?.toIntOrNull()
                val fps = Regex("""FRAME-RATE=([\d.]+)""").find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toInt()
                val label = when {
                    h != null && fps != null && fps > 30 -> "${h}p$fps"
                    h != null -> "${h}p"
                    else -> "Auto"
                }
                out.add(
                    CreatorQualityVariant(
                        label = label,
                        url = resolved,
                        mimeType = "application/x-mpegURL",
                        width = w,
                        height = h,
                        fps = fps,
                        bitrate = bw
                    )
                )
                i += 2
                continue
            }
            i++
        }
        return out.sortedByDescending { it.height ?: 0 }
    }

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "User-Agent" to WEB_UA,
        "Origin" to "https://odysee.com",
        "Referer" to "https://odysee.com/"
    )

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
