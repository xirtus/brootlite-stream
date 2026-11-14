package app.brootlite.tv.youtube

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Explicit auth & configuration struct passed into YouTube API calls so the `youtube` package
 * has zero hard compile-time dependency on `AppPrefs` during Wave 1.
 * Wave 2 wires `AppPrefs` into [YouTubeAuthContext] and [onTokenRefreshed].
 */
data class YouTubeAuthContext(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtMs: Long = 0L,
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** Brand Account `pageId` (`onBehalfOfUser` / `X-Goog-Pageid`) when a Brand channel is selected. */
    val pageId: String? = null,
    val accountId: String? = null,
    val visitorData: String? = null,
    val language: String = "en",
    val region: String = "US",
    val deArrowEnabled: Boolean = false,
    val onTokenRefreshed: (suspend (YouTubeOAuthTokens) -> Unit)? = null
) {
    val isSignedIn: Boolean
        get() = !refreshToken.isNullOrBlank() || !accessToken.isNullOrBlank()

    /** Alias matching YouTube InnerTube `context.user.onBehalfOfUser`. */
    val onBehalfOfUser: String?
        get() = pageId?.takeIf { it.isNotBlank() }

    /** Stable session scope key (never exposes raw tokens). */
    fun sessionKey(): String = "${accountId.orEmpty()}|${pageId.orEmpty()}|$language|$region|$isSignedIn"

    override fun toString(): String =
        "YouTubeAuthContext(signedIn=$isSignedIn, hasBrandPageId=${!pageId.isNullOrBlank()}, lang=$language, region=$region)"
}

@Serializable
data class YouTubeDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String = "https://www.youtube.com/activate",
    val intervalSec: Int = 5,
    val expiresInSec: Int = 1800,
    val clientId: String = "",
    val clientSecret: String = ""
) {
    override fun toString(): String =
        "YouTubeDeviceCode(userCode=$userCode, verificationUrl=$verificationUrl, intervalSec=$intervalSec, expiresInSec=$expiresInSec)"
}

@Serializable
data class YouTubeOAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long,
    val clientId: String,
    val clientSecret: String,
    val expiresAtMs: Long = System.currentTimeMillis() + (expiresInSec * 1000L)
) {
    override fun toString(): String =
        "YouTubeOAuthTokens(expiresInSec=$expiresInSec, expiresAtMs=$expiresAtMs, hasRefresh=${refreshToken.isNotBlank()})"
}

sealed class YouTubeDevicePollResult {
    data object Pending : YouTubeDevicePollResult()
    data class SlowDown(val extraDelaySec: Int = 5) : YouTubeDevicePollResult()
    data class Authorized(val tokens: YouTubeOAuthTokens) : YouTubeDevicePollResult()
    data class Error(val errorCode: String, val message: String) : YouTubeDevicePollResult()
}

@Serializable
data class YouTubeAccountItem(
    val accountId: String,
    val accountName: String,
    val channelHandle: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    /** Non-null for Brand Accounts; passed as `context.user.onBehalfOfUser` and `X-Goog-Pageid`. */
    val pageId: String? = null,
    val isSelected: Boolean = false
) {
    val onBehalfOfUser: String?
        get() = pageId?.takeIf { it.isNotBlank() }

    val isBrandAccount: Boolean
        get() = !pageId.isNullOrBlank()
}

enum class YouTubeSearchFilter(val params: String?) {
    ALL(null),
    VIDEOS("EgIQAQ=="),
    CHANNELS("EgIQAg=="),
    PLAYLISTS("EgIQAw=="),
    LIVE("EgJAAQ==")
}

@Serializable
data class YouTubeVideoCard(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String? = null,
    val channelAvatarUrl: String? = null,
    val thumbnailUrl: String,
    val durationText: String? = null,
    val durationSec: Long? = null,
    val viewCountText: String? = null,
    val publishedText: String? = null,
    val resumePercent: Int? = null,
    val startSeconds: Long? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val playlistId: String? = null
)

@Serializable
data class YouTubeChannelCard(
    val channelId: String,
    val title: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val subscriberCountText: String? = null,
    val videoCountText: String? = null,
    val isSubscribed: Boolean = false,
    val hasNewContent: Boolean = false
)

@Serializable
data class YouTubePlaylistCard(
    val playlistId: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val videoCountText: String? = null
)

@Serializable
data class YouTubeShelf(
    val title: String?,
    val videos: List<YouTubeVideoCard>,
    val channels: List<YouTubeChannelCard> = emptyList(),
    val playlists: List<YouTubePlaylistCard> = emptyList(),
    val continuation: String? = null
)

