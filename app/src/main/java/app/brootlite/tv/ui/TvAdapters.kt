package app.brootlite.tv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.brootlite.tv.data.AddonCatalogDef
import app.brootlite.tv.data.FavoriteEntry
import app.brootlite.tv.data.InstalledAddon
import app.brootlite.tv.data.KodiDirectoryItem
import app.brootlite.tv.data.MetaItem
import app.brootlite.tv.data.StreamCandidate
import app.brootlite.tv.data.StremioApiClient
import app.brootlite.tv.data.WatchHistoryEntry
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

data class BoardRowSpec(
    val key: String,
    val eyebrow: String,
    val title: String,
    val addon: InstalledAddon,
    val catalog: AddonCatalogDef,
    val genre: String? = null
)

private fun formatPosterMetaLine(item: MetaItem): String {
    val yr = item.releaseInfo.takeIf { it.isNotBlank() }?.take(9)
    val rt = item.imdbRating.takeIf { it.isNotBlank() }?.let { "★ $it" }
    return when {
        yr != null && rt != null -> "$yr · $rt"
        rt != null -> rt
        yr != null -> yr
        else -> item.type.replaceFirstChar { it.uppercase() }
    }
}

class PosterRowAdapter(
    private var items: List<MetaItem>,
    private val onClick: (MetaItem) -> Unit
) : RecyclerView.Adapter<PosterRowAdapter.VH>() {

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
        val cardW = TvUiKit.dp(ctx, 138)
        val imgH = TvUiKit.dp(ctx, 198)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(ctx, 12)
                topMargin = TvUiKit.dp(ctx, 4)
                bottomMargin = TvUiKit.dp(ctx, 4)
            }
            setPadding(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 8))
            TvUiKit.applyCardFocusEffect(this, 1.03f)
        }

        val poster = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imgH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#111722"))
            TvUiKit.applyRoundedClip(this, 10f)
        }

        val title = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.medium(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 3), 0)
        }
        val sub = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoRegular(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 2), TvUiKit.dp(ctx, 3), 0)
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
        holder.sub.text = formatPosterMetaLine(item)
        if (item.poster.isNotBlank()) {
            holder.poster.load(item.poster)
        } else {
            holder.poster.setImageDrawable(null)
        }
        holder.root.setOnClickListener { onClick(item) }
    }
}

/**
 * 6-Column Infinite-Scroll Poster Gallery Grid Adapter for the Stremio Discover tab.
 * Uses shared RecycledViewPool + RGB_565 thumbnails for 0 extra RAM.
 */
class DiscoverGridAdapter(
    private val onClick: (MetaItem) -> Unit
) : RecyclerView.Adapter<DiscoverGridAdapter.VH>() {

    private val items = ArrayList<MetaItem>(120)

    class VH(
        val root: LinearLayout,
        val poster: ImageView,
        val title: TextView,
        val sub: TextView
    ) : RecyclerView.ViewHolder(root)

    fun submit(newItems: List<MetaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun append(moreItems: List<MetaItem>) {
        if (moreItems.isEmpty()) return
        val start = items.size
        items.addAll(moreItems)
        notifyItemRangeInserted(start, moreItems.size)
    }

    fun currentCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 7))
            }
            setPadding(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 8))
            TvUiKit.applyCardFocusEffect(this, 1.03f)
        }

        val poster = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TvUiKit.dp(ctx, 184))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#111722"))
            TvUiKit.applyRoundedClip(this, 10f)
        }

        val title = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.medium(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 3), 0)
        }

        val sub = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = XirtusFonts.monoRegular(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 2), TvUiKit.dp(ctx, 3), 0)
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
        holder.sub.text = formatPosterMetaLine(item)
        if (item.poster.isNotBlank()) {
            holder.poster.load(item.poster)
        } else {
            holder.poster.setImageDrawable(null)
        }
        holder.root.setOnClickListener { onClick(item) }
    }
}

