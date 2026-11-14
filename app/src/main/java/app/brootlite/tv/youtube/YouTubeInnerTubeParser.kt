package app.brootlite.tv.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure-Kotlin `kotlinx.serialization.json` parser for YouTube InnerTube responses across
 * `TVHTML5` (`tileRenderer`, `tvSurfaceContentRenderer`), classic `WEB` (`videoRenderer`, `richGridRenderer`),
 * and 2025/2026 `lockupViewModel` / `pageHeaderViewModel` schemas.
 *
 * Strips all ad slots (`adSlotRenderer`, `promotedSparklesWebRenderer`, `inFeedAdLayoutRenderer`, etc.).
 */
internal object YouTubeInnerTubeParser {

    private val AD_RENDERER_KEYS = setOf(
        "adSlotRenderer",
        "promotedSparklesWebRenderer",
        "promotedSparklesTextSearchRenderer",
        "promotedVideoRenderer",
        "compactPromotedVideoRenderer",
        "displayAdRenderer",
        "searchPyvRenderer",
        "inFeedAdLayoutRenderer",
        "bannerPromoRenderer",
        "statementBannerRenderer",
        "mastheadAdRenderer",
        "brandInteractionRenderer"
    )

    fun parseFeedPage(
        root: JsonObject,
        defaultChannelName: String? = null,
        defaultChannelId: String? = null
    ): YouTubeFeedPage {
        val visitorData = extractVisitorData(root)
        val flatVideos = LinkedHashMap<String, YouTubeVideoCard>()
        val flatChannels = LinkedHashMap<String, YouTubeChannelCard>()
        val flatPlaylists = LinkedHashMap<String, YouTubePlaylistCard>()
        val shelves = ArrayList<YouTubeShelf>()
        var pageContinuation: String? = null

        // 1. Continuation append/reload actions (used on page >= 2 across Home, Search, Channel, Playlist, History)
        val actions = (root["onResponseReceivedActions"] as? JsonArray)
            ?: (root["onResponseReceivedEndpoints"] as? JsonArray)
            ?: (root["onResponseReceivedCommands"] as? JsonArray)

        if (actions != null) {
            for (actionEl in actions) {
                val actionObj = actionEl as? JsonObject ?: continue
                val itemsArray = (actionObj["appendContinuationItemsAction"] as? JsonObject)?.get("continuationItems") as? JsonArray
                    ?: (actionObj["reloadContinuationItemsCommand"] as? JsonObject)?.get("continuationItems") as? JsonArray
                    ?: continue
                val contToken = parseItemsList(
                    items = itemsArray,
                    outVideos = flatVideos,
                    outChannels = flatChannels,
                    outPlaylists = flatPlaylists,
                    outShelves = shelves,
                    defaultChannelName = defaultChannelName,
                    defaultChannelId = defaultChannelId
                )
                if (contToken != null) pageContinuation = contToken
            }
        }

        // 2. Top-level continuationContents (TVHTML5 & classic browse/search continuations)
        val contContents = root["continuationContents"] as? JsonObject
        if (contContents != null) {
            val contToken = walkContainerNode(
                node = contContents,
                outVideos = flatVideos,
                outChannels = flatChannels,
                outPlaylists = flatPlaylists,
                outShelves = shelves,
                defaultChannelName = defaultChannelName,
                defaultChannelId = defaultChannelId
            )
            if (contToken != null) pageContinuation = contToken
        }

        // 3. Initial browse / search / next contents
        val contents = root["contents"] as? JsonObject
        if (contents != null) {
            val contToken = walkContainerNode(
                node = contents,
                outVideos = flatVideos,
                outChannels = flatChannels,
                outPlaylists = flatPlaylists,
                outShelves = shelves,
                defaultChannelName = defaultChannelName,
                defaultChannelId = defaultChannelId
            )
            if (contToken != null && pageContinuation == null) {
                pageContinuation = contToken
            }
        }

        if (pageContinuation == null) {
            pageContinuation = findTopContinuationToken(contents ?: contContents ?: root)
        }

        // Ensure shelf videos are also represented in flat items if flat items were empty or partial
        for (shelf in shelves) {
            for (v in shelf.videos) {
                flatVideos.putIfAbsent(v.videoId, v)
            }
            for (c in shelf.channels) {
                flatChannels.putIfAbsent(c.channelId, c)
            }
            for (p in shelf.playlists) {
                flatPlaylists.putIfAbsent(p.playlistId, p)
            }
        }

        return YouTubeFeedPage(
            items = flatVideos.values.toList(),
            shelves = shelves,
            channels = flatChannels.values.toList(),
            playlists = flatPlaylists.values.toList(),
            continuation = pageContinuation,
            visitorData = visitorData
        )
    }

