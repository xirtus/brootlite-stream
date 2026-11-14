package app.brootlite.tv.data

import android.content.Context
import app.brootlite.tv.BrootLiteApp
import app.brootlite.tv.jellyfin.JellyfinApiClient
import app.brootlite.tv.jellyfin.JellyfinEndpointRouter
import app.brootlite.tv.jellyfin.JellyfinIds
import app.brootlite.tv.jellyfin.JfItem
import app.brootlite.tv.jellyfin.JfItemsEnvelope
import app.brootlite.tv.jellyfin.JfPlaybackSession
import app.brootlite.tv.youtube.YouTubeApiClient
import app.brootlite.tv.youtube.YouTubeAuthContext
import app.brootlite.tv.youtube.YouTubeSearchFilter
import app.brootlite.tv.youtube.YouTubeVideoCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

@Serializable
data class ResolvedBingePlayback(
    val title: String,
    val subtitle: String,
    val sourceBadge: String,
    val itemId: String,
    val videoId: String,
    val contentType: String,
    val poster: String = "",
    val background: String = "",
    val directUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val ignoreSsl: Boolean = false,
    val magnetUri: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val trackers: List<String> = emptyList(),
    val bingeGroup: String = "",
    val streamHint: String = "",
    val addonName: String = "",
    val isMirror: Boolean = false,
    val labeledSubtitles: List<ExternalSubtitle> = emptyList(),
    val seriesEpisodes: List<VideoEpisode> = emptyList(),
    val dashMpdXml: String? = null,
    val fallbackDirectUrl: String? = null,
    val youtubeVideoId: String? = null,
    val ytPlaybackTrackingUrl: String? = null,
    val ytWatchtimeTrackingUrl: String? = null,
    val ytCpn: String = "",
    val jellyfinServerId: String = "",
    val jellyfinItemId: String = "",
    val jellyfinSession: JfPlaybackSession? = null
)

object StreamSimilarityMatcher {

    private val RESOLUTION_TIERS = listOf(480, 720, 1080, 1440, 2160)

    private val BRACKET_GROUP_REGEX = Regex("""\[([A-Za-z0-9._ -]{2,28})]""")
    private val TRAILING_GROUP_REGEX = Regex(
        """-([A-Za-z0-9]{2,18})(?:\.(?:mkv|mp4|avi|webm|ts|m4v))?\s*$""",
        RegexOption.MULTILINE
    )
    private val HEX_CRC32_REGEX = Regex("""^[0-9a-fA-F]{8}$""")
    private val EPISODE_TOKEN_REGEX = Regex(
        """^(?:s\d{1,2}e\d{1,3}|s\d{1,2}|e\d{1,3}|ep\d{1,3}|\d{1,2}x\d{1,3}|\d+)$"""
    )
    private val WORD_SPLIT_REGEX = Regex("""[^a-z0-9]+""")

    private val NON_GROUP_BRACKET_TAGS = setOf(
        "2160p", "1440p", "1080p", "720p", "480p", "4k", "uhd", "fhd", "hd", "sd",
        "bd", "web", "web-dl", "webrip", "bluray", "remux", "hdr", "dv", "hevc",
        "x265", "x264", "av1", "10bit", "dual", "multi", "eng", "sub", "dub",
        "rd+", "rd", "ad+", "ad", "pm+", "pm", "tb+", "dl", "p2p"
    )

    private val STOP_WORDS = setOf(
        "mkv", "mp4", "avi", "webm", "the", "and", "for", "with", "from",
        "seed", "seeds", "seeders", "leechers", "peers", "size", "http", "https",
        "direct", "mirror", "stream", "p2p", "swarm", "episode", "season"
    )

    private data class ParsedStreamFeatures(
        val releaseGroup: String,
        val height: Int?,
        val videoCodec: String?,
        val featureTokens: Set<String>,
        val wordTokens: Set<String>
    )

