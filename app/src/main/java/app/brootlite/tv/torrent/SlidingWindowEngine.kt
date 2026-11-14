package app.brootlite.tv.torrent

import android.content.Context
import android.net.Uri
import android.util.LruCache
import app.brootlite.tv.data.AppPrefs
import app.brootlite.tv.data.StreamCandidate
import app.brootlite.tv.data.TorrentEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.ErrorCode
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class SwarmStats(
    val statusText: String = "Initializing swarm...",
    val downloadRateBps: Int = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val totalSeeds: Int = 0,
    val windowBufferPct: Int = 0,
    val isReady: Boolean = false,
    val fileName: String = "",
    val fileSize: Long = 0L
) {
    val formattedOverlay: String
        get() {
            val mbps = downloadRateBps.toDouble() / (1024.0 * 1024.0)
            val seedStr = if (totalSeeds > seeds) "$seeds/$totalSeeds Seeds" else "$seeds Seeds"
            return String.format("↓ %.2f MB/s • %d Peers (%s) • Window %d%%", mbps, peers, seedStr, windowBufferPct)
        }
}

class SlidingWindowEngine(
    context: Context,
    private val magnetUri: String,
    private val requestedFileIdx: Int?,
    private val extraTrackers: List<String>,
    private val config: TorrentEngineConfig,
    private val isResumingMidStream: Boolean = false,
    private val targetSeason: Int = 0,
    private val targetEpisode: Int = 0
) {
    companion object {
        private const val CHUNK_SIZE = 256 * 1024 // 256 KB aligned chunks -> zero large ByteArray fragmentation
        private const val PIECE_RAM_CACHE_BYTES = 12 * 1024 * 1024 // Strictly capped at 12 MB
        private const val HEADER_PIN_BYTES = 2L * 1024L * 1024L // 2 MB container header pin (EBML / ftyp / moov)
        private const val FOOTER_PIN_BYTES = 2L * 1024L * 1024L // 2 MB EOF pin (MKV Cues / trailing moov)
        private val VIDEO_EXTENSIONS = listOf(".mkv", ".mp4", ".webm", ".avi", ".ts", ".m4v", ".mov")

        // Warm process-wide SessionManager so DHT routing nodes stay populated across plays & episodes
        private val sessionLock = Any()
        private val sharedSession = SessionManager(false)
        @Volatile
        private var activeEngine: SlidingWindowEngine? = null

        private fun buildHighSpeedSettings(maxConnectionsCfg: Int): SettingsPack {
            val maxConn = maxOf(maxConnectionsCfg, 120)
            return SettingsPack().apply {
                listenInterfaces("0.0.0.0:0,[::]:0")
                connectionsLimit(maxConn)
                activeDownloads(4)
                activeSeeds(2)
                activeLimit(8)
                maxPeerlistSize(2000)
                maxQueuedDiskBytes(6 * 1024 * 1024)
                sendBufferWatermark(2 * 1024 * 1024)
                tickInterval(120)

                // Simultaneous Multi-Tracker + Aggressive DHT + LSD + PEX Peer Discovery
                setEnableDht(true)
                setEnableLsd(true)
                setBoolean(settings_pack.bool_types.use_dht_as_fallback.swigValue(), false)
                setBoolean(settings_pack.bool_types.dht_aggressive_lookups.swigValue(), true)
                setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
                setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
                setBoolean(settings_pack.bool_types.announce_crypto_support.swigValue(), true)
                // Critical on Android: native BoringSSL lacks /etc/ssl/certs so validate_https_trackers=true drops HTTPS trackers!
                setBoolean(settings_pack.bool_types.validate_https_trackers.swigValue(), false)
                setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
                setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
                setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), true)
                setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), true)
                setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
                // Disable outgoing uTP LEDBAT on Android TV Wi-Fi so 100ms Wi-Fi jitter never collapses rate to 0.00 MB/s!
                setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
                setBoolean(settings_pack.bool_types.smooth_connects.swigValue(), false)

                // MSE / Protocol Encryption so seedboxes & ISP-filtered peers connect immediately
                setInteger(
                    settings_pack.int_types.out_enc_policy.swigValue(),
                    settings_pack.enc_policy.pe_enabled.swigValue()
                )
                setInteger(
                    settings_pack.int_types.in_enc_policy.swigValue(),
                    settings_pack.enc_policy.pe_enabled.swigValue()
                )
                setInteger(
                    settings_pack.int_types.allowed_enc_level.swigValue(),
                    settings_pack.enc_level.pe_both.swigValue()
                )
                setBoolean(settings_pack.bool_types.prefer_rc4.swigValue(), true)

                // Elementum High-Throughput Streaming Piece-Picker & Socket Pipeline Tuning
                setInteger(
                    settings_pack.int_types.mixed_mode_algorithm.swigValue(),
                    settings_pack.bandwidth_mixed_algo_t.prefer_tcp.swigValue()
                )
                setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), false)
                setBoolean(settings_pack.bool_types.prioritize_partial_pieces.swigValue(), true)
                setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), 32)
                setInteger(settings_pack.int_types.connections_slack.swigValue(), 30)
                setInteger(settings_pack.int_types.connection_speed.swigValue(), 200)
                setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), 255)
                setInteger(settings_pack.int_types.piece_timeout.swigValue(), 6)
                setInteger(settings_pack.int_types.request_timeout.swigValue(), 8)
                setInteger(settings_pack.int_types.peer_timeout.swigValue(), 20)
                setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 6)
                setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 1500)
                setInteger(settings_pack.int_types.max_allowed_in_request_queue.swigValue(), 2000)
                setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), 10)
                setInteger(settings_pack.int_types.recv_socket_buffer_size.swigValue(), 2 * 1024 * 1024)
                setInteger(settings_pack.int_types.send_socket_buffer_size.swigValue(), 512 * 1024)
                setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0)
                setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 512 * 1024)

                setInteger(settings_pack.int_types.aio_threads.swigValue(), 4)
                setInteger(settings_pack.int_types.checking_mem_usage.swigValue(), 256)
                setDhtBootstrapNodes(
                    "router.bittorrent.com:6881," +
                        "dht.transmissionbt.com:6881," +
                        "router.utorrent.com:6881," +
                        "dht.libtorrent.org:25401," +
                        "dht.aelitis.com:6881"
                )
            }
        }

        private fun ensureWarmSessionLocked(appContext: Context, maxConn: Int): SessionManager {
            val dhtFile = File(appContext.filesDir, "dht_state.bin")
            val sp = buildHighSpeedSettings(maxConn)
            if (!sharedSession.isRunning) {
                val params = runCatching {
                    if (dhtFile.isFile && dhtFile.length() in 16L..262_144L) {
                        SessionParams(dhtFile).apply {
                            settings = sp
                            setPosixDiskIO()
                        }
                    } else null
                }.getOrNull() ?: SessionParams(sp).apply {
                    setPosixDiskIO()
                }
                sharedSession.start(params)
            } else {
                runCatching { sharedSession.applySettings(sp) }
            }
            return sharedSession
        }

        fun warmUpSessionAsync(context: Context) {
            val appCtx = context.applicationContext
            Thread {
                runCatching {
                    val cfg = AppPrefs.getTorrentConfig(appCtx)
                    synchronized(sessionLock) {
                        ensureWarmSessionLocked(appCtx, cfg.maxConnections)
                    }
                }
            }.start()
        }
    }

    private val appContext = context.applicationContext
    private val saveDir = File(context.cacheDir, "torrents/session_${System.currentTimeMillis()}").apply {
        mkdirs()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickerJob: Job? = null

    @Volatile
    private var torrentHandle: TorrentHandle? = null
    @Volatile
    private var torrentInfo: TorrentInfo? = null
    @Volatile
    private var selectedFileIdx: Int = 0
    @Volatile
    var selectedFileSize: Long = -1L
        private set
    @Volatile
    private var selectedFileOnDisk: File? = null

    @Volatile
    var currentReadOffset: Long = 0L
    @Volatile
    private var lastReadAtOffset: Long = 0L
    @Volatile
    private var pendingSeekReset: Boolean = false
    @Volatile
    private var demandedPiece: Int = -1
    @Volatile
    private var hasSeenPlayheadSeek: Boolean = !isResumingMidStream
    @Volatile
    private var isStopped: Boolean = false

    private val readyLatch = CountDownLatch(1)
    @Volatile
    private var startupError: String? = null

    private val pieceWaitLock = Object()
    private val rafLock = Any()
    private var raf: RandomAccessFile? = null

    private val completedPieces = BitSet()
    private val pinnedPieces = BitSet()
    private var firstFilePiece = 0
    private var lastFilePiece = 0
    private var headerEndPiece = 0
    private var footerStartPiece = 0

    // 12 MB RAM LRU cache holding 256 KB file chunks
    private val chunkCache = object : LruCache<Long, ByteArray>(PIECE_RAM_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: ByteArray): Int = value.size
    }

    private val _stats = MutableStateFlow(SwarmStats())
    val stats: StateFlow<SwarmStats> = _stats

    private val alertListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.PIECE_FINISHED.swig()
        )

        override fun alert(alert: Alert<*>) {
            val myHandle = torrentHandle
            when (alert) {
                is MetadataReceivedAlert -> {
                    if (myHandle == null || alert.handle().swig() == myHandle.swig()) {
                        synchronized(pieceWaitLock) {
                            pieceWaitLock.notifyAll()
                        }
                    }
                }
                is PieceFinishedAlert -> {
                    if (myHandle == null || alert.handle().swig() == myHandle.swig()) {
                        val idx = alert.pieceIndex()
                        synchronized(pieceWaitLock) {
                            completedPieces.set(idx)
                            pieceWaitLock.notifyAll()
                        }
                    }
                }
            }
        }
    }

    fun onDataSourceOpen(position: Long) {
        val prev = currentReadOffset
        if (abs(position - prev) > 1_500_000L) {
            pendingSeekReset = true
            if (position > HEADER_PIN_BYTES && selectedFileSize > 0L && position < selectedFileSize - FOOTER_PIN_BYTES) {
                hasSeenPlayheadSeek = true
            }
        }
        currentReadOffset = position
    }

    fun start() {
        scope.launch {
            try {
                // Stop any previous active torrent cleanly before adding the new one
                val prevEngine = synchronized(sessionLock) {
                    val old = activeEngine
                    activeEngine = this@SlidingWindowEngine
                    old
                }
                if (prevEngine != null && prevEngine !== this@SlidingWindowEngine) {
                    prevEngine.stopSynchronously()
                }

                val handle = synchronized(sessionLock) {
                    if (isStopped) return@synchronized null
                    val sm = ensureWarmSessionLocked(appContext, config.maxConnections)
                    sm.addListener(alertListener)

                    val addParams = AddTorrentParams.parseMagnetUri(magnetUri)
                    addParams.savePath = saveDir.absolutePath
                    val mergedTrackers = (addParams.trackers + extraTrackers + StreamCandidate.DEFAULT_PUBLIC_TRACKERS)
                        .map { it.replace(Regex("^tracker:", RegexOption.IGNORE_CASE), "").trim() }
                        .filter {
                            it.startsWith("udp://", true) ||
                                it.startsWith("http://", true) ||
                                it.startsWith("https://", true)
                        }
                        .distinct()
                    addParams.trackers = mergedTrackers
                    // Put every tracker in tier 0 so libtorrent blasts ALL trackers concurrently on t=0ms!
                    addParams.trackerTiers = List(mergedTrackers.size) { 0 }

                    // Prevent downloading unwanted season-pack episodes before we inspect metadata
                    val cleanFlags = addParams.flags
                        .or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
                        .and_(TorrentFlags.SEQUENTIAL_DOWNLOAD.inv())
                        .and_(TorrentFlags.PAUSED.inv())
                    addParams.flags = cleanFlags

                    val sessionHandle = SessionHandle(sm.swig())
                    // Remove existing handle if re-opening the exact same infoHash
                    val existing = sm.find(addParams.infoHashes.v1)
                    if (existing != null && existing.isValid) {
                        runCatching { sm.remove(existing, SessionHandle.DELETE_FILES) }
                    }

                    val errorCode = ErrorCode(org.libtorrent4j.swig.error_code())
                    val added = sessionHandle.addTorrent(addParams, errorCode)
                    if (errorCode.isError || !added.isValid) {
                        startupError = "Failed to add torrent (${errorCode.message})"
                        null
                    } else {
                        runCatching {
                            mergedTrackers.forEach { tr ->
                                added.addTracker(AnnounceEntry(tr))
                            }
                        }
                        added
                    }
                }

                if (handle == null) {
                    readyLatch.countDown()
                    return@launch
                }
                torrentHandle = handle

                handle.resume()
                runCatching { handle.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL) }
                runCatching { handle.forceDHTAnnounce() }
                runCatching { handle.forceLSDAnnounce() }

                // Wait for torrent metadata, re-poking DHT/trackers every 3s if swarm is slow to reply
                val startWaitMs = System.currentTimeMillis()
                val deadlineMs = startWaitMs + 50_000L
                var lastPokeMs = startWaitMs
                while (!isStopped && !handle.status().hasMetadata() && System.currentTimeMillis() < deadlineMs) {
                    val now = System.currentTimeMillis()
                    if (now - lastPokeMs >= 3_000L) {
                        lastPokeMs = now
                        runCatching { handle.forceDHTAnnounce() }
                        runCatching { handle.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL) }
                    }
                    val st = handle.status()
                    val knownSeeds = maxOf(st.numSeeds(), st.listSeeds(), st.numComplete())
                    _stats.value = SwarmStats(
                        statusText = "Connecting to swarm... (${st.numPeers()} peers, ${st.numSeeds()} seeds)",
                        downloadRateBps = st.downloadPayloadRate(),
                        peers = st.numPeers(),
                        seeds = st.numSeeds(),
                        totalSeeds = knownSeeds
                    )
                    delay(150L)
                }

                if (isStopped) {
                    readyLatch.countDown()
                    return@launch
                }
                if (!handle.status().hasMetadata()) {
                    startupError = "Timed out waiting for torrent metadata from swarm"
                    readyLatch.countDown()
                    return@launch
                }

                val ti = handle.torrentFile()
                torrentInfo = ti
                configureSelectedFileAndInitialPriorities(handle, ti)
                readyLatch.countDown()

                // Start fast 220ms Elementum-style sliding-window scheduler
                tickerJob = scope.launch {
                    runSlidingWindowTicker(handle, ti)
                }
            } catch (t: Throwable) {
                startupError = t.message ?: t.javaClass.simpleName
                readyLatch.countDown()
            }
        }
    }

    private fun configureSelectedFileAndInitialPriorities(handle: TorrentHandle, ti: TorrentInfo) {
        val files = ti.files()
        val numFiles = files.numFiles()

        val chosenIdx = if (requestedFileIdx != null && requestedFileIdx in 0 until numFiles && !files.padFileAt(requestedFileIdx)) {
            requestedFileIdx
        } else {
            selectBestEpisodeOrVideoFile(files, numFiles, targetSeason, targetEpisode)
        }

        selectedFileIdx = chosenIdx
        val fSize = files.fileSize(chosenIdx)
        selectedFileSize = fSize
        selectedFileOnDisk = File(saveDir, files.filePath(chosenIdx))

        // Ignore all other files in multi-file season packs; set chosen file to LOW baseline so
        // totalWantedDone < totalWanted remains true until 100% of the episode is downloaded
        // (preventing libtorrent State.FINISHED -> disconnect_seeds()!).
        val filePriorities = Array(numFiles) { idx ->
            if (idx == chosenIdx) Priority.LOW else Priority.IGNORE
        }
        handle.prioritizeFiles(filePriorities)

        firstFilePiece = files.mapFile(chosenIdx, 0L, 1).piece()
        lastFilePiece = files.mapFile(chosenIdx, maxOf(0L, fSize - 1L), 1).piece()

        headerEndPiece = files.mapFile(chosenIdx, minOf(maxOf(0L, fSize - 1L), HEADER_PIN_BYTES), 1).piece()
        footerStartPiece = files.mapFile(chosenIdx, maxOf(0L, fSize - FOOTER_PIN_BYTES), 1).piece()

        synchronized(pieceWaitLock) {
            pinnedPieces.clear()
            for (p in firstFilePiece..headerEndPiece) pinnedPieces.set(p)
            for (p in footerStartPiece..lastFilePiece) pinnedPieces.set(p)
        }

        val numPieces = ti.numPieces()
        val piecePrios = Array(numPieces) { p ->
            when {
                p < firstFilePiece || p > lastFilePiece -> Priority.IGNORE
                pinnedPieces.get(p) -> Priority.TOP_PRIORITY
                else -> Priority.LOW
            }
        }
        handle.prioritizePieces(piecePrios)
        applyPinnedHeaderFooterDeadlines(handle)

        _stats.value = SwarmStats(
            statusText = "Buffering container index...",
            isReady = true,
            fileName = files.fileName(chosenIdx),
            fileSize = fSize
        )
    }

    private fun selectBestEpisodeOrVideoFile(
        files: FileStorage,
        numFiles: Int,
        season: Int,
        episode: Int
    ): Int {
        var bestVideoIdx = -1
        var bestVideoScore = -1
        var bestVideoSize = -1L
        var fallbackIdx = 0
        var fallbackSize = -1L

        val exactSxxExx = if (season > 0 && episode > 0) {
            Regex("""(?i)(?:\bS0?$season[ ._-]*E0?$episode\b|\b0?${season}x0?$episode\b)""")
        } else null
        val seasonFolderRegex = if (season > 0) {
            Regex("""(?i)(?:\bSeason[ ._-]*0?$season\b|\bS0?$season\b)""")
        } else null
        val epOnlyRegex = if (episode > 0) {
            Regex("""(?i)(?:\bE0?$episode\b|\bEpisode[ ._-]*0?$episode\b)""")
        } else null
        val combinedCodeRegex = if (season in 1..19 && episode in 1..99) {
            val compact = String.format("%d%02d", season, episode)
            Regex("""(?<!\d)0?$compact(?!\d|[pP])""")
        } else null

        for (i in 0 until numFiles) {
            if (files.padFileAt(i)) continue
            val fPath = files.filePath(i)
            val fName = files.fileName(i)
            val fLower = fName.lowercase()
            val fSize = files.fileSize(i)
            if (fSize > fallbackSize) {
                fallbackSize = fSize
                fallbackIdx = i
            }
            val isVideo = VIDEO_EXTENSIONS.any { fLower.endsWith(it) } && !fLower.contains("sample")
            if (!isVideo) continue

            val epScore = when {
                exactSxxExx != null && exactSxxExx.containsMatchIn(fPath) -> 1000
                seasonFolderRegex != null && epOnlyRegex != null &&
                    seasonFolderRegex.containsMatchIn(fPath) && epOnlyRegex.containsMatchIn(fName) -> 900
                combinedCodeRegex != null && combinedCodeRegex.containsMatchIn(fName) &&
                    (seasonFolderRegex == null || seasonFolderRegex.containsMatchIn(fPath) || !Regex("""(?i)\bSeason[ ._-]*\d+""").containsMatchIn(fPath)) -> 800
                epOnlyRegex != null && epOnlyRegex.containsMatchIn(fName) &&
                    !Regex("""(?i)\bS\d{1,2}[ ._-]*E\d{1,3}\b""").containsMatchIn(fPath) -> 700
                else -> 0
            }

            if (epScore > bestVideoScore || (epScore == bestVideoScore && fSize > bestVideoSize)) {
                bestVideoScore = epScore
                bestVideoSize = fSize
                bestVideoIdx = i
            }
        }
        return if (bestVideoIdx >= 0) bestVideoIdx else fallbackIdx
    }

    private fun applyPinnedHeaderFooterDeadlines(handle: TorrentHandle) {
        // Urgent deadlines on compact header + EOF index so MatroskaExtractor Cues / MP4 moov open immediately!
        var rank = 0
        for (p in firstFilePiece..minOf(headerEndPiece, firstFilePiece + 2)) {
            if (!hasPieceReady(handle, p)) {
                handle.piecePriority(p, Priority.TOP_PRIORITY)
                handle.setPieceDeadline(p, rank * 35)
                rank++
            }
        }
        var fRank = 0
        for (p in lastFilePiece downTo maxOf(footerStartPiece, lastFilePiece - 2)) {
            if (!hasPieceReady(handle, p)) {
                handle.piecePriority(p, Priority.TOP_PRIORITY)
                handle.setPieceDeadline(p, 50 + fRank * 40)
                fRank++
            }
        }
    }

    private suspend fun runSlidingWindowTicker(handle: TorrentHandle, ti: TorrentInfo) {
        val files = ti.files()
        val windowBytes = maxOf(config.slidingWindowMb, config.preBufferMb + 12).toLong() * 1024L * 1024L
        val preBufferBytes = config.preBufferMb.toLong() * 1024L * 1024L
        val behindKeepBytes = 2L * 1024L * 1024L
        var lastScheduledPlayheadPiece = -1
        var lastSwarmPokeMs = System.currentTimeMillis()

        while (scope.isActive && !isStopped && handle.isValid) {
            try {
                val fSize = selectedFileSize
                if (fSize > 0L) {
                    val readPos = currentReadOffset.coerceIn(0L, fSize - 1L)
                    if (readPos > HEADER_PIN_BYTES && readPos < fSize - FOOTER_PIN_BYTES) {
                        hasSeenPlayheadSeek = true
                    }
                    val playheadPiece = files.mapFile(selectedFileIdx, readPos, 1).piece()
                    val isProbingContainerOnly = isResumingMidStream && !hasSeenPlayheadSeek

                    if (pendingSeekReset || (lastScheduledPlayheadPiece >= 0 && abs(playheadPiece - lastScheduledPlayheadPiece) > 6)) {
                        pendingSeekReset = false
                        runCatching { handle.clearPieceDeadlines() }
                        applyPinnedHeaderFooterDeadlines(handle)
                        val curDemanded = demandedPiece
                        if (curDemanded in firstFilePiece..lastFilePiece && !hasPieceReady(handle, curDemanded)) {
                            handle.piecePriority(curDemanded, Priority.TOP_PRIORITY)
                            handle.setPieceDeadline(curDemanded, 0)
                        }
                    }
                    lastScheduledPlayheadPiece = playheadPiece

                    val curPrios = handle.piecePriorities()

                    // Ensure uncompleted 2 MB header/footer index pieces stay TOP_PRIORITY with deadlines
                    var pIdx = pinnedPieces.nextSetBit(0)
                    while (pIdx >= 0) {
                        if (!hasPieceReady(handle, pIdx)) {
                            if (pIdx < curPrios.size && curPrios[pIdx] != Priority.TOP_PRIORITY) {
                                handle.piecePriority(pIdx, Priority.TOP_PRIORITY)
                            }
                        }
                        pIdx = pinnedPieces.nextSetBit(pIdx + 1)
                    }
                    applyPinnedHeaderFooterDeadlines(handle)

                    var pct = 0
                    if (isProbingContainerOnly) {
                        // While ExoPlayer probes header + EOF Cues before seeking to resumePositionMs,
                        // do NOT schedule a 28 MB window at 0:00 — focus 100% of swarm bandwidth on header + footer!
                        var pinnedTotal = 0
                        var pinnedDone = 0
                        var b = pinnedPieces.nextSetBit(0)
                        while (b >= 0) {
                            pinnedTotal++
                            if (hasPieceReady(handle, b)) pinnedDone++
                            b = pinnedPieces.nextSetBit(b + 1)
                        }
                        pct = if (pinnedTotal > 0) ((pinnedDone * 100) / pinnedTotal).coerceIn(0, 100) else 0
                    } else {
                        val preBufferEndPos = (readPos + preBufferBytes).coerceAtMost(fSize - 1L)
                        val preBufferEndPiece = files.mapFile(selectedFileIdx, preBufferEndPos, 1).piece()
                        val windowEndPos = (readPos + windowBytes).coerceAtMost(fSize - 1L)
                        val windowEndPiece = files.mapFile(selectedFileIdx, windowEndPos, 1).piece()
                        val behindCutoffPos = (readPos - behindKeepBytes).coerceAtLeast(0L)
                        val behindCutoffPiece = files.mapFile(selectedFileIdx, behindCutoffPos, 1).piece()

                        // Demote pieces outside active window to Priority.LOW (NOT IGNORE!) so seeds stay connected
                        // while active window pieces (TOP_PRIORITY..THREE + deadlines) strictly preempt them!
                        for (p in firstFilePiece..lastFilePiece) {
                            if (pinnedPieces.get(p) || hasPieceReady(handle, p)) continue
                            if (p < behindCutoffPiece || p > windowEndPiece) {
                                if (p < curPrios.size && curPrios[p] != Priority.LOW) {
                                    handle.piecePriority(p, Priority.LOW)
                                    handle.resetPieceDeadline(p)
                                }
                            } else if (p > playheadPiece + 8) {
                                handle.resetPieceDeadline(p)
                            }
                        }

                        var readyInPreBuffer = 0
                        val totalInPreBuffer = (preBufferEndPiece - playheadPiece + 1).coerceAtLeast(1)
                        for (p in playheadPiece..windowEndPiece) {
                            if (hasPieceReady(handle, p)) {
                                if (p <= preBufferEndPiece) readyInPreBuffer++
                                continue
                            }
                            val dist = p - playheadPiece
                            val targetPrio = when {
                                dist <= 4 -> Priority.TOP_PRIORITY
                                p <= preBufferEndPiece -> Priority.SIX
                                dist <= 20 -> Priority.FIVE
                                dist <= 32 -> Priority.DEFAULT
                                else -> Priority.THREE
                            }
                            if (p < curPrios.size && curPrios[p] != targetPrio) {
                                handle.piecePriority(p, targetPrio)
                            }
                            if (dist == 0) {
                                handle.setPieceDeadline(p, 0)
                            } else if (dist <= 6) {
                                handle.setPieceDeadline(p, dist * 85)
                            }
                        }
                        pct = ((readyInPreBuffer * 100) / totalInPreBuffer).coerceIn(0, 100)
                    }

                    val st = handle.status()
                    val knownSeeds = maxOf(st.numSeeds(), st.listSeeds(), st.numComplete())
                    val now = System.currentTimeMillis()
                    if (st.numSeeds() < 8 && now - lastSwarmPokeMs >= 5_000L) {
                        lastSwarmPokeMs = now
                        runCatching { handle.forceDHTAnnounce() }
                        runCatching { handle.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL) }
                    }

                    _stats.value = SwarmStats(
                        statusText = if (isProbingContainerOnly) "Seeking to resume point..." else "Streaming Swarm",
                        downloadRateBps = st.downloadPayloadRate(),
                        peers = st.numPeers(),
                        seeds = st.numSeeds(),
                        totalSeeds = knownSeeds,
                        windowBufferPct = pct,
                        isReady = true,
                        fileName = files.fileName(selectedFileIdx),
                        fileSize = fSize
                    )
                }
            } catch (_: Throwable) {
            }
            delay(220L)
        }
    }

    private fun hasPieceReady(handle: TorrentHandle, piece: Int): Boolean {
        synchronized(pieceWaitLock) {
            if (completedPieces.get(piece)) return true
        }
        val has = handle.isValid && handle.havePiece(piece)
        if (has) {
            synchronized(pieceWaitLock) {
                completedPieces.set(piece)
            }
        }
        return has
    }

    @Throws(IOException::class)
    fun awaitMetadataReady(): Long {
        try {
            while (!isStopped) {
                if (readyLatch.await(250, TimeUnit.MILLISECONDS)) break
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted waiting for torrent metadata")
        }
        if (isStopped) throw IOException("Torrent engine stopped")
        startupError?.let { throw IOException(it) }
        if (selectedFileSize <= 0L) throw IOException("Invalid video file size in torrent")
        return selectedFileSize
    }

    @Throws(IOException::class)
    fun readAt(filePosition: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val totalSize = selectedFileSize
        if (filePosition >= totalSize) return -1

        val jumpedFar = abs(filePosition - lastReadAtOffset) > 1_500_000L
        lastReadAtOffset = filePosition
        currentReadOffset = filePosition

        val chunkIndex = filePosition / CHUNK_SIZE
        val chunkStart = chunkIndex * CHUNK_SIZE
        val chunkLen = minOf(CHUNK_SIZE.toLong(), totalSize - chunkStart).toInt()
        val offsetInChunk = (filePosition - chunkStart).toInt()
        val bytesToCopy = minOf(length, chunkLen - offsetInChunk)

        val chunkBytes = chunkCache.get(chunkIndex) ?: loadChunkFromTorrent(chunkIndex, chunkStart, chunkLen, jumpedFar)
        System.arraycopy(chunkBytes, offsetInChunk, target, offset, bytesToCopy)
        return bytesToCopy
    }

    @Throws(IOException::class)
    private fun loadChunkFromTorrent(
        chunkIndex: Long,
        chunkStart: Long,
        chunkLen: Int,
        clearStaleDeadlinesFirst: Boolean
    ): ByteArray {
        val handle = torrentHandle ?: throw IOException("Torrent handle unavailable")
        val ti = torrentInfo ?: throw IOException("Torrent info unavailable")
        val files: FileStorage = ti.files()

        val firstPiece = files.mapFile(selectedFileIdx, chunkStart, 1).piece()
        val lastPiece = files.mapFile(selectedFileIdx, chunkStart + chunkLen - 1L, 1).piece()
        val lookaheadCap = minOf(lastFilePiece, lastPiece + 4)

        if (clearStaleDeadlinesFirst) {
            pendingSeekReset = false
            runCatching { handle.clearPieceDeadlines() }
            applyPinnedHeaderFooterDeadlines(handle)
        }

        // Immediately prioritize demanded chunk + next 4 lookahead pieces (strictly within selectedFileIdx!)
        for (p in firstPiece..lookaheadCap) {
            if (!hasPieceReady(handle, p)) {
                handle.piecePriority(p, Priority.TOP_PRIORITY)
                val dist = (p - firstPiece).coerceAtLeast(0)
                handle.setPieceDeadline(p, dist * 65)
            }
        }

        try {
            for (p in firstPiece..lastPiece) {
                demandedPiece = p
                while (!hasPieceReady(handle, p)) {
                    if (isStopped || !handle.isValid) throw IOException("Torrent session closed")
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, 0)
                    synchronized(pieceWaitLock) {
                        try {
                            pieceWaitLock.wait(90L)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw InterruptedIOException()
                        }
                    }
                }
            }
        } finally {
            demandedPiece = -1
        }

        val buf = ByteArray(chunkLen)
        synchronized(rafLock) {
            val targetFile = selectedFileOnDisk ?: throw IOException("Target video file missing")
            if (raf == null) {
                var retries = 0
                while (!targetFile.exists() && retries < 30 && !isStopped) {
                    Thread.sleep(30L)
                    retries++
                }
                raf = RandomAccessFile(targetFile, "r")
            }
            val r = raf!!
            r.seek(chunkStart)
            r.readFully(buf, 0, chunkLen)
        }
        chunkCache.put(chunkIndex, buf)
        return buf
    }

    fun buildStreamUri(): Uri = Uri.parse("torrent://stream/$selectedFileIdx")

    private fun stopSynchronously() {
        if (isStopped) return
        isStopped = true
        synchronized(pieceWaitLock) {
            pieceWaitLock.notifyAll()
        }
        readyLatch.countDown()
        tickerJob?.cancel()
        scope.cancel()

        synchronized(rafLock) {
            runCatching { raf?.close() }
            raf = null
        }
        chunkCache.evictAll()

        synchronized(sessionLock) {
            if (activeEngine === this) {
                activeEngine = null
            }
            runCatching {
                sharedSession.removeListener(alertListener)
                torrentHandle?.let { h ->
                    if (h.isValid) {
                        sharedSession.remove(h, SessionHandle.DELETE_FILES)
                    }
                }
                if (sharedSession.isRunning) {
                    val stateBytes = sharedSession.saveState()
                    if (stateBytes != null && stateBytes.isNotEmpty()) {
                        File(appContext.filesDir, "dht_state.bin").writeBytes(stateBytes)
                    }
                }
            }
        }
        runCatching {
            saveDir.deleteRecursively()
        }
    }

    fun stopAndCleanup() {
        if (isStopped) return
        Thread {
            stopSynchronously()
        }.start()
    }
}
