package app.brootlite.tv.data

import kotlinx.serialization.Serializable

@Serializable
data class InstalledAddon(
    val transportUrl: String,
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val logo: String = "",
    val types: List<String> = emptyList(),
    val resources: List<AddonResourceSpec> = emptyList(),
    val catalogs: List<AddonCatalogDef> = emptyList(),
    val idPrefixes: List<String> = emptyList()
) {
    val baseUrl: String
        get() = transportUrl.substringBeforeLast("/manifest.json").trimEnd('/')

    fun supportsResource(resName: String, contentType: String, itemId: String): Boolean =
        resolveResourceType(resName, contentType, itemId) != null

    /**
     * Resolves the effective Stremio resource `type` to query on this addon (handling cross-type
     * IMDb `tt...` items from Anime/JAV/Hentai/Other catalogs so HTTP Mirror & Torrent addons with
     * `types = ["movie", "series"]` are NEVER skipped).
     */
    fun resolveResourceType(resName: String, contentType: String, itemId: String): String? {
        if (resources.isEmpty()) return null
        val looksLikeEpisode = itemId.count { it == ':' } >= 2 ||
            (itemId.startsWith("tt") && itemId.contains(':')) ||
            contentType == "series"
        val fallbackImdbType = if (itemId.startsWith("tt")) {
            if (itemId.contains(':')) "series" else "movie"
        } else null

        for (res in resources) {
            if (res.name != resName) continue

            val effectivePrefixes = if (res.idPrefixes.isNotEmpty()) res.idPrefixes else idPrefixes
            val prefixMatch = effectivePrefixes.isEmpty() ||
                effectivePrefixes.any { pref ->
                    val normalizedPref = if (pref.equals("tt:", ignoreCase = true)) "tt" else pref
                    normalizedPref.isEmpty() || itemId.startsWith(normalizedPref)
                }
            if (!prefixMatch) continue

            val candidateTypes = if (res.types.isNotEmpty()) res.types else types
            if (candidateTypes.isEmpty() || contentType in candidateTypes) {
                return contentType
            }
            if (fallbackImdbType != null && fallbackImdbType in candidateTypes) {
                return fallbackImdbType
            }
            // Community / Anime / JAV / Hentai / Mirror addons often declare stream types=["movie","series"]
            // while catalog/meta type is "anime", "jav", "hentai", "porn", or "other".
            if (looksLikeEpisode && "series" in candidateTypes) {
                return "series"
            }
            if (!looksLikeEpisode && "movie" in candidateTypes) {
                return "movie"
            }
            return candidateTypes.firstOrNull() ?: contentType
        }
        return null
    }
}

@Serializable
data class AddonResourceSpec(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList()
)

@Serializable
data class AddonCatalogDef(
    val type: String,
    val id: String,
    val name: String = "",
    val isSearchSupported: Boolean = false,
    val isSearchRequired: Boolean = false,
    val isBrowseSupported: Boolean = true,
    val isSkipSupported: Boolean = false,
    val isGenreSupported: Boolean = false,
    val isGenreRequired: Boolean = false,
    val genreOptions: List<String> = emptyList(),
    val extraParamName: String = "genre",
    val defaultExtraMap: Map<String, String> = emptyMap()
)

@Serializable
data class MetaItem(
    val id: String,
    val type: String = "movie",
    val name: String,
    val poster: String = "",
    val background: String = "",
    val logo: String = "",
    val description: String = "",
    val releaseInfo: String = "",
    val imdbRating: String = "",
    val runtime: String = "",
    val genres: List<String> = emptyList(),
    val videos: List<VideoEpisode> = emptyList(),
    val defaultVideoId: String = "",
    val embeddedStreams: List<StreamCandidate> = emptyList()
)

@Serializable
data class VideoEpisode(
    val id: String,
    val title: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val overview: String = "",
    val thumbnail: String = "",
    val released: String = "",
    val streams: List<StreamCandidate> = emptyList()
)

