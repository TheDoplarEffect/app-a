package com.example.appa

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ATOMIC COMPONENT LIBRARY.
 *
 * Every component is isolated: it reads all of its values (colors, type,
 * spacing, radii, motion) from AppTheme.current at build time, takes no
 * layout context assumptions, and exposes only semantic parameters
 * (label, icon, onClick…). To change the design system, swap the tokens in
 * Tokens.kt — no component code changes needed.
 *
 * Hierarchy: Tokens.kt (design values) → this file (atoms) → screens (organisms).
 */

// ------------------------------------------------------------------ primitives

/** Layout size shorthands (same values as ViewGroup.LayoutParams). */
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

enum class TextStyle { DISPLAY, HEADLINE, TITLE, BODY, LABEL }

fun col(ctx: Context, t: DesignTokens = AppTheme.current, init: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; init() }

fun row(ctx: Context, t: DesignTokens = AppTheme.current, init: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; init() }

fun LinearLayout.lp(w: Int, h: Int, weight: Float = 0f, marginTopDp: Int = 0, marginStartDp: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply {
        topMargin = context.dp(marginTopDp)
        marginStart = context.dp(marginStartDp)
    }

/** Semantic text atom. */
fun AppText(
    ctx: Context,
    text: String,
    style: TextStyle = TextStyle.BODY,
    color: Int? = null,
    bold: Boolean = false,
    t: DesignTokens = AppTheme.current
): TextView {
    val size = when (style) {
        TextStyle.DISPLAY -> t.type.displaySp
        TextStyle.HEADLINE -> t.type.headlineSp
        TextStyle.TITLE -> t.type.titleSp
        TextStyle.BODY -> t.type.bodySp
        TextStyle.LABEL -> t.type.labelSp
    }
    return TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color ?: when (style) {
            TextStyle.DISPLAY, TextStyle.HEADLINE, TextStyle.TITLE, TextStyle.BODY -> t.colors.textPrimary
            TextStyle.LABEL -> t.colors.textSecondary
        })
        if (bold || style == TextStyle.DISPLAY || style == TextStyle.HEADLINE) setTypeface(null, android.graphics.Typeface.BOLD)
    }
}

/** Clip any view to rounded corners (posters, thumbs). */
fun View.roundedClip(radiusDp: Int): View {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, context.dp(radiusDp).toFloat())
        }
    }
    return this
}

/** Touch feedback: selectable-item ripple + press scale. */
fun View.pressable(): View {
    val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
    val resId = ta.getResourceId(0, 0)
    ta.recycle()
    if (resId != 0) foreground = context.getDrawable(resId)
    return withPressScale()
}

// ------------------------------------------------------------------ top bar

data class TopBarAction(val icon: String, val contentDescription: String = icon, var badge: Int = 0, val onClick: () -> Unit)

/**
 * Top app bar atom: large title + optional subtitle on the left, circular
 * glass icon actions on the right. Callers pad it with padSystemBars(top).
 */
fun AppTopBar(
    ctx: Context,
    title: String,
    subtitle: String? = null,
    actions: List<TopBarAction> = emptyList(),
    leading: TopBarAction? = null,
    t: DesignTokens = AppTheme.current
): LinearLayout = col(ctx) {
    row(ctx) {
        gravity = Gravity.CENTER_VERTICAL
        if (leading != null) addView(CircleIconButton(ctx, leading.icon, 0, t, leading.contentDescription, leading.onClick), lp(WRAP, WRAP, marginStartDp = 0))
        col(ctx) {
            addView(AppText(ctx, title, TextStyle.HEADLINE, t = t))
            if (subtitle != null) addView(AppText(ctx, subtitle, TextStyle.LABEL, t = t))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ctx.dp(if (leading != null) 12 else 0) }
        }
        actions.forEach { a ->
            addView(CircleIconButton(ctx, a.icon, a.badge, t, a.contentDescription, a.onClick).apply { tag = "top_action_${a.icon}" }, lp(WRAP, WRAP, marginStartDp = 8))
        }
    }.let { addView(it, lp(MATCH, WRAP)) }
}

