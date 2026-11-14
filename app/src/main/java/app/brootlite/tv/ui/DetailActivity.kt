package app.brootlite.tv.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.brootlite.tv.data.AppPrefs
import app.brootlite.tv.data.FavoriteEntry
import app.brootlite.tv.data.MetaItem
import app.brootlite.tv.data.NativeTorrentSearch
import app.brootlite.tv.data.StreamCandidate
import app.brootlite.tv.data.StreamSimilarityMatcher
import app.brootlite.tv.data.StremioApiClient
import app.brootlite.tv.data.SwarmFilter
import app.brootlite.tv.data.VideoEpisode
import app.brootlite.tv.jellyfin.JellyfinApiClient
import app.brootlite.tv.jellyfin.JellyfinEndpointRouter
import app.brootlite.tv.jellyfin.JellyfinIds
import app.brootlite.tv.jellyfin.JellyfinServerProfile
import app.brootlite.tv.jellyfin.JfItem
import app.brootlite.tv.jellyfin.JfMediaSource
import app.brootlite.tv.kodi.PyAddonClient
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Locale

class DetailActivity : AppCompatActivity() {

    private enum class SourceFilterMode {
        ALL,
        MIRRORS_DIRECT,
        P2P_SWARM
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_CONTENT_TYPE = "extra_content_type"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_POSTER = "extra_poster"
        const val EXTRA_RESUME_VIDEO_ID = "extra_resume_video_id"
        const val EXTRA_RESUME_OFFSET_MS = "extra_resume_offset_ms"
        const val EXTRA_AUTO_EPISODE_ID = "extra_auto_episode_id"
        const val EXTRA_BINGE_GROUP = "extra_binge_group"
        const val EXTRA_STREAM_HINT = "extra_stream_hint"
        const val EXTRA_ADDON_NAME = "extra_addon_name"
        const val EXTRA_WAS_MIRROR = "extra_was_mirror"

        fun start(
            context: Context,
            itemId: String,
            contentType: String,
            title: String,
            poster: String = "",
            resumeVideoId: String = "",
            resumeOffsetMs: Long = 0L
        ) {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
                putExtra(EXTRA_CONTENT_TYPE, contentType)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_POSTER, poster)
                putExtra(EXTRA_RESUME_VIDEO_ID, resumeVideoId)
                putExtra(EXTRA_RESUME_OFFSET_MS, resumeOffsetMs)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var itemId: String
    private lateinit var contentType: String
    private var initialPosterUrl: String = ""
    private var meta: MetaItem? = null
    private var selectedEpisode: VideoEpisode? = null
    private var currentStreams: List<StreamCandidate> = emptyList()
    private var activeFilterMode: SourceFilterMode = SourceFilterMode.ALL
    private var streamLoadJob: Job? = null
    private var resumeVideoId: String = ""
    private var resumeOffsetMs: Long = 0L
    private var hasConsumedInitialResume: Boolean = false
    private var autoPlayEpisodeId: String = ""
    private var preferredBingeGroup: String = ""
    private var preferredStreamHint: String = ""
    private var preferredAddonName: String = ""
    private var preferredWasMirror: Boolean = false

    // Native Multi-Server Jellyfin state
    private var isJellyfin: Boolean = false
    private var jellyfinProfile: JellyfinServerProfile? = null
    private var jellyfinBaseUrl: String = ""
    private var activeJellyfinItem: JfItem? = null
    private val jellyfinEpisodeResumeMs = HashMap<String, Long>()

    private lateinit var backdropImg: ImageView
    private lateinit var posterImg: ImageView
    private lateinit var titleView: TextView
    private lateinit var metaTagsView: TextView
    private lateinit var plotView: TextView
    private lateinit var favBtn: TextView
    private lateinit var seasonContainer: LinearLayout
    private lateinit var episodeContainer: LinearLayout
    private lateinit var seriesSection: LinearLayout
    private lateinit var streamHeader: TextView
    private lateinit var streamSpinner: ProgressBar
    private lateinit var filterAllBtn: TextView
    private lateinit var filterMirrorsBtn: TextView
    private lateinit var filterP2pBtn: TextView
    private lateinit var streamAdapter: StreamListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: "movie"
        val fallbackTitle = intent.getStringExtra(EXTRA_TITLE) ?: itemId
        val fallbackPoster = intent.getStringExtra(EXTRA_POSTER) ?: ""
        initialPosterUrl = fallbackPoster
        resumeVideoId = intent.getStringExtra(EXTRA_RESUME_VIDEO_ID) ?: ""
        resumeOffsetMs = intent.getLongExtra(EXTRA_RESUME_OFFSET_MS, 0L)
        autoPlayEpisodeId = intent.getStringExtra(EXTRA_AUTO_EPISODE_ID) ?: ""
        preferredBingeGroup = intent.getStringExtra(EXTRA_BINGE_GROUP) ?: ""
        preferredStreamHint = intent.getStringExtra(EXTRA_STREAM_HINT) ?: ""
        preferredAddonName = intent.getStringExtra(EXTRA_ADDON_NAME) ?: ""
        preferredWasMirror = intent.getBooleanExtra(EXTRA_WAS_MIRROR, false)

