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

data class TopBarAction(val icon: String, val label: String = "", var badge: Int = 0, val onClick: () -> Unit)

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
        if (leading != null) addView(CircleIconButton(ctx, leading.icon, 0, t, leading.onClick), lp(WRAP, WRAP, marginStartDp = 0))
        col(ctx) {
            addView(AppText(ctx, title, TextStyle.HEADLINE, t = t))
            if (subtitle != null) addView(AppText(ctx, subtitle, TextStyle.LABEL, t = t))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ctx.dp(if (leading != null) 12 else 0) }
        }
        actions.forEach { a ->
            addView(CircleIconButton(ctx, a.icon, a.badge, t, a.onClick).apply { tag = "top_action_${a.icon}" }, lp(WRAP, WRAP, marginStartDp = 8))
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
    onClick: () -> Unit
): FrameLayout {
    val size = ctx.dp(40)
    return FrameLayout(ctx).apply {
        glass(t.radius.pill, t = t)
        addView(AppText(ctx, icon, TextStyle.TITLE, t = t).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        if (badge > 0) {
            addView(Chip(ctx, badge.toString(), t.colors.error, t.colors.textOnAccent, t).apply { tag = "badge" },
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                    topMargin = ctx.dp(2); marginEnd = ctx.dp(2)
                })
        }
        setOnClickListener { onClick() }
        pressable()
    }
}

/** Update the count badge of a [CircleIconButton] (tagged "badge" child). */
fun FrameLayout.setBadge(n: Int, t: DesignTokens = AppTheme.current) {
    val existing = findViewWithTag<TextView>("badge")
    when {
        n > 0 && existing == null -> addView(
            Chip(context, n.toString(), t.colors.error, t.colors.textOnAccent, t).apply { tag = "badge" },
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
fun PrimaryButton(ctx: Context, label: String, enabled: Boolean = true, t: DesignTokens = AppTheme.current, onClick: () -> Unit): TextView =
    AppText(ctx, label, TextStyle.TITLE, color = t.colors.textOnAccent, bold = true, t = t).apply {
        gravity = Gravity.CENTER
        background = ctx.accentGradient(t.radius.button, t)
        val px = ctx.dp(20); val py = ctx.dp(12)
        setPadding(px, py, px, py)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { onClick() }
        pressable()
    }

/** Secondary button: glass pill with accent label. */
fun SecondaryButton(ctx: Context, label: String, t: DesignTokens = AppTheme.current, onClick: () -> Unit): TextView =
    AppText(ctx, label, TextStyle.TITLE, color = t.colors.accentSolid, bold = true, t = t).apply {
        gravity = Gravity.CENTER
        glass(t.radius.button, t = t)
        val px = ctx.dp(16); val py = ctx.dp(12)
        setPadding(px, py, px, py)
        setOnClickListener { onClick() }
        pressable()
    }

/** Danger button: glass pill with error-colored label. */
fun DangerButton(ctx: Context, label: String, t: DesignTokens = AppTheme.current, onClick: () -> Unit): TextView =
    AppText(ctx, label, TextStyle.BODY, color = t.colors.error, bold = true, t = t).apply {
        gravity = Gravity.CENTER
        glass(t.radius.button, t = t)
        val px = ctx.dp(14); val py = ctx.dp(10)
        setPadding(px, py, px, py)
        setOnClickListener { onClick() }
        pressable()
    }

// ------------------------------------------------------------------ bottom nav

data class NavItem(val icon: String, val label: String)

/**
 * Bottom navigation atom with an animated active-pill indicator (Material 3
 * style). Exposes [select] to move the pill with a spring.
 */
class BottomNavBar(private val ctx: Context, val items: List<NavItem>, initial: Int, t: DesignTokens = AppTheme.current, val onSelect: (Int) -> Unit) {
    val root: FrameLayout = FrameLayout(ctx)
    private val indicator: View
    private val itemViews = mutableListOf<LinearLayout>()
    var selected = initial
        private set

    init {
        root.glass(t.radius.card, strong = true, t = t)
        root.clipToOutline = true
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(t.radius.card).toFloat())
            }
        }
        val bar = row(ctx) { gravity = Gravity.CENTER_VERTICAL }
        items.forEachIndexed { i, item ->
            val cell = col(ctx) {
                gravity = Gravity.CENTER
                addView(AppText(ctx, item.icon, TextStyle.TITLE, t = t))
                addView(AppText(ctx, item.label, TextStyle.LABEL, t = t))
                val p = ctx.dp(6); setPadding(p, p, p, p)
                setOnClickListener { select(i) }
                pressable()
            }
            bar.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            itemViews.add(cell)
        }
        indicator = View(ctx).apply {
            background = ctx.accentGradient(t.radius.pill, t)
            elevation = ctx.dp(4).toFloat()
        }
        root.addView(indicator, FrameLayout.LayoutParams(0, ctx.dp(44)))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        root.post { select(initial, animate = false) }
    }

    fun select(i: Int, animate: Boolean = true) {
        if (i == selected && animate) return
        selected = i
        val cell = itemViews[i]
        itemViews.forEachIndexed { idx, v ->
            val label = (v.getChildAt(1) as TextView)
            val icon = (v.getChildAt(0) as TextView)
            val active = idx == i
            label.setTextColor(if (active) AppTheme.current.colors.textOnAccent else AppTheme.current.colors.textSecondary)
            icon.setTextColor(if (active) AppTheme.current.colors.textOnAccent else AppTheme.current.colors.textSecondary)
        }
        root.post {
            val targetX = cell.x + (cell.width - ctx.dp(64)) / 2f
            val a = indicator.animate().x(targetX).setDuration(if (animate) AppTheme.current.motion.mediumMs else 0)
            a.interpolator = if (animate) EmphasizedOvershoot else FastOutSlowIn
            a.start()
            indicator.layoutParams.width = ctx.dp(64)
            indicator.requestLayout()
        }
        onSelect(i)
    }
}

