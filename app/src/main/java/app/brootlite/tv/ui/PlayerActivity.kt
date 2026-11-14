package app.brootlite.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.brootlite.tv.BrootLiteApp
import app.brootlite.tv.creators.TwitchAdFreeHlsParserFactory
import app.brootlite.tv.data.AppPrefs
import app.brootlite.tv.data.AudioDspConfig
import app.brootlite.tv.data.AudioEqPreset
import app.brootlite.tv.data.ExternalSubtitle
import app.brootlite.tv.data.MetaItem
import app.brootlite.tv.data.RecommendationEngine
import app.brootlite.tv.data.ResolvedBingePlayback
import app.brootlite.tv.data.StreamSimilarityMatcher
import app.brootlite.tv.data.StremioApiClient
import app.brootlite.tv.data.VideoEpisode
import app.brootlite.tv.data.WatchHistoryEntry
import app.brootlite.tv.jellyfin.JellyfinApiClient
import app.brootlite.tv.jellyfin.JellyfinIds
import app.brootlite.tv.jellyfin.JellyfinServerProfile
import app.brootlite.tv.jellyfin.JfPlaybackSession
import app.brootlite.tv.kodi.PyAddonClient
import app.brootlite.tv.torrent.SlidingWindowEngine
import app.brootlite.tv.torrent.TorrentDataSource
import app.brootlite.tv.youtube.SponsorSegment
import app.brootlite.tv.youtube.YouTubeApiClient
import app.brootlite.tv.youtube.YouTubeAuthContext
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.math.log10

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    @kotlinx.serialization.Serializable
    data class QualityChoice(
        val label: String,
        val directUrl: String? = null,
        val dashMpdXml: String? = null,
        val mimeType: String? = null,
        val maxHeight: Int? = null
    )

    @kotlinx.serialization.Serializable
    data class LaunchPayload(
        val title: String,
        val subtitle: String = "",
        val itemId: String = "",
        val videoId: String = "",
        val contentType: String = "movie",
        val poster: String = "",
        val background: String = "",
        val directUrl: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val ignoreSsl: Boolean = false,
        val magnetUri: String? = null,
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val trackers: List<String> = emptyList(),
        val resumePositionMs: Long = 0L,
        val bingeGroup: String = "",
        val streamHint: String = "",
        val addonName: String = "",
        val isMirror: Boolean = false,
        val presetSubtitles: List<String> = emptyList(),
        val labeledSubtitles: List<ExternalSubtitle> = emptyList(),
        val seriesEpisodes: List<VideoEpisode> = emptyList(),
        // YouTube Adaptive DASH, SponsorBlock & Watch History
        val dashMpdXml: String? = null,
        val fallbackDirectUrl: String? = null,
        val youtubeVideoId: String? = null,
        val ytPlaybackTrackingUrl: String? = null,
        val ytWatchtimeTrackingUrl: String? = null,
        val ytCpn: String = "",
        // Selectable Quality Options (YouTube / Twitch / Odysee / Rumble)
        val qualityChoices: List<QualityChoice> = emptyList(),
        val activeQualityLabel: String = "",
        val initialMaxVideoHeight: Int = 0,
        // Twitch Live Ad-Free HLS
        val isTwitchLive: Boolean = false,
        val twitchChannelLogin: String? = null,
        val twitchBackupMasterUrl: String? = null,
        // Jellyfin Native Playback, Intro Skip, Chapters & Session Reporting
        val jellyfinServerId: String? = null,
        val jellyfinItemId: String? = null,
        val jellyfinSession: JfPlaybackSession? = null
    )

    private enum class SkipSegmentAction {
        NONE,
        INTRO,
        CREDITS
    }

    companion object {
        private const val EXTRA_PAYLOAD = "player_payload_json"
        private const val EXTRA_PAYLOAD_FILE = "player_payload_file"
        private const val BINDER_SAFE_CHAR_LIMIT = 32_000

        // Outlives Activity.onDestroy() so final watchtime / progress pings complete reliably
        private val stopReportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun start(context: Context, payload: LaunchPayload) {
            PyAddonClient.killAddonProcessNow(context)
            val encoded = AppPrefs.json.encodeToString(payload)
            val intent = Intent(context, PlayerActivity::class.java).apply {
                if (encoded.length > BINDER_SAFE_CHAR_LIMIT) {
                    val file = File(context.cacheDir, "active_launch_payload.json")
                    runCatching { file.writeText(encoded, Charsets.UTF_8) }
                    putExtra(EXTRA_PAYLOAD_FILE, file.absolutePath)
                } else {
                    putExtra(EXTRA_PAYLOAD, encoded)
                }
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }

    private lateinit var payload: LaunchPayload
    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var torrentEngine: SlidingWindowEngine? = null
    private var externalSubs: List<ExternalSubtitle> = emptyList()

    // Active mutable stream / quality / session state
    private var activeDirectUrl: String? = null
    private var activeDashMpdXml: String? = null
    private var activeQualityLabel: String = ""
    private var currentMaxVideoHeight: Int = 0
    private var isPlayingDashMpd: Boolean = false
    private var lastKnownPosMs: Long = 0L
    private var lastKnownDurMs: Long = 0L
    private var tickerTickCount: Int = 0

    // Jellyfin state & reporting guards
    private var activeJfProfile: JellyfinServerProfile? = null
    private var activeJfSession: JfPlaybackSession? = null
    private var activeJfItemId: String = ""
    private var jfReportedStart: Boolean = false
    private var jfReportedStop: Boolean = false
    private var retriedJellyfinTranscode: Boolean = false
    private var activeSkipAction: SkipSegmentAction = SkipSegmentAction.NONE

    // YouTube SponsorBlock & Watch History state
    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private val ignoredSponsorUuids = HashSet<String>()
    private var lastSponsorUndoOriginMs: Long = -1L
    private var lastSponsorUndoUuid: String? = null
    private var lastSponsorUndoUntilMs: Long = 0L
    private var ytReportedStart: Boolean = false
    private var ytReportedStop: Boolean = false

    // Fallback flags
    private var retriedDashFallback: Boolean = false
    private var retriedDirectAsHls: Boolean = false
    private var retriedDirectPermissiveSsl: Boolean = false

    // Series Binge Autoplay & Pre-Resolution state
    private var nextEpResolveJob: Job? = null
    private var preResolvedNextEpisode: ResolvedBingePlayback? = null
    private var nextEpResolveCompleted: Boolean = false
    private var bingeCardDismissedForCredits: Boolean = false
    private var autoCountdownCancelled: Boolean = false

    // Movie / Finale Credits Recommendation Gallery state
    private var recFetchJob: Job? = null
    private var recFetchCompleted: Boolean = false
    private var recommendedItems: List<MetaItem> = emptyList()
    private var recResolveJob: Job? = null
    private var creditsRecDismissedDuringCredits: Boolean = false

    // Guard against duplicate activity transitions
    private var isLaunchingTransition: Boolean = false

    private lateinit var playerView: PlayerView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var loadingLabel: TextView
    private lateinit var osdRoot: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var swarmBadge: TextView
    private lateinit var timeText: TextView
    private lateinit var scrubPreviewPill: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timelineContainer: LinearLayout
    private lateinit var playPauseBtn: TextView
    private lateinit var audioBoostBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var chaptersBtn: TextView
    private lateinit var nextEpBtn: TextView
    private lateinit var skipSegmentBtn: TextView

    private var activeAudioDsp: AudioDspConfig = AudioDspConfig()
    private lateinit var audioDspProcessor: ProjectorAudioDspProcessor

    // Binge "Keep Watching / Stream Next" bottom-right card views
    private lateinit var bingeNextCard: LinearLayout
    private lateinit var bingeEyebrowText: TextView
    private lateinit var bingeTitleText: TextView
    private lateinit var bingeSourceText: TextView
    private lateinit var bingeStreamNextBtn: TextView
    private lateinit var bingeWatchCreditsBtn: TextView

    // Movie / Finale Credits "Next Recommended" bottom gallery views
    private lateinit var creditsRecOverlay: LinearLayout
    private lateinit var creditsRecTitleText: TextView
    private lateinit var creditsRecSpinner: ProgressBar
    private lateinit var creditsRecStatusText: TextView
    private lateinit var creditsWatchBtn: TextView
    private lateinit var creditsExitBtn: TextView
    private lateinit var creditsRecRecycler: RecyclerView
    private lateinit var creditsRecAdapter: CreditsRecAdapter

    private var osdHideJob: Job? = null
    private val OSD_AUTO_HIDE_MS = 5_000L
    private var uiTickerJob: Job? = null
    private var hasAppliedInitialSeek = false

    // Timeline Scrubbing State Machine (zero seekTo thrashing + post-seek snap-back guard)
    private var isScrubbing: Boolean = false
    private var isTouchTrackingScrub: Boolean = false
    private var scrubOriginPosMs: Long = 0L
    private var scrubTargetMs: Long = 0L
    private var scrubRepeatCount: Int = 0
    private var lastScrubInputAtMs: Long = 0L
    private var scrubCommitJob: Job? = null
    private var pendingSeekTargetMs: Long = -1L
    private var pendingSeekExpireAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val payloadFilePath = intent.getStringExtra(EXTRA_PAYLOAD_FILE)
        val rawJson = if (!payloadFilePath.isNullOrBlank()) {
            runCatching { File(payloadFilePath).readText(Charsets.UTF_8) }.getOrNull()
        } else {
            intent.getStringExtra(EXTRA_PAYLOAD)
        }
        if (rawJson.isNullOrBlank()) {
            finish()
            return
        }
        payload = runCatching { AppPrefs.json.decodeFromString<LaunchPayload>(rawJson) }
            .getOrElse {
                finish()
                return
            }

        activeDirectUrl = payload.directUrl
        activeDashMpdXml = payload.dashMpdXml
        activeQualityLabel = payload.activeQualityLabel
        currentMaxVideoHeight = payload.initialMaxVideoHeight
        activeJfProfile = payload.jellyfinServerId
            ?.takeIf { it.isNotBlank() }
            ?.let { AppPrefs.getJellyfinServer(this, it) }
        activeJfSession = payload.jellyfinSession
        activeJfItemId = payload.jellyfinItemId?.takeIf { it.isNotBlank() }
            ?: payload.videoId.ifBlank { payload.itemId }
        activeAudioDsp = AppPrefs.getAudioDspConfig(this)
        audioDspProcessor = ProjectorAudioDspProcessor(activeAudioDsp)

        buildPlayerUi()
        initializePlayback()
    }

    private fun buildPlayerUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false
            keepScreenOn = true
            subtitleView?.setStyle(
                CaptionStyleCompat(
                    Color.WHITE,
                    Color.parseColor("#880D1118"),
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                    Color.BLACK,
                    XirtusFonts.semiBold(this@PlayerActivity)
                )
            )
        }
        root.addView(playerView)

        // Xirtus Frosted Glass Buffering Panel
        loadingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setPadding(
                TvUiKit.dp(this@PlayerActivity, 28),
                TvUiKit.dp(this@PlayerActivity, 20),
                TvUiKit.dp(this@PlayerActivity, 28),
                TvUiKit.dp(this@PlayerActivity, 20)
            )
            background = TvUiKit.createGlassPanelDrawable(
                this@PlayerActivity,
                radiusDp = 20f,
                fillColor = Color.parseColor("#EB0F141E"),
                strokeColor = Color.parseColor("#2BFFFFFF")
            )
        }
        loadingPanel.addView(ProgressBar(this))
        loadingLabel = TextView(this).apply {
            text = "Preparing stream..."
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            typeface = XirtusFonts.monoRegular(this@PlayerActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(0, TvUiKit.dp(this@PlayerActivity, 10), 0, 0)
        }
        loadingPanel.addView(loadingLabel)
        root.addView(loadingPanel)

        // Custom D-Pad OSD overlay
        osdRoot = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.VISIBLE
        }

        // Top OSD header
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            setPadding(
                TvUiKit.dp(this@PlayerActivity, 32),
                TvUiKit.dp(this@PlayerActivity, 22),
                TvUiKit.dp(this@PlayerActivity, 32),
                TvUiKit.dp(this@PlayerActivity, 28)
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#EB0D1118"), Color.TRANSPARENT)
            )
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TvUiKit.eyebrow(this, "XIRTUS / HARDWARE PLAYBACK"))
        titleText = TextView(this).apply {
            text = listOfNotNull(payload.title, payload.subtitle.takeIf { it.isNotBlank() }).joinToString("  ·  ")
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = XirtusFonts.semiBold(this@PlayerActivity)
            letterSpacing = -0.015f
            maxLines = 1
        }
        titleCol.addView(titleText)

        swarmBadge = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = XirtusFonts.monoMedium(this@PlayerActivity)
            setPadding(
                TvUiKit.dp(this@PlayerActivity, 12),
                TvUiKit.dp(this@PlayerActivity, 6),
                TvUiKit.dp(this@PlayerActivity, 12),
                TvUiKit.dp(this@PlayerActivity, 6)
            )
            background = TvUiKit.createGlassPanelDrawable(this@PlayerActivity, radiusDp = 10f)
            visibility = View.GONE
        }
        topBar.addView(titleCol)
        topBar.addView(swarmBadge)
        osdRoot.addView(topBar)

        // Bottom OSD controls
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setPadding(
                TvUiKit.dp(this@PlayerActivity, 32),
                TvUiKit.dp(this@PlayerActivity, 28),
                TvUiKit.dp(this@PlayerActivity, 32),
                TvUiKit.dp(this@PlayerActivity, 24)
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#F00D1118"), Color.TRANSPARENT)
            )
        }

        scrubPreviewPill = TextView(this).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.monoMedium(this@PlayerActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TvUiKit.dp(this@PlayerActivity, 8)
            }
            setPadding(
                TvUiKit.dp(this@PlayerActivity, 14),
                TvUiKit.dp(this@PlayerActivity, 7),
                TvUiKit.dp(this@PlayerActivity, 14),
                TvUiKit.dp(this@PlayerActivity, 7)
            )
            background = TvUiKit.createGlassPanelDrawable(
                this@PlayerActivity,
                radiusDp = 12f,
                fillColor = Color.parseColor("#F00C1320"),
                strokeColor = Color.parseColor("#8838BDF8")
            )
            visibility = View.GONE
        }
        bottomBar.addView(scrubPreviewPill)

        val seekBarId = View.generateViewId()
        val playPauseId = View.generateViewId()

        timelineContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                TvUiKit.dp(this@PlayerActivity, 10),
                TvUiKit.dp(this@PlayerActivity, 6),
                TvUiKit.dp(this@PlayerActivity, 12),
                TvUiKit.dp(this@PlayerActivity, 6)
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TvUiKit.dp(this@PlayerActivity, 12).toFloat()
                setColor(Color.TRANSPARENT)
            }
        }
        seekBar = SeekBar(this).apply {
            id = seekBarId
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            max = 1000
            isFocusable = true
            isFocusableInTouchMode = false
            nextFocusDownId = playPauseId
            nextFocusUpId = seekBarId
            progressTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            secondaryProgressTintList = ColorStateList.valueOf(Color.parseColor("#5594A3B8"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#F8FAFC"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#38FFFFFF"))

            setOnFocusChangeListener { _, hasFocus ->
                progressTintList = ColorStateList.valueOf(
                    if (hasFocus) Color.parseColor("#7DD3FC") else Color.parseColor("#38BDF8")
                )
                thumbTintList = ColorStateList.valueOf(
                    if (hasFocus) Color.parseColor("#38BDF8") else Color.parseColor("#F8FAFC")
                )
                scaleY = if (hasFocus) 1.18f else 1.0f
                timelineContainer.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = TvUiKit.dp(this@PlayerActivity, 12).toFloat()
                    if (hasFocus) {
                        setColor(Color.parseColor("#2438BDF8"))
                        setStroke(TvUiKit.dp(this@PlayerActivity, 1), Color.parseColor("#8838BDF8"))
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
            }

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    val p = exoPlayer ?: return
                    scrubCommitJob?.cancel()
                    isTouchTrackingScrub = true
                    if (!isScrubbing) {
                        val base = effectiveDisplayPositionMs(p)
                        isScrubbing = true
                        scrubOriginPosMs = base
                        scrubTargetMs = base
                    }
                    osdHideJob?.cancel()
                }

                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || !isTouchTrackingScrub) return
                    val dur = exoPlayer?.duration?.coerceAtLeast(0L) ?: lastKnownDurMs
                    if (dur > 0L) {
                        scrubTargetMs = ((progress.toLong() * dur) / 1000L).coerceIn(0L, dur)
                        updateScrubUi(dur)
                    }
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (!isTouchTrackingScrub) return
                    isTouchTrackingScrub = false
                    commitScrubNow()
                }
            })
        }
        timeText = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.monoRegular(this@PlayerActivity)
            setPadding(TvUiKit.dp(this@PlayerActivity, 14), 0, 0, 0)
        }
        timelineContainer.addView(seekBar)
        timelineContainer.addView(timeText)
        bottomBar.addView(timelineContainer)

        val btnScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(TvUiKit.dp(this@PlayerActivity, 2), TvUiKit.dp(this@PlayerActivity, 12), TvUiKit.dp(this@PlayerActivity, 16), TvUiKit.dp(this@PlayerActivity, 4))
        }

        fun addOsdButton(btn: TextView) {
            btn.nextFocusUpId = seekBarId
            btn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = TvUiKit.dp(this@PlayerActivity, 10) }
            btnRow.addView(btn)
        }

        playPauseBtn = TvUiKit.createPillButton(this, "Pause", selected = true) {
            togglePlayPause()
        }.apply {
            id = playPauseId
        }
        addOsdButton(playPauseBtn)
        addOsdButton(TvUiKit.createPillButton(this, "−10s") { seekRelative(-10_000L) })
        addOsdButton(TvUiKit.createPillButton(this, "+30s") { seekRelative(30_000L) })

        audioBoostBtn = TvUiKit.createPillButton(
            this,
            activeAudioDsp.osdButtonLabel,
            selected = !activeAudioDsp.isDefault
        ) {
            showAudioEqualizerDialog()
        }
        addOsdButton(audioBoostBtn)

        addOsdButton(TvUiKit.createPillButton(this, "Audio Track") { showAudioTrackPicker() })
        addOsdButton(TvUiKit.createPillButton(this, "Subtitles") { showSubtitlePicker() })

        qualityBtn = TvUiKit.createPillButton(this, "Quality") {
            showQualityPicker()
        }.apply {
            visibility = if (payload.qualityChoices.size > 1) View.VISIBLE else View.GONE
        }
        addOsdButton(qualityBtn)

        chaptersBtn = TvUiKit.createPillButton(this, "Chapters") {
            showChaptersPicker()
        }.apply {
            visibility = if ((activeJfSession?.chapters?.size ?: 0) > 1) View.VISIBLE else View.GONE
        }
        addOsdButton(chaptersBtn)

        nextEpBtn = TvUiKit.createPillButton(this, "Next Episode →") {
            launchNextEpisodeSeamless()
        }.apply {
            visibility = if (findNextEpisode() != null) View.VISIBLE else View.GONE
        }
        addOsdButton(nextEpBtn)

        btnScroll.addView(btnRow)
        bottomBar.addView(btnScroll)
        osdRoot.addView(bottomBar)
        root.addView(osdRoot)

        // Floating Skip Intro / Skip Credits frosted-glass pill (visible even when osdRoot is GONE)
        skipSegmentBtn = TvUiKit.createPillButton(this, "Skip Intro ⏭", selected = true) {
            onSkipSegmentClicked()
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = TvUiKit.dp(this@PlayerActivity, 34)
                bottomMargin = TvUiKit.dp(this@PlayerActivity, 104)
            }
            elevation = 16f
            visibility = View.GONE
        }
        root.addView(skipSegmentBtn)

        // Bottom-Right Series "Keep Watching / Stream Next" Binge Card (0 MB bitmap bloat)
        bingeNextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                TvUiKit.dp(this@PlayerActivity, 356),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = TvUiKit.dp(this@PlayerActivity, 34)
                bottomMargin = TvUiKit.dp(this@PlayerActivity, 34)
            }
            setPadding(
                TvUiKit.dp(this@PlayerActivity, 18),
                TvUiKit.dp(this@PlayerActivity, 15),
                TvUiKit.dp(this@PlayerActivity, 18),
                TvUiKit.dp(this@PlayerActivity, 15)
            )
            background = TvUiKit.createGlassPanelDrawable(
                this@PlayerActivity,
                radiusDp = 18f,
                fillColor = Color.parseColor("#EE0E1520"),
                strokeColor = Color.parseColor("#3D38BDF8")
            )
            elevation = 18f
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        bingeEyebrowText = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = XirtusFonts.monoMedium(this@PlayerActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            letterSpacing = 0.08f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bingeTitleText = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            typeface = XirtusFonts.semiBold(this@PlayerActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            letterSpacing = -0.01f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, TvUiKit.dp(this@PlayerActivity, 4), 0, TvUiKit.dp(this@PlayerActivity, 3))
        }
        bingeSourceText = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            typeface = XirtusFonts.monoRegular(this@PlayerActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val bingeActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, TvUiKit.dp(this@PlayerActivity, 11), 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        val streamNextId = View.generateViewId()
        val watchCreditsBingeId = View.generateViewId()
        bingeStreamNextBtn = TvUiKit.createPillButton(this, "Stream Next ▶", selected = true, compact = true) {
            launchNextEpisodeSeamless()
        }.apply {
            id = streamNextId
            nextFocusRightId = watchCreditsBingeId
            nextFocusLeftId = streamNextId
        }
        bingeWatchCreditsBtn = TvUiKit.createPillButton(this, "Watch Credits", selected = false, compact = true) {
            dismissBingeCardForCredits()
        }.apply {
            id = watchCreditsBingeId
            nextFocusLeftId = streamNextId
            nextFocusRightId = watchCreditsBingeId
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = TvUiKit.dp(this@PlayerActivity, 8)
            }
        }
        bingeActionsRow.addView(bingeStreamNextBtn)
        bingeActionsRow.addView(bingeWatchCreditsBtn)
        bingeNextCard.addView(bingeEyebrowText)
        bingeNextCard.addView(bingeTitleText)
        bingeNextCard.addView(bingeSourceText)
        bingeNextCard.addView(bingeActionsRow)
        root.addView(bingeNextCard)

        // Bottom Frosted-Glass "NEXT RECOMMENDED" Credits Gallery (Movies / Series Finales / VODs)
        creditsRecOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                marginStart = TvUiKit.dp(this@PlayerActivity, 28)
                marginEnd = TvUiKit.dp(this@PlayerActivity, 28)
                bottomMargin = TvUiKit.dp(this@PlayerActivity, 22)
            }
            setPadding(
                TvUiKit.dp(this@PlayerActivity, 18),
                TvUiKit.dp(this@PlayerActivity, 14),
                TvUiKit.dp(this@PlayerActivity, 18),
                TvUiKit.dp(this@PlayerActivity, 14)
            )
            background = TvUiKit.createGlassPanelDrawable(
                this@PlayerActivity,
                radiusDp = 16f,
                fillColor = Color.parseColor("#EE0E141E"),
                strokeColor = Color.parseColor("#2EFFFFFF")
            )
            elevation = 18f
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }

        val recHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, TvUiKit.dp(this@PlayerActivity, 10))
            clipChildren = false
            clipToPadding = false
        }
        val recTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = TvUiKit.dp(this@PlayerActivity, 12)
            }
        }
        val recEyebrow = TextView(this).apply {
            text = "CREDITS / KEEP STREAMING"
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = XirtusFonts.monoMedium(this@PlayerActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.12f
            maxLines = 1
        }
        val recTitleStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, TvUiKit.dp(this@PlayerActivity, 2), 0, 0)
        }
        creditsRecTitleText = TextView(this).apply {
            text = "Next Recommended for ${payload.title}"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            typeface = XirtusFonts.semiBold(this@PlayerActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            letterSpacing = -0.015f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        creditsRecSpinner = ProgressBar(this).apply {
            val sz = TvUiKit.dp(this@PlayerActivity, 15)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = TvUiKit.dp(this@PlayerActivity, 12)
            }
            visibility = View.GONE
        }
        creditsRecStatusText = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = XirtusFonts.monoRegular(this@PlayerActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(this@PlayerActivity, 7), 0, 0, 0)
            visibility = View.GONE
        }
        recTitleStatusRow.addView(creditsRecTitleText)
        recTitleStatusRow.addView(creditsRecSpinner)
        recTitleStatusRow.addView(creditsRecStatusText)
        recTitleCol.addView(recEyebrow)
        recTitleCol.addView(recTitleStatusRow)

        val watchCreditsRecId = View.generateViewId()
        val exitPlayerRecId = View.generateViewId()
        creditsWatchBtn = TvUiKit.createPillButton(this, "Watch Credits", compact = true) {
            dismissCreditsRecForCredits()
        }.apply {
            id = watchCreditsRecId
            nextFocusRightId = exitPlayerRecId
            nextFocusLeftId = watchCreditsRecId
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = TvUiKit.dp(this@PlayerActivity, 8)
            }
        }
        creditsExitBtn = TvUiKit.createPillButton(this, "Exit Player", compact = true) {
            finish()
        }.apply {
            id = exitPlayerRecId
            nextFocusLeftId = watchCreditsRecId
            nextFocusRightId = exitPlayerRecId
        }
        recHeaderRow.addView(recTitleCol)
        recHeaderRow.addView(creditsWatchBtn)
        recHeaderRow.addView(creditsExitBtn)

        creditsRecAdapter = CreditsRecAdapter(
            onClick = { item -> onRecommendedCardClicked(item) },
            onLongClick = { item -> openRecommendedDetail(item) }
        )
        creditsRecRecycler = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutManager = LinearLayoutManager(this@PlayerActivity, LinearLayoutManager.HORIZONTAL, false).apply {
                initialPrefetchItemCount = 8
            }
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            adapter = creditsRecAdapter
        }

        creditsRecOverlay.addView(recHeaderRow)
        creditsRecOverlay.addView(creditsRecRecycler)
        root.addView(creditsRecOverlay)

        setContentView(root)
        scheduleOsdAutoHide()
    }

    private fun initializePlayback() {
        val isTorrent = !payload.magnetUri.isNullOrBlank() || !payload.infoHash.isNullOrBlank()
        if (!isTorrent && activeDashMpdXml.isNullOrBlank() && activeDirectUrl.isNullOrBlank()) {
            Toast.makeText(this@PlayerActivity, "No playable URL or infoHash", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Configure top-right telemetry / source badge
        when {
            !activeJfSession?.videoSummaryLabel.isNullOrBlank() -> {
                swarmBadge.text = activeJfSession!!.videoSummaryLabel
                swarmBadge.visibility = View.VISIBLE
            }
            !payload.youtubeVideoId.isNullOrBlank() -> {
                swarmBadge.text = "YouTube · Ad-Free"
                swarmBadge.visibility = View.VISIBLE
            }
            payload.isTwitchLive -> {
                swarmBadge.text = "Twitch · Ad-Free Live"
                swarmBadge.visibility = View.VISIBLE
            }
        }

        when {
            isTorrent -> {
                startTorrentPlayback()
            }
            !activeDashMpdXml.isNullOrBlank() -> {
                startDashMpdPlayback(
                    mpdXml = activeDashMpdXml!!,
                    startPosMs = payload.resumePositionMs,
                    allowShortSeek = false
                )
            }
            payload.isTwitchLive && !activeDirectUrl.isNullOrBlank() -> {
                startTwitchLiveHlsPlayback(activeDirectUrl!!)
            }
            else -> {
                startDirectHttpPlayback(
                    rawUrl = activeDirectUrl!!,
                    forceHls = false,
                    forcePermissiveSsl = payload.ignoreSsl || (activeJfSession?.ignoreSsl == true),
                    startPosMs = payload.resumePositionMs,
                    allowShortSeek = false
                )
            }
        }

        // Fetch SponsorBlock segments asynchronously for YouTube playback
        val ytId = payload.youtubeVideoId
        if (!ytId.isNullOrBlank() && AppPrefs.isSponsorBlockEnabled(this)) {
            lifecycleScope.launch {
                val fetchedSegments = runCatching {
                    YouTubeApiClient.fetchSponsorBlockSegments(ytId)
                }.getOrDefault(emptyList())
                if (fetchedSegments.isNotEmpty()) {
                    sponsorSegments = fetchedSegments
                    swarmBadge.text = "YouTube · Ad-Free · SB (${fetchedSegments.size})"
                    swarmBadge.visibility = View.VISIBLE
                }
            }
        }

        // Fetch external OpenSubtitles asynchronously in parallel so swarm / HTTP startup waits 0ms!
        if (payload.videoId.startsWith("tt") || payload.videoId.startsWith("kitsu:")) {
            lifecycleScope.launch {
                val addons = AppPrefs.getInstalledAddons(this@PlayerActivity)
                val fetched = runCatching {
                    StremioApiClient.fetchSubtitles(addons, payload.contentType, payload.videoId)
                }.getOrDefault(emptyList())
                if (fetched.isNotEmpty()) {
                    externalSubs = fetched
                    val p = exoPlayer
                    val curItem = p?.currentMediaItem
                    val curUri = curItem?.localConfiguration?.uri
                    if (p != null && curUri != null) {
                        val updatedItem = buildMediaItemWithSubtitles(
                            uri = curUri,
                            urlHint = curUri.toString(),
                            forceMimeType = curItem.localConfiguration?.mimeType
                        )
                        // Seamless Media3 metadata/subtitle configuration update without resetting buffer!
                        runCatching { p.replaceMediaItem(0, updatedItem) }
                    }
                }
            }
        }
    }

    private fun createConfiguredExoPlayer(dataSourceFactory: DataSource.Factory): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBufferDurationsMs(
                12_000,
                28_000,
                2_000,
                4_500
            )
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(audioDspProcessor))
                    .build()
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val selector = DefaultTrackSelector(this).apply {
            val params = buildUponParameters()
                .setPreferredAudioLanguage("en")
                .setTunnelingEnabled(false)
            if (currentMaxVideoHeight > 0) {
                params.setMaxVideoSize(currentMaxVideoHeight * 16 / 9 + 16, currentMaxVideoHeight)
            }
            setParameters(params)
        }
        trackSelector = selector

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                addListener(playerListener)
            }
    }

    private fun buildHttpBackedDataSourceFactory(
        rawUrl: String?,
        forcePermissiveSsl: Boolean = false
    ): Pair<String, DefaultDataSource.Factory> {
        val parsedKodi = if (!rawUrl.isNullOrBlank()) {
            PyAddonClient.parseKodiStreamUrl(rawUrl)
        } else {
            null
        }
        val mergedHeaders = LinkedHashMap<String, String>()
        fun putNormalizedHeader(k: String, v: String) {
            val existingKey = mergedHeaders.keys.firstOrNull { it.equals(k, ignoreCase = true) }
            if (existingKey != null) mergedHeaders.remove(existingKey)
            mergedHeaders[k] = v
        }
        payload.headers.forEach { (k, v) -> putNormalizedHeader(k, v) }
        activeJfSession?.headers?.forEach { (k, v) -> putNormalizedHeader(k, v) }
        parsedKodi?.headers?.forEach { (k, v) -> putNormalizedHeader(k, v) }

        val uaEntry = mergedHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
        val userAgent = uaEntry?.value ?: BrootLiteApp.DEFAULT_STREMIO_UA
        uaEntry?.key?.let { mergedHeaders.remove(it) }

        val usePermissive = forcePermissiveSsl ||
            payload.ignoreSsl ||
            (activeJfSession?.ignoreSsl == true) ||
            (parsedKodi?.ignoreSsl == true)

        val httpFactory: DataSource.Factory = if (usePermissive) {
            PermissiveOkHttpDataSource.Factory(mergedHeaders, userAgent)
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(22_000)
                .setDefaultRequestProperties(mergedHeaders)
        }
        val cleanUrl = parsedKodi?.cleanUrl ?: rawUrl.orEmpty()
        return cleanUrl to DefaultDataSource.Factory(this, httpFactory)
    }

    private fun startDashMpdPlayback(
        mpdXml: String,
        startPosMs: Long = payload.resumePositionMs,
        allowShortSeek: Boolean = false
    ) {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null

        isPlayingDashMpd = true
        val mpdFile = File(cacheDir, "yt_active_manifest.mpd")
        mpdFile.writeText(mpdXml, Charsets.UTF_8)

        val (_, dsFactory) = buildHttpBackedDataSourceFactory(
            rawUrl = null,
            forcePermissiveSsl = payload.ignoreSsl
        )
        val player = createConfiguredExoPlayer(dsFactory)
        exoPlayer = player
        playerView.player = player

        val mediaItem = buildMediaItemWithSubtitles(
            uri = Uri.fromFile(mpdFile),
            urlHint = mpdFile.name,
            forceMimeType = MimeTypes.APPLICATION_MPD
        )
        if (startPosMs > 2_000L || (allowShortSeek && startPosMs > 0L)) {
            hasAppliedInitialSeek = true
            player.setMediaItem(mediaItem, startPosMs)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = true
        startUiTicker()
    }

    private fun startTwitchLiveHlsPlayback(rawUrl: String) {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null

        isPlayingDashMpd = false
        val (cleanUrl, dsFactory) = buildHttpBackedDataSourceFactory(
            rawUrl = rawUrl,
            forcePermissiveSsl = payload.ignoreSsl
        )
        val player = createConfiguredExoPlayer(dsFactory)
        exoPlayer = player
        playerView.player = player

        val mediaItem = buildMediaItemWithSubtitles(
            uri = Uri.parse(cleanUrl),
            urlHint = cleanUrl,
            forceMimeType = MimeTypes.APPLICATION_M3U8
        )
        val hlsSource = HlsMediaSource.Factory(dsFactory)
            .setPlaylistParserFactory(
                TwitchAdFreeHlsParserFactory(
                    channelLogin = payload.twitchChannelLogin,
                    initialBackupMasterUrl = payload.twitchBackupMasterUrl
                )
            )
            .createMediaSource(mediaItem)
        player.setMediaSource(hlsSource)
        player.prepare()
        player.playWhenReady = true
        startUiTicker()
    }

    private fun startDirectHttpPlayback(
        rawUrl: String,
        forceHls: Boolean = false,
        forcePermissiveSsl: Boolean = false,
        startPosMs: Long = payload.resumePositionMs,
        allowShortSeek: Boolean = false
    ) {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null

        isPlayingDashMpd = false
        val (cleanUrl, dsFactory) = buildHttpBackedDataSourceFactory(
            rawUrl = rawUrl,
            forcePermissiveSsl = forcePermissiveSsl
        )
        val player = createConfiguredExoPlayer(dsFactory)
        exoPlayer = player
        playerView.player = player

        val mediaItem = buildMediaItemWithSubtitles(
            uri = Uri.parse(cleanUrl),
            urlHint = cleanUrl,
            forceMimeType = if (forceHls) MimeTypes.APPLICATION_M3U8 else null
        )
        if (startPosMs > 5_000L || (allowShortSeek && startPosMs > 0L)) {
            hasAppliedInitialSeek = true
            player.setMediaItem(mediaItem, startPosMs)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = true
        startUiTicker()
    }

    private fun startTorrentPlayback() {
        isPlayingDashMpd = false
        val magnet = payload.magnetUri ?: buildMagnetFromHash(payload.infoHash!!, payload.title, payload.trackers)
        val cfg = AppPrefs.getTorrentConfig(this)
        val isResuming = payload.resumePositionMs > 5_000L
        val matchedEp = payload.seriesEpisodes.firstOrNull { it.id == payload.videoId }
        val vidParts = payload.videoId.split(':')
        val epSeason = matchedEp?.season?.takeIf { it > 0 }
            ?: vidParts.getOrNull(1)?.toIntOrNull()
            ?: 0
        val epNum = matchedEp?.episode?.takeIf { it > 0 }
            ?: vidParts.getOrNull(2)?.toIntOrNull()
            ?: 0
        val engine = SlidingWindowEngine(
            context = this,
            magnetUri = magnet,
            requestedFileIdx = payload.fileIdx,
            extraTrackers = payload.trackers + app.brootlite.tv.data.StreamCandidate.DEFAULT_PUBLIC_TRACKERS,
            config = cfg,
            isResumingMidStream = isResuming,
            targetSeason = epSeason,
            targetEpisode = epNum
        )
        torrentEngine = engine
        swarmBadge.visibility = View.VISIBLE
        engine.start()

        lifecycleScope.launch {
            engine.stats.collect { st ->
                swarmBadge.text = st.formattedOverlay
                if (!st.isReady || exoPlayer?.playbackState == Player.STATE_BUFFERING) {
                    loadingLabel.text = "${st.statusText}\n${st.formattedOverlay}"
                }
            }
        }

        val httpFallback = PermissiveOkHttpDataSource.Factory(emptyMap(), BrootLiteApp.DEFAULT_STREMIO_UA)
        val torrentFactory = TorrentDataSource.Factory(engine)

        val routingFactory = DataSource.Factory {
            RoutingDataSource(
                torrentFactory.createDataSource(),
                DefaultDataSource(this@PlayerActivity, httpFallback.createDataSource())
            )
        }

        val player = createConfiguredExoPlayer(routingFactory)
        exoPlayer = player
        playerView.player = player

        val streamUri = Uri.parse("torrent://stream/0")
        val mediaItem = buildMediaItemWithSubtitles(streamUri, "")
        // Crucial: pass resumePositionMs BEFORE prepare() so ExoPlayer seeks right after reading container Cues/moov!
        if (isResuming) {
            hasAppliedInitialSeek = true
            player.setMediaItem(mediaItem, payload.resumePositionMs)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = true
        startUiTicker()
    }

    private fun buildMediaItemWithSubtitles(
        uri: Uri,
        urlHint: String,
        forceMimeType: String? = null
    ): MediaItem {
        val subConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        payload.presetSubtitles.forEachIndexed { idx, subUrl ->
            subConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    .setMimeType(guessSubtitleMime(subUrl))
                    .setLanguage("en")
                    .setLabel("Addon Sub ${idx + 1}")
                    .build()
            )
        }
        val sessionExternalSubs = activeJfSession?.subtitleTracks
            ?.mapNotNull { it.toExternalSubtitleOrNull() }
            .orEmpty()
        val combinedLabeled = LinkedHashMap<String, ExternalSubtitle>().apply {
            for (ext in payload.labeledSubtitles) {
                if (ext.url.isNotBlank()) put(ext.url, ext)
            }
            for (ext in sessionExternalSubs) {
                if (ext.url.isNotBlank()) putIfAbsent(ext.url, ext)
            }
        }.values
        combinedLabeled.forEach { ext ->
            val labelText = if (ext.addonName.isNotBlank() && !ext.lang.contains(ext.addonName, ignoreCase = true)) {
                "${ext.lang.ifBlank { "Sub" }} (${ext.addonName})"
            } else {
                ext.lang.ifBlank { ext.addonName.ifBlank { "Subtitle" } }
            }
            subConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(ext.url))
                    .setMimeType(guessSubtitleMime(ext.url))
                    .setLanguage(ext.lang.ifBlank { "und" })
                    .setLabel(labelText)
                    .build()
            )
        }
        externalSubs.forEachIndexed { idx, ext ->
            subConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(ext.url))
                    .setMimeType(guessSubtitleMime(ext.url))
                    .setLanguage(ext.lang)
                    .setLabel("${ext.lang.uppercase()} #${idx + 1} (${ext.addonName})")
                    .build()
            )
        }

        val builder = MediaItem.Builder()
            .setUri(uri)
            .setSubtitleConfigurations(subConfigs)

        val lower = urlHint.lowercase()
        when {
            forceMimeType != null -> builder.setMimeType(forceMimeType)
            lower.contains(".m3u8") || lower.contains("/hls/") || lower.contains("format=m3u8") ->
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.contains(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return builder.build()
    }

    private fun guessSubtitleMime(url: String): String {
        val low = url.lowercase(Locale.US)
        return when {
            low.contains(".vtt") || low.contains("fmt=vtt") || low.contains("format=vtt") || low.contains("webvtt") ->
                MimeTypes.TEXT_VTT
            low.contains(".ass") || low.contains(".ssa") ->
                MimeTypes.TEXT_SSA
            low.contains(".ttml") || low.contains("fmt=ttml") ->
                MimeTypes.APPLICATION_TTML
            else ->
                MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun buildMagnetFromHash(hash: String, name: String, trackers: List<String>): String {
        val encName = java.net.URLEncoder.encode(name, "UTF-8")
        val sb = StringBuilder("magnet:?xt=urn:btih:${hash.lowercase()}&dn=$encName")
        val merged = (trackers + app.brootlite.tv.data.StreamCandidate.DEFAULT_PUBLIC_TRACKERS).distinct()
        for (tr in merged) {
            val clean = tr.replace(Regex("^tracker:", RegexOption.IGNORE_CASE), "").trim()
            if (clean.startsWith("udp://", true) || clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
                sb.append("&tr=").append(java.net.URLEncoder.encode(clean, "UTF-8"))
            }
        }
        return sb.toString()
    }

    private fun refreshDynamicOsdButtons() {
        val videoTrackCount = exoPlayer?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_VIDEO }
            ?.sumOf { it.length } ?: 0
        qualityBtn.visibility = if (payload.qualityChoices.size > 1 || videoTrackCount > 1) {
            View.VISIBLE
        } else {
            View.GONE
        }
        chaptersBtn.visibility = if ((activeJfSession?.chapters?.size ?: 0) > 1) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            refreshDynamicOsdButtons()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    loadingPanel.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    loadingPanel.visibility = View.GONE
                    val p = exoPlayer
                    if (!hasAppliedInitialSeek && payload.resumePositionMs > 5_000L) {
                        hasAppliedInitialSeek = true
                        p?.seekTo(payload.resumePositionMs)
                    }
                    refreshDynamicOsdButtons()

                    if (p != null) {
                        val pos = p.currentPosition.coerceAtLeast(0L)
                        val dur = p.duration.coerceAtLeast(0L)
                        lastKnownPosMs = pos
                        lastKnownDurMs = dur

                        val jfProf = activeJfProfile
                        val jfSess = activeJfSession
                        if (!jfReportedStart && jfProf != null && jfSess != null && activeJfItemId.isNotBlank()) {
                            jfReportedStart = true
                            JellyfinApiClient.reportPlaybackStart(
                                context = this@PlayerActivity,
                                profile = jfProf,
                                itemId = activeJfItemId,
                                session = jfSess,
                                positionMs = pos,
                                isPaused = !p.isPlaying
                            )
                        }

                        if (!ytReportedStart && !payload.youtubeVideoId.isNullOrBlank()) {
                            ytReportedStart = true
                            reportYouTubeWatchProgressAsync(pos, dur)
                        }
                    }
                }
                Player.STATE_ENDED -> {
                    loadingPanel.visibility = View.GONE
                    saveProgressNow()
                    if (findNextEpisode() != null) {
                        if (!autoCountdownCancelled) {
                            launchNextEpisodeSeamless()
                        } else {
                            osdRoot.visibility = View.GONE
                            bingeCardDismissedForCredits = false
                            updateBingeAndRecommendationOverlays(
                                pos = lastKnownDurMs,
                                dur = lastKnownDurMs,
                                isEnded = true
                            )
                        }
                    } else if (!payload.isTwitchLive) {
                        osdRoot.visibility = View.GONE
                        creditsRecDismissedDuringCredits = false
                        if (recFetchCompleted) {
                            if (recommendedItems.isEmpty()) {
                                finish()
                            } else {
                                updateBingeAndRecommendationOverlays(
                                    pos = lastKnownDurMs,
                                    dur = lastKnownDurMs,
                                    isEnded = true
                                )
                            }
                        } else {
                            ensureRecommendationsFetched()
                        }
                    } else {
                        finish()
                    }
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playPauseBtn.text = if (isPlaying) "Pause" else "Play"
            val p = exoPlayer
            val pos = p?.currentPosition?.coerceAtLeast(0L) ?: lastKnownPosMs
            val dur = p?.duration?.coerceAtLeast(0L) ?: lastKnownDurMs
            if (pos > 0L) lastKnownPosMs = pos
            if (dur > 0L) lastKnownDurMs = dur

            val jfProf = activeJfProfile
            val jfSess = activeJfSession
            if (jfReportedStart && jfProf != null && jfSess != null && activeJfItemId.isNotBlank()) {
                JellyfinApiClient.reportPlaybackProgress(
                    context = this@PlayerActivity,
                    profile = jfProf,
                    itemId = activeJfItemId,
                    session = jfSess,
                    positionMs = pos,
                    isPaused = !isPlaying,
                    eventName = "pause"
                )
            }

            if (!isPlaying) {
                saveProgressNow()
                if (ytReportedStart) {
                    reportYouTubeWatchProgressAsync(pos, dur)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val resumeAt = exoPlayer?.currentPosition?.takeIf { it > 0L }
                ?: lastKnownPosMs.takeIf { it > 0L }
                ?: payload.resumePositionMs

            // 1. YouTube synthetic DASH MPD -> progressive muxedUrl fallback
            if (isPlayingDashMpd && !retriedDashFallback && !payload.fallbackDirectUrl.isNullOrBlank()) {
                retriedDashFallback = true
                isPlayingDashMpd = false
                activeDirectUrl = payload.fallbackDirectUrl
                loadingPanel.visibility = View.VISIBLE
                loadingLabel.text = "Switching to progressive stream..."
                startDirectHttpPlayback(
                    rawUrl = payload.fallbackDirectUrl!!,
                    forceHls = false,
                    forcePermissiveSsl = payload.ignoreSsl,
                    startPosMs = resumeAt,
                    allowShortSeek = true
                )
                return
            }

            // 2. Jellyfin DirectPlay / DirectStream -> Server HLS Transcode fallback
            val jfProf = activeJfProfile
            val jfSess = activeJfSession
            if (jfProf != null &&
                jfSess != null &&
                !jfSess.playMethod.equals("Transcode", ignoreCase = true) &&
                !retriedJellyfinTranscode &&
                activeJfItemId.isNotBlank()
            ) {
                retriedJellyfinTranscode = true
                loadingPanel.visibility = View.VISIBLE
                loadingLabel.text = "Switching to Jellyfin HLS Transcode..."
                lifecycleScope.launch {
                    val transcodeSession = JellyfinApiClient.resolvePlaybackSession(
                        context = this@PlayerActivity,
                        profile = jfProf,
                        itemId = activeJfItemId,
                        mediaSourceId = jfSess.mediaSourceId,
                        startPositionMs = resumeAt,
                        forceTranscode = true
                    )
                    if (transcodeSession != null) {
                        activeJfSession = transcodeSession
                        activeDirectUrl = transcodeSession.streamUrl
                        jfReportedStart = false
                        if (transcodeSession.videoSummaryLabel.isNotBlank()) {
                            swarmBadge.text = transcodeSession.videoSummaryLabel
                            swarmBadge.visibility = View.VISIBLE
                        }
                        startDirectHttpPlayback(
                            rawUrl = transcodeSession.streamUrl,
                            forceHls = true,
                            forcePermissiveSsl = transcodeSession.ignoreSsl || payload.ignoreSsl,
                            startPosMs = resumeAt,
                            allowShortSeek = true
                        )
                    } else {
                        loadingPanel.visibility = View.GONE
                        Toast.makeText(
                            this@PlayerActivity,
                            "Playback error: ${error.errorCodeName} (${error.message ?: ""})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return
            }

            // 3. Direct HTTP SSL & HLS container fallbacks
            val direct = activeDirectUrl ?: payload.directUrl
            if (!direct.isNullOrBlank() && !payload.isTwitchLive) {
                val causeChain = generateSequence(error.cause) { it.cause }.toList()
                val isSslFailure = causeChain.any { it is javax.net.ssl.SSLException || it.message?.contains("CertPath", true) == true }
                if (isSslFailure && !retriedDirectPermissiveSsl) {
                    retriedDirectPermissiveSsl = true
                    startDirectHttpPlayback(
                        rawUrl = direct,
                        forceHls = retriedDirectAsHls,
                        forcePermissiveSsl = true,
                        startPosMs = resumeAt,
                        allowShortSeek = true
                    )
                    return
                }
                val isContainerUnsupported =
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                if (isContainerUnsupported && !retriedDirectAsHls) {
                    retriedDirectAsHls = true
                    startDirectHttpPlayback(
                        rawUrl = direct,
                        forceHls = true,
                        forcePermissiveSsl = retriedDirectPermissiveSsl || payload.ignoreSsl,
                        startPosMs = resumeAt,
                        allowShortSeek = true
                    )
                    return
                }
            }

            loadingPanel.visibility = View.GONE
            Toast.makeText(
                this@PlayerActivity,
                "Playback error: ${error.errorCodeName} (${error.message ?: ""})",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun effectiveDisplayPositionMs(p: ExoPlayer): Long {
        if (isScrubbing) return scrubTargetMs
        val rawPos = p.currentPosition.coerceAtLeast(0L)
        if (pendingSeekTargetMs >= 0L) {
            val now = System.currentTimeMillis()
            val settled = p.playbackState == Player.STATE_READY &&
                kotlin.math.abs(rawPos - pendingSeekTargetMs) <= 3_500L
            if (settled || now >= pendingSeekExpireAtMs) {
                pendingSeekTargetMs = -1L
                return rawPos
            }
            return pendingSeekTargetMs
        }
        return rawPos
    }

    private fun performPlayerSeekTo(targetMs: Long) {
        val p = exoPlayer ?: return
        val dur = p.duration.coerceAtLeast(0L)
        val clamped = if (dur > 0L) targetMs.coerceIn(0L, dur) else targetMs.coerceAtLeast(0L)
        pendingSeekTargetMs = clamped
        pendingSeekExpireAtMs = System.currentTimeMillis() + 3_200L
        lastKnownPosMs = clamped
        if (dur > 0L) {
            seekBar.progress = ((clamped * 1000L) / dur).toInt().coerceIn(0, 1000)
        }
        timeText.text = if (payload.isTwitchLive && dur <= 0L) {
            "${formatTimeMs(clamped)} / LIVE"
        } else {
            "${formatTimeMs(clamped)} / ${formatTimeMs(dur)}"
        }
        p.seekTo(clamped)
    }

    private fun startUiTicker() {
        uiTickerJob?.cancel()
        tickerTickCount = 0
        uiTickerJob = lifecycleScope.launch {
            while (isActive) {
                val p = exoPlayer
                if (p != null) {
                    val rawPos = p.currentPosition.coerceAtLeast(0L)
                    val pos = effectiveDisplayPositionMs(p)
                    val dur = p.duration.coerceAtLeast(0L)
                    if (rawPos > 0L && pendingSeekTargetMs < 0L) lastKnownPosMs = rawPos
                    if (dur > 0L) lastKnownDurMs = dur

                    if (!isScrubbing) {
                        if (dur > 0L) {
                            seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                            val bufPos = p.bufferedPosition.coerceAtLeast(pos)
                            seekBar.secondaryProgress = ((bufPos * 1000L) / dur).toInt().coerceIn(0, 1000)
                        }
                        timeText.text = if (payload.isTwitchLive && dur <= 0L) {
                            "${formatTimeMs(pos)} / LIVE"
                        } else {
                            "${formatTimeMs(pos)} / ${formatTimeMs(dur)}"
                        }
                    } else if (dur > 0L) {
                        val bufPos = p.bufferedPosition.coerceAtLeast(0L)
                        seekBar.secondaryProgress = ((bufPos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    }

                    val isEnded = p.playbackState == Player.STATE_ENDED
                    maybePreResolveBingeOrRecommendations(pos, dur, isEnded)
                    updateBingeAndRecommendationOverlays(pos, dur, isEnded)

                    // Jellyfin Intro / Credits floating skip pill
                    updateJellyfinSkipSegmentPill(pos, dur)

                    // SponsorBlock auto-skip with BACK-to-undo (suppressed during active scrub / pending seek)
                    if (!isScrubbing &&
                        pendingSeekTargetMs < 0L &&
                        !payload.youtubeVideoId.isNullOrBlank() &&
                        sponsorSegments.isNotEmpty() &&
                        p.playbackState == Player.STATE_READY &&
                        AppPrefs.isSponsorBlockEnabled(this@PlayerActivity)
                    ) {
                        val hitSeg = sponsorSegments.firstOrNull { seg ->
                            seg.uuid !in ignoredSponsorUuids &&
                                seg.endMs - 800L > seg.startMs &&
                                rawPos in seg.startMs until (seg.endMs - 800L)
                        }
                        if (hitSeg != null) {
                            lastSponsorUndoOriginMs = rawPos
                            lastSponsorUndoUuid = hitSeg.uuid
                            lastSponsorUndoUntilMs = System.currentTimeMillis() + 6_500L
                            ignoredSponsorUuids.add(hitSeg.uuid)
                            performPlayerSeekTo(hitSeg.endMs)
                            Toast.makeText(
                                this@PlayerActivity,
                                "Skipped ${hitSeg.category} · Press BACK to undo",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    tickerTickCount++

                    // Jellyfin periodic session progress (every 10s while STATE_READY)
                    val jfProf = activeJfProfile
                    val jfSess = activeJfSession
                    if (jfReportedStart &&
                        p.playbackState == Player.STATE_READY &&
                        jfProf != null &&
                        jfSess != null &&
                        activeJfItemId.isNotBlank() &&
                        tickerTickCount % 10 == 0
                    ) {
                        JellyfinApiClient.reportPlaybackProgress(
                            context = this@PlayerActivity,
                            profile = jfProf,
                            itemId = activeJfItemId,
                            session = jfSess,
                            positionMs = pos,
                            isPaused = !p.isPlaying,
                            eventName = "timeupdate"
                        )
                    }

                    // YouTube Watch History periodic sync (every 15s while playing)
                    if (ytReportedStart && p.isPlaying && tickerTickCount % 15 == 0) {
                        reportYouTubeWatchProgressAsync(pos, dur)
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun updateJellyfinSkipSegmentPill(pos: Long, dur: Long) {
        val session = activeJfSession
        if (session == null) {
            if (skipSegmentBtn.visibility != View.GONE) {
                skipSegmentBtn.visibility = View.GONE
            }
            activeSkipAction = SkipSegmentAction.NONE
            return
        }

        val introStart = session.introStartMs
        val introEnd = session.introEndMs
        val creditsStart = session.creditsStartMs

        val inIntro = introStart != null &&
            introEnd != null &&
            introEnd - 1_000L > introStart &&
            pos in introStart until (introEnd - 1_000L)

        val hasSeriesBinge = findNextEpisode() != null
        val creditsSupersededByOverlay = hasSeriesBinge ||
            creditsRecOverlay.visibility == View.VISIBLE ||
            recommendedItems.isNotEmpty()

        val inCredits = !inIntro &&
            !creditsSupersededByOverlay &&
            creditsStart != null &&
            dur > creditsStart &&
            dur - 2_000L > creditsStart &&
            pos in creditsStart until (dur - 2_000L)

        val wasGone = skipSegmentBtn.visibility != View.VISIBLE

        when {
            inIntro -> {
                activeSkipAction = SkipSegmentAction.INTRO
                skipSegmentBtn.text = "Skip Intro ⏭"
                skipSegmentBtn.visibility = View.VISIBLE
                if (wasGone &&
                    osdRoot.visibility != View.VISIBLE &&
                    bingeNextCard.visibility != View.VISIBLE &&
                    creditsRecOverlay.visibility != View.VISIBLE
                ) {
                    skipSegmentBtn.requestFocus()
                }
            }
            inCredits -> {
                activeSkipAction = SkipSegmentAction.CREDITS
                skipSegmentBtn.text = "Skip Credits ⏭"
                skipSegmentBtn.visibility = View.VISIBLE
                if (wasGone && osdRoot.visibility != View.VISIBLE) {
                    skipSegmentBtn.requestFocus()
                }
            }
            else -> {
                activeSkipAction = SkipSegmentAction.NONE
                if (skipSegmentBtn.visibility != View.GONE) {
                    skipSegmentBtn.visibility = View.GONE
                }
            }
        }
    }

    private fun onSkipSegmentClicked() {
        val session = activeJfSession ?: return
        when (activeSkipAction) {
            SkipSegmentAction.INTRO -> {
                val targetMs = session.introEndMs ?: return
                performPlayerSeekTo(targetMs)
                skipSegmentBtn.visibility = View.GONE
                activeSkipAction = SkipSegmentAction.NONE
            }
            SkipSegmentAction.CREDITS -> {
                if (findNextEpisode() != null) {
                    launchNextEpisodeSeamless()
                } else {
                    val p = exoPlayer ?: return
                    val dur = p.duration
                    if (dur > 2_000L) {
                        val target = (dur - 1_500L).coerceAtLeast(session.creditsStartMs ?: 0L)
                        performPlayerSeekTo(target)
                    }
                    skipSegmentBtn.visibility = View.GONE
                    activeSkipAction = SkipSegmentAction.NONE
                }
            }
            SkipSegmentAction.NONE -> Unit
        }
    }

    private fun formatTimeMs(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000L
        val hrs = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hrs > 0L) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private fun formatSignedDeltaMs(deltaMs: Long): String {
        val sign = if (deltaMs >= 0L) "+" else "−"
        val absSec = kotlin.math.abs(deltaMs) / 1000L
        val hrs = absSec / 3600L
        val mins = (absSec % 3600L) / 60L
        val secs = absSec % 60L
        return if (hrs > 0L) {
            String.format("%s%d:%02d:%02d", sign, hrs, mins, secs)
        } else {
            String.format("%s%02d:%02d", sign, mins, secs)
        }
    }

    private fun computeAcceleratedScrubStepMs(directionSign: Int, isKeyRepeat: Boolean, durMs: Long): Long {
        val now = System.currentTimeMillis()
        if (isKeyRepeat || (now - lastScrubInputAtMs) <= 650L) {
            scrubRepeatCount++
        } else {
            scrubRepeatCount = 0
        }
        lastScrubInputAtMs = now

        val baseStep = when {
            scrubRepeatCount <= 1 -> if (durMs in 1L..90_000L) 5_000L else 10_000L
            scrubRepeatCount <= 5 -> 30_000L
            scrubRepeatCount <= 12 -> 60_000L
            scrubRepeatCount <= 24 -> 120_000L
            else -> 300_000L
        }
        val cappedStep = if (durMs > 20_000L) minOf(baseStep, maxOf(5_000L, durMs / 6L)) else baseStep
        return cappedStep * directionSign
    }

    private fun updateScrubUi(durMs: Long) {
        if (durMs > 0L) {
            seekBar.progress = ((scrubTargetMs * 1000L) / durMs).toInt().coerceIn(0, 1000)
        }
        timeText.text = if (payload.isTwitchLive && durMs <= 0L) {
            "${formatTimeMs(scrubTargetMs)} / LIVE"
        } else {
            "${formatTimeMs(scrubTargetMs)} / ${formatTimeMs(durMs)}"
        }

        val deltaMs = scrubTargetMs - scrubOriginPosMs
        val arrow = if (deltaMs >= 0L) "▶▶" else "◀◀"
        val chapters = activeJfSession?.chapters.orEmpty()
        val chapterSuffix = if (chapters.isNotEmpty()) {
            val idx = chapters.indexOfLast { scrubTargetMs >= it.startMs }
            if (idx >= 0) {
                val chName = chapters[idx].Name?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
                "  ·  $chName"
            } else ""
        } else ""

        scrubPreviewPill.text = "$arrow ${formatSignedDeltaMs(deltaMs)}  ·  ${formatTimeMs(scrubTargetMs)} / ${formatTimeMs(durMs)}$chapterSuffix   [OK Jump · BACK Cancel]"
        scrubPreviewPill.visibility = View.VISIBLE
    }

    private fun scheduleScrubAutoCommit(delayMs: Long = 350L) {
        scrubCommitJob?.cancel()
        scrubCommitJob = lifecycleScope.launch {
            delay(delayMs)
            if (isScrubbing && !isTouchTrackingScrub) {
                commitScrubNow()
            }
        }
    }

    private fun stepScrub(deltaMs: Long, focusTimeline: Boolean = true, commitDelayMs: Long = 380L) {
        val p = exoPlayer ?: return
        if (payload.isTwitchLive && p.duration <= 0L) return

        val dur = p.duration.coerceAtLeast(0L)
        if (!isScrubbing) {
            val base = effectiveDisplayPositionMs(p)
            isScrubbing = true
            scrubOriginPosMs = base
            scrubTargetMs = base
        }
        scrubTargetMs = if (dur > 0L) {
            (scrubTargetMs + deltaMs).coerceIn(0L, dur)
        } else {
            (scrubTargetMs + deltaMs).coerceAtLeast(0L)
        }

        if (osdRoot.visibility != View.VISIBLE) {
            showOsd(focusButtons = false, focusTimeline = focusTimeline)
        } else if (focusTimeline && currentFocus !== seekBar) {
            seekBar.requestFocus()
        }
        osdHideJob?.cancel()
        updateScrubUi(dur)
        scheduleScrubAutoCommit(commitDelayMs)
    }

    private fun stepAcceleratedDpadScrub(directionSign: Int, isKeyRepeat: Boolean) {
        val p = exoPlayer ?: return
        val dur = p.duration.coerceAtLeast(0L)
        val step = computeAcceleratedScrubStepMs(directionSign, isKeyRepeat, dur)
        // Hold longer debounce while key repeats are arriving; ACTION_UP tightens to 350ms
        val holdDelay = if (isKeyRepeat) 650L else 380L
        stepScrub(step, focusTimeline = true, commitDelayMs = holdDelay)
    }

    private fun commitScrubNow() {
        scrubCommitJob?.cancel()
        scrubCommitJob = null
        if (!isScrubbing) return
        val target = scrubTargetMs
        isScrubbing = false
        isTouchTrackingScrub = false
        scrubRepeatCount = 0
        scrubPreviewPill.visibility = View.GONE
        performPlayerSeekTo(target)
        scheduleOsdAutoHide()
    }

    private fun cancelScrub() {
        scrubCommitJob?.cancel()
        scrubCommitJob = null
        if (!isScrubbing) return
        isScrubbing = false
        isTouchTrackingScrub = false
        scrubRepeatCount = 0
        scrubPreviewPill.visibility = View.GONE
        val p = exoPlayer
        val pos = p?.let { effectiveDisplayPositionMs(it) } ?: scrubOriginPosMs
        val dur = p?.duration?.coerceAtLeast(0L) ?: lastKnownDurMs
        if (dur > 0L) {
            seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
        }
        timeText.text = if (payload.isTwitchLive && dur <= 0L) {
            "${formatTimeMs(pos)} / LIVE"
        } else {
            "${formatTimeMs(pos)} / ${formatTimeMs(dur)}"
        }
        scheduleOsdAutoHide()
    }

    private fun showOsd(focusButtons: Boolean = false, focusTimeline: Boolean = false) {
        osdRoot.visibility = View.VISIBLE
        if (bingeNextCard.visibility == View.VISIBLE) {
            bingeNextCard.visibility = View.GONE
        }
        if (creditsRecOverlay.visibility == View.VISIBLE) {
            creditsRecOverlay.visibility = View.GONE
        }
        when {
            focusTimeline -> seekBar.requestFocus()
            focusButtons -> playPauseBtn.requestFocus()
        }
        if (!isScrubbing) {
            scheduleOsdAutoHide()
        }
    }

    private fun hideOsdAndRestoreOverlays() {
        if (isScrubbing) {
            commitScrubNow()
        }
        scrubPreviewPill.visibility = View.GONE
        osdRoot.visibility = View.GONE
        val p = exoPlayer
        val pos = p?.let { effectiveDisplayPositionMs(it) } ?: lastKnownPosMs
        val dur = p?.duration?.coerceAtLeast(0L) ?: lastKnownDurMs
        updateBingeAndRecommendationOverlays(
            pos = pos,
            dur = dur,
            isEnded = p?.playbackState == Player.STATE_ENDED
        )
    }

    private fun scheduleOsdAutoHide() {
        osdHideJob?.cancel()
        if (isScrubbing) return
        osdHideJob = lifecycleScope.launch {
            delay(OSD_AUTO_HIDE_MS)
            if (isScrubbing) return@launch
            val p = exoPlayer
            // playWhenReady (not isPlaying): right after a scrub the player is still buffering the new
            // position, and isPlaying=false there used to leave the overlay up until Back was pressed.
            // Only a pause the viewer chose keeps the overlay on screen.
            if (p == null || p.playWhenReady || p.playbackState == Player.STATE_ENDED) {
                hideOsdAndRestoreOverlays()
            }
        }
    }

    private fun togglePlayPause() {
        if (isScrubbing) {
            commitScrubNow()
        }
        val p = exoPlayer ?: return
        if (p.isPlaying) p.pause() else p.play()
        showOsd(focusButtons = false)
    }

    private fun seekRelative(deltaMs: Long) {
        stepScrub(deltaMs, focusTimeline = false, commitDelayMs = 350L)
    }

    private fun showQualityPicker() {
        val p = exoPlayer ?: return
        val selector = trackSelector ?: return

        if (payload.qualityChoices.isNotEmpty()) {
            val labels = payload.qualityChoices.map { it.label }
            val selIdx = payload.qualityChoices.indexOfFirst { choice ->
                (activeQualityLabel.isNotBlank() && choice.label.equals(activeQualityLabel, ignoreCase = true)) ||
                    (activeQualityLabel.isBlank() && !activeDirectUrl.isNullOrBlank() && choice.directUrl == activeDirectUrl) ||
                    (activeQualityLabel.isBlank() && currentMaxVideoHeight > 0 && choice.maxHeight == currentMaxVideoHeight)
            }.coerceAtLeast(0)

            TvUiKit.showSelectDialog(
                this,
                "Select Video Quality",
                labels,
                selectedIndex = selIdx,
                eyebrowLabel = "PLAYER / QUALITY"
            ) { idx ->
                if (idx in payload.qualityChoices.indices) {
                    val choice = payload.qualityChoices[idx]
                    val curPos = p.currentPosition.coerceAtLeast(0L)
                    activeQualityLabel = choice.label

                    when {
                        !choice.dashMpdXml.isNullOrBlank() -> {
                            currentMaxVideoHeight = choice.maxHeight ?: 0
                            val sameManifest = isPlayingDashMpd && choice.dashMpdXml == activeDashMpdXml
                            activeDashMpdXml = choice.dashMpdXml
                            if (sameManifest) {
                                val params = selector.buildUponParameters()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                if (currentMaxVideoHeight > 0) {
                                    params.setMaxVideoSize(currentMaxVideoHeight * 16 / 9 + 32, currentMaxVideoHeight)
                                } else {
                                    params.clearVideoSizeConstraints()
                                }
                                selector.setParameters(params)
                            } else {
                                startDashMpdPlayback(
                                    mpdXml = choice.dashMpdXml,
                                    startPosMs = curPos,
                                    allowShortSeek = true
                                )
                            }
                        }
                        choice.maxHeight != null && (isPlayingDashMpd || choice.directUrl.isNullOrBlank() || choice.directUrl == activeDirectUrl) -> {
                            currentMaxVideoHeight = choice.maxHeight
                            if (!isPlayingDashMpd && choice.directUrl.isNullOrBlank() && !payload.dashMpdXml.isNullOrBlank()) {
                                activeDashMpdXml = payload.dashMpdXml
                                startDashMpdPlayback(
                                    mpdXml = payload.dashMpdXml!!,
                                    startPosMs = curPos,
                                    allowShortSeek = true
                                )
                            } else {
                                val params = selector.buildUponParameters()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                if (choice.maxHeight > 0) {
                                    params.setMaxVideoSize(choice.maxHeight * 16 / 9 + 32, choice.maxHeight)
                                } else {
                                    params.clearVideoSizeConstraints()
                                }
                                selector.setParameters(params)
                            }
                        }
                        !choice.directUrl.isNullOrBlank() -> {
                            currentMaxVideoHeight = choice.maxHeight ?: 0
                            activeDirectUrl = choice.directUrl
                            if (payload.isTwitchLive) {
                                startTwitchLiveHlsPlayback(choice.directUrl)
                            } else {
                                val forceHls = choice.mimeType == MimeTypes.APPLICATION_M3U8 ||
                                    choice.directUrl.contains(".m3u8", ignoreCase = true)
                                startDirectHttpPlayback(
                                    rawUrl = choice.directUrl,
                                    forceHls = forceHls,
                                    forcePermissiveSsl = payload.ignoreSsl,
                                    startPosMs = curPos,
                                    allowShortSeek = true
                                )
                            }
                        }
                    }
                }
                scheduleOsdAutoHide()
            }
            return
        }

        // Dynamic ExoPlayer video track override selector (HLS multivariant / multi-video DASH)
        val videoGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val labels = mutableListOf("Auto (Adaptive)")
        val overrides = mutableListOf<TrackSelectionOverride?>(null)
        val hasExplicitOverride = selector.parameters.overrides.values.any { it.type == C.TRACK_TYPE_VIDEO }
        var selIdx = 0

        videoGroups.forEach { group ->
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val heightStr = if (fmt.height > 0) "${fmt.height}p" else "Track ${labels.size}"
                val fpsStr = if (fmt.frameRate >= 50f) "${fmt.frameRate.toInt()}" else ""
                val kbpsStr = if (fmt.bitrate > 0) "${fmt.bitrate / 1000} kbps" else ""
                val codecStr = fmt.codecs?.substringBefore('.')?.uppercase(Locale.US).orEmpty()
                if (hasExplicitOverride && group.isTrackSelected(i)) {
                    selIdx = labels.size
                }
                labels.add(
                    listOf("$heightStr$fpsStr", codecStr, kbpsStr)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                )
                overrides.add(TrackSelectionOverride(group.mediaTrackGroup, i))
            }
        }

        if (labels.size <= 1) {
            Toast.makeText(this, "Single video quality active", Toast.LENGTH_SHORT).show()
            return
        }

        TvUiKit.showSelectDialog(
            this,
            "Select Video Quality",
            labels,
            selectedIndex = selIdx,
            eyebrowLabel = "PLAYER / QUALITY"
        ) { idx ->
            if (idx == 0) {
                selector.setParameters(
                    selector.buildUponParameters()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .clearVideoSizeConstraints()
                )
            } else if (idx in overrides.indices) {
                overrides[idx]?.let { ov ->
                    selector.setParameters(
                        selector.buildUponParameters()
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .clearVideoSizeConstraints()
                            .addOverride(ov)
                    )
                }
            }
            scheduleOsdAutoHide()
        }
    }

    private fun showChaptersPicker() {
        val chapters = activeJfSession?.chapters.orEmpty()
        if (chapters.isEmpty()) {
            Toast.makeText(this, "No chapters available", Toast.LENGTH_SHORT).show()
            return
        }
        val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val curChapIdx = chapters.indexOfLast { pos >= it.startMs }.coerceAtLeast(0)
        val labels = chapters.mapIndexed { idx, ch ->
            "${formatTimeMs(ch.startMs)} · ${ch.Name?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"}"
        }
        TvUiKit.showSelectDialog(
            this,
            "Chapters",
            labels,
            selectedIndex = curChapIdx,
            eyebrowLabel = "PLAYER / CHAPTERS"
        ) { idx ->
            if (idx in chapters.indices) {
                exoPlayer?.seekTo(chapters[idx].startMs)
            }
            scheduleOsdAutoHide()
        }
    }

    private fun showAudioTrackPicker() {
        val p = exoPlayer ?: return
        val selector = trackSelector ?: return
        val audioGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) {
            Toast.makeText(this, "Single audio stream active", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = mutableListOf<String>()
        val overrides = mutableListOf<TrackSelectionOverride>()
        var selIdx = 0
        audioGroups.forEach { group ->
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val lang = fmt.language ?: "und"
                val label = fmt.label ?: ""
                val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""
                val codec = fmt.sampleMimeType?.substringAfter('/')?.uppercase() ?: ""
                if (group.isTrackSelected(i)) selIdx = labels.size
                labels.add(listOf(lang.uppercase(), label, codec, ch).filter { it.isNotBlank() }.joinToString(" · "))
                overrides.add(TrackSelectionOverride(group.mediaTrackGroup, i))
            }
        }
        TvUiKit.showSelectDialog(this, "Select Audio Track", labels, selectedIndex = selIdx, eyebrowLabel = "PLAYER / AUDIO") { idx ->
            if (idx in overrides.indices) {
                selector.setParameters(
                    selector.buildUponParameters()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .addOverride(overrides[idx])
                )
            }
            scheduleOsdAutoHide()
        }
    }

    private fun applyAudioDspConfig(newCfg: AudioDspConfig) {
        val sanitized = newCfg.copy(
            boostPercent = newCfg.boostPercent.coerceIn(50, 300),
            bassDb = newCfg.bassDb.coerceIn(-8, 8),
            voiceDb = newCfg.voiceDb.coerceIn(-8, 8),
            trebleDb = newCfg.trebleDb.coerceIn(-8, 8)
        )
        activeAudioDsp = sanitized
        audioDspProcessor.updateConfig(sanitized)
        AppPrefs.saveAudioDspConfig(this, sanitized)
        if (::audioBoostBtn.isInitialized) {
            audioBoostBtn.text = sanitized.osdButtonLabel
            TvUiKit.setPillSelected(audioBoostBtn, !sanitized.isDefault)
        }
    }

    private fun showAudioEqualizerDialog() {
        osdHideJob?.cancel()
        val ctx = this
        val dp = { v: Int -> TvUiKit.dp(ctx, v) }

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TvUiKit.eyebrow(ctx, "PLAYER / PROJECTOR AUDIO & EQUALIZER"))
        titleCol.addView(TextView(ctx).apply {
            text = "Volume Amplification & 3-Band EQ"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = XirtusFonts.semiBold(ctx)
            letterSpacing = -0.02f
            setPadding(0, dp(2), 0, 0)
        })
        val liveReadoutBadge = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.monoMedium(ctx)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A2639"))
                cornerRadius = TvUiKit.dpF(ctx, 999f)
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#3893C5FD"))
            }
        }
        headerRow.addView(titleCol)
        headerRow.addView(liveReadoutBadge)
        outer.addView(headerRow)
        outer.addView(TvUiKit.hairlineDivider(ctx, topMarginDp = 10, bottomMarginDp = 10))

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(365)
            )
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // --- Quick Volume Boost Pills (100% .. 300%) ---
        body.addView(TextView(ctx).apply {
            text = "QUICK VOLUME AMPLIFICATION (KODI-STYLE GAIN + SOFT-KNEE LIMITER)"
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoMedium(ctx)
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(6))
        })

        val boostLevels = listOf(100, 125, 150, 200, 250, 300)
        val boostPillsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val boostPillViews = HashMap<Int, TextView>(boostLevels.size)

        // --- Fine D-Pad / Touch Boost Stepper (50% - 300%) ---
        var suppressBoostSeekCallback = false
        val fineBoostLabel = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = XirtusFonts.monoRegular(ctx)
            layoutParams = LinearLayout.LayoutParams(dp(154), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val fineBoostSeek = SeekBar(ctx).apply {
            max = 250 // maps 0..250 -> 50%..300%
            isFocusable = false
            progressTintList = ColorStateList.valueOf(XirtusPalette.EMERALD)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fineBoostHint = TextView(ctx).apply {
            text = "◀ / ▶ ±10%"
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = XirtusFonts.monoRegular(ctx)
            setPadding(dp(8), 0, 0, 0)
        }

        fun makeStepperBoxBg(focused: Boolean): GradientDrawable = GradientDrawable().apply {
            setColor(if (focused) XirtusPalette.FOCUSED_FILL else Color.parseColor("#CC141B26"))
            cornerRadius = TvUiKit.dpF(ctx, 11f)
            setStroke(
                dp(if (focused) 2 else 1).coerceAtLeast(1),
                if (focused) XirtusPalette.FOCUSED_BORDER else Color.parseColor("#24FFFFFF")
            )
        }

        val fineBoostRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = makeStepperBoxBg(false)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = makeStepperBoxBg(hasFocus)
            }
        }
        fineBoostRow.addView(fineBoostLabel)
        fineBoostRow.addView(fineBoostSeek)
        fineBoostRow.addView(fineBoostHint)

        // --- EQ Presets Row ---
        body.addView(boostPillsRow)
        body.addView(fineBoostRow)

        body.addView(TextView(ctx).apply {
            text = "ACOUSTIC PROFILE & DIALOGUE ENHANCEMENT"
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoMedium(ctx)
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(6))
        })

        val presetOrder = listOf(
            AudioEqPreset.FLAT,
            AudioEqPreset.PROJECTOR,
            AudioEqPreset.DIALOGUE,
            AudioEqPreset.CINEMA,
            AudioEqPreset.NIGHT
        )
        val presetScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val presetPillViews = HashMap<String, TextView>(presetOrder.size)

        lateinit var drcToggleBtn: TextView

        // --- 3-Band Parametric EQ Steppers (-8 dB .. +8 dB) ---
        data class EqBandUi(
            val row: LinearLayout,
            val valueText: TextView,
            val bar: ProgressBar
        )

        fun createEqBandRow(
            bandTitle: String,
            getDb: (AudioDspConfig) -> Int,
            withDb: (AudioDspConfig, Int) -> AudioDspConfig,
            refreshUi: () -> Unit
        ): EqBandUi {
            val nameTv = TextView(ctx).apply {
                text = bandTitle
                setTextColor(XirtusPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = XirtusFonts.medium(ctx)
                layoutParams = LinearLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 16 // -8..+8 -> 0..16
                progressTintList = ColorStateList.valueOf(Color.parseColor("#60A5FA"))
                progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#29FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f).apply {
                    marginEnd = dp(12)
                }
            }
            val valTv = TextView(ctx).apply {
                setTextColor(XirtusPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = XirtusFonts.monoMedium(ctx)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                background = makeStepperBoxBg(false)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = makeStepperBoxBg(hasFocus)
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val next = (getDb(activeAudioDsp) - 1).coerceIn(-8, 8)
                            applyAudioDspConfig(withDb(activeAudioDsp, next).copy(presetId = "custom"))
                            refreshUi()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val next = (getDb(activeAudioDsp) + 1).coerceIn(-8, 8)
                            applyAudioDspConfig(withDb(activeAudioDsp, next).copy(presetId = "custom"))
                            refreshUi()
                            true
                        }
                        else -> false
                    }
                }
                setOnClickListener {
                    // Clicking OK cycles 0 -> +3 -> +6 -> -3 -> 0 (or resets to 0 dB if customized)
                    val cur = getDb(activeAudioDsp)
                    val next = if (cur != 0) 0 else 3
                    applyAudioDspConfig(withDb(activeAudioDsp, next).copy(presetId = "custom"))
                    refreshUi()
                }
            }
            row.addView(nameTv)
            row.addView(bar)
            row.addView(valTv)
            return EqBandUi(row, valTv, bar)
        }

        lateinit var bassUi: EqBandUi
        lateinit var voiceUi: EqBandUi
        lateinit var trebleUi: EqBandUi

        fun formatSignedDb(db: Int): String = when {
            db > 0 -> "◀ +${db} dB ▶"
            db < 0 -> "◀ ${db} dB ▶"
            else -> "◀  0 dB ▶"
        }

        fun refreshAllUi() {
            val cfg = activeAudioDsp
            val gainRatio = (cfg.boostPercent / 100.0).coerceAtLeast(0.5)
            val gainDb = 20.0 * log10(gainRatio)
            val dbStr = String.format(Locale.US, "%+.1f dB", gainDb)
            val presetLabel = AudioEqPreset.fromId(cfg.presetId).label

            liveReadoutBadge.text = "${cfg.boostPercent}% ($dbStr) · $presetLabel"
            liveReadoutBadge.setTextColor(
                when {
                    cfg.boostPercent >= 200 -> XirtusPalette.AMBER
                    !cfg.isDefault -> XirtusPalette.EMERALD
                    else -> XirtusPalette.TEXT_BODY
                }
            )

            boostPillViews.forEach { (pct, pill) ->
                TvUiKit.setPillSelected(pill, cfg.boostPercent == pct)
            }

            fineBoostLabel.text = "Fine Gain: ${cfg.boostPercent}% ($dbStr)"
            suppressBoostSeekCallback = true
            fineBoostSeek.progress = (cfg.boostPercent - 50).coerceIn(0, 250)
            suppressBoostSeekCallback = false

            presetPillViews.forEach { (id, pill) ->
                TvUiKit.setPillSelected(pill, cfg.presetId.equals(id, ignoreCase = true))
            }

            drcToggleBtn.text = if (cfg.dialogueCompress) {
                "Dialogue DRC & 5.1 Center Lift: ON ✓"
            } else {
                "Dialogue DRC & 5.1 Center Lift: OFF"
            }
            TvUiKit.setPillSelected(drcToggleBtn, cfg.dialogueCompress)

            bassUi.valueText.text = formatSignedDb(cfg.bassDb)
            bassUi.bar.progress = (cfg.bassDb + 8).coerceIn(0, 16)
            voiceUi.valueText.text = formatSignedDb(cfg.voiceDb)
            voiceUi.bar.progress = (cfg.voiceDb + 8).coerceIn(0, 16)
            trebleUi.valueText.text = formatSignedDb(cfg.trebleDb)
            trebleUi.bar.progress = (cfg.trebleDb + 8).coerceIn(0, 16)
        }

        boostLevels.forEach { pct ->
            val pill = TvUiKit.createPillButton(ctx, "${pct}%", selected = activeAudioDsp.boostPercent == pct, compact = true) {
                applyAudioDspConfig(activeAudioDsp.copy(boostPercent = pct))
                refreshAllUi()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            boostPillViews[pct] = pill
            boostPillsRow.addView(pill)
        }

        fineBoostSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressBoostSeekCallback) return
                val snapped = (((progress + 50) + 2) / 5 * 5).coerceIn(50, 300)
                applyAudioDspConfig(activeAudioDsp.copy(boostPercent = snapped))
                refreshAllUi()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        fineBoostRow.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val next = (activeAudioDsp.boostPercent - 10).coerceIn(50, 300)
                    applyAudioDspConfig(activeAudioDsp.copy(boostPercent = next))
                    refreshAllUi()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val next = (activeAudioDsp.boostPercent + 10).coerceIn(50, 300)
                    applyAudioDspConfig(activeAudioDsp.copy(boostPercent = next))
                    refreshAllUi()
                    true
                }
                else -> false
            }
        }
        fineBoostRow.setOnClickListener {
            val toggled = if (activeAudioDsp.boostPercent == 200) 100 else 200
            applyAudioDspConfig(activeAudioDsp.copy(boostPercent = toggled))
            refreshAllUi()
        }

        presetOrder.forEach { preset ->
            val pill = TvUiKit.createPillButton(
                ctx,
                preset.label,
                selected = activeAudioDsp.presetId.equals(preset.id, ignoreCase = true),
                compact = true
            ) {
                applyAudioDspConfig(
                    activeAudioDsp.copy(
                        presetId = preset.id,
                        bassDb = preset.bassDb,
                        voiceDb = preset.voiceDb,
                        trebleDb = preset.trebleDb,
                        dialogueCompress = preset.dialogueCompress
                    )
                )
                refreshAllUi()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            presetPillViews[preset.id] = pill
            presetRow.addView(pill)
        }
        presetScroll.addView(presetRow)
        body.addView(presetScroll)

        drcToggleBtn = TvUiKit.createPillButton(
            ctx,
            "Dialogue DRC & 5.1 Center Lift",
            selected = activeAudioDsp.dialogueCompress,
            compact = true
        ) {
            applyAudioDspConfig(
                activeAudioDsp.copy(
                    dialogueCompress = !activeAudioDsp.dialogueCompress,
                    presetId = "custom"
                )
            )
            refreshAllUi()
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        body.addView(drcToggleBtn)

        bassUi = createEqBandRow(
            "Bass (100 Hz Low-Shelf)",
            getDb = { it.bassDb },
            withDb = { c, v -> c.copy(bassDb = v) },
            refreshUi = ::refreshAllUi
        )
        voiceUi = createEqBandRow(
            "Voice (1.8 kHz Dialogue)",
            getDb = { it.voiceDb },
            withDb = { c, v -> c.copy(voiceDb = v) },
            refreshUi = ::refreshAllUi
        )
        trebleUi = createEqBandRow(
            "Treble (7.5 kHz Clarity)",
            getDb = { it.trebleDb },
            withDb = { c, v -> c.copy(trebleDb = v) },
            refreshUi = ::refreshAllUi
        )
        body.addView(bassUi.row)
        body.addView(voiceUi.row)
        body.addView(trebleUi.row)

        scroll.addView(body)
        outer.addView(scroll)
        outer.addView(TvUiKit.hairlineDivider(ctx, topMarginDp = 8, bottomMarginDp = 10))

        lateinit var dlg: AlertDialog
        val footerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val resetBtn = TvUiKit.createPillButton(ctx, "Reset (100% · Flat)", compact = true) {
            applyAudioDspConfig(AudioDspConfig())
            refreshAllUi()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }
        val doneBtn = TvUiKit.createPillButton(ctx, "Done", selected = true, compact = true) {
            dlg.dismiss()
        }
        footerRow.addView(resetBtn)
        footerRow.addView(doneBtn)
        outer.addView(footerRow)

        refreshAllUi()

        dlg = AlertDialog.Builder(ctx)
            .setView(outer)
            .setOnDismissListener {
                if (osdRoot.visibility == View.VISIBLE) {
                    scheduleOsdAutoHide()
                    audioBoostBtn.post { audioBoostBtn.requestFocus() }
                }
            }
            .create()
        dlg.show()
        dlg.window?.let { win ->
            win.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(Color.parseColor("#EB111722"))
                    cornerRadius = TvUiKit.dpF(ctx, 22f)
                    setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
                }
            )
            win.setDimAmount(0.24f)
            win.setLayout(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val initialFocus: View = boostPillViews[activeAudioDsp.boostPercent]
            ?: boostPillViews[200]
            ?: fineBoostRow
        initialFocus.post { initialFocus.requestFocus() }
    }

    private fun showSubtitlePicker() {
        val p = exoPlayer ?: return
        val selector = trackSelector ?: return
        val textGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val labels = mutableListOf("Off (Disable Subtitles)")
        val overrides = mutableListOf<TrackSelectionOverride?>()
        overrides.add(null)
        var selIdx = 0

        textGroups.forEach { group ->
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val lang = fmt.language ?: "sub"
                val label = fmt.label ?: lang.uppercase()
                if (group.isTrackSelected(i)) selIdx = labels.size
                val display = if (label.contains(lang, ignoreCase = true)) label else "$label ($lang)"
                labels.add(display)
                overrides.add(TrackSelectionOverride(group.mediaTrackGroup, i))
            }
        }

        TvUiKit.showSelectDialog(this, "Select Subtitles", labels, selectedIndex = selIdx, eyebrowLabel = "PLAYER / SUBTITLES") { idx ->
            if (idx == 0) {
                selector.setParameters(
                    selector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                )
            } else if (idx in overrides.indices) {
                val ov = overrides[idx]
                if (ov != null) {
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .addOverride(ov)
                    )
                }
            }
            scheduleOsdAutoHide()
        }
    }

    private fun findNextEpisode(): VideoEpisode? {
        if (payload.seriesEpisodes.isEmpty() || payload.videoId.isBlank()) return null
        val curIdx = payload.seriesEpisodes.indexOfFirst { it.id == payload.videoId }
        return if (curIdx >= 0 && curIdx + 1 < payload.seriesEpisodes.size) {
            payload.seriesEpisodes[curIdx + 1]
        } else null
    }

    private fun maybePreResolveBingeOrRecommendations(pos: Long, dur: Long, isEnded: Boolean) {
        val nextEp = findNextEpisode()
        if (nextEp != null) {
            if (!nextEpResolveCompleted && nextEpResolveJob == null && preResolvedNextEpisode == null) {
                val creditsStart = activeJfSession?.creditsStartMs?.takeIf { it > 0L }
                val inPreResolveTail = dur >= 180_000L && pos > 5_000L && (dur - pos) <= 55_000L
                val inPreResolveCredits = creditsStart != null &&
                    (dur <= 0L || creditsStart < dur) &&
                    pos >= (creditsStart - 10_000L).coerceAtLeast(0L)

                if (inPreResolveTail || inPreResolveCredits || isEnded) {
                    nextEpResolveJob = lifecycleScope.launch {
                        val resolved = runCatching {
                            StreamSimilarityMatcher.resolveNextEpisodePlayback(
                                context = this@PlayerActivity,
                                itemId = payload.itemId,
                                contentType = payload.contentType,
                                seriesTitle = payload.title,
                                poster = payload.poster,
                                background = payload.background,
                                nextEpisode = nextEp,
                                allEpisodes = payload.seriesEpisodes,
                                preferredBingeGroup = payload.bingeGroup,
                                previousStreamHint = payload.streamHint,
                                previousAddonName = payload.addonName,
                                previousWasMirror = payload.isMirror
                            )
                        }.getOrNull()
                        preResolvedNextEpisode = resolved
                        nextEpResolveCompleted = true
                        bingeSourceText.text = resolved?.sourceBadge?.takeIf { it.isNotBlank() }
                            ?: "Auto-match stream ready"
                    }
                }
            }
            return
        }

        if (!payload.isTwitchLive && !recFetchCompleted && recFetchJob == null) {
            val creditsStart = activeJfSession?.creditsStartMs?.takeIf { it > 0L }
            val inFilmPreFetch = dur >= 15 * 60_000L && pos > 5_000L && (dur - pos) <= 180_000L
            val inShortVodPreFetch = dur >= 90_000L && pos > 5_000L && (dur - pos) <= 40_000L
            val inCreditsPreFetch = creditsStart != null &&
                (dur <= 0L || creditsStart < dur) &&
                pos >= (creditsStart - 15_000L).coerceAtLeast(0L)

            if (inFilmPreFetch || inShortVodPreFetch || inCreditsPreFetch || isEnded) {
                ensureRecommendationsFetched()
            }
        }
    }

    private fun ensureRecommendationsFetched() {
        if (recFetchCompleted || recFetchJob != null) return
        recFetchJob = lifecycleScope.launch {
            val fetched = runCatching {
                RecommendationEngine.fetchRecommendations(
                    this@PlayerActivity,
                    payload.itemId,
                    payload.contentType,
                    payload.title,
                    limit = 8
                )
            }.getOrDefault(emptyList())
            recommendedItems = fetched
            recFetchCompleted = true
            creditsRecAdapter.submit(fetched)

            val p = exoPlayer
            val endedNow = p?.playbackState == Player.STATE_ENDED
            if (endedNow) {
                if (fetched.isEmpty()) {
                    finish()
                } else {
                    creditsRecDismissedDuringCredits = false
                    updateBingeAndRecommendationOverlays(
                        pos = lastKnownDurMs,
                        dur = lastKnownDurMs,
                        isEnded = true
                    )
                }
            } else if (fetched.isNotEmpty()) {
                val curPos = p?.currentPosition?.coerceAtLeast(0L) ?: lastKnownPosMs
                val curDur = p?.duration?.coerceAtLeast(0L) ?: lastKnownDurMs
                updateBingeAndRecommendationOverlays(
                    pos = curPos,
                    dur = curDur,
                    isEnded = false
                )
            }
        }
    }

    private fun updateBingeAndRecommendationOverlays(pos: Long, dur: Long, isEnded: Boolean) {
        val nextEp = findNextEpisode()
        if (nextEp != null) {
            if (creditsRecOverlay.visibility != View.GONE) {
                creditsRecOverlay.visibility = View.GONE
            }

            val creditsStart = activeJfSession?.creditsStartMs?.takeIf { it > 0L }
            val inCreditsTrigger = creditsStart != null &&
                (dur <= 0L || creditsStart < dur) &&
                pos >= creditsStart
            val inFinal35s = dur > 35_000L && pos > 5_000L && (dur - pos) <= 35_000L
            val inBingeWindow = isEnded || inCreditsTrigger || inFinal35s

            if (!inBingeWindow &&
                !isEnded &&
                dur > 0L &&
                (dur - pos) > 45_000L &&
                (creditsStart == null || pos < (creditsStart - 10_000L).coerceAtLeast(0L))
            ) {
                bingeCardDismissedForCredits = false
                autoCountdownCancelled = false
            }

            if (!autoCountdownCancelled && (isEnded || (dur > 35_000L && pos >= dur))) {
                launchNextEpisodeSeamless()
                return
            }

            val epTag = if (nextEp.season > 0 || nextEp.episode > 0) {
                String.format(Locale.US, "KEEP WATCHING · S%02dE%02d", nextEp.season, nextEp.episode)
            } else {
                "KEEP WATCHING · NEXT EP"
            }
            val countdownSuffix = if (!autoCountdownCancelled && !isEnded && dur > 0L && (dur - pos) in 1L..15_000L) {
                val secLeft = ((dur - pos + 999L) / 1000L).coerceIn(1L, 15L)
                " · AUTO IN ${secLeft}s"
            } else {
                ""
            }
            bingeEyebrowText.text = "$epTag$countdownSuffix"
            bingeTitleText.text = nextEp.title.ifBlank {
                if (nextEp.episode > 0) "Episode ${nextEp.episode}" else "Next Episode"
            }
            bingeSourceText.text = preResolvedNextEpisode?.sourceBadge?.takeIf { it.isNotBlank() }
                ?: if (nextEpResolveCompleted) "Auto-match stream ready" else "Matching best seeded source..."

            val watchBtnVisible = !isEnded
            if (bingeWatchCreditsBtn.visibility != (if (watchBtnVisible) View.VISIBLE else View.GONE)) {
                val hadFocus = bingeWatchCreditsBtn.hasFocus()
                bingeWatchCreditsBtn.visibility = if (watchBtnVisible) View.VISIBLE else View.GONE
                if (!watchBtnVisible && hadFocus) {
                    bingeStreamNextBtn.requestFocus()
                }
            }

            val shouldShowBinge = inBingeWindow &&
                !bingeCardDismissedForCredits &&
                osdRoot.visibility != View.VISIBLE

            if (shouldShowBinge) {
                val wasHidden = bingeNextCard.visibility != View.VISIBLE
                bingeNextCard.visibility = View.VISIBLE
                if (wasHidden) {
                    bingeStreamNextBtn.post {
                        if (bingeNextCard.visibility == View.VISIBLE && osdRoot.visibility != View.VISIBLE) {
                            bingeStreamNextBtn.requestFocus()
                        }
                    }
                }
            } else if (bingeNextCard.visibility != View.GONE) {
                bingeNextCard.visibility = View.GONE
            }
            return
        }

        if (bingeNextCard.visibility != View.GONE) {
            bingeNextCard.visibility = View.GONE
        }

        if (payload.isTwitchLive) {
            if (creditsRecOverlay.visibility != View.GONE) {
                creditsRecOverlay.visibility = View.GONE
            }
            return
        }

        val creditsStart = activeJfSession?.creditsStartMs?.takeIf { it > 0L }
        val inCreditsMarker = creditsStart != null &&
            (dur <= 0L || creditsStart < dur) &&
            pos >= creditsStart
        val inMovieCreditsWindow = dur >= 25 * 60_000L && pos > 5_000L && (dur - pos) <= 90_000L
        val inShortVodCreditsWindow = dur in 20_001L until (25 * 60_000L) &&
            pos > 5_000L &&
            (dur - pos) <= 20_000L
        val inRecWindow = isEnded || inCreditsMarker || inMovieCreditsWindow || inShortVodCreditsWindow

        if (!inRecWindow &&
            !isEnded &&
            dur > 0L &&
            (dur - pos) > 105_000L &&
            (creditsStart == null || pos < (creditsStart - 10_000L).coerceAtLeast(0L))
        ) {
            creditsRecDismissedDuringCredits = false
        }

        val showWatchCreditsPill = !isEnded
        if (creditsWatchBtn.visibility != (if (showWatchCreditsPill) View.VISIBLE else View.GONE)) {
            val hadFocus = creditsWatchBtn.hasFocus()
            creditsWatchBtn.visibility = if (showWatchCreditsPill) View.VISIBLE else View.GONE
            if (!showWatchCreditsPill && hadFocus) {
                focusFirstRecommendationCard()
            }
        }

        val shouldShowRec = inRecWindow &&
            recommendedItems.isNotEmpty() &&
            !creditsRecDismissedDuringCredits &&
            osdRoot.visibility != View.VISIBLE

        if (shouldShowRec) {
            val wasHidden = creditsRecOverlay.visibility != View.VISIBLE
            creditsRecOverlay.visibility = View.VISIBLE
            if (wasHidden) {
                focusFirstRecommendationCard()
            }
        } else if (creditsRecOverlay.visibility != View.GONE) {
            creditsRecOverlay.visibility = View.GONE
        }
    }

    private fun dismissBingeCardForCredits() {
        bingeCardDismissedForCredits = true
        autoCountdownCancelled = true
        bingeNextCard.visibility = View.GONE
    }

    private fun dismissCreditsRecForCredits() {
        if (exoPlayer?.playbackState == Player.STATE_ENDED) {
            finish()
            return
        }
        creditsRecDismissedDuringCredits = true
        creditsRecOverlay.visibility = View.GONE
    }

    private fun focusFirstRecommendationCard() {
        creditsRecRecycler.post {
            if (creditsRecOverlay.visibility == View.VISIBLE && osdRoot.visibility != View.VISIBLE) {
                val target = creditsRecRecycler.findViewHolderForAdapterPosition(0)?.itemView
                    ?: creditsRecRecycler.getChildAt(0)
                    ?: if (creditsWatchBtn.visibility == View.VISIBLE) creditsWatchBtn else creditsExitBtn
                target.requestFocus()
            }
        }
    }

    private fun isFocusInside(container: ViewGroup): Boolean {
        var cur: View? = currentFocus
        while (cur != null) {
            if (cur === container) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun ResolvedBingePlayback.toLaunchPayload(): LaunchPayload = LaunchPayload(
        title = title,
        subtitle = subtitle,
        itemId = itemId,
        videoId = videoId,
        contentType = contentType,
        poster = poster,
        background = background,
        directUrl = directUrl,
        headers = headers,
        ignoreSsl = ignoreSsl,
        magnetUri = magnetUri,
        infoHash = infoHash,
        fileIdx = fileIdx,
        trackers = trackers,
        resumePositionMs = 0L,
        bingeGroup = bingeGroup,
        streamHint = streamHint,
        addonName = addonName,
        isMirror = isMirror,
        labeledSubtitles = labeledSubtitles,
        seriesEpisodes = seriesEpisodes,
        dashMpdXml = dashMpdXml,
        fallbackDirectUrl = fallbackDirectUrl,
        youtubeVideoId = youtubeVideoId,
        ytPlaybackTrackingUrl = ytPlaybackTrackingUrl,
        ytWatchtimeTrackingUrl = ytWatchtimeTrackingUrl,
        ytCpn = ytCpn,
        jellyfinServerId = jellyfinServerId.ifBlank { null },
        jellyfinItemId = jellyfinItemId.ifBlank { null },
        jellyfinSession = jellyfinSession
    )

    private fun launchResolvedBingePlayback(resolved: ResolvedBingePlayback) {
        if (isLaunchingTransition) return
        isLaunchingTransition = true
        val nextPayload = resolved.toLaunchPayload()
        saveProgressNow()
        releaseEverything()
        start(this, nextPayload)
        finish()
    }

    private fun launchNextEpisodeSeamless() {
        val resolved = preResolvedNextEpisode
        if (resolved != null) {
            launchResolvedBingePlayback(resolved)
        } else {
            playNextEpisode()
        }
    }

    private fun onRecommendedCardClicked(item: MetaItem) {
        if (isLaunchingTransition) return
        recResolveJob?.cancel()
        creditsRecSpinner.visibility = View.VISIBLE
        creditsRecStatusText.text = "Resolving best stream for ${item.name}..."
        creditsRecStatusText.visibility = View.VISIBLE

        recResolveJob = lifecycleScope.launch {
            val resolved = runCatching {
                RecommendationEngine.resolveRecommendedPlayback(
                    this@PlayerActivity,
                    item,
                    preferredResolutionHint = payload.streamHint.ifBlank { "1080p" },
                    preferMirror = payload.isMirror
                )
            }.getOrNull()
            creditsRecSpinner.visibility = View.GONE
            creditsRecStatusText.visibility = View.GONE

            if (resolved != null) {
                launchResolvedBingePlayback(resolved)
            } else {
                openRecommendedDetail(item)
            }
        }
    }

    private fun openRecommendedDetail(item: MetaItem) {
        if (isLaunchingTransition) return
        isLaunchingTransition = true
        recResolveJob?.cancel()
        saveProgressNow()
        releaseEverything()
        DetailActivity.start(this@PlayerActivity, item.id, item.type, item.name, item.poster)
        finish()
    }

    private fun playNextEpisode() {
        val nextEp = findNextEpisode() ?: return
        if (isLaunchingTransition) return
        isLaunchingTransition = true
        saveProgressNow()
        releaseEverything()
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_ITEM_ID, payload.itemId)
            putExtra(DetailActivity.EXTRA_CONTENT_TYPE, payload.contentType)
            putExtra(DetailActivity.EXTRA_TITLE, payload.title)
            putExtra(DetailActivity.EXTRA_POSTER, payload.poster)
            putExtra(DetailActivity.EXTRA_AUTO_EPISODE_ID, nextEp.id)
            putExtra(DetailActivity.EXTRA_BINGE_GROUP, payload.bingeGroup)
            putExtra(DetailActivity.EXTRA_STREAM_HINT, payload.streamHint)
            putExtra(DetailActivity.EXTRA_ADDON_NAME, payload.addonName)
            putExtra(DetailActivity.EXTRA_WAS_MIRROR, payload.isMirror)
        }
        startActivity(intent)
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (isScrubbing && !isTouchTrackingScrub) {
                        scheduleScrubAutoCommit(350L)
                        return true
                    }
                }
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            val osdVisible = osdRoot.visibility == View.VISIBLE
            val bingeVisible = !osdVisible && bingeNextCard.visibility == View.VISIBLE
            val recVisible = !osdVisible && creditsRecOverlay.visibility == View.VISIBLE
            val isRepeat = event.repeatCount > 0

            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    stepAcceleratedDpadScrub(-1, isRepeat)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    stepAcceleratedDpadScrub(1, isRepeat)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (isScrubbing) {
                        commitScrubNow()
                        return true
                    } else if (bingeVisible) {
                        if (!isFocusInside(bingeNextCard)) {
                            bingeStreamNextBtn.requestFocus()
                            return true
                        }
                    } else if (recVisible) {
                        if (!isFocusInside(creditsRecOverlay)) {
                            focusFirstRecommendationCard()
                            return true
                        }
                    } else if (!osdVisible && skipSegmentBtn.visibility == View.VISIBLE) {
                        skipSegmentBtn.performClick()
                        return true
                    } else if (!osdVisible || currentFocus === seekBar) {
                        togglePlayPause()
                        return true
                    } else {
                        scheduleOsdAutoHide()
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (bingeVisible) {
                        if (!isFocusInside(bingeNextCard)) {
                            bingeStreamNextBtn.requestFocus()
                            return true
                        }
                    } else if (recVisible) {
                        if (!isFocusInside(creditsRecOverlay)) {
                            focusFirstRecommendationCard()
                            return true
                        }
                    } else if (!osdVisible || isScrubbing || currentFocus === seekBar) {
                        stepAcceleratedDpadScrub(-1, isRepeat)
                        return true
                    } else {
                        scheduleOsdAutoHide()
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (bingeVisible) {
                        if (!isFocusInside(bingeNextCard)) {
                            bingeStreamNextBtn.requestFocus()
                            return true
                        }
                    } else if (recVisible) {
                        if (!isFocusInside(creditsRecOverlay)) {
                            focusFirstRecommendationCard()
                            return true
                        }
                    } else if (!osdVisible || isScrubbing || currentFocus === seekBar) {
                        stepAcceleratedDpadScrub(1, isRepeat)
                        return true
                    } else {
                        scheduleOsdAutoHide()
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (recVisible) {
                        if (isFocusInside(creditsRecRecycler)) {
                            val headerBtn = if (creditsWatchBtn.visibility == View.VISIBLE) {
                                creditsWatchBtn
                            } else {
                                creditsExitBtn
                            }
                            headerBtn.requestFocus()
                            return true
                        } else if (exoPlayer?.playbackState != Player.STATE_ENDED) {
                            showOsd(focusTimeline = true)
                            return true
                        } else {
                            return true
                        }
                    } else if (!osdVisible) {
                        if (exoPlayer?.playbackState != Player.STATE_ENDED) {
                            showOsd(focusTimeline = true)
                        }
                        return true
                    } else if (currentFocus !== seekBar) {
                        seekBar.requestFocus()
                        scheduleOsdAutoHide()
                        return true
                    } else {
                        scheduleOsdAutoHide()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (recVisible) {
                        if (currentFocus === creditsWatchBtn ||
                            currentFocus === creditsExitBtn ||
                            !isFocusInside(creditsRecRecycler)
                        ) {
                            focusFirstRecommendationCard()
                        }
                        return true
                    } else if (bingeVisible) {
                        if (!isFocusInside(bingeNextCard)) {
                            bingeStreamNextBtn.requestFocus()
                        }
                        return true
                    } else if (!osdVisible) {
                        if (exoPlayer?.playbackState != Player.STATE_ENDED) {
                            showOsd(focusButtons = true)
                        }
                        return true
                    } else if (currentFocus === seekBar || isScrubbing) {
                        if (isScrubbing) {
                            commitScrubNow()
                        }
                        playPauseBtn.requestFocus()
                        scheduleOsdAutoHide()
                        return true
                    } else {
                        scheduleOsdAutoHide()
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (recVisible && isFocusInside(creditsRecRecycler)) {
                        // Allow focused recommendation card's OnKeyListener to handle MENU -> Detail
                    } else if (!osdVisible) {
                        if (exoPlayer?.playbackState != Player.STATE_ENDED) {
                            showOsd(focusButtons = true)
                        }
                        return true
                    } else {
                        scheduleOsdAutoHide()
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (isScrubbing) {
                        cancelScrub()
                        Toast.makeText(this, "Scrub cancelled", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    if (osdVisible) {
                        hideOsdAndRestoreOverlays()
                        return true
                    }
                    if (bingeVisible) {
                        if (exoPlayer?.playbackState == Player.STATE_ENDED) {
                            finish()
                        } else {
                            dismissBingeCardForCredits()
                        }
                        return true
                    }
                    if (recVisible) {
                        if (recResolveJob?.isActive == true) {
                            recResolveJob?.cancel()
                            recResolveJob = null
                            creditsRecSpinner.visibility = View.GONE
                            creditsRecStatusText.visibility = View.GONE
                            return true
                        }
                        if (exoPlayer?.playbackState == Player.STATE_ENDED) {
                            finish()
                        } else {
                            dismissCreditsRecForCredits()
                        }
                        return true
                    }
                    if (System.currentTimeMillis() < lastSponsorUndoUntilMs && lastSponsorUndoOriginMs >= 0L) {
                        val undoPos = lastSponsorUndoOriginMs
                        lastSponsorUndoUntilMs = 0L
                        lastSponsorUndoOriginMs = -1L
                        lastSponsorUndoUuid?.let { ignoredSponsorUuids.add(it) }
                        performPlayerSeekTo(undoPos)
                        Toast.makeText(this, "Undid SponsorBlock skip", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildYouTubeAuthContext(): YouTubeAuthContext? {
        val appCtx = applicationContext
        val synced = AppPrefs.getYouTubeOAuth(appCtx) ?: return null
        if (!synced.isActive) return null
        return YouTubeAuthContext(
            accessToken = synced.accessToken.takeIf { it.isNotBlank() },
            refreshToken = synced.refreshToken.takeIf { it.isNotBlank() },
            expiresAtMs = synced.accessTokenExpiresAtMs,
            clientId = synced.clientId.takeIf { it.isNotBlank() },
            clientSecret = synced.clientSecret.takeIf { it.isNotBlank() },
            pageId = synced.pageId.takeIf { it.isNotBlank() },
            accountId = synced.channelId.takeIf { it.isNotBlank() },
            deArrowEnabled = AppPrefs.isDeArrowEnabled(appCtx),
            onTokenRefreshed = { refreshed ->
                val latest = AppPrefs.getYouTubeOAuth(appCtx) ?: synced
                AppPrefs.saveYouTubeOAuth(
                    appCtx,
                    latest.copy(
                        accessToken = refreshed.accessToken,
                        refreshToken = refreshed.refreshToken.ifBlank { latest.refreshToken },
                        clientId = refreshed.clientId.ifBlank { latest.clientId },
                        clientSecret = refreshed.clientSecret.ifBlank { latest.clientSecret },
                        accessTokenExpiresAtMs = refreshed.expiresAtMs,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        )
    }

    private fun reportYouTubeWatchProgressAsync(posMs: Long, durMs: Long) {
        val ytVid = payload.youtubeVideoId?.takeIf { it.isNotBlank() } ?: return
        val appCtx = applicationContext
        if (!AppPrefs.isYouTubeWatchHistoryEnabled(appCtx)) return
        val ytAuth = buildYouTubeAuthContext() ?: return
        if (!ytAuth.isSignedIn) return
        val pbUrl = payload.ytPlaybackTrackingUrl
        val wtUrl = payload.ytWatchtimeTrackingUrl
        val cpn = payload.ytCpn
        stopReportScope.launch {
            runCatching {
                YouTubeApiClient.reportWatchTime(
                    playbackTrackingUrl = pbUrl,
                    watchtimeTrackingUrl = wtUrl,
                    cpn = cpn,
                    positionSec = posMs.coerceAtLeast(0L) / 1000f,
                    durationSec = durMs.coerceAtLeast(0L) / 1000f,
                    authContext = ytAuth,
                    videoId = ytVid
                )
            }
        }
    }

    private fun saveProgressNow() {
        val p = exoPlayer ?: return
        if (payload.itemId.isBlank()) return
        val pos = effectiveDisplayPositionMs(p)
        val dur = p.duration.coerceAtLeast(0L)
        if (pos < 5_000L) return

        val entry = WatchHistoryEntry(
            itemId = payload.itemId,
            videoId = payload.videoId.ifBlank { payload.itemId },
            type = payload.contentType,
            name = payload.title,
            episodeTitle = payload.subtitle,
            poster = payload.poster,
            background = payload.background,
            timeOffsetMs = pos,
            durationMs = dur,
            updatedAtMs = System.currentTimeMillis(),
            directStreamUrl = activeDirectUrl ?: payload.directUrl,
            directHeaders = payload.headers,
            infoHash = payload.infoHash,
            fileIdx = payload.fileIdx
        )
        val appCtx = applicationContext
        AppPrefs.upsertWatchHistory(appCtx, entry)

        val isPrivateOrCreatorSource = JellyfinIds.isJellyfinId(payload.itemId) ||
            payload.itemId.startsWith("yt:") ||
            payload.itemId.startsWith("twitch:") ||
            payload.itemId.startsWith("odysee:") ||
            payload.itemId.startsWith("rumble:")
        if (!isPrivateOrCreatorSource) {
            stopReportScope.launch {
                StremioApiClient.pushWatchProgress(appCtx, entry)
            }
        }
    }

    private fun releaseEverything() {
        scrubCommitJob?.cancel()
        scrubCommitJob = null
        isScrubbing = false
        uiTickerJob?.cancel()
        osdHideJob?.cancel()
        nextEpResolveJob?.cancel()
        recFetchJob?.cancel()
        recResolveJob?.cancel()
        val finalPos = exoPlayer?.currentPosition?.takeIf { it > 0L } ?: lastKnownPosMs
        val finalDur = exoPlayer?.duration?.takeIf { it > 0L } ?: lastKnownDurMs

        saveProgressNow()

        val jfProf = activeJfProfile
        val jfSess = activeJfSession
        if (!jfReportedStop && jfProf != null && jfSess != null && activeJfItemId.isNotBlank()) {
            jfReportedStop = true
            JellyfinApiClient.reportPlaybackStopped(
                context = applicationContext,
                profile = jfProf,
                itemId = activeJfItemId,
                session = jfSess,
                positionMs = finalPos
            )
        }

        if (!ytReportedStop && ytReportedStart && !payload.youtubeVideoId.isNullOrBlank()) {
            ytReportedStop = true
            reportYouTubeWatchProgressAsync(finalPos, finalDur)
        }

        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        torrentEngine?.stopAndCleanup()
        torrentEngine = null
    }

    override fun onStop() {
        super.onStop()
        releaseEverything()
        if (!isFinishing) {
            finish()
        }
    }

    override fun onDestroy() {
        releaseEverything()
        super.onDestroy()
    }

    private class RoutingDataSource(
        private val torrentDs: DataSource,
        private val standardDs: DataSource
    ) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            torrentDs.addTransferListener(transferListener)
            standardDs.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target = if (dataSpec.uri.scheme == "torrent") torrentDs else standardDs
            active = target
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = active?.uri

        override fun close() {
            active?.close()
            active = null
        }
    }

    private class PermissiveOkHttpDataSource(
        private val defaultHeaders: Map<String, String>,
        private val userAgent: String
    ) : BaseDataSource(true) {

        private var response: Response? = null
        private var stream: InputStream? = null
        private var uri: Uri? = null
        private var bytesRemaining: Long = 0L
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)

            val reqBuilder = Request.Builder().url(dataSpec.uri.toString())
            reqBuilder.header("User-Agent", userAgent)
            for ((k, v) in defaultHeaders) {
                reqBuilder.header(k, v)
            }
            for ((k, v) in dataSpec.httpRequestHeaders) {
                reqBuilder.header(k, v)
            }
            if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val rangeEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    (dataSpec.position + dataSpec.length - 1L).toString()
                } else ""
                reqBuilder.header("Range", "bytes=${dataSpec.position}-$rangeEnd")
            }

            val resp = BrootLiteApp.sharedPermissiveHttpClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful) {
                resp.close()
                throw IOException("HTTP ${resp.code} opening stream")
            }
            response = resp
            val body = resp.body ?: throw IOException("Empty HTTP body")
            stream = body.byteStream()

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                val cl = body.contentLength()
                if (cl >= 0L) cl else C.LENGTH_UNSET.toLong()
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
            val read = stream?.read(buffer, offset, toRead) ?: -1
            if (read < 0) return C.RESULT_END_OF_INPUT
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= read
            }
            bytesTransferred(read)
            return read
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            uri = null
            runCatching { stream?.close() }
            stream = null
            runCatching { response?.close() }
            response = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }

        class Factory(
            private val headers: Map<String, String>,
            private val userAgent: String
        ) : DataSource.Factory {
            override fun createDataSource(): DataSource = PermissiveOkHttpDataSource(headers, userAgent)
        }
    }

    private class CreditsRecAdapter(
        private val onClick: (MetaItem) -> Unit,
        private val onLongClick: (MetaItem) -> Unit
    ) : RecyclerView.Adapter<CreditsRecAdapter.VH>() {

        private var items: List<MetaItem> = emptyList()

        class VH(
            val root: LinearLayout,
            val poster: ImageView,
            val title: TextView,
            val sub: TextView
        ) : RecyclerView.ViewHolder(root)

        fun submit(newItems: List<MetaItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val cardW = TvUiKit.dp(ctx, 96)
            val imgH = TvUiKit.dp(ctx, 138)

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = TvUiKit.dp(ctx, 10)
                    topMargin = TvUiKit.dp(ctx, 4)
                    bottomMargin = TvUiKit.dp(ctx, 4)
                }
                setPadding(TvUiKit.dp(ctx, 4), TvUiKit.dp(ctx, 4), TvUiKit.dp(ctx, 4), TvUiKit.dp(ctx, 6))
                isLongClickable = true
                TvUiKit.applyCardFocusEffect(this, 1.05f)
            }

            val poster = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imgH)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#111722"))
                TvUiKit.applyRoundedClip(this, 8f)
            }

            val title = TextView(ctx).apply {
                setTextColor(XirtusPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = XirtusFonts.medium(ctx)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(TvUiKit.dp(ctx, 2), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 2), 0)
            }

            val sub = TextView(ctx).apply {
                setTextColor(XirtusPalette.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = XirtusFonts.monoRegular(ctx)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(TvUiKit.dp(ctx, 2), TvUiKit.dp(ctx, 1), TvUiKit.dp(ctx, 2), 0)
            }

            root.addView(poster)
            root.addView(title)
            root.addView(sub)
            return VH(root, poster, title, sub)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.name
            val yr = item.releaseInfo.takeIf { it.isNotBlank() }?.take(9)
            val rt = item.imdbRating.takeIf { it.isNotBlank() }?.let { "★ $it" }
            holder.sub.text = when {
                yr != null && rt != null -> "$yr · $rt"
                rt != null -> rt
                yr != null -> yr
                else -> item.type.replaceFirstChar { it.uppercase(Locale.US) }
            }

            if (item.poster.isNotBlank()) {
                val imgData: Any = if (item.poster.startsWith("/")) File(item.poster) else item.poster
                holder.poster.load(imgData) {
                    bitmapConfig(Bitmap.Config.RGB_565)
                    allowHardware(false)
                    allowRgb565(true)
                    size(192, 276)
                }
            } else {
                holder.poster.setImageDrawable(null)
            }

            holder.root.setOnClickListener { onClick(item) }
            holder.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
            holder.root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    onLongClick(item)
                    true
                } else {
                    false
                }
            }
        }
    }
}