class ContinueWatchingAdapter(
    private var items: List<WatchHistoryEntry>,
    private val onClick: (WatchHistoryEntry) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val thumb: ImageView,
        val progressFill: View,
        val title: TextView,
        val sub: TextView
    ) : RecyclerView.ViewHolder(root)

    fun submit(list: List<WatchHistoryEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val w = TvUiKit.dp(ctx, 224)
        val h = TvUiKit.dp(ctx, 122)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(ctx, 12)
                topMargin = TvUiKit.dp(ctx, 4)
                bottomMargin = TvUiKit.dp(ctx, 4)
            }
            setPadding(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 8))
            TvUiKit.applyCardFocusEffect(this, 1.03f)
        }

        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            TvUiKit.applyRoundedClip(this, 10f)
        }
        val thumb = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#111722"))
        }
        val barTrack = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TvUiKit.dp(ctx, 4),
                Gravity.BOTTOM
            )
            setBackgroundColor(Color.parseColor("#880D1118"))
        }
        val barFill = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#E2E8F0"))
        }
        barTrack.addView(barFill)
        frame.addView(thumb)
        frame.addView(barTrack)

        val title = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.medium(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 3), 0)
        }
        val sub = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoRegular(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 2), TvUiKit.dp(ctx, 3), 0)
        }
        root.addView(frame)
        root.addView(title)
        root.addView(sub)
        return VH(root, thumb, barFill, title, sub)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.title.text = entry.name
        val minLeft = if (entry.durationMs > entry.timeOffsetMs) {
            "${(entry.durationMs - entry.timeOffsetMs) / 60000L}m remaining"
        } else "Resume"
        holder.sub.text = listOfNotNull(
            entry.episodeTitle.takeIf { it.isNotBlank() },
            minLeft
        ).joinToString(" · ")

        val imgUrl = entry.background.ifBlank { entry.poster }
        if (imgUrl.isNotBlank()) holder.thumb.load(imgUrl) else holder.thumb.setImageDrawable(null)

        val totalW = TvUiKit.dp(holder.root.context, 214)
        holder.progressFill.layoutParams = FrameLayout.LayoutParams(
            (totalW * entry.progressFraction).toInt().coerceAtLeast(8),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        holder.root.setOnClickListener { onClick(entry) }
    }
}

/**
 * Lazy-loading vertical Board feed adapter:
 * - Position 0: Top header container (Continue Watching + Companion Strip)
 * - Position 1..N: Streaming & Stremio catalog rows loaded lazily only as they scroll into view!
 */
