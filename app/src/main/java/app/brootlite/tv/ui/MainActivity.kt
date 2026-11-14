package app.brootlite.tv.ui

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.speech.RecognizerIntent
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.brootlite.tv.creators.CreatorCategory
import app.brootlite.tv.creators.CreatorChannel
import app.brootlite.tv.creators.CreatorFeedPage
import app.brootlite.tv.creators.CreatorPlatform
import app.brootlite.tv.creators.CreatorVideoCard
import app.brootlite.tv.creators.OdyseeClient
import app.brootlite.tv.creators.RumbleClient
import app.brootlite.tv.creators.TwitchAuthContext
import app.brootlite.tv.creators.TwitchClient
import app.brootlite.tv.creators.TwitchDevicePollResult
import app.brootlite.tv.data.AddonCatalogDef
import app.brootlite.tv.data.AppPrefs
import app.brootlite.tv.data.CreatorFollowEntry
import app.brootlite.tv.data.DiscoverSortMode
import app.brootlite.tv.data.ExternalSubtitle
import app.brootlite.tv.data.FavoriteEntry
import app.brootlite.tv.data.InstalledAddon
import app.brootlite.tv.data.MetaItem
import app.brootlite.tv.data.MultipassSyncClient
import app.brootlite.tv.data.NativeTorrentSearch
import app.brootlite.tv.data.StreamCandidate
import app.brootlite.tv.data.StremioApiClient
import app.brootlite.tv.data.SwarmFilter
import app.brootlite.tv.data.SyncedTwitchDeviceOAuth
import app.brootlite.tv.data.SyncedYouTubeOAuth
import app.brootlite.tv.data.WatchHistoryEntry
import app.brootlite.tv.jellyfin.JellyfinApiClient
import app.brootlite.tv.jellyfin.JellyfinEndpointRouter
import app.brootlite.tv.jellyfin.JellyfinIds
import app.brootlite.tv.jellyfin.JellyfinServerProfile
import app.brootlite.tv.jellyfin.JfItem
import app.brootlite.tv.jellyfin.JfView
import app.brootlite.tv.kodi.PyAddonClient
import app.brootlite.tv.youtube.YouTubeApiClient
import app.brootlite.tv.youtube.YouTubeAuthClient
import app.brootlite.tv.youtube.YouTubeAuthContext
import app.brootlite.tv.youtube.YouTubeChannelCard
import app.brootlite.tv.youtube.YouTubeDevicePollResult
import app.brootlite.tv.youtube.YouTubePlaylistCard
import app.brootlite.tv.youtube.YouTubeQualityVariant
import app.brootlite.tv.youtube.YouTubeSearchFilter
import app.brootlite.tv.youtube.YouTubeVideoCard
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class NavTab(val label: String, val headerTitle: String) {
        BOARD("Board", "Your Board"),
        DISCOVER("Discover", "Discover Gallery"),
        JELLYFIN("Jellyfin", "Jellyfin Servers"),
        STREAMING("Streaming", "Creator Streaming"),
        SEARCH("Search", "Search & Swarm"),
        LIBRARY("Library", "Your Library"),
        VAULT("Kodi", "Kodi Addons"),
        PASSPORT("Passport ↗", "Your Passport")
    }

    private enum class JfSortOption(
        val label: String,
        val shortLabel: String,
        val sortBy: String,
        val sortOrder: String
    ) {
        NAME_ASC("Name (A–Z)", "Name", "SortName", "Ascending"),
        DATE_ADDED("Recently Added", "Recent", "DateCreated", "Descending"),
        RELEASE_DATE("Release Date (Newest)", "Release", "PremiereDate", "Descending"),
        RATING_DESC("Community Rating", "Top Rated", "CommunityRating", "Descending")
    }

    private enum class StreamingPlatform(val key: String, val label: String, val defaultSection: String) {
        YOUTUBE("youtube", "YouTube", "home"),
        TWITCH("twitch", "Twitch", "top_live"),
        ODYSEE("odysee", "Odysee", "trending"),
        RUMBLE("rumble", "Rumble", "featured")
    }

    private data class StreamingDrillState(
        val kind: String, // "channel" | "playlist" | "category"
        val id: String,
        val title: String,
        val subscribeParams: String? = null,
        val isSubscribedRemote: Boolean? = null,
        val avatarUrl: String = ""
    )

    private data class DiscoverCatalogEntry(
        val addon: InstalledAddon,
        val catalog: AddonCatalogDef,
        val displayLabel: String,
        val shortChipKey: String = ""
    )

    private data class QuickNetworkChip(
        val key: String,
        val label: String,
        val catalogId: String,
        val preferredType: String? = null,
        val addonId: String? = null
    )

    companion object {
        private const val JF_VIEW_OVERVIEW = "__overview__"
        private const val JF_PAGE_SIZE = 48
    }

    private var activeTab: NavTab = NavTab.BOARD
    private val navButtons = LinkedHashMap<NavTab, TextView>()
    private lateinit var passportBtn: TextView
    private lateinit var brandHeaderTitle: TextView
    private lateinit var topStatusDot: TextView
    private lateinit var contentFrame: FrameLayout

    // Tab views
    private lateinit var boardView: View
    private lateinit var discoverView: View
    private lateinit var jellyfinView: View
    private lateinit var streamingView: View
    private lateinit var searchView: View
    private lateinit var libraryView: View
    private lateinit var vaultView: View
    private lateinit var passportView: View

    // Shared RecycledViewPools (0 extra RAM!)
    private val sharedPosterPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 28) }
    private val sharedDiscoverPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 36) }
    private val sharedVideoPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 24) }

    // Board state
    private lateinit var boardAuthBanner: LinearLayout
    private lateinit var continueSection: LinearLayout
    private lateinit var continueAdapter: ContinueWatchingAdapter
    private lateinit var lazyBoardAdapter: LazyBoardAdapter
    private var continueLoadJob: Job? = null

    // Discover state
    private var discoverType: String = "movie"
    private var discoverCatalogsForType: List<DiscoverCatalogEntry> = emptyList()
    private var selectedDiscoverEntry: DiscoverCatalogEntry? = null
    private var selectedDiscoverGenre: String? = null
    private var selectedDiscoverSort: DiscoverSortMode = DiscoverSortMode.DEFAULT
    private val discoverRawItems = ArrayList<MetaItem>(150)
    private var discoverIsLoading = false
    private var discoverReachedEnd = false
    private var discoverLoadJob: Job? = null

    private lateinit var discoverTypePill: TextView
    private lateinit var discoverCatalogPill: TextView
    private lateinit var discoverGenrePill: TextView
    private lateinit var discoverSortPill: TextView
    private lateinit var discoverResetPill: TextView
    private lateinit var discoverStatusMono: TextView
    private lateinit var discoverSpinner: ProgressBar
    private lateinit var discoverEmptyText: TextView
    private lateinit var discoverRibbonRow: LinearLayout
    private lateinit var discoverGridAdapter: DiscoverGridAdapter
    private val quickNetworkButtons = LinkedHashMap<String, TextView>()
    private val discoverExtendedYears: List<String> = (2026 downTo 1920).map { it.toString() }

    // Jellyfin state (strictly isolated per single selected server!)
    private var activeJfServerId: String? = null
    private var jfViewsForActiveServer: List<JfView> = emptyList()
    private var selectedJfViewId: String = JF_VIEW_OVERVIEW
    private var selectedJfGenre: String? = null
    private var selectedJfYear: String? = null
    private var selectedJfSort: JfSortOption = JfSortOption.NAME_ASC
    private var jfSearchQuery: String = ""
    private var jfNextStartIndex: Int = 0
    private var jfTotalRecords: Int = 0
    private var jfIsLoading: Boolean = false
    private var jfLoadJob: Job? = null
    private var jfAuthPollJob: Job? = null

    private lateinit var jfServerRow: LinearLayout
    private lateinit var jfViewsFilterScroll: HorizontalScrollView
    private lateinit var jfViewsFilterRow: LinearLayout
    private lateinit var jfSpinner: ProgressBar
    private lateinit var jfStatusMono: TextView
    private lateinit var jfEmptyCard: LinearLayout
    private lateinit var jfEmptyTitle: TextView
    private lateinit var jfEmptyBody: TextView
    private lateinit var jfEmptyPrimaryBtn: TextView
    private lateinit var jfGridRv: RecyclerView
    private lateinit var jfGridAdapter: DiscoverGridAdapter

    // Creator Streaming state (YouTube · Twitch · Odysee · Rumble)
    private var activeStreamingPlatform: StreamingPlatform = StreamingPlatform.YOUTUBE
    private var activeStreamingSection: String = "home"
    private var streamingSearchQuery: String = ""
    private var selectedYouTubeSearchFilter: YouTubeSearchFilter = YouTubeSearchFilter.ALL
    private val streamingDrillStack = ArrayDeque<StreamingDrillState>()
    private var streamingNextToken: String? = null
    private var streamingIsLoading: Boolean = false
    private var streamingReachedEnd: Boolean = false
    private var streamingLoadedAny: Boolean = false
    private var streamingLoadJob: Job? = null
    private var streamingResolveJob: Job? = null
    private var streamingAuthPollJob: Job? = null

    private val streamingPlatformButtons = LinkedHashMap<StreamingPlatform, TextView>()
    private lateinit var streamingAccountPill: TextView
    private lateinit var streamingSpinner: ProgressBar
    private lateinit var streamingStatusMono: TextView
    private lateinit var streamingSubNavRow: LinearLayout
    private lateinit var streamingEmptyText: TextView
    private lateinit var streamingGridRv: RecyclerView
    private lateinit var streamingGridAdapter: StreamVideoGridAdapter
    private var streamingBackCallback: OnBackPressedCallback? = null

    // Search & Swarm state
    private var currentSearchQuery: String = ""
    private var currentSwarmFilter: SwarmFilter = SwarmFilter.ALL
    private var searchCatalogTypeFilter: String = "all"
    private var lastCatalogSearchHits: List<MetaItem> = emptyList()
    private var forceNextVoiceToStremioSearch: Boolean = false
    private lateinit var boardSearchBarBtn: TextView
    private lateinit var searchQueryBtn: TextView
    private lateinit var searchStremioSection: LinearLayout
    private lateinit var searchStremioHeader: TextView
    private lateinit var searchCatalogTypeRow: LinearLayout
    private lateinit var searchStremioIdleBox: LinearLayout
    private lateinit var searchStremioPromptText: TextView
    private lateinit var searchStremioRv: RecyclerView
    private lateinit var searchStremioAdapter: PosterRowAdapter
    private lateinit var searchSwarmHeader: TextView
    private lateinit var searchSwarmSpinner: ProgressBar
    private lateinit var searchSwarmAdapter: StreamListAdapter
    private val swarmFilterButtons = LinkedHashMap<SwarmFilter, TextView>()
    private var searchJob: Job? = null

    // Library state
    private var libraryFilterType: String = "all"
    private lateinit var libraryAdapter: FavoriteGridAdapter
    private lateinit var libraryEmptyText: TextView

    // Kodi state
    private lateinit var vaultContainer: LinearLayout

    // Passport state
    private lateinit var ramMeterText: TextView
    private lateinit var passportIdentityName: TextView
    private lateinit var passportIdentityHandle: TextView
    private lateinit var passportIdentitySummary: TextView
    private lateinit var passportStremioStatus: TextView
    private lateinit var passportMultipassStatus: TextView
    private lateinit var passportJellyfinStatus: TextView
    private lateinit var passportStreamingStatus: TextView
    private lateinit var passportKodiStatus: TextView
    private lateinit var passportTorrentStatus: TextView
    private lateinit var passportBottomActionBtn: TextView
    private lateinit var preBufferBtn: TextView
    private lateinit var windowMbBtn: TextView
    private lateinit var maxConnBtn: TextView
    private lateinit var torznabBtn: TextView
    private lateinit var ytSponsorBlockBtn: TextView
    private lateinit var ytHistorySyncBtn: TextView
    private lateinit var ytDislikesBtn: TextView
    private lateinit var ytDeArrowBtn: TextView
    private var ramTickerJob: Job? = null
    private var qrPollJob: Job? = null

    private val quickNetworkSpecs = listOf(
        QuickNetworkChip("top", "All Popular", "top"),
        QuickNetworkChip("imdbRating", "Top Rated", "imdbRating"),
        QuickNetworkChip("year", "By Year", "year"),
        QuickNetworkChip("netflix-top10-global", "Netflix Top 10", "netflix-top10-global"),
        QuickNetworkChip("nfx", "Netflix", "nfx"),
        QuickNetworkChip("amp", "Prime Video", "amp"),
        QuickNetworkChip("dnp", "Disney+", "dnp"),
        QuickNetworkChip("hbm", "HBO Max", "hbm"),
        QuickNetworkChip("atp", "Apple TV+", "atp"),
        QuickNetworkChip("hlu", "Hulu", "hlu"),
        QuickNetworkChip("pcp", "Peacock", "pcp"),
        QuickNetworkChip("pmp", "Paramount+", "pmp"),
        QuickNetworkChip("cru", "Crunchyroll", "cru", preferredType = "series"),
        QuickNetworkChip("mbi", "Mubi", "mbi", preferredType = "movie"),
        QuickNetworkChip("crc", "Criterion", "crc", preferredType = "movie")
    )

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val forceStremio = forceNextVoiceToStremioSearch
        forceNextVoiceToStremioSearch = false
        if (res.resultCode == RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
            if (!spoken.isNullOrBlank()) {
                if (activeTab == NavTab.STREAMING && !forceStremio) {
                    streamingSearchQuery = spoken
                    streamingDrillStack.clear()
                    syncStreamingBackHandler()
                    activeStreamingSection = "search"
                    rebuildStreamingSubNavRow()
                    reloadStreamingFeedFromScratch()
                } else {
                    currentSearchQuery = spoken
                    searchCatalogTypeFilter = "all"
                    updateSearchButtonText()
                    executeSearchAndSwarm()
                    if (activeTab != NavTab.SEARCH) {
                        switchTab(NavTab.SEARCH)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildRootShell()
        val backCb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (activeTab == NavTab.STREAMING && streamingDrillStack.isNotEmpty()) {
                    streamingDrillStack.removeLast()
                    syncStreamingBackHandler()
                    rebuildStreamingSubNavRow()
                    reloadStreamingFeedFromScratch()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        streamingBackCallback = backCb
        onBackPressedDispatcher.addCallback(this, backCb)

        handleIncomingMagnetIntent(intent)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppPrefs.tryAutoImportStremioAuth(this@MainActivity)
                MultipassSyncClient.tryAutoImportMultipassFile(this@MainActivity)
                AppPrefs.tryImportKodiFavoritesOnce(this@MainActivity)
            }
            refreshAccountIndicators()
            rebuildBoardRows(clearCache = false)
            rebuildDiscoverCatalogChoices(keepSelection = true, triggerLoad = false)
            reloadJellyfinServersState(triggerNetwork = false)
            refreshStreamingAccountPill()

            // Refresh synced Stremio addon manifests & watch state in background so JAV/Hentai/Mirrors stay 100% live
            val authKey = AppPrefs.getStremioAuthKey(this@MainActivity)
            if (authKey != null) {
                val synced = StremioApiClient.syncAccountEverything(this@MainActivity, authKey)
                if (synced > 0) {
                    refreshAccountIndicators()
                    refreshContinueWatchingRow()
                    rebuildBoardRows(clearCache = false)
                    rebuildDiscoverCatalogChoices(keepSelection = true, triggerLoad = activeTab == NavTab.DISCOVER)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingMagnetIntent(intent)
    }

    @OptIn(UnstableApi::class)
    private fun handleIncomingMagnetIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("magnet", ignoreCase = true)) {
            PlayerActivity.start(
                this,
                PlayerActivity.LaunchPayload(
                    title = data.getQueryParameter("dn") ?: "Magnet Stream",
                    subtitle = "Direct P2P Magnet",
                    magnetUri = data.toString(),
                    trackers = StreamCandidate.DEFAULT_PUBLIC_TRACKERS
                )
            )
        }
    }

    // =========================================================================
    // ROOT SHELL & XIRTUS TOP NAVIGATION CHROME
    // =========================================================================
    private fun buildRootShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = TvUiKit.createRootCanvasDrawable()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Compact Top Navigation Chrome fitting all 8 tabs cleanly on a 960dp TV viewport
        val topNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 20),
                TvUiKit.dp(this@MainActivity, 11),
                TvUiKit.dp(this@MainActivity, 20),
                TvUiKit.dp(this@MainActivity, 9)
            )
            setBackgroundColor(Color.parseColor("#B30F141D"))
        }

        // Left brootlite "bL" mark (branding/mark.svg) + stacked eyebrow & header
        val emblemView = View(this).apply {
            val sz = TvUiKit.dp(this@MainActivity, 34)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 10)
            }
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, app.brootlite.tv.R.drawable.brootlite_mark)
        }
        topNav.addView(emblemView)

        val brandStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 14) }
        }
        brandStack.addView(TvUiKit.eyebrow(this, "XIRTUS / BROOTLITE").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
        })
        brandHeaderTitle = TextView(this).apply {
            text = "Your Board"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = XirtusFonts.bold(this@MainActivity)
            letterSpacing = -0.02f
            maxLines = 1
        }
        brandStack.addView(brandHeaderTitle)
        topNav.addView(brandStack)

        // Primary navigation tabs: Board · Discover · Jellyfin · Streaming · Search · Library · Kodi
        val primaryTabs = listOf(
            NavTab.BOARD,
            NavTab.DISCOVER,
            NavTab.JELLYFIN,
            NavTab.STREAMING,
            NavTab.SEARCH,
            NavTab.LIBRARY,
            NavTab.VAULT
        )
        for (tab in primaryTabs) {
            val btn = TvUiKit.createNavTabPill(this, tab.label, selected = (tab == activeTab)) {
                switchTab(tab)
            }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(
                    TvUiKit.dp(this@MainActivity, 11),
                    TvUiKit.dp(this@MainActivity, 7),
                    TvUiKit.dp(this@MainActivity, 11),
                    TvUiKit.dp(this@MainActivity, 7)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 4) }
            }
            navButtons[tab] = btn
            topNav.addView(btn)
        }

        topNav.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        topStatusDot = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = XirtusFonts.monoRegular(this@MainActivity)
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = TvUiKit.dp(this@MainActivity, 124)
            setPadding(0, 0, TvUiKit.dp(this@MainActivity, 10), 0)
        }
        topNav.addView(topStatusDot)

        // Right-aligned 'Passport ↗' pill
        passportBtn = TvUiKit.createPillButton(
            context = this,
            text = NavTab.PASSPORT.label,
            selected = false,
            compact = true
        ) {
            switchTab(NavTab.PASSPORT)
        }
        topNav.addView(passportBtn)

        root.addView(topNav)
        root.addView(TvUiKit.hairlineDivider(this))

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        boardView = createBoardTabView()
        discoverView = createDiscoverTabView()
        jellyfinView = createJellyfinTabView()
        streamingView = createStreamingTabView()
        searchView = createSearchTabView()
        libraryView = createLibraryTabView()
        vaultView = createVaultTabView()
        passportView = createPassportTabView()

        contentFrame.addView(boardView)
        contentFrame.addView(discoverView)
        contentFrame.addView(jellyfinView)
        contentFrame.addView(streamingView)
        contentFrame.addView(searchView)
        contentFrame.addView(libraryView)
        contentFrame.addView(vaultView)
        contentFrame.addView(passportView)
        root.addView(contentFrame)

        setContentView(root)
        refreshAccountIndicators()
        rebuildBoardRows(clearCache = false)
        rebuildDiscoverCatalogChoices(keepSelection = false, triggerLoad = false)
        reloadJellyfinServersState(triggerNetwork = false)
        refreshStreamingAccountPill()
        switchTab(NavTab.BOARD)
        navButtons[NavTab.BOARD]?.requestFocus()
    }

    private fun syncStreamingBackHandler() {
        streamingBackCallback?.isEnabled =
            (activeTab == NavTab.STREAMING && streamingDrillStack.isNotEmpty())
    }

    private fun switchTab(tab: NavTab) {
        activeTab = tab
        brandHeaderTitle.text = tab.headerTitle
        navButtons.forEach { (t, btn) -> TvUiKit.setNavTabSelected(btn, t == tab) }
        TvUiKit.setPillSelected(passportBtn, tab == NavTab.PASSPORT)
        syncStreamingBackHandler()

        boardView.visibility = if (tab == NavTab.BOARD) View.VISIBLE else View.GONE
        discoverView.visibility = if (tab == NavTab.DISCOVER) View.VISIBLE else View.GONE
        jellyfinView.visibility = if (tab == NavTab.JELLYFIN) View.VISIBLE else View.GONE
        streamingView.visibility = if (tab == NavTab.STREAMING) View.VISIBLE else View.GONE
        searchView.visibility = if (tab == NavTab.SEARCH) View.VISIBLE else View.GONE
        libraryView.visibility = if (tab == NavTab.LIBRARY) View.VISIBLE else View.GONE
        vaultView.visibility = if (tab == NavTab.VAULT) View.VISIBLE else View.GONE
        passportView.visibility = if (tab == NavTab.PASSPORT) View.VISIBLE else View.GONE

        if (tab == NavTab.PASSPORT) {
            refreshPassportTab()
            startRamTicker()
        } else {
            ramTickerJob?.cancel()
        }

        when (tab) {
            NavTab.BOARD -> refreshContinueWatchingRow()
            NavTab.DISCOVER -> {
                if (discoverRawItems.isEmpty() && !discoverIsLoading) {
                    reloadDiscoverCatalogFromScratch()
                }
            }
            NavTab.JELLYFIN -> {
                reloadJellyfinServersState(triggerNetwork = jfGridAdapter.currentCount() == 0 && !jfIsLoading)
            }
            NavTab.STREAMING -> {
                refreshStreamingAccountPill()
                if (!streamingLoadedAny && !streamingIsLoading) {
                    reloadStreamingFeedFromScratch()
                }
            }
            NavTab.SEARCH -> {
                if (searchSwarmAdapter.itemCount == 0 && searchJob?.isActive != true) {
                    executeSearchAndSwarm()
                }
            }
            NavTab.LIBRARY -> refreshLibraryGrid()
            NavTab.VAULT -> populateVaultHub()
            NavTab.PASSPORT -> Unit
        }
    }

    // =========================================================================
    // 1. BOARD (HOME) TAB — LAZY-LOADED STREAMING & STREMIO CATALOG ROWS
    // =========================================================================
    private fun createBoardTabView(): View {
        val headerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, TvUiKit.dp(this@MainActivity, 8), 0, TvUiKit.dp(this@MainActivity, 6))
        }

        // Inline Frosted Glass Stremio Search Bar + Voice Mic (top of Board)
        val boardSearchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 12) }
            setPadding(
                TvUiKit.dp(this@MainActivity, 12),
                TvUiKit.dp(this@MainActivity, 8),
                TvUiKit.dp(this@MainActivity, 12),
                TvUiKit.dp(this@MainActivity, 8)
            )
            background = TvUiKit.createGlassPanelDrawable(this@MainActivity, radiusDp = 16f)
        }

        boardSearchBarBtn = TvUiKit.createPillButton(
            this,
            "🔍  Search Stremio Movies, Series, Anime & Addons..."
        ) {
            openStremioSearchInputDialog()
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }
        boardSearchRow.addView(boardSearchBarBtn)

        val boardVoiceBtn = TvUiKit.createIconPillButton(this, MicIconDrawable(this), "Voice Search Stremio") {
            forceNextVoiceToStremioSearch = true
            startVoiceSearch()
        }.apply {
            minimumWidth = TvUiKit.dp(this@MainActivity, 42)
            minimumHeight = TvUiKit.dp(this@MainActivity, 36)
            layoutParams = LinearLayout.LayoutParams(
                TvUiKit.dp(this@MainActivity, 42),
                TvUiKit.dp(this@MainActivity, 36)
            )
        }
        boardSearchRow.addView(boardVoiceBtn)
        headerContainer.addView(boardSearchRow)

        // Frosted Glass Companion Banner
        boardAuthBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 16) }
            setPadding(
                TvUiKit.dp(this@MainActivity, 18),
                TvUiKit.dp(this@MainActivity, 12),
                TvUiKit.dp(this@MainActivity, 18),
                TvUiKit.dp(this@MainActivity, 12)
            )
            background = TvUiKit.createGlassPanelDrawable(this@MainActivity, radiusDp = 16f)
        }
        headerContainer.addView(boardAuthBanner)

        // Continue Watching Section
        continueSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 18) }
            clipChildren = false
            clipToPadding = false
        }
        continueSection.addView(TvUiKit.eyebrow(this, "PLAYBACK / RESUME"))
        continueSection.addView(TextView(this).apply {
            text = "Continue Watching"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = XirtusFonts.semiBold(this@MainActivity)
            letterSpacing = -0.015f
            setPadding(TvUiKit.dp(this@MainActivity, 2), 0, 0, TvUiKit.dp(this@MainActivity, 6))
        })
        continueAdapter = ContinueWatchingAdapter(emptyList()) { entry ->
            onContinueWatchingClicked(entry)
        }
        val continueRv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            adapter = continueAdapter
        }
        continueSection.addView(continueRv)
        continueSection.addView(TvUiKit.hairlineDivider(this, topMarginDp = 12))
        headerContainer.addView(continueSection)

        lazyBoardAdapter = LazyBoardAdapter(
            headerView = headerContainer,
            scope = lifecycleScope,
            sharedPool = sharedPosterPool,
            onItemClick = { item ->
                DetailActivity.start(this, item.id, item.type, item.name, item.poster)
            },
            onOpenInDiscover = { spec ->
                openCatalogInDiscover(spec.catalog.type, spec.addon.id, spec.catalog.id)
            }
        )

        return RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 4),
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 24)
            )
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = lazyBoardAdapter
        }
    }

    private fun rebuildBoardRows(clearCache: Boolean) {
        val addons = AppPrefs.getInstalledAddons(this)
        val specs = ArrayList<BoardRowSpec>()
        val usedKeys = HashSet<String>()

        fun addCatalogIfPresent(
            addonMatch: (InstalledAddon) -> Boolean,
            type: String,
            catId: String,
            eyebrow: String,
            title: String,
            genre: String? = null
        ) {
            val addon = addons.firstOrNull(addonMatch) ?: return
            val cat = addon.catalogs.firstOrNull { it.type == type && it.id == catId && it.isBrowseSupported } ?: return
            val key = "${addon.id}:${cat.type}:${cat.id}:${genre.orEmpty()}"
            if (usedKeys.add(key)) {
                specs.add(BoardRowSpec(key, eyebrow, title, addon, cat, genre = genre))
            }
        }

        val isStreamingAddon: (InstalledAddon) -> Boolean = {
            it.id == "pw.ers.netflix-catalog" || it.transportUrl.contains("baby-beamup.club", true)
        }
        val isCinemeta: (InstalledAddon) -> Boolean = { it.id == "com.linvo.cinemeta" }

        val curatedNetworkIds = setOf(
            "netflix-top10-global", "nfx", "amp", "dnp", "hbm", "atp", "hlu",
            "cru", "pmp", "pcp", "mbi", "crc", "shd", "stz", "dpe"
        )
        val extraNetworkRows = ArrayList<BoardRowSpec>()

        // 1. Core Stremio Board Top Rows (Popular + New)
        addCatalogIfPresent(isCinemeta, "movie", "top", "STREMIO / POPULAR", "Popular Movies")
        addCatalogIfPresent(isCinemeta, "series", "top", "STREMIO / POPULAR", "Popular Series")
        addCatalogIfPresent(isCinemeta, "movie", "year", "STREMIO / NEW RELEASES", "New Movies", genre = "2025")
        addCatalogIfPresent(isCinemeta, "series", "year", "STREMIO / NEW RELEASES", "New Series", genre = "2025")

        // 2. ALL User-Synced Stremio Specialty & Addon Catalogs FIRST (JAV, Hentai, PublicDomainMovies, Anime, Asian Drama, etc.)
        for (addon in addons) {
            if (isCinemeta(addon) || isStreamingAddon(addon) || addon.id == "org.stremio.opensubtitlesv3") continue
            var liveTvAddedForAddon = 0
            for (cat in addon.catalogs) {
                if (!cat.isBrowseSupported) continue
                // Skip search-only pseudo-catalogs even in legacy cached manifests before live sync finishes
                if (cat.id.endsWith("-search", ignoreCase = true) || cat.id.equals("search", ignoreCase = true) || cat.id.endsWith("_search", ignoreCase = true)) {
                    continue
                }
                // Avoid duplicating curated streaming network rows that are added in section 3
                if ((cat.type == "movie" || cat.type == "series") && cat.id in curatedNetworkIds) {
                    continue
                }
                // Keep top 2 Live TV country rows on Board (all 25+ countries remain in Discover)
                if (cat.type == "tv" && cat.id.startsWith("livetv-")) {
                    if (liveTvAddedForAddon >= 2) continue
                    liveTvAddedForAddon++
                }
                val defaultGenre = if (cat.isGenreRequired) cat.genreOptions.firstOrNull() else null
                // Skip only if genre is strictly required AND zero options exist
                if (cat.isGenreRequired && defaultGenre == null && cat.defaultExtraMap.isEmpty()) continue
                val key = "${addon.id}:${cat.type}:${cat.id}:${defaultGenre.orEmpty()}"
                if (usedKeys.add(key)) {
                    val cleanName = cat.name.ifBlank { cat.id }
                    val typeTag = cat.type.replaceFirstChar { it.uppercase() }
                    val suffix = if (defaultGenre != null) " · $defaultGenre" else ""
                    val rowTitle = if (cleanName.contains(typeTag, ignoreCase = true)) {
                        "$cleanName$suffix"
                    } else {
                        "$cleanName ($typeTag)$suffix"
                    }
                    val rowSpec = BoardRowSpec(
                        key = key,
                        eyebrow = "${addon.name.uppercase()} / ${cat.type.uppercase()}",
                        title = rowTitle,
                        addon = addon,
                        catalog = cat,
                        genre = defaultGenre
                    )
                    // Place secondary regional movie/series networks after primary streaming services
                    if ((cat.type == "movie" || cat.type == "series") && cat.id.length <= 8 && addon.catalogs.size > 40) {
                        extraNetworkRows.add(rowSpec)
                    } else {
                        specs.add(rowSpec)
                    }
                }
            }
        }

        // 3. Full Streaming Services & Curated Cinema Rows (15+ Networks + Regional Networks)
        val hasStreamingOrHub: (InstalledAddon) -> Boolean = {
            isStreamingAddon(it) || it.catalogs.any { c -> c.id == "nfx" && c.type == "movie" }
        }
        addCatalogIfPresent(isStreamingAddon, "movie", "netflix-top10-global", "STREAMING / GLOBAL TOP 10", "Netflix Top 10 Movies")
        addCatalogIfPresent(isStreamingAddon, "series", "netflix-top10-global", "STREAMING / GLOBAL TOP 10", "Netflix Top 10 Series")
        addCatalogIfPresent(isCinemeta, "movie", "imdbRating", "CINEMETA / FEATURED", "Top Rated Movies")
        addCatalogIfPresent(isCinemeta, "series", "imdbRating", "CINEMETA / FEATURED", "Top Rated Series")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "nfx", "NETWORK / NETFLIX", "Netflix Movies")
        addCatalogIfPresent(hasStreamingOrHub, "series", "nfx", "NETWORK / NETFLIX", "Netflix Series")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "amp", "NETWORK / PRIME VIDEO", "Prime Video Movies")
        addCatalogIfPresent(hasStreamingOrHub, "series", "amp", "NETWORK / PRIME VIDEO", "Prime Video Series")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "dnp", "NETWORK / DISNEY+", "Disney+ Movies")
        addCatalogIfPresent(hasStreamingOrHub, "series", "dnp", "NETWORK / DISNEY+", "Disney+ Series")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "hbm", "NETWORK / HBO MAX", "HBO Max Movies")
        addCatalogIfPresent(hasStreamingOrHub, "series", "hbm", "NETWORK / HBO MAX", "HBO Max Series")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "atp", "NETWORK / APPLE TV+", "Apple TV+ Movies")
        addCatalogIfPresent(hasStreamingOrHub, "series", "atp", "NETWORK / APPLE TV+", "Apple TV+ Series")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "hlu", "NETWORK / HULU", "Hulu Movies")
        addCatalogIfPresent(hasStreamingOrHub, "series", "hlu", "NETWORK / HULU", "Hulu Series")
        addCatalogIfPresent(hasStreamingOrHub, "series", "cru", "NETWORK / CRUNCHYROLL", "Crunchyroll Anime")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "pmp", "NETWORK / PARAMOUNT+", "Paramount+ Movies")
        addCatalogIfPresent(hasStreamingOrHub, "series", "pmp", "NETWORK / PARAMOUNT+", "Paramount+ Series")
        addCatalogIfPresent(hasStreamingOrHub, "series", "pcp", "NETWORK / PEACOCK", "Peacock Series")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "mbi", "CURATED / MUBI", "Mubi Cinema")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "crc", "CURATED / CRITERION", "Criterion Channel")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "shd", "CURATED / SHUDDER", "Shudder Horror")
        addCatalogIfPresent(hasStreamingOrHub, "movie", "stz", "NETWORK / STARZ", "Starz Movies")
        addCatalogIfPresent(hasStreamingOrHub, "series", "dpe", "NETWORK / DISCOVERY+", "Discovery+ Series")
        specs.addAll(extraNetworkRows)

        lazyBoardAdapter.submitSpecs(specs, clearCache = clearCache)
    }

    @OptIn(UnstableApi::class)
    private fun onContinueWatchingClicked(entry: WatchHistoryEntry) {
        when {
            entry.itemId.startsWith("yt:") -> {
                val vid = entry.itemId.removePrefix("yt:").ifBlank { entry.videoId }
                if (vid.isNotBlank()) {
                    playStreamingVideoCard(
                        item = StreamCardItem(
                            key = "yt:$vid",
                            kind = "video",
                            platform = "youtube",
                            id = vid,
                            title = entry.name,
                            subtitle = entry.episodeTitle,
                            thumbnailUrl = entry.background.ifBlank { entry.poster },
                            channelName = entry.episodeTitle
                        ),
                        resumeOverrideMs = entry.timeOffsetMs
                    )
                }
            }
            entry.itemId.startsWith("twitch:") ||
                entry.itemId.startsWith("odysee:") ||
                entry.itemId.startsWith("rumble:") -> {
                val plat = entry.itemId.substringBefore(':').lowercase(Locale.US)
                val rawId = entry.itemId.substringAfter(':').ifBlank { entry.videoId }
                if (rawId.isNotBlank()) {
                    playStreamingVideoCard(
                        item = StreamCardItem(
                            key = entry.itemId,
                            kind = "video",
                            platform = plat,
                            id = rawId,
                            title = entry.name,
                            subtitle = entry.episodeTitle,
                            thumbnailUrl = entry.background.ifBlank { entry.poster },
                            channelName = entry.episodeTitle
                        ),
                        resumeOverrideMs = entry.timeOffsetMs
                    )
                }
            }
            JellyfinIds.isJellyfinId(entry.itemId) -> {
                DetailActivity.start(
                    context = this,
                    itemId = entry.itemId,
                    contentType = entry.type.ifBlank { "movie" },
                    title = entry.name,
                    poster = entry.poster,
                    resumeVideoId = entry.videoId,
                    resumeOffsetMs = entry.timeOffsetMs
                )
            }
            !entry.directStreamUrl.isNullOrBlank() || (!entry.infoHash.isNullOrBlank() && entry.itemId.startsWith("torrent:")) -> {
                PlayerActivity.start(
                    this,
                    PlayerActivity.LaunchPayload(
                        title = entry.name,
                        subtitle = entry.episodeTitle,
                        itemId = entry.itemId,
                        videoId = entry.videoId,
                        contentType = entry.type,
                        poster = entry.poster,
                        background = entry.background,
                        directUrl = entry.directStreamUrl,
                        headers = entry.directHeaders,
                        infoHash = entry.infoHash,
                        fileIdx = entry.fileIdx,
                        resumePositionMs = entry.timeOffsetMs
                    )
                )
            }
            !entry.itemId.startsWith("kodi:") -> {
                DetailActivity.start(
                    context = this,
                    itemId = entry.itemId,
                    contentType = entry.type,
                    title = entry.name,
                    poster = entry.poster,
                    resumeVideoId = entry.videoId,
                    resumeOffsetMs = entry.timeOffsetMs
                )
            }
        }
    }

    private fun refreshContinueWatchingRow() {
        val localHistory = AppPrefs.getWatchHistory(this)
        if (localHistory.isEmpty()) {
            continueSection.visibility = View.GONE
        } else {
            continueSection.visibility = View.VISIBLE
            continueAdapter.submit(localHistory)
        }

        val activeJfServers = AppPrefs.getJellyfinServers(this).filter { it.enabled && it.isAuthenticated }
        continueLoadJob?.cancel()
        if (activeJfServers.isEmpty()) return

        continueLoadJob = lifecycleScope.launch {
            val jfEntries: List<WatchHistoryEntry> = withTimeoutOrNull(5_500L) {
                coroutineScope {
                    activeJfServers.map { srv ->
                        async {
                            val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(srv)
                            if (baseUrl.isBlank()) return@async emptyList<WatchHistoryEntry>()
                            val resumeItems = JellyfinApiClient.getResumeItems(this@MainActivity, srv, limit = 6)
                            resumeItems.map { item ->
                                val baseEntry = item.toWatchHistoryEntry(srv.serverId, baseUrl)
                                if (activeJfServers.size > 1) {
                                    val ep = if (baseEntry.episodeTitle.isNotBlank()) {
                                        "${srv.name} · ${baseEntry.episodeTitle}"
                                    } else {
                                        srv.name
                                    }
                                    baseEntry.copy(episodeTitle = ep)
                                } else {
                                    baseEntry
                                }
                            }
                        }
                    }.awaitAll().flatten()
                }
            }.orEmpty()

            if (jfEntries.isEmpty()) return@launch

            val freshLocal = AppPrefs.getWatchHistory(this@MainActivity)
            val mergedById = LinkedHashMap<String, WatchHistoryEntry>()
            for (loc in freshLocal) {
                mergedById[loc.itemId] = loc
            }
            for (jf in jfEntries) {
                val existing = mergedById[jf.itemId]
                if (existing == null) {
                    mergedById[jf.itemId] = jf
                } else {
                    // Refresh poster/progress from live Jellyfin server while preserving local recency order
                    mergedById[jf.itemId] = jf.copy(updatedAtMs = existing.updatedAtMs)
                }
            }
            val mergedList = mergedById.values.toList()
            if (mergedList.isNotEmpty()) {
                continueSection.visibility = View.VISIBLE
                continueAdapter.submit(mergedList)
            }
        }
    }

    private fun refreshAccountIndicators() {
        val authKey = AppPrefs.getStremioAuthKey(this)
        val email = AppPrefs.getStremioEmail(this)
        val mp = AppPrefs.getMultipassProfile(this)
        val addonCount = AppPrefs.getInstalledAddons(this).size

        val isSynced = authKey != null || mp.handle.isNotBlank()
        topStatusDot.text = if (isSynced) {
            "● ${mp.handle.ifBlank { email ?: "Synced" }}"
        } else {
            "○ $addonCount Addons"
        }
        topStatusDot.setTextColor(if (isSynced) XirtusPalette.EMERALD else XirtusPalette.TEXT_SECONDARY)

        boardAuthBanner.removeAllViews()
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 14)
            }
        }

        if (authKey == null && mp.handle.isBlank()) {
            infoCol.addView(TvUiKit.eyebrow(this, "COMPANION / PRIVATE BY DESIGN"))
            infoCol.addView(TextView(this).apply {
                text = "Explore 15+ Streaming Networks in Discover, or pair your Passport"
                setTextColor(XirtusPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = XirtusFonts.semiBold(this@MainActivity)
                setPadding(0, TvUiKit.dp(this@MainActivity, 2), 0, TvUiKit.dp(this@MainActivity, 2))
            })
            infoCol.addView(TextView(this).apply {
                text = "Netflix, HBO Max, Disney+, Prime Video, Apple TV+, Popular & By Year are ready now. Pair Stremio or Multipass anytime."
                setTextColor(XirtusPalette.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = XirtusFonts.regular(this@MainActivity)
            })
            boardAuthBanner.addView(infoCol)
            boardAuthBanner.addView(TvUiKit.createPillButton(this, "Explore Discover →") {
                switchTab(NavTab.DISCOVER)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = TvUiKit.dp(this@MainActivity, 8)
                }
            })
            boardAuthBanner.addView(TvUiKit.createPillButton(this, "Open Passport ↗", selected = true) {
                switchTab(NavTab.PASSPORT)
            })
        } else {
            infoCol.addView(TvUiKit.eyebrow(this, "COMPANION / PASSPORT ACTIVE"))
            infoCol.addView(TextView(this).apply {
                val idLabel = mp.handle.ifBlank { email ?: "Stremio Cloud" }
                text = "Connected as $idLabel  ·  $addonCount Active Addons"
                setTextColor(XirtusPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = XirtusFonts.semiBold(this@MainActivity)
                setPadding(0, TvUiKit.dp(this@MainActivity, 2), 0, 0)
            })
            boardAuthBanner.addView(infoCol)
            boardAuthBanner.addView(TvUiKit.createPillButton(this, "Explore Discover →") {
                switchTab(NavTab.DISCOVER)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = TvUiKit.dp(this@MainActivity, 8)
                }
            })
            if (authKey != null) {
                boardAuthBanner.addView(TvUiKit.createPillButton(this, "↻ Sync Cloud") {
                    lifecycleScope.launch {
                        Toast.makeText(this@MainActivity, "Syncing Stremio Cloud...", Toast.LENGTH_SHORT).show()
                        StremioApiClient.syncAccountEverything(this@MainActivity, authKey)
                        refreshAccountIndicators()
                        rebuildBoardRows(clearCache = true)
                        rebuildDiscoverCatalogChoices(keepSelection = true, triggerLoad = activeTab == NavTab.DISCOVER)
                    }
                })
            }
        }
    }

    // =========================================================================
    // 2. DISCOVER TAB — FULL STREMIO GALLERY & STREAMING NETWORKS BROWSER
    // =========================================================================
    private fun createDiscoverTabView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 10),
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 12)
            )
        }

        // Top Frosted Glass Filter Bar (4 crisp D-pad filter selector pills + status counter)
        val filterPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 10),
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 10)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
            background = TvUiKit.createGlassPanelDrawable(this@MainActivity, radiusDp = 14f)
        }

        discoverTypePill = TvUiKit.createPillButton(this, "Type: Movies ▾") {
            showDiscoverTypeSelector()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }

        discoverCatalogPill = TvUiKit.createPillButton(this, "Catalog: Popular ▾", selected = true) {
            showDiscoverCatalogSelector()
        }.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = TvUiKit.dp(this@MainActivity, 215)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }

        discoverGenrePill = TvUiKit.createPillButton(this, "Genre: All ▾") {
            showDiscoverGenreOrYearSelector()
        }.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = TvUiKit.dp(this@MainActivity, 165)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }

        discoverSortPill = TvUiKit.createPillButton(this, "Sort: Default ▾") {
            showDiscoverSortSelector()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }

        discoverResetPill = TvUiKit.createPillButton(this, "↺ Reset", compact = true) {
            resetDiscoverFilters()
        }.apply {
            visibility = View.GONE
        }

        filterPanel.addView(discoverTypePill)
        filterPanel.addView(discoverCatalogPill)
        filterPanel.addView(discoverGenrePill)
        filterPanel.addView(discoverSortPill)
        filterPanel.addView(discoverResetPill)

        filterPanel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        discoverSpinner = ProgressBar(this).apply {
            val sz = TvUiKit.dp(this@MainActivity, 16)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = TvUiKit.dp(this@MainActivity, 8) }
            visibility = View.GONE
        }
        discoverStatusMono = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoMedium(this@MainActivity)
            letterSpacing = 0.08f
            text = "GALLERY"
        }
        val discoverQuickSearchPill = TvUiKit.createPillButton(this, "🔍 Search", compact = true) {
            openStremioSearchInputDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = TvUiKit.dp(this@MainActivity, 8) }
        }
        filterPanel.addView(discoverSpinner)
        filterPanel.addView(discoverStatusMono)
        filterPanel.addView(discoverQuickSearchPill)
        col.addView(filterPanel)

        // Quick Network + Synced Addon Ribbon (1-click D-pad catalog switcher!)
        val ribbonScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
        }
        discoverRibbonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ribbonScroll.addView(discoverRibbonRow)
        col.addView(ribbonScroll)
        rebuildDiscoverRibbonChips()

        discoverEmptyText = TextView(this).apply {
            text = "No titles matched this filter."
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            typeface = XirtusFonts.regular(this@MainActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(TvUiKit.dp(this@MainActivity, 6), TvUiKit.dp(this@MainActivity, 20), 0, 0)
            visibility = View.GONE
        }
        col.addView(discoverEmptyText)

        // 6-Column Infinite-Scroll Poster Gallery Grid (GridLayoutManager(this, 6))
        discoverGridAdapter = DiscoverGridAdapter { item ->
            DetailActivity.start(this, item.id, item.type, item.name, item.poster)
        }
        val gridLayoutManager = GridLayoutManager(this, 6)
        val gridRv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = gridLayoutManager
            setRecycledViewPool(sharedDiscoverPool)
            itemAnimator = null
            clipToPadding = false
            adapter = discoverGridAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val total = gridLayoutManager.itemCount
                    val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                    if (total > 0 && lastVisible >= total - 12) {
                        maybeLoadNextDiscoverPage()
                    }
                }
            })
        }
        col.addView(gridRv)
        return col
    }

    private fun rebuildDiscoverRibbonChips() {
        if (!::discoverRibbonRow.isInitialized) return
        discoverRibbonRow.removeAllViews()
        quickNetworkButtons.clear()

        val allChips = ArrayList<QuickNetworkChip>()
        allChips.add(QuickNetworkChip("top", "Popular", "top"))
        allChips.add(QuickNetworkChip("year", "By Year", "year"))
        allChips.add(QuickNetworkChip("imdbRating", "Top Rated", "imdbRating"))

        // Dynamically surface ALL user-synced Stremio addons (JAV, Hentai, PublicDomainMovies, Anime, etc.) right on the ribbon!
        val addons = AppPrefs.getInstalledAddons(this)
        for (addon in addons) {
            val isBuiltIn = addon.id == "com.linvo.cinemeta" ||
                addon.id == "pw.ers.netflix-catalog" ||
                addon.transportUrl.contains("baby-beamup.club", true) ||
                addon.id == "org.stremio.opensubtitlesv3"
            if (isBuiltIn) continue
            val browsable = addon.catalogs.filter { it.isBrowseSupported }
            browsable.take(8).forEach { cat ->
                val chipKey = "${addon.id}:${cat.id}"
                val shortName = if (browsable.size == 1) {
                    addon.name.take(22)
                } else {
                    "${addon.name.take(12)} · ${cat.name.ifBlank { cat.id }.take(15)}"
                }
                allChips.add(
                    QuickNetworkChip(
                        key = chipKey,
                        label = shortName,
                        catalogId = cat.id,
                        preferredType = cat.type,
                        addonId = addon.id
                    )
                )
            }
        }

        // Append all Streaming Network chips
        quickNetworkSpecs.filter { it.key != "top" && it.key != "year" && it.key != "imdbRating" }.forEach { allChips.add(it) }

        for (chipSpec in allChips) {
            val chip = TvUiKit.createPillButton(this, chipSpec.label, compact = true) {
                onQuickNetworkChipClicked(chipSpec)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            quickNetworkButtons[chipSpec.key] = chip
            discoverRibbonRow.addView(chip)
        }
    }

    private fun formatDiscoverTypeName(rawType: String): String = when (rawType.lowercase()) {
        "all" -> "All Types"
        "movie" -> "Movies"
        "series" -> "Series"
        "anime" -> "Anime"
        "jav" -> "JAV"
        "hentai" -> "Hentai"
        "tv" -> "Live TV"
        "channel" -> "Channels"
        "other" -> "Other / Custom"
        else -> rawType.replaceFirstChar { it.uppercase() }
    }

    private fun availableDiscoverTypes(): List<Pair<String, String>> {
        val addons = AppPrefs.getInstalledAddons(this)
        val rawTypes = LinkedHashSet<String>()
        rawTypes.add("all")
        rawTypes.add("movie")
        rawTypes.add("series")
        rawTypes.add("anime")
        for (a in addons) {
            for (c in a.catalogs) {
                if (c.isBrowseSupported) rawTypes.add(c.type)
            }
        }
        return rawTypes.map { t -> t to formatDiscoverTypeName(t) }
    }

    private fun formatCatalogLabelForDiscover(addon: InstalledAddon, cat: AddonCatalogDef): Pair<String, String> {
        if (addon.id == "com.linvo.cinemeta") {
            return when (cat.id) {
                "top" -> "Popular (Cinemeta)" to "top"
                "imdbRating" -> "Top Rated / IMDb Featured" to "imdbRating"
                "year" -> "By Year (2026–1920) · Cinemeta" to "year"
                else -> "${cat.name.ifBlank { cat.id }} (Cinemeta)" to cat.id
            }
        }
        val isStreaming = addon.id == "pw.ers.netflix-catalog" || addon.transportUrl.contains("baby-beamup.club", true)
        if (isStreaming) {
            val nice = when (cat.id) {
                "netflix-top10-global" -> "Netflix Top 10 (Global)"
                "nfx" -> "Netflix"
                "amp" -> "Prime Video"
                "dnp" -> "Disney+"
                "hbm" -> "HBO Max"
                "atp" -> "Apple TV+"
                "hlu" -> "Hulu"
                "pcp" -> "Peacock"
                "pmp" -> "Paramount+"
                "cru" -> "Crunchyroll"
                "mbi" -> "Mubi"
                "crc" -> "Criterion Channel"
                "shd" -> "Shudder"
                "stz" -> "Starz"
                "dpe" -> "Discovery+"
                else -> cat.name.ifBlank { cat.id }
            }
            return "Streaming · $nice" to cat.id
        }
        val baseName = cat.name.ifBlank { cat.id }
        return "${addon.name} · $baseName" to "${addon.id}:${cat.id}"
    }

    private fun defaultRequiredOptionForCatalog(cat: AddonCatalogDef): String? {
        if (cat.id == "year") {
            return if ("2025" in cat.genreOptions) "2025" else (cat.genreOptions.firstOrNull() ?: "2025")
        }
        return cat.genreOptions.firstOrNull()
    }

    private fun rebuildDiscoverCatalogChoices(keepSelection: Boolean, triggerLoad: Boolean) {
        rebuildDiscoverRibbonChips()
        val addons = AppPrefs.getInstalledAddons(this)
        val entries = ArrayList<DiscoverCatalogEntry>()

        when (discoverType) {
            "all" -> {
                // Show User-Synced Addons FIRST, then Cinemeta (Popular, Top Rated, By Year), then Streaming Networks across EVERY type!
                val orderedAddons = addons.sortedBy { a ->
                    when {
                        a.id == "com.linvo.cinemeta" -> 1
                        a.id == "pw.ers.netflix-catalog" || a.transportUrl.contains("baby-beamup.club", true) -> 2
                        else -> 0
                    }
                }
                for (addon in orderedAddons) {
                    for (cat in addon.catalogs) {
                        if (!cat.isBrowseSupported) continue
                        val (lbl, chipKey) = formatCatalogLabelForDiscover(addon, cat)
                        val typeSuffix = " (${formatDiscoverTypeName(cat.type)})"
                        entries.add(DiscoverCatalogEntry(addon, cat, lbl + typeSuffix, chipKey))
                    }
                }
            }
            "anime" -> {
                for (addon in addons) {
                    for (cat in addon.catalogs) {
                        if (!cat.isBrowseSupported) continue
                        if (cat.type == "anime" || cat.id == "cru" || addon.name.contains("anime", true) || addon.name.contains("kitsu", true)) {
                            val (lbl, chipKey) = formatCatalogLabelForDiscover(addon, cat)
                            val typeSuffix = if (cat.type != "anime") " (${formatDiscoverTypeName(cat.type)})" else ""
                            entries.add(DiscoverCatalogEntry(addon, cat, lbl + typeSuffix, chipKey))
                        }
                    }
                }
                val cinemeta = addons.firstOrNull { it.id == "com.linvo.cinemeta" }
                cinemeta?.catalogs?.filter { it.id == "top" || it.id == "imdbRating" }?.forEach { cat ->
                    val prefix = if (cat.id == "imdbRating") "Top Rated Animation" else "Popular Animation"
                    entries.add(
                        DiscoverCatalogEntry(
                            cinemeta,
                            cat,
                            "Cinemeta · $prefix (${formatDiscoverTypeName(cat.type)})",
                            cat.id
                        )
                    )
                }
            }
            else -> {
                // Cinemeta (Popular, Top Rated, By Year) + User-synced addons + Streaming Services matching discoverType
                val orderedAddons = addons.sortedBy { a ->
                    when {
                        a.id == "com.linvo.cinemeta" -> 0
                        a.id == "pw.ers.netflix-catalog" || a.transportUrl.contains("baby-beamup.club", true) -> 2
                        else -> 1
                    }
                }
                for (addon in orderedAddons) {
                    for (cat in addon.catalogs) {
                        if (!cat.isBrowseSupported || cat.type != discoverType) continue
                        val (lbl, chipKey) = formatCatalogLabelForDiscover(addon, cat)
                        entries.add(DiscoverCatalogEntry(addon, cat, lbl, chipKey))
                    }
                }
            }
        }

        discoverCatalogsForType = entries
        val prev = selectedDiscoverEntry
        selectedDiscoverEntry = if (keepSelection && prev != null) {
            entries.firstOrNull { it.addon.id == prev.addon.id && it.catalog.id == prev.catalog.id && it.catalog.type == prev.catalog.type }
                ?: entries.firstOrNull { it.catalog.id == prev.catalog.id }
                ?: entries.firstOrNull()
        } else {
            entries.firstOrNull()
        }

        val curCat = selectedDiscoverEntry?.catalog
        if (curCat != null) {
            if (curCat.isGenreRequired) {
                val allowedOpts = if (curCat.id == "year") discoverExtendedYears else curCat.genreOptions
                if (selectedDiscoverGenre == null || (allowedOpts.isNotEmpty() && selectedDiscoverGenre !in allowedOpts)) {
                    selectedDiscoverGenre = defaultRequiredOptionForCatalog(curCat)
                }
            } else if (discoverType == "anime" && selectedDiscoverEntry?.addon?.id == "com.linvo.cinemeta") {
                selectedDiscoverGenre = "Animation"
            } else if (curCat.isGenreSupported && curCat.genreOptions.isNotEmpty() && selectedDiscoverGenre != null && selectedDiscoverGenre !in curCat.genreOptions) {
                selectedDiscoverGenre = null
            }
        }

        updateDiscoverFilterBarLabels()
        if (triggerLoad) {
            reloadDiscoverCatalogFromScratch()
        }
    }

    private fun updateDiscoverFilterBarLabels() {
        val typeLabel = formatDiscoverTypeName(discoverType)
        discoverTypePill.text = "Type: $typeLabel ▾"

        val entry = selectedDiscoverEntry
        val shortCat = when {
            entry == null -> "None"
            entry.addon.id == "com.linvo.cinemeta" && entry.catalog.id == "year" -> "By Year"
            else -> entry.displayLabel
                .removePrefix("Streaming · ")
                .removeSuffix(" · Cinemeta")
                .removeSuffix(" (Cinemeta)")
        }
        discoverCatalogPill.text = "Catalog: $shortCat ▾"

        val cat = entry?.catalog
        if (cat?.id == "year") {
            discoverGenrePill.text = "Year: ${selectedDiscoverGenre ?: "2025"} ▾"
            TvUiKit.setPillSelected(discoverGenrePill, true)
        } else if (cat?.isGenreRequired == true) {
            val paramTitle = cat.extraParamName.replaceFirstChar { it.uppercase() }
            discoverGenrePill.text = "$paramTitle: ${selectedDiscoverGenre ?: cat.genreOptions.firstOrNull() ?: "Default"} ▾"
            TvUiKit.setPillSelected(discoverGenrePill, true)
        } else {
            val paramTitle = cat?.extraParamName?.takeIf { it != "genre" }?.replaceFirstChar { it.uppercase() } ?: "Genre"
            val gLabel = selectedDiscoverGenre ?: "All"
            discoverGenrePill.text = "$paramTitle: $gLabel ▾"
            TvUiKit.setPillSelected(discoverGenrePill, selectedDiscoverGenre != null)
        }

        discoverSortPill.text = "Sort: ${selectedDiscoverSort.shortLabel} ▾"
        TvUiKit.setPillSelected(discoverSortPill, selectedDiscoverSort != DiscoverSortMode.DEFAULT)

        if (::discoverResetPill.isInitialized) {
            val hasActiveFilter = selectedDiscoverSort != DiscoverSortMode.DEFAULT ||
                (!cat?.id.equals("year") && selectedDiscoverGenre != null && !(discoverType == "anime" && entry?.addon?.id == "com.linvo.cinemeta")) ||
                (discoverType != "movie") ||
                (entry != null && !(entry.addon.id == "com.linvo.cinemeta" && entry.catalog.id == "top"))
            discoverResetPill.visibility = if (hasActiveFilter) View.VISIBLE else View.GONE
        }

        val activeKey = entry?.shortChipKey ?: ""
        quickNetworkButtons.forEach { (key, btn) ->
            TvUiKit.setPillSelected(btn, key == activeKey)
        }
    }

    private fun resetDiscoverFilters() {
        discoverType = "movie"
        selectedDiscoverGenre = null
        selectedDiscoverSort = DiscoverSortMode.DEFAULT
        rebuildDiscoverCatalogChoices(keepSelection = false, triggerLoad = true)
    }

    private fun onQuickNetworkChipClicked(chip: QuickNetworkChip) {
        if (chip.addonId != null) {
            openCatalogInDiscover(chip.preferredType ?: "movie", chip.addonId, chip.catalogId)
            return
        }
        val addons = AppPrefs.getInstalledAddons(this)
        val curType = if (discoverType == "anime" || discoverType == "all") (chip.preferredType ?: "movie") else discoverType
        val hasForCurType = addons.any { a ->
            a.catalogs.any { it.isBrowseSupported && it.type == curType && it.id == chip.catalogId }
        }
        val targetType = if (hasForCurType) {
            curType
        } else {
            chip.preferredType ?: if (curType == "movie") "series" else "movie"
        }

        discoverType = targetType
        selectedDiscoverGenre = null
        rebuildDiscoverCatalogChoices(keepSelection = false, triggerLoad = false)
        val matched = discoverCatalogsForType.firstOrNull { it.catalog.id == chip.catalogId }
        if (matched != null) {
            selectedDiscoverEntry = matched
            if (matched.catalog.isGenreRequired) {
                selectedDiscoverGenre = defaultRequiredOptionForCatalog(matched.catalog)
            }
        }
        updateDiscoverFilterBarLabels()
        reloadDiscoverCatalogFromScratch()
    }

    private fun openCatalogInDiscover(type: String, addonId: String, catalogId: String) {
        discoverType = type
        selectedDiscoverGenre = null
        selectedDiscoverSort = DiscoverSortMode.DEFAULT
        rebuildDiscoverCatalogChoices(keepSelection = false, triggerLoad = false)
        val matched = discoverCatalogsForType.firstOrNull {
            it.addon.id == addonId && it.catalog.id == catalogId && it.catalog.type == type
        } ?: discoverCatalogsForType.firstOrNull { it.catalog.id == catalogId }
        if (matched != null) {
            selectedDiscoverEntry = matched
            if (matched.catalog.isGenreRequired) {
                selectedDiscoverGenre = defaultRequiredOptionForCatalog(matched.catalog)
            }
        }
        updateDiscoverFilterBarLabels()
        switchTab(NavTab.DISCOVER)
        reloadDiscoverCatalogFromScratch()
    }

    private fun showDiscoverTypeSelector() {
        val types = availableDiscoverTypes()
        val curIdx = types.indexOfFirst { it.first == discoverType }.coerceAtLeast(0)
        TvUiKit.showSelectDialog(
            context = this,
            title = "Select Content Type",
            options = types.map { it.second },
            selectedIndex = curIdx,
            eyebrowLabel = "DISCOVER / CONTENT TYPE"
        ) { idx ->
            if (idx in types.indices) {
                discoverType = types[idx].first
                selectedDiscoverGenre = null
                rebuildDiscoverCatalogChoices(keepSelection = true, triggerLoad = true)
            }
        }
    }

    private fun showDiscoverCatalogSelector() {
        // Combine catalogs for the active type + any user-synced custom catalogs across other types so 100% of synced Stremio catalogs are reachable in 1 click!
        val combined = ArrayList<Pair<DiscoverCatalogEntry, Boolean>>()
        discoverCatalogsForType.forEach { combined.add(it to false) }

        if (discoverType != "all") {
            val existingKeys = discoverCatalogsForType.mapTo(HashSet()) { "${it.addon.id}:${it.catalog.type}:${it.catalog.id}" }
            val addons = AppPrefs.getInstalledAddons(this)
            for (addon in addons) {
                val isBuiltIn = addon.id == "com.linvo.cinemeta" ||
                    addon.id == "pw.ers.netflix-catalog" ||
                    addon.transportUrl.contains("baby-beamup.club", true) ||
                    addon.id == "org.stremio.opensubtitlesv3"
                if (isBuiltIn) continue
                for (cat in addon.catalogs) {
                    if (!cat.isBrowseSupported) continue
                    val key = "${addon.id}:${cat.type}:${cat.id}"
                    if (existingKeys.add(key)) {
                        val (lbl, chipKey) = formatCatalogLabelForDiscover(addon, cat)
                        val crossLabel = "[${formatDiscoverTypeName(cat.type)}] $lbl"
                        combined.add(DiscoverCatalogEntry(addon, cat, crossLabel, chipKey) to true)
                    }
                }
            }
        }

        if (combined.isEmpty()) return
        val curIdx = combined.indexOfFirst { (e, _) ->
            selectedDiscoverEntry != null &&
                e.addon.id == selectedDiscoverEntry?.addon?.id &&
                e.catalog.id == selectedDiscoverEntry?.catalog?.id &&
                e.catalog.type == selectedDiscoverEntry?.catalog?.type
        }.coerceAtLeast(0)

        TvUiKit.showSelectDialog(
            context = this,
            title = "Select Catalog, Network, or Synced Addon",
            options = combined.map { it.first.displayLabel },
            selectedIndex = curIdx,
            eyebrowLabel = "DISCOVER / STREAMING & SYNCED CATALOGS"
        ) { idx ->
            if (idx in combined.indices) {
                val (chosen, isCrossType) = combined[idx]
                if (isCrossType) {
                    openCatalogInDiscover(chosen.catalog.type, chosen.addon.id, chosen.catalog.id)
                    return@showSelectDialog
                }
                selectedDiscoverEntry = chosen
                selectedDiscoverGenre = if (chosen.catalog.isGenreRequired) {
                    defaultRequiredOptionForCatalog(chosen.catalog)
                } else if (discoverType == "anime" && chosen.addon.id == "com.linvo.cinemeta") {
                    "Animation"
                } else {
                    null
                }
                updateDiscoverFilterBarLabels()
                reloadDiscoverCatalogFromScratch()
            }
        }
    }

    private fun findYearCatalogForActiveType(): DiscoverCatalogEntry? {
        discoverCatalogsForType.firstOrNull { it.catalog.id == "year" && it.catalog.type == selectedDiscoverEntry?.catalog?.type }
            ?.let { return it }
        discoverCatalogsForType.firstOrNull { it.catalog.id == "year" }
            ?.let { return it }
        val targetType = if (discoverType == "series") "series" else "movie"
        val cinemeta = AppPrefs.getInstalledAddons(this).firstOrNull { it.id == "com.linvo.cinemeta" } ?: return null
        val yearCat = cinemeta.catalogs.firstOrNull { it.id == "year" && it.type == targetType } ?: return null
        val (lbl, chipKey) = formatCatalogLabelForDiscover(cinemeta, yearCat)
        return DiscoverCatalogEntry(cinemeta, yearCat, lbl, chipKey)
    }

    private fun showDiscoverGenreOrYearSelector() {
        val entry = selectedDiscoverEntry ?: return
        val cat = entry.catalog

        if (cat.isGenreRequired) {
            val isYear = cat.id == "year"
            val rawYearsOrOpts = if (isYear && entry.addon.id == "com.linvo.cinemeta") {
                discoverExtendedYears
            } else {
                cat.genreOptions.ifEmpty {
                    if (isYear) discoverExtendedYears
                    else if (cat.type == "series") AppPrefs.CINEMETA_SERIES_GENRES
                    else AppPrefs.CINEMETA_MOVIE_GENRES
                }
            }
            val switchToGenreAction = if (isYear) "Switch to Popular by Genre..." else null
            val dialogOptions = if (switchToGenreAction != null) {
                listOf(switchToGenreAction) + rawYearsOrOpts
            } else {
                rawYearsOrOpts
            }
            val offset = if (switchToGenreAction != null) 1 else 0
            val yearIdx = rawYearsOrOpts.indexOf(selectedDiscoverGenre ?: rawYearsOrOpts.firstOrNull().orEmpty()).coerceAtLeast(0)
            val paramTitle = if (isYear) "Release Year (2026–1920)" else cat.extraParamName.replaceFirstChar { it.uppercase() }

            TvUiKit.showSelectDialog(
                context = this,
                title = "Browse By $paramTitle",
                options = dialogOptions,
                selectedIndex = yearIdx + offset,
                eyebrowLabel = "DISCOVER / ${paramTitle.uppercase()}"
            ) { idx ->
                if (idx !in dialogOptions.indices) return@showSelectDialog
                if (switchToGenreAction != null && idx == 0) {
                    val popMatch = discoverCatalogsForType.firstOrNull {
                        it.addon.id == "com.linvo.cinemeta" && it.catalog.id == "top" && it.catalog.type == cat.type
                    } ?: discoverCatalogsForType.firstOrNull { it.catalog.id == "top" }
                    if (popMatch != null) {
                        selectedDiscoverEntry = popMatch
                        selectedDiscoverGenre = null
                        updateDiscoverFilterBarLabels()
                        reloadDiscoverCatalogFromScratch()
                        showDiscoverGenreOrYearSelector()
                    }
                    return@showSelectDialog
                }
                selectedDiscoverGenre = rawYearsOrOpts[idx - offset]
                updateDiscoverFilterBarLabels()
                reloadDiscoverCatalogFromScratch()
            }
            return
        }

        // Server-side genre/category/extra options if supported, otherwise dynamic client-side genres from loaded metas!
        val serverSide = cat.isGenreSupported && cat.genreOptions.isNotEmpty()
        val baseGenres = if (serverSide) {
            cat.genreOptions
        } else {
            val dynamic = discoverRawItems.flatMap { it.genres }.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
            if (dynamic.isNotEmpty()) dynamic
            else if (cat.type == "series") AppPrefs.CINEMETA_SERIES_GENRES
            else AppPrefs.CINEMETA_MOVIE_GENRES
        }

        val yearCatCandidate = if (cat.id != "year") findYearCatalogForActiveType() else null
        val switchYearLabel = if (yearCatCandidate != null) "Switch to By Year (2026–1920)..." else null

        val genreChoices = buildList {
            add("All")
            if (switchYearLabel != null) add(switchYearLabel)
            addAll(baseGenres)
        }
        val curIdx = if (selectedDiscoverGenre == null) 0 else genreChoices.indexOf(selectedDiscoverGenre).coerceAtLeast(0)
        val filterLabel = cat.extraParamName.replaceFirstChar { it.uppercase() }

        TvUiKit.showSelectDialog(
            context = this,
            title = if (serverSide) "Filter by $filterLabel" else "Filter Titles by Genre",
            options = genreChoices,
            selectedIndex = curIdx,
            eyebrowLabel = "DISCOVER / ${filterLabel.uppercase()} & YEAR"
        ) { idx ->
            if (idx !in genreChoices.indices) return@showSelectDialog
            val picked = genreChoices[idx]
            if (switchYearLabel != null && picked == switchYearLabel && yearCatCandidate != null) {
                if (discoverType != "all" && yearCatCandidate.catalog.type != discoverType) {
                    discoverType = yearCatCandidate.catalog.type
                    rebuildDiscoverCatalogChoices(keepSelection = false, triggerLoad = false)
                }
                val resolvedYearEntry = discoverCatalogsForType.firstOrNull {
                    it.addon.id == yearCatCandidate.addon.id &&
                        it.catalog.id == "year" &&
                        it.catalog.type == yearCatCandidate.catalog.type
                } ?: yearCatCandidate
                selectedDiscoverEntry = resolvedYearEntry
                if (selectedDiscoverGenre !in discoverExtendedYears) {
                    selectedDiscoverGenre = defaultRequiredOptionForCatalog(resolvedYearEntry.catalog)
                }
                updateDiscoverFilterBarLabels()
                reloadDiscoverCatalogFromScratch()
                showDiscoverGenreOrYearSelector()
                return@showSelectDialog
            }

            selectedDiscoverGenre = if (idx == 0) null else picked
            updateDiscoverFilterBarLabels()
            if (serverSide) {
                reloadDiscoverCatalogFromScratch()
            } else {
                applyDiscoverClientFilterAndSort()
            }
        }
    }

    private fun showDiscoverSortSelector() {
        val modes = DiscoverSortMode.entries
        val resetOptionLabel = "↺ Reset All Discover Filters & Sort"
        val options = modes.map { it.label } + resetOptionLabel
        val curIdx = modes.indexOf(selectedDiscoverSort).coerceAtLeast(0)
        TvUiKit.showSelectDialog(
            context = this,
            title = "Sort or Reset Gallery",
            options = options,
            selectedIndex = curIdx,
            eyebrowLabel = "DISCOVER / SORT & RESET"
        ) { idx ->
            when {
                idx in modes.indices -> {
                    selectedDiscoverSort = modes[idx]
                    updateDiscoverFilterBarLabels()
                    applyDiscoverClientFilterAndSort()
                }
                idx == modes.size -> {
                    resetDiscoverFilters()
                }
            }
        }
    }

    private fun reloadDiscoverCatalogFromScratch() {
        val entry = selectedDiscoverEntry ?: run {
            discoverGridAdapter.submit(emptyList())
            discoverStatusMono.text = "0 TITLES"
            return
        }
        discoverLoadJob?.cancel()
        discoverRawItems.clear()
        discoverReachedEnd = false
        discoverIsLoading = true
        discoverSpinner.visibility = View.VISIBLE
        discoverEmptyText.visibility = View.GONE
        discoverStatusMono.text = "LOADING..."
        discoverGridAdapter.submit(emptyList())

        val serverGenre = if (entry.catalog.isGenreSupported || entry.catalog.isGenreRequired) {
            selectedDiscoverGenre
        } else null

        discoverLoadJob = lifecycleScope.launch {
            val fetched = StremioApiClient.fetchCatalog(
                addon = entry.addon,
                catalog = entry.catalog,
                genre = serverGenre,
                skip = 0
            )
            discoverIsLoading = false
            discoverSpinner.visibility = View.GONE

            val seen = HashSet<String>()
            for (item in fetched) {
                if (seen.add(item.id)) {
                    discoverRawItems.add(item)
                }
            }
            if (fetched.size < 20 || !entry.catalog.isSkipSupported) {
                discoverReachedEnd = true
            }
            applyDiscoverClientFilterAndSort()
        }
    }

    private fun maybeLoadNextDiscoverPage() {
        val entry = selectedDiscoverEntry ?: return
        if (discoverIsLoading || discoverReachedEnd || !entry.catalog.isSkipSupported) return
        if (discoverRawItems.isEmpty()) return

        discoverIsLoading = true
        discoverSpinner.visibility = View.VISIBLE
        val nextSkip = discoverRawItems.size
        val serverGenre = if (entry.catalog.isGenreSupported || entry.catalog.isGenreRequired) {
            selectedDiscoverGenre
        } else null
        discoverStatusMono.text = "${discoverGridAdapter.currentCount()} · PAGE +"

        discoverLoadJob = lifecycleScope.launch {
            val more = StremioApiClient.fetchCatalog(
                addon = entry.addon,
                catalog = entry.catalog,
                genre = serverGenre,
                skip = nextSkip
            )
            discoverIsLoading = false
            discoverSpinner.visibility = View.GONE

            if (more.isEmpty()) {
                discoverReachedEnd = true
                discoverStatusMono.text = "${discoverGridAdapter.currentCount()} TITLES"
                return@launch
            }

            val existingIds = discoverRawItems.mapTo(HashSet()) { it.id }
            val fresh = more.filter { existingIds.add(it.id) }
            if (fresh.isEmpty()) {
                discoverReachedEnd = true
                discoverStatusMono.text = "${discoverGridAdapter.currentCount()} TITLES"
                return@launch
            }
            discoverRawItems.addAll(fresh)

            val clientGenreOnly = !entry.catalog.isGenreSupported && !entry.catalog.isGenreRequired && selectedDiscoverGenre != null
            if (selectedDiscoverSort == DiscoverSortMode.DEFAULT && !clientGenreOnly) {
                // Fast incremental append so D-pad focus stays completely untouched during infinite scroll!
                discoverGridAdapter.append(fresh)
                discoverStatusMono.text = "${discoverGridAdapter.currentCount()} TITLES"
            } else {
                applyDiscoverClientFilterAndSort()
            }
        }
    }

    private fun applyDiscoverClientFilterAndSort() {
        val entry = selectedDiscoverEntry
        val clientGenre = if (entry != null && !entry.catalog.isGenreSupported && !entry.catalog.isGenreRequired) {
            selectedDiscoverGenre
        } else null

        var filtered: List<MetaItem> = if (!clientGenre.isNullOrBlank()) {
            discoverRawItems.filter { item ->
                item.genres.any { it.equals(clientGenre, ignoreCase = true) }
            }
        } else {
            discoverRawItems.toList()
        }

        filtered = when (selectedDiscoverSort) {
            DiscoverSortMode.DEFAULT -> filtered
            DiscoverSortMode.RATING_DESC -> filtered.sortedByDescending {
                it.imdbRating.toDoubleOrNull() ?: 0.0
            }
            DiscoverSortMode.YEAR_NEWEST -> filtered.sortedByDescending {
                Regex("""\d{4}""").find(it.releaseInfo)?.value?.toIntOrNull() ?: 0
            }
            DiscoverSortMode.YEAR_OLDEST -> filtered.sortedBy {
                Regex("""\d{4}""").find(it.releaseInfo)?.value?.toIntOrNull() ?: 9999
            }
            DiscoverSortMode.ALPHA_ASC -> filtered.sortedBy { it.name.lowercase() }
        }

        discoverGridAdapter.submit(filtered)
        discoverEmptyText.visibility = if (filtered.isEmpty() && !discoverIsLoading) View.VISIBLE else View.GONE
        discoverStatusMono.text = "${filtered.size} TITLES"
    }

    // =========================================================================
    // 2B. JELLYFIN TAB — STRICTLY SEPARATED MULTI-SERVER BROWSER & QUICKCONNECT
    // =========================================================================
    private fun createJellyfinTabView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 10),
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 12)
            )
        }

        // Row 1: Server Switcher Ribbon (strictly 1 server active at a time!)
        val serverPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 9),
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 9)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
            background = TvUiKit.createGlassPanelDrawable(this@MainActivity, radiusDp = 14f)
        }

        val serverScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 10)
            }
        }
        jfServerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        serverScroll.addView(jfServerRow)
        serverPanel.addView(serverScroll)

        jfSpinner = ProgressBar(this).apply {
            val sz = TvUiKit.dp(this@MainActivity, 16)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
            visibility = View.GONE
        }
        jfStatusMono = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoMedium(this@MainActivity)
            letterSpacing = 0.08f
            maxLines = 1
            text = "JELLYFIN"
        }
        serverPanel.addView(jfSpinner)
        serverPanel.addView(jfStatusMono)
        col.addView(serverPanel)

        // Row 2: Selected Server's Library Views & Filter Pills
        jfViewsFilterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
            visibility = View.GONE
        }
        jfViewsFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        jfViewsFilterScroll.addView(jfViewsFilterRow)
        col.addView(jfViewsFilterScroll)

        // Frosted-Glass Empty / Unauthenticated State Card
        jfEmptyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 24),
                TvUiKit.dp(this@MainActivity, 20),
                TvUiKit.dp(this@MainActivity, 24),
                TvUiKit.dp(this@MainActivity, 20)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = TvUiKit.dp(this@MainActivity, 8) }
            background = TvUiKit.createGlassPanelDrawable(this@MainActivity, radiusDp = 18f)
            visibility = View.GONE
        }
        jfEmptyCard.addView(TvUiKit.eyebrow(this, "JELLYFIN / MULTI-SERVER DIRECT PLAY"))
        jfEmptyTitle = TextView(this).apply {
            text = "Connect your private Jellyfin servers"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = XirtusFonts.semiBold(this@MainActivity)
            setPadding(0, TvUiKit.dp(this@MainActivity, 4), 0, TvUiKit.dp(this@MainActivity, 6))
        }
        jfEmptyBody = TextView(this).apply {
            text = "Discover LAN servers automatically over UDP 7359, pair in seconds via 6-digit QuickConnect, or enter any local IP / cloud HTTPS URL. Multiple servers stay strictly isolated."
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.regular(this@MainActivity)
            setPadding(0, 0, 0, TvUiKit.dp(this@MainActivity, 14))
        }
        jfEmptyPrimaryBtn = TvUiKit.createPillButton(
            context = this,
            text = "+ Add Jellyfin Server (LAN Scan / QuickConnect / URL)",
            selected = true
        ) {
            showAddJellyfinServerDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        jfEmptyCard.addView(jfEmptyTitle)
        jfEmptyCard.addView(jfEmptyBody)
        jfEmptyCard.addView(jfEmptyPrimaryBtn)
        col.addView(jfEmptyCard)

        // 6-Column Poster Gallery Grid reusing sharedDiscoverPool
        jfGridAdapter = DiscoverGridAdapter { item ->
            DetailActivity.start(this, item.id, item.type, item.name, item.poster)
        }
        val gridLm = GridLayoutManager(this, 6)
        jfGridRv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = gridLm
            setRecycledViewPool(sharedDiscoverPool)
            itemAnimator = null
            clipToPadding = false
            adapter = jfGridAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val total = gridLm.itemCount
                    val lastVis = gridLm.findLastVisibleItemPosition()
                    if (total > 0 && lastVis >= total - 12) {
                        maybeLoadNextJellyfinViewPage()
                    }
                }
            })
        }
        col.addView(jfGridRv)
        return col
    }

    private fun getActiveJellyfinServer(): JellyfinServerProfile? {
        val servers = AppPrefs.getJellyfinServers(this).filter { it.enabled }
        if (servers.isEmpty()) return null
        val curId = activeJfServerId
        return servers.firstOrNull { it.serverId == curId } ?: servers.first().also {
            activeJfServerId = it.serverId
        }
    }

    private fun reloadJellyfinServersState(triggerNetwork: Boolean) {
        if (!::jfServerRow.isInitialized) return
        val servers = AppPrefs.getJellyfinServers(this).filter { it.enabled }
        if (servers.isEmpty()) {
            jfLoadJob?.cancel()
            jfIsLoading = false
            activeJfServerId = null
            jfViewsForActiveServer = emptyList()
            jfGridAdapter.submit(emptyList())
            jfSpinner.visibility = View.GONE
            jfStatusMono.text = "0 SERVERS"
            rebuildJellyfinServerRibbon(servers)
            jfViewsFilterScroll.visibility = View.GONE
            showJellyfinEmptyState(
                title = "Connect your private Jellyfin servers",
                body = "Discover LAN servers automatically over UDP 7359, pair in seconds via 6-digit QuickConnect, or enter any local IP / cloud HTTPS URL. Multiple servers stay strictly isolated.",
                btnText = "+ Add Jellyfin Server (LAN Scan / QuickConnect / URL)"
            ) {
                showAddJellyfinServerDialog()
            }
            return
        }

        val prevId = activeJfServerId
        val activeSrv = servers.firstOrNull { it.serverId == prevId } ?: servers.first()
        val serverChanged = activeSrv.serverId != prevId
        activeJfServerId = activeSrv.serverId
        if (serverChanged) {
            jfViewsForActiveServer = emptyList()
            selectedJfViewId = JF_VIEW_OVERVIEW
            selectedJfGenre = null
            selectedJfYear = null
            selectedJfSort = JfSortOption.NAME_ASC
            jfSearchQuery = ""
            jfNextStartIndex = 0
            jfTotalRecords = 0
            jfGridAdapter.submit(emptyList())
        }

        rebuildJellyfinServerRibbon(servers)
        rebuildJellyfinViewsAndFiltersRibbon(activeSrv)

        if (!activeSrv.isAuthenticated) {
            jfLoadJob?.cancel()
            jfIsLoading = false
            jfSpinner.visibility = View.GONE
            jfGridAdapter.submit(emptyList())
            jfViewsFilterScroll.visibility = View.GONE
            jfStatusMono.text = "${activeSrv.name.uppercase(Locale.US)} · SIGN IN REQUIRED"
            showJellyfinEmptyState(
                title = "Sign in to ${activeSrv.name}",
                body = "Endpoint: ${activeSrv.primaryUrl}\nAuthenticate this TV via QuickConnect 6-digit code or username & password.",
                btnText = "Authenticate ${activeSrv.name}"
            ) {
                probeAndAuthenticateJellyfinUrl(activeSrv.primaryUrl, existingServer = activeSrv)
            }
            return
        }

        jfEmptyCard.visibility = View.GONE
        if (triggerNetwork || serverChanged) {
            loadActiveJellyfinServerViewsAndContent(activeSrv)
        }
    }

    private fun showJellyfinEmptyState(
        title: String,
        body: String,
        btnText: String,
        action: () -> Unit
    ) {
        jfEmptyTitle.text = title
        jfEmptyBody.text = body
        jfEmptyPrimaryBtn.text = btnText
        jfEmptyPrimaryBtn.setOnClickListener { action() }
        jfEmptyCard.visibility = View.VISIBLE
    }

    private fun selectJellyfinServer(serverId: String) {
        if (activeJfServerId == serverId && jfGridAdapter.currentCount() > 0) return
        jfLoadJob?.cancel()
        jfIsLoading = false
        activeJfServerId = serverId
        jfViewsForActiveServer = emptyList()
        selectedJfViewId = JF_VIEW_OVERVIEW
        selectedJfGenre = null
        selectedJfYear = null
        selectedJfSort = JfSortOption.NAME_ASC
        jfSearchQuery = ""
        jfNextStartIndex = 0
        jfTotalRecords = 0
        jfGridAdapter.submit(emptyList())

        val servers = AppPrefs.getJellyfinServers(this).filter { it.enabled }
        val srv = servers.firstOrNull { it.serverId == serverId } ?: return
        rebuildJellyfinServerRibbon(servers)
        rebuildJellyfinViewsAndFiltersRibbon(srv)

        if (!srv.isAuthenticated) {
            reloadJellyfinServersState(triggerNetwork = false)
        } else {
            jfEmptyCard.visibility = View.GONE
            loadActiveJellyfinServerViewsAndContent(srv)
        }
    }

    private fun rebuildJellyfinServerRibbon(servers: List<JellyfinServerProfile>) {
        jfServerRow.removeAllViews()
        val curId = activeJfServerId
        for (srv in servers) {
            val isSel = srv.serverId == curId
            val authSuffix = if (!srv.isAuthenticated) " (!)" else ""
            val label = if (isSel) "● ${srv.name}$authSuffix" else "${srv.name}$authSuffix"
            val pill = TvUiKit.createPillButton(this, label, selected = isSel, compact = true) {
                selectJellyfinServer(srv.serverId)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            jfServerRow.addView(pill)
        }

        val activeSrv = servers.firstOrNull { it.serverId == curId }
        if (activeSrv != null) {
            val settingsPill = TvUiKit.createPillButton(this, "Server Settings ⚙", compact = true) {
                showJellyfinServerSettingsDialog(activeSrv)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            jfServerRow.addView(settingsPill)
        }

        val addPill = TvUiKit.createPillButton(
            context = this,
            text = "+ Add Server",
            selected = servers.isEmpty(),
            compact = true
        ) {
            showAddJellyfinServerDialog()
        }
        jfServerRow.addView(addPill)
    }

    private fun rebuildJellyfinViewsAndFiltersRibbon(activeSrv: JellyfinServerProfile) {
        jfViewsFilterRow.removeAllViews()
        if (!activeSrv.isAuthenticated) {
            jfViewsFilterScroll.visibility = View.GONE
            return
        }
        jfViewsFilterScroll.visibility = View.VISIBLE

        val isOverview = selectedJfViewId == JF_VIEW_OVERVIEW && jfSearchQuery.isBlank()
        val overviewPill = TvUiKit.createPillButton(this, "Overview", selected = isOverview, compact = true) {
            if (selectedJfViewId != JF_VIEW_OVERVIEW || jfSearchQuery.isNotBlank()) {
                selectedJfViewId = JF_VIEW_OVERVIEW
                selectedJfGenre = null
                selectedJfYear = null
                jfSearchQuery = ""
                rebuildJellyfinViewsAndFiltersRibbon(activeSrv)
                loadActiveJellyfinServerContent(activeSrv)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
        }
        jfViewsFilterRow.addView(overviewPill)

        for (view in jfViewsForActiveServer) {
            val isViewSel = selectedJfViewId == view.Id && jfSearchQuery.isBlank()
            val vPill = TvUiKit.createPillButton(this, view.Name, selected = isViewSel, compact = true) {
                if (selectedJfViewId != view.Id || jfSearchQuery.isNotBlank()) {
                    selectedJfViewId = view.Id
                    selectedJfGenre = null
                    selectedJfYear = null
                    jfSearchQuery = ""
                    rebuildJellyfinViewsAndFiltersRibbon(activeSrv)
                    loadActiveJellyfinServerContent(activeSrv)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            jfViewsFilterRow.addView(vPill)
        }

        // Show Genre / Year / Sort pills when browsing a specific library view
        if (selectedJfViewId != JF_VIEW_OVERVIEW && jfSearchQuery.isBlank()) {
            val genrePill = TvUiKit.createPillButton(
                context = this,
                text = "Genre: ${selectedJfGenre ?: "All"} ▾",
                selected = selectedJfGenre != null,
                compact = true
            ) {
                showJellyfinGenreSelector(activeSrv, selectedJfViewId)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }

            val yearPill = TvUiKit.createPillButton(
                context = this,
                text = "Year: ${selectedJfYear ?: "All"} ▾",
                selected = selectedJfYear != null,
                compact = true
            ) {
                showJellyfinYearSelector(activeSrv, selectedJfViewId)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }

            val sortPill = TvUiKit.createPillButton(
                context = this,
                text = "Sort: ${selectedJfSort.shortLabel} ▾",
                selected = selectedJfSort != JfSortOption.NAME_ASC,
                compact = true
            ) {
                showJellyfinSortSelector(activeSrv)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }

            jfViewsFilterRow.addView(genrePill)
            jfViewsFilterRow.addView(yearPill)
            jfViewsFilterRow.addView(sortPill)
        }

        val searchLabel = if (jfSearchQuery.isBlank()) {
            "Search Server 🔍"
        } else {
            "Search: \"$jfSearchQuery\" ✕"
        }
        val searchPill = TvUiKit.createPillButton(
            context = this,
            text = searchLabel,
            selected = jfSearchQuery.isNotBlank(),
            compact = true
        ) {
            if (jfSearchQuery.isNotBlank()) {
                TvUiKit.showSelectDialog(
                    context = this,
                    title = "Search on ${activeSrv.name}",
                    options = listOf("New Search Query...", "Clear Search"),
                    selectedIndex = 0,
                    eyebrowLabel = "JELLYFIN / ${activeSrv.name.uppercase(Locale.US)}"
                ) { idx ->
                    when (idx) {
                        0 -> promptJellyfinServerSearch(activeSrv)
                        1 -> {
                            jfSearchQuery = ""
                            rebuildJellyfinViewsAndFiltersRibbon(activeSrv)
                            loadActiveJellyfinServerContent(activeSrv)
                        }
                    }
                }
            } else {
                promptJellyfinServerSearch(activeSrv)
            }
        }
        jfViewsFilterRow.addView(searchPill)
    }

    private fun promptJellyfinServerSearch(srv: JellyfinServerProfile) {
        TvUiKit.showInputDialog(
            context = this,
            title = "Search ${srv.name}",
            initialValue = jfSearchQuery,
            hint = "Movie, series, or episode title on ${srv.name}...",
            onSubmit = { q ->
                jfSearchQuery = q.trim()
                rebuildJellyfinViewsAndFiltersRibbon(srv)
                loadActiveJellyfinServerContent(srv)
            }
        )
    }

    private fun showJellyfinGenreSelector(srv: JellyfinServerProfile, viewId: String) {
        lifecycleScope.launch {
            val genres = JellyfinApiClient.getGenres(this@MainActivity, srv, viewId)
            val options = listOf("All") + genres
            val curIdx = if (selectedJfGenre == null) 0 else options.indexOf(selectedJfGenre).coerceAtLeast(0)
            TvUiKit.showSelectDialog(
                context = this@MainActivity,
                title = "Filter by Genre (${srv.name})",
                options = options,
                selectedIndex = curIdx,
                eyebrowLabel = "JELLYFIN / GENRE"
            ) { idx ->
                if (idx in options.indices) {
                    selectedJfGenre = if (idx == 0) null else options[idx]
                    rebuildJellyfinViewsAndFiltersRibbon(srv)
                    loadActiveJellyfinServerContent(srv)
                }
            }
        }
    }

    private fun showJellyfinYearSelector(srv: JellyfinServerProfile, viewId: String) {
        lifecycleScope.launch {
            val years = JellyfinApiClient.getYears(this@MainActivity, srv, viewId)
            val options = listOf("All") + years
            val curIdx = if (selectedJfYear == null) 0 else options.indexOf(selectedJfYear).coerceAtLeast(0)
            TvUiKit.showSelectDialog(
                context = this@MainActivity,
                title = "Filter by Year (${srv.name})",
                options = options,
                selectedIndex = curIdx,
                eyebrowLabel = "JELLYFIN / YEAR"
            ) { idx ->
                if (idx in options.indices) {
                    selectedJfYear = if (idx == 0) null else options[idx]
                    rebuildJellyfinViewsAndFiltersRibbon(srv)
                    loadActiveJellyfinServerContent(srv)
                }
            }
        }
    }

    private fun showJellyfinSortSelector(srv: JellyfinServerProfile) {
        val opts = JfSortOption.entries
        val curIdx = opts.indexOf(selectedJfSort).coerceAtLeast(0)
        TvUiKit.showSelectDialog(
            context = this,
            title = "Sort Library (${srv.name})",
            options = opts.map { it.label },
            selectedIndex = curIdx,
            eyebrowLabel = "JELLYFIN / SORT"
        ) { idx ->
            if (idx in opts.indices) {
                selectedJfSort = opts[idx]
                rebuildJellyfinViewsAndFiltersRibbon(srv)
                loadActiveJellyfinServerContent(srv)
            }
        }
    }

    private fun loadActiveJellyfinServerViewsAndContent(srv: JellyfinServerProfile) {
        jfLoadJob?.cancel()
        jfIsLoading = true
        jfSpinner.visibility = View.VISIBLE
        jfEmptyCard.visibility = View.GONE
        jfStatusMono.text = "${srv.name.uppercase(Locale.US)} · CONNECTING..."

        jfLoadJob = lifecycleScope.launch {
            val views = JellyfinApiClient.getViews(this@MainActivity, srv)
            if (activeJfServerId != srv.serverId) return@launch
            jfViewsForActiveServer = views
            if (selectedJfViewId != JF_VIEW_OVERVIEW && views.none { it.Id == selectedJfViewId }) {
                selectedJfViewId = JF_VIEW_OVERVIEW
            }
            rebuildJellyfinViewsAndFiltersRibbon(srv)
            fetchActiveJellyfinItemsInternal(srv)
        }
    }

    private fun loadActiveJellyfinServerContent(srv: JellyfinServerProfile) {
        jfLoadJob?.cancel()
        jfIsLoading = true
        jfSpinner.visibility = View.VISIBLE
        jfEmptyCard.visibility = View.GONE
        jfNextStartIndex = 0
        jfTotalRecords = 0
        jfGridAdapter.submit(emptyList())
        jfStatusMono.text = "${srv.name.uppercase(Locale.US)} · LOADING..."

        jfLoadJob = lifecycleScope.launch {
            fetchActiveJellyfinItemsInternal(srv)
        }
    }

    private suspend fun fetchActiveJellyfinItemsInternal(srv: JellyfinServerProfile) {
        val ep = JellyfinEndpointRouter.resolveActiveEndpoint(srv)
        val baseUrl = ep.baseUrl
        val routeTag = if (ep.isLan) "LAN" else "REMOTE"

        if (baseUrl.isBlank()) {
            jfIsLoading = false
            jfSpinner.visibility = View.GONE
            jfStatusMono.text = "${srv.name.uppercase(Locale.US)} · OFFLINE"
            showJellyfinEmptyState(
                title = "Unable to reach ${srv.name}",
                body = "Check your local IP or remote HTTPS URL in Server Settings.",
                btnText = "Server Settings ⚙"
            ) {
                showJellyfinServerSettingsDialog(srv)
            }
            return
        }

        val metas: List<MetaItem> = when {
            jfSearchQuery.isNotBlank() -> {
                val hits = JellyfinApiClient.searchServer(this@MainActivity, srv, jfSearchQuery, limit = 36)
                jfNextStartIndex = hits.size
                jfTotalRecords = hits.size
                hits.map { it.toMetaItem(srv.serverId, baseUrl) }
            }
            selectedJfViewId == JF_VIEW_OVERVIEW -> {
                val combined = coroutineScope {
                    val resumeDef = async { JellyfinApiClient.getResumeItems(this@MainActivity, srv, limit = 12) }
                    val nextUpDef = async { JellyfinApiClient.getNextUp(this@MainActivity, srv, limit = 16) }
                    val latestDefs = jfViewsForActiveServer.take(5).map { v ->
                        async { JellyfinApiClient.getLatestInView(this@MainActivity, srv, v.Id, limit = 14) }
                    }
                    buildList<JfItem> {
                        addAll(resumeDef.await())
                        addAll(nextUpDef.await())
                        latestDefs.awaitAll().forEach { addAll(it) }
                    }
                }
                val seen = HashSet<String>()
                val deduped = combined.filter { it.Id.isNotBlank() && seen.add(it.Id) }
                jfNextStartIndex = deduped.size
                jfTotalRecords = deduped.size
                deduped.map { it.toMetaItem(srv.serverId, baseUrl) }
            }
            else -> {
                val viewObj = jfViewsForActiveServer.firstOrNull { it.Id == selectedJfViewId }
                val (items, total) = JellyfinApiClient.getLibraryItems(
                    context = this@MainActivity,
                    profile = srv,
                    viewId = selectedJfViewId,
                    includeItemTypes = viewObj?.defaultIncludeItemTypes(),
                    sortBy = selectedJfSort.sortBy,
                    sortOrder = selectedJfSort.sortOrder,
                    genre = selectedJfGenre,
                    year = selectedJfYear,
                    startIndex = 0,
                    limit = JF_PAGE_SIZE
                )
                jfNextStartIndex = items.size
                jfTotalRecords = total
                items.map { it.toMetaItem(srv.serverId, baseUrl) }
            }
        }

        if (activeJfServerId != srv.serverId) return
        jfIsLoading = false
        jfSpinner.visibility = View.GONE
        jfGridAdapter.submit(metas)

        val countLabel = if (selectedJfViewId != JF_VIEW_OVERVIEW && jfSearchQuery.isBlank() && jfTotalRecords > metas.size) {
            "${metas.size}/${jfTotalRecords}"
        } else {
            "${metas.size}"
        }
        jfStatusMono.text = "${srv.name.uppercase(Locale.US)} ($routeTag) · $countLabel TITLES"

        if (metas.isEmpty()) {
            showJellyfinEmptyState(
                title = "No titles matched on ${srv.name}",
                body = if (jfSearchQuery.isNotBlank()) {
                    "No matches found for \"$jfSearchQuery\" on ${srv.name}."
                } else {
                    "This library or filter returned 0 titles on ${srv.name}."
                },
                btnText = "Back to Overview"
            ) {
                selectedJfViewId = JF_VIEW_OVERVIEW
                selectedJfGenre = null
                selectedJfYear = null
                jfSearchQuery = ""
                rebuildJellyfinViewsAndFiltersRibbon(srv)
                loadActiveJellyfinServerContent(srv)
            }
        } else {
            jfEmptyCard.visibility = View.GONE
        }
    }

    private fun maybeLoadNextJellyfinViewPage() {
        val srv = getActiveJellyfinServer() ?: return
        if (!srv.isAuthenticated || jfIsLoading) return
        if (selectedJfViewId == JF_VIEW_OVERVIEW || jfSearchQuery.isNotBlank()) return
        if (jfNextStartIndex <= 0 || jfNextStartIndex >= jfTotalRecords) return

        jfIsLoading = true
        jfSpinner.visibility = View.VISIBLE
        val startIdx = jfNextStartIndex
        val viewObj = jfViewsForActiveServer.firstOrNull { it.Id == selectedJfViewId }

        jfLoadJob = lifecycleScope.launch {
            val baseUrl = JellyfinEndpointRouter.resolveBaseUrl(srv)
            val (more, total) = JellyfinApiClient.getLibraryItems(
                context = this@MainActivity,
                profile = srv,
                viewId = selectedJfViewId,
                includeItemTypes = viewObj?.defaultIncludeItemTypes(),
                sortBy = selectedJfSort.sortBy,
                sortOrder = selectedJfSort.sortOrder,
                genre = selectedJfGenre,
                year = selectedJfYear,
                startIndex = startIdx,
                limit = JF_PAGE_SIZE
            )
            if (activeJfServerId != srv.serverId) return@launch
            jfIsLoading = false
            jfSpinner.visibility = View.GONE

            if (more.isEmpty()) {
                jfTotalRecords = jfNextStartIndex
                return@launch
            }
            jfNextStartIndex += more.size
            jfTotalRecords = total.coerceAtLeast(jfNextStartIndex)
            val mapped = more.map { it.toMetaItem(srv.serverId, baseUrl) }
            jfGridAdapter.append(mapped)
            jfStatusMono.text = "${srv.name.uppercase(Locale.US)} · ${jfGridAdapter.currentCount()}/$jfTotalRecords TITLES"
        }
    }

    private fun showAddJellyfinServerDialog() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Scanning LAN for Jellyfin servers (UDP 7359)...", Toast.LENGTH_SHORT).show()
            val discovered = JellyfinEndpointRouter.discoverLanServers(timeoutMs = 900L)
            val manualLabel = "Enter Server URL Manually (Local IP or Cloud HTTPS)"
            val options = discovered.map { s ->
                "⚡ ${s.Name.ifBlank { "Jellyfin Server" }} (${s.Address})"
            } + manualLabel

            TvUiKit.showSelectDialog(
                context = this@MainActivity,
                title = "Add Jellyfin Server",
                options = options,
                selectedIndex = 0,
                eyebrowLabel = "JELLYFIN / LAN DISCOVERY & URL"
            ) { idx ->
                if (idx !in options.indices) return@showSelectDialog
                if (idx < discovered.size) {
                    val chosen = discovered[idx]
                    probeAndAuthenticateJellyfinUrl(chosen.Address, existingServer = null)
                } else {
                    TvUiKit.showInputDialog(
                        context = this@MainActivity,
                        title = "Jellyfin Server URL",
                        hint = "http://192.168.1.50:8096 or https://jf.example.com",
                        onSubmit = { rawUrl ->
                            if (rawUrl.isNotBlank()) {
                                probeAndAuthenticateJellyfinUrl(rawUrl, existingServer = null)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun probeAndAuthenticateJellyfinUrl(
        rawUrl: String,
        existingServer: JellyfinServerProfile? = null
    ) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Probing Jellyfin server...", Toast.LENGTH_SHORT).show()
            val preferInsecure = existingServer?.allowInsecureTls == true
            val strictProbe = JellyfinApiClient.probeServerPublicInfo(rawUrl, allowInsecureTls = preferInsecure)
            if (strictProbe != null) {
                chooseJellyfinAuthMethod(
                    serverInfo = strictProbe,
                    allowInsecureTls = preferInsecure,
                    existingServer = existingServer
                )
                return@launch
            }

            // Try permissive TLS probe in case of private/self-signed homelab HTTPS certificate
            val insecureProbe = if (!preferInsecure) {
                JellyfinApiClient.probeServerPublicInfo(rawUrl, allowInsecureTls = true)
            } else null

            if (insecureProbe != null) {
                TvUiKit.showConfirmDialog(
                    context = this@MainActivity,
                    title = "Allow Self-Signed HTTPS?",
                    message = "\"${insecureProbe.ServerName.ifBlank { insecureProbe.resolvedBaseUrl }}\" uses a private or self-signed TLS certificate. Trust this server?",
                    yesText = "Trust & Continue"
                ) { yes ->
                    if (yes) {
                        lifecycleScope.launch {
                            chooseJellyfinAuthMethod(
                                serverInfo = insecureProbe,
                                allowInsecureTls = true,
                                existingServer = existingServer
                            )
                        }
                    }
                }
                return@launch
            }

            Toast.makeText(
                this@MainActivity,
                "Could not reach Jellyfin at $rawUrl",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun chooseJellyfinAuthMethod(
        serverInfo: app.brootlite.tv.jellyfin.JfPublicSystemInfo,
        allowInsecureTls: Boolean,
        existingServer: JellyfinServerProfile?
    ) {
        val baseUrl = serverInfo.resolvedBaseUrl
        val serverName = existingServer?.name?.takeIf { it.isNotBlank() }
            ?: serverInfo.ServerName.ifBlank { "Jellyfin" }
        val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(this)
        val qcEnabled = JellyfinApiClient.isQuickConnectEnabled(baseUrl, deviceId, allowInsecureTls)

        val authOptions = buildList {
            if (qcEnabled) add("QuickConnect 6-Digit Code (Recommended)")
            add("Username & Password")
        }

        TvUiKit.showSelectDialog(
            context = this,
            title = "Sign In to $serverName",
            options = authOptions,
            selectedIndex = 0,
            eyebrowLabel = "JELLYFIN / AUTHENTICATION"
        ) { idx ->
            if (idx !in authOptions.indices) return@showSelectDialog
            if (qcEnabled && idx == 0) {
                startJellyfinQuickConnectFlow(serverInfo, serverName, allowInsecureTls, existingServer)
            } else {
                promptJellyfinPasswordAuth(serverInfo, serverName, allowInsecureTls, existingServer)
            }
        }
    }

    private fun startJellyfinQuickConnectFlow(
        serverInfo: app.brootlite.tv.jellyfin.JfPublicSystemInfo,
        serverName: String,
        allowInsecureTls: Boolean,
        existingServer: JellyfinServerProfile?
    ) {
        jfAuthPollJob?.cancel()
        val baseUrl = serverInfo.resolvedBaseUrl
        val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(this)

        lifecycleScope.launch {
            val qcState = JellyfinApiClient.initiateQuickConnect(baseUrl, deviceId, allowInsecureTls)
            if (qcState == null) {
                Toast.makeText(
                    this@MainActivity,
                    "QuickConnect unavailable on $serverName · Falling back to Username & Password",
                    Toast.LENGTH_SHORT
                ).show()
                promptJellyfinPasswordAuth(serverInfo, serverName, allowInsecureTls, existingServer)
                return@launch
            }

            val qcWebUrl = "$baseUrl/web/#/quickconnect"
            var modal: AlertDialog? = null
            modal = TvUiKit.showQrModal(
                context = this@MainActivity,
                code = qcState.Code,
                linkUrl = "$qcWebUrl (Settings -> Quick Connect)",
                qrTargetUrl = qcWebUrl,
                eyebrowLabel = "JELLYFIN / QUICKCONNECT",
                title = "Authorize $serverName"
            ) {
                jfAuthPollJob?.cancel()
            }

            jfAuthPollJob = lifecycleScope.launch {
                val deadline = System.currentTimeMillis() + 5 * 60_000L
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(2_500L)
                    val auth = JellyfinApiClient.pollQuickConnect(
                        baseUrl = baseUrl,
                        secret = qcState.Secret,
                        deviceId = deviceId,
                        allowInsecureTls = allowInsecureTls
                    ) ?: continue

                    modal?.dismiss()
                    persistAuthenticatedJellyfinServer(
                        serverInfo = serverInfo,
                        serverName = serverName,
                        auth = auth,
                        allowInsecureTls = allowInsecureTls,
                        existingServer = existingServer
                    )
                    return@launch
                }
                modal?.dismiss()
                Toast.makeText(this@MainActivity, "QuickConnect code expired", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptJellyfinPasswordAuth(
        serverInfo: app.brootlite.tv.jellyfin.JfPublicSystemInfo,
        serverName: String,
        allowInsecureTls: Boolean,
        existingServer: JellyfinServerProfile?
    ) {
        val baseUrl = serverInfo.resolvedBaseUrl
        val deviceId = AppPrefs.getOrCreateJellyfinDeviceId(this)

        TvUiKit.showInputDialog(
            context = this,
            title = "$serverName · Username",
            initialValue = existingServer?.username.orEmpty(),
            hint = "Jellyfin username...",
            onSubmit = { username ->
                if (username.isBlank()) return@showInputDialog
                TvUiKit.showInputDialog(
                    context = this,
                    title = "$serverName · Password",
                    hint = "Password (never stored on disk — exchanged for token)",
                    hidden = true,
                    onSubmit = { password ->
                        lifecycleScope.launch {
                            val auth = JellyfinApiClient.authenticateByName(
                                baseUrl = baseUrl,
                                username = username,
                                password = password,
                                deviceId = deviceId,
                                allowInsecureTls = allowInsecureTls
                            )
                            if (auth == null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Authentication failed on $serverName",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }
                            persistAuthenticatedJellyfinServer(
                                serverInfo = serverInfo,
                                serverName = serverName,
                                auth = auth,
                                allowInsecureTls = allowInsecureTls,
                                existingServer = existingServer
                            )
                        }
                    }
                )
            }
        )
    }

    private fun persistAuthenticatedJellyfinServer(
        serverInfo: app.brootlite.tv.jellyfin.JfPublicSystemInfo,
        serverName: String,
        auth: app.brootlite.tv.jellyfin.JfAuthResult,
        allowInsecureTls: Boolean,
        existingServer: JellyfinServerProfile?
    ) {
        val baseUrl = serverInfo.resolvedBaseUrl
        val serverId = auth.ServerId.ifBlank { serverInfo.Id }.ifBlank { existingServer?.serverId.orEmpty() }
        val isLan = JellyfinEndpointRouter.isLikelyLanUrl(baseUrl)
        val prior = existingServer ?: AppPrefs.getJellyfinServer(this, serverId)

        val profile = JellyfinServerProfile(
            serverId = serverId,
            name = serverName.ifBlank { prior?.name.orEmpty() }.ifBlank { "Jellyfin" },
            primaryUrl = baseUrl,
            localUrl = if (isLan) baseUrl else prior?.localUrl.orEmpty(),
            remoteUrl = if (!isLan) baseUrl else prior?.remoteUrl.orEmpty(),
            userId = auth.User.Id,
            username = auth.User.Name,
            accessToken = auth.AccessToken,
            bootstrapToken = auth.AccessToken,
            allowInsecureTls = allowInsecureTls,
            maxBitrateMbps = prior?.maxBitrateMbps ?: 0,
            enabled = true,
            updatedAtMs = System.currentTimeMillis()
        )

        AppPrefs.upsertJellyfinServer(this, profile)
        JellyfinEndpointRouter.invalidate(serverId)
        activeJfServerId = serverId
        selectedJfViewId = JF_VIEW_OVERVIEW
        selectedJfGenre = null
        selectedJfYear = null
        jfSearchQuery = ""

        Toast.makeText(
            this,
            "Connected ${profile.name} as ${auth.User.Name}",
            Toast.LENGTH_SHORT
        ).show()

        switchTab(NavTab.JELLYFIN)
        reloadJellyfinServersState(triggerNetwork = true)
        refreshContinueWatchingRow()
        refreshAccountIndicators()
    }

    private fun showJellyfinServerSettingsDialog(srv: JellyfinServerProfile) {
        val fresh = AppPrefs.getJellyfinServer(this, srv.serverId) ?: srv
        val options = listOf(
            "Rename Server Label (${fresh.name})",
            "Set Local LAN URL (${fresh.localUrl.ifBlank { "None" }})",
            "Set Remote / Cloud URL (${fresh.remoteUrl.ifBlank { "None" }})",
            "Max Bitrate Cap: ${fresh.maxBitrateLabel} (Cycle)",
            "Allow Self-Signed HTTPS: ${if (fresh.allowInsecureTls) "ON" else "OFF"}",
            "Re-Authenticate (${fresh.username.ifBlank { "Sign In" }})",
            "Remove Server (${fresh.name})"
        )

        TvUiKit.showSelectDialog(
            context = this,
            title = "${fresh.name} · Server Settings",
            options = options,
            selectedIndex = 0,
            eyebrowLabel = "JELLYFIN / SERVER CONFIGURATION"
        ) { idx ->
            when (idx) {
                0 -> {
                    TvUiKit.showInputDialog(
                        context = this,
                        title = "Server Display Label",
                        initialValue = fresh.name,
                        hint = "e.g. Home NAS, Seedbox, Anime Server...",
                        onSubmit = { newName ->
                            if (newName.isNotBlank()) {
                                AppPrefs.upsertJellyfinServer(
                                    this,
                                    fresh.copy(name = newName.trim(), updatedAtMs = System.currentTimeMillis())
                                )
                                reloadJellyfinServersState(triggerNetwork = false)
                            }
                        }
                    )
                }
                1 -> {
                    TvUiKit.showInputDialog(
                        context = this,
                        title = "${fresh.name} · Local LAN Endpoint",
                        initialValue = fresh.localUrl,
                        hint = "http://192.168.1.50:8096 (fast 900ms auto-probe when on home Wi-Fi)",
                        onSubmit = { rawLan ->
                            val cleanLan = JellyfinEndpointRouter.normalizeServerUrl(rawLan)
                            val newPrimary = if (fresh.primaryUrl.isBlank() && cleanLan.isNotBlank()) cleanLan else fresh.primaryUrl
                            AppPrefs.upsertJellyfinServer(
                                this,
                                fresh.copy(
                                    primaryUrl = newPrimary,
                                    localUrl = cleanLan,
                                    updatedAtMs = System.currentTimeMillis()
                                )
                            )
                            JellyfinEndpointRouter.invalidate(fresh.serverId)
                            reloadJellyfinServersState(triggerNetwork = true)
                        }
                    )
                }
                2 -> {
                    TvUiKit.showInputDialog(
                        context = this,
                        title = "${fresh.name} · Remote / Cloud Endpoint",
                        initialValue = fresh.remoteUrl,
                        hint = "https://jellyfin.example.com (automatic fallback away from LAN)",
                        onSubmit = { rawRemote ->
                            val cleanRemote = JellyfinEndpointRouter.normalizeServerUrl(rawRemote)
                            val newPrimary = if (fresh.primaryUrl.isBlank() && cleanRemote.isNotBlank()) cleanRemote else fresh.primaryUrl
                            AppPrefs.upsertJellyfinServer(
                                this,
                                fresh.copy(
                                    primaryUrl = newPrimary,
                                    remoteUrl = cleanRemote,
                                    updatedAtMs = System.currentTimeMillis()
                                )
                            )
                            JellyfinEndpointRouter.invalidate(fresh.serverId)
                            reloadJellyfinServersState(triggerNetwork = true)
                        }
                    )
                }
                3 -> {
                    val cycle = listOf(0, 80, 40, 20)
                    val curIdx = cycle.indexOf(fresh.maxBitrateMbps)
                    val nextCap = cycle[(curIdx + 1) % cycle.size]
                    val updated = fresh.copy(maxBitrateMbps = nextCap, updatedAtMs = System.currentTimeMillis())
                    AppPrefs.upsertJellyfinServer(this, updated)
                    Toast.makeText(this, "${updated.name} Bitrate: ${updated.maxBitrateLabel}", Toast.LENGTH_SHORT).show()
                    showJellyfinServerSettingsDialog(updated)
                }
                4 -> {
                    val updated = fresh.copy(
                        allowInsecureTls = !fresh.allowInsecureTls,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    AppPrefs.upsertJellyfinServer(this, updated)
                    JellyfinEndpointRouter.invalidate(fresh.serverId)
                    Toast.makeText(
                        this,
                        "Allow Self-Signed HTTPS: ${if (updated.allowInsecureTls) "ON" else "OFF"}",
                        Toast.LENGTH_SHORT
                    ).show()
                    reloadJellyfinServersState(triggerNetwork = true)
                }
                5 -> {
                    val targetUrl = fresh.primaryUrl.ifBlank { fresh.localUrl.ifBlank { fresh.remoteUrl } }
                    probeAndAuthenticateJellyfinUrl(targetUrl, existingServer = fresh)
                }
                6 -> {
                    TvUiKit.showConfirmDialog(
                        context = this,
                        title = "Remove ${fresh.name}?",
                        message = "Remove \"${fresh.name}\" from this TV? Server media is untouched.",
                        yesText = "Remove"
                    ) { yes ->
                        if (yes) {
                            AppPrefs.removeJellyfinServer(this, fresh.serverId)
                            JellyfinEndpointRouter.invalidate(fresh.serverId)
                            activeJfServerId = AppPrefs.getJellyfinServers(this)
                                .firstOrNull { it.enabled }?.serverId
                            reloadJellyfinServersState(triggerNetwork = true)
                            refreshContinueWatchingRow()
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 2C. CREATOR STREAMING TAB — YOUTUBE · TWITCH · ODYSEE · RUMBLE
    // =========================================================================
    private fun createStreamingTabView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 10),
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 12)
            )
        }

        // Row 1: Platform Switcher (YouTube · Twitch · Odysee · Rumble) + Status + Account/Follow Pill
        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 9),
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 9)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
            background = TvUiKit.createGlassPanelDrawable(this@MainActivity, radiusDp = 14f)
        }

        for (plat in StreamingPlatform.entries) {
            val pill = TvUiKit.createPillButton(
                context = this,
                text = plat.label,
                selected = (plat == activeStreamingPlatform),
                compact = true
            ) {
                switchStreamingPlatform(plat)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            streamingPlatformButtons[plat] = pill
            topPanel.addView(pill)
        }

        topPanel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        streamingSpinner = ProgressBar(this).apply {
            val sz = TvUiKit.dp(this@MainActivity, 16)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
            visibility = View.GONE
        }
        streamingStatusMono = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoMedium(this@MainActivity)
            letterSpacing = 0.08f
            maxLines = 1
            text = "YOUTUBE"
            setPadding(0, 0, TvUiKit.dp(this@MainActivity, 10), 0)
        }
        streamingAccountPill = TvUiKit.createPillButton(
            context = this,
            text = "Sign In (TV Code)",
            selected = false,
            compact = true
        ) {
            onStreamingAccountPillClicked()
        }

        topPanel.addView(streamingSpinner)
        topPanel.addView(streamingStatusMono)
        topPanel.addView(streamingAccountPill)
        col.addView(topPanel)

        // Row 2: Sub-Navigation / Drilldown / Search Ribbon
        val subScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
        }
        streamingSubNavRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        subScroll.addView(streamingSubNavRow)
        col.addView(subScroll)
        rebuildStreamingSubNavRow()

        streamingEmptyText = TextView(this).apply {
            text = "No videos found."
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            typeface = XirtusFonts.regular(this@MainActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(TvUiKit.dp(this@MainActivity, 6), TvUiKit.dp(this@MainActivity, 18), 0, 0)
            visibility = View.GONE
        }
        col.addView(streamingEmptyText)

        // 4-Column 16:9 StreamVideoGridAdapter attached to sharedVideoPool
        streamingGridAdapter = StreamVideoGridAdapter(
            onClick = { card -> onStreamCardClicked(card) },
            onLongClick = { card -> onStreamCardLongClicked(card) }
        )
        val gridLm = GridLayoutManager(this, 4)
        streamingGridRv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = gridLm
            setRecycledViewPool(sharedVideoPool)
            itemAnimator = null
            clipToPadding = false
            adapter = streamingGridAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val total = gridLm.itemCount
                    val lastVis = gridLm.findLastVisibleItemPosition()
                    if (total > 0 && lastVis >= total - 8) {
                        maybeLoadNextStreamingPage()
                    }
                }
            })
        }
        col.addView(streamingGridRv)
        return col
    }

    private fun buildYouTubeAuthContext(): YouTubeAuthContext {
        val oauth = AppPrefs.getYouTubeOAuth(this)?.takeIf { it.isActive }
        val deArrow = AppPrefs.isDeArrowEnabled(this)
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
                val cur = AppPrefs.getYouTubeOAuth(this@MainActivity) ?: oauth
                AppPrefs.saveYouTubeOAuth(
                    this@MainActivity,
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

    private fun buildTwitchAuthContext(): TwitchAuthContext? {
        val tw = AppPrefs.getTwitchDeviceOAuth(this)?.takeIf { it.isActive } ?: return null
        return TwitchAuthContext(
            accessToken = tw.accessToken.ifBlank { null },
            refreshToken = tw.refreshToken.ifBlank { null },
            clientId = tw.clientId.ifBlank { null },
            userId = tw.twitchUserId.ifBlank { null },
            userLogin = tw.login.ifBlank { null },
            onTokenRefreshed = { fresh ->
                val cur = AppPrefs.getTwitchDeviceOAuth(this@MainActivity) ?: tw
                AppPrefs.saveTwitchDeviceOAuth(
                    this@MainActivity,
                    cur.copy(
                        accessToken = fresh.accessToken,
                        refreshToken = fresh.refreshToken.ifBlank { cur.refreshToken },
                        clientId = fresh.clientId.ifBlank { cur.clientId },
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        )
    }

    private fun switchStreamingPlatform(platform: StreamingPlatform) {
        if (activeStreamingPlatform == platform && streamingDrillStack.isEmpty()) return
        streamingLoadJob?.cancel()
        streamingIsLoading = false
        activeStreamingPlatform = platform
        activeStreamingSection = platform.defaultSection
        streamingDrillStack.clear()
        syncStreamingBackHandler()
        streamingNextToken = null
        streamingReachedEnd = false

        streamingPlatformButtons.forEach { (p, btn) ->
            TvUiKit.setPillSelected(btn, p == platform)
        }
        refreshStreamingAccountPill()
        rebuildStreamingSubNavRow()
        reloadStreamingFeedFromScratch()
    }

    private fun refreshStreamingAccountPill() {
        if (!::streamingAccountPill.isInitialized) return
        when (activeStreamingPlatform) {
            StreamingPlatform.YOUTUBE -> {
                val yt = AppPrefs.getYouTubeOAuth(this)?.takeIf { it.isActive }
                if (yt != null) {
                    streamingAccountPill.text = "✓ ${yt.accountLabel.ifBlank { "YouTube" }.take(20)} ▾"
                    TvUiKit.setPillSelected(streamingAccountPill, true)
                } else {
                    streamingAccountPill.text = "Sign In (TV Code)"
                    TvUiKit.setPillSelected(streamingAccountPill, false)
                }
            }
            StreamingPlatform.TWITCH -> {
                val tw = AppPrefs.getTwitchDeviceOAuth(this)?.takeIf { it.isActive }
                if (tw != null) {
                    val tag = tw.login.ifBlank { tw.displayName }.ifBlank { "Twitch" }
                    streamingAccountPill.text = "✓ @${tag.removePrefix("@").take(18)} ▾"
                    TvUiKit.setPillSelected(streamingAccountPill, true)
                } else {
                    streamingAccountPill.text = "Sign In (Twitch QR)"
                    TvUiKit.setPillSelected(streamingAccountPill, false)
                }
            }
            StreamingPlatform.ODYSEE, StreamingPlatform.RUMBLE -> {
                val count = AppPrefs.getCreatorFollows(this)
                    .count { it.isActive && it.platform.equals(activeStreamingPlatform.key, ignoreCase = true) }
                streamingAccountPill.text = if (count > 0) {
                    "+ Follow ($count)"
                } else {
                    "+ Follow Channel"
                }
                TvUiKit.setPillSelected(streamingAccountPill, count > 0)
            }
        }
    }

    private fun sectionsForStreamingPlatform(platform: StreamingPlatform): List<Pair<String, String>> = when (platform) {
        StreamingPlatform.YOUTUBE -> listOf(
            "home" to "Home",
            "subscriptions" to "Subscriptions",
            "history" to "History",
            "playlists" to "Playlists & Liked",
            "search" to "Search"
        )
        StreamingPlatform.TWITCH -> listOf(
            "top_live" to "Top Live",
            "following" to "Following",
            "categories" to "Categories",
            "search" to "Search"
        )
        StreamingPlatform.ODYSEE -> listOf(
            "trending" to "Trending",
            "live" to "Live",
            "following" to "Following",
            "categories" to "Categories",
            "search" to "Search"
        )
        StreamingPlatform.RUMBLE -> listOf(
            "featured" to "Featured",
            "live" to "Live",
            "following" to "Following",
            "categories" to "Categories",
            "search" to "Search"
        )
    }

    private fun formatYtSearchFilterLabel(filter: YouTubeSearchFilter): String = when (filter) {
        YouTubeSearchFilter.ALL -> "All"
        YouTubeSearchFilter.VIDEOS -> "Videos"
        YouTubeSearchFilter.CHANNELS -> "Channels"
        YouTubeSearchFilter.PLAYLISTS -> "Playlists"
        YouTubeSearchFilter.LIVE -> "Live"
    }

    private fun rebuildStreamingSubNavRow() {
        if (!::streamingSubNavRow.isInitialized) return
        streamingSubNavRow.removeAllViews()

        val topDrill = streamingDrillStack.lastOrNull()
        if (topDrill != null) {
            val backBtn = TvUiKit.createPillButton(this, "← Back", selected = true, compact = true) {
                if (streamingDrillStack.isNotEmpty()) {
                    streamingDrillStack.removeLast()
                    syncStreamingBackHandler()
                    rebuildStreamingSubNavRow()
                    reloadStreamingFeedFromScratch()
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 8) }
            }
            streamingSubNavRow.addView(backBtn)

            val kindTag = topDrill.kind.replaceFirstChar { it.uppercase() }
            val crumbView = TextView(this).apply {
                text = "$kindTag · ${topDrill.title}"
                setTextColor(XirtusPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = XirtusFonts.semiBold(this@MainActivity)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(TvUiKit.dp(this@MainActivity, 4), 0, TvUiKit.dp(this@MainActivity, 10), 0)
            }
            streamingSubNavRow.addView(crumbView)

            if (topDrill.kind == "channel" && topDrill.id.isNotBlank()) {
                val isFollowed = AppPrefs.isCreatorFollowed(this, activeStreamingPlatform.key, topDrill.id) ||
                    (topDrill.isSubscribedRemote == true)
                val followLabel = if (isFollowed) "★ Following" else "☆ Follow"
                val followPill = TvUiKit.createPillButton(
                    context = this,
                    text = followLabel,
                    selected = isFollowed,
                    compact = true
                ) {
                    toggleStreamingChannelFollow(
                        platformKey = activeStreamingPlatform.key,
                        channelId = topDrill.id,
                        channelName = topDrill.title,
                        avatarUrl = topDrill.avatarUrl,
                        subscribeParams = topDrill.subscribeParams,
                        currentlyFollowed = isFollowed
                    )
                }
                streamingSubNavRow.addView(followPill)
            }
            return
        }

        val sections = sectionsForStreamingPlatform(activeStreamingPlatform)
        for ((secKey, secLabel) in sections) {
            val isSel = activeStreamingSection == secKey
            val pill = TvUiKit.createPillButton(this, secLabel, selected = isSel, compact = true) {
                val wasSearch = activeStreamingSection == "search"
                activeStreamingSection = secKey
                rebuildStreamingSubNavRow()
                if (secKey == "search" && (streamingSearchQuery.isBlank() || wasSearch)) {
                    promptStreamingSearchInput()
                } else {
                    reloadStreamingFeedFromScratch()
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            streamingSubNavRow.addView(pill)
        }

        if (activeStreamingSection == "search") {
            val qLabel = if (streamingSearchQuery.isBlank()) {
                "Enter Query..."
            } else {
                "\"${streamingSearchQuery.take(26)}\""
            }
            val queryPill = TvUiKit.createPillButton(
                context = this,
                text = qLabel,
                selected = streamingSearchQuery.isNotBlank(),
                compact = true
            ) {
                promptStreamingSearchInput()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            streamingSubNavRow.addView(queryPill)

            val micBtn = TvUiKit.createIconPillButton(this, MicIconDrawable(this), "Voice Search") {
                startVoiceSearch()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    TvUiKit.dp(this@MainActivity, 40),
                    TvUiKit.dp(this@MainActivity, 32)
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            streamingSubNavRow.addView(micBtn)

            if (activeStreamingPlatform == StreamingPlatform.YOUTUBE) {
                val filterPill = TvUiKit.createPillButton(
                    context = this,
                    text = "Filter: ${formatYtSearchFilterLabel(selectedYouTubeSearchFilter)} ▾",
                    selected = selectedYouTubeSearchFilter != YouTubeSearchFilter.ALL,
                    compact = true
                ) {
                    val filters = YouTubeSearchFilter.entries
                    val curIdx = filters.indexOf(selectedYouTubeSearchFilter).coerceAtLeast(0)
                    TvUiKit.showSelectDialog(
                        context = this,
                        title = "YouTube Search Filter",
                        options = filters.map { formatYtSearchFilterLabel(it) },
                        selectedIndex = curIdx,
                        eyebrowLabel = "YOUTUBE / SEARCH FILTER"
                    ) { idx ->
                        if (idx in filters.indices) {
                            selectedYouTubeSearchFilter = filters[idx]
                            rebuildStreamingSubNavRow()
                            if (streamingSearchQuery.isNotBlank()) {
                                reloadStreamingFeedFromScratch()
                            }
                        }
                    }
                }
                streamingSubNavRow.addView(filterPill)
            }
        }
    }

    private fun promptStreamingSearchInput() {
        val platName = activeStreamingPlatform.label
        TvUiKit.showInputDialog(
            context = this,
            title = "Search $platName",
            initialValue = streamingSearchQuery,
            hint = "Search videos, live streams, or channels on $platName...",
            onSubmit = { q ->
                streamingSearchQuery = q.trim()
                rebuildStreamingSubNavRow()
                if (streamingSearchQuery.isNotBlank()) {
                    reloadStreamingFeedFromScratch()
                }
            }
        )
    }

    private fun toggleStreamingChannelFollow(
        platformKey: String,
        channelId: String,
        channelName: String,
        avatarUrl: String = "",
        subscribeParams: String? = null,
        currentlyFollowed: Boolean? = null
    ) {
        if (channelId.isBlank()) return
        val wasFollowed = currentlyFollowed ?: AppPrefs.isCreatorFollowed(this, platformKey, channelId)
        val entry = CreatorFollowEntry(
            platform = platformKey,
            channelId = channelId,
            handle = if (channelName.startsWith("@")) channelName else "",
            displayName = channelName.ifBlank { channelId },
            avatarUrl = avatarUrl,
            addedAtMs = System.currentTimeMillis()
        )
        if (wasFollowed) {
            AppPrefs.unfollowCreator(this, platformKey, channelId)
        } else {
            AppPrefs.followCreator(this, entry)
        }
        val nowFollowed = !wasFollowed

        // Update drilldown state if currently viewing this channel
        val top = streamingDrillStack.lastOrNull()
        if (top != null && top.kind == "channel" && top.id == channelId) {
            streamingDrillStack.removeLast()
            streamingDrillStack.addLast(top.copy(isSubscribedRemote = nowFollowed))
        }
        refreshStreamingAccountPill()
        rebuildStreamingSubNavRow()

        Toast.makeText(
            this,
            if (nowFollowed) "Followed ${channelName.ifBlank { channelId }}" else "Unfollowed ${channelName.ifBlank { channelId }}",
            Toast.LENGTH_SHORT
        ).show()

        if (platformKey == "youtube") {
            val ytAuth = buildYouTubeAuthContext()
            if (ytAuth.isSignedIn) {
                lifecycleScope.launch {
                    runCatching {
                        if (nowFollowed) {
                            YouTubeApiClient.subscribe(channelId, ytAuth, subscribeParams)
                        } else {
                            YouTubeApiClient.unsubscribe(channelId, ytAuth, subscribeParams)
                        }
                    }
                }
            }
        }
    }

    private fun reloadStreamingFeedFromScratch() {
        streamingLoadJob?.cancel()
        streamingIsLoading = true
        streamingReachedEnd = false
        streamingNextToken = null
        streamingSpinner.visibility = View.VISIBLE
        streamingEmptyText.visibility = View.GONE
        streamingGridAdapter.submit(emptyList())
        streamingStatusMono.text = "${activeStreamingPlatform.label.uppercase(Locale.US)} · LOADING..."

        val capturedPlatform = activeStreamingPlatform
        val capturedSection = activeStreamingSection
        val capturedDrill = streamingDrillStack.lastOrNull()

        streamingLoadJob = lifecycleScope.launch {
            val (cards, nextTok, emptyHint) = runCatching {
                fetchStreamingPageInternal(
                    platform = capturedPlatform,
                    section = capturedSection,
                    drill = capturedDrill,
                    pageToken = null
                )
            }.getOrElse { err ->
                Triple(emptyList(), null, "Unable to load ${capturedPlatform.label}: ${err.message ?: "Network error"}")
            }

            if (activeStreamingPlatform != capturedPlatform) return@launch
            streamingIsLoading = false
            streamingLoadedAny = true
            streamingSpinner.visibility = View.GONE
            streamingNextToken = nextTok
            streamingReachedEnd = nextTok.isNullOrBlank() || cards.isEmpty()

            streamingGridAdapter.submit(cards)
            streamingStatusMono.text = "${capturedPlatform.label.uppercase(Locale.US)} · ${cards.size} ITEMS"
            if (cards.isEmpty()) {
                streamingEmptyText.text = emptyHint ?: "No items returned for this section."
                streamingEmptyText.visibility = View.VISIBLE
            } else {
                streamingEmptyText.visibility = View.GONE
            }
        }
    }

    private fun maybeLoadNextStreamingPage() {
        if (streamingIsLoading || streamingReachedEnd) return
        val token = streamingNextToken?.takeIf { it.isNotBlank() } ?: return
        if (streamingGridAdapter.currentCount() == 0) return

        streamingIsLoading = true
        streamingSpinner.visibility = View.VISIBLE
        val capturedPlatform = activeStreamingPlatform
        val capturedSection = activeStreamingSection
        val capturedDrill = streamingDrillStack.lastOrNull()

        streamingLoadJob = lifecycleScope.launch {
            val (moreCards, nextTok, _) = runCatching {
                fetchStreamingPageInternal(
                    platform = capturedPlatform,
                    section = capturedSection,
                    drill = capturedDrill,
                    pageToken = token
                )
            }.getOrElse { Triple(emptyList(), null, null) }

            if (activeStreamingPlatform != capturedPlatform) return@launch
            streamingIsLoading = false
            streamingSpinner.visibility = View.GONE

            if (moreCards.isEmpty()) {
                streamingReachedEnd = true
                streamingNextToken = null
                return@launch
            }
            streamingNextToken = nextTok
            streamingReachedEnd = nextTok.isNullOrBlank()
            streamingGridAdapter.append(moreCards)
            streamingStatusMono.text = "${capturedPlatform.label.uppercase(Locale.US)} · ${streamingGridAdapter.currentCount()} ITEMS"
        }
    }

    private suspend fun fetchStreamingPageInternal(
        platform: StreamingPlatform,
        section: String,
        drill: StreamingDrillState?,
        pageToken: String?
    ): Triple<List<StreamCardItem>, String?, String?> {
        if (drill != null) {
            return when (platform) {
                StreamingPlatform.YOUTUBE -> {
                    val ytAuth = buildYouTubeAuthContext()
                    when (drill.kind) {
                        "channel" -> {
                            val feed = YouTubeApiClient.getChannelFeed(
                                channelId = drill.id,
                                authContext = ytAuth,
                                continuation = pageToken
                            )
                            if (pageToken == null && streamingDrillStack.lastOrNull()?.id == drill.id) {
                                streamingDrillStack.removeLast()
                                streamingDrillStack.addLast(
                                    drill.copy(
                                        title = feed.title.ifBlank { drill.title },
                                        subscribeParams = feed.subscribeParams ?: drill.subscribeParams,
                                        isSubscribedRemote = feed.isSubscribed ?: drill.isSubscribedRemote,
                                        avatarUrl = feed.avatarUrl ?: drill.avatarUrl
                                    )
                                )
                                withContext(Dispatchers.Main) { rebuildStreamingSubNavRow() }
                            }
                            val allVids = if (pageToken == null) (feed.liveVideos + feed.videos).distinctBy { it.videoId } else feed.videos
                            Triple(allVids.map { mapYouTubeVideoToCard(it) }, feed.continuation, "No uploads found on this channel.")
                        }
                        "playlist" -> {
                            val page = YouTubeApiClient.getPlaylistVideos(
                                playlistId = drill.id,
                                authContext = ytAuth,
                                continuation = pageToken
                            )
                            Triple(page.items.map { mapYouTubeVideoToCard(it) }, page.continuation, "This playlist has 0 videos.")
                        }
                        else -> Triple(emptyList(), null, null)
                    }
                }
                StreamingPlatform.TWITCH -> {
                    when (drill.kind) {
                        "channel" -> {
                            val (_, page) = TwitchClient.getChannelDetailsAndVods(
                                channelLogin = drill.id,
                                limit = 30,
                                cursor = pageToken
                            )
                            Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "No live stream or past broadcasts found for @${drill.id}.")
                        }
                        "category" -> {
                            val page = TwitchClient.getCategoryStreams(
                                categorySlugOrName = drill.id,
                                limit = 30,
                                cursor = pageToken
                            )
                            Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "No live streams in ${drill.title} right now.")
                        }
                        else -> Triple(emptyList(), null, null)
                    }
                }
                StreamingPlatform.ODYSEE -> {
                    val pageNum = pageToken?.toIntOrNull() ?: 1
                    when (drill.kind) {
                        "channel" -> {
                            val (ch, page) = OdyseeClient.getChannelFeed(
                                channelIdOrHandle = drill.id,
                                page = pageNum,
                                pageSize = 30
                            )
                            if (pageNum == 1 && ch != null && streamingDrillStack.lastOrNull()?.id == drill.id) {
                                streamingDrillStack.removeLast()
                                streamingDrillStack.addLast(
                                    drill.copy(
                                        id = ch.id,
                                        title = ch.displayName.ifBlank { drill.title },
                                        avatarUrl = ch.avatarUrl.orEmpty()
                                    )
                                )
                                withContext(Dispatchers.Main) { rebuildStreamingSubNavRow() }
                            }
                            Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "No Odysee uploads found on this channel.")
                        }
                        "category" -> {
                            val page = OdyseeClient.getCategoryFeed(
                                categorySlugOrTag = drill.id,
                                page = pageNum,
                                pageSize = 30
                            )
                            Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "No Odysee videos found in ${drill.title}.")
                        }
                        else -> Triple(emptyList(), null, null)
                    }
                }
                StreamingPlatform.RUMBLE -> {
                    val pageNum = pageToken?.toIntOrNull() ?: 1
                    when (drill.kind) {
                        "channel" -> {
                            val (ch, page) = RumbleClient.getChannelFeed(
                                channelPathOrName = drill.id,
                                page = pageNum
                            )
                            if (pageNum == 1 && ch != null && streamingDrillStack.lastOrNull()?.id == drill.id) {
                                streamingDrillStack.removeLast()
                                streamingDrillStack.addLast(
                                    drill.copy(
                                        id = ch.id,
                                        title = ch.displayName.ifBlank { drill.title },
                                        avatarUrl = ch.avatarUrl.orEmpty()
                                    )
                                )
                                withContext(Dispatchers.Main) { rebuildStreamingSubNavRow() }
                            }
                            Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "No Rumble uploads found on this channel.")
                        }
                        "category" -> {
                            val page = RumbleClient.getCategoryFeed(categorySlug = drill.id, liveOnly = false)
                            Triple(page.items.map { mapCreatorVideoToCard(it) }, null, "No Rumble videos found in ${drill.title}.")
                        }
                        else -> Triple(emptyList(), null, null)
                    }
                }
            }
        }

        // Top-level platform sections
        return when (platform) {
            StreamingPlatform.YOUTUBE -> {
                val ytAuth = buildYouTubeAuthContext()
                when (section) {
                    "home" -> {
                        val page = YouTubeApiClient.getHomeFeed(ytAuth, continuation = pageToken)
                        Triple(page.items.map { mapYouTubeVideoToCard(it) }, page.continuation, "YouTube Home feed is currently empty.")
                    }
                    "subscriptions" -> {
                        val localChIds = AppPrefs.getCreatorFollows(this)
                            .filter { it.isActive && it.platform.equals("youtube", ignoreCase = true) }
                            .map { it.channelId }
                        if (!ytAuth.isSignedIn && localChIds.isEmpty()) {
                            return Triple(
                                emptyList(),
                                null,
                                "Sign in with YouTube TV Code (top-right) or long-press any video -> Follow Channel for zero-login RSS subscriptions."
                            )
                        }
                        val page = YouTubeApiClient.getSubscriptionsFeed(
                            authContext = ytAuth,
                            continuation = pageToken,
                            followedChannelIds = localChIds
                        )
                        Triple(page.items.map { mapYouTubeVideoToCard(it) }, page.continuation, "No recent subscription uploads found.")
                    }
                    "history" -> {
                        if (!ytAuth.isSignedIn) {
                            val localYt = AppPrefs.getWatchHistory(this)
                                .filter { it.itemId.startsWith("yt:") }
                                .map { h ->
                                    val vid = h.itemId.removePrefix("yt:").ifBlank { h.videoId }
                                    StreamCardItem(
                                        key = "yt:hist:$vid",
                                        kind = "video",
                                        platform = "youtube",
                                        id = vid,
                                        title = h.name,
                                        subtitle = h.episodeTitle.ifBlank { "Local History" },
                                        thumbnailUrl = h.background.ifBlank { h.poster },
                                        channelName = h.episodeTitle,
                                        resumeFraction = h.progressFraction,
                                        startSeconds = h.timeOffsetMs / 1000L
                                    )
                                }
                            return Triple(
                                localYt,
                                null,
                                "Sign in with YouTube TV Code (top-right) to sync cloud Watch History & red resume bars."
                            )
                        }
                        val page = YouTubeApiClient.getHistoryFeed(ytAuth, continuation = pageToken)
                        Triple(page.items.map { mapYouTubeVideoToCard(it) }, page.continuation, "Your YouTube Watch History is empty.")
                    }
                    "playlists" -> {
                        if (!ytAuth.isSignedIn) {
                            return Triple(
                                emptyList(),
                                null,
                                "Sign in with YouTube TV Code (top-right) to browse Watch Later, Liked Videos, and saved playlists."
                            )
                        }
                        val pls = YouTubeApiClient.getLibraryPlaylists(ytAuth)
                        Triple(pls.map { mapYouTubePlaylistToCard(it) }, null, "No playlists found on this YouTube account.")
                    }
                    "search" -> {
                        if (streamingSearchQuery.isBlank()) {
                            return Triple(emptyList(), null, "Select \"Enter Query...\" or press the Mic button to search YouTube.")
                        }
                        val page = YouTubeApiClient.search(
                            query = streamingSearchQuery,
                            authContext = ytAuth,
                            filter = selectedYouTubeSearchFilter,
                            continuation = pageToken
                        )
                        val cards = buildList {
                            if (pageToken == null) {
                                page.channels.forEach { add(mapYouTubeChannelToCard(it)) }
                                page.playlists.forEach { add(mapYouTubePlaylistToCard(it)) }
                            }
                            page.items.forEach { add(mapYouTubeVideoToCard(it)) }
                        }
                        Triple(cards, page.continuation, "No YouTube matches for \"$streamingSearchQuery\".")
                    }
                    else -> Triple(emptyList(), null, null)
                }
            }

            StreamingPlatform.TWITCH -> {
                val twAuth = buildTwitchAuthContext()
                when (section) {
                    "top_live" -> {
                        val page = TwitchClient.getTopLiveStreams(limit = 30, cursor = pageToken)
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "Unable to load Twitch live streams.")
                    }
                    "following" -> {
                        val localLogins = AppPrefs.getCreatorFollows(this)
                            .filter { it.isActive && it.platform.equals("twitch", ignoreCase = true) }
                            .map { it.channelId.ifBlank { it.handle.removePrefix("@") } }
                        if (twAuth == null && localLogins.isEmpty()) {
                            return Triple(
                                emptyList(),
                                null,
                                "Sign in via Twitch QR (top-right) or long-press any stream -> Follow Streamer to track live channels."
                            )
                        }
                        val page = TwitchClient.getFollowedLiveStreams(
                            authContext = twAuth,
                            followedChannelLogins = localLogins
                        )
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, null, "None of your followed Twitch channels are live right now.")
                    }
                    "categories" -> {
                        val page = TwitchClient.getTopCategories(limit = 32, cursor = pageToken)
                        Triple(page.categories.map { mapCreatorCategoryToCard(it) }, page.nextCursor, "No Twitch categories loaded.")
                    }
                    "search" -> {
                        if (streamingSearchQuery.isBlank()) {
                            return Triple(emptyList(), null, "Enter a streamer or game query to search Twitch.")
                        }
                        val page = TwitchClient.search(streamingSearchQuery, limit = 28)
                        val cards = buildList {
                            page.items.forEach { add(mapCreatorVideoToCard(it)) }
                            page.channels.forEach { ch ->
                                if (page.items.none { it.channelId.equals(ch.id, ignoreCase = true) }) {
                                    add(mapCreatorChannelToCard(ch))
                                } else {
                                    add(mapCreatorChannelToCard(ch))
                                }
                            }
                        }.distinctBy { it.key }
                        Triple(cards, null, "No Twitch channels matched \"$streamingSearchQuery\".")
                    }
                    else -> Triple(emptyList(), null, null)
                }
            }

            StreamingPlatform.ODYSEE -> {
                val pageNum = pageToken?.toIntOrNull() ?: 1
                when (section) {
                    "trending" -> {
                        val page = OdyseeClient.getTrendingFeed(page = pageNum, pageSize = 30)
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "Unable to load Odysee Trending.")
                    }
                    "live" -> {
                        val page = OdyseeClient.getLiveStreams(limit = 36)
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, null, "No active Odysee livestreams right now.")
                    }
                    "following" -> {
                        val claimIds = AppPrefs.getCreatorFollows(this)
                            .filter { it.isActive && it.platform.equals("odysee", ignoreCase = true) }
                            .map { it.channelId }
                        if (claimIds.isEmpty()) {
                            return Triple(
                                emptyList(),
                                null,
                                "No followed Odysee channels yet. Click \"+ Follow Channel\" (top-right) or long-press any video to follow."
                            )
                        }
                        val page = OdyseeClient.getFollowedFeed(followedChannelClaimIds = claimIds, page = pageNum, pageSize = 30)
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "No uploads found from followed Odysee channels.")
                    }
                    "categories" -> {
                        val cats = OdyseeClient.getCategories().map { mapCreatorCategoryToCard(it) }
                        Triple(cats, null, "No Odysee categories.")
                    }
                    "search" -> {
                        if (streamingSearchQuery.isBlank()) {
                            return Triple(emptyList(), null, "Enter a query to search Odysee videos & channels.")
                        }
                        val searchPage = pageToken?.toIntOrNull() ?: 0
                        val page = OdyseeClient.search(streamingSearchQuery, page = searchPage, pageSize = 25)
                        val cards = buildList {
                            if (searchPage == 0) page.channels.forEach { add(mapCreatorChannelToCard(it)) }
                            page.items.forEach { add(mapCreatorVideoToCard(it)) }
                        }
                        Triple(cards, page.nextCursor, "No Odysee results for \"$streamingSearchQuery\".")
                    }
                    else -> Triple(emptyList(), null, null)
                }
            }

            StreamingPlatform.RUMBLE -> {
                val pageNum = pageToken?.toIntOrNull() ?: 1
                when (section) {
                    "featured" -> {
                        val page = RumbleClient.getFeaturedFeed(page = pageNum)
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "Unable to load Rumble Featured feed.")
                    }
                    "live" -> {
                        val page = RumbleClient.getLiveStreams(page = pageNum)
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "No active Rumble livestreams found.")
                    }
                    "following" -> {
                        val paths = AppPrefs.getCreatorFollows(this)
                            .filter { it.isActive && it.platform.equals("rumble", ignoreCase = true) }
                            .map { it.channelId }
                        if (paths.isEmpty()) {
                            return Triple(
                                emptyList(),
                                null,
                                "No followed Rumble channels yet. Click \"+ Follow Channel\" (top-right) or long-press any video to follow."
                            )
                        }
                        val page = RumbleClient.getFollowedFeed(paths)
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, null, "No recent uploads from followed Rumble channels.")
                    }
                    "categories" -> {
                        val cats = RumbleClient.getCategories().map { mapCreatorCategoryToCard(it) }
                        Triple(cats, null, "No Rumble categories.")
                    }
                    "search" -> {
                        if (streamingSearchQuery.isBlank()) {
                            return Triple(emptyList(), null, "Enter a query to search Rumble.")
                        }
                        val page = RumbleClient.search(streamingSearchQuery, page = pageNum)
                        Triple(page.items.map { mapCreatorVideoToCard(it) }, page.nextCursor, "No Rumble results for \"$streamingSearchQuery\".")
                    }
                    else -> Triple(emptyList(), null, null)
                }
            }
        }
    }

    private fun mapYouTubeVideoToCard(v: YouTubeVideoCard): StreamCardItem {
        val sub = listOfNotNull(
            v.channelName.takeIf { it.isNotBlank() },
            v.viewCountText?.takeIf { it.isNotBlank() },
            v.publishedText?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        val serverFrac = ((v.resumePercent ?: 0).coerceIn(0, 100)) / 100f
        return StreamCardItem(
            key = "yt:v:${v.videoId}",
            kind = "video",
            platform = "youtube",
            id = v.videoId,
            title = v.title,
            subtitle = sub,
            badgeText = v.durationText.orEmpty(),
            isLive = v.isLive,
            thumbnailUrl = v.thumbnailUrl,
            channelId = v.channelId.orEmpty(),
            channelName = v.channelName,
            channelAvatarUrl = v.channelAvatarUrl.orEmpty(),
            resumeFraction = serverFrac,
            startSeconds = v.startSeconds ?: 0L
        )
    }

    private fun mapYouTubeChannelToCard(ch: YouTubeChannelCard): StreamCardItem {
        val sub = listOfNotNull(
            ch.handle?.takeIf { it.isNotBlank() },
            ch.subscriberCountText?.takeIf { it.isNotBlank() },
            ch.videoCountText?.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "YouTube Channel" }
        return StreamCardItem(
            key = "yt:ch:${ch.channelId}",
            kind = "channel",
            platform = "youtube",
            id = ch.channelId,
            title = ch.title,
            subtitle = sub,
            badgeText = "CHANNEL",
            thumbnailUrl = ch.avatarUrl.orEmpty(),
            channelId = ch.channelId,
            channelName = ch.title,
            channelAvatarUrl = ch.avatarUrl.orEmpty()
        )
    }

    private fun mapYouTubePlaylistToCard(pl: YouTubePlaylistCard): StreamCardItem {
        val sub = listOfNotNull(
            pl.author?.takeIf { it.isNotBlank() },
            pl.videoCountText?.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "YouTube Playlist" }
        return StreamCardItem(
            key = "yt:pl:${pl.playlistId}",
            kind = "playlist",
            platform = "youtube",
            id = pl.playlistId,
            title = pl.title,
            subtitle = sub,
            badgeText = pl.videoCountText?.uppercase(Locale.US) ?: "PLAYLIST",
            thumbnailUrl = pl.thumbnailUrl.orEmpty()
        )
    }

    private fun mapCreatorVideoToCard(v: CreatorVideoCard): StreamCardItem {
        val pKey = v.platform.name.lowercase(Locale.US)
        val sub = listOfNotNull(
            v.channelName.takeIf { it.isNotBlank() },
            v.categoryName?.takeIf { it.isNotBlank() },
            v.viewCountText?.takeIf { it.isNotBlank() },
            v.publishedText?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        return StreamCardItem(
            key = "$pKey:v:${v.id}",
            kind = "video",
            platform = pKey,
            id = v.id,
            title = v.title,
            subtitle = sub,
            badgeText = v.durationText.orEmpty(),
            isLive = v.isLive,
            thumbnailUrl = v.thumbnailUrl,
            channelId = v.channelId,
            channelName = v.channelName,
            channelAvatarUrl = v.channelAvatarUrl.orEmpty(),
            streamHint = v.streamUrlHint
        )
    }

    private fun mapCreatorChannelToCard(ch: CreatorChannel): StreamCardItem {
        val pKey = ch.platform.name.lowercase(Locale.US)
        val sub = listOfNotNull(
            ch.handle?.takeIf { it.isNotBlank() },
            ch.followerCountText?.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "${ch.platform.name} Channel" }
        return StreamCardItem(
            key = "$pKey:ch:${ch.id}",
            kind = "channel",
            platform = pKey,
            id = ch.id,
            title = ch.displayName,
            subtitle = sub,
            badgeText = if (ch.isLive) "LIVE CH" else "CHANNEL",
            isLive = ch.isLive,
            thumbnailUrl = ch.avatarUrl ?: ch.bannerUrl.orEmpty(),
            channelId = ch.id,
            channelName = ch.displayName,
            channelAvatarUrl = ch.avatarUrl.orEmpty()
        )
    }

    private fun mapCreatorCategoryToCard(cat: CreatorCategory): StreamCardItem {
        val pKey = cat.platform.name.lowercase(Locale.US)
        return StreamCardItem(
            key = "$pKey:cat:${cat.slug}",
            kind = "category",
            platform = pKey,
            id = cat.slug,
            title = cat.name,
            subtitle = cat.viewerCountText ?: "${cat.platform.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }} Category",
            badgeText = "CATEGORY",
            thumbnailUrl = cat.boxArtUrl.orEmpty()
        )
    }

    private fun onStreamCardClicked(item: StreamCardItem) {
        when (item.kind) {
            "channel", "playlist", "category" -> {
                streamingDrillStack.addLast(
                    StreamingDrillState(
                        kind = item.kind,
                        id = item.id,
                        title = item.title,
                        avatarUrl = item.channelAvatarUrl.ifBlank { item.thumbnailUrl }
                    )
                )
                syncStreamingBackHandler()
                rebuildStreamingSubNavRow()
                reloadStreamingFeedFromScratch()
            }
            else -> playStreamingVideoCard(item)
        }
    }

    private fun onStreamCardLongClicked(item: StreamCardItem) {
        when (item.kind) {
            "channel" -> {
                val isFol = AppPrefs.isCreatorFollowed(this, item.platform, item.id)
                val opts = listOf(
                    "Open Channel (${item.title})",
                    if (isFol) "Unfollow ${item.title}" else "Follow ${item.title}"
                )
                TvUiKit.showSelectDialog(
                    context = this,
                    title = item.title,
                    options = opts,
                    selectedIndex = 0,
                    eyebrowLabel = "${item.platform.uppercase(Locale.US)} / CHANNEL"
                ) { idx ->
                    when (idx) {
                        0 -> onStreamCardClicked(item)
                        1 -> toggleStreamingChannelFollow(
                            platformKey = item.platform,
                            channelId = item.id,
                            channelName = item.title,
                            avatarUrl = item.thumbnailUrl,
                            currentlyFollowed = isFol
                        )
                    }
                }
            }
            "playlist", "category" -> onStreamCardClicked(item)
            else -> showStreamVideoContextMenu(item)
        }
    }

    private fun compositeStreamFavoriteId(item: StreamCardItem): String = when (item.platform) {
        "youtube" -> "yt:${item.id}"
        else -> "${item.platform}:${item.id}"
    }

    private fun formatCompactVotes(n: Long): String = when {
        n >= 1_000_000L -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000L -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    @OptIn(UnstableApi::class)
    private fun showStreamVideoContextMenu(item: StreamCardItem) {
        lifecycleScope.launch {
            val rydSuffix = if (item.platform == "youtube" && AppPrefs.isReturnYouTubeDislikeEnabled(this@MainActivity)) {
                val ryd = withTimeoutOrNull(1_200L) {
                    YouTubeApiClient.fetchReturnYouTubeDislike(item.id)
                }
                if (ryd != null) "  ·  👍 ${formatCompactVotes(ryd.likes)}  👎 ${formatCompactVotes(ryd.dislikes)}" else ""
            } else ""

            val hasChannel = item.channelId.isNotBlank()
            val chLabel = item.channelName.ifBlank { item.channelId }
            val isFollowed = hasChannel && AppPrefs.isCreatorFollowed(this@MainActivity, item.platform, item.channelId)
            val favId = compositeStreamFavoriteId(item)
            val isFav = AppPrefs.isFavorite(this@MainActivity, favId)

            data class MenuAction(val label: String, val run: () -> Unit)
            val actions = ArrayList<MenuAction>()
            actions.add(MenuAction("▶ Play Ad-Free (Auto <=1080p)") {
                playStreamingVideoCard(item)
            })
            actions.add(MenuAction("⚙ Select Quality (4K / 1080p60 / 720p / 360p)...") {
                playStreamingVideoCard(item, promptQualitySelection = true)
            })
            if (hasChannel) {
                actions.add(MenuAction("👤 Open Channel ($chLabel)") {
                    val targetPlat = StreamingPlatform.entries.firstOrNull { it.key == item.platform }
                    if (targetPlat != null && activeStreamingPlatform != targetPlat) {
                        activeStreamingPlatform = targetPlat
                        streamingPlatformButtons.forEach { (p, btn) -> TvUiKit.setPillSelected(btn, p == targetPlat) }
                        refreshStreamingAccountPill()
                    }
                    streamingDrillStack.addLast(
                        StreamingDrillState(
                            kind = "channel",
                            id = item.channelId,
                            title = chLabel,
                            avatarUrl = item.channelAvatarUrl
                        )
                    )
                    syncStreamingBackHandler()
                    if (activeTab != NavTab.STREAMING) switchTab(NavTab.STREAMING)
                    rebuildStreamingSubNavRow()
                    reloadStreamingFeedFromScratch()
                })
                actions.add(MenuAction(if (isFollowed) "★ Unfollow $chLabel" else "☆ Follow $chLabel") {
                    toggleStreamingChannelFollow(
                        platformKey = item.platform,
                        channelId = item.channelId,
                        channelName = chLabel,
                        avatarUrl = item.channelAvatarUrl,
                        currentlyFollowed = isFollowed
                    )
                })
            }
            actions.add(MenuAction(if (isFav) "♥ Remove from Library" else "♡ Save to Library") {
                val added = AppPrefs.toggleFavorite(
                    this@MainActivity,
                    FavoriteEntry(
                        id = favId,
                        type = item.platform,
                        name = item.title,
                        poster = item.thumbnailUrl,
                        subtitle = item.channelName.ifBlank { item.platform.uppercase(Locale.US) }
                    )
                )
                Toast.makeText(
                    this@MainActivity,
                    if (added) "Saved to Library" else "Removed from Library",
                    Toast.LENGTH_SHORT
                ).show()
                if (activeTab == NavTab.LIBRARY) refreshLibraryGrid()
            })

            TvUiKit.showSelectDialog(
                context = this@MainActivity,
                title = item.title,
                options = actions.map { it.label },
                selectedIndex = 0,
                eyebrowLabel = "${item.platform.uppercase(Locale.US)}$rydSuffix"
            ) { idx ->
                if (idx in actions.indices) {
                    actions[idx].run()
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun playStreamingVideoCard(
        item: StreamCardItem,
        resumeOverrideMs: Long? = null,
        promptQualitySelection: Boolean = false
    ) {
        streamingResolveJob?.cancel()
        if (::streamingSpinner.isInitialized) streamingSpinner.visibility = View.VISIBLE
        if (::streamingStatusMono.isInitialized && activeTab == NavTab.STREAMING) {
            streamingStatusMono.text = "RESOLVING AD-FREE STREAM..."
        } else {
            Toast.makeText(this, "Resolving ad-free stream...", Toast.LENGTH_SHORT).show()
        }

        streamingResolveJob = lifecycleScope.launch {
            try {
                if (item.platform == "youtube") {
                    val ytAuth = buildYouTubeAuthContext()
                    val rydEnabled = AppPrefs.isReturnYouTubeDislikeEnabled(this@MainActivity)
                    val (resolved, ryd) = coroutineScope {
                        val sDef = async {
                            YouTubeApiClient.resolveStream(
                                videoId = item.id,
                                authContext = ytAuth,
                                preferredMaxHeight = 1080,
                                context = this@MainActivity
                            )
                        }
                        val rDef = async {
                            if (rydEnabled) {
                                withTimeoutOrNull(2_000L) { YouTubeApiClient.fetchReturnYouTubeDislike(item.id) }
                            } else null
                        }
                        sDef.await() to rDef.await()
                    }

                    val fullDashXml = if (!resolved.isLive) resolved.buildDashMpdXml() else null
                    val qualityChoices = ArrayList<PlayerActivity.QualityChoice>()
                    if (!resolved.isLive && fullDashXml != null) {
                        qualityChoices.add(
                            PlayerActivity.QualityChoice(
                                label = "Auto (<=1080p)",
                                dashMpdXml = fullDashXml,
                                maxHeight = 1080
                            )
                        )
                        val distinctVariants = resolved.qualityVariants
                            .sortedWith(
                                compareByDescending<YouTubeQualityVariant> { it.height }
                                    .thenByDescending { it.fps }
                                    .thenByDescending { it.bitrate }
                            )
                            .distinctBy { "${it.height}p${if (it.fps > 30) it.fps else ""}" }

                        for (v in distinctVariants) {
                            val singleDash = resolved.buildDashMpdXml(selectedVariant = v)
                            if (singleDash != null) {
                                qualityChoices.add(
                                    PlayerActivity.QualityChoice(
                                        label = v.label,
                                        dashMpdXml = singleDash,
                                        maxHeight = v.height
                                    )
                                )
                            }
                        }
                        if (!resolved.muxedUrl.isNullOrBlank()) {
                            val mh = resolved.muxedHeight ?: 360
                            qualityChoices.add(
                                PlayerActivity.QualityChoice(
                                    label = "Progressive ${mh}p (Muxed)",
                                    directUrl = resolved.muxedUrl,
                                    mimeType = "video/mp4",
                                    maxHeight = mh
                                )
                            )
                        }
                    } else if (resolved.isLive && !resolved.hlsManifestUrl.isNullOrBlank()) {
                        qualityChoices.add(
                            PlayerActivity.QualityChoice(
                                label = "Live HLS (Auto)",
                                directUrl = resolved.hlsManifestUrl,
                                mimeType = "application/x-mpegURL",
                                maxHeight = 1080
                            )
                        )
                    }

                    val subtitles = resolved.captionTracks.mapIndexed { idx, ct ->
                        ExternalSubtitle(
                            id = "ytcc:${ct.languageCode}:$idx",
                            url = ct.vttUrl.ifBlank { ct.srtUrl },
                            lang = if (ct.isAutoGenerated) "${ct.name} (Auto)" else ct.name.ifBlank { ct.languageCode },
                            addonName = "YouTube CC"
                        )
                    }

                    val localHistMs = AppPrefs.getWatchHistory(this@MainActivity)
                        .firstOrNull { it.itemId == "yt:${resolved.videoId}" || it.videoId == resolved.videoId }
                        ?.timeOffsetMs
                        ?.takeIf { it > 0L }
                    val cardStartMs = (item.startSeconds * 1000L).takeIf { it > 0L }
                    val fracMs = if (item.resumeFraction in 0.03f..0.95f && resolved.durationSec > 0L) {
                        (item.resumeFraction * resolved.durationSec * 1000f).toLong()
                    } else null
                    val resumeMs = if (resolved.isLive) 0L else (resumeOverrideMs ?: localHistMs ?: cardStartMs ?: fracMs ?: 0L)

                    val subLine = buildList {
                        add(resolved.author.ifBlank { item.channelName }.ifBlank { "YouTube" })
                        if (ryd != null) {
                            add("👍 ${formatCompactVotes(ryd.likes)} · 👎 ${formatCompactVotes(ryd.dislikes)}")
                        }
                    }.joinToString("  ·  ")

                    val syncHistory = AppPrefs.isYouTubeWatchHistoryEnabled(this@MainActivity)
                    val fallbackUrl = resolved.hlsManifestUrl ?: resolved.muxedUrl

                    fun launchWithChoice(choice: PlayerActivity.QualityChoice?) {
                        val useDash = choice?.dashMpdXml ?: fullDashXml
                        val useDirect = when {
                            choice?.directUrl != null -> choice.directUrl
                            useDash != null -> null
                            else -> resolved.hlsManifestUrl ?: resolved.muxedUrl ?: resolved.adaptiveVideoUrl
                        }
                        PlayerActivity.start(
                            this@MainActivity,
                            PlayerActivity.LaunchPayload(
                                title = resolved.title.ifBlank { item.title },
                                subtitle = subLine,
                                itemId = "yt:${resolved.videoId}",
                                videoId = resolved.videoId,
                                contentType = "youtube",
                                poster = resolved.thumbnailUrl ?: item.thumbnailUrl,
                                background = resolved.thumbnailUrl ?: item.thumbnailUrl,
                                directUrl = useDirect,
                                headers = resolved.headers,
                                resumePositionMs = resumeMs,
                                labeledSubtitles = subtitles,
                                dashMpdXml = if (choice?.directUrl != null) null else useDash,
                                fallbackDirectUrl = fallbackUrl,
                                youtubeVideoId = resolved.videoId,
                                ytPlaybackTrackingUrl = if (syncHistory) resolved.playbackTrackingUrl else null,
                                ytWatchtimeTrackingUrl = if (syncHistory) resolved.watchtimeTrackingUrl else null,
                                ytCpn = if (syncHistory) resolved.cpn else "",
                                qualityChoices = qualityChoices,
                                activeQualityLabel = choice?.label ?: qualityChoices.firstOrNull()?.label.orEmpty(),
                                initialMaxVideoHeight = choice?.maxHeight ?: 1080
                            )
                        )
                    }

                    if (promptQualitySelection && qualityChoices.size > 1) {
                        TvUiKit.showSelectDialog(
                            context = this@MainActivity,
                            title = "Select Stream Quality",
                            options = qualityChoices.map { it.label },
                            selectedIndex = 0,
                            eyebrowLabel = "YOUTUBE / QUALITY"
                        ) { idx ->
                            if (idx in qualityChoices.indices) {
                                launchWithChoice(qualityChoices[idx])
                            }
                        }
                    } else {
                        launchWithChoice(qualityChoices.firstOrNull())
                    }
                } else {
                    // Twitch / Odysee / Rumble ad-free stream resolution
                    val resolved = when (item.platform) {
                        "twitch" -> {
                            val target = if (item.isLive) item.channelId.ifBlank { item.id } else item.id
                            TwitchClient.resolveStream(
                                idOrLogin = target,
                                isLive = item.isLive,
                                authContext = buildTwitchAuthContext()
                            )
                        }
                        "odysee" -> OdyseeClient.resolveStream(
                            claimIdOrUri = item.id,
                            streamUrlHint = item.streamHint
                        )
                        "rumble" -> RumbleClient.resolveStream(
                            videoIdOrUrl = item.streamHint?.takeIf { it.isNotBlank() } ?: item.id
                        )
                        else -> error("Unsupported streaming platform: ${item.platform}")
                    }

                    val qualityChoices = ArrayList<PlayerActivity.QualityChoice>()
                    val autoUrl = resolved.hlsManifestUrl ?: resolved.primaryUrl
                    qualityChoices.add(
                        PlayerActivity.QualityChoice(
                            label = "Auto (<=1080p)",
                            directUrl = autoUrl,
                            mimeType = resolved.primaryMimeType,
                            maxHeight = 1080
                        )
                    )
                    for (v in resolved.qualityVariants) {
                        qualityChoices.add(
                            PlayerActivity.QualityChoice(
                                label = v.label,
                                directUrl = v.url,
                                mimeType = v.mimeType,
                                maxHeight = v.height
                            )
                        )
                    }
                    val mp4Fb = resolved.mp4FallbackUrl
                    if (!mp4Fb.isNullOrBlank() && qualityChoices.none { it.directUrl == mp4Fb }) {
                        qualityChoices.add(
                            PlayerActivity.QualityChoice(
                                label = "Direct MP4 (Fallback)",
                                directUrl = mp4Fb,
                                mimeType = "video/mp4",
                                maxHeight = 720
                            )
                        )
                    }

                    val subtitles = resolved.subtitleTracks.mapIndexed { idx, st ->
                        ExternalSubtitle(
                            id = "crsub:${st.languageCode}:$idx",
                            url = st.url,
                            lang = st.label.ifBlank { st.languageCode },
                            addonName = item.platform.replaceFirstChar { it.uppercase() }
                        )
                    }

                    val compId = "${item.platform}:${item.id}"
                    val localHistMs = AppPrefs.getWatchHistory(this@MainActivity)
                        .firstOrNull { it.itemId == compId }
                        ?.timeOffsetMs
                        ?.takeIf { it > 0L }
                    val resumeMs = if (resolved.isLive) 0L else (resumeOverrideMs ?: localHistMs ?: 0L)
                    val isTwLive = item.platform == "twitch" && resolved.isLive

                    fun launchCreatorWithChoice(choice: PlayerActivity.QualityChoice?) {
                        PlayerActivity.start(
                            this@MainActivity,
                            PlayerActivity.LaunchPayload(
                                title = resolved.title.ifBlank { item.title },
                                subtitle = resolved.channelName.ifBlank { item.channelName },
                                itemId = compId,
                                videoId = item.id,
                                contentType = item.platform,
                                poster = resolved.thumbnailUrl ?: item.thumbnailUrl,
                                background = resolved.thumbnailUrl ?: item.thumbnailUrl,
                                directUrl = choice?.directUrl ?: resolved.primaryUrl,
                                headers = resolved.headers,
                                resumePositionMs = resumeMs,
                                labeledSubtitles = subtitles,
                                fallbackDirectUrl = resolved.mp4FallbackUrl ?: resolved.backupAdFreeHlsUrl,
                                qualityChoices = qualityChoices,
                                activeQualityLabel = choice?.label ?: qualityChoices.firstOrNull()?.label.orEmpty(),
                                initialMaxVideoHeight = choice?.maxHeight ?: 1080,
                                isTwitchLive = isTwLive,
                                twitchChannelLogin = if (item.platform == "twitch") {
                                    (resolved.channelId ?: item.channelId).takeIf { it.isNotBlank() }
                                } else null,
                                twitchBackupMasterUrl = resolved.backupAdFreeHlsUrl
                            )
                        )
                    }

                    if (promptQualitySelection && qualityChoices.size > 1) {
                        TvUiKit.showSelectDialog(
                            context = this@MainActivity,
                            title = "Select Stream Quality",
                            options = qualityChoices.map { it.label },
                            selectedIndex = 0,
                            eyebrowLabel = "${item.platform.uppercase(Locale.US)} / QUALITY"
                        ) { idx ->
                            if (idx in qualityChoices.indices) {
                                launchCreatorWithChoice(qualityChoices[idx])
                            }
                        }
                    } else {
                        launchCreatorWithChoice(qualityChoices.firstOrNull())
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Stream resolution failed: ${e.message ?: "Unavailable"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (::streamingSpinner.isInitialized) streamingSpinner.visibility = View.GONE
                if (::streamingStatusMono.isInitialized && activeTab == NavTab.STREAMING) {
                    streamingStatusMono.text = "${activeStreamingPlatform.label.uppercase(Locale.US)} · ${streamingGridAdapter.currentCount()} ITEMS"
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Streaming Account & Follow Flows (YouTube TV OAuth, Twitch QR, Local Follows)
    // -------------------------------------------------------------------------
    private fun onStreamingAccountPillClicked() {
        when (activeStreamingPlatform) {
            StreamingPlatform.YOUTUBE -> {
                val yt = AppPrefs.getYouTubeOAuth(this)?.takeIf { it.isActive }
                if (yt == null) startYouTubeDeviceSignInFlow() else showYouTubeAccountMenu(yt)
            }
            StreamingPlatform.TWITCH -> {
                val tw = AppPrefs.getTwitchDeviceOAuth(this)?.takeIf { it.isActive }
                if (tw == null) startTwitchDeviceSignInFlow() else showTwitchAccountMenu(tw)
            }
            StreamingPlatform.ODYSEE, StreamingPlatform.RUMBLE -> {
                showLocalCreatorFollowDialog(activeStreamingPlatform)
            }
        }
    }

    private fun startYouTubeDeviceSignInFlow() {
        streamingAuthPollJob?.cancel()
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Requesting YouTube TV activation code...", Toast.LENGTH_SHORT).show()
            val dc = runCatching { YouTubeAuthClient.requestDeviceCode() }.getOrElse { err ->
                Toast.makeText(
                    this@MainActivity,
                    "YouTube code request failed: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            var modal: AlertDialog? = null
            modal = TvUiKit.showQrModal(
                context = this@MainActivity,
                code = dc.userCode,
                linkUrl = dc.verificationUrl,
                qrTargetUrl = dc.verificationUrl,
                eyebrowLabel = "YOUTUBE / GOOGLE TV SIGN-IN",
                title = "Activate YouTube on TV"
            ) {
                streamingAuthPollJob?.cancel()
            }

            streamingAuthPollJob = lifecycleScope.launch {
                var intervalMs = dc.intervalSec.coerceAtLeast(4) * 1_000L
                val deadline = System.currentTimeMillis() + dc.expiresInSec.coerceIn(120, 1800) * 1_000L

                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(intervalMs)
                    when (val res = YouTubeAuthClient.pollDeviceTokenDetailed(dc.deviceCode, dc.clientId, dc.clientSecret)) {
                        is YouTubeDevicePollResult.Pending -> Unit
                        is YouTubeDevicePollResult.SlowDown -> intervalMs += res.extraDelaySec * 1_000L
                        is YouTubeDevicePollResult.Error -> {
                            modal?.dismiss()
                            Toast.makeText(this@MainActivity, "YouTube sign-in failed: ${res.message}", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        is YouTubeDevicePollResult.Authorized -> {
                            modal?.dismiss()
                            val tokens = res.tokens
                            val accounts = runCatching { YouTubeAuthClient.listAccounts(tokens.accessToken) }.getOrDefault(emptyList())
                            if (accounts.size > 1) {
                                val labels = accounts.map { acc ->
                                    val brandTag = if (acc.isBrandAccount) " [Brand]" else ""
                                    val handleTag = acc.channelHandle?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                                    "${acc.accountName}$handleTag$brandTag"
                                }
                                TvUiKit.showSelectDialog(
                                    context = this@MainActivity,
                                    title = "Choose YouTube Channel / Brand Account",
                                    options = labels,
                                    selectedIndex = accounts.indexOfFirst { it.isSelected }.coerceAtLeast(0),
                                    eyebrowLabel = "YOUTUBE / ACCOUNT PICKER"
                                ) { idx ->
                                    val picked = accounts.getOrNull(idx) ?: accounts.first()
                                    saveYouTubeAccountSelection(tokens, picked)
                                }
                            } else {
                                saveYouTubeAccountSelection(tokens, accounts.firstOrNull())
                            }
                            return@launch
                        }
                    }
                }
                modal?.dismiss()
                Toast.makeText(this@MainActivity, "YouTube activation code expired", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveYouTubeAccountSelection(
        tokens: app.brootlite.tv.youtube.YouTubeOAuthTokens,
        picked: app.brootlite.tv.youtube.YouTubeAccountItem?
    ) {
        val label = picked?.accountName?.ifBlank { null }
            ?: picked?.channelHandle?.ifBlank { null }
            ?: "YouTube"
        AppPrefs.saveYouTubeOAuth(
            this,
            SyncedYouTubeOAuth(
                accountLabel = label,
                channelId = picked?.accountId.orEmpty(),
                pageId = picked?.pageId.orEmpty(),
                clientId = tokens.clientId,
                clientSecret = tokens.clientSecret,
                refreshToken = tokens.refreshToken,
                accessToken = tokens.accessToken,
                accessTokenExpiresAtMs = tokens.expiresAtMs,
                updatedAtMs = System.currentTimeMillis(),
                revokedAtMs = 0L
            )
        )
        Toast.makeText(this, "Signed in as $label", Toast.LENGTH_SHORT).show()
        refreshStreamingAccountPill()
        if (activeTab == NavTab.STREAMING && activeStreamingPlatform == StreamingPlatform.YOUTUBE) {
            reloadStreamingFeedFromScratch()
        }
        if (activeTab == NavTab.PASSPORT) refreshPassportTab()
    }

    private fun showYouTubeAccountMenu(oauth: SyncedYouTubeOAuth) {
        val sbOn = AppPrefs.isSponsorBlockEnabled(this)
        val histOn = AppPrefs.isYouTubeWatchHistoryEnabled(this)
        val rydOn = AppPrefs.isReturnYouTubeDislikeEnabled(this)
        val deArrowOn = AppPrefs.isDeArrowEnabled(this)
        val followsCount = AppPrefs.getCreatorFollows(this)
            .count { it.isActive && it.platform.equals("youtube", ignoreCase = true) }

        val options = listOf(
            "Switch Channel / Brand Account...",
            "SponsorBlock Auto-Skip: ${if (sbOn) "ON" else "OFF"}",
            "Sync Watch History & Red Bar: ${if (histOn) "ON" else "OFF"}",
            "ReturnYouTubeDislike Votes: ${if (rydOn) "ON" else "OFF"}",
            "DeArrow Clickbait Titles/Thumbs: ${if (deArrowOn) "ON" else "OFF"}",
            "Manage Local Follows ($followsCount)...",
            "Sign Out (${oauth.accountLabel.ifBlank { "YouTube" }})"
        )

        TvUiKit.showSelectDialog(
            context = this,
            title = oauth.accountLabel.ifBlank { "YouTube Account" },
            options = options,
            selectedIndex = 0,
            eyebrowLabel = "YOUTUBE / ACCOUNT & ENHANCEMENTS"
        ) { idx ->
            when (idx) {
                0 -> {
                    lifecycleScope.launch {
                        val token = YouTubeAuthClient.getValidAccessToken(buildYouTubeAuthContext())
                        if (token.isNullOrBlank()) {
                            Toast.makeText(this@MainActivity, "Session expired · Please sign in again", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val accounts = runCatching { YouTubeAuthClient.listAccounts(token) }.getOrDefault(emptyList())
                        if (accounts.isEmpty()) {
                            Toast.makeText(this@MainActivity, "No alternate Brand channels found on this Google account", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val labels = accounts.map { acc ->
                            val brandTag = if (acc.isBrandAccount) " [Brand]" else ""
                            val handleTag = acc.channelHandle?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                            "${acc.accountName}$handleTag$brandTag"
                        }
                        val curSel = accounts.indexOfFirst {
                            it.pageId.orEmpty() == oauth.pageId && (oauth.channelId.isBlank() || it.accountId == oauth.channelId)
                        }.coerceAtLeast(0)
                        TvUiKit.showSelectDialog(
                            context = this@MainActivity,
                            title = "Switch Channel / Brand Account",
                            options = labels,
                            selectedIndex = curSel,
                            eyebrowLabel = "YOUTUBE / BRAND CHANNEL"
                        ) { pickIdx ->
                            val picked = accounts.getOrNull(pickIdx) ?: return@showSelectDialog
                            val cur = AppPrefs.getYouTubeOAuth(this@MainActivity) ?: oauth
                            AppPrefs.saveYouTubeOAuth(
                                this@MainActivity,
                                cur.copy(
                                    accountLabel = picked.accountName.ifBlank { cur.accountLabel },
                                    channelId = picked.accountId,
                                    pageId = picked.pageId.orEmpty(),
                                    updatedAtMs = System.currentTimeMillis()
                                )
                            )
                            refreshStreamingAccountPill()
                            reloadStreamingFeedFromScratch()
                        }
                    }
                }
                1 -> {
                    AppPrefs.setSponsorBlockEnabled(this, !sbOn)
                    showYouTubeAccountMenu(AppPrefs.getYouTubeOAuth(this) ?: oauth)
                }
                2 -> {
                    AppPrefs.setYouTubeWatchHistoryEnabled(this, !histOn)
                    showYouTubeAccountMenu(AppPrefs.getYouTubeOAuth(this) ?: oauth)
                }
                3 -> {
                    AppPrefs.setReturnYouTubeDislikeEnabled(this, !rydOn)
                    showYouTubeAccountMenu(AppPrefs.getYouTubeOAuth(this) ?: oauth)
                }
                4 -> {
                    AppPrefs.setDeArrowEnabled(this, !deArrowOn)
                    reloadStreamingFeedFromScratch()
                }
                5 -> showLocalCreatorFollowDialog(StreamingPlatform.YOUTUBE)
                6 -> {
                    AppPrefs.revokeYouTubeOAuth(this)
                    YouTubeAuthClient.clearRamTokenCache()
                    refreshStreamingAccountPill()
                    reloadStreamingFeedFromScratch()
                    Toast.makeText(this, "Signed out of YouTube", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startTwitchDeviceSignInFlow() {
        streamingAuthPollJob?.cancel()
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Requesting Twitch activation code...", Toast.LENGTH_SHORT).show()
            val dc = runCatching { TwitchClient.requestDeviceCode() }.getOrElse { err ->
                Toast.makeText(
                    this@MainActivity,
                    "Twitch code request failed: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            var modal: AlertDialog? = null
            modal = TvUiKit.showQrModal(
                context = this@MainActivity,
                code = dc.userCode,
                linkUrl = dc.verificationUri,
                qrTargetUrl = dc.verificationUri,
                eyebrowLabel = "TWITCH / TV DEVICE PAIRING",
                title = "Sign In to Twitch"
            ) {
                streamingAuthPollJob?.cancel()
            }

            streamingAuthPollJob = lifecycleScope.launch {
                var intervalMs = dc.intervalSec.coerceAtLeast(4) * 1_000L
                val deadline = System.currentTimeMillis() + dc.expiresInSec.coerceIn(120, 1800) * 1_000L

                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(intervalMs)
                    when (val res = TwitchClient.pollDeviceTokenDetailed(dc.deviceCode, dc.clientId)) {
                        is TwitchDevicePollResult.Pending -> Unit
                        is TwitchDevicePollResult.SlowDown -> intervalMs += res.extraDelaySec * 1_000L
                        is TwitchDevicePollResult.Error -> {
                            modal?.dismiss()
                            Toast.makeText(this@MainActivity, "Twitch sign-in failed: ${res.message}", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        is TwitchDevicePollResult.Authorized -> {
                            modal?.dismiss()
                            val tokens = res.tokens
                            val tempCtx = TwitchAuthContext(
                                accessToken = tokens.accessToken,
                                refreshToken = tokens.refreshToken,
                                expiresAtMs = tokens.expiresAtMs,
                                clientId = tokens.clientId
                            )
                            val profile = runCatching { TwitchClient.getCurrentUserProfile(tempCtx) }.getOrNull()
                            val login = profile?.id?.ifBlank { null } ?: "twitch_user"
                            val disp = profile?.displayName?.ifBlank { null } ?: login
                            AppPrefs.saveTwitchDeviceOAuth(
                                this@MainActivity,
                                SyncedTwitchDeviceOAuth(
                                    twitchUserId = login,
                                    login = login,
                                    displayName = disp,
                                    clientId = tokens.clientId,
                                    accessToken = tokens.accessToken,
                                    refreshToken = tokens.refreshToken,
                                    updatedAtMs = System.currentTimeMillis(),
                                    revokedAtMs = 0L
                                )
                            )
                            Toast.makeText(this@MainActivity, "Connected Twitch as @$login", Toast.LENGTH_SHORT).show()
                            refreshStreamingAccountPill()
                            if (activeTab == NavTab.STREAMING && activeStreamingPlatform == StreamingPlatform.TWITCH) {
                                reloadStreamingFeedFromScratch()
                            }
                            if (activeTab == NavTab.PASSPORT) refreshPassportTab()
                            return@launch
                        }
                    }
                }
                modal?.dismiss()
                Toast.makeText(this@MainActivity, "Twitch activation code expired", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTwitchAccountMenu(tw: SyncedTwitchDeviceOAuth) {
        val localCount = AppPrefs.getCreatorFollows(this)
            .count { it.isActive && it.platform.equals("twitch", ignoreCase = true) }
        val options = listOf(
            "Follow Streamer by @Login...",
            "Manage Local Follows ($localCount)...",
            "Sign Out (@${tw.login.ifBlank { tw.displayName }})"
        )
        TvUiKit.showSelectDialog(
            context = this,
            title = "Twitch · @${tw.login.ifBlank { tw.displayName }}",
            options = options,
            selectedIndex = 0,
            eyebrowLabel = "TWITCH / ACCOUNT"
        ) { idx ->
            when (idx) {
                0 -> promptAddCreatorFollowByInput(StreamingPlatform.TWITCH)
                1 -> showLocalCreatorFollowDialog(StreamingPlatform.TWITCH)
                2 -> {
                    AppPrefs.revokeTwitchDeviceOAuth(this)
                    refreshStreamingAccountPill()
                    reloadStreamingFeedFromScratch()
                    Toast.makeText(this, "Signed out of Twitch", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLocalCreatorFollowDialog(platform: StreamingPlatform) {
        val follows = AppPrefs.getCreatorFollows(this)
            .filter { it.isActive && it.platform.equals(platform.key, ignoreCase = true) }
        val addLabel = "+ Follow ${platform.label} Channel by Handle / ID..."
        val options = listOf(addLabel) + follows.map { f ->
            "★ ${f.displayName.ifBlank { f.channelId }} (Open or Unfollow)"
        }

        TvUiKit.showSelectDialog(
            context = this,
            title = "${platform.label} Followed Channels (${follows.size})",
            options = options,
            selectedIndex = 0,
            eyebrowLabel = "${platform.label.uppercase(Locale.US)} / FOLLOWS"
        ) { idx ->
            if (idx !in options.indices) return@showSelectDialog
            if (idx == 0) {
                promptAddCreatorFollowByInput(platform)
            } else {
                val chosen = follows[idx - 1]
                TvUiKit.showSelectDialog(
                    context = this,
                    title = chosen.displayName.ifBlank { chosen.channelId },
                    options = listOf("Open Channel", "Unfollow"),
                    selectedIndex = 0,
                    eyebrowLabel = "${platform.label.uppercase(Locale.US)} / CHANNEL"
                ) { subIdx ->
                    when (subIdx) {
                        0 -> {
                            if (activeStreamingPlatform != platform) {
                                activeStreamingPlatform = platform
                                streamingPlatformButtons.forEach { (p, btn) -> TvUiKit.setPillSelected(btn, p == platform) }
                            }
                            streamingDrillStack.clear()
                            streamingDrillStack.addLast(
                                StreamingDrillState(
                                    kind = "channel",
                                    id = chosen.channelId,
                                    title = chosen.displayName.ifBlank { chosen.channelId },
                                    avatarUrl = chosen.avatarUrl
                                )
                            )
                            syncStreamingBackHandler()
                            refreshStreamingAccountPill()
                            rebuildStreamingSubNavRow()
                            reloadStreamingFeedFromScratch()
                        }
                        1 -> {
                            AppPrefs.unfollowCreator(this, platform.key, chosen.channelId)
                            refreshStreamingAccountPill()
                            if (activeStreamingSection == "following" || activeStreamingSection == "subscriptions") {
                                reloadStreamingFeedFromScratch()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun promptAddCreatorFollowByInput(platform: StreamingPlatform) {
        val hintText = when (platform) {
            StreamingPlatform.YOUTUBE -> "@ChannelHandle or UC... channel ID"
            StreamingPlatform.TWITCH -> "Streamer login (e.g. shroud, lirik)"
            StreamingPlatform.ODYSEE -> "@ChannelName or 40-char claim_id"
            StreamingPlatform.RUMBLE -> "/c/ChannelName or /user/Creator"
        }
        TvUiKit.showInputDialog(
            context = this,
            title = "Follow ${platform.label} Channel",
            hint = hintText,
            onSubmit = { raw ->
                val clean = raw.trim()
                if (clean.isBlank()) return@showInputDialog
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "Resolving ${platform.label} channel...", Toast.LENGTH_SHORT).show()
                    when (platform) {
                        StreamingPlatform.YOUTUBE -> {
                            val feed = YouTubeApiClient.getChannelFeed(clean, buildYouTubeAuthContext())
                            val cid = feed.channelId.ifBlank { clean }
                            val name = feed.title.ifBlank { clean }
                            AppPrefs.followCreator(
                                this@MainActivity,
                                CreatorFollowEntry(
                                    platform = "youtube",
                                    channelId = cid,
                                    handle = feed.handle.orEmpty(),
                                    displayName = name,
                                    avatarUrl = feed.avatarUrl.orEmpty()
                                )
                            )
                            Toast.makeText(this@MainActivity, "Followed $name", Toast.LENGTH_SHORT).show()
                        }
                        StreamingPlatform.TWITCH -> {
                            val login = clean.removePrefix("@").lowercase(Locale.US)
                            val (ch, _) = TwitchClient.getChannelDetailsAndVods(login, limit = 1)
                            AppPrefs.followCreator(
                                this@MainActivity,
                                CreatorFollowEntry(
                                    platform = "twitch",
                                    channelId = ch?.id ?: login,
                                    handle = "@${ch?.id ?: login}",
                                    displayName = ch?.displayName ?: login,
                                    avatarUrl = ch?.avatarUrl.orEmpty()
                                )
                            )
                            Toast.makeText(this@MainActivity, "Followed ${ch?.displayName ?: login}", Toast.LENGTH_SHORT).show()
                        }
                        StreamingPlatform.ODYSEE -> {
                            val handleOrId = if (!clean.startsWith("@") && !clean.startsWith("lbry://") && clean.length != 40) {
                                "@$clean"
                            } else clean
                            val (ch, _) = OdyseeClient.getChannelFeed(handleOrId, page = 1, pageSize = 5)
                            val cid = ch?.id ?: clean
                            val name = ch?.displayName ?: clean
                            AppPrefs.followCreator(
                                this@MainActivity,
                                CreatorFollowEntry(
                                    platform = "odysee",
                                    channelId = cid,
                                    handle = ch?.handle.orEmpty(),
                                    displayName = name,
                                    avatarUrl = ch?.avatarUrl.orEmpty()
                                )
                            )
                            Toast.makeText(this@MainActivity, "Followed $name", Toast.LENGTH_SHORT).show()
                        }
                        StreamingPlatform.RUMBLE -> {
                            val (ch, _) = RumbleClient.getChannelFeed(clean, page = 1)
                            val cid = ch?.id ?: clean
                            val name = ch?.displayName ?: clean
                            AppPrefs.followCreator(
                                this@MainActivity,
                                CreatorFollowEntry(
                                    platform = "rumble",
                                    channelId = cid,
                                    handle = cid,
                                    displayName = name,
                                    avatarUrl = ch?.avatarUrl.orEmpty()
                                )
                            )
                            Toast.makeText(this@MainActivity, "Followed $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    refreshStreamingAccountPill()
                    if (activeStreamingSection == "following" || activeStreamingSection == "subscriptions") {
                        reloadStreamingFeedFromScratch()
                    }
                }
            }
        )
    }

    // =========================================================================
    // 3. SEARCH & SWARM TAB (Stremio + Public Domain + Custom RSS + Torznab)
    // =========================================================================
    @OptIn(UnstableApi::class)
    private fun createSearchTabView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 10),
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 16)
            )
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
        }

        searchQueryBtn = TvUiKit.createPillButton(
            this,
            "🔍  Search Stremio Movies, Series, Anime, or Torrent Swarm..."
        ) {
            openStremioSearchInputDialog()
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }
        inputRow.addView(searchQueryBtn)

        val voiceBtn = TvUiKit.createIconPillButton(this, MicIconDrawable(this), "Voice Search") {
            forceNextVoiceToStremioSearch = true
            startVoiceSearch()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                TvUiKit.dp(this@MainActivity, 44),
                TvUiKit.dp(this@MainActivity, 38)
            ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 8) }
        }
        inputRow.addView(voiceBtn)

        val clearBtn = TvUiKit.createPillButton(this, "Clear") {
            currentSearchQuery = ""
            searchCatalogTypeFilter = "all"
            lastCatalogSearchHits = emptyList()
            updateSearchButtonText()
            executeSearchAndSwarm()
        }
        inputRow.addView(clearBtn)
        col.addView(inputRow)

        // First-Class Stremio Catalog Search Section (prominently above Direct P2P Swarm)
        searchStremioSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
            clipChildren = false
            clipToPadding = false
            setPadding(
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 10),
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 10)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 10) }
            background = TvUiKit.createGlassPanelDrawable(this@MainActivity, radiusDp = 14f)
        }

        val stremioHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 6) }
        }
        searchStremioHeader = TextView(this).apply {
            text = "STREMIO CATALOG SEARCH · MOVIES · SERIES · ANIME"
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoMedium(this@MainActivity)
            letterSpacing = 0.12f
        }
        stremioHeaderRow.addView(searchStremioHeader)
        stremioHeaderRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        searchCatalogTypeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        stremioHeaderRow.addView(searchCatalogTypeRow)
        searchStremioSection.addView(stremioHeaderRow)

        searchStremioIdleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }
        searchStremioPromptText = TextView(this).apply {
            text = "Enter a title or tap 🎤 Voice to search all synced Stremio addons"
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.regular(this@MainActivity)
            setPadding(0, 0, 0, TvUiKit.dp(this@MainActivity, 8))
        }
        searchStremioIdleBox.addView(searchStremioPromptText)

        val quickSearchScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val quickSearchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val quickSuggestions = listOf(
            "Dune",
            "Severance",
            "Sci-Fi",
            "Anime",
            "Cyberpunk",
            "Thriller",
            "Studio Ghibli",
            "2025"
        )
        for (suggestion in quickSuggestions) {
            val quickPill = TvUiKit.createPillButton(this, "🔍 $suggestion", compact = true) {
                currentSearchQuery = suggestion
                searchCatalogTypeFilter = "all"
                updateSearchButtonText()
                executeSearchAndSwarm()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            quickSearchRow.addView(quickPill)
        }
        quickSearchScroll.addView(quickSearchRow)
        searchStremioIdleBox.addView(quickSearchScroll)
        searchStremioSection.addView(searchStremioIdleBox)

        searchStremioAdapter = PosterRowAdapter(emptyList()) { item ->
            DetailActivity.start(this, item.id, item.type, item.name, item.poster)
        }
        searchStremioRv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            setRecycledViewPool(sharedPosterPool)
            clipToPadding = false
            clipChildren = false
            itemAnimator = null
            visibility = View.GONE
            adapter = searchStremioAdapter
        }
        searchStremioSection.addView(searchStremioRv)
        col.addView(searchStremioSection)

        // Direct P2P Swarm section + Swarm Indexer Filter Chips
        val swarmTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, TvUiKit.dp(this@MainActivity, 6))
        }
        searchSwarmHeader = TextView(this).apply {
            text = "SWARM INDEXERS · PUBLIC DOMAIN + CUSTOM RSS + TORZNAB"
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoMedium(this@MainActivity)
            letterSpacing = 0.12f
        }
        searchSwarmSpinner = ProgressBar(this).apply {
            val sz = TvUiKit.dp(this@MainActivity, 16)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = TvUiKit.dp(this@MainActivity, 10) }
            visibility = View.GONE
        }
        swarmTitleRow.addView(searchSwarmHeader)
        swarmTitleRow.addView(searchSwarmSpinner)
        col.addView(swarmTitleRow)

        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
        }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (filter in SwarmFilter.entries) {
            val chip = TvUiKit.createPillButton(this, filter.label, selected = (filter == currentSwarmFilter), compact = true) {
                currentSwarmFilter = filter
                swarmFilterButtons.forEach { (f, b) -> TvUiKit.setPillSelected(b, f == filter) }
                executeSearchAndSwarm()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            swarmFilterButtons[filter] = chip
            filterRow.addView(chip)
        }
        filterScroll.addView(filterRow)
        col.addView(filterScroll)

        searchSwarmAdapter = StreamListAdapter(
            items = emptyList(),
            onPlay = { stream ->
                val magnet = stream.toMagnetUri()
                if (magnet != null) {
                    PlayerActivity.start(
                        this,
                        PlayerActivity.LaunchPayload(
                            title = stream.filename.ifBlank { "P2P Torrent Stream" },
                            subtitle = "${stream.addonName} · ${stream.sizeLabel}",
                            itemId = "torrent:${stream.infoHash ?: magnet.hashCode()}",
                            videoId = "torrent:${stream.infoHash ?: magnet.hashCode()}",
                            magnetUri = magnet,
                            infoHash = stream.infoHash,
                            trackers = stream.sources + StreamCandidate.DEFAULT_PUBLIC_TRACKERS
                        )
                    )
                }
            },
            onLongClick = { stream ->
                val mag = stream.toMagnetUri()
                if (mag != null) {
                    val added = AppPrefs.toggleFavorite(
                        this,
                        FavoriteEntry(
                            id = "torrent:${stream.infoHash ?: mag.hashCode()}",
                            type = "torrent",
                            name = stream.filename.ifBlank { "Saved Torrent" },
                            subtitle = "${stream.addonName} · ${stream.sizeLabel}",
                            magnetUri = mag
                        )
                    )
                    Toast.makeText(this, if (added) "Saved Torrent to Library" else "Removed from Library", Toast.LENGTH_SHORT).show()
                }
            }
        )
        col.addView(RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@MainActivity)
            itemAnimator = null
            adapter = searchSwarmAdapter
        })

        return col
    }

    private fun openStremioSearchInputDialog() {
        TvUiKit.showInputDialog(
            context = this,
            title = "Search Stremio & P2P Swarm",
            initialValue = currentSearchQuery,
            hint = "Title, actor, anime, IMDb ID...",
            onSubmit = { q ->
                currentSearchQuery = q.trim()
                searchCatalogTypeFilter = "all"
                updateSearchButtonText()
                executeSearchAndSwarm()
                if (activeTab != NavTab.SEARCH) {
                    switchTab(NavTab.SEARCH)
                }
            }
        )
    }

    private fun startVoiceSearch() {
        runCatching {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak movie, anime, or torrent title...")
            }
            voiceLauncher.launch(intent)
        }.onFailure {
            forceNextVoiceToStremioSearch = false
            Toast.makeText(this, "Voice input unavailable on this remote", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSearchButtonText() {
        val q = currentSearchQuery.trim()
        if (::searchQueryBtn.isInitialized) {
            searchQueryBtn.text = if (q.isBlank()) {
                "🔍  Search Stremio Movies, Series, Anime, or Torrent Swarm..."
            } else {
                "🔍  Query: \"$q\""
            }
        }
        if (::boardSearchBarBtn.isInitialized) {
            boardSearchBarBtn.text = if (q.isBlank()) {
                "🔍  Search Stremio Movies, Series, Anime & Addons..."
            } else {
                "🔍  Stremio Search: \"$q\""
            }
        }
    }

    private fun rebuildSearchCatalogTypeFilterChips() {
        if (!::searchCatalogTypeRow.isInitialized) return
        searchCatalogTypeRow.removeAllViews()
        if (lastCatalogSearchHits.isEmpty()) {
            searchCatalogTypeRow.visibility = View.GONE
            return
        }
        val hasAnime = lastCatalogSearchHits.any { it.type.equals("anime", ignoreCase = true) }
        val specs = buildList {
            add("all" to "All")
            add("movie" to "Movies")
            add("series" to "Series")
            if (hasAnime) add("anime" to "Anime")
        }
        for ((key, label) in specs) {
            val selected = searchCatalogTypeFilter == key
            val chip = TvUiKit.createPillButton(this, label, selected = selected, compact = true) {
                searchCatalogTypeFilter = key
                applyStremioCatalogSearchViewState(isLoading = false)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = TvUiKit.dp(this@MainActivity, 6) }
            }
            searchCatalogTypeRow.addView(chip)
        }
        searchCatalogTypeRow.visibility = View.VISIBLE
    }

    private fun applyStremioCatalogSearchViewState(isLoading: Boolean) {
        if (!::searchStremioSection.isInitialized) return
        searchStremioSection.visibility = View.VISIBLE
        val q = currentSearchQuery.trim()

        if (q.isBlank()) {
            searchStremioHeader.text = "STREMIO CATALOG SEARCH · MOVIES · SERIES · ANIME"
            searchCatalogTypeRow.visibility = View.GONE
            searchStremioPromptText.text = "Enter a title or tap 🎤 Voice to search all synced Stremio addons"
            searchStremioIdleBox.visibility = View.VISIBLE
            searchStremioRv.visibility = View.GONE
            searchStremioAdapter.submit(emptyList())
            return
        }

        if (isLoading) {
            searchStremioHeader.text = "QUERYING STREMIO ADDONS · \"${q.uppercase(Locale.US)}\"..."
            searchCatalogTypeRow.visibility = View.GONE
            if (lastCatalogSearchHits.isEmpty()) {
                searchStremioPromptText.text = "Searching synced Stremio catalogs for \"$q\"..."
                searchStremioIdleBox.visibility = View.VISIBLE
                searchStremioRv.visibility = View.GONE
            }
            return
        }

        rebuildSearchCatalogTypeFilterChips()
        val filteredHits = when (searchCatalogTypeFilter) {
            "movie" -> lastCatalogSearchHits.filter { it.type.equals("movie", ignoreCase = true) }
            "series" -> lastCatalogSearchHits.filter { it.type.equals("series", ignoreCase = true) }
            "anime" -> lastCatalogSearchHits.filter { it.type.equals("anime", ignoreCase = true) }
            else -> lastCatalogSearchHits
        }

        searchStremioHeader.text = if (searchCatalogTypeFilter == "all") {
            "STREMIO CATALOG MATCHES (${lastCatalogSearchHits.size})"
        } else {
            "STREMIO CATALOG MATCHES (${filteredHits.size} / ${lastCatalogSearchHits.size})"
        }

        if (filteredHits.isNotEmpty()) {
            searchStremioIdleBox.visibility = View.GONE
            searchStremioRv.visibility = View.VISIBLE
            searchStremioAdapter.submit(filteredHits)
        } else {
            searchStremioRv.visibility = View.GONE
            searchStremioAdapter.submit(emptyList())
            searchStremioPromptText.text = if (lastCatalogSearchHits.isEmpty()) {
                "No Stremio catalog posters matched \"$q\" — check Direct P2P Swarm below or tap a quick search"
            } else {
                "No ${searchCatalogTypeFilter} matches in Stremio catalogs for \"$q\" — switch filter to All"
            }
            searchStremioIdleBox.visibility = View.VISIBLE
        }
    }

    private fun executeSearchAndSwarm() {
        searchJob?.cancel()
        searchSwarmSpinner.visibility = View.VISIBLE
        val q = currentSearchQuery.trim()
        val filter = currentSwarmFilter
        val loadingDesc = if (q.isBlank()) "LIVE TOP SEEDED · ${filter.label}" else "\"$q\" · ${filter.label}"
        searchSwarmHeader.text = "QUERYING SWARM · ${loadingDesc.uppercase(Locale.US)}..."
        if (q.isBlank()) {
            lastCatalogSearchHits = emptyList()
        }
        applyStremioCatalogSearchViewState(isLoading = q.isNotBlank())

        searchJob = lifecycleScope.launch {
            val stremioJob = async {
                if (q.isNotBlank()) {
                    val addons = AppPrefs.getInstalledAddons(this@MainActivity)
                    StremioApiClient.searchStremioCatalogs(addons, q)
                } else {
                    emptyList()
                }
            }
            val swarmJob = async {
                val catKey = when (filter) {
                    SwarmFilter.PUBLIC_DOMAIN -> "public_domain"
                    SwarmFilter.RSS -> "rss"
                    SwarmFilter.TORZNAB -> "torznab"
                    else -> "all"
                }
                NativeTorrentSearch.searchAll(this@MainActivity, q, catKey)
            }

            val catalogHits = stremioJob.await()
            val swarmHits = swarmJob.await()

            searchSwarmSpinner.visibility = View.GONE
            lastCatalogSearchHits = catalogHits
            applyStremioCatalogSearchViewState(isLoading = false)

            val modeDesc = if (q.isBlank()) "TOP SEEDED LIVE FEED (${filter.label})" else "\"$q\" · ${filter.label}"
            searchSwarmHeader.text = "DIRECT P2P SWARM (${swarmHits.size}) · ${modeDesc.uppercase(Locale.US)}"
            searchSwarmAdapter.submit(swarmHits)
        }
    }

    private fun jumpToSwarmFilter(filter: SwarmFilter, query: String = "") {
        currentSwarmFilter = filter
        currentSearchQuery = query
        searchCatalogTypeFilter = "all"
        updateSearchButtonText()
        swarmFilterButtons.forEach { (f, b) -> TvUiKit.setPillSelected(b, f == filter) }
        executeSearchAndSwarm()
        switchTab(NavTab.SEARCH)
    }

    // =========================================================================
    // 4. LIBRARY TAB
    // =========================================================================
    @OptIn(UnstableApi::class)
    private fun createLibraryTabView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 10),
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 18)
            )
        }

        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 12) }
        }
        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val filterDefs = listOf(
            "all" to "All Saved",
            "stremio" to "Stremio",
            "jellyfin" to "Jellyfin",
            "streaming" to "Streaming",
            "kodi" to "Kodi",
            "torrent" to "Saved Torrents"
        )
        val buttons = mutableListOf<Pair<String, TextView>>()
        for ((key, label) in filterDefs) {
            val btn = TvUiKit.createPillButton(this, label, selected = (key == libraryFilterType)) {
                libraryFilterType = key
                buttons.forEach { (k, b) -> TvUiKit.setPillSelected(b, k == key) }
                refreshLibraryGrid()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 8) }
            }
            buttons.add(key to btn)
            filterBar.addView(btn)
        }
        filterScroll.addView(filterBar)
        col.addView(filterScroll)

        libraryEmptyText = TextView(this).apply {
            text = "Your Library is currently empty. Bookmark titles from Discover, Jellyfin, Streaming, Kodi, or Swarm Search, or pair your Passport."
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            typeface = XirtusFonts.regular(this@MainActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(TvUiKit.dp(this@MainActivity, 6), TvUiKit.dp(this@MainActivity, 20), 0, 0)
            visibility = View.GONE
        }
        col.addView(libraryEmptyText)

        libraryAdapter = FavoriteGridAdapter(
            items = emptyList(),
            onClick = { fav ->
                when {
                    fav.id.startsWith("yt:") || fav.type == "youtube" -> {
                        val vid = fav.id.removePrefix("yt:").ifBlank { fav.id }
                        if (vid.isNotBlank()) {
                            playStreamingVideoCard(
                                StreamCardItem(
                                    key = "yt:$vid",
                                    kind = "video",
                                    platform = "youtube",
                                    id = vid,
                                    title = fav.name,
                                    subtitle = fav.subtitle,
                                    thumbnailUrl = fav.poster,
                                    channelName = fav.subtitle.substringBefore(" · ")
                                )
                            )
                        }
                    }
                    fav.id.startsWith("twitch:") ||
                        fav.id.startsWith("odysee:") ||
                        fav.id.startsWith("rumble:") ||
                        fav.type in setOf("twitch", "odysee", "rumble") -> {
                        val plat = if (fav.id.contains(':')) {
                            fav.id.substringBefore(':').lowercase(Locale.US)
                        } else {
                            fav.type.lowercase(Locale.US)
                        }
                        val rawId = fav.id.substringAfter(':', fav.id)
                        if (rawId.isNotBlank()) {
                            playStreamingVideoCard(
                                StreamCardItem(
                                    key = "$plat:$rawId",
                                    kind = "video",
                                    platform = plat,
                                    id = rawId,
                                    title = fav.name,
                                    subtitle = fav.subtitle,
                                    thumbnailUrl = fav.poster,
                                    channelName = fav.subtitle.substringBefore(" · ")
                                )
                            )
                        }
                    }
                    JellyfinIds.isJellyfinId(fav.id) -> {
                        DetailActivity.start(this, fav.id, fav.type.ifBlank { "movie" }, fav.name, fav.poster)
                    }
                    fav.type == "kodi" || !fav.pluginUrl.isNullOrBlank() -> {
                        val pUrl = fav.pluginUrl ?: fav.id.removePrefix("kodi:")
                        if (pUrl.isNotBlank()) {
                            AddonBrowserActivity.start(this, pUrl, fav.name)
                        }
                    }
                    fav.type == "torrent" || !fav.magnetUri.isNullOrBlank() -> {
                        val mag = fav.magnetUri
                        if (!mag.isNullOrBlank()) {
                            PlayerActivity.start(
                                this,
                                PlayerActivity.LaunchPayload(
                                    title = fav.name,
                                    subtitle = fav.subtitle,
                                    magnetUri = mag,
                                    trackers = StreamCandidate.DEFAULT_PUBLIC_TRACKERS
                                )
                            )
                        }
                    }
                    else -> {
                        DetailActivity.start(this, fav.id, fav.type.ifBlank { "movie" }, fav.name, fav.poster)
                    }
                }
            },
            onLongClick = { fav ->
                TvUiKit.showConfirmDialog(this, "Remove from Library?", "Remove \"${fav.name}\" from saved items?") { yes ->
                    if (yes) {
                        AppPrefs.toggleFavorite(this, fav)
                        refreshLibraryGrid()
                    }
                }
            }
        )

        col.addView(RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = GridLayoutManager(this@MainActivity, 6)
            itemAnimator = null
            adapter = libraryAdapter
        })
        return col
    }

    private fun isStreamingFavorite(fav: FavoriteEntry): Boolean =
        fav.id.startsWith("yt:") ||
            fav.id.startsWith("twitch:") ||
            fav.id.startsWith("odysee:") ||
            fav.id.startsWith("rumble:") ||
            fav.type in setOf("youtube", "twitch", "odysee", "rumble")

    private fun refreshLibraryGrid() {
        val all = AppPrefs.getFavorites(this)
        val filtered = when (libraryFilterType) {
            "jellyfin" -> all.filter { JellyfinIds.isJellyfinId(it.id) }
            "streaming" -> all.filter { isStreamingFavorite(it) }
            "kodi" -> all.filter { it.type == "kodi" }
            "torrent" -> all.filter { it.type == "torrent" }
            "stremio" -> all.filter {
                it.type != "kodi" &&
                    it.type != "torrent" &&
                    !JellyfinIds.isJellyfinId(it.id) &&
                    !isStreamingFavorite(it)
            }
            else -> all
        }
        libraryEmptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        libraryAdapter.submit(filtered)
    }

    // =========================================================================
    // 5. KODI TAB — ISOLATED :pyaddon ENGINE
    // =========================================================================
    private fun createVaultTabView(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        vaultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 12),
                TvUiKit.dp(this@MainActivity, 26),
                TvUiKit.dp(this@MainActivity, 28)
            )
        }
        scroll.addView(vaultContainer)
        return scroll
    }

    private fun populateVaultHub() {
        vaultContainer.removeAllViews()

        vaultContainer.addView(TvUiKit.eyebrow(this, "KODI / DISPOSABLE :PYADDON SANDBOX"))
        vaultContainer.addView(TextView(this).apply {
            text = "External Kodi Addon Runner"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = XirtusFonts.semiBold(this@MainActivity)
            letterSpacing = -0.02f
            setPadding(0, TvUiKit.dp(this@MainActivity, 2), 0, TvUiKit.dp(this@MainActivity, 4))
        })
        vaultContainer.addView(TextView(this).apply {
            text = "Executes Python repositories inside an isolated :pyaddon process that self-terminates (0 MB playback overhead) the instant a video stream resolves."
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            typeface = XirtusFonts.regular(this@MainActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 0, 0, TvUiKit.dp(this@MainActivity, 14))
        })

        val installedKodi = PyAddonClient.scanInstalledVideoAddonsFast(this)
        val primaryKodiAddon = installedKodi.firstOrNull { it.id.startsWith("plugin.video.") }

        // Row 1: Installed Quick Directory Shortcuts (rendered dynamically when present in kodi_env)
        if (primaryKodiAddon != null) {
            val addonId = primaryKodiAddon.id
            val addonTitle = primaryKodiAddon.name.ifBlank { "Directory" }
            vaultContainer.addView(vaultSectionEyebrow("${addonTitle.uppercase()} / QUICK DIRECTORY"))
            val quickRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 16) }
            }
            val quickActions = listOf(
                Triple("Saved Favorites", "Synced favorites.db items", "plugin://$addonId/?mode=favorites.List&url=1"),
                Triple("All Sites Index", "Alphabetical site directory", "plugin://$addonId/?mode=main.site_list"),
                Triple("$addonTitle Root", "Main menu & lists", "plugin://$addonId/"),
                Triple("New Keyword", "Add search keyword to DB", "plugin://$addonId/?mode=utils.newSearch")
            )
            for ((title, desc, pUrl) in quickActions) {
                quickRow.addView(buildVaultCard(addonTitle.uppercase(), title, desc) {
                    AddonBrowserActivity.start(this, pUrl, title)
                })
            }
            vaultContainer.addView(quickRow)
        }

        // Row 2: User-Installed Custom Sites (loaded dynamically from local kodi_env userdata / Multipass sync)
        val dbCustomSites = AppPrefs.readCustomSites(this)
        if (dbCustomSites.isNotEmpty() && primaryKodiAddon != null) {
            vaultContainer.addView(vaultSectionEyebrow("CUSTOM SITES / USER INSTALLED"))
            val customRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 16) }
            }
            for (triple in dbCustomSites) {
                val siteTitle = triple.first
                val sPluginUrl = triple.second
                val imgPath = triple.third
                val targetAddonId = sPluginUrl.removePrefix("plugin://").substringBefore('/')
                customRow.addView(buildVaultCard("CUSTOM MODULE", siteTitle, "Direct Launch", imgPath) {
                    AddonBrowserActivity.start(
                        context = this,
                        pluginUrl = sPluginUrl,
                        title = siteTitle,
                        autoOpenSiteMatch = siteTitle
                    )
                })
            }
            vaultContainer.addView(customRow)
        }

        // Row 3: Saved Keywords & Swarm Jumpers
        val keywords = AppPrefs.readCustomSiteKeywords(this)
        vaultContainer.addView(vaultSectionEyebrow("KEYWORDS & SWARM FEEDS / PUBLIC DOMAIN · TORZNAB"))
        val kwScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 16) }
        }
        val kwRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Public Domain Movies ↗" to SwarmFilter.PUBLIC_DOMAIN,
            "Torznab Indexer ↗" to SwarmFilter.TORZNAB,
            "Custom RSS Feeds ↗" to SwarmFilter.RSS
        ).forEach { pair ->
            val lbl = pair.first
            val flt = pair.second
            kwRow.addView(TvUiKit.createPillButton(this, lbl) {
                jumpToSwarmFilter(flt, "")
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = TvUiKit.dp(this@MainActivity, 8)
                }
            })
        }

        for (kw in keywords) {
            val pill = TvUiKit.createPillButton(this, "\"$kw\"") {
                val opts = buildList {
                    add("Search Public Domain Swarm for \"$kw\"")
                    add("Search Torznab / RSS for \"$kw\"")
                    if (primaryKodiAddon != null) {
                        add("Open ${primaryKodiAddon.name} Directory")
                    }
                }
                TvUiKit.showSelectDialog(
                    context = this,
                    title = "Keyword \"$kw\"",
                    options = opts,
                    eyebrowLabel = "KODI / KEYWORD"
                ) { idx ->
                    when (idx) {
                        0 -> jumpToSwarmFilter(SwarmFilter.PUBLIC_DOMAIN, kw)
                        1 -> jumpToSwarmFilter(SwarmFilter.TORZNAB, kw)
                        2 -> if (primaryKodiAddon != null) {
                            AddonBrowserActivity.start(this, primaryKodiAddon.pluginUrl, primaryKodiAddon.name)
                        }
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = TvUiKit.dp(this@MainActivity, 8)
                }
            }
            kwRow.addView(pill)
        }
        kwScroll.addView(kwRow)
        vaultContainer.addView(kwScroll)

        // Row 4: Installed Video Addons in kodi_env
        vaultContainer.addView(vaultSectionEyebrow("KODI_ENV / INSTALLED VIDEO REPOSITORIES"))
        val addonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        if (installedKodi.isEmpty()) {
            addonsRow.addView(buildVaultCard(
                "PASSPORT / SYNC",
                "Sync Kodi Vault",
                "Pull repositories via Multipass or stage kodi_env/addons"
            ) {
                switchTab(NavTab.PASSPORT)
            })
        } else {
            for (ka in installedKodi) {
                addonsRow.addView(buildVaultCard(
                    "V${ka.version.ifBlank { "1.0" }}",
                    ka.name,
                    ka.summary.ifBlank { ka.id },
                    ka.icon
                ) {
                    AddonBrowserActivity.start(this, ka.pluginUrl, ka.name)
                })
            }
        }
        vaultContainer.addView(addonsRow)
    }

    private fun vaultSectionEyebrow(label: String): TextView = TvUiKit.eyebrow(this, label).apply {
        setPadding(0, TvUiKit.dp(this@MainActivity, 2), 0, TvUiKit.dp(this@MainActivity, 8))
    }

    private fun buildVaultCard(
        eyebrowText: String,
        title: String,
        subtitle: String,
        iconPath: String = "",
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                TvUiKit.dp(this@MainActivity, 232),
                TvUiKit.dp(this@MainActivity, 86)
            ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 10) }
            setPadding(
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 10),
                TvUiKit.dp(this@MainActivity, 14),
                TvUiKit.dp(this@MainActivity, 10)
            )
            TvUiKit.applyCardFocusEffect(this, 1.03f)
            setOnClickListener { onClick() }
        }
        if (iconPath.isNotBlank() && File(iconPath).isFile) {
            card.addView(ImageView(this).apply {
                val sz = TvUiKit.dp(this@MainActivity, 42)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = TvUiKit.dp(this@MainActivity, 10) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                TvUiKit.applyRoundedClip(this, 8f)
                load(File(iconPath))
            })
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TvUiKit.eyebrow(this, eyebrowText).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
        })
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = XirtusFonts.semiBold(this@MainActivity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = XirtusFonts.regular(this@MainActivity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(textCol)
        return card
    }

    // =========================================================================
    // 6. PASSPORT ↗ TAB — TWO-COLUMN XIRTUS 'YOUR PASSPORT' LAYOUT (V2 LOCKBOX)
    // =========================================================================
    private fun createPassportTabView(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val outerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 30),
                TvUiKit.dp(this@MainActivity, 20),
                TvUiKit.dp(this@MainActivity, 30),
                TvUiKit.dp(this@MainActivity, 24)
            )
        }

        // LEFT COLUMN: Editorial narrative + Node RAM telemetry + Sliding Window + YouTube Enhancements
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 34)
            }
        }

        leftCol.addView(TvUiKit.eyebrow(this, "YOUR MEDIA VAULT, WHEREVER YOU GO").apply {
            setPadding(0, 0, 0, TvUiKit.dp(this@MainActivity, 16))
        })
        leftCol.addView(TvUiKit.eyebrow(this, "THE CONNECTED COMPANION"))
        leftCol.addView(TvUiKit.displayTitle(this, "Private by design.", sp = 31f).apply {
            setPadding(0, TvUiKit.dp(this@MainActivity, 4), 0, TvUiKit.dp(this@MainActivity, 10))
        })
        leftCol.addView(TvUiKit.bodyText(
            this,
            "BrootLite stays clean on your projector while your private Stremio subscriptions, multi-server Jellyfin libraries, YouTube & Twitch accounts, external Kodi repositories, and Torznab indexers travel inside your Zero-Knowledge E2EE Leeloo Multipass.",
            sp = 13.5f
        ))

        leftCol.addView(TvUiKit.hairlineDivider(this, topMarginDp = 18, bottomMarginDp = 16))

        // Active Hardware Node + Live RAM Telemetry (Active Hardware Node + Live RAM Telemetry)
        val nodeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nodeRow.addView(View(this).apply {
            val sz = TvUiKit.dp(this@MainActivity, 36)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = TvUiKit.dp(this@MainActivity, 12) }
            background = XirtusEmblemDrawable(this@MainActivity)
        })
        val nodeTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nodeTextCol.addView(TvUiKit.eyebrow(this, "ACTIVE HARDWARE NODE"))
        nodeTextCol.addView(TextView(this).apply {
            text = "${android.os.Build.MODEL} · ARMv7 32-bit"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = XirtusFonts.semiBold(this@MainActivity)
        })
        nodeRow.addView(nodeTextCol)
        nodeRow.addView(TvUiKit.createPillButton(this, "Kill :pyaddon", compact = true) {
            PyAddonClient.killAddonProcessNow(this)
            Toast.makeText(this, ":pyaddon terminated (0 MB)", Toast.LENGTH_SHORT).show()
            updateRamMeterOnce()
        })
        leftCol.addView(nodeRow)

        ramMeterText = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = XirtusFonts.monoRegular(this@MainActivity)
            setPadding(0, TvUiKit.dp(this@MainActivity, 9), 0, TvUiKit.dp(this@MainActivity, 14))
            text = "Measuring memory telemetry..."
        }
        leftCol.addView(ramMeterText)

        // Compact Sliding-Window & Torznab controls
        leftCol.addView(TvUiKit.eyebrow(this, "SLIDING-WINDOW ENGINE & TORZNAB").apply {
            setPadding(0, 0, 0, TvUiKit.dp(this@MainActivity, 8))
        })
        val engineRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = TvUiKit.dp(this@MainActivity, 8)
            }
        }
        preBufferBtn = TvUiKit.createPillButton(this, "Pre-buffer: 16 MB", compact = true) {
            val cfg = AppPrefs.getTorrentConfig(this)
            val cycle = listOf(8, 12, 16, 24)
            val next = cycle[(cycle.indexOf(cfg.preBufferMb) + 1) % cycle.size]
            AppPrefs.saveTorrentConfig(this, cfg.copy(preBufferMb = next))
            refreshPassportTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }
        windowMbBtn = TvUiKit.createPillButton(this, "Window: 28 MB", compact = true) {
            val cfg = AppPrefs.getTorrentConfig(this)
            val cycle = listOf(20, 28, 40, 56)
            val next = cycle[(cycle.indexOf(cfg.slidingWindowMb) + 1) % cycle.size]
            AppPrefs.saveTorrentConfig(this, cfg.copy(slidingWindowMb = next))
            refreshPassportTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }
        maxConnBtn = TvUiKit.createPillButton(this, "Peers: 120", compact = true) {
            val cfg = AppPrefs.getTorrentConfig(this)
            val cycle = listOf(60, 90, 120, 160)
            val next = cycle[(cycle.indexOf(cfg.maxConnections) + 1) % cycle.size]
            AppPrefs.saveTorrentConfig(this, cfg.copy(maxConnections = next))
            refreshPassportTab()
        }
        engineRow1.addView(preBufferBtn)
        engineRow1.addView(windowMbBtn)
        engineRow1.addView(maxConnBtn)
        leftCol.addView(engineRow1)

        val engineRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        torznabBtn = TvUiKit.createPillButton(this, "Torznab Indexer", compact = true) {
            promptTorznabConfig()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }
        val purgeBtn = TvUiKit.createPillButton(this, "Purge Swarm Cache", compact = true) {
            runCatching { File(cacheDir, "torrents").deleteRecursively() }
            Toast.makeText(this, "Temporary torrent cache purged", Toast.LENGTH_SHORT).show()
            refreshPassportTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }
        val inspectAddonsBtn = TvUiKit.createPillButton(this, "Installed Manifests", compact = true) {
            val summary = AppPrefs.getInstalledAddons(this).joinToString("\n\n") { a ->
                val res = a.resources.joinToString(", ") { it.name }
                "• ${a.name} (v${a.version}) [${a.catalogs.size} catalogs · $res]\n  ${a.transportUrl}"
            }
            TvUiKit.showTextViewerDialog(this, "Installed Stremio Addons", summary)
        }
        engineRow2.addView(torznabBtn)
        engineRow2.addView(purgeBtn)
        engineRow2.addView(inspectAddonsBtn)
        leftCol.addView(engineRow2)

        // YouTube & Ad-Free Creator Streaming Enhancement Toggles
        leftCol.addView(TvUiKit.eyebrow(this, "YOUTUBE & STREAMING ENHANCEMENTS").apply {
            setPadding(0, TvUiKit.dp(this@MainActivity, 14), 0, TvUiKit.dp(this@MainActivity, 8))
        })
        val ytRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(this@MainActivity, 8) }
        }
        ytSponsorBlockBtn = TvUiKit.createPillButton(this, "SponsorBlock: ON", selected = true, compact = true) {
            val next = !AppPrefs.isSponsorBlockEnabled(this)
            AppPrefs.setSponsorBlockEnabled(this, next)
            refreshPassportTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 8) }
        }
        ytHistorySyncBtn = TvUiKit.createPillButton(this, "YT History: ON", selected = true, compact = true) {
            val next = !AppPrefs.isYouTubeWatchHistoryEnabled(this)
            AppPrefs.setYouTubeWatchHistoryEnabled(this, next)
            refreshPassportTab()
        }
        ytRow1.addView(ytSponsorBlockBtn)
        ytRow1.addView(ytHistorySyncBtn)
        leftCol.addView(ytRow1)

        val ytRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        ytDislikesBtn = TvUiKit.createPillButton(this, "Dislikes: ON", selected = true, compact = true) {
            val next = !AppPrefs.isReturnYouTubeDislikeEnabled(this)
            AppPrefs.setReturnYouTubeDislikeEnabled(this, next)
            refreshPassportTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 8) }
        }
        ytDeArrowBtn = TvUiKit.createPillButton(this, "DeArrow: OFF", selected = false, compact = true) {
            val next = !AppPrefs.isDeArrowEnabled(this)
            AppPrefs.setDeArrowEnabled(this, next)
            refreshPassportTab()
            if (activeTab == NavTab.STREAMING && activeStreamingPlatform == StreamingPlatform.YOUTUBE) {
                reloadStreamingFeedFromScratch()
            }
        }
        ytRow2.addView(ytDislikesBtn)
        ytRow2.addView(ytDeArrowBtn)
        leftCol.addView(ytRow2)

        // RIGHT COLUMN: Signature Indigo Companion Card ('XIRTUS / PASSPORT')
        val passportCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F141F"))
                cornerRadius = TvUiKit.dpF(this@MainActivity, 20f)
                setStroke(TvUiKit.dp(this@MainActivity, 1).coerceAtLeast(1), XirtusPalette.INDIGO_BORDER)
            }
        }

        // Top Indigo Header with Concentric Arc Watermark Rings (0 MB bitmap RAM!)
        val indigoHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = XirtusPassportHeaderDrawable(this@MainActivity, topCornerRadiusDp = 20f)
            setPadding(
                TvUiKit.dp(this@MainActivity, 22),
                TvUiKit.dp(this@MainActivity, 18),
                TvUiKit.dp(this@MainActivity, 22),
                TvUiKit.dp(this@MainActivity, 18)
            )
        }
        indigoHeader.addView(TvUiKit.eyebrow(this, "XIRTUS / PASSPORT").apply {
            setTextColor(Color.parseColor("#93A6C2"))
        })
        passportIdentityName = TextView(this).apply {
            text = "Unlinked Companion"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = XirtusFonts.bold(this@MainActivity)
            letterSpacing = -0.02f
            setPadding(0, TvUiKit.dp(this@MainActivity, 6), 0, TvUiKit.dp(this@MainActivity, 2))
        }
        passportIdentityHandle = TextView(this).apply {
            text = "@companion:androidtv.local"
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = XirtusFonts.monoRegular(this@MainActivity)
        }
        passportIdentitySummary = TextView(this).apply {
            text = "3 Addons · 33 Catalogs Ready"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = XirtusFonts.semiBold(this@MainActivity)
            setPadding(0, TvUiKit.dp(this@MainActivity, 10), 0, 0)
        }
        indigoHeader.addView(passportIdentityName)
        indigoHeader.addView(passportIdentityHandle)
        indigoHeader.addView(passportIdentitySummary)
        passportCard.addView(indigoHeader)
        passportCard.addView(TvUiKit.hairlineDivider(this, color = Color.parseColor("#22FFFFFF")))

        // Section 1: STREMIO SUBSCRIPTIONS
        passportStremioStatus = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.regular(this@MainActivity)
        }
        passportCard.addView(buildPassportCardSection(
            eyebrowLabel = "STREMIO SUBSCRIPTIONS",
            valueView = passportStremioStatus,
            actions = listOf(
                "Pair QR" to { startStremioQrPairingFlow() },
                "+ Manifest" to { promptInstallManifestUrl() }
            )
        ))
        passportCard.addView(TvUiKit.hairlineDivider(this))

        // Section 2: LEELOO MULTIPASS SYNC (v2 Zero-Knowledge ECDH + AES-256-GCM Lockbox)
        passportMultipassStatus = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.regular(this@MainActivity)
        }
        passportCard.addView(buildPassportStackedSection(
            eyebrowLabel = "LEELOO MULTIPASS / ZERO-KNOWLEDGE VAULT",
            valueView = passportMultipassStatus,
            actions = listOf(
                "Pair Multipass" to { startMultipassQrPairingFlow() },
                "Pull Vault" to { pullMultipassVaultNow() },
                "Push Setup" to { pushMultipassVaultNow() },
                "Endpoint / Token" to { promptMultipassConfig() }
            )
        ))
        passportCard.addView(TvUiKit.hairlineDivider(this))

        // Section 3: JELLYFIN SERVERS (strictly isolated multi-server profiles)
        passportJellyfinStatus = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.regular(this@MainActivity)
        }
        passportCard.addView(buildPassportCardSection(
            eyebrowLabel = "JELLYFIN SERVERS",
            valueView = passportJellyfinStatus,
            actions = listOf(
                "Open Jellyfin" to { switchTab(NavTab.JELLYFIN) },
                "+ Server" to { showAddJellyfinServerDialog() }
            )
        ))
        passportCard.addView(TvUiKit.hairlineDivider(this))

        // Section 4: CREATOR STREAMING (YouTube · Twitch · Odysee · Rumble)
        passportStreamingStatus = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.regular(this@MainActivity)
        }
        passportCard.addView(buildPassportCardSection(
            eyebrowLabel = "CREATOR STREAMING",
            valueView = passportStreamingStatus,
            actions = listOf(
                "Open Streaming" to { switchTab(NavTab.STREAMING) }
            )
        ))
        passportCard.addView(TvUiKit.hairlineDivider(this))

        // Section 5: EXTERNAL KODI REPOSITORIES
        passportKodiStatus = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.regular(this@MainActivity)
        }
        passportCard.addView(buildPassportCardSection(
            eyebrowLabel = "EXTERNAL KODI REPOSITORIES",
            valueView = passportKodiStatus,
            actions = listOf(
                "Open Kodi" to { switchTab(NavTab.VAULT) }
            )
        ))
        passportCard.addView(TvUiKit.hairlineDivider(this))

        // Section 6: TORRENT ENGINE CACHE
        passportTorrentStatus = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.regular(this@MainActivity)
        }
        passportCard.addView(buildPassportCardSection(
            eyebrowLabel = "TORRENT ENGINE CACHE",
            valueView = passportTorrentStatus,
            actions = emptyList()
        ))
        passportCard.addView(TvUiKit.hairlineDivider(this))

        // Bottom full-width button matching 'Sign out' in the Xirtus Passport screenshot
        val footerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 18),
                TvUiKit.dp(this@MainActivity, 12),
                TvUiKit.dp(this@MainActivity, 18),
                TvUiKit.dp(this@MainActivity, 14)
            )
        }
        passportBottomActionBtn = TvUiKit.createPillButton(this, "Pair Stremio Cloud via QR") {
            val auth = AppPrefs.getStremioAuthKey(this)
            if (auth != null) {
                TvUiKit.showConfirmDialog(
                    this,
                    "Sign Out of Passport?",
                    "Unlink your Stremio Cloud key from this projector? Default Streaming Catalogs & Cinemeta remain active.",
                    yesText = "Sign out"
                ) { yes ->
                    if (yes) {
                        AppPrefs.setStremioAccount(this, null, null)
                        refreshAccountIndicators()
                        refreshPassportTab()
                        Toast.makeText(this, "Signed out of Stremio Cloud", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                startStremioQrPairingFlow()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        footerBox.addView(passportBottomActionBtn)
        passportCard.addView(footerBox)

        outerRow.addView(leftCol)
        outerRow.addView(passportCard)
        scroll.addView(outerRow)
        return scroll
    }

    private fun buildPassportCardSection(
        eyebrowLabel: String,
        valueView: TextView,
        actions: List<Pair<String, () -> Unit>>
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 20),
                TvUiKit.dp(this@MainActivity, 11),
                TvUiKit.dp(this@MainActivity, 18),
                TvUiKit.dp(this@MainActivity, 11)
            )
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = TvUiKit.dp(this@MainActivity, 8)
            }
        }
        left.addView(TvUiKit.eyebrow(this, eyebrowLabel))
        valueView.setPadding(0, TvUiKit.dp(this, 3), 0, 0)
        left.addView(valueView)
        row.addView(left)

        for ((label, cb) in actions) {
            row.addView(TvUiKit.createPillButton(this, label, compact = true, onClick = cb).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = TvUiKit.dp(this@MainActivity, 6) }
            })
        }
        return row
    }

    private fun buildPassportStackedSection(
        eyebrowLabel: String,
        valueView: TextView,
        actions: List<Pair<String, () -> Unit>>
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                TvUiKit.dp(this@MainActivity, 20),
                TvUiKit.dp(this@MainActivity, 12),
                TvUiKit.dp(this@MainActivity, 18),
                TvUiKit.dp(this@MainActivity, 12)
            )
        }
        col.addView(TvUiKit.eyebrow(this, eyebrowLabel))
        valueView.setPadding(0, TvUiKit.dp(this, 3), 0, TvUiKit.dp(this, 9))
        col.addView(valueView)

        val actionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.forEachIndexed { idx, (label, cb) ->
            val btn = TvUiKit.createPillButton(this, label, selected = (idx == 0), compact = true, onClick = cb).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = TvUiKit.dp(this@MainActivity, 6) }
            }
            actionRow.addView(btn)
        }
        actionScroll.addView(actionRow)
        col.addView(actionScroll)
        return col
    }

    private fun promptInstallManifestUrl() {
        TvUiKit.showInputDialog(
            context = this,
            title = "Install Stremio Addon Manifest URL",
            hint = "https://.../manifest.json or stremio://...",
            onSubmit = { url ->
                if (url.isNotBlank()) {
                    lifecycleScope.launch {
                        val addon = StremioApiClient.installAddonFromUrl(this@MainActivity, url)
                        if (addon != null) {
                            Toast.makeText(this@MainActivity, "Installed ${addon.name}", Toast.LENGTH_SHORT).show()
                            refreshAccountIndicators()
                            refreshPassportTab()
                            rebuildBoardRows(clearCache = false)
                            rebuildDiscoverCatalogChoices(keepSelection = true, triggerLoad = false)
                        } else {
                            Toast.makeText(this@MainActivity, "Could not fetch addon manifest", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    // =========================================================================
    // LEELOO MULTIPASS /v1/media-sync V2 LOCKBOX PAIRING, PULL VAULT & PUSH SETUP
    // =========================================================================
    private fun startMultipassQrPairingFlow(forcePromptOrigin: Boolean = false) {
        val cur = AppPrefs.getMultipassProfile(this)
        val hasLiveOrigin = cur.serverUrl.isNotBlank() && !cur.serverUrl.substringBefore('?').endsWith(".json", true)
        if (!forcePromptOrigin && hasLiveOrigin) {
            runMultipassQrPairingWithOrigin(cur.serverUrl)
            return
        }
        TvUiKit.showInputDialog(
            context = this,
            title = "Leeloo Multipass Server Base URL",
            initialValue = MultipassSyncClient.normalizeOrigin(cur.serverUrl),
            hint = "https://multipass.xirtus.com",
            onSubmit = { enteredUrl ->
                val origin = MultipassSyncClient.normalizeOrigin(enteredUrl)
                if (origin.isBlank()) {
                    Toast.makeText(this, "Enter your Multipass server URL to generate a QR code", Toast.LENGTH_SHORT).show()
                    return@showInputDialog
                }
                AppPrefs.saveMultipassProfile(this, cur.copy(serverUrl = origin))
                refreshAccountIndicators()
                if (activeTab == NavTab.PASSPORT) refreshPassportTab()
                runMultipassQrPairingWithOrigin(origin)
            }
        )
    }

    private fun runMultipassQrPairingWithOrigin(rawOrigin: String) {
        val origin = MultipassSyncClient.normalizeOrigin(rawOrigin)
        if (origin.isBlank()) {
            startMultipassQrPairingFlow(forcePromptOrigin = true)
            return
        }
        qrPollJob?.cancel()
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Requesting Multipass pairing code from $origin...", Toast.LENGTH_SHORT).show()
            val startRes = MultipassSyncClient.startDevicePairing(origin)
            val session = startRes.getOrElse { err ->
                Toast.makeText(
                    this@MainActivity,
                    err.message ?: "Unable to reach Multipass server",
                    Toast.LENGTH_LONG
                ).show()
                startMultipassQrPairingFlow(forcePromptOrigin = true)
                return@launch
            }

            val verifyUrl = if (session.verificationUrl.startsWith("http://", true) ||
                session.verificationUrl.startsWith("https://", true)
            ) {
                session.verificationUrl.substringBefore('#')
            } else {
                "$origin/${session.verificationUrl.trimStart('/').substringBefore('#')}"
            }
            val qrUrl = session.qrVerificationUrl.ifBlank {
                session.verificationUriComplete.ifBlank { verifyUrl }
            }

            var qrDialog: AlertDialog? = null
            qrDialog = TvUiKit.showQrModal(
                context = this@MainActivity,
                code = session.userCode,
                linkUrl = verifyUrl,
                qrTargetUrl = qrUrl,
                eyebrowLabel = "XIRTUS PASSPORT / ZERO-KNOWLEDGE ECDH PAIR",
                title = "Pair Leeloo Multipass Vault"
            ) {
                qrPollJob?.cancel()
            }

            val pollDelayMs = session.intervalSeconds.coerceIn(2, 10) * 1_000L
            val expiresMs = session.expiresIn.coerceIn(60, 900) * 1_000L

            qrPollJob = lifecycleScope.launch {
                val deadline = System.currentTimeMillis() + expiresMs
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(pollDelayMs)
                    val pollRes = MultipassSyncClient.pollDevicePairing(
                        context = this@MainActivity,
                        originUrl = origin,
                        pairingId = session.pairingId,
                        pollSecret = session.pollSecret
                    ).getOrNull() ?: continue

                    if (pollRes.status.equals("approved", ignoreCase = true) && !pollRes.deviceToken.isNullOrBlank()) {
                        val prev = AppPrefs.getMultipassProfile(this@MainActivity)
                        val hostTag = origin.removePrefix("https://").removePrefix("http://").substringBefore('/')
                        val updated = prev.copy(
                            serverUrl = origin,
                            pairToken = pollRes.deviceToken,
                            deviceId = pollRes.deviceId ?: prev.deviceId,
                            scope = pollRes.scope ?: session.requestedScope,
                            handle = prev.handle.ifBlank { "@vault:$hostTag" }
                        )
                        AppPrefs.saveMultipassProfile(this@MainActivity, updated)
                        qrDialog?.dismiss()
                        refreshAccountIndicators()
                        if (activeTab == NavTab.PASSPORT) refreshPassportTab()

                        val vmkNotice = if (pollRes.vmkUnwrapped) {
                            "\nZero-Knowledge Vault Key sealed in Hardware Keystore ✓"
                        } else {
                            ""
                        }
                        TvUiKit.showConfirmDialog(
                            context = this@MainActivity,
                            title = "Leeloo Multipass Paired",
                            message = "Scoped device token (${pollRes.deviceToken.take(10)}…) saved for $hostTag.$vmkNotice\nPull your private Media Vault now?",
                            yesText = "Pull Vault",
                            noText = "Later"
                        ) { yes ->
                            if (yes) {
                                pullMultipassVaultNow()
                            }
                        }
                        return@launch
                    }
                }
                qrDialog?.dismiss()
                Toast.makeText(this@MainActivity, "Multipass pairing session expired", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptMultipassConfig() {
        val cur = AppPrefs.getMultipassProfile(this)
        TvUiKit.showInputDialog(
            context = this,
            title = "Multipass Server or /v1/media-sync Endpoint URL",
            initialValue = cur.serverUrl,
            hint = "https://multipass.xirtus.com or .../v1/media-sync",
            onSubmit = { rawUrl ->
                val cleanUrl = if (rawUrl.substringBefore('?').endsWith(".json", true)) {
                    rawUrl.trim()
                } else {
                    MultipassSyncClient.normalizeOrigin(rawUrl)
                }
                TvUiKit.showInputDialog(
                    context = this,
                    title = "Scoped Device Token (mpd_...) or @handle",
                    initialValue = cur.pairToken.ifBlank { cur.handle },
                    hint = "mpd_... token (or @handle, or leave blank)",
                    onSubmit = { tokenOrHandle ->
                        val trimmed = tokenOrHandle.trim()
                        val handle = when {
                            trimmed.startsWith("@") -> trimmed
                            else -> cur.handle
                        }
                        val token = when {
                            trimmed.startsWith("@") -> cur.pairToken
                            else -> trimmed
                        }
                        AppPrefs.saveMultipassProfile(
                            this,
                            cur.copy(serverUrl = cleanUrl, handle = handle, pairToken = token)
                        )
                        refreshAccountIndicators()
                        if (activeTab == NavTab.PASSPORT) refreshPassportTab()
                        Toast.makeText(this, "Multipass endpoint & token saved", Toast.LENGTH_SHORT).show()
                        if (cleanUrl.isNotBlank()) {
                            pullMultipassVaultNow()
                        }
                    }
                )
            }
        )
    }

    private fun pullMultipassVaultNow(passphrase: String? = null) {
        val mp = AppPrefs.getMultipassProfile(this)
        if (mp.serverUrl.isBlank()) {
            TvUiKit.showSelectDialog(
                context = this,
                title = "Connect Leeloo Multipass Vault",
                options = listOf(
                    "Pair via QR Code (Recommended)",
                    "Enter Endpoint URL & Token Manually"
                ),
                selectedIndex = 0,
                eyebrowLabel = "LEELOO MULTIPASS / SETUP"
            ) { idx ->
                when (idx) {
                    0 -> startMultipassQrPairingFlow(forcePromptOrigin = true)
                    1 -> promptMultipassConfig()
                }
            }
            return
        }

        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Pulling Leeloo Multipass vault...", Toast.LENGTH_SHORT).show()
            val res = MultipassSyncClient.syncFromEndpoint(
                context = this@MainActivity,
                endpointUrl = mp.serverUrl,
                pairToken = mp.pairToken,
                passphrase = passphrase
            )
            if (res.needsPassphrase) {
                if (passphrase != null) {
                    Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_SHORT).show()
                }
                TvUiKit.showInputDialog(
                    context = this@MainActivity,
                    title = if (passphrase == null) "Unlock Zero-Knowledge E2EE Vault" else "Retry Vault Passphrase",
                    hint = "Enter your AES-256-GCM vault passphrase...",
                    hidden = true,
                    onSubmit = { enteredPass ->
                        if (enteredPass.isNotBlank()) {
                            pullMultipassVaultNow(enteredPass)
                        }
                    }
                )
                return@launch
            }

            Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
            if (res.ok) {
                refreshAccountIndicators()
                refreshContinueWatchingRow()
                rebuildBoardRows(clearCache = true)
                rebuildDiscoverCatalogChoices(keepSelection = true, triggerLoad = activeTab == NavTab.DISCOVER)
                reloadJellyfinServersState(triggerNetwork = activeTab == NavTab.JELLYFIN)
                refreshStreamingAccountPill()
                if (activeTab == NavTab.STREAMING) reloadStreamingFeedFromScratch()
                refreshLibraryGrid()
                if (activeTab == NavTab.VAULT) populateVaultHub()
                if (activeTab == NavTab.PASSPORT) refreshPassportTab()
            }
        }
    }

    private fun pushMultipassVaultNow(passphrase: String? = null) {
        val mp = AppPrefs.getMultipassProfile(this)
        val hasRemotePair = mp.serverUrl.isNotBlank() &&
            !mp.serverUrl.substringBefore('?').endsWith(".json", true) &&
            mp.pairToken.isNotBlank()

        // Zero-passphrase v2 Lockbox push when VMK is already sealed in Hardware Keystore!
        if (hasRemotePair && (passphrase != null || MultipassSyncClient.hasLocalVmk(this))) {
            lifecycleScope.launch {
                Toast.makeText(
                    this@MainActivity,
                    "Encrypting & pushing setup to Multipass v2 vault...",
                    Toast.LENGTH_SHORT
                ).show()
                val res = MultipassSyncClient.pushCurrentSetupToVault(
                    context = this@MainActivity,
                    originUrl = mp.serverUrl,
                    pairToken = mp.pairToken,
                    passphrase = passphrase
                )
                if (res.needsPassphrase) {
                    Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_SHORT).show()
                    promptRemotePushPassphraseDialog(isRetry = passphrase != null)
                    return@launch
                }
                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
                if (res.ok) {
                    refreshAccountIndicators()
                    if (activeTab == NavTab.PASSPORT) refreshPassportTab()
                }
            }
            return
        }

        if (hasRemotePair) {
            promptRemotePushPassphraseDialog(isRetry = false)
            return
        }

        TvUiKit.showInputDialog(
            context = this,
            title = "Export Local Vault Snapshot · Optional E2EE Passphrase",
            hint = "8+ chars for v2 Zero-Knowledge E2EE, or leave blank",
            hidden = true,
            onSubmit = { rawPass ->
                val cleanPass = rawPass.trim().takeIf { it.isNotEmpty() }
                if (cleanPass != null && cleanPass.length < 8) {
                    Toast.makeText(
                        this,
                        "E2EE passphrase must be at least 8 characters (or leave empty)",
                        Toast.LENGTH_LONG
                    ).show()
                    pushMultipassVaultNow()
                    return@showInputDialog
                }

                lifecycleScope.launch {
                    val exported = MultipassSyncClient.exportLocalVaultSnapshot(this@MainActivity, cleanPass)
                    exported.onSuccess { outFile ->
                        val hasV2 = cleanPass != null || MultipassSyncClient.hasLocalVmk(this@MainActivity)
                        val modeLabel = if (hasV2) "v2 Zero-Knowledge E2EE" else "JSON"
                        Toast.makeText(
                            this@MainActivity,
                            "Saved $modeLabel vault snapshot: ${outFile.name}",
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure { err ->
                        Toast.makeText(
                            this@MainActivity,
                            "Snapshot export failed: ${err.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    private fun promptRemotePushPassphraseDialog(isRetry: Boolean) {
        TvUiKit.showInputDialog(
            context = this,
            title = if (isRetry) "Retry E2EE Vault Passphrase (8+ chars)" else "Vault E2EE Passphrase (8+ chars)",
            hint = "Enter 8+ character AES-256-GCM passphrase for v2 recovery slot...",
            hidden = true,
            onSubmit = { rawPass ->
                val cleanPass = rawPass.trim()
                if (cleanPass.length < 8) {
                    Toast.makeText(
                        this,
                        "Zero-Knowledge v2 vaults require an 8+ character recovery passphrase",
                        Toast.LENGTH_LONG
                    ).show()
                    promptRemotePushPassphraseDialog(isRetry = true)
                    return@showInputDialog
                }
                pushMultipassVaultNow(cleanPass)
            }
        )
    }

    private fun promptTorznabConfig() {
        val cfg = AppPrefs.getTorrentConfig(this)
        TvUiKit.showInputDialog(
            context = this,
            title = "Torznab Endpoint URL (Prowlarr / Jackett)",
            initialValue = cfg.torznabUrl,
            hint = "http://192.168.1.x:9696/1/api",
            onSubmit = { url ->
                TvUiKit.showInputDialog(
                    context = this,
                    title = "Torznab API Key (optional)",
                    initialValue = cfg.torznabApiKey,
                    hint = "API key...",
                    onSubmit = { key ->
                        AppPrefs.saveTorrentConfig(this, cfg.copy(torznabUrl = url, torznabApiKey = key))
                        refreshPassportTab()
                        Toast.makeText(this, "Torznab settings saved", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
    }

    private fun refreshPassportTab() {
        val auth = AppPrefs.getStremioAuthKey(this)
        val email = AppPrefs.getStremioEmail(this)
        val mp = AppPrefs.getMultipassProfile(this)
        val addons = AppPrefs.getInstalledAddons(this)
        val totalCatalogs = addons.sumOf { a -> a.catalogs.count { it.isBrowseSupported } }
        val jfServers = AppPrefs.getJellyfinServers(this)
        val jfAuthed = jfServers.count { it.isAuthenticated }
        val ytAcc = AppPrefs.getYouTubeOAuth(this)
        val twAcc = AppPrefs.getTwitchDeviceOAuth(this)?.takeIf { it.isActive }
        val creatorFollows = AppPrefs.getCreatorFollows(this).count { it.isActive }
        val hasVmk = MultipassSyncClient.hasLocalVmk(this)

        val primaryName = when {
            mp.handle.isNotBlank() -> mp.handle.removePrefix("@").substringBefore(':')
            !email.isNullOrBlank() -> email.substringBefore('@')
            ytAcc != null && ytAcc.accountLabel.isNotBlank() -> ytAcc.accountLabel
            else -> "Unlinked Companion"
        }
        val handleLine = when {
            mp.handle.isNotBlank() -> mp.handle
            !email.isNullOrBlank() -> email
            else -> "@companion:androidtv.local"
        }

        val summaryParts = buildList {
            add("${addons.size} Addons")
            add("$totalCatalogs Catalogs")
            if (jfServers.isNotEmpty()) add("${jfServers.size} Jellyfin")
            if (creatorFollows > 0) add("$creatorFollows Follows")
        }

        passportIdentityName.text = primaryName
        passportIdentityHandle.text = handleLine
        passportIdentitySummary.text = summaryParts.joinToString(" · ")

        passportStremioStatus.text = if (auth != null) {
            "${addons.size} addons synced (${email ?: "Stremio Cloud"})"
        } else {
            "${addons.size} built-in addons (Cinemeta + 15 Streaming Networks)"
        }

        passportMultipassStatus.text = if (mp.serverUrl.isNotBlank()) {
            val host = mp.serverUrl.removePrefix("https://").removePrefix("http://").take(30)
            val authBadge = when {
                mp.pairToken.startsWith("mpd_") -> " · Paired ✓"
                mp.pairToken.isNotBlank() -> " · Token ✓"
                else -> " · Unpaired"
            }
            val vmkBadge = if (hasVmk) " · VMK ✓" else ""
            val revBadge = if (mp.vaultRevision > 0) {
                val modeTag = if (mp.vaultMode.contains("e2ee", true) || hasVmk) "Zero-Knowledge E2EE" else "Legacy"
                " · rev #${mp.vaultRevision} ($modeTag)"
            } else if (hasVmk) {
                " · Zero-Knowledge E2EE"
            } else {
                ""
            }
            "$host$authBadge$vmkBadge$revBadge"
        } else {
            "Unpaired · Scan #pk ECDH QR or enter /v1/media-sync endpoint"
        }

        passportJellyfinStatus.text = if (jfServers.isEmpty()) {
            "No servers configured · LAN UDP 7359 & QuickConnect ready"
        } else {
            val names = jfServers.take(2).joinToString(", ") { it.name }
            val extra = if (jfServers.size > 2) " +${jfServers.size - 2}" else ""
            "$names$extra · $jfAuthed/${jfServers.size} authenticated (strictly isolated)"
        }

        val ytTag = ytAcc?.accountLabel?.ifBlank { "Signed in" } ?: "Guest"
        val twTag = twAcc?.let { it.displayName.ifBlank { it.login }.ifBlank { "Signed in" } } ?: "Guest"
        passportStreamingStatus.text = "YouTube: $ytTag · Twitch: $twTag · $creatorFollows followed"

        val kodiAddons = PyAddonClient.scanInstalledVideoAddonsFast(this)
        val customSites = AppPrefs.readCustomSites(this)
        passportKodiStatus.text = if (kodiAddons.isEmpty() && customSites.isEmpty()) {
            "kodi_env ready · 0 MB idle Python footprint"
        } else {
            "${kodiAddons.size} video addons · ${customSites.size} custom modules"
        }

        val cfg = AppPrefs.getTorrentConfig(this)
        val cacheBytes = runCatching {
            File(cacheDir, "torrents").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        val cacheMb = cacheBytes / (1024L * 1024L)
        passportTorrentStatus.text = "${cfg.preBufferMb} MB pre-buffer · ${cfg.slidingWindowMb} MB window · ${cacheMb} MB on disk"

        preBufferBtn.text = "Pre-buffer: ${cfg.preBufferMb} MB"
        windowMbBtn.text = "Window: ${cfg.slidingWindowMb} MB"
        maxConnBtn.text = "Peers: ${cfg.maxConnections}"
        torznabBtn.text = if (cfg.torznabUrl.isNotBlank()) "Torznab: Active ✓" else "Torznab Indexer"

        val sbOn = AppPrefs.isSponsorBlockEnabled(this)
        val histOn = AppPrefs.isYouTubeWatchHistoryEnabled(this)
        val rydOn = AppPrefs.isReturnYouTubeDislikeEnabled(this)
        val deArrowOn = AppPrefs.isDeArrowEnabled(this)
        ytSponsorBlockBtn.text = "SponsorBlock: ${if (sbOn) "ON" else "OFF"}"
        TvUiKit.setPillSelected(ytSponsorBlockBtn, sbOn)
        ytHistorySyncBtn.text = "YT History: ${if (histOn) "ON" else "OFF"}"
        TvUiKit.setPillSelected(ytHistorySyncBtn, histOn)
        ytDislikesBtn.text = "Dislikes: ${if (rydOn) "ON" else "OFF"}"
        TvUiKit.setPillSelected(ytDislikesBtn, rydOn)
        ytDeArrowBtn.text = "DeArrow: ${if (deArrowOn) "ON" else "OFF"}"
        TvUiKit.setPillSelected(ytDeArrowBtn, deArrowOn)

        passportBottomActionBtn.text = if (auth != null) "Sign out" else "Pair Stremio Cloud via QR"

        updateRamMeterOnce()
    }

    private fun startRamTicker() {
        ramTickerJob?.cancel()
        ramTickerJob = lifecycleScope.launch {
            while (isActive && activeTab == NavTab.PASSPORT) {
                updateRamMeterOnce()
                delay(1_200L)
            }
        }
    }

    private fun updateRamMeterOnce() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val myPid = Process.myPid()
        val pssMainKb = am.getProcessMemoryInfo(intArrayOf(myPid)).firstOrNull()?.totalPss ?: 0
        val mainMb = pssMainKb / 1024.0

        val running = am.runningAppProcesses.orEmpty()
        val pyProc = running.firstOrNull { it.processName.endsWith(":pyaddon") }
        val pyStatus = if (pyProc != null) {
            val pyKb = am.getProcessMemoryInfo(intArrayOf(pyProc.pid)).firstOrNull()?.totalPss ?: 0
            String.format(Locale.US, "%.1f MB", pyKb / 1024.0)
        } else {
            "0.0 MB"
        }

        val poProc = running.firstOrNull { it.processName.endsWith(":potoken") }
        val poStatus = if (poProc != null) {
            val poKb = am.getProcessMemoryInfo(intArrayOf(poProc.pid)).firstOrNull()?.totalPss ?: 0
            String.format(Locale.US, "%.1f MB", poKb / 1024.0)
        } else {
            "0.0 MB"
        }

        val availMb = memInfo.availMem / (1024L * 1024L)
        val totalMb = memInfo.totalMem / (1024L * 1024L)

        ramMeterText.text = String.format(
            Locale.US,
            "APP %.1f MB · :PYADDON %s · :POTOKEN %s · AVAIL %d / %d MB",
            mainMb,
            pyStatus,
            poStatus,
            availMb,
            totalMb
        )
    }

    // =========================================================================
    // STREMIO QR PAIRING FLOW
    // =========================================================================
    private fun startStremioQrPairingFlow() {
        qrPollJob?.cancel()
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Generating Stremio QR link code...", Toast.LENGTH_SHORT).show()
            val session = StremioApiClient.createQrLinkSession()
            if (session == null) {
                Toast.makeText(this@MainActivity, "Unable to reach link.stremio.com", Toast.LENGTH_LONG).show()
                return@launch
            }

            var qrDialog: AlertDialog? = null
            qrDialog = TvUiKit.showQrModal(
                context = this@MainActivity,
                code = session.code,
                linkUrl = session.linkUrl,
                qrTargetUrl = session.linkUrl
            ) {
                qrPollJob?.cancel()
            }

            qrPollJob = lifecycleScope.launch {
                val deadline = System.currentTimeMillis() + 5 * 60_000L
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(2_500L)
                    val linked = StremioApiClient.pollQrLinkAuthKey(session.code)
                    if (linked != null) {
                        val (authKey, email) = linked
                        AppPrefs.setStremioAccount(this@MainActivity, authKey, email)
                        qrDialog?.dismiss()
                        Toast.makeText(this@MainActivity, "Linked $email · Syncing subscriptions...", Toast.LENGTH_LONG).show()
                        val count = StremioApiClient.syncAccountEverything(this@MainActivity, authKey)
                        Toast.makeText(this@MainActivity, "Synced $count Stremio addons", Toast.LENGTH_SHORT).show()
                        refreshAccountIndicators()
                        if (activeTab == NavTab.PASSPORT) refreshPassportTab()
                        rebuildBoardRows(clearCache = true)
                        rebuildDiscoverCatalogChoices(keepSelection = true, triggerLoad = activeTab == NavTab.DISCOVER)
                        break
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccountIndicators()
        refreshStreamingAccountPill()
        when (activeTab) {
            NavTab.BOARD -> refreshContinueWatchingRow()
            NavTab.JELLYFIN -> reloadJellyfinServersState(triggerNetwork = false)
            NavTab.LIBRARY -> refreshLibraryGrid()
            NavTab.PASSPORT -> refreshPassportTab()
            else -> Unit
        }
    }
}