    fun pickBestMatchingStream(
        candidates: List<StreamCandidate>,
        preferredBingeGroup: String = "",
        previousStreamHint: String = "",
        previousAddonName: String = "",
        previousWasMirror: Boolean = false,
        preferredMaxHeight: Int = 1080
    ): StreamCandidate? {
        if (candidates.isEmpty()) return null

        // Separate playable candidates (HTTP/Debrid direct URLs, mirrors, or live >0-seed torrents)
        // from dead 0-seed torrents so autoplay never picks a dead swarm when playable streams exist.
        val directlyPlayable = candidates.filter { c ->
            c.directHttpUrlOrNull() != null || c.seeders > 0
        }
        val playablePool = when {
            directlyPlayable.isNotEmpty() -> directlyPlayable
            else -> {
                val mirrorOrLive = candidates.filter { c ->
                    c.isMirrorOrDirect || c.directHttpUrlOrNull() != null || c.seeders > 0
                }
                if (mirrorOrLive.isNotEmpty()) mirrorOrLive else candidates
            }
        }

        val prevFeatures = parseStreamFeatures(previousStreamHint)
        val targetHeight = prevFeatures.height ?: preferredMaxHeight.takeIf { it > 0 } ?: 1080
        val cleanBingeGroup = preferredBingeGroup.trim()
        val cleanPrevAddon = previousAddonName.trim()

        var bestCandidate: StreamCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (c in playablePool) {
            val candText = buildCandidateFeatureString(c)
            val candFeatures = parseStreamFeatures(candText)
            val candHasHttp = c.directHttpUrlOrNull() != null
            val candIsMirrorOrHttp = c.isMirrorOrDirect || candHasHttp

            var score = 0.0

            // 1. Exact non-blank Stremio bingeGroup match (+130.0)
            if (cleanBingeGroup.isNotEmpty() && c.bingeGroup.isNotBlank() && c.bingeGroup.trim() == cleanBingeGroup) {
                score += 130.0
            }

            // 2. Same addonName (+42.0)
            if (cleanPrevAddon.isNotEmpty() && c.addonName.isNotBlank() &&
                c.addonName.trim().equals(cleanPrevAddon, ignoreCase = true)
            ) {
                score += 42.0
            }

            // 3. Same delivery mode (mirror/HTTP vs P2P swarm) (+32.0)
            if (candIsMirrorOrHttp == previousWasMirror) {
                score += 32.0
            }

            // 4. Matching release group tag (+70.0)
            if (prevFeatures.releaseGroup.isNotEmpty() &&
                candFeatures.releaseGroup.isNotEmpty() &&
                candFeatures.releaseGroup == prevFeatures.releaseGroup
            ) {
                score += 70.0
            }

            // 5. Resolution match against previous resolution or preferredMaxHeight (+48.0 / +18.0 / -20.0)
            val candHeight = candFeatures.height
            if (candHeight != null) {
                if (candHeight == targetHeight) {
                    score += 48.0
                } else {
                    val stepDist = resolutionStepDistance(candHeight, targetHeight)
                    if (stepDist == 1) {
                        score += 18.0
                    }
                }
                val prevHeightCap = prevFeatures.height ?: preferredMaxHeight
                if (prevHeightCap <= 1080 && candHeight > preferredMaxHeight) {
                    score -= 20.0
                }
            }

            // 6. Matching video codec (+20.0)
            if (prevFeatures.videoCodec != null && candFeatures.videoCodec == prevFeatures.videoCodec) {
                score += 20.0
            }

            // 7. Jaccard / overlap bonus on audio/language/HDR tokens + alphanumeric filename tokens (up to +40.0)
            val featureSim = jaccardSimilarity(prevFeatures.featureTokens, candFeatures.featureTokens)
            val wordSim = jaccardSimilarity(prevFeatures.wordTokens, candFeatures.wordTokens)
            val tokenBonus = when {
                prevFeatures.featureTokens.isNotEmpty() && prevFeatures.wordTokens.isNotEmpty() ->
                    (featureSim * 24.0) + (wordSim * 16.0)
                prevFeatures.featureTokens.isNotEmpty() ->
                    featureSim * 40.0
                prevFeatures.wordTokens.isNotEmpty() ->
                    wordSim * 32.0
                else -> 0.0
            }.coerceIn(0.0, 40.0)
            score += tokenBonus

            // 8. Healthy seeder / direct mirror reliability bonus
            score += if (candIsMirrorOrHttp) {
                38.0
            } else {
                (ln((c.seeders.coerceAtLeast(0) + 1).toDouble()) * 16.0).coerceAtMost(80.0)
            }

            // Prefer directly playable HTTP streams over external browser-only links
            if (c.isExternalMirror && !candHasHttp) {
                score -= 25.0
            }

            if (score > bestScore || (score == bestScore && c.seeders > (bestCandidate?.seeders ?: -1))) {
                bestScore = score
                bestCandidate = c
            }
        }

        return bestCandidate
    }