class LazyBoardAdapter(
    private val headerView: View,
    private val scope: CoroutineScope,
    private val sharedPool: RecyclerView.RecycledViewPool,
    private val onItemClick: (MetaItem) -> Unit,
    private val onOpenInDiscover: (BoardRowSpec) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CATALOG_ROW = 1
    }

    private var specs: List<BoardRowSpec> = emptyList()
    private val rowCache = LruCache<String, List<MetaItem>>(80)
    private val emptyKnownKeys = HashSet<String>()
    private val inFlight = HashMap<String, Job>()

    init {
        setHasStableIds(true)
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view)

    class RowVH(
        val root: LinearLayout,
        val eyebrowView: TextView,
        val titleView: TextView,
        val seeAllBtn: TextView,
        val statusText: TextView,
        val recycler: RecyclerView,
        val posterAdapter: PosterRowAdapter,
        var boundKey: String = ""
    ) : RecyclerView.ViewHolder(root)

    fun submitSpecs(newSpecs: List<BoardRowSpec>, clearCache: Boolean = false) {
        if (clearCache) {
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
            rowCache.evictAll()
            emptyKnownKeys.clear()
        }
        specs = newSpecs
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long {
        if (position == 0) return -1L
        return specs[position - 1].key.hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_HEADER else TYPE_CATALOG_ROW

    override fun getItemCount(): Int = 1 + specs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            (headerView.parent as? ViewGroup)?.removeView(headerView)
            headerView.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return HeaderVH(headerView)
        }

        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = TvUiKit.dp(ctx, 18) }
            clipChildren = false
            clipToPadding = false
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(TvUiKit.dp(ctx, 2), 0, TvUiKit.dp(ctx, 4), TvUiKit.dp(ctx, 6))
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val eyebrow = TvUiKit.eyebrow(ctx, "CATALOG")
        val title = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = XirtusFonts.semiBold(ctx)
            letterSpacing = -0.015f
        }
        titleCol.addView(eyebrow)
        titleCol.addView(title)

        val seeAllBtn = TvUiKit.createPillButton(ctx, "See all in Discover →", compact = true) {}
        headerRow.addView(titleCol)
        headerRow.addView(seeAllBtn)

        // Constant-height row body FrameLayout prevents vertical scroll offset jumps & row vanishing!
        val bodyFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TvUiKit.dp(ctx, 260)
            )
            clipChildren = false
            clipToPadding = false
        }

        val statusText = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
            setTextColor(XirtusPalette.TEXT_EYEBROW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = XirtusFonts.monoRegular(ctx)
            setPadding(TvUiKit.dp(ctx, 6), 0, 0, 0)
            visibility = View.GONE
        }

        val innerAdapter = PosterRowAdapter(emptyList(), onItemClick)
        val rv = RecyclerView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false).apply {
                initialPrefetchItemCount = 6
            }
            setRecycledViewPool(sharedPool)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            adapter = innerAdapter
        }

        bodyFrame.addView(statusText)
        bodyFrame.addView(rv)

        root.addView(headerRow)
        root.addView(bodyFrame)
        return RowVH(root, eyebrow, title, seeAllBtn, statusText, rv, innerAdapter)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is RowVH) return
        val spec = specs[position - 1]
        val wasSameKey = holder.boundKey == spec.key
        holder.boundKey = spec.key
        holder.eyebrowView.text = spec.eyebrow.uppercase()
        holder.titleView.text = spec.title
        holder.seeAllBtn.setOnClickListener { onOpenInDiscover(spec) }

        val cached = rowCache.get(spec.key)
        if (cached != null) {
            holder.statusText.visibility = View.GONE
            holder.recycler.visibility = View.VISIBLE
            holder.posterAdapter.submit(cached)
            if (!wasSameKey) {
                holder.recycler.scrollToPosition(0)
            }
            return
        }

        if (spec.key in emptyKnownKeys) {
            holder.posterAdapter.submit(emptyList())
            holder.recycler.visibility = View.INVISIBLE
            holder.statusText.text = "Open in Discover → to browse options"
            holder.statusText.visibility = View.VISIBLE
            return
        }

        holder.posterAdapter.submit(emptyList())
        holder.recycler.visibility = View.INVISIBLE
        holder.statusText.text = "Loading ${spec.title}..."
        holder.statusText.visibility = View.VISIBLE

        if (inFlight.containsKey(spec.key)) return
        val effectiveGenre = spec.genre
            ?: if (spec.catalog.isGenreRequired) spec.catalog.genreOptions.firstOrNull() else null
        val job = scope.launch {
            val fetched = StremioApiClient.fetchCatalog(
                addon = spec.addon,
                catalog = spec.catalog,
                genre = effectiveGenre
            ).take(28)
            inFlight.remove(spec.key)
            if (fetched.isNotEmpty()) {
                rowCache.put(spec.key, fetched)
            } else {
                emptyKnownKeys.add(spec.key)
            }
            if (holder.boundKey == spec.key) {
                if (fetched.isNotEmpty()) {
                    holder.statusText.visibility = View.GONE
                    holder.recycler.visibility = View.VISIBLE
                    holder.posterAdapter.submit(fetched)
                    holder.recycler.scrollToPosition(0)
                } else {
                    holder.recycler.visibility = View.INVISIBLE
                    holder.statusText.text = "Open in Discover → to browse options"
                    holder.statusText.visibility = View.VISIBLE
                }
            } else if (fetched.isNotEmpty()) {
                val idx = specs.indexOfFirst { it.key == spec.key }
                if (idx >= 0) notifyItemChanged(idx + 1)
            }
        }
        inFlight[spec.key] = job
    }
}