@Serializable
data class YouTubeFeedPage(
    val items: List<YouTubeVideoCard>,
    val shelves: List<YouTubeShelf> = emptyList(),
    val channels: List<YouTubeChannelCard> = emptyList(),
    val playlists: List<YouTubePlaylistCard> = emptyList(),
    val continuation: String? = null,
    val visitorData: String? = null
)

@Serializable
data class YouTubeChannelTab(
    val title: String,
    val browseId: String,
    val params: String? = null,
    val isSelected: Boolean = false
)

@Serializable
data class YouTubeChannelFeed(
    val channelId: String,
    val title: String,
    val description: String? = null,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val subscriberCountText: String? = null,
    val videoCountText: String? = null,
    val isSubscribed: Boolean? = null,
    val subscribeParams: String? = null,
    val videos: List<YouTubeVideoCard> = emptyList(),
    val liveVideos: List<YouTubeVideoCard> = emptyList(),
    val shorts: List<YouTubeVideoCard> = emptyList(),
    val tabs: List<YouTubeChannelTab> = emptyList(),
    val continuation: String? = null
)

@Serializable
data class ByteRange(
    val start: Long,
    val end: Long
) {
    override fun toString(): String = "$start-$end"
}

@Serializable
data class YouTubeQualityVariant(
    val label: String,
    val itag: Int,
    val videoUrl: String,
    val mimeType: String,
    val codecs: String? = null,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Long,
    val contentLength: Long? = null,
    val initRange: ByteRange? = null,
    val indexRange: ByteRange? = null,
    val approxDurationMs: Long? = null,
    val isHdr: Boolean = false
)

@Serializable
data class YouTubeAudioTrack(
    val itag: Int,
    val audioUrl: String,
    val mimeType: String,
    val codecs: String? = null,
    val bitrate: Long,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val languageCode: String? = null,
    val displayName: String? = null,
    val contentLength: Long? = null,
    val initRange: ByteRange? = null,
    val indexRange: ByteRange? = null,
    val approxDurationMs: Long? = null,
    val isDefault: Boolean = false
)

@Serializable
data class YouTubeCaptionTrack(
    val languageCode: String,
    val name: String,
    val vttUrl: String,
    val srtUrl: String,
    val baseUrl: String,
    val isAutoGenerated: Boolean = false,
    val isTranslatable: Boolean = false,
    val isTranslated: Boolean = false
)