    suspend fun resolveNextEpisodePlayback(
        context: Context,
        itemId: String,
        contentType: String,
        seriesTitle: String,
        poster: String,
        background: String,
        nextEpisode: VideoEpisode,
        allEpisodes: List<VideoEpisode>,
        preferredBingeGroup: String = "",
        previousStreamHint: String = "",
        previousAddonName: String = "",
        previousWasMirror: Boolean = false
    ): ResolvedBingePlayback? = withContext(Dispatchers.IO) {
        // 1. Native Multi-Server Jellyfin Series Binge
        if (JellyfinIds.isJellyfinId(itemId) || JellyfinIds.isJellyfinId(nextEpisode.id)) {
            val serverId = JellyfinIds.parseId(nextEpisode.id)?.first
                ?: JellyfinIds.parseId(itemId)?.first
                ?: return@withContext null
            val profile = AppPrefs.getJellyfinServer(context, serverId)
                ?.takeIf { it.isAuthenticated }
                ?: return@withContext null
            val forceTranscode = preferredBingeGroup.endsWith(":transcode", ignoreCase = true)
            val session = JellyfinApiClient.resolvePlaybackSession(
                context = context,
                profile = profile,
                itemId = nextEpisode.id,
                startPositionMs = 0L,
                forceTranscode = forceTranscode
            ) ?: return@withContext null

            val epCode = formatEpisodeCode(nextEpisode.season, nextEpisode.episode)
            val subText = listOfNotNull(
                epCode.takeIf { it.isNotBlank() },
                nextEpisode.title.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            val compositeEpId = JellyfinIds.buildId(profile.serverId, JellyfinIds.rawItemId(nextEpisode.id))

            return@withContext ResolvedBingePlayback(
                title = seriesTitle,
                subtitle = subText,
                sourceBadge = "${session.playMethod} · ${profile.name}",
                itemId = itemId,
                videoId = compositeEpId,
                contentType = contentType.ifBlank { "series" },
                poster = poster,
                background = nextEpisode.thumbnail.ifBlank { background },
                directUrl = session.streamUrl,
                headers = session.headers,
                ignoreSsl = session.ignoreSsl,
                bingeGroup = preferredBingeGroup.ifBlank { "jf:${profile.serverId}" },
                streamHint = session.videoSummaryLabel,
                addonName = profile.name,
                isMirror = true,
                labeledSubtitles = session.subtitleTracks.mapNotNull { it.toExternalSubtitleOrNull() },
                seriesEpisodes = allEpisodes,
                jellyfinServerId = profile.serverId,
                jellyfinItemId = JellyfinIds.rawItemId(nextEpisode.id),
                jellyfinSession = session
            )
        }

        // 2. Stremio / HTTP Mirror / P2P Torrent Series Binge
        val addons = AppPrefs.getInstalledAddons(context)
        val effectiveType = contentType.ifBlank { "series" }

        return@withContext coroutineScope {
            val subsDeferred = async {
                withTimeoutOrNull(2_500L) {
                    StremioApiClient.fetchSubtitles(addons, effectiveType, nextEpisode.id)
                }.orEmpty()
            }

            val fetched = StremioApiClient.fetchStreamsProgressive(
                addons = addons,
                type = effectiveType,
                videoId = nextEpisode.id
            ) { _, _, _ -> }
            var combined = StremioApiClient.dedupeAndSortStreams(nextEpisode.streams + fetched)

            val hasPlayable = combined.any { it.directHttpUrlOrNull() != null || it.seeders > 0 }
            if (!hasPlayable && seriesTitle.isNotBlank()) {
                val epQuery = if (nextEpisode.season > 0) {
                    String.format(Locale.US, "%s S%02dE%02d", seriesTitle, nextEpisode.season, nextEpisode.episode)
                } else if (nextEpisode.episode > 0) {
                    String.format(Locale.US, "%s %02d", seriesTitle, nextEpisode.episode)
                } else {
                    "$seriesTitle ${nextEpisode.title}".trim()
                }
                val swarm = NativeTorrentSearch.searchAll(context, epQuery, "public_domain")
                if (swarm.isNotEmpty()) {
                    combined = StremioApiClient.dedupeAndSortStreams(combined + swarm)
                }
            }

            // Filter to streams that PlayerActivity can directly open via HTTP or Magnet/InfoHash
            val playerOpenable = combined.filter {
                it.directHttpUrlOrNull() != null || it.toMagnetUri() != null || !it.infoHash.isNullOrBlank()
            }
            val best = pickBestMatchingStream(
                candidates = playerOpenable.ifEmpty { combined },
                preferredBingeGroup = preferredBingeGroup,
                previousStreamHint = previousStreamHint,
                previousAddonName = previousAddonName,
                previousWasMirror = previousWasMirror
            ) ?: return@coroutineScope null

            val subs = subsDeferred.await()
            val epCode = formatEpisodeCode(nextEpisode.season, nextEpisode.episode)
            val subText = listOfNotNull(
                epCode.takeIf { it.isNotBlank() },
                nextEpisode.title.takeIf { it.isNotBlank() }
            ).joinToString(" · ")

            buildStremioResolvedPlayback(
                title = seriesTitle,
                subtitle = subText,
                itemId = itemId,
                videoId = nextEpisode.id,
                contentType = effectiveType,
                poster = poster,
                background = nextEpisode.thumbnail.ifBlank { background },
                best = best,
                subs = subs,
                allEpisodes = allEpisodes
            )
        }
    }

    internal fun buildStremioResolvedPlayback(
        title: String,
        subtitle: String,
        itemId: String,
        videoId: String,
        contentType: String,
        poster: String,
        background: String,
        best: StreamCandidate,
        subs: List<ExternalSubtitle>,
        allEpisodes: List<VideoEpisode>
    ): ResolvedBingePlayback? {
        val httpUrl = best.directHttpUrlOrNull()
        val magnet = if (httpUrl == null) best.toMagnetUri() else null
        val hash = if (httpUrl == null) best.infoHash else null
        if (httpUrl == null && magnet == null && hash.isNullOrBlank()) {
            return null
        }

        val parsed = parseStreamFeatures(buildCandidateFeatureString(best))
        val resTag = when (parsed.height) {
            2160 -> "4K"
            1440 -> "1440p"
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            else -> null
        }
        val addonTag = best.addonName.ifBlank {
            if (httpUrl != null) "Mirror" else "P2P Swarm"
        }
        val sourceBadge = if (httpUrl != null) {
            listOfNotNull(resTag, "Direct Mirror", addonTag).joinToString(" · ")
        } else {
            val seedsTag = "${best.seeders} seeds"
            listOfNotNull(resTag, seedsTag, addonTag).joinToString(" · ")
        }

        val hintSummary = listOf(best.name, best.title, best.description, best.filename)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace('\n', ' ')
            .trim()

        return ResolvedBingePlayback(
            title = title,
            subtitle = subtitle,
            sourceBadge = sourceBadge,
            itemId = itemId,
            videoId = videoId,
            contentType = contentType,
            poster = poster,
            background = background,
            directUrl = httpUrl,
            headers = best.headers,
            ignoreSsl = best.ignoreSsl,
            magnetUri = magnet,
            infoHash = hash,
            fileIdx = if (httpUrl == null) best.fileIdx else null,
            trackers = if (httpUrl == null) {
                (best.sources + StreamCandidate.DEFAULT_PUBLIC_TRACKERS).distinct()
            } else {
                emptyList()
            },
            bingeGroup = best.bingeGroup,
            streamHint = hintSummary,
            addonName = best.addonName,
            isMirror = httpUrl != null,
            labeledSubtitles = subs,
            seriesEpisodes = allEpisodes
        )
    }

    internal fun parseHeightFromHint(raw: String): Int? = parseStreamFeatures(raw).height

    private fun buildCandidateFeatureString(c: StreamCandidate): String =
        "${c.name}\n${c.title}\n${c.description}\n${c.filename}"

    private fun parseStreamFeatures(raw: String): ParsedStreamFeatures {
        if (raw.isBlank()) {
            return ParsedStreamFeatures(
                releaseGroup = "",
                height = null,
                videoCodec = null,
                featureTokens = emptySet(),
                wordTokens = emptySet()
            )
        }
        val lower = raw.lowercase(Locale.US)

        // 1. Release group: leading [SubsPlease] / [Erai-raws] or trailing -NTb / -FLUX / -FENiX
        var group = ""
        for (m in BRACKET_GROUP_REGEX.findAll(raw)) {
            val token = m.groupValues[1].trim().lowercase(Locale.US)
            if (token.length >= 2 &&
                token !in NON_GROUP_BRACKET_TAGS &&
                !HEX_CRC32_REGEX.matches(token) &&
                !token.all { it.isDigit() }
            ) {
                group = token
                break
            }
        }
        if (group.isEmpty()) {
            for (line in raw.lineSequence()) {
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty()) continue
                val m = TRAILING_GROUP_REGEX.find(trimmedLine)
                if (m != null) {
                    val cand = m.groupValues[1].trim().lowercase(Locale.US)
                    if (cand !in NON_GROUP_BRACKET_TAGS &&
                        !cand.all { it.isDigit() } &&
                        !EPISODE_TOKEN_REGEX.matches(cand)
                    ) {
                        group = cand
                        break
                    }
                }
            }
        }

        // 2. Resolution height
        val height = when {
            Regex("""\b(?:2160p|4k|uhd)\b""").containsMatchIn(lower) -> 2160
            Regex("""\b(?:1440p|2k|qhd)\b""").containsMatchIn(lower) -> 1440
            Regex("""\b(?:1080p|1080i|fhd)\b""").containsMatchIn(lower) -> 1080
            Regex("""\b(?:720p|hd)\b""").containsMatchIn(lower) -> 720
            Regex("""\b(?:480p|576p|sd)\b""").containsMatchIn(lower) -> 480
            else -> null
        }

        // 3. Video codec
        val videoCodec = when {
            Regex("""\b(?:hevc|x265|h\.?265)\b""").containsMatchIn(lower) -> "hevc"
            Regex("""\bav1\b""").containsMatchIn(lower) -> "av1"
            Regex("""\b(?:avc|x264|h\.?264)\b""").containsMatchIn(lower) -> "avc"
            else -> null
        }

        // 4. Audio / Language / Dub-Sub / HDR tokens
        val features = LinkedHashSet<String>()
        if (Regex("""\bdual[\s._-]*audio\b""").containsMatchIn(lower)) features.add("dual_audio")
        if (Regex("""\b(?:dub|dubbed)\b""").containsMatchIn(lower)) features.add("dub")
        if (Regex("""\b(?:multi|multisub|multi-sub|multi-subs)\b""").containsMatchIn(lower)) features.add("multi")
        if (Regex("""\b(?:eng|english)\b""").containsMatchIn(lower)) features.add("eng")
        if (Regex("""\b(?:jpn|japanese|jap)\b""").containsMatchIn(lower)) features.add("jpn")
        if (Regex("""\b(?:eac3|e-ac-3|ddp|dd\+|ddp5)\b""").containsMatchIn(lower)) features.add("eac3")
        if (Regex("""\b(?:dts|dts-hd|dtshd)\b""").containsMatchIn(lower)) features.add("dts")
        if (Regex("""\b(?:truehd|atmos)\b""").containsMatchIn(lower)) features.add("truehd")
        if (Regex("""\bflac\b""").containsMatchIn(lower)) features.add("flac")
        if (Regex("""\baac\b""").containsMatchIn(lower)) features.add("aac")
        if (Regex("""\bopus\b""").containsMatchIn(lower)) features.add("opus")
        if (Regex("""\b(?:hdr|hdr10|hdr10\+)\b""").containsMatchIn(lower)) features.add("hdr")
        if (Regex("""\b(?:dv|dovi|dolby[\s._-]*vision)\b""").containsMatchIn(lower)) features.add("dv")
        if (Regex("""\b(?:10bit|10-bit|hi10p)\b""").containsMatchIn(lower)) features.add("10bit")
        if (Regex("""\bremux\b""").containsMatchIn(lower)) features.add("remux")
        if (Regex("""\b(?:bluray|blu-ray|bdrip)\b""").containsMatchIn(lower)) features.add("bluray")
        if (Regex("""\b(?:web-dl|webdl|webrip)\b""").containsMatchIn(lower)) features.add("web")

        // 5. General alphanumeric filename tokens (excluding episode numbers)
        val words = LinkedHashSet<String>()
        for (part in WORD_SPLIT_REGEX.split(lower)) {
            if (part.length < 3) continue
            if (part in STOP_WORDS) continue
            if (EPISODE_TOKEN_REGEX.matches(part)) continue
            words.add(part)
        }

        return ParsedStreamFeatures(
            releaseGroup = group,
            height = height,
            videoCodec = videoCodec,
            featureTokens = features,
            wordTokens = words
        )
    }