@Serializable
data class StreamCandidate(
    val addonName: String = "",
    val name: String = "",
    val title: String = "",
    val description: String = "",
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val sources: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val ignoreSsl: Boolean = false,
    val bingeGroup: String = "",
    val filename: String = "",
    val seeders: Int = 0,
    val leechers: Int = 0,
    val sizeLabel: String = ""
) {
    val isTorrent: Boolean
        get() = !infoHash.isNullOrBlank() || (url?.startsWith("magnet:", ignoreCase = true) == true)

    val isDirectHttp: Boolean
        get() = !isTorrent && !url.isNullOrBlank()

    val isExternalMirror: Boolean
        get() = !isTorrent && url.isNullOrBlank() && !externalUrl.isNullOrBlank()

    val isMirrorOrDirect: Boolean
        get() = !isTorrent && (!url.isNullOrBlank() || !externalUrl.isNullOrBlank())

    val extractedIndexer: String
        get() {
            val combined = "$title\n$description"
            val m = INDEXER_TAG_REGEX.find(combined)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return m.trim('·', '•', '-', '|', ' ').take(24)
        }

    val extractedSize: String
        get() {
            if (sizeLabel.isNotBlank()) return sizeLabel.trim()
            val combined = "$title\n$description"
            return SIZE_TAG_REGEX.find(combined)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        }

    val extractedResolution: String
        get() {
            val combined = "$name $title $description $filename"
            return when {
                Regex("""(?i)\b(?:2160p|4k|uhd)\b""").containsMatchIn(combined) -> "4K"
                Regex("""(?i)\b(?:1440p|2k)\b""").containsMatchIn(combined) -> "1440p"
                Regex("""(?i)\b(?:1080p|fhd)\b""").containsMatchIn(combined) -> "1080p"
                Regex("""(?i)\b720p\b""").containsMatchIn(combined) -> "720p"
                Regex("""(?i)\b(?:576p|480p)\b""").containsMatchIn(combined) -> "480p"
                Regex("""(?i)\b(?:360p|threesixtyp)\b""").containsMatchIn(combined) -> "360p"
                else -> ""
            }
        }

    val extractedCodecTags: List<String>
        get() {
            val combined = "$name $title $description $filename"
            return buildList {
                if (Regex("""(?i)\b(?:hevc|[xh][ ._-]?265)\b""").containsMatchIn(combined)) add("HEVC/H.265")
                if (Regex("""(?i)\bav1\b""").containsMatchIn(combined)) add("AV1")
                if (Regex("""(?i)\b(?:avc|[xh][ ._-]?264)\b""").containsMatchIn(combined)) add("H.264")
                if (Regex("""(?i)\bvp9\b""").containsMatchIn(combined)) add("VP9")
                if (Regex("""(?i)\b(?:10[ ._-]?bit|hi10p)\b""").containsMatchIn(combined)) add("10-bit")
                if (Regex("""(?i)\b(?:dv|dovi|dolby[ ._-]?vision)\b""").containsMatchIn(combined)) add("DV")
                else if (Regex("""(?i)\bhdr(?:10\+?)?\b""").containsMatchIn(combined)) add("HDR")
            }
        }

    val isPreferredModernCodec: Boolean
        get() {
            val codecs = extractedCodecTags
            return "HEVC/H.265" in codecs || "AV1" in codecs
        }

    val telemetryHeader: String
        get() {
            val parts = ArrayList<String>(6)
            when {
                isTorrent -> {
                    val dot = when {
                        seeders >= 5 -> "🟢"
                        seeders >= 1 -> "🟡"
                        else -> "🔴"
                    }
                    val peerSuffix = if (leechers > 0) " ($leechers leech)" else ""
                    parts.add("$dot $seeders ${if (seeders == 1) "Seed" else "Seeds"}$peerSuffix")
                }
                isExternalMirror -> parts.add("↗ Web Mirror")
                isDirectHttp -> parts.add("⚡ Direct Stream")
            }
            val sz = extractedSize
            if (sz.isNotBlank()) parts.add("💾 $sz")
            val res = extractedResolution
            if (res.isNotBlank()) parts.add(res)
            val codecs = extractedCodecTags
            if (codecs.isNotEmpty()) parts.add(codecs.joinToString(" · "))
            val idx = extractedIndexer.ifBlank {
                addonName.takeIf { it.isNotBlank() && !name.contains(it, ignoreCase = true) }.orEmpty()
            }
            if (idx.isNotBlank()) parts.add("⚙️ $idx")
            return parts.joinToString("   ·   ")
        }

    val displayBadge: String
        get() {
            val raw = name.replace("\n", " · ").trim()
            val idxTag = extractedIndexer
            val codecHighlight = extractedCodecTags.firstOrNull { it == "HEVC/H.265" || it == "AV1" }.orEmpty()
            val lines = ArrayList<String>(3)
            if (raw.isNotBlank()) lines.add(raw)
            if (codecHighlight.isNotBlank() && !raw.contains(codecHighlight, ignoreCase = true)) {
                lines.add(codecHighlight)
            }
            if (idxTag.isNotBlank() &&
                !raw.contains(idxTag, ignoreCase = true) &&
                !addonName.equals(idxTag, ignoreCase = true)
            ) {
                lines.add(idxTag)
            }
            val joined = lines.joinToString("\n")
            return when {
                isExternalMirror && joined.isNotBlank() -> "$joined · MIRROR ↗"
                isExternalMirror -> "${addonName.ifBlank { "WEB" }} · MIRROR ↗"
                isDirectHttp && joined.isNotBlank() -> joined
                isDirectHttp -> "${addonName.ifBlank { "HTTP" }} · DIRECT"
                joined.isNotBlank() -> joined
                isTorrent -> "P2P SWARM"
                else -> addonName.ifBlank { "STREAM" }
            }
        }

    val displayDetails: String
        get() {
            val blockLines = ArrayList<String>(4)
            if (title.isNotBlank()) blockLines.add(title.trim())
            if (description.isNotBlank() && !description.trim().equals(title.trim(), ignoreCase = true)) {
                blockLines.add(description.trim())
            }
            val cleanFn = filename.trim()
            if (cleanFn.isNotBlank() &&
                blockLines.none { it.contains(cleanFn, ignoreCase = true) }
            ) {
                blockLines.add("File: $cleanFn")
            }
            val primary = blockLines.joinToString("\n")
            return when {
                primary.isNotBlank() -> primary
                isExternalMirror -> "External Mirror (${externalUrl?.substringAfter("://")?.substringBefore('/') ?: "Web"})"
                isDirectHttp -> "Direct HTTP / Mirror Stream (${addonName.ifBlank { "Instant Play" }})"
                else -> cleanFn.ifBlank { addonName }
            }
        }

    fun toMagnetUri(): String? {
        if (url?.startsWith("magnet:", ignoreCase = true) == true) {
            return appendTrackers(url)
        }
        val hash = infoHash?.trim()?.lowercase() ?: return null
        val dn = java.net.URLEncoder.encode(filename.ifBlank { title.lineSequence().firstOrNull() ?: hash }, "UTF-8")
        val base = "magnet:?xt=urn:btih:$hash&dn=$dn"
        return appendTrackers(base)
    }

    private fun appendTrackers(baseMagnet: String): String {
        val sb = StringBuilder(baseMagnet)
        val trackerSet = LinkedHashSet<String>()
        for (src in sources) {
            if (src.startsWith("tracker:", ignoreCase = true)) {
                trackerSet.add(src.substring("tracker:".length).trim())
            } else if (src.startsWith("udp://", true) || src.startsWith("http://", true) || src.startsWith("https://", true)) {
                trackerSet.add(src.trim())
            }
        }
        trackerSet.addAll(DEFAULT_PUBLIC_TRACKERS)
        for (tr in trackerSet) {
            if (tr.isNotBlank()) {
                val enc = java.net.URLEncoder.encode(tr, "UTF-8")
                if (!sb.contains(enc)) {
                    sb.append("&tr=").append(enc)
                }
            }
        }
        return sb.toString()
    }

    companion object {
        private val INDEXER_TAG_REGEX = Regex("""(?:⚙️|⚙|📡|🔗)\s*([A-Za-z0-9_.+-]{2,24})""")
        private val SIZE_TAG_REGEX = Regex("""(?:💾\s*)?(\d+(?:\.\d+)?\s*(?:GiB|MiB|GB|MB|TiB|TB))""", RegexOption.IGNORE_CASE)
        val DEFAULT_PUBLIC_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://explodie.org:6969/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://p4p.arenabg.com:1337/announce",
            "udp://tracker1.bt.moack.co.kr:80/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
            "udp://retracker.lanta.me:2710/announce",
            "udp://opentracker.io:6969/announce",
            "https://tracker.tamersunion.org:443/announce",
            "https://tracker.gbitt.info:443/announce",
            "https://tracker.renfei.net:443/announce",
            "http://bt1.archive.org:6969/announce"
        )
    }
}