class StreamListAdapter(
    private var items: List<StreamCandidate>,
    private val onPlay: (StreamCandidate) -> Unit,
    private val onLongClick: ((StreamCandidate) -> Unit)? = null
) : RecyclerView.Adapter<StreamListAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val badge: TextView,
        val telemetry: TextView,
        val details: TextView
    ) : RecyclerView.ViewHolder(root)

    fun submit(newList: List<StreamCandidate>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TvUiKit.dp(ctx, 8)
                marginStart = TvUiKit.dp(ctx, 2)
                marginEnd = TvUiKit.dp(ctx, 2)
            }
            setPadding(TvUiKit.dp(ctx, 14), TvUiKit.dp(ctx, 11), TvUiKit.dp(ctx, 14), TvUiKit.dp(ctx, 11))
            TvUiKit.applyCardFocusEffect(this, 1.015f)
        }

        val badge = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(TvUiKit.dp(ctx, 166), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = TvUiKit.dp(ctx, 14)
            }
            gravity = Gravity.CENTER
            setPadding(TvUiKit.dp(ctx, 8), TvUiKit.dp(ctx, 7), TvUiKit.dp(ctx, 8), TvUiKit.dp(ctx, 7))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = XirtusFonts.monoMedium(ctx)
            setTextColor(XirtusPalette.TEXT_PRIMARY)
        }

        val rightStack = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Dedicated never-truncated telemetry header: Seeds · Size · Resolution · HEVC/AV1/H.264 · Source
        val telemetry = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = TvUiKit.dp(ctx, 3)
            }
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = XirtusFonts.monoMedium(ctx)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }

        // Full untruncated release title, pack path & filename — never clipped with ellipsis!
        val details = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            typeface = XirtusFonts.regular(ctx)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(0f, 1.18f)
        }

        rightStack.addView(telemetry)
        rightStack.addView(details)
        root.addView(badge)
        root.addView(rightStack)
        return VH(root, badge, telemetry, details)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.root.context
        val badgeLabel = item.displayBadge
        holder.badge.text = badgeLabel
        val isDirectOrDebrid = item.isDirectHttp || badgeLabel.contains("RD+", true) || badgeLabel.contains("AD+", true)
        val isExtMirror = item.isExternalMirror
        val isModernCodec = item.isPreferredModernCodec
        val isPublicOrRss = badgeLabel.contains("InternetArchive", true) || badgeLabel.contains("Torznab", true) || item.addonName.contains("PublicDomain", true) || item.addonName.contains("Torznab", true) || item.addonName.contains("RSS", true)
        val fillHex = when {
            isDirectOrDebrid -> "#172E29"
            isExtMirror -> "#241E38"
            isModernCodec && (!item.isTorrent || item.seeders > 0) -> "#152932"
            item.addonName.contains("RSS", true) || badgeLabel.contains("RSS", true) -> "#2A1B29"
            item.addonName.contains("Torznab", true) || badgeLabel.contains("Torznab", true) -> "#1D243B"
            isPublicOrRss -> "#22201A"
            item.isTorrent && item.seeders == 0 -> "#151B26"
            else -> "#1B2536"
        }
        val borderHex = when {
            isDirectOrDebrid -> "#5934D399"
            isExtMirror -> "#66A78BFA"
            isModernCodec && (!item.isTorrent || item.seeders > 0) -> "#6638BDF8"
            isPublicOrRss -> "#66F59E0B"
            else -> "#33FFFFFF"
        }
        holder.badge.background = GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = TvUiKit.dpF(ctx, 8f)
            setStroke(1, Color.parseColor(borderHex))
        }

        val tel = item.telemetryHeader
        if (tel.isNotBlank()) {
            holder.telemetry.visibility = View.VISIBLE
            holder.telemetry.text = tel
            holder.telemetry.setTextColor(
                when {
                    item.isTorrent && item.seeders == 0 -> Color.parseColor("#94A3B8")
                    item.isDirectHttp || (item.isTorrent && item.seeders >= 5) -> Color.parseColor("#6EE7B7")
                    isModernCodec -> Color.parseColor("#7DD3FC")
                    else -> Color.parseColor("#FCD34D")
                }
            )
        } else {
            holder.telemetry.visibility = View.GONE
        }

        holder.details.text = item.displayDetails.ifBlank { item.filename.ifBlank { item.url ?: item.externalUrl ?: item.infoHash ?: "Stream" } }
        holder.root.setOnClickListener { onPlay(item) }
        holder.root.setOnLongClickListener {
            onLongClick?.invoke(item)
            onLongClick != null
        }
        holder.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU && onLongClick != null) {
                onLongClick.invoke(item)
                true
            } else false
        }
    }
}