    private fun resolutionStepDistance(h1: Int, h2: Int): Int {
        val idx1 = RESOLUTION_TIERS.indexOf(h1)
        val idx2 = RESOLUTION_TIERS.indexOf(h2)
        if (idx1 < 0 || idx2 < 0) return 99
        return abs(idx1 - idx2)
    }

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var intersection = 0
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        for (el in smaller) {
            if (el in larger) intersection++
        }
        if (intersection == 0) return 0.0
        val union = a.size + b.size - intersection
        return if (union > 0) intersection.toDouble() / union.toDouble() else 0.0
    }
}

object RecommendationEngine {

    suspend fun fetchRecommendations(
        context: Context,
        itemId: String,
        contentType: String,
        title: String,
        limit: Int = 8
    ): List<MetaItem> = withContext(Dispatchers.IO) {
        val cappedLimit = limit.coerceIn(1, 24)
        val excludedIds = AppPrefs.getWatchHistory(context)
            .mapTo(HashSet()) { it.itemId }
            .apply {
                if (itemId.isNotBlank()) add(itemId)
            }

        // Case A — Jellyfin native /Items/{rawItemId}/Similar + same-genre server fallback
        if (JellyfinIds.isJellyfinId(itemId)) {
            return@withContext fetchJellyfinRecommendations(
                context = context,
                itemId = itemId,
                contentType = contentType,
                excludedIds = excludedIds,
                limit = cappedLimit
            )
        }

        // Case B — YouTube related / search videos
        if (itemId.startsWith("yt:") || contentType.equals("youtube", ignoreCase = true)) {
            return@withContext fetchYouTubeRecommendations(
                context = context,
                itemId = itemId,
                title = title,
                excludedIds = excludedIds,
                limit = cappedLimit
            )
        }

        // Case C — Stremio Movies / Series Finales / Anime
        fetchStremioRecommendations(
            context = context,
            itemId = itemId,
            contentType = contentType,
            title = title,
            excludedIds = excludedIds,
            limit = cappedLimit
        )
    }