// ------------------------------------------------------------------ search field

/** Glass search field atom with icon, debounce and a clear button. */
class SearchField(ctx: Context, hint: String, t: DesignTokens = AppTheme.current, onQuery: (String) -> Unit) {
    val root: LinearLayout = row(ctx) {
        gravity = Gravity.CENTER_VERTICAL
        glass(t.radius.button, t = t)
        val p = ctx.dp(14); setPadding(p, ctx.dp(10), p, ctx.dp(10))
        addView(AppText(ctx, "🔍", TextStyle.BODY, t = t))
        val input = EditText(ctx).apply {
            this.hint = hint
            setHintTextColor(t.colors.textSecondary)
            setTextColor(t.colors.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.type.bodySp)
            background = null
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ctx.dp(8) }
            layoutParams = lp
            isSingleLine = true
        }
        addView(input)
        val clear = AppText(ctx, "✕", TextStyle.BODY, color = t.colors.textSecondary, t = t).apply {
            visibility = View.GONE
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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: Runnable? = null
    val input: EditText get() = (root.getChildAt(1) as EditText)
    fun setTextSilently(q: String) {
        pending?.let { handler.removeCallbacks(it) }
        input.setText(q)
    }
}

// ------------------------------------------------------------------ chips & pills

fun Chip(ctx: Context, text: String, bg: Int, fg: Int = 0xFFFFFFFF.toInt(), t: DesignTokens = AppTheme.current): TextView =
    AppText(ctx, text, TextStyle.LABEL, color = fg, bold = true, t = t).apply {
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ctx.dp(t.radius.pill).toFloat()
            setColor(bg)
        }
        val px = ctx.dp(8); val py = ctx.dp(3)
        setPadding(px, py, px, py)
    }

fun statusColorOf(status: RequestStatus?, t: DesignTokens = AppTheme.current): Int = when (status) {
    RequestStatus.AVAILABLE -> t.colors.success
    RequestStatus.PENDING -> t.colors.warning
    RequestStatus.PROCESSING -> t.colors.info
    null -> t.colors.textSecondary
}

fun statusLabelOf(media: Media, status: RequestStatus?): String? {
    if (media.available) return "✓ Available"
    return when (status) {
        RequestStatus.PENDING -> "⏳ Pending"
        RequestStatus.PROCESSING -> "⚙ Processing"
        RequestStatus.AVAILABLE -> "✓ Available"
        null -> null
    }
}

fun StatusPill(ctx: Context, media: Media, status: RequestStatus?, t: DesignTokens = AppTheme.current): TextView? {
    val label = statusLabelOf(media, status) ?: return null
    return Chip(ctx, label, statusColorOf(status, t), t = t)
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
    private val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER
    }
    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(Color.WHITE, 0.85f); textAlign = android.graphics.Paint.Align.CENTER
    }
    private val bgPaint = android.graphics.Paint()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, w * 3 / 2)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bgPaint.shader = android.graphics.LinearGradient(0f, 0f, w, h, c1, c2, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val deco = android.graphics.Paint().apply { color = withAlpha(Color.WHITE, 0.08f) }
        canvas.drawCircle(w * 0.84f, h * 0.14f, w * 0.33f, deco)
        labelPaint.textSize = w * 0.05f
        canvas.drawText(media.type.label.uppercase(), w / 2f, h * 0.12f, labelPaint)
        val words = media.title.split(" ").filter { it.isNotBlank() }
        val lines = mutableListOf<String>(); var cur = ""
        for (word in words) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (cand.length <= 14) cur = cand else { if (cur.isNotEmpty()) lines.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        val shown = if (lines.size > 3) lines.take(2) + lines[2].removeSuffix("…") + "…" else lines
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
fun PosterCard(ctx: Context, m: Media, t: DesignTokens = AppTheme.current, onClick: () -> Unit): View {
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
        posterFrame.addView(Chip(ctx, "★ %.1f".format(m.rating), 0xB3000000.toInt(), t = t),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = ctx.dp(6); marginStart = ctx.dp(6)
            })
        StatusPill(ctx, m, AppState.requestFor(m.id)?.status, t)?.let { pill ->
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
        setOnClickListener { onClick() }
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
            addView(AppText(ctx, actionLabel, TextStyle.LABEL, color = t.colors.accentSolid, bold = true, t = t).apply {
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
        layoutParams = when (val p = layoutParams) {
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(w, ctx.dp(hDp))
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(w, ctx.dp(hDp))
            else -> ViewGroup.LayoutParams(w, ctx.dp(hDp))
        }
    }
    val shimmer = android.animation.ValueAnimator.ofFloat(1f, 0.4f).apply {
        duration = t.motion.longMs
        repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE
        addUpdateListener { v.alpha = it.animatedValue as Float }
    }
    v.fadeIn(durationMs = t.motion.shortMs)
    shimmer.start()
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
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

// ------------------------------------------------------------------ misc

fun ModeBanner(ctx: Context, live: Boolean, t: DesignTokens = AppTheme.current): TextView {
    val (text, color) = if (live)
        "🌐 Live — TMDB · Sonarr/Radarr · Prowlarr · Jellyfin" to t.colors.success
    else
        "🧪 Placeholder mode — tap ⚙ to connect your services" to t.colors.warning
    return Chip(ctx, text, withAlpha(color, 0.18f), color, t).apply {
        gravity = Gravity.START
    }
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
