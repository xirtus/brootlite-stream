package app.brootlite.tv.creators

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import app.brootlite.tv.BrootLiteApp
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Media3 [HlsPlaylistParserFactory] wrapper that eliminates Twitch server-side stitched ads
 * (`twitch-stitched-ad` / `stitched-ad` `#EXT-X-DATERANGE` & `,Amazon` `#EXTINF` segments):
 *
 * 1. When a live media playlist contains stitched ad markers and a backup ad-free Twitch HLS URL
 *    (`embed` / `thunderdome` playerType) is available, transparently swaps to the clean backup
 *    media playlist while preserving sequence continuity!
 * 2. Strips stitched ad `#EXT-X-DATERANGE` metadata and `,Amazon` ad segments (adjusting
 *    `#EXT-X-MEDIA-SEQUENCE` for any stripped leading segments so ExoPlayer never stalls).
 */
@UnstableApi
class TwitchAdFreeHlsParserFactory(
    private val channelLogin: String? = null,
    private val initialBackupMasterUrl: String? = null,
    private val delegateFactory: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory()
) : HlsPlaylistParserFactory {

    @Volatile
    private var cachedBackupMediaPlaylistUrl: String? = null

    @Volatile
    private var lastBackupLookupFailureMs: Long = 0L

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        return wrapParser(delegateFactory.createPlaylistParser())
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> {
        return wrapParser(
            delegateFactory.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
        )
    }

    private fun wrapParser(
        delegate: ParsingLoadable.Parser<HlsPlaylist>
    ): ParsingLoadable.Parser<HlsPlaylist> = ParsingLoadable.Parser { uri, inputStream ->
        val rawText = inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (rawText.contains("#EXT-X-STREAM-INF")) {
            return@Parser delegate.parse(uri, ByteArrayInputStream(rawText.toByteArray(Charsets.UTF_8)))
        }

        val cleanedText = if (containsTwitchStitchedAds(rawText)) {
            val swapped = tryFetchCleanBackupMediaPlaylist()
            if (swapped != null) {
                alignBackupMediaSequence(originalM3u8 = rawText, backupM3u8 = swapped)
            } else {
                stripStitchedAdsFromMediaPlaylist(rawText)
            }
        } else {
            rawText
        }

        delegate.parse(
            uri,
            ByteArrayInputStream(cleanedText.toByteArray(Charsets.UTF_8))
        )
    }

    private fun containsTwitchStitchedAds(m3u8: String): Boolean {
        return m3u8.contains("twitch-stitched-ad", ignoreCase = true) ||
            m3u8.contains("stitched-ad-", ignoreCase = true) ||
            m3u8.contains("X-TV-TWITCH-AD-", ignoreCase = true) ||
            AD_EXTINF_REGEX.containsMatchIn(m3u8)
    }

    /**
     * Fetches a clean ad-free media playlist from `initialBackupMasterUrl` or by minting a fresh
     * `embed` / `Picture-by-Picture` token for [channelLogin].
     */
    private fun tryFetchCleanBackupMediaPlaylist(): String? {
        val now = System.currentTimeMillis()
        if (now - lastBackupLookupFailureMs < BACKUP_RETRY_COOLDOWN_MS && cachedBackupMediaPlaylistUrl == null) {
            return null
        }

        // 1. Try cached backup media playlist URL first (1 HTTP round-trip during active ad break)
        cachedBackupMediaPlaylistUrl?.let { mediaUrl ->
            val playlist = fetchText(mediaUrl)
            if (playlist != null && playlist.contains("#EXTM3U") && !containsTwitchStitchedAds(playlist)) {
                return playlist
            }
            cachedBackupMediaPlaylistUrl = null
        }

        // 2. Resolve backup master playlist -> pick <=720p variant (most reliable ad-exempt tier on Twitch)
        val masterUrlsToTry = ArrayList<String>(3)
        if (!initialBackupMasterUrl.isNullOrBlank()) {
            masterUrlsToTry.add(initialBackupMasterUrl)
        }
        val cleanLogin = channelLogin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cleanLogin != null) {
            for (pType in BACKUP_PLAYER_TYPES) {
                val tok = TwitchClient.fetchPlaybackAccessToken(
                    login = cleanLogin,
                    vodId = "",
                    isLive = true,
                    playerType = pType
                ) ?: continue
                masterUrlsToTry.add(TwitchClient.buildUsherLiveMasterUrl(cleanLogin, tok.first, tok.second))
            }
        }

        for (masterUrl in masterUrlsToTry) {
            val variants = TwitchClient.fetchAndParseHlsVariants(masterUrl)
                .filter { (it.height ?: 0) > 0 }
            if (variants.isEmpty()) continue

            val candidate = variants.firstOrNull { (it.height ?: 0) in 480..720 }
                ?: variants.firstOrNull { (it.height ?: 0) <= 1080 }
                ?: variants.last()

            val mediaText = fetchText(candidate.url)
            if (mediaText != null && mediaText.contains("#EXTM3U") && !containsTwitchStitchedAds(mediaText)) {
                cachedBackupMediaPlaylistUrl = candidate.url
                return mediaText
            }
        }

        lastBackupLookupFailureMs = now
        return null
    }

    /**
     * Preserves `#EXT-X-MEDIA-SEQUENCE` from [originalM3u8] and injects `#EXT-X-DISCONTINUITY`
     * when swapping to [backupM3u8] so ExoPlayer transitions across decoder params seamlessly!
     */
    private fun alignBackupMediaSequence(originalM3u8: String, backupM3u8: String): String {
        val origSeq = MEDIA_SEQ_REGEX.find(originalM3u8)?.groupValues?.getOrNull(1)
            ?: return backupM3u8

        var rewritten = backupM3u8.replace(MEDIA_SEQ_REGEX, "#EXT-X-MEDIA-SEQUENCE:$origSeq")
        if (!rewritten.contains("#EXT-X-DISCONTINUITY")) {
            val firstExtInfIdx = rewritten.indexOf("#EXTINF:")
            if (firstExtInfIdx > 0) {
                rewritten = rewritten.substring(0, firstExtInfIdx) +
                    "#EXT-X-DISCONTINUITY\n" +
                    rewritten.substring(firstExtInfIdx)
            }
        }
        return rewritten
    }

    /**
     * Strips stitched ad `#EXT-X-DATERANGE` tags and `,Amazon` ad segments while keeping at least
     * one live/fallback segment so Media3 never throws an empty-playlist parse error.
     */
    internal fun stripStitchedAdsFromMediaPlaylist(rawM3u8: String): String {
        val lines = rawM3u8.lines()
        val headerLines = ArrayList<String>()

        data class SegmentBlock(
            val metaLines: List<String>,
            val extInfLine: String,
            val uriLine: String,
            val isAd: Boolean
        )

        val blocks = ArrayList<SegmentBlock>()
        val trailingLines = ArrayList<String>()
        val pendingSegMeta = ArrayList<String>()

        var seenFirstSegment = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Drop Twitch ad DATERANGE / SCTE35 tags everywhere
            if (trimmed.startsWith("#EXT-X-DATERANGE:") &&
                (trimmed.contains("stitched-ad", ignoreCase = true) ||
                    trimmed.contains("X-TV-TWITCH-AD", ignoreCase = true))
            ) {
                i++
                continue
            }
            if (trimmed.startsWith("#EXT-X-SCTE35-")) {
                i++
                continue
            }

            if (trimmed.startsWith("#EXTINF:")) {
                seenFirstSegment = true
                // Find the segment URI on the next non-empty, non-comment line
                var uriIdx = i + 1
                while (uriIdx < lines.size && (lines[uriIdx].isBlank() || lines[uriIdx].trim().startsWith("#"))) {
                    if (lines[uriIdx].isNotBlank()) pendingSegMeta.add(lines[uriIdx])
                    uriIdx++
                }
                if (uriIdx < lines.size) {
                    val uriLine = lines[uriIdx]
                    val isAd = AD_EXTINF_LINE_REGEX.containsMatchIn(trimmed) ||
                        uriLine.contains("stitched-ad", ignoreCase = true)
                    blocks.add(
                        SegmentBlock(
                            metaLines = pendingSegMeta.toList(),
                            extInfLine = line,
                            uriLine = uriLine,
                            isAd = isAd
                        )
                    )
                    pendingSegMeta.clear()
                    i = uriIdx + 1
                    continue
                }
            }

            if (!seenFirstSegment) {
                if (trimmed.startsWith("#EXT-X-PROGRAM-DATE-TIME:") ||
                    trimmed.startsWith("#EXT-X-DISCONTINUITY") ||
                    trimmed.startsWith("#EXT-X-CUE-")
                ) {
                    pendingSegMeta.add(line)
                } else {
                    headerLines.add(line)
                }
            } else {
                if (trimmed.startsWith("#EXT-X-PROGRAM-DATE-TIME:") ||
                    trimmed.startsWith("#EXT-X-DISCONTINUITY") ||
                    trimmed.startsWith("#EXT-X-CUE-")
                ) {
                    pendingSegMeta.add(line)
                } else {
                    trailingLines.add(line)
                }
            }
            i++
        }

        val cleanBlocks = blocks.filter { !it.isAd }
        // If 100% of segments in the window were ads (mid-roll full replacement & backup unreachable),
        // promote clean `#EXT-X-TWITCH-PREFETCH:` low-latency live segments if present, or keep only
        // the final segment so ExoPlayer stays alive until live segments resume in ~2s!
        val prefetchUrls = trailingLines
            .map { it.trim() }
            .filter { it.startsWith("#EXT-X-TWITCH-PREFETCH:") }
            .map { it.removePrefix("#EXT-X-TWITCH-PREFETCH:").trim() }
            .filter { it.startsWith("http") }

        val strippedLeadingCount = blocks.takeWhile { it.isAd }.size
        val updatedHeader = headerLines.map { h ->
            val m = MEDIA_SEQ_REGEX.find(h)
            if (m != null && cleanBlocks.isNotEmpty() && strippedLeadingCount > 0) {
                val origSeq = m.groupValues[1].toLongOrNull() ?: 0L
                "#EXT-X-MEDIA-SEQUENCE:${origSeq + strippedLeadingCount}"
            } else {
                h
            }
        }

        return buildString(rawM3u8.length) {
            for (h in updatedHeader) append(h).append('\n')

            when {
                cleanBlocks.isNotEmpty() -> {
                    var prevWasStripped = strippedLeadingCount > 0
                    for (blk in blocks) {
                        if (blk.isAd) {
                            prevWasStripped = true
                            continue
                        }
                        if (prevWasStripped) {
                            append("#EXT-X-DISCONTINUITY\n")
                            prevWasStripped = false
                        }
                        for (m in blk.metaLines) {
                            if (!m.trim().startsWith("#EXT-X-CUE-")) append(m).append('\n')
                        }
                        append(blk.extInfLine).append('\n')
                        append(blk.uriLine).append('\n')
                    }
                }
                prefetchUrls.isNotEmpty() -> {
                    append("#EXT-X-DISCONTINUITY\n")
                    for (pUrl in prefetchUrls) {
                        append("#EXTINF:2.000,live\n")
                        append(pUrl).append('\n')
                    }
                }
                blocks.isNotEmpty() -> {
                    val tail = blocks.last()
                    append("#EXT-X-DISCONTINUITY\n")
                    append(tail.extInfLine).append('\n')
                    append(tail.uriLine).append('\n')
                }
            }

            for (t in trailingLines) {
                if (!t.trim().startsWith("#EXT-X-CUE-")) {
                    append(t).append('\n')
                }
            }
        }
    }

    private fun fetchText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
            )
            .header("Origin", "https://player.twitch.tv")
            .get()
            .build()
        return runCatching {
            BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    companion object {
        private const val BACKUP_RETRY_COOLDOWN_MS = 8_000L
        private val BACKUP_PLAYER_TYPES = listOf("embed", "Picture-by-Picture", "thunderdome")
        private val AD_EXTINF_REGEX = Regex("""#EXTINF:[\d.]+,\s*Amazon""", RegexOption.IGNORE_CASE)
        private val AD_EXTINF_LINE_REGEX = Regex(""",\s*Amazon""", RegexOption.IGNORE_CASE)
        private val MEDIA_SEQ_REGEX = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""")
    }
}