    suspend fun resolveRecommendedPlayback(
        context: Context,
        item: MetaItem,
        preferredResolutionHint: String = "1080p",
        preferMirror: Boolean = false
    ): ResolvedBingePlayback? = withContext(Dispatchers.IO) {
        // 1. Jellyfin Movie or Series (S01E01)
        if (JellyfinIds.isJellyfinId(item.id)) {
            val (serverId, _) = JellyfinIds.parseId(item.id) ?: return@withContext null
            val profile = AppPrefs.getJellyfinServer(context, serverId)
                ?.takeIf { it.isAuthenticated }
                ?: return@withContext null
            val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
            val detail = JellyfinApiClient.getItemDetail(context, profile, item.id)
                ?: return@withContext null

            val isSeries = detail.Type.equals("Series", ignoreCase = true) ||
                item.type.equals("series", ignoreCase = true)
            if (isSeries) {
                val jfEps = JellyfinApiClient.getEpisodes(context, profile, detail.Id)
                val videoEps = jfEps.map { it.toVideoEpisode(profile.serverId, baseUrl) }
                val firstEp = videoEps.firstOrNull { it.season == 1 && it.episode == 1 }
                    ?: videoEps.firstOrNull { it.season > 0 }
                    ?: videoEps.firstOrNull()
                    ?: return@withContext null
                val session = JellyfinApiClient.resolvePlaybackSession(
                    context = context,
                    profile = profile,
                    itemId = firstEp.id,
                    startPositionMs = 0L,
                    forceTranscode = false
                ) ?: return@withContext null

                val epCode = formatEpisodeCode(firstEp.season, firstEp.episode)
                val subText = listOfNotNull(
                    epCode.takeIf { it.isNotBlank() },
                    firstEp.title.takeIf { it.isNotBlank() }
                ).joinToString(" · ")

                return@withContext ResolvedBingePlayback(
                    title = detail.Name.ifBlank { item.name },
                    subtitle = subText,
                    sourceBadge = "${session.playMethod} · ${profile.name}",
                    itemId = item.id,
                    videoId = firstEp.id,
                    contentType = "series",
                    poster = item.poster,
                    background = firstEp.thumbnail.ifBlank { item.background },
                    directUrl = session.streamUrl,
                    headers = session.headers,
                    ignoreSsl = session.ignoreSsl,
                    bingeGroup = "jf:${profile.serverId}",
                    streamHint = session.videoSummaryLabel,
                    addonName = profile.name,
                    isMirror = true,
                    labeledSubtitles = session.subtitleTracks.mapNotNull { it.toExternalSubtitleOrNull() },
                    seriesEpisodes = videoEps,
                    jellyfinServerId = profile.serverId,
                    jellyfinItemId = JellyfinIds.rawItemId(firstEp.id),
                    jellyfinSession = session
                )
            } else {
                val session = JellyfinApiClient.resolvePlaybackSession(
                    context = context,
                    profile = profile,
                    itemId = detail.Id,
                    startPositionMs = 0L,
                    forceTranscode = false
                ) ?: return@withContext null

                return@withContext ResolvedBingePlayback(
                    title = detail.Name.ifBlank { item.name },
                    subtitle = item.releaseInfo,
                    sourceBadge = "${session.playMethod} · ${profile.name}",
                    itemId = item.id,
                    videoId = item.id,
                    contentType = "movie",
                    poster = item.poster,
                    background = item.background,
                    directUrl = session.streamUrl,
                    headers = session.headers,
                    ignoreSsl = session.ignoreSsl,
                    bingeGroup = "jf:${profile.serverId}",
                    streamHint = session.videoSummaryLabel,
                    addonName = profile.name,
                    isMirror = true,
                    labeledSubtitles = session.subtitleTracks.mapNotNull { it.toExternalSubtitleOrNull() },
                    jellyfinServerId = profile.serverId,
                    jellyfinItemId = detail.Id,
                    jellyfinSession = session
                )
            }
        }

        // 2. YouTube Video
        if (item.id.startsWith("yt:") || item.type.equals("youtube", ignoreCase = true)) {
            val videoId = item.id.removePrefix("yt:").trim().takeIf { it.isNotBlank() }
                ?: return@withContext null
            val maxHeight = StreamSimilarityMatcher.parseHeightFromHint(preferredResolutionHint) ?: 1080
            val ytAuth = buildYouTubeAuthContextFromPrefs(context)
            val resolved = runCatching {
                YouTubeApiClient.resolveStream(
                    videoId = videoId,
                    authContext = ytAuth,
                    preferredMaxHeight = maxHeight,
                    context = context
                )
            }.getOrNull() ?: return@withContext null

            val dashMpd = if (!resolved.isLive) resolved.buildDashMpdXml() else null
            val fallbackUrl = resolved.muxedUrl ?: resolved.hlsManifestUrl
            val primaryDirect = if (dashMpd != null) {
                fallbackUrl
            } else {
                resolved.hlsManifestUrl ?: resolved.muxedUrl ?: resolved.adaptiveVideoUrl
            }
            if (dashMpd == null && primaryDirect.isNullOrBlank()) {
                return@withContext null
            }

            val subtitles = resolved.captionTracks.mapIndexed { idx, ct ->
                ExternalSubtitle(
                    id = "ytcc:${ct.languageCode}:$idx",
                    url = ct.vttUrl.ifBlank { ct.srtUrl },
                    lang = if (ct.isAutoGenerated) "${ct.name} (Auto)" else ct.name.ifBlank { ct.languageCode },
                    addonName = "YouTube CC"
                )
            }
            val syncHistory = AppPrefs.isYouTubeWatchHistoryEnabled(context)
            val resBadge = resolved.height?.let { "${it}p" } ?: if (resolved.isLive) "Live" else "Ad-Free"

            return@withContext ResolvedBingePlayback(
                title = resolved.title.ifBlank { item.name },
                subtitle = resolved.author.ifBlank { item.releaseInfo }.ifBlank { "YouTube" },
                sourceBadge = "YouTube · $resBadge",
                itemId = "yt:${resolved.videoId}",
                videoId = resolved.videoId,
                contentType = "youtube",
                poster = resolved.thumbnailUrl ?: item.poster,
                background = resolved.thumbnailUrl ?: item.background.ifBlank { item.poster },
                directUrl = primaryDirect,
                headers = resolved.headers,
                addonName = "YouTube",
                isMirror = true,
                labeledSubtitles = subtitles,
                dashMpdXml = dashMpd,
                fallbackDirectUrl = fallbackUrl,
                youtubeVideoId = resolved.videoId,
                ytPlaybackTrackingUrl = if (syncHistory) resolved.playbackTrackingUrl else null,
                ytWatchtimeTrackingUrl = if (syncHistory) resolved.watchtimeTrackingUrl else null,
                ytCpn = if (syncHistory) resolved.cpn else ""
            )
        }

        // 3. Stremio Movie / Series
        val addons = AppPrefs.getInstalledAddons(context)
        val effectiveType = item.type.ifBlank { "movie" }
        val isSeriesLike = effectiveType.equals("series", ignoreCase = true) ||
            effectiveType.equals("anime", ignoreCase = true) ||
            item.videos.size > 1

        var targetVideoId = item.defaultVideoId.ifBlank { item.id }
        var subtitle = listOfNotNull(
            item.releaseInfo.takeIf { it.isNotBlank() },
            item.imdbRating.takeIf { it.isNotBlank() }?.let { "★ $it" }
        ).joinToString(" · ")
        var allEpisodes: List<VideoEpisode> = item.videos
        var embeddedStreams: List<StreamCandidate> = item.embeddedStreams
        var resolvedPoster = item.poster
        var resolvedBg = item.background

        if (isSeriesLike) {
            val detail = StremioApiClient.fetchMetaDetail(addons, effectiveType, item.id)
            if (detail != null) {
                if (resolvedPoster.isBlank()) resolvedPoster = detail.poster
                if (resolvedBg.isBlank()) resolvedBg = detail.background
            }
            val vids = detail?.videos.orEmpty().ifEmpty { item.videos }
            if (vids.isNotEmpty()) {
                val ep = vids.firstOrNull { it.season == 1 && it.episode == 1 }
                    ?: vids.firstOrNull { it.season > 0 }
                    ?: vids.first()
                targetVideoId = ep.id
                val epCode = formatEpisodeCode(ep.season, ep.episode)
                subtitle = listOfNotNull(
                    epCode.takeIf { it.isNotBlank() },
                    ep.title.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                allEpisodes = vids
                embeddedStreams = ep.streams + detail?.embeddedStreams.orEmpty() + item.embeddedStreams
                if (ep.thumbnail.isNotBlank()) resolvedBg = ep.thumbnail
            } else if (detail != null && detail.defaultVideoId.isNotBlank()) {
                targetVideoId = detail.defaultVideoId
                embeddedStreams = detail.embeddedStreams + item.embeddedStreams
            }
        }

        coroutineScope {
            val subsDeferred = async {
                withTimeoutOrNull(2_500L) {
                    StremioApiClient.fetchSubtitles(addons, effectiveType, targetVideoId)
                }.orEmpty()
            }

            val fetched = StremioApiClient.fetchStreamsProgressive(
                addons = addons,
                type = effectiveType,
                videoId = targetVideoId
            ) { _, _, _ -> }
            var combined = StremioApiClient.dedupeAndSortStreams(embeddedStreams + fetched)

            val hasPlayable = combined.any { it.directHttpUrlOrNull() != null || it.seeders > 0 }
            if (!hasPlayable && item.name.isNotBlank()) {
                val swarmQuery = if (allEpisodes.isNotEmpty()) {
                    val ep = allEpisodes.firstOrNull { it.id == targetVideoId }
                    if (ep != null && ep.season > 0) {
                        String.format(Locale.US, "%s S%02dE%02d", item.name, ep.season, ep.episode)
                    } else {
                        item.name
                    }
                } else {
                    val yr = item.releaseInfo.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
                    "${item.name} $yr".trim()
                }
                val swarm = NativeTorrentSearch.searchAll(context, swarmQuery, "public_domain")
                if (swarm.isNotEmpty()) {
                    combined = StremioApiClient.dedupeAndSortStreams(combined + swarm)
                }
            }

            val maxH = StreamSimilarityMatcher.parseHeightFromHint(preferredResolutionHint) ?: 1080
            val playerOpenable = combined.filter {
                it.directHttpUrlOrNull() != null || it.toMagnetUri() != null || !it.infoHash.isNullOrBlank()
            }
            val best = StreamSimilarityMatcher.pickBestMatchingStream(
                candidates = playerOpenable.ifEmpty { combined },
                previousStreamHint = preferredResolutionHint,
                previousWasMirror = preferMirror,
                preferredMaxHeight = maxH
            ) ?: return@coroutineScope null

            val subs = subsDeferred.await()
            StreamSimilarityMatcher.buildStremioResolvedPlayback(
                title = item.name,
                subtitle = subtitle,
                itemId = item.id,
                videoId = targetVideoId,
                contentType = effectiveType,
                poster = resolvedPoster,
                background = resolvedBg.ifBlank { resolvedPoster },
                best = best,
                subs = subs,
                allEpisodes = allEpisodes
            )
        }
    }

    private suspend fun fetchJellyfinRecommendations(
        context: Context,
        itemId: String,
        contentType: String,
        excludedIds: Set<String>,
        limit: Int
    ): List<MetaItem> {
        val (serverId, rawItemId) = JellyfinIds.parseId(itemId) ?: return emptyList()
        val profile = AppPrefs.getJellyfinServer(context, serverId)
            ?.takeIf { it.isAuthenticated }
            ?: return emptyList()
        val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
            .takeIf { it.isNotBlank() }
            ?: return emptyList()

        val collected = LinkedHashMap<String, MetaItem>()
        val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(context)
        val authHeader = JellyfinApiClient.buildAuthorizationHeader(deviceId, profile.accessToken)
        val client = if (profile.allowInsecureTls) {
            BrootLiteApp.sharedPermissiveHttpClient
        } else {
            BrootLiteApp.sharedHttpClient
        }

        val similarItems: List<JfItem> = runCatching {
            val encId = URLEncoder.encode(rawItemId, "UTF-8")
            val encUser = URLEncoder.encode(profile.userId, "UTF-8")
            val url = "$baseUrl/Items/$encId/Similar" +
                "?userId=$encUser&limit=${limit + 4}" +
                "&Fields=PrimaryImageAspectRatio,ProductionYear,Overview,CommunityRating,Genres,OfficialRating" +
                "&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .header("X-Emby-Token", profile.accessToken)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@use emptyList()
                val elem = AppPrefs.json.parseToJsonElement(raw)
                when (elem) {
                    is JsonObject -> AppPrefs.json.decodeFromString<JfItemsEnvelope<JfItem>>(raw).Items
                    is JsonArray -> AppPrefs.json.decodeFromString<List<JfItem>>(raw)
                    else -> emptyList()
                }
            }
        }.getOrDefault(emptyList())

        for (jf in similarItems) {
            if (jf.Id.isBlank() || jf.Id.equals(rawItemId, ignoreCase = true)) continue
            val mapped = jf.toMetaItem(profile.serverId, baseUrl)
            if (mapped.id !in excludedIds) {
                collected[mapped.id] = mapped
                if (collected.size >= limit) return collected.values.toList()
            }
        }

        // Fallback to same-genre items from the same isolated Jellyfin server if /Similar was sparse/empty
        val detail = JellyfinApiClient.getItemDetail(context, profile, rawItemId)
        val primaryGenre = detail?.Genres?.firstOrNull()?.takeIf { it.isNotBlank() }
        val includeType = if (contentType.equals("series", ignoreCase = true) ||
            detail?.Type.equals("Series", ignoreCase = true) ||
            detail?.Type.equals("Episode", ignoreCase = true)
        ) {
            "Series"
        } else {
            "Movie"
        }
        val (fallbackItems, _) = JellyfinApiClient.getLibraryItems(
            context = context,
            profile = profile,
            viewId = "",
            includeItemTypes = includeType,
            sortBy = "CommunityRating",
            sortOrder = "Descending",
            genre = primaryGenre,
            limit = limit + 8
        )
        for (jf in fallbackItems) {
            if (jf.Id.isBlank() || jf.Id.equals(rawItemId, ignoreCase = true)) continue
            val mapped = jf.toMetaItem(profile.serverId, baseUrl)
            if (mapped.id !in excludedIds && !collected.containsKey(mapped.id)) {
                collected[mapped.id] = mapped
                if (collected.size >= limit) break
            }
        }

        return collected.values.take(limit)
    }