@Serializable
data class ResolvedYouTubeStream(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String? = null,
    val durationSec: Long = 0L,
    val viewCount: Long? = null,
    val shortDescription: String? = null,
    val thumbnailUrl: String? = null,
    val isLive: Boolean = false,
    val clientUsed: String,
    val userAgent: String,
    val headers: Map<String, String> = emptyMap(),
    /** Live stream HLS `.m3u8` manifest URL (or VisionOS HLS fallback). */
    val hlsManifestUrl: String? = null,
    /** Selected default adaptive video stream (defaults to <=1080p60 for Android TV). */
    val adaptiveVideoUrl: String? = null,
    val mimeType: String? = null,
    val codecs: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrate: Long? = null,
    val contentLength: Long? = null,
    val initRange: ByteRange? = null,
    val indexRange: ByteRange? = null,
    /** Best adaptive Opus/AAC audio stream. */
    val adaptiveAudioUrl: String? = null,
    val adaptiveAudioMimeType: String? = null,
    val adaptiveAudioCodecs: String? = null,
    val adaptiveAudioBitrate: Long? = null,
    val adaptiveAudioContentLength: Long? = null,
    val adaptiveAudioInitRange: ByteRange? = null,
    val adaptiveAudioIndexRange: ByteRange? = null,
    val audioTracks: List<YouTubeAudioTrack> = emptyList(),
    /** Progressive 360p/720p muxed audio+video fallback URL. */
    val muxedUrl: String? = null,
    val muxedHeight: Int? = null,
    /** Selectable quality variants: `2160p 4K`, `1440p`, `1080p60`, `720p60`, `480p`, `360p`. */
    val qualityVariants: List<YouTubeQualityVariant> = emptyList(),
    /** Closed-caption subtitle tracks with `.vtt` and `.srt` URLs. */
    val captionTracks: List<YouTubeCaptionTrack> = emptyList(),
    /** Two-way Google Watch History & red-bar resume tracking endpoints. */
    val playbackTrackingUrl: String? = null,
    val watchtimeTrackingUrl: String? = null,
    val cpn: String = ""
) {
    @Transient
    val hasAdaptiveSplit: Boolean = !adaptiveVideoUrl.isNullOrBlank() && !adaptiveAudioUrl.isNullOrBlank()

    /**
     * Picks the best [YouTubeQualityVariant] matching [targetLabelOrHeight] (e.g. `"2160p 4K"`, `"1080p60"`, or `maxHeight = 1080`).
     */
    fun selectVariant(maxHeight: Int = 1080, preferLabel: String? = null): YouTubeQualityVariant? {
        if (qualityVariants.isEmpty()) return null
        if (!preferLabel.isNullOrBlank()) {
            qualityVariants.firstOrNull { it.label.equals(preferLabel, ignoreCase = true) }?.let { return it }
        }
        return qualityVariants.firstOrNull { it.height <= maxHeight } ?: qualityVariants.last()
    }

    /**
     * Builds an in-memory MPEG-DASH MPD XML manifest with exact `<Initialization>` and `<SegmentBase indexRange>`
     * byte ranges so Media3 `DashMediaSource` can seek instantaneously with zero rebuffering!
     */
    fun buildDashMpdXml(
        selectedVariant: YouTubeQualityVariant? = null,
        selectedAudio: YouTubeAudioTrack? = null
    ): String? {
        val vList = if (selectedVariant != null) listOf(selectedVariant) else qualityVariants
        val validVideos = vList.filter { it.initRange != null && it.indexRange != null && it.contentLength != null }
        val aTrack = selectedAudio ?: audioTracks.firstOrNull { it.initRange != null && it.indexRange != null }
        if (validVideos.isEmpty() || aTrack == null || aTrack.initRange == null || aTrack.indexRange == null) {
            return null
        }
        val durSec = durationSec.takeIf { it > 0 }
            ?: validVideos.first().approxDurationMs?.let { (it / 1000L).coerceAtLeast(1L) }
            ?: 1L

        fun xmlEscape(raw: String): String = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        val videoGrouped = validVideos.groupBy { it.mimeType.substringBefore(";").trim() }
        val sb = StringBuilder(2048)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT${durSec}S">""").append('\n')
        sb.append("""  <Period duration="PT${durSec}S">""").append('\n')

        var adaptId = 0
        for ((vMime, reps) in videoGrouped) {
            sb.append("""    <AdaptationSet id="${adaptId++}" mimeType="${xmlEscape(vMime)}" subsegmentAlignment="true">""").append('\n')
            for (v in reps) {
                val codecAttr = v.codecs?.let { """ codecs="${xmlEscape(it)}"""" } ?: ""
                sb.append("""      <Representation id="v_${v.itag}_${v.height}" bandwidth="${v.bitrate}" width="${v.width}" height="${v.height}" frameRate="${v.fps}"$codecAttr>""").append('\n')
                sb.append("""        <BaseURL>${xmlEscape(v.videoUrl)}</BaseURL>""").append('\n')
                sb.append("""        <SegmentBase indexRange="${v.indexRange}">""").append('\n')
                sb.append("""          <Initialization range="${v.initRange}"/>""").append('\n')
                sb.append("""        </SegmentBase>""").append('\n')
                sb.append("""      </Representation>""").append('\n')
            }
            sb.append("""    </AdaptationSet>""").append('\n')
        }

        val aMime = aTrack.mimeType.substringBefore(";").trim()
        val aCodec = aTrack.codecs?.let { """ codecs="${xmlEscape(it)}"""" } ?: ""
        val aLang = aTrack.languageCode?.let { """ lang="${xmlEscape(it)}"""" } ?: ""
        sb.append("""    <AdaptationSet id="${adaptId}" mimeType="${xmlEscape(aMime)}" subsegmentAlignment="true"$aLang>""").append('\n')
        sb.append("""      <Representation id="a_${aTrack.itag}" bandwidth="${aTrack.bitrate}"$aCodec>""").append('\n')
        sb.append("""        <BaseURL>${xmlEscape(aTrack.audioUrl)}</BaseURL>""").append('\n')
        sb.append("""        <SegmentBase indexRange="${aTrack.indexRange}">""").append('\n')
        sb.append("""          <Initialization range="${aTrack.initRange}"/>""").append('\n')
        sb.append("""        </SegmentBase>""").append('\n')
        sb.append("""      </Representation>""").append('\n')
        sb.append("""    </AdaptationSet>""").append('\n')

        sb.append("""  </Period>""").append('\n')
        sb.append("""</MPD>""")
        return sb.toString()
    }
}

@Serializable
data class SponsorSegment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
    val actionType: String = "skip"
)

@Serializable
data class RydVotes(
    val likes: Long,
    val dislikes: Long,
    val rating: Double,
    val viewCount: Long
)

@Serializable
internal data class RydApiResponse(
    val id: String? = null,
    val likes: Long = 0L,
    val dislikes: Long = 0L,
    val rating: Double = 0.0,
    val viewCount: Long = 0L,
    val deleted: Boolean = false
)

@Serializable
internal data class SponsorBlockItemDto(
    @SerialName("UUID") val uuid: String = "",
    val category: String = "sponsor",
    val actionType: String = "skip",
    val segment: List<Double> = emptyList()
)