class FavoriteGridAdapter(
    private var items: List<FavoriteEntry>,
    private val onClick: (FavoriteEntry) -> Unit,
    private val onLongClick: (FavoriteEntry) -> Unit
) : RecyclerView.Adapter<FavoriteGridAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val poster: ImageView,
        val title: TextView,
        val sub: TextView
    ) : RecyclerView.ViewHolder(root)

    fun submit(list: List<FavoriteEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 7))
            }
            setPadding(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 8))
            TvUiKit.applyCardFocusEffect(this, 1.03f)
        }
        val poster = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TvUiKit.dp(ctx, 180))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#111722"))
            TvUiKit.applyRoundedClip(this, 10f)
        }
        val title = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.medium(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 3), 0)
        }
        val sub = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = XirtusFonts.monoRegular(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 2), TvUiKit.dp(ctx, 3), 0)
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
        holder.sub.text = item.subtitle.ifBlank { item.type.uppercase() }
        if (item.poster.isNotBlank()) {
            val data: Any = if (item.poster.startsWith("/")) File(item.poster) else item.poster
            holder.poster.load(data)
        } else {
            holder.poster.setImageDrawable(null)
        }
        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}

class KodiDirectoryAdapter(
    private var items: List<KodiDirectoryItem>,
    private val onClick: (KodiDirectoryItem) -> Unit,
    private val onContextAction: (KodiDirectoryItem) -> Unit
) : RecyclerView.Adapter<KodiDirectoryAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val thumb: ImageView,
        val badge: TextView,
        val title: TextView,
        val meta: TextView
    ) : RecyclerView.ViewHolder(root)

    fun submit(newItems: List<KodiDirectoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 5))
            }
            setPadding(TvUiKit.dp(ctx, 10), TvUiKit.dp(ctx, 8), TvUiKit.dp(ctx, 12), TvUiKit.dp(ctx, 8))
            TvUiKit.applyCardFocusEffect(this, 1.02f)
        }

        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(TvUiKit.dp(ctx, 112), TvUiKit.dp(ctx, 68)).apply {
                marginEnd = TvUiKit.dp(ctx, 12)
            }
            TvUiKit.applyRoundedClip(this, 8f)
        }
        val thumb = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#111722"))
        }
        val badge = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(4, 4, 4, 4) }
            setPadding(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 1), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 1))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = XirtusFonts.monoMedium(ctx)
            setTextColor(XirtusPalette.TEXT_PRIMARY)
        }
        frame.addView(thumb)
        frame.addView(badge)

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = XirtusFonts.medium(ctx)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoRegular(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, TvUiKit.dp(ctx, 3), 0, 0)
        }
        textCol.addView(title)
        textCol.addView(meta)

        root.addView(frame)
        root.addView(textCol)
        return VH(root, thumb, badge, title, meta)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.root.context
        holder.title.text = TvUiKit.parseKodiBbCode(item.label.ifBlank { item.cleanLabel })

        val metaParts = mutableListOf<String>()
        metaParts.add(if (item.isFolder) "Directory" else "Stream")
        if (item.duration > 0) {
            val mins = item.duration / 60
            val secs = item.duration % 60
            metaParts.add(String.format("%d:%02d", mins, secs))
        }
        if (item.height >= 720) {
            metaParts.add("${item.height}p")
        }
        if (item.plot.isNotBlank() && item.plot != item.label) {
            metaParts.add(TvUiKit.parseKodiBbCode(item.plot).toString().replace("\n", " "))
        }
        holder.meta.text = metaParts.joinToString(" · ")

        holder.badge.text = if (item.isFolder) "DIR" else "PLAY"
        holder.badge.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (item.isFolder) "#CC1E2736" else "#CC273347"))
            cornerRadius = TvUiKit.dpF(ctx, 5f)
            setStroke(1, Color.parseColor("#33FFFFFF"))
        }

        if (item.thumb.isNotBlank()) {
            val data: Any = if (item.thumb.startsWith("/")) File(item.thumb) else item.thumb
            holder.thumb.load(data)
        } else {
            holder.thumb.setImageDrawable(null)
        }

        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener {
            onContextAction(item)
            true
        }
        holder.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onContextAction(item)
                true
            } else false
        }
    }
}

data class StreamCardItem(
    val key: String,
    val kind: String, // "video" | "playlist" | "channel" | "category"
    val platform: String, // "youtube" | "twitch" | "odysee" | "rumble"
    val id: String,
    val title: String,
    val subtitle: String,
    val badgeText: String = "",
    val isLive: Boolean = false,
    val thumbnailUrl: String = "",
    val channelId: String = "",
    val channelName: String = "",
    val channelAvatarUrl: String = "",
    val resumeFraction: Float = 0f,
    val startSeconds: Long = 0L,
    val streamHint: String? = null
)