// ------------------------------------------------------------------ circle icon button

/** 40dp circular glass icon button with optional count badge. */
fun CircleIconButton(
    ctx: Context,
    icon: String,
    badge: Int = 0,
    t: DesignTokens = AppTheme.current,
    contentDescription: String = icon,
    onClick: () -> Unit
): FrameLayout {
    val size = ctx.dp(48) // M3 minimum touch target
    return FrameLayout(ctx).apply {
        glass(t.radius.pill, t = t)
        addView(AppText(ctx, icon, TextStyle.TITLE, t = t).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        if (badge > 0) {
            addView(Chip(ctx, badge.toString(), withAlpha(t.colors.error, 0.30f), fg = t.colors.error, t = t).apply { tag = "badge" },
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                    topMargin = ctx.dp(2); marginEnd = ctx.dp(2)
                })
        }
        this.contentDescription = contentDescription
        setOnClickListener { onClick() }
        pressable()
    }
}

/** Update the count badge of a [CircleIconButton] (tagged "badge" child). */
fun FrameLayout.setBadge(n: Int, t: DesignTokens = AppTheme.current) {
    val existing = findViewWithTag<TextView>("badge")
    when {
        n > 0 && existing == null -> addView(
            Chip(context, n.toString(), withAlpha(t.colors.error, 0.30f), fg = t.colors.error, t = t).apply { tag = "badge" },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                topMargin = context.dp(2); marginEnd = context.dp(2)
            }
        )
        n > 0 -> existing.text = n.toString()
        existing != null -> removeView(existing)
    }
}

// ------------------------------------------------------------------ buttons

/** Primary button: accent gradient pill, white label. */
fun PrimaryButton(ctx: Context, label: String, enabled: Boolean = true, t: DesignTokens = AppTheme.current, onClick: (TextView) -> Unit): TextView =
    AppText(ctx, label, TextStyle.TITLE, color = t.colors.textOnAccent, bold = true, t = t).apply {
        gravity = Gravity.CENTER
        background = ctx.accentGradient(t.radius.button, t)
        val px = ctx.dp(20); val py = ctx.dp(14)
        setPadding(px, py, px, py)
        minimumHeight = ctx.dp(48)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { onClick(this) }
        pressable()
    }

/** SecondaryButton/DangerButton share [buttonBase]; PrimaryButton keeps its gradient + enabled state. */

/** Shared button base: padding, 48dp minimum target, press feedback. */
private fun buttonBase(
    ctx: Context,
    label: String,
    style: TextStyle,
    color: Int,
    t: DesignTokens,
    hPadDp: Int,
    onClick: () -> Unit
): TextView =
    AppText(ctx, label, style, color = color, bold = true, t = t).apply {
        gravity = Gravity.CENTER
        val px = ctx.dp(hPadDp); val py = ctx.dp(14)
        setPadding(px, py, px, py)
        minimumHeight = ctx.dp(48)
        setOnClickListener { onClick() }
        pressable()
    }

/** Secondary button: glass pill with accent label. */
fun SecondaryButton(ctx: Context, label: String, t: DesignTokens = AppTheme.current, onClick: () -> Unit): TextView =
    buttonBase(ctx, label, TextStyle.TITLE, t.colors.accentText, t, 16, onClick).apply {
        glass(t.radius.button, t = t)
    }

/** Danger button: glass pill with error-colored label. */
fun DangerButton(ctx: Context, label: String, t: DesignTokens = AppTheme.current, onClick: () -> Unit): TextView =
    buttonBase(ctx, label, TextStyle.BODY, t.colors.error, t, 14, onClick).apply {
        glass(t.radius.button, t = t)
    }

// ------------------------------------------------------------------ bottom nav

data class NavItem(val icon: String, val label: String)

/**
 * Bottom navigation atom with an animated active-pill indicator (Material 3
 * style). Exposes [select] to move the pill with a spring.
 */
