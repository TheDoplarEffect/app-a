package com.example.appa

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Detail screen (organism): hero poster with scrim + title overlay, glass
 * cards for meta/overview, request action, and a live magnet list whose
 * magnets resolve lazily on "Copy magnet". Rises in with a sheet transition.
 */
class DetailActivity : Activity() {

    private lateinit var magnetsHeader: android.widget.TextView
    private lateinit var magnetsBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        AppState.init(this)
        val t = AppTheme.current
        val mediaId = intent.getStringExtra("mediaId") ?: run { finishDropping(); return }
        var media = AppState.mediaById(mediaId) ?: run { finishDropping(); return }

        val root = FrameLayout(this).withAurora()
        val body = col(this) {
            padSystemBars(top = true, bottom = true, start = true, end = true, extraTopDp = t.space.sm, extraBottomDp = t.space.sm)
            val px = this@DetailActivity.dp(t.space.lg); setPadding(px, paddingTop, px, paddingBottom)

            addView(AppTopBar(this@DetailActivity, "Details", leading = TopBarAction("✕") { finishDropping() }), lp(MATCH, WRAP))

            val scroll = ScrollView(this@DetailActivity)
            val content = col(this@DetailActivity)
            scroll.addView(content)
            addView(scroll, lp(MATCH, 0, weight = 1f, marginTopDp = t.space.md))

            // ---------- hero ----------
            val hero = FrameLayout(this@DetailActivity).apply {
                glass(t.radius.sheet, strong = true, t = t)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, this@DetailActivity.dp(t.radius.sheet).toFloat())
                    }
                }
            }
            val heroH = dp(300)
            if (media.posterUrl != null) {
                val img = ImageView(this@DetailActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    roundedClip(t.radius.sheet)
                }
                hero.addView(img, FrameLayout.LayoutParams(MATCH, heroH))
                ImageLoader.load(media.posterUrl!!, img)
            } else {
                hero.addView(GradientPoster(this@DetailActivity, media, t).apply { roundedClip(t.radius.sheet) },
                    FrameLayout.LayoutParams(MATCH, heroH))
            }
            hero.addView(View(this@DetailActivity).apply { background = posterScrim(140, t) },
                FrameLayout.LayoutParams(MATCH, dp(140), Gravity.BOTTOM))
            val heroText = col(this@DetailActivity) {
                gravity = Gravity.BOTTOM
                val p = dp(t.space.lg); setPadding(p, p, p, p)
                addView(AppText(this@DetailActivity, media.title, TextStyle.DISPLAY, t = t))
                addView(row(this@DetailActivity) {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Chip(this@DetailActivity, "★ %.1f".format(media.rating), 0xB3000000.toInt(), t = t))
                    addView(Chip(this@DetailActivity, "${media.year} · ${media.type.label}", 0xB3000000.toInt(), t = t),
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
                }, lp(MATCH, WRAP, marginTopDp = t.space.sm))
            }
            hero.addView(heroText, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            content.addView(hero, lp(MATCH, WRAP))
            hero.popIn()

            // live: fetch full details in background, then rebuild
            if (AppState.liveMode && media.type != MediaType.AUDIOBOOKS) {
                Thread {
                    val d = runCatching { AppState.repo!!.details(media) }.getOrDefault(media)
                    handler.post {
                        AppState.repo?.remember(listOf(d))
                        if (d.id == mediaId && !isFinishing) recreate()
                    }
                }.start()
            }
            media = AppState.mediaById(mediaId) ?: media

            // ---------- meta card ----------
            val metaCard = GlassCard(this@DetailActivity, padDp = t.space.lg.toInt(), t = t) {
                val chips = mutableListOf<String>()
                if (media.genres.isNotEmpty()) chips += media.genres
                if (media.type == MediaType.SERIES && media.seasons != null) chips += "${media.seasons} seasons · ${media.episodes} eps"
                if (media.type == MediaType.MOVIES && media.runtimeMinutes != null) chips += "${media.runtimeMinutes} min"
                if (media.type == MediaType.AUDIOBOOKS) {
                    if (media.author != null) chips += "✍ ${media.author}"
                    if (media.narrator != null) chips += "🎙 ${media.narrator}"
                    if (media.durationHours != null) chips += "%.1f h".format(media.durationHours)
                }
                if (chips.isEmpty()) chips += "No extra metadata"
                val flow = row(this@DetailActivity) { gravity = Gravity.CENTER_VERTICAL }
                chips.take(6).forEachIndexed { i, c ->
                    flow.addView(Chip(this@DetailActivity, c, t.colors.surfaceGlassStrong, t.colors.textSecondary, t),
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(6) })
                }
                // simple wrap: use a second row if many chips
                addView(flow, lp(MATCH, WRAP))
                if (chips.size > 6) {
                    val flow2 = row(this@DetailActivity) { gravity = Gravity.CENTER_VERTICAL }
                    chips.drop(6).forEachIndexed { i, c ->
                        flow2.addView(Chip(this@DetailActivity, c, t.colors.surfaceGlassStrong, t.colors.textSecondary, t),
                            LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(6) })
                    }
                    addView(flow2, lp(MATCH, WRAP, marginTopDp = 6))
                }
                if (media.overview.isNotBlank()) {
                    addView(AppText(this@DetailActivity, media.overview, TextStyle.BODY, color = t.colors.textSecondary, t = t),
                        lp(MATCH, WRAP, marginTopDp = t.space.md))
                } else {
                    addView(AppText(this@DetailActivity, "No overview available.", TextStyle.BODY, color = t.colors.textSecondary, t = t),
                        lp(MATCH, WRAP, marginTopDp = t.space.md))
                }
            }
            content.addView(metaCard, lp(MATCH, WRAP, marginTopDp = t.space.lg))
            metaCard.slideUpIn(delayMs = 80)

            // ---------- request action ----------
            val actions = row(this@DetailActivity) { gravity = Gravity.CENTER_VERTICAL }
            renderActions(actions, media, t)
            content.addView(actions, lp(MATCH, WRAP, marginTopDp = t.space.lg))

            // ---------- magnets ----------
            magnetsHeader = AppText(this@DetailActivity, "🧲 Magnets", TextStyle.TITLE, bold = true, t = t)
            content.addView(magnetsHeader, lp(MATCH, WRAP, marginTopDp = t.space.xl))
            magnetsBox = col(this@DetailActivity)
            content.addView(magnetsBox, lp(MATCH, WRAP, marginTopDp = t.space.sm))

            loadMagnets(media, t)
        }
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    private fun renderActions(actions: LinearLayout, media: Media, t: DesignTokens) {
        actions.removeAllViews()
        val request = AppState.requestFor(media.id)
        when {
            media.available -> {
                actions.addView(PrimaryButton(this, "✓ Available in library", enabled = false, t, onClick = {}), actions.lp(WRAP, WRAP))
            }
            request != null -> {
                val statusText = when (request.status) {
                    RequestStatus.PENDING -> "⏳ Requested — pending"
                    RequestStatus.PROCESSING -> "⚙ Requested — processing"
                    RequestStatus.AVAILABLE -> "✓ Requested — available"
                }
                actions.addView(PrimaryButton(this, statusText, enabled = false, t, onClick = {}), actions.lp(WRAP, WRAP))
                actions.addView(DangerButton(this, "Cancel request", t) {
                    AppState.store.delete(request.id)
                    Toast.makeText(this, "Request removed", Toast.LENGTH_SHORT).show()
                    recreate()
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            }
            else -> {
                actions.addView(PrimaryButton(this, "＋ Request", t = t) { doRequest(media) }, actions.lp(WRAP, WRAP))
            }
        }
    }

    private fun doRequest(media: Media) {
        Toast.makeText(this, "Submitting request…", Toast.LENGTH_SHORT).show()
        if (AppState.liveMode) {
            Thread {
                val result = runCatching {
                    val external = AppState.repo!!.request(media)
                    AppState.store.create(media.id, external = external)
                    "Added to ${if (external.startsWith("radarr")) "Radarr" else if (external.startsWith("sonarr")) "Sonarr" else "local list"}"
                }
                handler.post {
                    Toast.makeText(this, result.getOrElse { "Failed: ${it.message}" }, Toast.LENGTH_LONG).show()
                    recreate()
                }
            }.start()
        } else {
            AppState.store.create(media.id)
            Toast.makeText(this, "Request submitted — monitoring started", Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    private fun loadMagnets(media: Media, t: DesignTokens) {
        if (AppState.liveMode) {
            magnetsHeader.text = "🧲 Magnets — searching indexers…"
            Thread {
                val mags = runCatching { AppState.repo!!.magnetsFor(media) }.getOrElse { emptyList() }
                handler.post {
                    magnetsHeader.text = "🧲 Magnets (${mags.size})"
                    renderMagnets(mags, t)
                }
            }.start()
        } else {
            val mags = Catalog.magnetsByMediaId[media.id].orEmpty().map {
                LiveMagnet(it.name, it.source, it.sizeBytes, it.seeders, it.leechers, downloadUrl = "", magnetUri = it.magnetUri)
            }
            magnetsHeader.text = "🧲 Magnets (${mags.size})"
            renderMagnets(mags, t)
        }
    }

    private fun renderMagnets(mags: List<LiveMagnet>, t: DesignTokens) {
        magnetsBox.removeAllViews()
        if (mags.isEmpty()) {
            magnetsBox.addView(EmptyState(this, "🧲", "No magnets", "No results on the indexers for this title.", t))
            return
        }
        mags.forEachIndexed { i, mag ->
            val row = MagnetRow(this, mag, t) { copyMagnet(mag) }
            magnetsBox.addView(row, magnetsBox.lp(MATCH, WRAP, marginTopDp = if (i == 0) 0 else t.space.sm))
        }
        magnetsBox.staggerIn(stepMs = 30)
    }

    private fun copyMagnet(mag: LiveMagnet) {
        Thread {
            val uri = runCatching {
                if (AppState.liveMode) AppState.repo!!.resolveMagnet(mag) else mag.magnetUri
            }.getOrElse { mag.downloadUrl }
            handler.post {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("magnet", uri))
                Toast.makeText(this, "Magnet link copied", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
