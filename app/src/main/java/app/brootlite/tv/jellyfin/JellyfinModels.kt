package app.brootlite.tv.jellyfin

import app.brootlite.tv.data.ExternalSubtitle
import app.brootlite.tv.data.MetaItem
import app.brootlite.tv.data.VideoEpisode
import app.brootlite.tv.data.WatchHistoryEntry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.net.URLEncoder
import java.util.Locale

object JellyfinIds {
    const val PREFIX = "jf:"
    const val TICKS_PER_MS = 10_000L

    fun ticksToMs(ticks: Long?): Long =
        if (ticks != null && ticks > 0L) ticks / TICKS_PER_MS else 0L

    fun msToTicks(ms: Long): Long =
        ms.coerceAtLeast(0L) * TICKS_PER_MS

    fun buildId(serverId: String, itemId: String): String =
        "$PREFIX${serverId.trim()}:${rawItemId(itemId)}"

    fun isJellyfinId(id: String?): Boolean =
        id != null && id.startsWith(PREFIX) && id.count { it == ':' } >= 2

    /**
     * Parses `"jf:{serverId}:{itemId}"` into `(serverId, itemId)`.
     */
    fun parseId(compositeId: String): Pair<String, String>? {
        if (!compositeId.startsWith(PREFIX)) return null
        val rest = compositeId.removePrefix(PREFIX)
        val sep = rest.indexOf(':')
        if (sep <= 0 || sep >= rest.lastIndex) return null
        val serverId = rest.substring(0, sep).trim()
        val itemId = rest.substring(sep + 1).trim()
        if (serverId.isEmpty() || itemId.isEmpty()) return null
        return serverId to itemId
    }

    /**
     * Accepts either `"jf:{serverId}:{itemId}"` or raw `"{itemId}"` and returns raw `"{itemId}"`.
     */
    fun rawItemId(idOrComposite: String): String =
        parseId(idOrComposite)?.second ?: idOrComposite.trim()
}