class BottomNavBar(private val ctx: Context, val items: List<NavItem>, initial: Int, private val t: DesignTokens = AppTheme.current, val onSelect: (Int) -> Unit) {
    val root: LinearLayout = row(ctx) { gravity = Gravity.CENTER }
    private val cells = mutableListOf<FrameLayout>()
    private val pills = mutableListOf<View>()
    private val icons = mutableListOf<TextView>()
    private val labels = mutableListOf<TextView>()
    var selected = initial
        private set
    private var initialized = false

    init {
        root.glass(t.radius.card, strong = true, t = t)
        root.clipToOutline = true
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(t.radius.card).toFloat())
            }
        }
        val p = ctx.dp(6); root.setPadding(p, p, p, p)
        items.forEachIndexed { i, item ->
            // pill sits BEHIND the label inside the same cell -> always centered
            val pill = View(ctx).apply {
                background = ctx.accentGradient(t.radius.pill, t)
                scaleX = 0f
                alpha = 0f
                elevation = ctx.dpF(t.elevation.level1Dp)
            }
            val icon = AppText(ctx, item.icon, TextStyle.TITLE, t = t)
            val label = AppText(ctx, item.label, TextStyle.LABEL, t = t)
            val labelCol = col(ctx) {
                gravity = Gravity.CENTER
                addView(icon)
                addView(label)
            }
            val cell = FrameLayout(ctx).apply {
                val pad = ctx.dp(8)
                labelCol.setPadding(pad, pad, pad, pad)
                addView(pill, FrameLayout.LayoutParams(MATCH, ctx.dp(48), Gravity.CENTER))
                addView(labelCol, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                setOnClickListener { select(i) }
                pressable()
                contentDescription = item.label
            }
            root.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            cells.add(cell); pills.add(pill); icons.add(icon); labels.add(label)
        }
        root.post { select(initial, animate = false); initialized = true }
    }

    fun select(i: Int, animate: Boolean = true) {
        if (i == selected && initialized) return
        selected = i
        val m = t.motion
        val c = t.colors
        cells.forEachIndexed { idx, _ ->
            val active = idx == i
            val pill = pills[idx]
            icons[idx].setTextColor(if (active) c.textOnAccent else c.textSecondary)
            labels[idx].setTextColor(if (active) c.textOnAccent else c.textSecondary)
            if (animate) {
                pill.animate().scaleX(if (active) 1f else 0f).alpha(if (active) 1f else 0f)
                    .setDuration(if (active) m.mediumMs else m.shortMs)
                    .setInterpolator(if (active) EmphasizedOvershoot else FastOutSlowIn)
                    .start()
            } else {
                pill.scaleX = if (active) 1f else 0f
                pill.alpha = if (active) 1f else 0f
            }
        }
        onSelect(i)
    }
}

// ------------------------------------------------------------------ search field

/** Glass search field atom with icon, debounce and a clear button. */
class SearchField(ctx: Context, hint: String, t: DesignTokens = AppTheme.current, onQuery: (String) -> Unit) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: Runnable? = null
    lateinit var input: EditText
        private set
    val root: LinearLayout = row(ctx) {
        gravity = Gravity.CENTER_VERTICAL
        glass(t.radius.button, t = t)
        val p = ctx.dp(14); setPadding(p, ctx.dp(10), p, ctx.dp(10))
        addView(AppText(ctx, "🔍", TextStyle.BODY, t = t))
        input = EditText(ctx).apply {
            this.hint = hint
            setHintTextColor(t.colors.textSecondary)
            setTextColor(t.colors.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.type.bodySp)
            background = null
            minimumHeight = ctx.dp(48)
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ctx.dp(8) }
            layoutParams = lp
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
        }
        addView(input)
        val clear = FrameLayout(ctx).apply {
            visibility = View.GONE
            val size = ctx.dp(48)
            addView(AppText(ctx, "✕", TextStyle.BODY, color = t.colors.textSecondary, t = t).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            contentDescription = "Clear search"
            setOnClickListener { input.setText("") }
            pressable()
        }
        addView(clear)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                pending?.let { handler.removeCallbacks(it) }
                pending = Runnable { onQuery(s?.toString() ?: "") }
                handler.postDelayed(pending!!, 350)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
}

