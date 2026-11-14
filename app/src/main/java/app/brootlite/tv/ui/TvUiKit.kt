package app.brootlite.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import app.brootlite.tv.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object XirtusFonts {
    @Volatile private var cachedRegular: Typeface? = null
    @Volatile private var cachedMedium: Typeface? = null
    @Volatile private var cachedSemiBold: Typeface? = null
    @Volatile private var cachedBold: Typeface? = null
    @Volatile private var cachedMonoRegular: Typeface? = null
    @Volatile private var cachedMonoMedium: Typeface? = null

    fun regular(context: Context): Typeface =
        cachedRegular ?: loadFont(context, R.font.geist_regular, Typeface.SANS_SERIF).also { cachedRegular = it }

    fun medium(context: Context): Typeface =
        cachedMedium ?: loadFont(context, R.font.geist_medium, Typeface.SANS_SERIF).also { cachedMedium = it }

    fun semiBold(context: Context): Typeface =
        cachedSemiBold ?: loadFont(context, R.font.geist_semibold, Typeface.DEFAULT_BOLD).also { cachedSemiBold = it }

    fun bold(context: Context): Typeface =
        cachedBold ?: loadFont(context, R.font.geist_bold, Typeface.DEFAULT_BOLD).also { cachedBold = it }

    fun monoRegular(context: Context): Typeface =
        cachedMonoRegular ?: loadFont(context, R.font.geist_mono_regular, Typeface.MONOSPACE).also { cachedMonoRegular = it }

    fun monoMedium(context: Context): Typeface =
        cachedMonoMedium ?: loadFont(context, R.font.geist_mono_medium, Typeface.MONOSPACE).also { cachedMonoMedium = it }

    private fun loadFont(context: Context, resId: Int, fallback: Typeface): Typeface {
        return runCatching {
            ResourcesCompat.getFont(context.applicationContext, resId) ?: fallback
        }.getOrDefault(fallback)
    }
}

object XirtusPalette {
    val CANVAS_START = Color.parseColor("#0D1118")
    val CANVAS_END = Color.parseColor("#121822")
    val PANEL_GLASS = Color.parseColor("#D9161D28")
    val CARD_GLASS = Color.parseColor("#E6151B25")
    val ELEVATED = Color.parseColor("#1E2736")
    val FOCUSED_FILL = Color.parseColor("#273347")
    val FOCUSED_BORDER = Color.parseColor("#CCFFFFFF")
    val HAIRLINE = Color.parseColor("#1FFFFFFF")
    val DIVIDER = Color.parseColor("#14FFFFFF")
    val INDIGO_START = Color.parseColor("#152238")
    val INDIGO_END = Color.parseColor("#0F1725")
    val INDIGO_BORDER = Color.parseColor("#2A4365")
    val INDIGO_ARC = Color.parseColor("#223556")
    val TEXT_PRIMARY = Color.parseColor("#F8FAFC")
    val TEXT_BODY = Color.parseColor("#B3BFD0")
    val TEXT_SECONDARY = Color.parseColor("#94A3B8")
    val TEXT_EYEBROW = Color.parseColor("#7D899B")
    val EMERALD = Color.parseColor("#34D399")
    val AMBER = Color.parseColor("#FBBF24")
}

/**
 * Lightweight vector Drawable rendering the circular Xirtus geometric 'X' emblem (0 MB bitmap RAM).
 */