@Serializable
data class JellyfinServerProfile(
    val serverId: String,
    val name: String,
    val primaryUrl: String,
    val localUrl: String = "",
    val remoteUrl: String = "",
    val userId: String = "",
    val username: String = "",
    val accessToken: String = "",
    val bootstrapToken: String = "",
    val allowInsecureTls: Boolean = false,
    val maxBitrateMbps: Int = 0, // 0 = 120 Mbps unlimited Direct Play
    val enabled: Boolean = true,
    val updatedAtMs: Long = 0L
) {
    val isAuthenticated: Boolean
        get() = userId.isNotBlank() && accessToken.isNotBlank()

    val effectiveMaxBitrateBps: Long
        get() = if (maxBitrateMbps <= 0) 120_000_000L else maxBitrateMbps.coerceIn(1, 250) * 1_000_000L

    val maxBitrateLabel: String
        get() = if (maxBitrateMbps <= 0) "Unlimited (120 Mbps)" else "$maxBitrateMbps Mbps"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JfLanDiscoveredServer(
    @JsonNames("Id", "id")
    val Id: String = "",
    @JsonNames("Name", "name")
    val Name: String = "",
    @JsonNames("Address", "address")
    val Address: String = "",
    @JsonNames("EndpointAddress", "endpointAddress")
    val EndpointAddress: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JfPublicSystemInfo(
    @JsonNames("Id", "id")
    val Id: String = "",
    @JsonNames("ServerName", "serverName")
    val ServerName: String = "",
    @JsonNames("Version", "version")
    val Version: String = "",
    @JsonNames("LocalAddress", "localAddress")
    val LocalAddress: String? = null,
    @JsonNames("StartupWizardCompleted", "startupWizardCompleted")
    val StartupWizardCompleted: Boolean = true,
    val resolvedBaseUrl: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JfQuickConnectState(
    @JsonNames("Secret", "secret")
    val Secret: String = "",
    @JsonNames("Code", "code")
    val Code: String = "",
    @JsonNames("Authenticated", "authenticated")
    val Authenticated: Boolean = false
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JfUser(
    @JsonNames("Id", "id")
    val Id: String = "",
    @JsonNames("Name", "name")
    val Name: String = "",
    @JsonNames("ServerId", "serverId")
    val ServerId: String? = null,
    @JsonNames("HasPassword", "hasPassword")
    val HasPassword: Boolean = true
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JfAuthResult(
    @JsonNames("AccessToken", "accessToken")
    val AccessToken: String = "",
    @JsonNames("ServerId", "serverId")
    val ServerId: String = "",
    @JsonNames("User", "user")
    val User: JfUser = JfUser()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JfView(
    @JsonNames("Id", "id")
    val Id: String = "",
    @JsonNames("Name", "name")
    val Name: String = "",
    @JsonNames("CollectionType", "collectionType")
    val CollectionType: String = "",
    val ImageTags: Map<String, String> = emptyMap()
) {
    val normalizedType: String
        get() = CollectionType.trim().lowercase(Locale.US)

    fun defaultIncludeItemTypes(): String? = when (normalizedType) {
        "movies" -> "Movie"
        "tvshows" -> "Series"
        "boxsets" -> "BoxSet"
        "musicvideos" -> "MusicVideo"
        "homevideos" -> "Video,Movie"
        else -> "Movie,Series,Video"
    }

    fun primaryImageUrl(baseUrl: String, maxWidth: Int = 480, quality: Int = 78): String {
        val clean = baseUrl.trimEnd('/')
        if (clean.isEmpty() || Id.isBlank()) return ""
        val tag = ImageTags["Primary"] ?: return ""
        return "$clean/Items/${urlEnc(Id)}/Images/Primary?maxWidth=$maxWidth&quality=$quality&tag=${urlEnc(tag)}"
    }
}

@Serializable
data class JfItemsEnvelope<T>(
    val Items: List<T> = emptyList(),
    val TotalRecordCount: Int = 0,
    val StartIndex: Int = 0
)

@Serializable
data class JfUserData(
    val PlaybackPositionTicks: Long = 0L,
    val PlayCount: Int = 0,
    val IsFavorite: Boolean = false,
    val Played: Boolean = false,
    val PlayedPercentage: Double? = null,
    val UnplayedItemCount: Int? = null,
    val LastPlayedDate: String? = null
) {
    val playbackPositionMs: Long
        get() = JellyfinIds.ticksToMs(PlaybackPositionTicks)
}

@Serializable
data class JfChapter(
    val StartPositionTicks: Long = 0L,
    val Name: String? = null,
    val ImageTag: String? = null
) {
    val startMs: Long
        get() = JellyfinIds.ticksToMs(StartPositionTicks)
}

@Serializable
data class JfMediaStream(
    val Index: Int = 0,
    val Type: String = "", // "Video" | "Audio" | "Subtitle"
    val Codec: String = "",
    val Language: String? = null,
    val DisplayTitle: String? = null,
    val Title: String? = null,
    val IsDefault: Boolean = false,
    val IsForced: Boolean = false,
    val IsExternal: Boolean = false,
    val IsTextSubtitleStream: Boolean = false,
    val SupportsExternalStream: Boolean = false,
    val DeliveryUrl: String? = null,
    val DeliveryMethod: String? = null, // "Embed" | "External" | "Encode" | "Hls"
    val Channels: Int? = null,
    val ChannelLayout: String? = null,
    val BitRate: Long? = null,
    val BitDepth: Int? = null,
    val Width: Int? = null,
    val Height: Int? = null,
    val VideoRange: String? = null,
    val VideoRangeType: String? = null
)

@Serializable
data class JfMediaSource(
    val Id: String = "",
    val Name: String = "",
    val Path: String? = null,
    val Protocol: String = "File",
    val Container: String? = null,
    val Size: Long? = null,
    val Bitrate: Long? = null,
    val RunTimeTicks: Long? = null,
    val SupportsDirectPlay: Boolean = true,
    val SupportsDirectStream: Boolean = true,
    val SupportsTranscoding: Boolean = true,
    val DirectStreamUrl: String? = null,
    val TranscodingUrl: String? = null,
    val TranscodingSubProtocol: String? = null,
    val TranscodingContainer: String? = null,
    val DefaultAudioStreamIndex: Int? = null,
    val DefaultSubtitleStreamIndex: Int? = null,
    val ETag: String? = null,
    val LiveStreamId: String? = null,
    val MediaStreams: List<JfMediaStream> = emptyList(),
    val RequiredHttpHeaders: Map<String, String> = emptyMap()
)

@Serializable
data class JfPlaybackInfoResponse(
    val MediaSources: List<JfMediaSource> = emptyList(),
    val PlaySessionId: String? = null,
    val ErrorCode: String? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JfMediaSegmentItem(
    @JsonNames("Id", "id")
    val Id: String = "",
    @JsonNames("ItemId", "itemId")
    val ItemId: String = "",
    @JsonNames("Type", "type")
    val Type: String = "", // "Intro" | "Outro" | "Recap" | "Preview" | "Commercial"
    @JsonNames("StartTicks", "startTicks")
    val StartTicks: Long = 0L,
    @JsonNames("EndTicks", "endTicks")
    val EndTicks: Long = 0L
)

@Serializable
data class JfMediaSegmentsResponse(
    val Items: List<JfMediaSegmentItem> = emptyList()
)

@Serializable
data class JfItem(
    val Id: String = "",
    val Name: String = "",
    val SeriesId: String? = null,
    val SeriesName: String? = null,
    val SeasonId: String? = null,
    val SeasonName: String? = null,
    val ParentIndexNumber: Int? = null,
    val IndexNumber: Int? = null,
    val Type: String = "", // "Movie" | "Series" | "Season" | "Episode" | "Video" | "BoxSet"
    val Overview: String? = null,
    val ProductionYear: Int? = null,
    val PremiereDate: String? = null,
    val CommunityRating: Double? = null,
    val OfficialRating: String? = null,
    val RunTimeTicks: Long? = null,
    val Genres: List<String> = emptyList(),
    val ImageTags: Map<String, String> = emptyMap(),
    val BackdropImageTags: List<String> = emptyList(),
    val SeriesPrimaryImageTag: String? = null,
    val ParentPrimaryImageTag: String? = null,
    val ParentBackdropItemId: String? = null,
    val ParentBackdropImageTags: List<String> = emptyList(),
    val ParentThumbItemId: String? = null,
    val ParentThumbImageTag: String? = null,
    val ParentLogoItemId: String? = null,
    val ParentLogoImageTag: String? = null,
    val UserData: JfUserData? = null,
    val MediaSources: List<JfMediaSource> = emptyList(),
    val Chapters: List<JfChapter> = emptyList(),
    val ProviderIds: Map<String, String> = emptyMap()
) {
    val runtimeMs: Long
        get() = JellyfinIds.ticksToMs(RunTimeTicks ?: MediaSources.firstOrNull()?.RunTimeTicks)

    val resumePositionMs: Long
        get() = UserData?.playbackPositionMs ?: 0L

    fun episodeCodeLabel(): String {
        val s = ParentIndexNumber
        val e = IndexNumber
        return when {
            s != null && e != null -> String.format(Locale.US, "S%02dE%02d", s, e)
            e != null -> String.format(Locale.US, "E%02d", e)
            else -> ""
        }
    }

    /**
     * Scaled Primary image URL (`Primary?maxHeight=360&quality=80`).
     */
    fun primaryImageUrl(baseUrl: String, maxHeight: Int = 360, quality: Int = 80): String {
        val clean = baseUrl.trimEnd('/')
        if (clean.isEmpty() || Id.isBlank()) return ""
        val ownTag = ImageTags["Primary"]
        if (!ownTag.isNullOrBlank()) {
            return "$clean/Items/${urlEnc(Id)}/Images/Primary?maxHeight=$maxHeight&quality=$quality&tag=${urlEnc(ownTag)}"
        }
        val sId = SeriesId?.takeIf { it.isNotBlank() }
        if (sId != null) {
            val sTag = SeriesPrimaryImageTag ?: ParentPrimaryImageTag
            val tagSuffix = if (!sTag.isNullOrBlank()) "&tag=${urlEnc(sTag)}" else ""
            return "$clean/Items/${urlEnc(sId)}/Images/Primary?maxHeight=$maxHeight&quality=$quality$tagSuffix"
        }
        return ""
    }

    /**
     * 2:3 Poster URL preferred for vertical poster rails (uses parent Series poster for Episodes).
     */
    fun posterImageUrl(baseUrl: String, maxHeight: Int = 360, quality: Int = 80): String {
        val clean = baseUrl.trimEnd('/')
        if (clean.isEmpty()) return ""
        if (Type.equals("Episode", ignoreCase = true) && !SeriesId.isNullOrBlank()) {
            val sTag = SeriesPrimaryImageTag ?: ParentPrimaryImageTag
            val tagSuffix = if (!sTag.isNullOrBlank()) "&tag=${urlEnc(sTag)}" else ""
            return "$clean/Items/${urlEnc(SeriesId)}/Images/Primary?maxHeight=$maxHeight&quality=$quality$tagSuffix"
        }
        return primaryImageUrl(baseUrl, maxHeight, quality)
    }

    /**
     * Scaled Backdrop image URL (`Backdrop/0?maxWidth=960&quality=72`).
     */
    fun backdropImageUrl(baseUrl: String, maxWidth: Int = 960, quality: Int = 72): String {
        val clean = baseUrl.trimEnd('/')
        if (clean.isEmpty()) return ""
        if (BackdropImageTags.isNotEmpty() && Id.isNotBlank()) {
            return "$clean/Items/${urlEnc(Id)}/Images/Backdrop/0?maxWidth=$maxWidth&quality=$quality&tag=${urlEnc(BackdropImageTags[0])}"
        }
        if (!ParentBackdropItemId.isNullOrBlank() && ParentBackdropImageTags.isNotEmpty()) {
            return "$clean/Items/${urlEnc(ParentBackdropItemId)}/Images/Backdrop/0?maxWidth=$maxWidth&quality=$quality&tag=${urlEnc(ParentBackdropImageTags[0])}"
        }
        if (!SeriesId.isNullOrBlank()) {
            return "$clean/Items/${urlEnc(SeriesId)}/Images/Backdrop/0?maxWidth=$maxWidth&quality=$quality"
        }
        return ""
    }

    /**
     * Scaled 16:9 Thumb / Episode-still URL (`Thumb?maxWidth=420&quality=78`).
     */
    fun thumbImageUrl(baseUrl: String, maxWidth: Int = 420, quality: Int = 78): String {
        val clean = baseUrl.trimEnd('/')
        if (clean.isEmpty()) return ""
        if (Type.equals("Episode", ignoreCase = true) && Id.isNotBlank()) {
            val epPrimary = ImageTags["Primary"]
            if (!epPrimary.isNullOrBlank()) {
                return "$clean/Items/${urlEnc(Id)}/Images/Primary?maxWidth=$maxWidth&quality=$quality&tag=${urlEnc(epPrimary)}"
            }
        }
        val ownThumb = ImageTags["Thumb"]
        if (!ownThumb.isNullOrBlank() && Id.isNotBlank()) {
            return "$clean/Items/${urlEnc(Id)}/Images/Thumb?maxWidth=$maxWidth&quality=$quality&tag=${urlEnc(ownThumb)}"
        }
        if (!ParentThumbItemId.isNullOrBlank() && !ParentThumbImageTag.isNullOrBlank()) {
            return "$clean/Items/${urlEnc(ParentThumbItemId)}/Images/Thumb?maxWidth=$maxWidth&quality=$quality&tag=${urlEnc(ParentThumbImageTag)}"
        }
        return backdropImageUrl(baseUrl, maxWidth, quality).ifBlank {
            primaryImageUrl(baseUrl, 360, quality)
        }
    }

    fun logoImageUrl(baseUrl: String, maxWidth: Int = 500, quality: Int = 80): String {
        val clean = baseUrl.trimEnd('/')
        if (clean.isEmpty()) return ""
        val ownLogo = ImageTags["Logo"]
        if (!ownLogo.isNullOrBlank() && Id.isNotBlank()) {
            return "$clean/Items/${urlEnc(Id)}/Images/Logo?maxWidth=$maxWidth&quality=$quality&tag=${urlEnc(ownLogo)}"
        }
        if (!ParentLogoItemId.isNullOrBlank() && !ParentLogoImageTag.isNullOrBlank()) {
            return "$clean/Items/${urlEnc(ParentLogoItemId)}/Images/Logo?maxWidth=$maxWidth&quality=$quality&tag=${urlEnc(ParentLogoImageTag)}"
        }
        return ""
    }

    fun toMetaItem(
        serverId: String,
        baseUrl: String,
        episodes: List<VideoEpisode> = emptyList()
    ): MetaItem {
        val isEpisode = Type.equals("Episode", ignoreCase = true)
        val isSeries = Type.equals("Series", ignoreCase = true) || isEpisode
        val brootId = JellyfinIds.buildId(serverId, Id)

        val displayTitle = if (isEpisode && !SeriesName.isNullOrBlank()) {
            val epCode = episodeCodeLabel()
            if (epCode.isNotBlank()) "$SeriesName · $epCode" else "$SeriesName · $Name"
        } else {
            Name
        }

        val releaseParts = buildList {
            if (isEpisode && Name.isNotBlank()) add(Name)
            ProductionYear?.let { add(it.toString()) }
            OfficialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        val ratingStr = CommunityRating
            ?.takeIf { it > 0.0 }
            ?.let { String.format(Locale.US, "%.1f", it) }
            .orEmpty()

        val runtimeLabel = runtimeMs
            .takeIf { it >= 60_000L }
            ?.let { "${(it / 60_000L).toInt()} min" }
            .orEmpty()

        return MetaItem(
            id = brootId,
            type = if (isSeries) "series" else "movie",
            name = displayTitle,
            poster = posterImageUrl(baseUrl).ifBlank { primaryImageUrl(baseUrl) },
            background = backdropImageUrl(baseUrl).ifBlank { thumbImageUrl(baseUrl, maxWidth = 960, quality = 75) },
            logo = logoImageUrl(baseUrl),
            description = Overview.orEmpty(),
            releaseInfo = releaseParts.joinToString(" · "),
            imdbRating = ratingStr,
            runtime = runtimeLabel,
            genres = Genres,
            videos = episodes,
            defaultVideoId = if (isEpisode) brootId else ""
        )
    }

    fun toVideoEpisode(serverId: String, baseUrl: String): VideoEpisode {
        return VideoEpisode(
            id = JellyfinIds.buildId(serverId, Id),
            title = Name.ifBlank { "Episode ${IndexNumber ?: 1}" },
            season = ParentIndexNumber ?: 1,
            episode = IndexNumber ?: 0,
            overview = Overview.orEmpty(),
            thumbnail = thumbImageUrl(baseUrl),
            released = PremiereDate?.take(10) ?: ProductionYear?.toString().orEmpty()
        )
    }

    fun toWatchHistoryEntry(serverId: String, baseUrl: String): WatchHistoryEntry {
        val isEpisode = Type.equals("Episode", ignoreCase = true)
        val itemCompositeId = if (isEpisode && !SeriesId.isNullOrBlank()) {
            JellyfinIds.buildId(serverId, SeriesId)
        } else {
            JellyfinIds.buildId(serverId, Id)
        }
        val videoCompositeId = JellyfinIds.buildId(serverId, Id)
        val epLabel = if (isEpisode) {
            listOf(episodeCodeLabel(), Name).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            ""
        }
        return WatchHistoryEntry(
            itemId = itemCompositeId,
            videoId = videoCompositeId,
            type = if (isEpisode) "series" else "movie",
            name = if (isEpisode && !SeriesName.isNullOrBlank()) SeriesName else Name,
            episodeTitle = epLabel,
            poster = posterImageUrl(baseUrl).ifBlank { primaryImageUrl(baseUrl) },
            background = backdropImageUrl(baseUrl).ifBlank { thumbImageUrl(baseUrl) },
            timeOffsetMs = resumePositionMs,
            durationMs = runtimeMs
        )
    }
}

@Serializable
data class JfAudioStream(
    val index: Int,
    val language: String = "und",
    val label: String,
    val codec: String = "",
    val channels: Int = 2,
    val isDefault: Boolean = false
)

@Serializable
data class JfSubtitleStream(
    val index: Int,
    val language: String = "und",
    val label: String,
    val codec: String = "",
    val deliveryMethod: String = "Embed", // "Embed" | "External" | "Encode" | "Hls"
    val externalUrl: String? = null,
    val mimeType: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isImageBased: Boolean = false
) {
    fun toExternalSubtitleOrNull(): ExternalSubtitle? {
        val url = externalUrl?.takeIf { it.isNotBlank() } ?: return null
        return ExternalSubtitle(
            id = "jfsub:$index",
            url = url,
            lang = label.ifBlank { language },
            addonName = "Jellyfin"
        )
    }
}

@Serializable
data class JfPlaybackSession(
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val playMethod: String, // "DirectPlay" | "DirectStream" | "Transcode"
    val playSessionId: String,
    val mediaSourceId: String,
    val audioTracks: List<JfAudioStream> = emptyList(),
    val subtitleTracks: List<JfSubtitleStream> = emptyList(),
    val chapters: List<JfChapter> = emptyList(),
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    val resumePositionMs: Long = 0L,
    val runtimeMs: Long = 0L,
    val ignoreSsl: Boolean = false,
    val selectedAudioStreamIndex: Int? = null,
    val selectedSubtitleStreamIndex: Int? = null,
    val videoSummaryLabel: String = ""
)

private fun urlEnc(raw: String): String =
    URLEncoder.encode(raw, "UTF-8")