// ------------------------------------------------------------------ chips & pills

fun Chip(ctx: Context, text: String, bg: Int, fg: Int? = null, t: DesignTokens = AppTheme.current): TextView =
    AppText(ctx, text, TextStyle.LABEL, color = fg ?: t.colors.textOnAccent, bold = true, t = t).apply {
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ctx.dp(t.radius.pill).toFloat()
            setColor(bg)
        }
        val px = ctx.dp(8); val py = ctx.dp(3)
        setPadding(px, py, px, py)
    }

/** Recolor an existing chip (used for live test-result updates). */
fun TextView.restyleChip(color: Int, t: DesignTokens = AppTheme.current) {
    (background as? android.graphics.drawable.GradientDrawable)?.setColor(withAlpha(color, 0.22f))
    setTextColor(color)
}

fun statusColorOf(status: RequestStatus?, available: Boolean, t: DesignTokens = AppTheme.current): Int = when {
    available || status == RequestStatus.AVAILABLE -> t.colors.success
    status == RequestStatus.PENDING -> t.colors.warning
    status == RequestStatus.PROCESSING -> t.colors.info
    else -> t.colors.textSecondary
}

fun statusLabelOf(media: Media, status: RequestStatus?): String? = when {
    media.available || status == RequestStatus.AVAILABLE -> "✓ Available"
    status == RequestStatus.PENDING -> "⏳ Pending"
    status == RequestStatus.PROCESSING -> "⚙ Processing"
    else -> null
}

fun StatusPill(ctx: Context, media: Media, status: RequestStatus?, t: DesignTokens = AppTheme.current): TextView? {
    val label = statusLabelOf(media, status) ?: return null
    val color = statusColorOf(status, media.available, t)
    // tinted background + colored text: passes WCAG contrast on dark and light themes
    return Chip(ctx, label, withAlpha(color, 0.22f), fg = color, t = t)
}

// ------------------------------------------------------------------ glass card

/** Glass card atom: translucent rounded container with padding. */
fun GlassCard(ctx: Context, padDp: Int = 16, strong: Boolean = false, t: DesignTokens = AppTheme.current, init: LinearLayout.() -> Unit): FrameLayout {
    val inner = col(ctx, t, init)
    val p = ctx.dp(padDp)
    inner.setPadding(p, p, p, p)
    return FrameLayout(ctx).apply {
        glass(t.radius.card, strong = strong, t = t)
        addView(inner, FrameLayout.LayoutParams(MATCH, WRAP))
    }
}

// ------------------------------------------------------------------ poster card