@Serializable
data class ExternalSubtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String = "OpenSubtitles"
)

@Serializable
data class WatchHistoryEntry(
    val itemId: String,
    val videoId: String,
    val type: String,
    val name: String,
    val episodeTitle: String = "",
    val poster: String = "",
    val background: String = "",
    val timeOffsetMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val directStreamUrl: String? = null,
    val directHeaders: Map<String, String> = emptyMap(),
    val infoHash: String? = null,
    val fileIdx: Int? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (timeOffsetMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

@Serializable
data class FavoriteEntry(
    val id: String,
    val type: String, // "movie", "series", "anime", "other", "kodi", "torrent", etc.
    val name: String,
    val poster: String = "",
    val subtitle: String = "",
    val pluginUrl: String? = null,
    val magnetUri: String? = null,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class KodiMenuOption(
    val label: String = "",
    val cleanLabel: String = "",
    val url: String = ""
)

@Serializable
data class KodiDirectoryItem(
    val label: String = "",
    val cleanLabel: String = "",
    val url: String = "",
    val isFolder: Boolean = false,
    val isPlayable: Boolean = false,
    val thumb: String = "",
    val fanart: String = "",
    val plot: String = "",
    val duration: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "",
    val subtitles: List<String> = emptyList(),
    val contextMenu: List<KodiMenuOption> = emptyList()
)

@Serializable
data class KodiBridgeResponse(
    val ok: Boolean = false,
    val addonId: String = "",
    val category: String = "",
    val items: List<KodiDirectoryItem> = emptyList(),
    val resolved: KodiDirectoryItem? = null,
    val redirectUrl: String? = null,
    val refresh: Boolean = false,
    val error: String? = null
)

@Serializable
data class KodiInstalledAddon(
    val id: String,
    val name: String,
    val version: String = "",
    val summary: String = "",
    val icon: String = "",
    val pluginUrl: String
)

enum class SwarmFilter(val label: String) {
    ALL("All Swarm"),
    PUBLIC_DOMAIN("Public Domain"),
    RSS("Custom RSS"),
    TORZNAB("Torznab")
}

enum class DiscoverSortMode(val label: String, val shortLabel: String) {
    DEFAULT("Default (Catalog Order)", "Default"),
    RATING_DESC("IMDb Rating ↓", "Rating ↓"),
    YEAR_NEWEST("Year (Newest)", "Newest"),
    YEAR_OLDEST("Year (Oldest)", "Oldest"),
    ALPHA_ASC("Title A–Z", "A–Z")
}

@Serializable
data class MultipassProfile(
    val serverUrl: String = "",
    val handle: String = "",
    val pairToken: String = "",
    val deviceId: String = "",
    val scope: String = "media_sync:readwrite",
    val vaultRevision: Int = 0,
    val vaultMode: String = "e2ee-aes-256-gcm",
    val lastSyncedAtMs: Long = 0L
)

data class TorrentEngineConfig(
    val preBufferMb: Int = 16,
    val slidingWindowMb: Int = 28,
    val maxConnections: Int = 120,
    val torznabUrl: String = "",
    val torznabApiKey: String = ""
)