    fun parseChannelFeed(root: JsonObject, fallbackChannelId: String): YouTubeChannelFeed {
        val metadataObj = (root["metadata"] as? JsonObject)?.get("channelMetadataRenderer") as? JsonObject
        val c4Header = (root["header"] as? JsonObject)?.get("c4TabbedHeaderRenderer") as? JsonObject
        val pageHeaderVm = ((root["header"] as? JsonObject)
            ?.get("pageHeaderRenderer") as? JsonObject)
            ?.let { (it["content"] as? JsonObject)?.get("pageHeaderViewModel") as? JsonObject }

        val resolvedChannelId = c4Header?.get("channelId")?.jsonPrimitive?.contentOrNull
            ?: metadataObj?.get("externalId")?.jsonPrimitive?.contentOrNull
            ?: findFirstStringByKey(root["header"], "channelId")
            ?: fallbackChannelId

        val title = extractText(c4Header?.get("title"))
            ?: pageHeaderVm?.let {
                ((it["title"] as? JsonObject)?.get("dynamicTextViewModel") as? JsonObject)
                    ?.let { dt -> (dt["text"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull }
            }
            ?: metadataObj?.get("title")?.jsonPrimitive?.contentOrNull
            ?: resolvedChannelId

        val description = metadataObj?.get("description")?.jsonPrimitive?.contentOrNull
            ?: pageHeaderVm?.let {
                ((it["description"] as? JsonObject)?.get("descriptionPreviewViewModel") as? JsonObject)
                    ?.let { dp -> (dp["description"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNull }
            }

        var handle: String? = extractText(c4Header?.get("channelHandleText"))
            ?: metadataObj?.get("vanityChannelUrl")?.jsonPrimitive?.contentOrNull
                ?.substringAfterLast("/")?.takeIf { it.startsWith("@") }

        var subscriberCountText: String? = extractText(c4Header?.get("subscriberCountText"))
        var videoCountText: String? = extractText(c4Header?.get("videosCountText"))

        // Parse 2025/2026 pageHeaderViewModel metadata rows (@handle • 1.2M subscribers • 450 videos)
        if (pageHeaderVm != null) {
            val rows = ((pageHeaderVm["metadata"] as? JsonObject)
                ?.get("contentMetadataViewModel") as? JsonObject)
                ?.get("metadataRows") as? JsonArray
            if (rows != null) {
                for (rowEl in rows) {
                    val parts = (rowEl as? JsonObject)?.get("metadataParts") as? JsonArray ?: continue
                    for (partEl in parts) {
                        val txt = ((partEl as? JsonObject)?.get("text") as? JsonObject)
                            ?.get("content")?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                        when {
                            txt.startsWith("@") && handle == null -> handle = txt
                            txt.contains("subscriber", ignoreCase = true) && subscriberCountText == null ->
                                subscriberCountText = txt
                            txt.contains("video", ignoreCase = true) && videoCountText == null ->
                                videoCountText = txt
                        }
                    }
                }
            }
        }

        val avatarUrl = extractThumbnailUrl(c4Header?.get("avatar"))
            ?: extractThumbnailUrl(metadataObj?.get("avatar"))
            ?: pageHeaderVm?.let { extractViewModelAvatarUrl(it["image"]) }

        val bannerUrl = extractThumbnailUrl(c4Header?.get("banner"))
            ?: extractThumbnailUrl(c4Header?.get("tvBanner"))
            ?: pageHeaderVm?.let {
                val imgVm = ((it["banner"] as? JsonObject)?.get("imageBannerViewModel") as? JsonObject)
                    ?.get("image")
                extractViewModelImageSources(imgVm)
            }

        // Subscribe state & params
        val subButtonObj = findFirstRendererObject(
            root["header"],
            setOf("subscribeButtonRenderer", "subscribeButtonViewModel")
        )
        val isSubscribed = subButtonObj?.get("subscribed")?.jsonPrimitive?.booleanOrNull
        val subscribeParams = findFirstStringByKey(subButtonObj, "params")

        // Parse tabs (Videos, Live, Shorts, Playlists)
        val tabsList = ArrayList<YouTubeChannelTab>()
        val rawTabs = (((root["contents"] as? JsonObject)
            ?.get("twoColumnBrowseResultsRenderer") as? JsonObject)
            ?.get("tabs") as? JsonArray)
            ?: (((root["contents"] as? JsonObject)
                ?.get("singleColumnBrowseResultsRenderer") as? JsonObject)
                ?.get("tabs") as? JsonArray)

        var selectedTabTitle = ""
        if (rawTabs != null) {
            for (tabEl in rawTabs) {
                val tr = (tabEl as? JsonObject)?.get("tabRenderer") as? JsonObject ?: continue
                val tTitle = tr["title"]?.jsonPrimitive?.contentOrNull
                    ?: extractText(tr["title"])
                    ?: continue
                val browseEp = (tr["endpoint"] as? JsonObject)?.get("browseEndpoint") as? JsonObject
                val bId = browseEp?.get("browseId")?.jsonPrimitive?.contentOrNull ?: resolvedChannelId
                val bParams = browseEp?.get("params")?.jsonPrimitive?.contentOrNull
                val selected = tr["selected"]?.jsonPrimitive?.booleanOrNull == true
                if (selected) selectedTabTitle = tTitle
                tabsList.add(
                    YouTubeChannelTab(
                        title = tTitle,
                        browseId = bId,
                        params = bParams,
                        isSelected = selected
                    )
                )
            }
        }

        val page = parseFeedPage(
            root = root,
            defaultChannelName = title,
            defaultChannelId = resolvedChannelId
        )

        val videos = ArrayList<YouTubeVideoCard>()
        val liveVideos = ArrayList<YouTubeVideoCard>()
        val shorts = ArrayList<YouTubeVideoCard>()

        for (v in page.items) {
            when {
                v.isShort || selectedTabTitle.equals("Shorts", ignoreCase = true) -> shorts.add(v.copy(isShort = true))
                v.isLive || selectedTabTitle.equals("Live", ignoreCase = true) -> liveVideos.add(v)
                else -> videos.add(v)
            }
        }

        // Also bucket shelves on the channel Home tab ("Live now", "Shorts", "Videos")
        for (shelf in page.shelves) {
            val st = shelf.title.orEmpty()
            for (sv in shelf.videos) {
                when {
                    sv.isShort || st.contains("Short", ignoreCase = true) -> {
                        if (shorts.none { it.videoId == sv.videoId }) shorts.add(sv.copy(isShort = true))
                    }
                    sv.isLive || st.equals("Live", ignoreCase = true) || st.contains("Live now", ignoreCase = true) -> {
                        if (liveVideos.none { it.videoId == sv.videoId }) liveVideos.add(sv)
                    }
                    else -> {
                        if (videos.none { it.videoId == sv.videoId }) videos.add(sv)
                    }
                }
            }
        }

        return YouTubeChannelFeed(
            channelId = resolvedChannelId,
            title = title,
            description = description,
            handle = handle,
            avatarUrl = avatarUrl,
            bannerUrl = bannerUrl,
            subscriberCountText = subscriberCountText,
            videoCountText = videoCountText,
            isSubscribed = isSubscribed,
            subscribeParams = subscribeParams,
            videos = videos,
            liveVideos = liveVideos,
            shorts = shorts,
            tabs = tabsList,
            continuation = page.continuation
        )
    }

    fun extractVisitorData(root: JsonObject): String? {
        return (root["responseContext"] as? JsonObject)
            ?.get("visitorData")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private fun walkContainerNode(
        node: JsonObject,
        outVideos: MutableMap<String, YouTubeVideoCard>,
        outChannels: MutableMap<String, YouTubeChannelCard>,
        outPlaylists: MutableMap<String, YouTubePlaylistCard>,
        outShelves: MutableList<YouTubeShelf>,
        defaultChannelName: String?,
        defaultChannelId: String?
    ): String? {
        if (node.keys.any { it in AD_RENDERER_KEYS }) return null

        var continuationToken: String? = null

        // Unwrap tabs -> selected tab (or all tabs if none marked selected)
        val tabs = node["tabs"] as? JsonArray
        if (tabs != null) {
            val selectedTabs = tabs.filter {
                ((it as? JsonObject)?.get("tabRenderer") as? JsonObject)
                    ?.get("selected")?.jsonPrimitive?.booleanOrNull == true
            }.ifEmpty { tabs.toList() }

            for (tabEl in selectedTabs) {
                val tabObj = (tabEl as? JsonObject)?.get("tabRenderer") as? JsonObject
                    ?: (tabEl as? JsonObject)?.get("expandableTabRenderer") as? JsonObject
                    ?: continue
                val content = tabObj["content"] as? JsonObject ?: continue
                val tok = walkContainerNode(
                    content, outVideos, outChannels, outPlaylists, outShelves, defaultChannelName, defaultChannelId
                )
                if (tok != null) continuationToken = tok
            }
        }

        // Check if this node itself is a shelf
        val shelfObj = (node["shelfRenderer"] as? JsonObject)
            ?: (node["richShelfRenderer"] as? JsonObject)
            ?: (node["reelShelfRenderer"] as? JsonObject)
        if (shelfObj != null) {
            parseShelfObject(shelfObj, defaultChannelName, defaultChannelId)?.let { shelf ->
                if (shelf.videos.isNotEmpty() || shelf.channels.isNotEmpty() || shelf.playlists.isNotEmpty()) {
                    outShelves.add(shelf)
                }
            }
            return findDirectContinuation(shelfObj)
        }

        // Check item arrays commonly found in sectionListRenderer, richGridRenderer, gridRenderer, playlistVideoListRenderer, horizontalListRenderer
        for (arrayKey in listOf("contents", "items", "results", "videos", "secondaryContents")) {
            val arr = node[arrayKey] as? JsonArray ?: continue
            val tok = parseItemsList(
                items = arr,
                outVideos = outVideos,
                outChannels = outChannels,
                outPlaylists = outPlaylists,
                outShelves = outShelves,
                defaultChannelName = defaultChannelName,
                defaultChannelId = defaultChannelId
            )
            if (tok != null) continuationToken = tok
        }

        // Walk known structural wrapper objects
        for (wrapperKey in listOf(
            "twoColumnBrowseResultsRenderer",
            "singleColumnBrowseResultsRenderer",
            "twoColumnSearchResultsRenderer",
            "twoColumnWatchNextResults",
            "tvSurfaceContentRenderer",
            "tvSecondaryNavRenderer",
            "primaryContents",
            "content",
            "sectionListRenderer",
            "sectionListContinuation",
            "richGridRenderer",
            "richGridContinuation",
            "gridRenderer",
            "gridContinuation",
            "playlistVideoListRenderer",
            "playlistVideoListContinuation",
            "itemSectionRenderer",
            "itemSectionContinuation",
            "horizontalListRenderer",
            "verticalListRenderer",
            "expandedShelfContentsRenderer"
        )) {
            val child = node[wrapperKey] as? JsonObject ?: continue
            val tok = walkContainerNode(
                child, outVideos, outChannels, outPlaylists, outShelves, defaultChannelName, defaultChannelId
            )
            if (tok != null) continuationToken = tok
        }

        // Check tvSecondaryNavRenderer sections (used in TVHTML5 Subscriptions / Library)
        val sections = node["sections"] as? JsonArray
        if (sections != null) {
            for (secEl in sections) {
                val secObj = secEl as? JsonObject ?: continue
                val tok = walkContainerNode(
                    secObj, outVideos, outChannels, outPlaylists, outShelves, defaultChannelName, defaultChannelId
                )
                if (tok != null) continuationToken = tok
            }
        }

        if (continuationToken == null) {
            continuationToken = findDirectContinuation(node)
        }
        return continuationToken
    }

    private fun parseItemsList(
        items: JsonArray,
        outVideos: MutableMap<String, YouTubeVideoCard>,
        outChannels: MutableMap<String, YouTubeChannelCard>,
        outPlaylists: MutableMap<String, YouTubePlaylistCard>,
        outShelves: MutableList<YouTubeShelf>,
        defaultChannelName: String?,
        defaultChannelId: String?
    ): String? {
        var continuation: String? = null
        for (el in items) {
            val obj = el as? JsonObject ?: continue
            if (obj.keys.any { it in AD_RENDERER_KEYS }) continue

            // Continuation item
            val contItem = obj["continuationItemRenderer"] as? JsonObject
            if (contItem != null) {
                val tok = extractContinuationFromItem(contItem)
                if (tok != null) continuation = tok
                continue
            }

            // Unwrap richItemRenderer / richSectionRenderer
            val richItemContent = (obj["richItemRenderer"] as? JsonObject)?.get("content") as? JsonObject
            if (richItemContent != null) {
                if (richItemContent.keys.any { it in AD_RENDERER_KEYS }) continue
                parseLeafItem(richItemContent, outVideos, outChannels, outPlaylists, defaultChannelName, defaultChannelId)
                continue
            }
            val richSectionContent = (obj["richSectionRenderer"] as? JsonObject)?.get("content") as? JsonObject
            if (richSectionContent != null) {
                if (richSectionContent.keys.any { it in AD_RENDERER_KEYS }) continue
                val tok = walkContainerNode(
                    richSectionContent, outVideos, outChannels, outPlaylists, outShelves, defaultChannelName, defaultChannelId
                )
                if (tok != null) continuation = tok
                continue
            }

            // Direct shelf
            val shelfObj = (obj["shelfRenderer"] as? JsonObject)
                ?: (obj["richShelfRenderer"] as? JsonObject)
                ?: (obj["reelShelfRenderer"] as? JsonObject)
            if (shelfObj != null) {
                parseShelfObject(shelfObj, defaultChannelName, defaultChannelId)?.let { shelf ->
                    if (shelf.videos.isNotEmpty() || shelf.channels.isNotEmpty() || shelf.playlists.isNotEmpty()) {
                        outShelves.add(shelf)
                    }
                }
                continue
            }

            // Direct leaf renderer (video, lockup, tile, channel, playlist)
            if (parseLeafItem(obj, outVideos, outChannels, outPlaylists, defaultChannelName, defaultChannelId)) {
                continue
            }

            // Nested container (e.g. itemSectionRenderer, gridRenderer)
            val tok = walkContainerNode(
                obj, outVideos, outChannels, outPlaylists, outShelves, defaultChannelName, defaultChannelId
            )
            if (tok != null) continuation = tok
        }
        return continuation
    }

    private fun parseShelfObject(
        shelfObj: JsonObject,
        defaultChannelName: String?,
        defaultChannelId: String?
    ): YouTubeShelf? {
        val title = extractText(shelfObj["title"])
            ?: extractText((shelfObj["headerRenderer"] as? JsonObject)?.let {
                (it["shelfHeaderRenderer"] as? JsonObject)?.get("title")
            })

        val shelfVideos = LinkedHashMap<String, YouTubeVideoCard>()
        val shelfChannels = LinkedHashMap<String, YouTubeChannelCard>()
        val shelfPlaylists = LinkedHashMap<String, YouTubePlaylistCard>()
        val nestedShelves = ArrayList<YouTubeShelf>()

        var cont: String? = null
        val directItems = (shelfObj["items"] as? JsonArray) ?: (shelfObj["contents"] as? JsonArray)
        if (directItems != null) {
            cont = parseItemsList(
                directItems, shelfVideos, shelfChannels, shelfPlaylists, nestedShelves, defaultChannelName, defaultChannelId
            )
        }
        val contentObj = shelfObj["content"] as? JsonObject
        if (contentObj != null) {
            val cTok = walkContainerNode(
                contentObj, shelfVideos, shelfChannels, shelfPlaylists, nestedShelves, defaultChannelName, defaultChannelId
            )
            if (cTok != null) cont = cTok
        }
        if (cont == null) cont = findDirectContinuation(shelfObj)

        return YouTubeShelf(
            title = title,
            videos = shelfVideos.values.toList(),
            channels = shelfChannels.values.toList(),
            playlists = shelfPlaylists.values.toList(),
            continuation = cont
        )
    }

    private fun parseLeafItem(
        obj: JsonObject,
        outVideos: MutableMap<String, YouTubeVideoCard>,
        outChannels: MutableMap<String, YouTubeChannelCard>,
        outPlaylists: MutableMap<String, YouTubePlaylistCard>,
        defaultChannelName: String?,
        defaultChannelId: String?
    ): Boolean {
        // 1. Classic WEB video renderers
        for (vKey in listOf(
            "videoRenderer",
            "gridVideoRenderer",
            "compactVideoRenderer",
            "playlistVideoRenderer",
            "videoWithContextRenderer"
        )) {
            val vr = obj[vKey] as? JsonObject ?: continue
            parseClassicVideoRenderer(vr, defaultChannelName, defaultChannelId)?.let {
                outVideos.putIfAbsent(it.videoId, it)
            }
            return true
        }

        // 2. Shorts / Reel renderers
        val reelR = obj["reelItemRenderer"] as? JsonObject
        if (reelR != null) {
            parseReelItemRenderer(reelR, defaultChannelName, defaultChannelId)?.let {
                outVideos.putIfAbsent(it.videoId, it)
            }
            return true
        }
        val shortsLockup = obj["shortsLockupViewModel"] as? JsonObject
        if (shortsLockup != null) {
            parseShortsLockupViewModel(shortsLockup, defaultChannelName, defaultChannelId)?.let {
                outVideos.putIfAbsent(it.videoId, it)
            }
            return true
        }

        // 3. Modern 2025/2026 lockupViewModel (Video, Short, or Playlist)
        val lockup = obj["lockupViewModel"] as? JsonObject
        if (lockup != null) {
            parseLockupViewModel(lockup, outVideos, outPlaylists, defaultChannelName, defaultChannelId)
            return true
        }

        // 4. TVHTML5 tileRenderer (Video, Channel, or Playlist)
        val tile = obj["tileRenderer"] as? JsonObject
        if (tile != null) {
            parseTvTileRenderer(tile, outVideos, outChannels, outPlaylists, defaultChannelName, defaultChannelId)
            return true
        }

        // 5. Classic Channel renderers
        for (cKey in listOf("channelRenderer", "gridChannelRenderer", "compactChannelRenderer")) {
            val cr = obj[cKey] as? JsonObject ?: continue
            parseChannelRenderer(cr)?.let { outChannels.putIfAbsent(it.channelId, it) }
            return true
        }

        // 6. Classic Playlist renderers
        for (pKey in listOf("playlistRenderer", "gridPlaylistRenderer", "compactPlaylistRenderer")) {
            val pr = obj[pKey] as? JsonObject ?: continue
            parsePlaylistRenderer(pr, defaultChannelName)?.let { outPlaylists.putIfAbsent(it.playlistId, it) }
            return true
        }

        return false
    }

    private fun parseClassicVideoRenderer(
        vr: JsonObject,
        defaultChannelName: String?,
        defaultChannelId: String?
    ): YouTubeVideoCard? {
        val videoId = vr["videoId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 8..16 }
            ?: return null
        // Skip inline ads disguised with adEndpoint
        val navEp = vr["navigationEndpoint"] as? JsonObject
        if (navEp != null && (navEp.containsKey("adEndpoint") || navEp.containsKey("urlEndpoint"))) {
            return null
        }

        val title = extractText(vr["title"])
            ?: extractText(vr["headline"])
            ?: return null

        val ownerNode = vr["ownerText"] ?: vr["longBylineText"] ?: vr["shortBylineText"]
        val channelName = extractText(ownerNode)
            ?: defaultChannelName
            ?: ""
        val channelId = extractBrowseIdFromRuns(ownerNode) ?: defaultChannelId

        val channelAvatar = extractThumbnailUrl(
            ((vr["channelThumbnailSupportedRenderers"] as? JsonObject)
                ?.get("channelThumbnailWithLinkRenderer") as? JsonObject)
                ?.get("thumbnail")
        ) ?: extractThumbnailUrl(vr["channelThumbnail"])

        val thumbUrl = extractThumbnailUrl(vr["thumbnail"])
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        var durationText = extractText(vr["lengthText"])
        var resumePercent: Int? = null
        var isLive = vr["isLive"]?.jsonPrimitive?.booleanOrNull == true
        var isShort = false

        val overlays = vr["thumbnailOverlays"] as? JsonArray
        if (overlays != null) {
            for (ovEl in overlays) {
                val ovObj = ovEl as? JsonObject ?: continue
                val timeStatus = ovObj["thumbnailOverlayTimeStatusRenderer"] as? JsonObject
                if (timeStatus != null) {
                    val style = timeStatus["style"]?.jsonPrimitive?.contentOrNull
                    val tTxt = extractText(timeStatus["text"])
                    if (style == "LIVE" || tTxt.equals("LIVE", ignoreCase = true)) {
                        isLive = true
                    } else if (style == "SHORTS" || tTxt.equals("SHORTS", ignoreCase = true)) {
                        isShort = true
                    } else if (durationText == null && !tTxt.isNullOrBlank()) {
                        durationText = tTxt
                    }
                }
                val resumeObj = ovObj["thumbnailOverlayResumePlaybackRenderer"] as? JsonObject
                if (resumeObj != null) {
                    resumePercent = resumeObj["percentDurationWatched"]?.jsonPrimitive?.intOrNull
                }
            }
        }

        val badges = vr["badges"] as? JsonArray
        if (badges != null && !isLive) {
            for (bEl in badges) {
                val bStyle = ((bEl as? JsonObject)?.get("metadataBadgeRenderer") as? JsonObject)
                    ?.get("style")?.jsonPrimitive?.contentOrNull
                if (bStyle != null && bStyle.contains("LIVE", ignoreCase = true)) {
                    isLive = true
                }
            }
        }

        val watchEp = navEp?.get("watchEndpoint") as? JsonObject
        val reelEp = navEp?.get("reelWatchEndpoint") as? JsonObject
        if (reelEp != null) isShort = true

        val startSec = watchEp?.get("startTimeSeconds")?.jsonPrimitive?.longOrNull
        val playlistId = watchEp?.get("playlistId")?.jsonPrimitive?.contentOrNull

        val viewCountText = extractText(vr["shortViewCountText"]) ?: extractText(vr["viewCountText"])
        val publishedText = extractText(vr["publishedTimeText"])
        val durationSec = vr["lengthSeconds"]?.jsonPrimitive?.longOrNull ?: parseDurationSec(durationText)

        return YouTubeVideoCard(
            videoId = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            channelAvatarUrl = channelAvatar,
            thumbnailUrl = thumbUrl,
            durationText = if (isLive) "LIVE" else durationText,
            durationSec = durationSec,
            viewCountText = viewCountText,
            publishedText = publishedText,
            resumePercent = resumePercent,
            startSeconds = startSec,
            isLive = isLive,
            isShort = isShort,
            playlistId = playlistId
        )
    }

    private fun parseLockupViewModel(
        lockup: JsonObject,
        outVideos: MutableMap<String, YouTubeVideoCard>,
        outPlaylists: MutableMap<String, YouTubePlaylistCard>,
        defaultChannelName: String?,
        defaultChannelId: String?
    ) {
        val contentType = lockup["contentType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val contentId = lockup["contentId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val metaVm = (lockup["metadata"] as? JsonObject)?.get("lockupMetadataViewModel") as? JsonObject
        val title = ((metaVm?.get("title") as? JsonObject)?.get("content"))?.jsonPrimitive?.contentOrNull
            ?: return

        val avatarUrl = extractViewModelAvatarUrl(metaVm["image"])

        var channelName: String? = defaultChannelName
        var channelId: String? = defaultChannelId
        var viewCountText: String? = null
        var publishedText: String? = null
        var videoCountText: String? = null

        val rows = ((metaVm["metadata"] as? JsonObject)
            ?.get("contentMetadataViewModel") as? JsonObject)
            ?.get("metadataRows") as? JsonArray

        if (rows != null) {
            rows.forEachIndexed { rowIdx, rowEl ->
                val parts = (rowEl as? JsonObject)?.get("metadataParts") as? JsonArray ?: return@forEachIndexed
                for (partEl in parts) {
                    val textObj = (partEl as? JsonObject)?.get("text") as? JsonObject ?: continue
                    val partText = textObj["content"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val partBrowseId = findFirstStringByKey(textObj["commandRuns"], "browseId")
                    when {
                        partBrowseId != null && partBrowseId.startsWith("UC") -> {
                            channelId = partBrowseId
                            channelName = partText
                        }
                        rowIdx == 0 && channelName.isNullOrBlank() &&
                            !partText.contains("view", ignoreCase = true) &&
                            !partText.contains("video", ignoreCase = true) -> {
                            channelName = partText
                        }
                        partText.contains("video", ignoreCase = true) ||
                            partText.contains("episode", ignoreCase = true) -> {
                            videoCountText = partText
                        }
                        partText.contains("view", ignoreCase = true) ||
                            partText.contains("watching", ignoreCase = true) -> {
                            viewCountText = partText
                        }
                        partText.contains("ago", ignoreCase = true) ||
                            partText.contains("Streamed", ignoreCase = true) ||
                            partText.contains("Premiered", ignoreCase = true) -> {
                            publishedText = partText
                        }
                    }
                }
            }
        }

        // Thumbnail & overlays
        val contentImage = lockup["contentImage"] as? JsonObject
        val thumbVm = (contentImage?.get("thumbnailViewModel") as? JsonObject)
            ?: (((contentImage?.get("collectionThumbnailViewModel") as? JsonObject)
                ?.get("primaryThumbnail") as? JsonObject)
                ?.get("thumbnailViewModel") as? JsonObject)

        val rawThumb = extractViewModelImageSources(thumbVm?.get("image"))
        var durationText: String? = null
        var isLive = false
        var resumePercent: Int? = null

        val overlays = thumbVm?.get("overlays") as? JsonArray
        if (overlays != null) {
            for (ovEl in overlays) {
                val ovObj = ovEl as? JsonObject ?: continue
                val badgeVm = ovObj["thumbnailOverlayBadgeViewModel"] as? JsonObject
                if (badgeVm != null) {
                    val badges = badgeVm["thumbnailBadges"] as? JsonArray
                    if (badges != null) {
                        for (bEl in badges) {
                            val bvm = (bEl as? JsonObject)?.get("thumbnailBadgeViewModel") as? JsonObject ?: continue
                            val bTxt = bvm["text"]?.jsonPrimitive?.contentOrNull?.trim()
                            val bStyle = bvm["badgeStyle"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (bStyle.contains("LIVE", ignoreCase = true) || bTxt.equals("LIVE", ignoreCase = true)) {
                                isLive = true
                            } else if (!bTxt.isNullOrBlank()) {
                                if (bTxt.contains("video", ignoreCase = true)) {
                                    if (videoCountText == null) videoCountText = bTxt
                                } else if (durationText == null) {
                                    durationText = bTxt
                                }
                            }
                        }
                    }
                }
                val bottomVm = ovObj["thumbnailBottomOverlayViewModel"] as? JsonObject
                if (bottomVm != null) {
                    val pct = ((bottomVm["progressBar"] as? JsonObject)
                        ?.get("thumbnailOverlayProgressBarViewModel") as? JsonObject)
                        ?.get("startPercent")?.let {
                            it.jsonPrimitive.intOrNull ?: it.jsonPrimitive.floatOrNull?.toInt()
                        }
                    if (pct != null) resumePercent = pct
                }
            }
        }

        val onTapCmd = (((lockup["rendererContext"] as? JsonObject)
            ?.get("commandContext") as? JsonObject)
            ?.get("onTap") as? JsonObject)
            ?.get("innertubeCommand") as? JsonObject

        // Skip ad lockups
        if (onTapCmd != null && (onTapCmd.containsKey("adEndpoint") || onTapCmd.containsKey("adsControlFlowOpportunityReceivedCommand"))) {
            return
        }

        val watchEp = onTapCmd?.get("watchEndpoint") as? JsonObject
        val browseEp = onTapCmd?.get("browseEndpoint") as? JsonObject

        val isPlaylistLockup = contentType.contains("PLAYLIST", ignoreCase = true) ||
            contentType.contains("PODCAST", ignoreCase = true) ||
            (browseEp?.get("browseId")?.jsonPrimitive?.contentOrNull?.startsWith("VL") == true)

        if (isPlaylistLockup) {
            val rawPid = contentId
                ?: browseEp?.get("browseId")?.jsonPrimitive?.contentOrNull?.removePrefix("VL")
                ?: watchEp?.get("playlistId")?.jsonPrimitive?.contentOrNull
                ?: return
            val cleanPid = rawPid.removePrefix("VL")
            outPlaylists.putIfAbsent(
                cleanPid,
                YouTubePlaylistCard(
                    playlistId = cleanPid,
                    title = title,
                    author = channelName,
                    thumbnailUrl = rawThumb,
                    videoCountText = videoCountText ?: durationText
                )
            )
            return
        }

        val videoId = watchEp?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: contentId?.takeIf { it.length == 11 }
            ?: return

        val startSec = watchEp?.get("startTimeSeconds")?.jsonPrimitive?.longOrNull
        val playlistId = watchEp?.get("playlistId")?.jsonPrimitive?.contentOrNull

        outVideos.putIfAbsent(
            videoId,
            YouTubeVideoCard(
                videoId = videoId,
                title = title,
                channelName = channelName.orEmpty(),
                channelId = channelId,
                channelAvatarUrl = avatarUrl,
                thumbnailUrl = rawThumb ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                durationText = if (isLive) "LIVE" else durationText,
                durationSec = parseDurationSec(durationText),
                viewCountText = viewCountText,
                publishedText = publishedText,
                resumePercent = resumePercent,
                startSeconds = startSec,
                isLive = isLive,
                isShort = contentType.contains("SHORT", ignoreCase = true),
                playlistId = playlistId
            )
        )
    }

    private fun parseTvTileRenderer(
        tile: JsonObject,
        outVideos: MutableMap<String, YouTubeVideoCard>,
        outChannels: MutableMap<String, YouTubeChannelCard>,
        outPlaylists: MutableMap<String, YouTubePlaylistCard>,
        defaultChannelName: String?,
        defaultChannelId: String?
    ) {
        val onSelect = tile["onSelectCommand"] as? JsonObject
        if (onSelect != null && (onSelect.containsKey("adEndpoint") || onSelect.containsKey("urlEndpoint"))) {
            return
        }

        val header = (tile["header"] as? JsonObject)?.get("tileHeaderRenderer") as? JsonObject
        val meta = (tile["metadata"] as? JsonObject)?.get("tileMetadataRenderer") as? JsonObject

        val title = extractText(meta?.get("title"))
            ?: extractText(header?.get("thumbnailAltText"))
            ?: return

        val thumbUrl = extractThumbnailUrl(header?.get("thumbnail"))

        // Extract lines of metadata from TVHTML5 tileMetadataRenderer.lines
        val lineTexts = ArrayList<String>()
        var isLive = false
        val lines = meta?.get("lines") as? JsonArray
        if (lines != null) {
            for (lineEl in lines) {
                val items = ((lineEl as? JsonObject)?.get("lineRenderer") as? JsonObject)
                    ?.get("items") as? JsonArray ?: continue
                val lineBuilder = StringBuilder()
                for (itemEl in items) {
                    val lir = (itemEl as? JsonObject)?.get("lineItemRenderer") as? JsonObject ?: continue
                    val badgeStyle = ((lir["badge"] as? JsonObject)?.get("metadataBadgeRenderer") as? JsonObject)
                        ?.get("style")?.jsonPrimitive?.contentOrNull
                    if (badgeStyle != null && badgeStyle.contains("LIVE", ignoreCase = true)) {
                        isLive = true
                    }
                    val txt = extractText(lir["text"])
                    if (!txt.isNullOrBlank()) lineBuilder.append(txt)
                }
                val combined = lineBuilder.toString().trim()
                if (combined.isNotEmpty()) lineTexts.add(combined)
            }
        }

        val watchEp = onSelect?.get("watchEndpoint") as? JsonObject
        val reelEp = onSelect?.get("reelWatchEndpoint") as? JsonObject
        val watchPlaylistEp = onSelect?.get("watchPlaylistEndpoint") as? JsonObject
        val browseEp = onSelect?.get("browseEndpoint") as? JsonObject

        val videoId = watchEp?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: reelEp?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: tile["contentId"]?.jsonPrimitive?.contentOrNull?.takeIf {
                it.length == 11 && browseEp == null && watchPlaylistEp == null
            }

        if (!videoId.isNullOrBlank()) {
            var durationText: String? = null
            var resumePercent: Int? = null
            val overlays = header?.get("thumbnailOverlays") as? JsonArray
            if (overlays != null) {
                for (ovEl in overlays) {
                    val ovObj = ovEl as? JsonObject ?: continue
                    val ts = ovObj["thumbnailOverlayTimeStatusRenderer"] as? JsonObject
                    if (ts != null) {
                        val st = ts["style"]?.jsonPrimitive?.contentOrNull
                        val tt = extractText(ts["text"])
                        if (st == "LIVE" || tt.equals("LIVE", ignoreCase = true)) {
                            isLive = true
                        } else if (!tt.isNullOrBlank()) {
                            durationText = tt
                        }
                    }
                    val rp = ovObj["thumbnailOverlayResumePlaybackRenderer"] as? JsonObject
                    if (rp != null) {
                        resumePercent = rp["percentDurationWatched"]?.jsonPrimitive?.intOrNull
                    }
                }
            }

            // Line 0 is typically channelName; Line 1 is typically "120K views • 2 hours ago"
            var channelName = defaultChannelName.orEmpty()
            var viewCountText: String? = null
            var publishedText: String? = null

            for (ln in lineTexts) {
                if (ln.contains("view", ignoreCase = true) || ln.contains("watching", ignoreCase = true)) {
                    val parts = ln.split("•", "·").map { it.trim() }.filter { it.isNotEmpty() }
                    viewCountText = parts.getOrNull(0) ?: ln
                    if (parts.size > 1) publishedText = parts.drop(1).joinToString(" • ")
                } else if (ln.contains("ago", ignoreCase = true) && publishedText == null) {
                    publishedText = ln
                } else if (channelName.isBlank()) {
                    channelName = ln
                }
            }

            // Look for channel browseId in menu items if present
            val menuChannelId = findFirstStringByKey(tile["menu"], "browseId")
                ?.takeIf { it.startsWith("UC") }
                ?: defaultChannelId

            outVideos.putIfAbsent(
                videoId,
                YouTubeVideoCard(
                    videoId = videoId,
                    title = title,
                    channelName = channelName,
                    channelId = menuChannelId,
                    thumbnailUrl = thumbUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    durationText = if (isLive) "LIVE" else durationText,
                    durationSec = parseDurationSec(durationText),
                    viewCountText = viewCountText,
                    publishedText = publishedText,
                    resumePercent = resumePercent,
                    startSeconds = watchEp?.get("startTimeSeconds")?.jsonPrimitive?.longOrNull,
                    isLive = isLive,
                    isShort = reelEp != null || tile["style"]?.jsonPrimitive?.contentOrNull?.contains("SHORTS", true) == true,
                    playlistId = watchEp?.get("playlistId")?.jsonPrimitive?.contentOrNull
                )
            )
            return
        }

        val browseId = browseEp?.get("browseId")?.jsonPrimitive?.contentOrNull
        if (browseId != null && browseId.startsWith("UC")) {
            val handle = lineTexts.firstOrNull { it.startsWith("@") }
            val subTxt = lineTexts.firstOrNull { it.contains("subscriber", ignoreCase = true) }
            outChannels.putIfAbsent(
                browseId,
                YouTubeChannelCard(
                    channelId = browseId,
                    title = title,
                    handle = handle,
                    avatarUrl = thumbUrl,
                    subscriberCountText = subTxt
                )
            )
            return
        }

        val playlistId = watchPlaylistEp?.get("playlistId")?.jsonPrimitive?.contentOrNull
            ?: browseId?.takeIf { it.startsWith("VL") || it.startsWith("PL") }?.removePrefix("VL")
        if (!playlistId.isNullOrBlank()) {
            outPlaylists.putIfAbsent(
                playlistId,
                YouTubePlaylistCard(
                    playlistId = playlistId,
                    title = title,
                    author = lineTexts.firstOrNull { !it.contains("video", ignoreCase = true) } ?: defaultChannelName,
                    thumbnailUrl = thumbUrl,
                    videoCountText = lineTexts.firstOrNull { it.contains("video", ignoreCase = true) }
                )
            )
        }
    }

    private fun parseReelItemRenderer(
        reelR: JsonObject,
        defaultChannelName: String?,
        defaultChannelId: String?
    ): YouTubeVideoCard? {
        val videoId = reelR["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = extractText(reelR["headline"]) ?: return null
        val views = extractText(reelR["viewCountText"])
        val thumb = extractThumbnailUrl(reelR["thumbnail"])
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return YouTubeVideoCard(
            videoId = videoId,
            title = title,
            channelName = defaultChannelName.orEmpty(),
            channelId = defaultChannelId,
            thumbnailUrl = thumb,
            durationText = "SHORTS",
            viewCountText = views,
            isShort = true
        )
    }

    private fun parseShortsLockupViewModel(
        shortsVm: JsonObject,
        defaultChannelName: String?,
        defaultChannelId: String?
    ): YouTubeVideoCard? {
        val videoId = findFirstStringByKey(shortsVm["onTap"], "videoId")
            ?: shortsVm["entityId"]?.jsonPrimitive?.contentOrNull?.substringAfterLast("-")?.takeIf { it.length == 11 }
            ?: return null
        val overlayMeta = shortsVm["overlayMetadata"] as? JsonObject
        val title = ((overlayMeta?.get("primaryText") as? JsonObject)?.get("content"))?.jsonPrimitive?.contentOrNull
            ?: shortsVm["accessibilityText"]?.jsonPrimitive?.contentOrNull?.substringBefore(",")
            ?: return null
        val views = ((overlayMeta?.get("secondaryText") as? JsonObject)?.get("content"))?.jsonPrimitive?.contentOrNull
        val thumb = extractViewModelImageSources(
            (shortsVm["thumbnail"] as? JsonObject)?.get("sources")?.let { shortsVm["thumbnail"] }
                ?: (shortsVm["thumbnailViewModel"] as? JsonObject)?.get("image")
        ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        return YouTubeVideoCard(
            videoId = videoId,
            title = title,
            channelName = defaultChannelName.orEmpty(),
            channelId = defaultChannelId,
            thumbnailUrl = thumb,
            durationText = "SHORTS",
            viewCountText = views,
            isShort = true
        )
    }

    private fun parseChannelRenderer(cr: JsonObject): YouTubeChannelCard? {
        val channelId = cr["channelId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = extractText(cr["title"]) ?: return null
        val rawSub = extractText(cr["subscriberCountText"])
        val rawVid = extractText(cr["videoCountText"])
        // Modern YouTube WEB swaps subscriberCountText (@handle) and videoCountText ("1.5M subscribers")
        val handle = listOfNotNull(rawSub, rawVid).firstOrNull { it.startsWith("@") }
        val subCount = listOfNotNull(rawSub, rawVid).firstOrNull { it.contains("subscriber", ignoreCase = true) }
        val vidCount = listOfNotNull(rawSub, rawVid).firstOrNull { it.contains("video", ignoreCase = true) }
        val avatar = extractThumbnailUrl(cr["thumbnail"])
        val subscribed = ((cr["subscribeButton"] as? JsonObject)?.get("subscribeButtonRenderer") as? JsonObject)
            ?.get("subscribed")?.jsonPrimitive?.booleanOrNull == true

        return YouTubeChannelCard(
            channelId = channelId,
            title = title,
            handle = handle,
            avatarUrl = avatar,
            subscriberCountText = subCount,
            videoCountText = vidCount,
            isSubscribed = subscribed
        )
    }

    private fun parsePlaylistRenderer(pr: JsonObject, defaultAuthor: String?): YouTubePlaylistCard? {
        val playlistId = pr["playlistId"]?.jsonPrimitive?.contentOrNull?.removePrefix("VL") ?: return null
        val title = extractText(pr["title"]) ?: return null
        val author = extractText(pr["shortBylineText"]) ?: extractText(pr["longBylineText"]) ?: defaultAuthor
        val thumb = extractThumbnailUrl(pr["thumbnail"])
            ?: (pr["thumbnails"] as? JsonArray)?.firstOrNull()?.let { extractThumbnailUrl(it) }
        val countTxt = extractText(pr["videoCountText"])
            ?: pr["videoCount"]?.jsonPrimitive?.contentOrNull?.let { "$it videos" }

        return YouTubePlaylistCard(
            playlistId = playlistId,
            title = title,
            author = author,
            thumbnailUrl = thumb,
            videoCountText = countTxt
        )
    }

    private fun extractContinuationFromItem(contItem: JsonObject): String? {
        val ep = contItem["continuationEndpoint"] as? JsonObject
        (ep?.get("continuationCommand") as? JsonObject)
            ?.get("token")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }?.let { return it }

        val subCmds = (ep?.get("commandExecutorCommand") as? JsonObject)?.get("commands") as? JsonArray
        if (subCmds != null) {
            for (cmdEl in subCmds) {
                val tok = ((cmdEl as? JsonObject)?.get("continuationCommand") as? JsonObject)
                    ?.get("token")?.jsonPrimitive?.contentOrNull
                if (!tok.isNullOrBlank()) return tok
            }
        }
        return findFirstStringByKey(contItem, "token")
    }

    private fun findDirectContinuation(obj: JsonObject): String? {
        val conts = obj["continuations"] as? JsonArray ?: return null
        for (cEl in conts) {
            val cObj = cEl as? JsonObject ?: continue
            val next = (cObj["nextContinuationData"] as? JsonObject)
                ?: (cObj["reloadContinuationData"] as? JsonObject)
            val tok = next?.get("continuation")?.jsonPrimitive?.contentOrNull
            if (!tok.isNullOrBlank()) return tok
        }
        return null
    }

    private fun findTopContinuationToken(element: JsonElement?): String? {
        if (element == null) return null
        when (element) {
            is JsonObject -> {
                findDirectContinuation(element)?.let { return it }
                val contItem = element["continuationItemRenderer"] as? JsonObject
                if (contItem != null) {
                    extractContinuationFromItem(contItem)?.let { return it }
                }
                for ((_, v) in element) {
                    findTopContinuationToken(v)?.let { return it }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    findTopContinuationToken(item)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    internal fun extractText(element: JsonElement?): String? {
        if (element == null) return null
        if (element is JsonPrimitive) return element.contentOrNull?.takeIf { it.isNotBlank() }
        val obj = element as? JsonObject ?: return null
        obj["simpleText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        obj["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val runs = obj["runs"] as? JsonArray ?: return null
        val text = buildString {
            for (r in runs) {
                val t = (r as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                if (!t.isNullOrEmpty()) append(t)
            }
        }.trim()
        return text.takeIf { it.isNotBlank() }
    }

    private fun extractBrowseIdFromRuns(element: JsonElement?): String? {
        val runs = (element as? JsonObject)?.get("runs") as? JsonArray ?: return null
        for (r in runs) {
            val bId = (((r as? JsonObject)?.get("navigationEndpoint") as? JsonObject)
                ?.get("browseEndpoint") as? JsonObject)
                ?.get("browseId")?.jsonPrimitive?.contentOrNull
            if (!bId.isNullOrBlank()) return bId
        }
        return null
    }

    internal fun extractThumbnailUrl(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        val thumbs = (obj["thumbnails"] as? JsonArray)
            ?: ((obj["musicThumbnailRenderer"] as? JsonObject)?.get("thumbnail") as? JsonObject)?.get("thumbnails") as? JsonArray
            ?: return null
        val best = thumbs.lastOrNull() as? JsonObject ?: return null
        val raw = best["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return normalizeImageUrl(raw)
    }

    private fun extractViewModelImageSources(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        val sources = obj["sources"] as? JsonArray ?: return null
        val best = sources.lastOrNull() as? JsonObject ?: return null
        val raw = best["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return normalizeImageUrl(raw)
    }

    private fun extractViewModelAvatarUrl(imageNode: JsonElement?): String? {
        val obj = imageNode as? JsonObject ?: return null
        val sources = (((obj["decoratedAvatarViewModel"] as? JsonObject)
            ?.get("avatar") as? JsonObject)
            ?.get("avatarViewModel") as? JsonObject)
            ?.let { (it["image"] as? JsonObject)?.get("sources") as? JsonArray }
            ?: ((obj["contentPreviewImageViewModel"] as? JsonObject)?.get("image") as? JsonObject)?.get("sources") as? JsonArray
            ?: (obj["avatarViewModel"] as? JsonObject)?.let { (it["image"] as? JsonObject)?.get("sources") as? JsonArray }
            ?: return null
        val raw = (sources.lastOrNull() as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: return null
        return normalizeImageUrl(raw)
    }

    private fun normalizeImageUrl(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "https://www.youtube.com$raw"
        else -> raw
    }

    internal fun parseDurationSec(durationText: String?): Long? {
        val clean = durationText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!clean[0].isDigit()) return null
        val parts = clean.split(':').map { it.trim().toLongOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> null
        }
    }

    private fun findFirstRendererObject(element: JsonElement?, keys: Set<String>): JsonObject? {
        if (element == null) return null
        when (element) {
            is JsonObject -> {
                for ((k, v) in element) {
                    if (k in keys && v is JsonObject) return v
                    findFirstRendererObject(v, keys)?.let { return it }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    findFirstRendererObject(item, keys)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    internal fun findFirstStringByKey(element: JsonElement?, targetKey: String): String? {
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
}