/** Gradient placeholder poster (replaces old PosterView) — token-driven, rounded. */
class GradientPoster(ctx: Context, val media: Media, t: DesignTokens = AppTheme.current) : View(ctx) {
    private val c1 = Color.parseColor(media.color1)
    private val c2 = Color.parseColor(media.color2)
    // pick a text color that keeps WCAG contrast on this gradient
    // (WCAG relative-luminance threshold for switching text polarity is ~0.183)
    private val textColor: Int = run {
        val avg = ((luminance(c1) + luminance(c2)) / 2.0)
        if (avg > 0.183) 0xFF101828.toInt() else t.colors.textOnAccent
    }
    private val scrimColor: Int = if (textColor == 0xFF101828.toInt()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER
    }
    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(textColor, 0.85f); textAlign = android.graphics.Paint.Align.CENTER
    }
    private val bgPaint = android.graphics.Paint()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = when (MeasureSpec.getMode(hSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(hSpec)
            else -> w * 3 / 2 // 2:3 poster aspect
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bgPaint.shader = android.graphics.LinearGradient(0f, 0f, w, h, c1, c2, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val deco = android.graphics.Paint().apply { color = withAlpha(textColor, 0.08f) }
        canvas.drawCircle(w * 0.84f, h * 0.14f, w * 0.33f, deco)
        labelPaint.textSize = w * 0.05f
        canvas.drawText(media.type.label.uppercase(), w / 2f, h * 0.12f, labelPaint)
        // scrim behind the title so text keeps WCAG contrast on any gradient
        val scrim = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, h * 0.30f, 0f, h * 0.85f,
                withAlpha(scrimColor, 0.62f), withAlpha(scrimColor, 0f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, h * 0.30f, w, h * 0.85f, scrim)
        val words = media.title.split(" ").filter { it.isNotBlank() }
        val lines = mutableListOf<String>(); var cur = ""
        for (word in words) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (cand.length <= 14) cur = cand else { if (cur.isNotEmpty()) lines.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        val shown = if (lines.size > 3) lines.take(2) + lines[2].take(11) + "…" else lines
        val fs = when (shown.size) { 1 -> w * 0.115f; 2 -> w * 0.10f; else -> w * 0.08f }
        titlePaint.textSize = fs
        val lh = fs * 1.15f
        val startY = h * 0.5f - (shown.size - 1) * lh / 2f + fs * 0.35f
        shown.forEachIndexed { i, line -> canvas.drawText(line, w / 2f, startY + i * lh, titlePaint) }
    }
}

/**
 * Poster grid cell atom: 2:3 poster (image or gradient) with rounded corners,
 * bottom scrim, rating chip, status badge, title + subtitle below.
 */
fun PosterCard(ctx: Context, m: Media, status: RequestStatus? = null, t: DesignTokens = AppTheme.current, onClick: () -> Unit): View {
    val cell = col(ctx) {
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        }
        val posterFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        if (m.posterUrl != null) {
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                minimumHeight = ctx.dp(150)
                roundedClip(t.radius.poster)
                contentDescription = "${m.title} poster"
            }
            posterFrame.addView(img, FrameLayout.LayoutParams(MATCH, WRAP))
            ImageLoader.load(m.posterUrl!!, img)
        } else {
            val gp = GradientPoster(ctx, m, t).apply { roundedClip(t.radius.poster) }
            posterFrame.addView(gp, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        // scrim + rating + badge overlay
        posterFrame.addView(View(ctx).apply {
            background = ctx.posterScrim(56, t)
        }, FrameLayout.LayoutParams(MATCH, ctx.dp(56), Gravity.BOTTOM))
        posterFrame.addView(Chip(ctx, "★ %.1f".format(m.rating), t.colors.chipScrim, fg = 0xFFFFFFFF.toInt(), t = t),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = ctx.dp(6); marginStart = ctx.dp(6)
            })
        StatusPill(ctx, m, status, t)?.let { pill ->
            posterFrame.addView(pill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                topMargin = ctx.dp(6); marginStart = ctx.dp(6)
            })
        }
        addView(posterFrame)
        addView(AppText(ctx, m.title, TextStyle.BODY, bold = true, t = t).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = lp(MATCH, WRAP, marginTopDp = t.space.sm)
        })
        val sub = if (m.type == MediaType.AUDIOBOOKS && m.author != null) "${m.year} · ${m.author}"
            else "${m.year} · ${m.genres.joinToString(", ")}"
        addView(AppText(ctx, sub, TextStyle.LABEL, t = t).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        contentDescription = "${m.title}, ${m.year}, ${m.type.label}"
        var lastClickMs = 0L
        setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastClickMs < 400) return@setOnClickListener // double-tap guard (re-arms, never dead)
            lastClickMs = now
            onClick()
        }
        pressable()
    }
    return cell
}

// ------------------------------------------------------------------ section header / empty / skeleton

fun SectionHeader(ctx: Context, title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, t: DesignTokens = AppTheme.current): LinearLayout =
    row(ctx) {
        gravity = Gravity.CENTER_VERTICAL
        addView(AppText(ctx, title, TextStyle.TITLE, bold = true, t = t), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (actionLabel != null && onAction != null) {
            addView(AppText(ctx, actionLabel, TextStyle.LABEL, color = t.colors.accentText, bold = true, t = t).apply {
                setOnClickListener { onAction() }
                pressable()
            })
        }
    }

fun EmptyState(ctx: Context, emoji: String, title: String, message: String, t: DesignTokens = AppTheme.current): LinearLayout =
    col(ctx) {
        gravity = Gravity.CENTER
        val p = ctx.dp(32); setPadding(p, p, p, p)
        addView(AppText(ctx, emoji, TextStyle.DISPLAY, t = t).apply { gravity = Gravity.CENTER })
        addView(AppText(ctx, title, TextStyle.TITLE, bold = true, t = t).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(8) }
        })
        addView(AppText(ctx, message, TextStyle.BODY, color = t.colors.textSecondary, t = t).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(4) }
        })
    }