        isJellyfin = JellyfinIds.isJellyfinId(itemId)
        if (isJellyfin) {
            val parsed = JellyfinIds.parseId(itemId)
            val profile = parsed?.first?.let { AppPrefs.getJellyfinServer(this, it) }
            if (profile == null) {
                Toast.makeText(this, "Jellyfin server not found or disconnected", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            jellyfinProfile = profile
        }

        buildUi(fallbackTitle, fallbackPoster)
        loadMetadataAndStreams(fallbackTitle, fallbackPoster)
    }

    private fun buildUi(initialTitle: String, initialPoster: String) {
        val root = FrameLayout(this).apply {
            background = TvUiKit.createRootCanvasDrawable()
        }

        backdropImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.14f
        }
        root.addView(backdropImg)

        root.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#EB0D1118"), Color.parseColor("#BD101620"))
            )
        })

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(
                TvUiKit.dp(this@DetailActivity, 28),
                TvUiKit.dp(this@DetailActivity, 22),
                TvUiKit.dp(this@DetailActivity, 28),
                TvUiKit.dp(this@DetailActivity, 20)
            )
        }

        // Left Frosted Glass Metadata Card
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(TvUiKit.dp(this@DetailActivity, 344), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = TvUiKit.dp(this@DetailActivity, 22)
            }
            setPadding(
                TvUiKit.dp(this@DetailActivity, 20),
                TvUiKit.dp(this@DetailActivity, 18),
                TvUiKit.dp(this@DetailActivity, 20),
                TvUiKit.dp(this@DetailActivity, 18)
            )
            background = TvUiKit.createGlassPanelDrawable(this@DetailActivity, radiusDp = 22f)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, TvUiKit.dp(this@DetailActivity, 12))
        }
        val backBtn = TvUiKit.createPillButton(this, "← Back", compact = true) { finish() }
        favBtn = TvUiKit.createPillButton(this, "☆ Library", compact = true) { toggleFavorite() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = TvUiKit.dp(this@DetailActivity, 8) }
        }
        topRow.addView(backBtn)
        topRow.addView(favBtn)
        leftCol.addView(topRow)

        posterImg = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(TvUiKit.dp(this@DetailActivity, 136), TvUiKit.dp(this@DetailActivity, 196)).apply {
                bottomMargin = TvUiKit.dp(this@DetailActivity, 12)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#111722"))
            TvUiKit.applyRoundedClip(this, 12f)
            if (initialPoster.isNotBlank()) load(initialPoster)
        }
        leftCol.addView(posterImg)

        val leftEyebrowLabel = if (isJellyfin) {
            "JELLYFIN / ${(jellyfinProfile?.name ?: "SERVER").uppercase(Locale.US)}"
        } else {
            "XIRTUS / ${contentType.uppercase(Locale.US)}"
        }
        leftCol.addView(TvUiKit.eyebrow(this, leftEyebrowLabel))

        titleView = TextView(this).apply {
            text = initialTitle
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = XirtusFonts.bold(this@DetailActivity)
            letterSpacing = -0.02f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, TvUiKit.dp(this@DetailActivity, 2), 0, 0)
        }
        leftCol.addView(titleView)

        metaTagsView = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = XirtusFonts.monoRegular(this@DetailActivity)
            setPadding(0, TvUiKit.dp(this@DetailActivity, 5), 0, TvUiKit.dp(this@DetailActivity, 8))
        }
        leftCol.addView(metaTagsView)

        leftCol.addView(TvUiKit.hairlineDivider(this, bottomMarginDp = 8))

        plotView = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_BODY)
            typeface = XirtusFonts.regular(this@DetailActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.3f)
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        leftCol.addView(plotView)

        val actionBtnLabel = if (isJellyfin) {
            "Max Bitrate · ${jellyfinProfile?.maxBitrateLabel ?: "Unlimited"}"
        } else {
            "Search Direct P2P Swarm ↗"
        }
        lateinit var bottomLeftBtn: TextView
        bottomLeftBtn = TvUiKit.createPillButton(this, actionBtnLabel) {
            if (isJellyfin) {
                showJellyfinBitratePicker(bottomLeftBtn)
            } else {
                runDirectSwarmFallbackSearch()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = TvUiKit.dp(this@DetailActivity, 10) }
        }
        leftCol.addView(bottomLeftBtn)

        // Right Frosted Glass Episodes + Streams Panel
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setPadding(
                TvUiKit.dp(this@DetailActivity, 20),
                TvUiKit.dp(this@DetailActivity, 18),
                TvUiKit.dp(this@DetailActivity, 20),
                TvUiKit.dp(this@DetailActivity, 16)
            )
            background = TvUiKit.createGlassPanelDrawable(this@DetailActivity, radiusDp = 22f)
        }

        seriesSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, TvUiKit.dp(this@DetailActivity, 10))
        }
        seriesSection.addView(TvUiKit.eyebrow(this, "SERIES / SEASONS & EPISODES").apply {
            setPadding(0, 0, 0, TvUiKit.dp(this@DetailActivity, 6))
        })
        val seasonScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        seasonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, TvUiKit.dp(this@DetailActivity, 6))
        }
        seasonScroll.addView(seasonContainer)

        val epScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        episodeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, TvUiKit.dp(this@DetailActivity, 8))
        }
        epScroll.addView(episodeContainer)

        seriesSection.addView(seasonScroll)
        seriesSection.addView(epScroll)
        seriesSection.addView(TvUiKit.hairlineDivider(this))
        rightCol.addView(seriesSection)

        val streamHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, TvUiKit.dp(this@DetailActivity, 8))
        }
        val streamHeaderStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sourcesEyebrow = if (isJellyfin) {
            "JELLYFIN / DIRECT PLAY, DIRECT STREAM & HLS TRANSCODE"
        } else {
            "SOURCES / MIRRORS, HTTP & P2P SWARM"
        }
        streamHeaderStack.addView(TvUiKit.eyebrow(this, sourcesEyebrow))
        streamHeader = TextView(this).apply {
            text = if (isJellyfin) "Playback Modes" else "Available Streams"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = XirtusFonts.semiBold(this@DetailActivity)
            letterSpacing = -0.015f
        }
        streamHeaderStack.addView(streamHeader)

        streamSpinner = ProgressBar(this).apply {
            val sz = TvUiKit.dp(this@DetailActivity, 18)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = TvUiKit.dp(this@DetailActivity, 12) }
            visibility = View.GONE
        }
        streamHeaderRow.addView(streamHeaderStack)
        streamHeaderRow.addView(streamSpinner)
        rightCol.addView(streamHeaderRow)

        // 1-Click Source Filter Bar: All · Mirrors & Direct · P2P Swarm (hidden for native Jellyfin)
        val sourceFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, TvUiKit.dp(this@DetailActivity, 10))
            visibility = if (isJellyfin) View.GONE else View.VISIBLE
        }
        filterAllBtn = TvUiKit.createPillButton(this, "All (0)", selected = true, compact = true) {
            setSourceFilter(SourceFilterMode.ALL)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@DetailActivity, 6)
            }
        }
        filterMirrorsBtn = TvUiKit.createPillButton(this, "Mirrors & Direct (0)", selected = false, compact = true) {
            setSourceFilter(SourceFilterMode.MIRRORS_DIRECT)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@DetailActivity, 6)
            }
        }
        filterP2pBtn = TvUiKit.createPillButton(this, "P2P Swarm (0)", selected = false, compact = true) {
            setSourceFilter(SourceFilterMode.P2P_SWARM)
        }
        sourceFilterRow.addView(filterAllBtn)
        sourceFilterRow.addView(filterMirrorsBtn)
        sourceFilterRow.addView(filterP2pBtn)
        rightCol.addView(sourceFilterRow)

        streamAdapter = StreamListAdapter(
            items = emptyList(),
            onPlay = { stream -> launchStream(stream) },
            onLongClick = { stream ->
                val mag = stream.toMagnetUri()
                if (mag != null) {
                    val added = AppPrefs.toggleFavorite(
                        this,
                        FavoriteEntry(
                            id = "torrent:${stream.infoHash ?: mag.hashCode()}",
                            type = "torrent",
                            name = stream.filename.ifBlank { titleView.text.toString() },
                            poster = meta?.poster ?: initialPoster,
                            subtitle = "${stream.addonName} · ${stream.sizeLabel}",
                            magnetUri = mag
                        )
                    )
                    Toast.makeText(this, if (added) "Saved Torrent to Library" else "Removed from Library", Toast.LENGTH_SHORT).show()
                }
            }
        )
        val streamsRv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@DetailActivity)
            itemAnimator = null
            adapter = streamAdapter
        }
        rightCol.addView(streamsRv)

        mainRow.addView(leftCol)
        mainRow.addView(rightCol)
        root.addView(mainRow)

        setContentView(root)
        refreshFavoriteButton()
    }

    private fun showJellyfinBitratePicker(btn: TextView) {
        val profile = jellyfinProfile ?: return
        val tiers = listOf(
            0 to "Unlimited (120 Mbps · Direct Play)",
            80 to "80 Mbps (4K UHD Remux)",
            40 to "40 Mbps (4K HDR)",
            20 to "20 Mbps (1080p High)",
            10 to "10 Mbps (1080p Remote WAN)",
            6 to "6 Mbps (720p Remote)",
            3 to "3 Mbps (480p Low Bandwidth)"
        )
        val selIdx = tiers.indexOfFirst { it.first == profile.maxBitrateMbps }.coerceAtLeast(0)
        TvUiKit.showSelectDialog(
            this,
            "Max Streaming Bitrate (${profile.name})",
            tiers.map { it.second },
            selectedIndex = selIdx,
            eyebrowLabel = "JELLYFIN / STREAMING BITRATE"
        ) { idx ->
            if (idx in tiers.indices) {
                val chosenMbps = tiers[idx].first
                val updated = profile.copy(
                    maxBitrateMbps = chosenMbps,
                    updatedAtMs = System.currentTimeMillis()
                )
                jellyfinProfile = updated
                AppPrefs.upsertJellyfinServer(this, updated)
                btn.text = "Max Bitrate · ${updated.maxBitrateLabel}"
                activeJellyfinItem?.let { curJf ->
                    currentStreams = buildJellyfinStreamCandidates(updated, curJf, JellyfinIds.buildId(updated.serverId, curJf.Id))
                    applySourceFilterToAdapter()
                }
            }
        }
    }

    private fun setSourceFilter(mode: SourceFilterMode) {
        activeFilterMode = mode
        TvUiKit.setPillSelected(filterAllBtn, mode == SourceFilterMode.ALL)
        TvUiKit.setPillSelected(filterMirrorsBtn, mode == SourceFilterMode.MIRRORS_DIRECT)
        TvUiKit.setPillSelected(filterP2pBtn, mode == SourceFilterMode.P2P_SWARM)
        applySourceFilterToAdapter()
    }

    private fun applySourceFilterToAdapter() {
        val mirrorsCount = currentStreams.count { it.isMirrorOrDirect }
        val p2pCount = currentStreams.count { it.isTorrent }
        filterAllBtn.text = "All (${currentStreams.size})"
        filterMirrorsBtn.text = "Mirrors & Direct ($mirrorsCount)"
        filterP2pBtn.text = "P2P Swarm ($p2pCount)"

        val filtered = when (activeFilterMode) {
            SourceFilterMode.ALL -> currentStreams
            SourceFilterMode.MIRRORS_DIRECT -> currentStreams.filter { it.isMirrorOrDirect }
            SourceFilterMode.P2P_SWARM -> currentStreams.filter { it.isTorrent }
        }
        streamAdapter.submit(filtered)
    }

    private fun refreshFavoriteButton() {
        val fav = AppPrefs.isFavorite(this, itemId)
        favBtn.text = if (fav) "★ In Library" else "☆ Library"
        TvUiKit.setPillSelected(favBtn, fav)
    }

    private fun toggleFavorite() {
        val m = meta
        val subtitleText = if (isJellyfin) {
            "Jellyfin · ${jellyfinProfile?.name ?: "Server"}"
        } else {
            "Stremio · ${contentType.replaceFirstChar { it.uppercase() }}"
        }
        val added = AppPrefs.toggleFavorite(
            this,
            FavoriteEntry(
                id = itemId,
                type = m?.type ?: contentType,
                name = m?.name ?: titleView.text.toString(),
                poster = m?.poster?.ifBlank { initialPosterUrl } ?: initialPosterUrl,
                subtitle = subtitleText
            )
        )
        refreshFavoriteButton()
        Toast.makeText(this, if (added) "Added to Library" else "Removed from Library", Toast.LENGTH_SHORT).show()
    }

    private fun bindMetaHeaderUi(fetched: MetaItem) {
        titleView.text = fetched.name
        if (fetched.poster.isNotBlank()) posterImg.load(fetched.poster)
        if (fetched.background.isNotBlank()) backdropImg.load(fetched.background)
        plotView.text = fetched.description.ifBlank {
            if (isJellyfin) {
                "Select Direct Play or Compatibility HLS Transcode on the right to begin hardware playback."
            } else {
                "Select an episode, HTTP mirror, or swarm source on the right to begin hardware playback."
            }
        }
        metaTagsView.text = listOfNotNull(
            fetched.releaseInfo.takeIf { it.isNotBlank() },
            fetched.imdbRating.takeIf { it.isNotBlank() }?.let { "★ $it" },
            fetched.runtime.takeIf { it.isNotBlank() },
            fetched.genres.take(3).joinToString(", ").takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
    }

    private fun loadMetadataAndStreams(fallbackTitle: String, fallbackPoster: String) {
        if (isJellyfin) {
            loadJellyfinMetadataAndStreams()
            return
        }

        streamSpinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val addons = AppPrefs.getInstalledAddons(this@DetailActivity)
            val fetched = StremioApiClient.fetchMetaDetail(addons, contentType, itemId)
                ?: MetaItem(id = itemId, type = contentType, name = fallbackTitle, poster = fallbackPoster)
            meta = fetched
            bindMetaHeaderUi(fetched)

            val showSeriesPicker = fetched.videos.size > 1 ||
                (fetched.videos.size == 1 && fetched.videos[0].season > 0)
            if (showSeriesPicker) {
                setupSeriesPicker(fetched.videos)
            } else {
                selectedEpisode = fetched.videos.firstOrNull()
                val singleVideoId = fetched.defaultVideoId
                    .ifBlank { fetched.videos.firstOrNull()?.id.orEmpty() }
                    .ifBlank { fetched.id }
                loadStreamsForVideoId(singleVideoId, subtitleLabel = "")
            }
        }
    }

    private fun loadJellyfinMetadataAndStreams() {
        val profile = jellyfinProfile ?: run { finish(); return }
        streamSpinner.visibility = View.VISIBLE
        streamHeader.text = "Connecting to ${profile.name}..."
        lifecycleScope.launch {
            val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(profile)
            jellyfinBaseUrl = baseUrl
            val jfDetail = JellyfinApiClient.getItemDetail(this@DetailActivity, profile, itemId)
            if (jfDetail == null) {
                streamSpinner.visibility = View.GONE
                streamHeader.text = "Unable to load from ${profile.name}"
                plotView.text = "Could not reach Jellyfin server (${profile.name}). Check LAN/WAN connection or server settings."
                Toast.makeText(this@DetailActivity, "Failed to load Jellyfin item from ${profile.name}", Toast.LENGTH_LONG).show()
                return@launch
            }
            activeJellyfinItem = jfDetail

            if (jfDetail.Type.equals("Series", ignoreCase = true)) {
                val jfEpisodes = JellyfinApiClient.getEpisodes(this@DetailActivity, profile, jfDetail.Id)
                jellyfinEpisodeResumeMs.clear()
                for (ep in jfEpisodes) {
                    val compId = JellyfinIds.buildId(profile.serverId, ep.Id)
                    if (ep.resumePositionMs > 0L) {
                        jellyfinEpisodeResumeMs[compId] = ep.resumePositionMs
                    }
                }

                if (resumeVideoId.isBlank() && autoPlayEpisodeId.isBlank()) {
                    val resumableEp = jfEpisodes
                        .filter { it.resumePositionMs > 0L }
                        .maxByOrNull { it.UserData?.LastPlayedDate.orEmpty() }
                        ?: jfEpisodes.firstOrNull { it.resumePositionMs > 0L }
                    if (resumableEp != null) {
                        resumeVideoId = JellyfinIds.buildId(profile.serverId, resumableEp.Id)
                        resumeOffsetMs = resumableEp.resumePositionMs
                    } else {
                        val nextUnplayed = jfEpisodes.firstOrNull {
                            it.UserData?.Played != true && (it.ParentIndexNumber ?: 1) > 0
                        }
                        if (nextUnplayed != null) {
                            resumeVideoId = JellyfinIds.buildId(profile.serverId, nextUnplayed.Id)
                        }
                    }
                }

                val videoEpisodes = jfEpisodes.map { it.toVideoEpisode(profile.serverId, baseUrl) }
                val fetched = jfDetail.toMetaItem(profile.serverId, baseUrl, videoEpisodes)
                meta = fetched
                bindMetaHeaderUi(fetched)

                if (videoEpisodes.isNotEmpty()) {
                    setupSeriesPicker(videoEpisodes)
                } else {
                    streamSpinner.visibility = View.GONE
                    streamHeader.text = "No episodes found"
                }
            } else if (jfDetail.Type.equals("Episode", ignoreCase = true) && !jfDetail.SeriesId.isNullOrBlank()) {
                val epCompId = JellyfinIds.buildId(profile.serverId, jfDetail.Id)
                if (jfDetail.resumePositionMs > 0L) {
                    jellyfinEpisodeResumeMs[epCompId] = jfDetail.resumePositionMs
                    if (resumeOffsetMs <= 0L) {
                        resumeOffsetMs = jfDetail.resumePositionMs
                    }
                }
                if (resumeVideoId.isBlank()) {
                    resumeVideoId = epCompId
                }

                val siblingEps = JellyfinApiClient.getEpisodes(this@DetailActivity, profile, jfDetail.SeriesId)
                for (ep in siblingEps) {
                    val compId = JellyfinIds.buildId(profile.serverId, ep.Id)
                    if (ep.resumePositionMs > 0L) {
                        jellyfinEpisodeResumeMs[compId] = ep.resumePositionMs
                    }
                }
                val videoEpisodes = siblingEps.map { it.toVideoEpisode(profile.serverId, baseUrl) }
                val fetched = jfDetail.toMetaItem(profile.serverId, baseUrl, videoEpisodes)
                meta = fetched
                bindMetaHeaderUi(fetched)

                if (videoEpisodes.size > 1) {
                    setupSeriesPicker(videoEpisodes)
                } else {
                    selectedEpisode = videoEpisodes.firstOrNull()
                    val subLbl = jfDetail.episodeCodeLabel().let { code ->
                        if (code.isNotBlank()) "$code · ${jfDetail.Name}" else jfDetail.Name
                    }
                    loadStreamsForVideoId(epCompId, subtitleLabel = subLbl)
                }
            } else {
                val compId = JellyfinIds.buildId(profile.serverId, jfDetail.Id)
                if (jfDetail.resumePositionMs > 0L) {
                    jellyfinEpisodeResumeMs[compId] = jfDetail.resumePositionMs
                }
                val fetched = jfDetail.toMetaItem(profile.serverId, baseUrl)
                meta = fetched
                bindMetaHeaderUi(fetched)
                selectedEpisode = null
                loadStreamsForVideoId(compId, subtitleLabel = "")
            }
        }
    }

    private fun setupSeriesPicker(episodes: List<VideoEpisode>) {
        seriesSection.visibility = View.VISIBLE
        val seasons = episodes.map { it.season }.distinct().sortedWith(compareBy { if (it == 0) 999 else it })

        val targetEp = episodes.firstOrNull { it.id == autoPlayEpisodeId }
            ?: episodes.firstOrNull { it.id == resumeVideoId }
            ?: episodes.firstOrNull { it.season > 0 }
            ?: episodes.first()

        var activeSeason = targetEp.season
        val seasonButtons = mutableListOf<Pair<Int, TextView>>()

        fun renderEpisodesForSeason(seasonNum: Int) {
            episodeContainer.removeAllViews()
            val seasonEps = episodes.filter { it.season == seasonNum }
            val epPills = mutableListOf<Pair<VideoEpisode, TextView>>()
            for (ep in seasonEps) {
                val isSelected = ep.id == selectedEpisode?.id
                val epLabel = if (ep.episode > 0) "E${ep.episode} · ${ep.title.take(24)}" else ep.title.take(28)
                val pill = TvUiKit.createPillButton(this, epLabel, selected = isSelected, compact = true) {
                    selectedEpisode = ep
                    epPills.forEach { (e, btn) -> TvUiKit.setPillSelected(btn, e.id == ep.id) }
                    val subLbl = if (ep.season > 0) "S${ep.season}:E${ep.episode} · ${ep.title}" else ep.title
                    loadStreamsForVideoId(ep.id, subLbl)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = TvUiKit.dp(this@DetailActivity, 6) }
                }
                epPills.add(ep to pill)
                episodeContainer.addView(pill)
            }
        }

        seasonContainer.removeAllViews()
        for (s in seasons) {
            val label = if (s == 0) "Episodes / Specials" else "Season $s"
            val btn = TvUiKit.createPillButton(this, label, selected = (s == activeSeason), compact = true) {
                activeSeason = s
                seasonButtons.forEach { (sn, b) -> TvUiKit.setPillSelected(b, sn == s) }
                renderEpisodesForSeason(s)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@DetailActivity, 6) }
            }
            seasonButtons.add(s to btn)
            seasonContainer.addView(btn)
        }

        selectedEpisode = targetEp
        renderEpisodesForSeason(activeSeason)
        val initSub = if (targetEp.season > 0) "S${targetEp.season}:E${targetEp.episode} · ${targetEp.title}" else targetEp.title
        loadStreamsForVideoId(targetEp.id, initSub)
    }

    private fun loadStreamsForVideoId(videoId: String, subtitleLabel: String) {
        streamLoadJob?.cancel()

        if (isJellyfin) {
            val profile = jellyfinProfile ?: return
            streamSpinner.visibility = View.VISIBLE
            streamHeader.text = if (subtitleLabel.isNotBlank()) {
                "Inspecting · $subtitleLabel"
            } else {
                "Inspecting Jellyfin Media Sources..."
            }
            streamLoadJob = lifecycleScope.launch {
                val epOrMovie = JellyfinApiClient.getItemDetail(this@DetailActivity, profile, videoId)
                    ?: activeJellyfinItem
                if (epOrMovie != null) {
                    activeJellyfinItem = epOrMovie
                    val compVid = JellyfinIds.buildId(profile.serverId, epOrMovie.Id)
                    if (epOrMovie.resumePositionMs > 0L) {
                        jellyfinEpisodeResumeMs[compVid] = epOrMovie.resumePositionMs
                    }
                    if (selectedEpisode != null && !epOrMovie.Overview.isNullOrBlank()) {
                        plotView.text = epOrMovie.Overview
                    }
                }
                val jfCandidates = buildJellyfinStreamCandidates(profile, epOrMovie, videoId)
                streamSpinner.visibility = View.GONE
                currentStreams = jfCandidates
                streamHeader.text = if (subtitleLabel.isNotBlank()) {
                    "Playback Modes (${jfCandidates.size}) · $subtitleLabel"
                } else {
                    "Playback Modes (${jfCandidates.size})"
                }
                applySourceFilterToAdapter()

                if (autoPlayEpisodeId.isNotBlank() && videoId == autoPlayEpisodeId && jfCandidates.isNotEmpty()) {
                    autoPlayEpisodeId = ""
                    val matched = StreamSimilarityMatcher.pickBestMatchingStream(
                        candidates = jfCandidates,
                        preferredBingeGroup = preferredBingeGroup,
                        previousStreamHint = preferredStreamHint,
                        previousAddonName = preferredAddonName,
                        previousWasMirror = preferredWasMirror
                    ) ?: jfCandidates.first()
                    launchStream(matched)
                }
            }
            return
        }

        streamSpinner.visibility = View.VISIBLE
        streamHeader.text = if (subtitleLabel.isNotBlank()) "Resolving · $subtitleLabel" else "Resolving Addons & Mirrors..."
        val embedded = buildList {
            selectedEpisode?.streams?.let { addAll(it) }
            meta?.embeddedStreams?.let { addAll(it) }
        }
        currentStreams = StremioApiClient.dedupeAndSortStreams(embedded)
        applySourceFilterToAdapter()

        streamLoadJob = lifecycleScope.launch {
            var addons = AppPrefs.getInstalledAddons(this@DetailActivity)
            val authKey = AppPrefs.getStremioAuthKey(this@DetailActivity)
            if (!authKey.isNullOrBlank() && addons.none { a -> a.resources.any { it.name == "stream" } }) {
                StremioApiClient.syncAddonsFromAccount(this@DetailActivity, authKey)
                addons = AppPrefs.getInstalledAddons(this@DetailActivity)
            }

            val ep = selectedEpisode
            val parsedVidParts = videoId.split(':')
            val epSeason = ep?.season?.takeIf { it > 0 }
                ?: parsedVidParts.getOrNull(1)?.toIntOrNull()
            val epNumber = ep?.episode?.takeIf { it > 0 }
                ?: parsedVidParts.getOrNull(2)?.toIntOrNull()
            val imdbRootId = when {
                videoId.startsWith("tt", ignoreCase = true) -> videoId.substringBefore(':')
                itemId.startsWith("tt", ignoreCase = true) -> itemId.substringBefore(':')
                else -> ""
            }

            var readyImdbSwarm: List<StreamCandidate> = emptyList()
            val imdbSwarmDeferred = async {
                if (imdbRootId.isNotBlank()) {
                    NativeTorrentSearch.searchImdbSwarm(imdbRootId, epSeason, epNumber).also {
                        readyImdbSwarm = it
                    }
                } else {
                    emptyList()
                }
            }

            val effectiveType = meta?.type?.ifBlank { contentType } ?: contentType
            val fetchedStreams = StremioApiClient.fetchStreamsProgressive(
                addons = addons,
                type = effectiveType,
                videoId = videoId
            ) { partialList, doneCount, totalCount ->
                val mergedPartial = StremioApiClient.dedupeAndSortStreams(embedded + partialList + readyImdbSwarm)
                currentStreams = mergedPartial
                streamHeader.text = if (doneCount < totalCount) {
                    "Streams (${mergedPartial.size}) · Addons $doneCount/$totalCount..."
                } else if (subtitleLabel.isNotBlank()) {
                    "Streams (${mergedPartial.size}) · $subtitleLabel"
                } else {
                    "Available Streams (${mergedPartial.size})"
                }
                applySourceFilterToAdapter()
            }
            val imdbSwarmStreams = imdbSwarmDeferred.await()
            var finalStreams = StremioApiClient.dedupeAndSortStreams(embedded + fetchedStreams + imdbSwarmStreams)

            // Automatic P2P Swarm fallback if addons + IMDb swarm returned 0 playable streams
            val hasPlayable = finalStreams.any { it.isMirrorOrDirect || it.seeders > 0 }
            if (!hasPlayable) {
                val q = buildBurstSearchQuery()
                if (q.isNotBlank()) {
                    streamHeader.text = "Searching Public Domain Swarm for \"$q\"..."
                    val swarm = NativeTorrentSearch.searchAll(this@DetailActivity, q, "public_domain")
                    if (swarm.isNotEmpty()) {
                        finalStreams = StremioApiClient.dedupeAndSortStreams(finalStreams + swarm)
                    }
                }
            }

            streamSpinner.visibility = View.GONE
            currentStreams = finalStreams
            streamHeader.text = if (subtitleLabel.isNotBlank()) {
                "Streams (${finalStreams.size}) · $subtitleLabel"
            } else {
                "Available Streams (${finalStreams.size})"
            }
            applySourceFilterToAdapter()

            if (autoPlayEpisodeId.isNotBlank() && videoId == autoPlayEpisodeId && finalStreams.isNotEmpty()) {
                autoPlayEpisodeId = ""
                val matched = StreamSimilarityMatcher.pickBestMatchingStream(
                    candidates = finalStreams,
                    preferredBingeGroup = preferredBingeGroup,
                    previousStreamHint = preferredStreamHint,
                    previousAddonName = preferredAddonName,
                    previousWasMirror = preferredWasMirror
                ) ?: finalStreams.first()
                launchStream(matched)
            }
        }
    }

    private fun buildJellyfinStreamCandidates(
        profile: JellyfinServerProfile,
        item: JfItem?,
        fallbackVideoId: String
    ): List<StreamCandidate> {
        val sources = item?.MediaSources.orEmpty()
        val effectiveSources = if (sources.isNotEmpty()) {
            sources
        } else {
            listOf(
                JfMediaSource(
                    Id = JellyfinIds.rawItemId(item?.Id ?: fallbackVideoId),
                    Name = item?.Name.orEmpty()
                )
            )
        }

        val candidates = ArrayList<StreamCandidate>(effectiveSources.size * 2)
        for (source in effectiveSources) {
            val videoStream = source.MediaStreams.firstOrNull { it.Type.equals("Video", ignoreCase = true) }
            val audioStream = source.MediaStreams.firstOrNull {
                it.Type.equals("Audio", ignoreCase = true) && (it.IsDefault || it.Index == source.DefaultAudioStreamIndex)
            } ?: source.MediaStreams.firstOrNull { it.Type.equals("Audio", ignoreCase = true) }

            val resLabel = when {
                (videoStream?.Width ?: 0) >= 3800 || (videoStream?.Height ?: 0) >= 2100 -> "4K"
                (videoStream?.Width ?: 0) >= 1900 || (videoStream?.Height ?: 0) >= 1060 -> "1080p"
                (videoStream?.Width ?: 0) >= 1260 || (videoStream?.Height ?: 0) >= 700 -> "720p"
                (videoStream?.Height ?: 0) > 0 -> "${videoStream?.Height}p"
                else -> "DIRECT"
            }
            val vCodec = videoStream?.Codec?.uppercase(Locale.US).orEmpty()
            val hdrTag = (videoStream?.VideoRangeType ?: videoStream?.VideoRange)
                ?.takeIf { !it.equals("SDR", ignoreCase = true) }
                .orEmpty()
            val aCodec = audioStream?.Codec?.uppercase(Locale.US).orEmpty()
            val aChannels = when (audioStream?.Channels) {
                8 -> "7.1"
                6 -> "5.1"
                2 -> "2.0"
                1 -> "1.0"
                null -> ""
                else -> "${audioStream.Channels}ch"
            }
            val audioLabel = listOf(aCodec, aChannels).filter { it.isNotBlank() }.joinToString(" ")
            val containerLabel = source.Container?.uppercase(Locale.US).orEmpty()
            val bitrateLabel = source.Bitrate
                ?.takeIf { it > 0L }
                ?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) }
                .orEmpty()
            val sizeStr = formatFileSize(source.Size)

            val sourceTitle = source.Name.ifBlank { item?.Name?.ifBlank { titleView.text.toString() } ?: titleView.text.toString() }
            val directSpecs = listOf(
                "${profile.name} · Direct",
                resLabel.takeIf { it != "DIRECT" }.orEmpty(),
                vCodec,
                hdrTag,
                audioLabel,
                containerLabel,
                bitrateLabel,
                sizeStr
            ).filter { it.isNotBlank() }.joinToString(" · ")

            val badgeText = listOf(resLabel, vCodec.ifBlank { "DIRECT" })
                .distinct()
                .joinToString(" · ")

            val sourceIdPath = source.Id.ifBlank { JellyfinIds.rawItemId(item?.Id ?: fallbackVideoId) }

            candidates.add(
                StreamCandidate(
                    addonName = "${profile.name} · Direct",
                    name = badgeText,
                    title = sourceTitle,
                    description = directSpecs,
                    url = "jf://play/$sourceIdPath?transcode=0",
                    bingeGroup = "jf:${profile.serverId}",
                    sizeLabel = sizeStr
                )
            )
            candidates.add(
                StreamCandidate(
                    addonName = "${profile.name} · HLS Transcode",
                    name = "HLS · TRANSCODE",
                    title = "$sourceTitle (Server Transcode)",
                    description = listOf(
                        "${profile.name} · HLS Transcode",
                        "Max ${profile.maxBitrateLabel}",
                        sizeStr
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    url = "jf://play/$sourceIdPath?transcode=1",
                    bingeGroup = "jf:${profile.serverId}:transcode",
                    sizeLabel = sizeStr
                )
            )
        }
        return candidates
    }

    private fun formatFileSize(bytes: Long?): String {
        val b = bytes ?: return ""
        if (b <= 0L) return ""
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return if (b >= gb) {
            String.format(Locale.US, "%.2f GB", b / gb)
        } else {
            String.format(Locale.US, "%.0f MB", b / mb)
        }
    }

    private fun buildBurstSearchQuery(): String {
        val m = meta ?: return ""
        val ep = selectedEpisode
        return if (ep != null && ep.season > 0) {
            String.format("%s S%02dE%02d", m.name, ep.season, ep.episode)
        } else {
            val year = m.releaseInfo.take(4).takeIf { it.all(Char::isDigit) } ?: ""
            "${m.name} $year".trim()
        }
    }

    private fun runDirectSwarmFallbackSearch() {
        val query = buildBurstSearchQuery()
        val imdbRootId = itemId.substringBefore(':').takeIf { it.startsWith("tt", ignoreCase = true) }.orEmpty()
        val effectiveQuery = query.ifBlank { imdbRootId }
        if (effectiveQuery.isBlank()) return
        streamSpinner.visibility = View.VISIBLE
        streamHeader.text = "Searching Public Domain & Torznab Indexers..."
        lifecycleScope.launch {
            val swarmResults = NativeTorrentSearch.searchAll(this@DetailActivity, effectiveQuery, "all")
            streamSpinner.visibility = View.GONE
            val merged = StremioApiClient.dedupeAndSortStreams(currentStreams + swarmResults)
            currentStreams = merged
            streamHeader.text = "Available Streams (${merged.size})"
            applySourceFilterToAdapter()
        }
    }

    @OptIn(UnstableApi::class)
    private fun launchStream(stream: StreamCandidate) {
        val m = meta
        val ep = selectedEpisode
        val activeVideoId = ep?.id ?: m?.id ?: itemId
        val computedStreamHint = "${stream.name} ${stream.filename} ${stream.description}".trim()
        val savedHistoryOffset = AppPrefs.getWatchHistory(this)
            .firstOrNull { it.videoId == activeVideoId || (it.itemId == itemId && it.videoId == itemId) }
            ?.timeOffsetMs ?: 0L
        val intentResume = if (!hasConsumedInitialResume && activeVideoId == resumeVideoId && resumeOffsetMs > 0L) {
            hasConsumedInitialResume = true
            resumeOffsetMs
        } else {
            0L
        }
        val subLabel = if (ep != null) {
            if (ep.season > 0) "S${ep.season}:E${ep.episode} ${ep.title}" else ep.title
        } else {
            m?.releaseInfo ?: ""
        }

        // Native Multi-Server Jellyfin playback session resolution
        if (isJellyfin || stream.url?.startsWith("jf://play/") == true) {
            val profile = jellyfinProfile
                ?: JellyfinIds.parseId(itemId)?.first?.let { AppPrefs.getJellyfinServer(this, it) }
            if (profile == null) {
                Toast.makeText(this, "Jellyfin server unavailable", Toast.LENGTH_SHORT).show()
                return
            }

            val parsedUri = Uri.parse(stream.url.orEmpty())
            val mediaSourceId = parsedUri.path?.trim('/')?.takeIf { it.isNotBlank() }
            val forceTranscode = parsedUri.getQueryParameter("transcode") == "1"

            val serverResumeMs = jellyfinEpisodeResumeMs.remove(activeVideoId)
                ?: activeJellyfinItem
                    ?.takeIf { JellyfinIds.buildId(profile.serverId, it.Id) == activeVideoId }
                    ?.resumePositionMs
                ?: 0L
            val resumeMs = when {
                intentResume > 0L -> intentResume
                savedHistoryOffset > 0L -> savedHistoryOffset
                else -> serverResumeMs
            }

            streamSpinner.visibility = View.VISIBLE
            lifecycleScope.launch {
                val session = JellyfinApiClient.resolvePlaybackSession(
                    context = this@DetailActivity,
                    profile = profile,
                    itemId = activeVideoId,
                    mediaSourceId = mediaSourceId,
                    startPositionMs = resumeMs.takeIf { it > 0L },
                    forceTranscode = forceTranscode
                )
                streamSpinner.visibility = View.GONE
                if (session == null) {
                    Toast.makeText(
                        this@DetailActivity,
                        "Unable to start Jellyfin playback on ${profile.name}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                PlayerActivity.start(
                    this@DetailActivity,
                    PlayerActivity.LaunchPayload(
                        title = m?.name ?: titleView.text.toString(),
                        subtitle = subLabel,
                        itemId = itemId,
                        videoId = activeVideoId,
                        contentType = m?.type ?: contentType,
                        poster = m?.poster?.ifBlank { initialPosterUrl } ?: initialPosterUrl,
                        background = m?.background ?: "",
                        directUrl = session.streamUrl,
                        headers = session.headers,
                        ignoreSsl = session.ignoreSsl,
                        labeledSubtitles = session.subtitleTracks.mapNotNull { it.toExternalSubtitleOrNull() },
                        resumePositionMs = if (resumeMs > 0L) resumeMs else session.resumePositionMs,
                        bingeGroup = stream.bingeGroup.ifBlank { "jf:${profile.serverId}" },
                        streamHint = computedStreamHint,
                        addonName = stream.addonName,
                        isMirror = stream.isMirrorOrDirect,
                        seriesEpisodes = m?.videos ?: emptyList(),
                        jellyfinServerId = profile.serverId,
                        jellyfinItemId = activeVideoId,
                        jellyfinSession = session
                    )
                )
            }
            return
        }

        val resumeMs = if (intentResume > 0L) intentResume else savedHistoryOffset

        if (stream.isTorrent) {
            val magnet = stream.toMagnetUri()
            PlayerActivity.start(
                this,
                PlayerActivity.LaunchPayload(
                    title = m?.name ?: titleView.text.toString(),
                    subtitle = subLabel,
                    itemId = itemId,
                    videoId = activeVideoId,
                    contentType = m?.type ?: contentType,
                    poster = m?.poster ?: "",
                    background = m?.background ?: "",
                    magnetUri = magnet,
                    infoHash = stream.infoHash,
                    fileIdx = stream.fileIdx,
                    trackers = stream.sources + StreamCandidate.DEFAULT_PUBLIC_TRACKERS,
                    resumePositionMs = resumeMs,
                    bingeGroup = stream.bingeGroup,
                    streamHint = computedStreamHint,
                    addonName = stream.addonName,
                    isMirror = stream.isMirrorOrDirect,
                    seriesEpisodes = m?.videos ?: emptyList()
                )
            )
        } else if (!stream.url.isNullOrBlank()) {
            PlayerActivity.start(
                this,
                PlayerActivity.LaunchPayload(
                    title = m?.name ?: titleView.text.toString(),
                    subtitle = subLabel,
                    itemId = itemId,
                    videoId = activeVideoId,
                    contentType = m?.type ?: contentType,
                    poster = m?.poster ?: "",
                    background = m?.background ?: "",
                    directUrl = stream.url,
                    headers = stream.headers,
                    ignoreSsl = stream.ignoreSsl,
                    resumePositionMs = resumeMs,
                    bingeGroup = stream.bingeGroup,
                    streamHint = computedStreamHint,
                    addonName = stream.addonName,
                    isMirror = stream.isMirrorOrDirect,
                    seriesEpisodes = m?.videos ?: emptyList()
                )
            )
        } else if (!stream.externalUrl.isNullOrBlank()) {
            val extUrl = stream.externalUrl
            // Try native ResolveURL extraction in isolated :pyaddon first, then kill :pyaddon immediately (0 MB)!
            streamSpinner.visibility = View.VISIBLE
            Toast.makeText(this, "Resolving Mirror Stream...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val enc = URLEncoder.encode(extUrl, "UTF-8")
                val bridgeRes = PyAddonClient.executeUrl(this@DetailActivity, "plugin://__resolveurl__/?url=$enc")
                PyAddonClient.killAddonProcessNow(this@DetailActivity)
                streamSpinner.visibility = View.GONE

                val resolvedRaw = bridgeRes.resolved?.url?.takeIf { it.isNotBlank() }
                if (resolvedRaw != null) {
                    val parsed = PyAddonClient.parseKodiStreamUrl(resolvedRaw)
                    PlayerActivity.start(
                        this@DetailActivity,
                        PlayerActivity.LaunchPayload(
                            title = m?.name ?: titleView.text.toString(),
                            subtitle = "$subLabel · Mirror".trim(' ', '·'),
                            itemId = itemId,
                            videoId = activeVideoId,
                            contentType = m?.type ?: contentType,
                            poster = m?.poster ?: "",
                            background = m?.background ?: "",
                            directUrl = parsed.cleanUrl,
                            headers = stream.headers + parsed.headers,
                            ignoreSsl = stream.ignoreSsl || parsed.ignoreSsl,
                            resumePositionMs = resumeMs,
                            bingeGroup = stream.bingeGroup,
                            streamHint = computedStreamHint,
                            addonName = stream.addonName,
                            isMirror = stream.isMirrorOrDirect,
                            seriesEpisodes = m?.videos ?: emptyList()
                        )
                    )
                } else {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(extUrl)))
                    }.onFailure {
                        Toast.makeText(this@DetailActivity, "External Mirror URL: $extUrl", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Unsupported stream format", Toast.LENGTH_SHORT).show()
        }
    }
}