    private suspend fun fetchYouTubeRecommendations(
        context: Context,
        itemId: String,
        title: String,
        excludedIds: Set<String>,
        limit: Int
    ): List<MetaItem> {
        val rawVideoId = itemId.removePrefix("yt:").trim()
        val ytAuth = buildYouTubeAuthContextFromPrefs(context)
        val byVideoId = LinkedHashMap<String, YouTubeVideoCard>()

        if (rawVideoId.isNotBlank()) {
            val related = runCatching {
                YouTubeApiClient.getRelatedVideos(videoId = rawVideoId, authContext = ytAuth)
            }.getOrNull()
            if (related != null) {
                for (v in related.items + related.shelves.flatMap { it.videos }) {
                    if (v.videoId.isBlank() || v.videoId == rawVideoId) continue
                    if ("yt:${v.videoId}" in excludedIds) continue
                    byVideoId.putIfAbsent(v.videoId, v)
                    if (byVideoId.size >= limit) break
                }
            }
        }

        if (byVideoId.size < limit && title.isNotBlank()) {
            val searched = runCatching {
                YouTubeApiClient.search(
                    query = title,
                    authContext = ytAuth,
                    filter = YouTubeSearchFilter.VIDEOS
                )
            }.getOrNull()
            if (searched != null) {
                for (v in searched.items + searched.shelves.flatMap { it.videos }) {
                    if (v.videoId.isBlank() || v.videoId == rawVideoId) continue
                    if ("yt:${v.videoId}" in excludedIds) continue
                    byVideoId.putIfAbsent(v.videoId, v)
                    if (byVideoId.size >= limit) break
                }
            }
        }

        return byVideoId.values.take(limit).map { v ->
            MetaItem(
                id = "yt:${v.videoId}",
                type = "youtube",
                name = v.title,
                poster = v.thumbnailUrl,
                background = v.thumbnailUrl,
                description = listOfNotNull(
                    v.channelName.takeIf { it.isNotBlank() },
                    v.viewCountText?.takeIf { it.isNotBlank() },
                    v.publishedText?.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                releaseInfo = v.channelName,
                imdbRating = v.durationText ?: "",
                runtime = v.durationText ?: ""
            )
        }
    }

    private suspend fun fetchStremioRecommendations(
        context: Context,
        itemId: String,
        contentType: String,
        title: String,
        excludedIds: Set<String>,
        limit: Int
    ): List<MetaItem> = coroutineScope {
        val addons = AppPrefs.getInstalledAddons(context)
        val cleanBaseId = if (itemId.startsWith("tt") && itemId.contains(':')) {
            itemId.substringBefore(':')
        } else {
            itemId.trim()
        }
        val effectiveType = when {
            contentType.equals("series", ignoreCase = true) -> "series"
            contentType.equals("anime", ignoreCase = true) -> "anime"
            itemId.startsWith("tt") && itemId.contains(':') -> "series"
            contentType.isBlank() || contentType.equals("torrent", ignoreCase = true) -> "movie"
            else -> contentType
        }

        val meta = if (cleanBaseId.isNotBlank() && !cleanBaseId.startsWith("torrent:")) {
            withTimeoutOrNull(6_000L) {
                StremioApiClient.fetchMetaDetail(addons, effectiveType, cleanBaseId)
            }
        } else {
            null
        }

        val sourceTitle = (meta?.name?.takeIf { it.isNotBlank() } ?: title).trim()
        val topGenres = meta?.genres.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(2)
        val targetGenresLower = meta?.genres.orEmpty()
            .mapTo(HashSet()) { it.trim().lowercase(Locale.US) }

        val catalogTarget = findBestGenreCatalogTarget(addons, effectiveType)
        val franchiseQuery = extractFranchiseSearchPrefix(sourceTitle)

        val genreHitsById = HashMap<String, MutableSet<String>>()
        val franchiseMatchedIds = HashSet<String>()
        val candidatesById = LinkedHashMap<String, MetaItem>()

        val jobs = buildList {
            if (catalogTarget != null) {
                val (catAddon, catDef) = catalogTarget
                if (topGenres.isNotEmpty()) {
                    for (genre in topGenres) {
                        add(async {
                            val items = withTimeoutOrNull(7_000L) {
                                StremioApiClient.fetchCatalog(catAddon, catDef, genre = genre, skip = 0)
                            }.orEmpty()
                            Triple("genre:$genre", genre.lowercase(Locale.US), items)
                        })
                    }
                } else {
                    add(async {
                        val items = withTimeoutOrNull(7_000L) {
                            StremioApiClient.fetchCatalog(catAddon, catDef, genre = null, skip = 0)
                        }.orEmpty()
                        Triple("popular", "", items)
                    })
                }
            }

            val searchSeed = franchiseQuery
                ?: sourceTitle.takeIf { topGenres.isEmpty() && it.length >= 3 }?.take(36)
            if (!searchSeed.isNullOrBlank()) {
                add(async {
                    val items = withTimeoutOrNull(7_000L) {
                        StremioApiClient.searchStremioCatalogs(addons, searchSeed)
                    }.orEmpty()
                    Triple("search", "", items)
                })
            }
        }

        for ((bucket, genreKey, items) in jobs.awaitAll()) {
            for (cand in items) {
                val cid = cand.id.trim()
                if (cid.isEmpty()) continue
                if (cid == cleanBaseId || cid == itemId || cid in excludedIds) continue
                if (cand.name.equals(sourceTitle, ignoreCase = true) && cid == cleanBaseId) continue

                val existing = candidatesById[cid]
                if (existing == null || (existing.poster.isBlank() && cand.poster.isNotBlank())) {
                    candidatesById[cid] = cand
                }
                if (bucket.startsWith("genre:") && genreKey.isNotEmpty()) {
                    genreHitsById.getOrPut(cid) { HashSet() }.add(genreKey)
                } else if (bucket == "search") {
                    franchiseMatchedIds.add(cid)
                }
            }
        }

        val ranked = candidatesById.values.sortedByDescending { cand ->
            val explicitGenreOverlap = cand.genres.count { it.trim().lowercase(Locale.US) in targetGenresLower }
            val catalogGenreOverlap = genreHitsById[cand.id]?.size ?: 0
            val matchingGenreCount = maxOf(explicitGenreOverlap, catalogGenreOverlap)
            val ratingScore = cand.imdbRating.toDoubleOrNull() ?: 6.0
            val franchiseBonus = if (cand.id in franchiseMatchedIds) 8.0 else 0.0
            val sameTypeBonus = if (cand.type.equals(effectiveType, ignoreCase = true)) 2.5 else 0.0
            val posterBonus = if (cand.poster.isNotBlank()) 1.5 else 0.0
            (matchingGenreCount * 10.0) + ratingScore + franchiseBonus + sameTypeBonus + posterBonus
        }

        ranked.take(limit)
    }

    private fun findBestGenreCatalogTarget(
        addons: List<InstalledAddon>,
        effectiveType: String
    ): Pair<InstalledAddon, AddonCatalogDef>? {
        val cinemetaType = if (effectiveType.equals("series", ignoreCase = true)) "series" else "movie"
        for (addon in addons) {
            if (addon.id != "com.linvo.cinemeta" && addon.id != "pw.ers.netflix-catalog") {
                val customMatch = addon.catalogs.firstOrNull {
                    it.type.equals(effectiveType, ignoreCase = true) && it.isBrowseSupported && it.isGenreSupported
                }
                if (customMatch != null && !effectiveType.equals("movie", true) && !effectiveType.equals("series", true)) {
                    return addon to customMatch
                }
            }
        }
        val cinemeta = addons.firstOrNull { it.id == "com.linvo.cinemeta" }
            ?: AppPrefs.defaultCinemetaAddon()
        val topCat = cinemeta.catalogs.firstOrNull { it.type == cinemetaType && it.id == "top" }
            ?: cinemeta.catalogs.firstOrNull { it.type == cinemetaType }
        return topCat?.let { cinemeta to it }
    }

    private fun extractFranchiseSearchPrefix(rawTitle: String): String? {
        val cleaned = rawTitle.trim()
        if (cleaned.length < 4) return null
        val delimiters = listOf(": ", " - ", " – ", " — ")
        for (delim in delimiters) {
            val idx = cleaned.indexOf(delim)
            if (idx >= 4) {
                return cleaned.substring(0, idx).trim()
            }
        }
        val sequelMatch = Regex("""^(.{4,}?)\s+(?:Part\s+[IVX\d]+|[2-9]|II|III|IV|V)$""", RegexOption.IGNORE_CASE)
            .find(cleaned)
        if (sequelMatch != null) {
            return sequelMatch.groupValues[1].trim()
        }
        return null
    }

    private fun buildYouTubeAuthContextFromPrefs(context: Context): YouTubeAuthContext {
        val appCtx = context.applicationContext
        val oauth = AppPrefs.getYouTubeOAuth(appCtx)?.takeIf { it.isActive }
        val deArrow = AppPrefs.isDeArrowEnabled(appCtx)
        if (oauth == null) {
            return YouTubeAuthContext(deArrowEnabled = deArrow)
        }
        return YouTubeAuthContext(
            accessToken = oauth.accessToken.ifBlank { null },
            refreshToken = oauth.refreshToken.ifBlank { null },
            expiresAtMs = oauth.accessTokenExpiresAtMs,
            clientId = oauth.clientId.ifBlank { null },
            clientSecret = oauth.clientSecret.ifBlank { null },
            pageId = oauth.pageId.ifBlank { null },
            accountId = oauth.channelId.ifBlank { null },
            deArrowEnabled = deArrow,
            onTokenRefreshed = { fresh ->
                val cur = AppPrefs.getYouTubeOAuth(appCtx) ?: oauth
                AppPrefs.saveYouTubeOAuth(
                    appCtx,
                    cur.copy(
                        accessToken = fresh.accessToken,
                        refreshToken = fresh.refreshToken.ifBlank { cur.refreshToken },
                        accessTokenExpiresAtMs = fresh.expiresAtMs,
                        clientId = fresh.clientId.ifBlank { cur.clientId },
                        clientSecret = fresh.clientSecret.ifBlank { cur.clientSecret },
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        )
    }
}

private fun StreamCandidate.directHttpUrlOrNull(): String? =
    url?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("magnet:", ignoreCase = true) }

private fun formatEpisodeCode(season: Int, episode: Int): String = when {
    season > 0 && episode > 0 -> String.format(Locale.US, "S%02dE%02d", season, episode)
    episode > 0 -> String.format(Locale.US, "E%02d", episode)
    else -> ""
}