/**
 * 4-Column 16:9 Xirtus Dark-Glass Card Adapter for the Creator Streaming Tab
 * (YouTube · Twitch · Odysee · Rumble).
 */
class StreamVideoGridAdapter(
    private val onClick: (StreamCardItem) -> Unit,
    private val onLongClick: ((StreamCardItem) -> Unit)? = null
) : RecyclerView.Adapter<StreamVideoGridAdapter.VH>() {

    private val items = ArrayList<StreamCardItem>(96)

    init {
        setHasStableIds(true)
    }

    class VH(
        val root: LinearLayout,
        val thumb: ImageView,
        val badge: TextView,
        val progressTrack: LinearLayout,
        val progressFill: View,
        val title: TextView,
        val subtitle: TextView
    ) : RecyclerView.ViewHolder(root)

    fun submit(newItems: List<StreamCardItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun append(moreItems: List<StreamCardItem>) {
        if (moreItems.isEmpty()) return
        val start = items.size
        items.addAll(moreItems)
        notifyItemRangeInserted(start, moreItems.size)
    }

    fun currentCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 7))
            }
            setPadding(TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 5), TvUiKit.dp(ctx, 8))
            TvUiKit.applyCardFocusEffect(this, 1.03f)
        }

        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TvUiKit.dp(ctx, 122)
            )
            TvUiKit.applyRoundedClip(this, 10f)
        }

        val thumb = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#111722"))
        }

        val badge = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                val m = TvUiKit.dp(ctx, 6)
                setMargins(m, m, m, m)
            }
            setPadding(TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 2), TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = XirtusFonts.monoMedium(ctx)
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            maxLines = 1
        }

        val progressTrack = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TvUiKit.dp(ctx, 3),
                Gravity.BOTTOM
            )
            setBackgroundColor(Color.parseColor("#880D1118"))
            visibility = View.GONE
        }

        val progressFill = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f)
            setBackgroundColor(Color.parseColor("#EF4444"))
        }
        progressTrack.addView(progressFill)

        frame.addView(thumb)
        frame.addView(badge)
        frame.addView(progressTrack)

        val title = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = XirtusFonts.medium(ctx)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 6), TvUiKit.dp(ctx, 3), 0)
        }

        val subtitle = TextView(ctx).apply {
            setTextColor(XirtusPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = XirtusFonts.monoRegular(ctx)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(TvUiKit.dp(ctx, 3), TvUiKit.dp(ctx, 2), TvUiKit.dp(ctx, 3), 0)
        }

        root.addView(frame)
        root.addView(title)
        root.addView(subtitle)
        return VH(root, thumb, badge, progressTrack, progressFill, title, subtitle)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.root.context

        holder.title.text = item.title
        holder.subtitle.text = item.subtitle

        if (item.thumbnailUrl.isNotBlank()) {
            holder.thumb.load(item.thumbnailUrl)
        } else {
            holder.thumb.setImageDrawable(null)
        }

        if (item.isLive) {
            holder.badge.text = "● LIVE"
            holder.badge.background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC2626"))
                cornerRadius = TvUiKit.dpF(ctx, 5f)
            }
            holder.badge.visibility = View.VISIBLE
        } else if (item.badgeText.isNotBlank()) {
            holder.badge.text = item.badgeText
            holder.badge.background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0D1118"))
                cornerRadius = TvUiKit.dpF(ctx, 5f)
                setStroke(1, Color.parseColor("#26FFFFFF"))
            }
            holder.badge.visibility = View.VISIBLE
        } else {
            holder.badge.visibility = View.GONE
        }

        val frac = item.resumeFraction.coerceIn(0f, 1f)
        if (!item.isLive && frac > 0.02f) {
            holder.progressTrack.visibility = View.VISIBLE
            holder.progressFill.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                frac.coerceAtLeast(0.04f)
            )
        } else {
            holder.progressTrack.visibility = View.GONE
        }

        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener {
            onLongClick?.invoke(item)
            onLongClick != null
        }
        holder.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU && onLongClick != null) {
                onLongClick.invoke(item)
                true
            } else false
        }
    }
}