/** Shimmering skeleton block for loading states. */
fun Skeleton(ctx: Context, w: Int, hDp: Int, t: DesignTokens = AppTheme.current): View {
    val v = View(ctx).apply {
        glass(t.radius.button, t = t)
        layoutParams = ViewGroup.LayoutParams(w, ctx.dp(hDp))
    }
    if (ctx.reduceMotion()) {
        v.alpha = 0.6f
    } else {
        val shimmer = android.animation.ValueAnimator.ofFloat(1f, 0.4f).apply {
            duration = t.motion.longMs
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { v.alpha = it.animatedValue as Float }
        }
        v.fadeIn(durationMs = t.motion.shortMs)
        shimmer.start()
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { shimmer.cancel() }
        })
    }
    return v
}

// ------------------------------------------------------------------ magnet row

fun MagnetRow(ctx: Context, mag: LiveMagnet, t: DesignTokens = AppTheme.current, onCopy: () -> Unit): FrameLayout =
    GlassCard(ctx, padDp = 12, t = t) {
        addView(AppText(ctx, mag.name, TextStyle.BODY, bold = true, t = t).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(MATCH, WRAP))
        addView(AppText(
            ctx,
            "${formatSize(mag.sizeBytes)} · ↑ ${mag.seeders} ↓ ${mag.leechers} · ${mag.indexer}",
            TextStyle.LABEL, t = t
        ), lp(MATCH, WRAP, marginTopDp = 4))
        addView(SecondaryButton(ctx, "Copy magnet", t, onCopy), lp(WRAP, WRAP, marginTopDp = 8))
    }

// ------------------------------------------------------------------ form atoms (settings)

fun FormField(ctx: Context, label: String, value: String, hint: String = "", secret: Boolean = false, t: DesignTokens = AppTheme.current): Pair<TextView, EditText> {
    val lab = AppText(ctx, label, TextStyle.LABEL, bold = true, t = t)
    val et = EditText(ctx).apply {
        setText(value)
        if (hint.isNotEmpty()) this.hint = hint
        setHintTextColor(t.colors.textSecondary)
        setTextColor(t.colors.textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, t.type.bodySp)
        glass(t.radius.button, t = t)
        val p = ctx.dp(12); setPadding(p, p, p, p)
        minimumHeight = ctx.dp(48)
        isSingleLine = true
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    return lab to et
}

fun SwitchRow(ctx: Context, label: String, checked: Boolean, t: DesignTokens = AppTheme.current, onChange: (Boolean) -> Unit): Switch =
    Switch(ctx).apply {
        text = label
        isChecked = checked
        setTextColor(t.colors.textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, t.type.bodySp)
        minimumHeight = ctx.dp(48) // M3 touch target
        val py = ctx.dp(12); setPadding(paddingStart, py, paddingEnd, py)
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

// ------------------------------------------------------------------ misc

fun ModeBanner(ctx: Context, live: Boolean, t: DesignTokens = AppTheme.current): TextView {
    val (text, color) = if (live)
        "🌐 Live — TMDB · Sonarr/Radarr · Prowlarr · Jellyfin" to t.colors.success
    else
        "🧪 Placeholder mode — tap to connect your services in Settings" to t.colors.warning
    return Chip(ctx, text, withAlpha(color, 0.18f), color, t).apply {
        gravity = Gravity.START
        minimumHeight = ctx.dp(48)
        contentDescription = if (live) "Live mode active. Tap to open settings." else "Placeholder mode. Tap to open settings."
    }
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