class XirtusEmblemDrawable(private val context: Context) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D9141C28")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvUiKit.dpF(context, 1f)
        color = Color.parseColor("#33FFFFFF")
    }
    private val geoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvUiKit.dpF(context, 1.15f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#DCE4F0")
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = (minOf(b.width(), b.height()) / 2f) - ringPaint.strokeWidth

        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        val halfSq = r * 0.46f
        val corner = r * 0.12f
        rect.set(cx - halfSq, cy - halfSq, cx + halfSq, cy + halfSq)
        canvas.drawRoundRect(rect, corner, corner, geoPaint)

        canvas.save()
        canvas.rotate(45f, cx, cy)
        canvas.drawRoundRect(rect, corner, corner, geoPaint)
        canvas.restore()

        val xArm = r * 0.26f
        canvas.drawLine(cx - xArm, cy - xArm, cx + xArm, cy + xArm, geoPaint)
        canvas.drawLine(cx - xArm, cy + xArm, cx + xArm, cy - xArm, geoPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        ringPaint.alpha = alpha
        geoPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        ringPaint.colorFilter = colorFilter
        geoPaint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Custom faceted geometric BrootLite logo emblem (0 MB bitmap RAM):
 * Dark indigo-slate squircle glass badge with layered silver-emerald prism play facets.
 */
class BrootLiteLogoDrawable(private val context: Context) : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvUiKit.dpF(context, 1.15f)
        color = Color.parseColor("#4793C5FD")
    }
    private val backWingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#6638BDF8")
    }
    private val frontPrismPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvUiKit.dpF(context, 1.1f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E6F8FAFC")
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = XirtusPalette.EMERALD
    }
    private val rect = RectF()
    private val backPath = Path()
    private val frontPath = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val inset = borderPaint.strokeWidth
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val r = rect.width() * 0.28f

        bgPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            Color.parseColor("#1A2B49"),
            Color.parseColor("#0D131F"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, bgPaint)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = rect.width()
        val h = rect.height()

        // Rear offset prism facet (B-stem / layered stream wing)
        backPath.reset()
        backPath.moveTo(cx - w * 0.24f, cy - h * 0.25f)
        backPath.lineTo(cx + w * 0.14f, cy - h * 0.05f)
        backPath.lineTo(cx - w * 0.24f, cy + h * 0.25f)
        backPath.close()
        canvas.drawPath(backPath, backWingPaint)

        // Foreground luminous play prism
        frontPaintShader(rect)
        frontPath.reset()
        frontPath.moveTo(cx - w * 0.12f, cy - h * 0.24f)
        frontPath.lineTo(cx + w * 0.28f, cy)
        frontPath.lineTo(cx - w * 0.12f, cy + h * 0.24f)
        frontPath.lineTo(cx - w * 0.02f, cy)
        frontPath.close()
        canvas.drawPath(frontPath, frontPrismPaint)
        canvas.drawPath(frontPath, accentEdgePaint)

        // Subtle emerald node dot in top-right corner of badge
        canvas.drawCircle(cx + w * 0.27f, cy - h * 0.25f, w * 0.055f, sparkPaint)
    }

    private fun frontPaintShader(boundsRect: RectF) {
        frontPrismPaint.shader = LinearGradient(
            boundsRect.left, boundsRect.top, boundsRect.right, boundsRect.bottom,
            Color.parseColor("#F8FAFC"),
            Color.parseColor("#93C5FD"),
            Shader.TileMode.CLAMP
        )
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        borderPaint.alpha = alpha
        frontPrismPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        frontPrismPaint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Symbolic vector microphone icon Drawable (0 MB bitmap RAM).
 */
class MicIconDrawable(
    private val context: Context,
    initialColor: Int = Color.parseColor("#CBD5E1")
) : Drawable() {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvUiKit.dpF(context, 1.65f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = initialColor
    }
    private val capsuleRect = RectF()
    private val arcRect = RectF()

    fun setTintStroke(color: Int) {
        strokePaint.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val s = minOf(b.width(), b.height()).toFloat()

        // Mic capsule
        val capHalfW = s * 0.15f
        val capTop = cy - s * 0.34f
        val capBot = cy + s * 0.05f
        capsuleRect.set(cx - capHalfW, capTop, cx + capHalfW, capBot)
        canvas.drawRoundRect(capsuleRect, capHalfW, capHalfW, strokePaint)

        // U-shaped pickup cradle arc
        val arcR = s * 0.28f
        arcRect.set(cx - arcR, cy - s * 0.22f, cx + arcR, cy + s * 0.18f)
        canvas.drawArc(arcRect, 0f, 180f, false, strokePaint)

        // Vertical stem + horizontal base
        val stemTop = cy + s * 0.18f
        val stemBot = cy + s * 0.34f
        canvas.drawLine(cx, stemTop, cx, stemBot, strokePaint)
        canvas.drawLine(cx - s * 0.16f, stemBot, cx + s * 0.16f, stemBot, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Lightweight Drawable for the signature XIRTUS / PASSPORT indigo card header:
 * #152238 -> #0F1725 gradient + concentric watermark arc rings via Canvas.drawArc (0 MB bitmap RAM).
 */
class XirtusPassportHeaderDrawable(
    private val context: Context,
    private val topCornerRadiusDp: Float = 20f
) : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outerArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvUiKit.dpF(context, 18f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#88223556")
    }
    private val innerArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TvUiKit.dpF(context, 12f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#662A4365")
    }
    private val clipPath = Path()
    private val arcRect = RectF()
    private val boundsRect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        boundsRect.set(b)
        val rad = TvUiKit.dpF(context, topCornerRadiusDp)
        clipPath.reset()
        clipPath.addRoundRect(
            boundsRect,
            floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f),
            Path.Direction.CW
        )

        bgPaint.shader = LinearGradient(
            b.left.toFloat(),
            b.top.toFloat(),
            b.right.toFloat(),
            b.bottom.toFloat(),
            XirtusPalette.INDIGO_START,
            XirtusPalette.INDIGO_END,
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(boundsRect, bgPaint)

        val ringSize1 = b.height() * 1.65f
        val cx = b.right - b.width() * 0.14f
        val cy = b.top + b.height() * 0.22f
        arcRect.set(cx - ringSize1 / 2f, cy - ringSize1 / 2f, cx + ringSize1 / 2f, cy + ringSize1 / 2f)
        canvas.drawArc(arcRect, 35f, 185f, false, outerArcPaint)

        val ringSize2 = b.height() * 2.18f
        arcRect.set(cx - ringSize2 / 2f, cy - ringSize2 / 2f, cx + ringSize2 / 2f, cy + ringSize2 / 2f)
        canvas.drawArc(arcRect, 35f, 185f, false, innerArcPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object TvUiKit {

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    fun dpF(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )

    fun createRootCanvasDrawable(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(XirtusPalette.CANVAS_START, XirtusPalette.CANVAS_END)
        )

    fun createGlassPanelDrawable(
        context: Context,
        radiusDp: Float = 20f,
        fillColor: Int = XirtusPalette.PANEL_GLASS,
        strokeColor: Int = XirtusPalette.HAIRLINE
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = dpF(context, radiusDp)
        setStroke(dp(context, 1).coerceAtLeast(1), strokeColor)
    }

    fun applyRoundedClip(view: View, radiusDp: Float) {
        val radPx = dpF(view.context, radiusDp)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radPx)
            }
        }
        view.clipToOutline = true
    }

    fun hairlineDivider(
        context: Context,
        topMarginDp: Int = 0,
        bottomMarginDp: Int = 0,
        color: Int = XirtusPalette.DIVIDER
    ): View = View(context).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 1).coerceAtLeast(1)
        ).apply {
            topMargin = dp(context, topMarginDp)
            bottomMargin = dp(context, bottomMarginDp)
        }
    }

    fun eyebrow(context: Context, label: String): TextView = TextView(context).apply {
        text = label.uppercase()
        typeface = XirtusFonts.monoMedium(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        letterSpacing = 0.14f
        setTextColor(XirtusPalette.TEXT_EYEBROW)
    }

    fun displayTitle(context: Context, label: String, sp: Float = 28f): TextView = TextView(context).apply {
        text = label
        typeface = XirtusFonts.semiBold(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        letterSpacing = -0.02f
        setTextColor(XirtusPalette.TEXT_PRIMARY)
    }

    fun bodyText(context: Context, label: String, sp: Float = 13f): TextView = TextView(context).apply {
        text = label
        typeface = XirtusFonts.regular(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(XirtusPalette.TEXT_BODY)
        setLineSpacing(0f, 1.32f)
    }

    fun applyCardFocusEffect(view: View, focusedScale: Float = 1.03f, onFocusExtra: ((Boolean) -> Unit)? = null) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.setBackgroundResource(R.drawable.bg_card_focusable)
        view.setOnFocusChangeListener { v, hasFocus ->
            val target = if (hasFocus) focusedScale else 1.0f
            v.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(120L)
                .start()
            v.elevation = if (hasFocus) 10f else 0f
            onFocusExtra?.invoke(hasFocus)
        }
    }

    /**
     * Navigation bar tab pill matching 'Passport' / 'Messages' / 'Contacts' in the Xirtus reference UI:
     * Inactive: transparent fill, #94A3B8 Geist Medium 13sp, 10dp radius.
     * Selected: #1E2736 fill + 1dp #38FFFFFF border + #F8FAFC text.
     * Focused: #273347 fill + 1.5dp #CCFFFFFF luminous silver-white border + 1.03f scale.
     */
    fun createNavTabPill(
        context: Context,
        text: String,
        selected: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = XirtusFonts.medium(context)
            gravity = Gravity.CENTER
            setPadding(dp(context, 15), dp(context, 8), dp(context, 15), dp(context, 8))
            isFocusable = true
            isClickable = true
            isSelected = selected
            setBackgroundResource(R.drawable.bg_nav_tab_focusable)
            setTextColor(if (selected) XirtusPalette.TEXT_PRIMARY else XirtusPalette.TEXT_SECONDARY)
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(
                    when {
                        hasFocus -> Color.WHITE
                        v.isSelected -> XirtusPalette.TEXT_PRIMARY
                        else -> XirtusPalette.TEXT_SECONDARY
                    }
                )
                val sc = if (hasFocus) 1.03f else 1.0f
                v.animate().scaleX(sc).scaleY(sc).setDuration(110L).start()
            }
            setOnClickListener { onClick() }
        }
    }

    fun setNavTabSelected(pill: TextView, selected: Boolean) {
        pill.isSelected = selected
        if (!pill.hasFocus()) {
            pill.setTextColor(if (selected) XirtusPalette.TEXT_PRIMARY else XirtusPalette.TEXT_SECONDARY)
        }
    }

    /**
     * Frosted slate glass action / filter pill matching 'Continue exploring ↗' in the Xirtus UI.
     */
    fun createPillButton(
        context: Context,
        text: String,
        selected: Boolean = false,
        compact: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val padH = if (compact) dp(context, 12) else dp(context, 15)
        val padV = if (compact) dp(context, 6) else dp(context, 8)
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 13f)
            typeface = XirtusFonts.medium(context)
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            isFocusable = true
            isClickable = true
            isSelected = selected
            setBackgroundResource(R.drawable.bg_pill_focusable)
            setTextColor(if (selected) XirtusPalette.TEXT_PRIMARY else Color.parseColor("#CBD5E1"))
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(
                    when {
                        hasFocus -> Color.WHITE
                        v.isSelected -> XirtusPalette.TEXT_PRIMARY
                        else -> Color.parseColor("#CBD5E1")
                    }
                )
                val sc = if (hasFocus) 1.03f else 1.0f
                v.animate().scaleX(sc).scaleY(sc).setDuration(110L).start()
            }
            setOnClickListener { onClick() }
        }
    }

    fun setPillSelected(pill: TextView, selected: Boolean) {
        pill.isSelected = selected
        if (!pill.hasFocus()) {
            pill.setTextColor(if (selected) XirtusPalette.TEXT_PRIMARY else Color.parseColor("#CBD5E1"))
        }
    }

    fun createIconPillButton(
        context: Context,
        icon: MicIconDrawable,
        contentDesc: String,
        onClick: () -> Unit
    ): ImageView {
        val padH = dp(context, 11)
        val padV = dp(context, 8)
        return ImageView(context).apply {
            contentDescription = contentDesc
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(icon)
            setPadding(padH, padV, padH, padV)
            minimumWidth = dp(context, 42)
            minimumHeight = dp(context, 38)
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_pill_focusable)
            setOnFocusChangeListener { v, hasFocus ->
                icon.setTintStroke(if (hasFocus) Color.WHITE else Color.parseColor("#CBD5E1"))
                val sc = if (hasFocus) 1.05f else 1.0f
                v.animate().scaleX(sc).scaleY(sc).setDuration(110L).start()
            }
            setOnClickListener { onClick() }
        }
    }

    fun parseKodiBbCode(raw: String): CharSequence {
        if (!raw.contains('[')) return raw
        val s = raw.replace("[CR]", "\n")
        val sb = SpannableStringBuilder()
        val colorStack = ArrayDeque<Pair<Int, Int>>()
        var boldStart = -1

        val tagRegex = Regex("""\[(/?)(COLOR(?:\s+([^\]]+))?|B|I|LIGHT|UPPERCASE|LOWERCASE)]""", RegexOption.IGNORE_CASE)
        var lastEnd = 0
        for (match in tagRegex.findAll(s)) {
            if (match.range.first > lastEnd) {
                sb.append(s.substring(lastEnd, match.range.first))
            }
            val isClosing = match.groupValues[1] == "/"
            val fullTag = match.groupValues[2].uppercase()
            val colorArg = match.groupValues[3].trim().lowercase()

            when {
                !isClosing && fullTag.startsWith("COLOR") -> {
                    colorStack.addLast(sb.length to mapKodiColor(colorArg))
                }
                isClosing && fullTag == "COLOR" -> {
                    if (colorStack.isNotEmpty()) {
                        val (startIdx, col) = colorStack.removeLast()
                        if (sb.length > startIdx) {
                            sb.setSpan(ForegroundColorSpan(col), startIdx, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                !isClosing && fullTag == "B" -> {
                    if (boldStart < 0) boldStart = sb.length
                }
                isClosing && fullTag == "B" -> {
                    if (boldStart in 0 until sb.length) {
                        sb.setSpan(StyleSpan(Typeface.BOLD), boldStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    boldStart = -1
                }
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < s.length) {
            sb.append(s.substring(lastEnd))
        }
        return sb
    }

    private fun mapKodiColor(name: String): Int {
        return when (name) {
            "hotpink", "deeppink", "lightpink", "pink" -> Color.parseColor("#F472B6")
            "skyblue", "deepskyblue", "cyan", "aqua" -> Color.parseColor("#E2E8F0")
            "orange", "gold", "yellow" -> Color.parseColor("#FBBF24")
            "red", "crimson", "tomato" -> Color.parseColor("#F87171")
            "green", "lime", "lightgreen" -> Color.parseColor("#34D399")
            "violet", "orchid", "magenta", "purple" -> Color.parseColor("#C4B5FD")
            "gray", "grey", "silver" -> Color.parseColor("#94A3B8")
            "white" -> Color.parseColor("#F8FAFC")
            else -> runCatching {
                if (name.startsWith("#")) Color.parseColor(name) else Color.parseColor("#F8FAFC")
            }.getOrDefault(Color.parseColor("#F8FAFC"))
        }
    }

    fun generateQrBitmap(content: String, sizePx: Int = 360): Bitmap? {
        return runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            val dark = Color.parseColor("#0D1118")
            val light = Color.parseColor("#F8FAFC")
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) dark else light)
                }
            }
            bmp
        }.getOrNull()
    }

    private fun styleXirtusDialog(context: Context, dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(Color.parseColor("#F2131923"))
                cornerRadius = dpF(context, 22f)
                setStroke(dp(context, 1).coerceAtLeast(1), Color.parseColor("#2EFFFFFF"))
            }
        )
    }

    fun showSelectDialog(
        context: Context,
        title: String,
        options: List<String>,
        selectedIndex: Int = -1,
        eyebrowLabel: String = "XIRTUS / SELECTOR",
        onSelected: (Int) -> Unit
    ) {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 18))
        }
        outer.addView(eyebrow(context, eyebrowLabel))
        outer.addView(TextView(context).apply {
            text = title
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = XirtusFonts.semiBold(context)
            letterSpacing = -0.02f
            setPadding(0, dp(context, 2), 0, dp(context, 10))
        })
        outer.addView(hairlineDivider(context, bottomMarginDp = 12))

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 340).coerceAtMost(dp(context, (options.size * 48 + 16).coerceAtLeast(90)))
            )
        }
        val listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        var chosen = false
        lateinit var dlg: AlertDialog
        var focusTarget: View? = null

        options.forEachIndexed { idx, opt ->
            val isSel = idx == selectedIndex
            val labelText = if (isSel && !opt.endsWith("✓")) "$opt   ✓" else opt
            val btn = createPillButton(context, labelText, selected = isSel) {
                if (!chosen) {
                    chosen = true
                    dlg.dismiss()
                    onSelected(idx)
                }
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, 6) }
            }
            if (isSel || (focusTarget == null && idx == 0)) {
                focusTarget = btn
            }
            listCol.addView(btn)
        }
        scroll.addView(listCol)
        outer.addView(scroll)

        dlg = AlertDialog.Builder(context)
            .setView(outer)
            .setOnCancelListener {
                if (!chosen) {
                    chosen = true
                    onSelected(-1)
                }
            }
            .create()
        dlg.show()
        styleXirtusDialog(context, dlg)
        focusTarget?.post { focusTarget?.requestFocus() }
    }

    fun showInputDialog(
        context: Context,
        title: String,
        initialValue: String = "",
        hint: String = "",
        hidden: Boolean = false,
        onSubmit: (String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 26), dp(context, 22), dp(context, 26), dp(context, 22))
        }
        root.addView(eyebrow(context, "XIRTUS / INPUT"))
        val titleView = TextView(context).apply {
            text = title
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = XirtusFonts.semiBold(context)
            letterSpacing = -0.02f
            setPadding(0, dp(context, 2), 0, 0)
        }
        val edit = EditText(context).apply {
            setText(initialValue)
            setHint(hint)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            typeface = XirtusFonts.regular(context)
            inputType = if (hidden) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            setSingleLine(true)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1118"))
                cornerRadius = dpF(context, 10f)
                setStroke(dp(context, 1).coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 14)
                bottomMargin = dp(context, 16)
            }
        }
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        var done = false
        lateinit var dlg: AlertDialog

        val cancelBtn = createPillButton(context, "Cancel") {
            if (!done) {
                done = true
                dlg.dismiss()
                onCancel?.invoke()
            }
        }
        val okBtn = createPillButton(context, "Confirm", selected = true) {
            if (!done) {
                done = true
                val txt = edit.text?.toString()?.trim().orEmpty()
                dlg.dismiss()
                onSubmit(txt)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(context, 10) }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(okBtn)
        root.addView(titleView)
        root.addView(edit)
        root.addView(btnRow)

        dlg = AlertDialog.Builder(context)
            .setView(root)
            .setOnCancelListener {
                if (!done) {
                    done = true
                    onCancel?.invoke()
                }
            }
            .create()
        dlg.show()
        styleXirtusDialog(context, dlg)
        edit.requestFocus()
    }

    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        yesText: String = "Confirm",
        noText: String = "Cancel",
        onResult: (Boolean) -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 26), dp(context, 22), dp(context, 26), dp(context, 22))
        }
        root.addView(eyebrow(context, "XIRTUS / CONFIRMATION"))
        root.addView(TextView(context).apply {
            text = title
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = XirtusFonts.semiBold(context)
            setPadding(0, dp(context, 2), 0, 0)
        })
        root.addView(TextView(context).apply {
            text = message
            setTextColor(XirtusPalette.TEXT_BODY)
            typeface = XirtusFonts.regular(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(context, 10), 0, dp(context, 18))
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        var done = false
        lateinit var dlg: AlertDialog
        val noBtn = createPillButton(context, noText) {
            if (!done) {
                done = true
                dlg.dismiss()
                onResult(false)
            }
        }
        val yesBtn = createPillButton(context, yesText, selected = true) {
            if (!done) {
                done = true
                dlg.dismiss()
                onResult(true)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(context, 10) }
        }
        row.addView(noBtn)
        row.addView(yesBtn)
        root.addView(row)

        dlg = AlertDialog.Builder(context)
            .setView(root)
            .setOnCancelListener {
                if (!done) {
                    done = true
                    onResult(false)
                }
            }
            .create()
        dlg.show()
        styleXirtusDialog(context, dlg)
        yesBtn.requestFocus()
    }

    fun showTextViewerDialog(context: Context, title: String, body: String) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 26), dp(context, 22), dp(context, 26), dp(context, 22))
        }
        root.addView(eyebrow(context, "XIRTUS / DOCUMENT"))
        root.addView(TextView(context).apply {
            text = title
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = XirtusFonts.semiBold(context)
            setPadding(0, dp(context, 2), 0, 0)
        })
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 260)
            ).apply { topMargin = dp(context, 10); bottomMargin = dp(context, 14) }
        }
        scroll.addView(TextView(context).apply {
            text = body
            setTextColor(XirtusPalette.TEXT_BODY)
            typeface = XirtusFonts.regular(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        root.addView(scroll)
        lateinit var dlg: AlertDialog
        val closeBtn = createPillButton(context, "Close") { dlg.dismiss() }
        root.addView(closeBtn)
        dlg = AlertDialog.Builder(context).setView(root).create()
        dlg.show()
        styleXirtusDialog(context, dlg)
        closeBtn.requestFocus()
    }

    fun showQrModal(
        context: Context,
        code: String,
        linkUrl: String,
        qrTargetUrl: String,
        eyebrowLabel: String = "XIRTUS PASSPORT / STREMIO LINK",
        title: String = "Pair Stremio Subscriptions",
        onDismissed: () -> Unit
    ): AlertDialog {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 28), dp(context, 24), dp(context, 28), dp(context, 24))
        }

        val qrFrame = LinearLayout(context).apply {
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8FAFC"))
                cornerRadius = dpF(context, 14f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(context, 26) }
        }
        val qrImg = ImageView(context).apply {
            val size = dp(context, 184)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageBitmap(generateQrBitmap(qrTargetUrl, 360))
        }
        qrFrame.addView(qrImg)
        root.addView(qrFrame)

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(eyebrow(context, eyebrowLabel))
        infoCol.addView(TextView(context).apply {
            text = title
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = XirtusFonts.semiBold(context)
            letterSpacing = -0.02f
            setPadding(0, dp(context, 2), 0, 0)
        })
        infoCol.addView(TextView(context).apply {
            text = "Scan with your phone camera or open:\n$linkUrl"
            setTextColor(XirtusPalette.TEXT_BODY)
            typeface = XirtusFonts.regular(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(context, 10), 0, dp(context, 10))
        })
        infoCol.addView(TextView(context).apply {
            text = "PAIR CODE · $code"
            setTextColor(XirtusPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = XirtusFonts.monoMedium(context)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(context, 16))
        })

        lateinit var dlg: AlertDialog
        val cancelBtn = createPillButton(context, "Close") {
            dlg.dismiss()
        }
        infoCol.addView(cancelBtn)
        root.addView(infoCol)

        dlg = AlertDialog.Builder(context)
            .setView(root)
            .setOnDismissListener { onDismissed() }
            .create()
        dlg.show()
        styleXirtusDialog(context, dlg)
        cancelBtn.requestFocus()
        return dlg
    }
}
