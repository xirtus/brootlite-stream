package app.brootlite.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.brootlite.tv.data.AppPrefs
import app.brootlite.tv.data.FavoriteEntry
import app.brootlite.tv.data.KodiDirectoryItem
import app.brootlite.tv.kodi.PyAddonClient
import kotlinx.coroutines.launch

class AddonBrowserActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_INITIAL_URL = "extra_plugin_url"
        private const val EXTRA_INITIAL_TITLE = "extra_plugin_title"
        private const val EXTRA_TARGET_SITE_MATCH = "extra_target_site_match"

        fun start(
            context: Context,
            pluginUrl: String,
            title: String,
            autoOpenSiteMatch: String? = null
        ) {
            val intent = Intent(context, AddonBrowserActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_URL, pluginUrl)
                putExtra(EXTRA_INITIAL_TITLE, title)
                if (!autoOpenSiteMatch.isNullOrBlank()) {
                    putExtra(EXTRA_TARGET_SITE_MATCH, autoOpenSiteMatch)
                }
            }
            context.startActivity(intent)
        }
    }

    private data class NavStep(val url: String, val title: String)

    private val navStack = ArrayDeque<NavStep>()
    private var fullItems: List<KodiDirectoryItem> = emptyList()
    private var filterQuery: String = ""
    private var pendingAutoSiteMatch: String? = null
    private var launchingPlayer = false

    private lateinit var breadcrumbText: TextView
    private lateinit var filterBtn: TextView
    private lateinit var loadingRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: KodiDirectoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val installedVideoAddons = PyAddonClient.scanInstalledVideoAddonsFast(this)
        val defaultPluginUrl = installedVideoAddons.firstOrNull { it.id.startsWith("plugin.video.") }?.pluginUrl ?: "plugin://"
        val initUrl = intent.getStringExtra(EXTRA_INITIAL_URL) ?: defaultPluginUrl
        val initTitle = intent.getStringExtra(EXTRA_INITIAL_TITLE) ?: "Kodi Addons"
        pendingAutoSiteMatch = intent.getStringExtra(EXTRA_TARGET_SITE_MATCH)

        buildUi()
        navigateTo(initUrl, initTitle, pushStack = true)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = TvUiKit.createRootCanvasDrawable()
        }

        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                TvUiKit.dp(this@AddonBrowserActivity, 26),
                TvUiKit.dp(this@AddonBrowserActivity, 14),
                TvUiKit.dp(this@AddonBrowserActivity, 26),
                TvUiKit.dp(this@AddonBrowserActivity, 12)
            )
        }

        val backBtn = TvUiKit.createPillButton(this, "← Back") {
            handleBackNavigation()
        }
        headerBar.addView(backBtn)

        headerBar.addView(View(this).apply {
            val sz = TvUiKit.dp(this@AddonBrowserActivity, 30)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = TvUiKit.dp(this@AddonBrowserActivity, 14)
                marginEnd = TvUiKit.dp(this@AddonBrowserActivity, 10)
            }
            background = XirtusEmblemDrawable(this@AddonBrowserActivity)
        })

        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = TvUiKit.dp(this@AddonBrowserActivity, 14)
            }
        }
        titleStack.addView(TvUiKit.eyebrow(this, "KODI / ISOLATED :PYADDON"))
        breadcrumbText = TextView(this).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = XirtusFonts.semiBold(this@AddonBrowserActivity)
            letterSpacing = -0.015f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleStack.addView(breadcrumbText)
        headerBar.addView(titleStack)

        filterBtn = TvUiKit.createPillButton(this, "Filter") {
            TvUiKit.showInputDialog(
                context = this,
                title = "Filter Current Directory",
                initialValue = filterQuery,
                hint = "Type site or video title (leave empty to show all)...",
                onSubmit = { q ->
                    filterQuery = q
                    applyFilter()
                }
            )
        }
        headerBar.addView(filterBtn)

        val refreshBtn = TvUiKit.createPillButton(this, "↻ Refresh") {
            navStack.lastOrNull()?.let { cur ->
                navigateTo(cur.url, cur.title, pushStack = false)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = TvUiKit.dp(this@AddonBrowserActivity, 8) }
        }
        headerBar.addView(refreshBtn)
        root.addView(headerBar)
        root.addView(TvUiKit.hairlineDivider(this))

        val bodyCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(
                TvUiKit.dp(this@AddonBrowserActivity, 24),
                TvUiKit.dp(this@AddonBrowserActivity, 10),
                TvUiKit.dp(this@AddonBrowserActivity, 24),
                TvUiKit.dp(this@AddonBrowserActivity, 14)
            )
        }

        val hintText = TextView(this).apply {
            text = "HOLD OK OR PRESS MENU FOR LIBRARY BOOKMARK & SITE ACTIONS  ·  :PYADDON SELF-TERMINATES AT STREAM HANDOFF (0 MB)"
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            typeface = XirtusFonts.monoRegular(this@AddonBrowserActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            letterSpacing = 0.06f
            setPadding(TvUiKit.dp(this@AddonBrowserActivity, 6), 0, 0, TvUiKit.dp(this@AddonBrowserActivity, 8))
        }
        bodyCol.addView(hintText)

        loadingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(TvUiKit.dp(this@AddonBrowserActivity, 6), TvUiKit.dp(this@AddonBrowserActivity, 8), 0, TvUiKit.dp(this@AddonBrowserActivity, 8))
            visibility = View.GONE
        }
        loadingRow.addView(ProgressBar(this).apply {
            val sz = TvUiKit.dp(this@AddonBrowserActivity, 18)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = TvUiKit.dp(this@AddonBrowserActivity, 10) }
        })
        statusText = TextView(this).apply {
            text = "Executing addon in :pyaddon..."
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            typeface = XirtusFonts.monoRegular(this@AddonBrowserActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        loadingRow.addView(statusText)
        bodyCol.addView(loadingRow)

        adapter = KodiDirectoryAdapter(
            items = emptyList(),
            onClick = { item -> onDirectoryItemClicked(item) },
            onContextAction = { item -> showItemContextMenu(item) }
        )
        recycler = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = GridLayoutManager(this@AddonBrowserActivity, 2)
            itemAnimator = null
            clipToPadding = false
            adapter = this@AddonBrowserActivity.adapter
        }
        bodyCol.addView(recycler)
        root.addView(bodyCol)

        setContentView(root)
    }

    private fun navigateTo(url: String, title: String, pushStack: Boolean) {
        if (pushStack) {
            navStack.addLast(NavStep(url, title))
        }
        breadcrumbText.text = navStack.joinToString("  ›  ") { it.title }
        filterQuery = ""
        filterBtn.text = "Filter"
        loadingRow.visibility = View.VISIBLE
        statusText.text = "Loading $title..."

        lifecycleScope.launch {
            val resp = PyAddonClient.executeUrl(this@AddonBrowserActivity, url)
            loadingRow.visibility = View.GONE

            if (!resp.ok && resp.error != null) {
                Toast.makeText(this@AddonBrowserActivity, resp.error, Toast.LENGTH_LONG).show()
                return@launch
            }

            if (!resp.redirectUrl.isNullOrBlank()) {
                navigateTo(resp.redirectUrl, title, pushStack = false)
                return@launch
            }

            if (resp.resolved != null && resp.resolved.url.isNotBlank()) {
                handoffResolvedStreamToPlayer(resp.resolved, title)
                return@launch
            }

            fullItems = resp.items
            applyFilter()

            val targetSite = pendingAutoSiteMatch
            if (!targetSite.isNullOrBlank()) {
                pendingAutoSiteMatch = null
                val matched = fullItems.firstOrNull {
                    it.cleanLabel.contains(targetSite, ignoreCase = true) ||
                        it.url.contains(targetSite, ignoreCase = true)
                }
                if (matched != null) {
                    onDirectoryItemClicked(matched)
                    return@launch
                }
            }

            recycler.post {
                recycler.getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun applyFilter() {
        val q = filterQuery.trim()
        val shown = if (q.isEmpty()) {
            filterBtn.text = "Filter (${fullItems.size})"
            fullItems
        } else {
            val f = fullItems.filter {
                it.cleanLabel.contains(q, ignoreCase = true) || it.plot.contains(q, ignoreCase = true)
            }
            filterBtn.text = "\"$q\" (${f.size})"
            f
        }
        adapter.submit(shown)
    }

    private fun onDirectoryItemClicked(item: KodiDirectoryItem) {
        val label = item.cleanLabel.ifBlank { "Stream" }
        if (item.isFolder && item.url.startsWith("plugin://")) {
            navigateTo(item.url, label, pushStack = true)
            return
        }

        if (item.url.startsWith("http://", true) || item.url.startsWith("https://", true) || item.url.startsWith("magnet:", true)) {
            handoffResolvedStreamToPlayer(item, label)
            return
        }

        loadingRow.visibility = View.VISIBLE
        statusText.text = "Resolving $label..."
        lifecycleScope.launch {
            val resp = PyAddonClient.executeUrl(this@AddonBrowserActivity, item.url)
            loadingRow.visibility = View.GONE

            when {
                !resp.redirectUrl.isNullOrBlank() -> {
                    navigateTo(resp.redirectUrl, label, pushStack = true)
                }
                resp.refresh -> {
                    navStack.lastOrNull()?.let { cur -> navigateTo(cur.url, cur.title, pushStack = false) }
                }
                resp.resolved != null && resp.resolved.url.isNotBlank() -> {
                    handoffResolvedStreamToPlayer(resp.resolved, label)
                }
                resp.items.isNotEmpty() -> {
                    navStack.addLast(NavStep(item.url, label))
                    breadcrumbText.text = navStack.joinToString("  ›  ") { it.title }
                    fullItems = resp.items
                    applyFilter()
                }
                !resp.error.isNullOrBlank() -> {
                    Toast.makeText(this@AddonBrowserActivity, resp.error, Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(this@AddonBrowserActivity, "Unable to resolve playable video link", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun handoffResolvedStreamToPlayer(resolved: KodiDirectoryItem, fallbackTitle: String) {
        val rawUrl = resolved.url
        launchingPlayer = true
        PyAddonClient.killAddonProcessNow(this)

        val title = resolved.cleanLabel.ifBlank { fallbackTitle }
        if (rawUrl.startsWith("magnet:", ignoreCase = true)) {
            PlayerActivity.start(
                this,
                PlayerActivity.LaunchPayload(
                    title = title,
                    subtitle = "Kodi P2P",
                    magnetUri = rawUrl,
                    presetSubtitles = resolved.subtitles
                )
            )
            return
        }

        val parsed = PyAddonClient.parseKodiStreamUrl(rawUrl)
        PlayerActivity.start(
            this,
            PlayerActivity.LaunchPayload(
                title = title,
                subtitle = navStack.firstOrNull()?.title ?: "Kodi",
                itemId = "kodi:${rawUrl.hashCode()}",
                videoId = "kodi:${rawUrl.hashCode()}",
                contentType = "movie",
                poster = resolved.thumb,
                background = resolved.fanart.ifBlank { resolved.thumb },
                directUrl = parsed.cleanUrl,
                headers = parsed.headers,
                ignoreSsl = parsed.ignoreSsl,
                presetSubtitles = resolved.subtitles
            )
        )
    }

    private fun showItemContextMenu(item: KodiDirectoryItem) {
        val favId = "kodi:${item.url}"
        val isFav = AppPrefs.isFavorite(this, favId)
        val options = mutableListOf<Pair<String, () -> Unit>>()

        options.add(
            (if (isFav) "★ Remove from BrootLite Library" else "☆ Save to BrootLite Library") to {
                val added = AppPrefs.toggleFavorite(
                    this,
                    FavoriteEntry(
                        id = favId,
                        type = "kodi",
                        name = item.cleanLabel.ifBlank { item.label },
                        poster = item.thumb,
                        subtitle = navStack.firstOrNull()?.title ?: "Kodi",
                        pluginUrl = item.url
                    )
                )
                Toast.makeText(this, if (added) "Saved to Library" else "Removed from Library", Toast.LENGTH_SHORT).show()
            }
        )

        for (ctxOpt in item.contextMenu) {
            val clean = ctxOpt.cleanLabel.ifBlank { ctxOpt.label }
            if (clean.contains("Set this view", true) || clean.contains("Addon settings", true)) continue
            options.add(clean to {
                loadingRow.visibility = View.VISIBLE
                statusText.text = "Running $clean..."
                lifecycleScope.launch {
                    val resp = PyAddonClient.executeUrl(this@AddonBrowserActivity, ctxOpt.url)
                    loadingRow.visibility = View.GONE
                    if (!resp.redirectUrl.isNullOrBlank()) {
                        navigateTo(resp.redirectUrl, clean, pushStack = true)
                    } else if (resp.refresh) {
                        navStack.lastOrNull()?.let { cur -> navigateTo(cur.url, cur.title, pushStack = false) }
                    } else if (resp.items.isNotEmpty()) {
                        navStack.addLast(NavStep(ctxOpt.url, clean))
                        breadcrumbText.text = navStack.joinToString("  ›  ") { it.title }
                        fullItems = resp.items
                        applyFilter()
                    }
                }
            })
        }

        TvUiKit.showSelectDialog(
            context = this,
            title = item.cleanLabel.ifBlank { "Item Actions" },
            options = options.map { it.first },
            eyebrowLabel = "KODI / CONTEXT MENU"
        ) { idx ->
            if (idx in options.indices) {
                options[idx].second.invoke()
            }
        }
    }

    private fun handleBackNavigation() {
        if (filterQuery.isNotBlank()) {
            filterQuery = ""
            applyFilter()
            return
        }
        if (navStack.size > 1) {
            navStack.removeLast()
            val prev = navStack.last()
            navigateTo(prev.url, prev.title, pushStack = false)
        } else {
            PyAddonClient.killAddonProcessNow(this)
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackNavigation()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        launchingPlayer = false
    }

    override fun onDestroy() {
        if (isFinishing) {
            PyAddonClient.killAddonProcessNow(this)
        }
        super.onDestroy()
    }
}
