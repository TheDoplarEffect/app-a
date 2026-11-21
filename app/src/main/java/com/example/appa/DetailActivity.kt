package com.example.appa

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.TextView
import android.widget.Toast

/**
 * Detail screen (organism). Hero poster + glass meta card + request action +
 * live magnet list. All updates are IN-PLACE (no recreate(): that re-fired the
 * network and replayed animations). Magnets resolve lazily on "Copy magnet";
 * only validated magnet:/torrent links are ever copied — never the
 * apikey-bearing download URL.
 */
class DetailActivity : Activity() {

    private lateinit var magnetsHeader: TextView
    private lateinit var magnetsBox: LinearLayout
    private lateinit var metaInner: LinearLayout
    private lateinit var actionsRow: LinearLayout
    private lateinit var currentMedia: Media
    private val handler = Handler(Looper.getMainLooper())
    private val dialogs = java.util.Collections.synchronizedList(mutableListOf<AlertDialog>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        AppState.init(this)
        val t = AppTheme.current
        val mediaId = intent.getStringExtra("mediaId") ?: run { finishDropping(); return }
        val media = AppState.mediaById(mediaId) ?: run {
            Toast.makeText(this, "This title is no longer available", Toast.LENGTH_SHORT).show()
            finishDropping(); return
        }
        currentMedia = media

        val root = FrameLayout(this).withAurora()
        val body = col(this) {
            padSystemBars(top = true, bottom = true, start = true, end = true, extraTopDp = t.space.sm, extraBottomDp = t.space.sm)
            val px = dp(t.space.lg); setPadding(px, paddingTop, px, paddingBottom)

            addView(AppTopBar(this@DetailActivity, "Details", leading = TopBarAction("✕", contentDescription = "Close") { finishDropping() }), lp(MATCH, WRAP))

            val scroll = ScrollView(this@DetailActivity)
            val content = col(this@DetailActivity)
            scroll.addView(content)
            addView(scroll, lp(MATCH, 0, weight = 1f, marginTopDp = t.space.md))

            content.addView(buildHero(media, t), lp(MATCH, WRAP))

            // live: fetch full details ONCE in the background, then update the meta card in place
            if (AppState.liveMode && media.type != MediaType.AUDIOBOOKS) {
                spawnWorker {
                    val d = runCatching { AppState.repo?.details(media) ?: media }.getOrDefault(media)
                    handler.post {
                        AppState.repo?.remember(listOf(d))
                        if (d.id == mediaId && !isFinishing && !isDestroyed) renderMeta(d, t)
                    }
                }
            }

            val metaCard = GlassCard(this@DetailActivity, padDp = t.space.lg.toInt(), t = t) {}
            metaInner = metaCard.getChildAt(0) as LinearLayout
            renderMeta(media, t)
            content.addView(metaCard, lp(MATCH, WRAP, marginTopDp = t.space.lg))
            metaCard.slideUpIn(delayMs = 80)

            actionsRow = row(this@DetailActivity) { gravity = Gravity.CENTER_VERTICAL }
            renderActions(media, t)
            content.addView(actionsRow, lp(MATCH, WRAP, marginTopDp = t.space.lg))

            magnetsHeader = AppText(this@DetailActivity, "🧲 Magnets", TextStyle.TITLE, bold = true, t = t)
            content.addView(magnetsHeader, lp(MATCH, WRAP, marginTopDp = t.space.xl))
            magnetsBox = col(this@DetailActivity)
            content.addView(magnetsBox, lp(MATCH, WRAP, marginTopDp = t.space.sm))

            loadMagnets(media, t)
        }
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::actionsRow.isInitialized) renderActions(currentMedia, AppTheme.current)
    }

    override fun onDestroy() {
        // no window leaks: dismiss dialogs; no thread leaks: interrupt workers
        synchronized(dialogs) { dialogs.forEach { runCatching { it.dismiss() } }; dialogs.clear() }
        WorkerScope.cancelAll()
        super.onDestroy()
    }

    private fun spawnWorker(block: () -> Unit) = WorkerScope.spawn(block)

    private fun showDialog(builder: AlertDialog.Builder) {
        val d = builder.show()
        dialogs.add(d)
    }

    // ---------- hero ----------

    private fun buildHero(media: Media, t: DesignTokens): FrameLayout {
        val hero = FrameLayout(this).apply {
            glass(t.radius.sheet, strong = true, t = t)
            roundedClip(t.radius.sheet)
        }
        val heroH = dp(300)
        if (media.posterUrl != null) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                roundedClip(t.radius.sheet)
                contentDescription = "${media.title} poster"
            }
            hero.addView(img, FrameLayout.LayoutParams(MATCH, heroH))
            ImageLoader.load(media.posterUrl!!, img)
        } else {
            hero.addView(GradientPoster(this, media, t).apply {
                roundedClip(t.radius.sheet)
                contentDescription = "${media.title} poster"
            }, FrameLayout.LayoutParams(MATCH, heroH))
        }
        hero.addView(View(this).apply { background = posterScrim(140, t) },
            FrameLayout.LayoutParams(MATCH, dp(140), Gravity.BOTTOM))
        val heroText = col(this) {
            gravity = Gravity.BOTTOM
            val p = dp(t.space.lg); setPadding(p, p, p, p)
            addView(AppText(this@DetailActivity, media.title, TextStyle.DISPLAY, t = t))
            addView(row(this@DetailActivity) {
                gravity = Gravity.CENTER_VERTICAL
                addView(Chip(this@DetailActivity, "★ %.1f".format(media.rating), t.colors.chipScrim, fg = 0xFFFFFFFF.toInt(), t = t))
                addView(Chip(this@DetailActivity, "${media.year} · ${media.type.label}", t.colors.chipScrim, fg = 0xFFFFFFFF.toInt(), t = t),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            }, lp(MATCH, WRAP, marginTopDp = t.space.sm))
        }
        hero.addView(heroText, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        hero.popIn()
        return hero
    }

    // ---------- meta ----------

    private fun renderMeta(media: Media, t: DesignTokens) {
        metaInner.removeAllViews()
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
        // wrapping flow: rows of ~3 chips
        var flow = row(this) { gravity = Gravity.CENTER_VERTICAL }
        chips.forEachIndexed { i, c ->
            if (i > 0 && i % 3 == 0) {
                metaInner.addView(flow, metaInner.lp(MATCH, WRAP, marginTopDp = 6))
                flow = row(this) { gravity = Gravity.CENTER_VERTICAL }
            }
            flow.addView(Chip(this, c, t.colors.surfaceGlassStrong, fg = t.colors.textSecondary, t),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i % 3 != 0) marginStart = dp(6) })
        }
        metaInner.addView(flow, metaInner.lp(MATCH, WRAP, marginTopDp = if (chips.size > 3) 6 else 0))
        metaInner.addView(
            AppText(this, media.overview.ifBlank { "No overview available." }, TextStyle.BODY, color = t.colors.textSecondary, t = t),
            metaInner.lp(MATCH, WRAP, marginTopDp = t.space.md)
        )
    }

    // ---------- request action ----------

    private fun renderActions(media: Media, t: DesignTokens) {
        actionsRow.removeAllViews()
        val request = AppState.requestFor(media.id)
        when {
            media.available -> {
                actionsRow.addView(PrimaryButton(this, "✓ Available in library", enabled = false, t, onClick = {}), actionsRow.lp(WRAP, WRAP))
            }
            request != null -> {
                val statusText = when (request.status) {
                    RequestStatus.PENDING -> "⏳ Requested — pending"
                    RequestStatus.PROCESSING -> "⚙ Requested — processing"
                    RequestStatus.AVAILABLE -> "✓ Requested — available"
                }
                actionsRow.addView(PrimaryButton(this, statusText, enabled = false, t, onClick = {}), actionsRow.lp(WRAP, WRAP))
                actionsRow.addView(DangerButton(this, "Cancel request", t) {
                    showDialog(AlertDialog.Builder(this)
                        .setTitle("Cancel request?")
                        .setMessage("Remove the request for \"${media.title}\"? (The *arr entry stays.)")
                        .setPositiveButton("Remove") { _, _ ->
                            AppState.store.delete(request.id)
                            Toast.makeText(this, "Request removed", Toast.LENGTH_SHORT).show()
                            renderActions(media, t)
                        }
                        .setNegativeButton("Keep", null))
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            }
            else -> {
                val btn = PrimaryButton(this, "＋ Request", t = t) { doRequest(media, it) }
                actionsRow.addView(btn, actionsRow.lp(WRAP, WRAP))
            }
        }
    }

    private fun doRequest(media: Media, btn: TextView) {
        btn.isEnabled = false
        btn.text = "Requesting…"
        val snapshot = MediaSnapshot.of(media).toJson()
        if (AppState.requestFor(media.id) != null) { // check FIRST — never double-add in *arr
            Toast.makeText(this, "Already requested", Toast.LENGTH_SHORT).show()
            renderActions(media, AppTheme.current)
            return
        }
        if (AppState.liveMode) {
            spawnWorker {
                val result = runCatching {
                    val external = AppState.repo?.request(media) ?: throw ApiException("Live mode off")
                    val created = AppState.store.create(media.id, external = external, snapshot = snapshot)
                    if (created == null) "Already requested"
                    else {
                        val kind = ExternalRef.parse(external)?.kind
                        "Added to ${kind?.replaceFirstChar { it.uppercase() } ?: "local list"}"
                    }
                }
                handler.post {
                    Toast.makeText(this, result.getOrElse { "Failed: ${it.message}" }, Toast.LENGTH_LONG).show()
                    renderActions(media, AppTheme.current)
                }
            }
        } else {
            val created = AppState.store.create(media.id, snapshot = snapshot)
            Toast.makeText(this, if (created == null) "Already requested" else "Request submitted — monitoring started", Toast.LENGTH_SHORT).show()
            renderActions(media, AppTheme.current)
        }
    }

    // ---------- magnets ----------

    private fun loadMagnets(media: Media, t: DesignTokens) {
        if (AppState.liveMode) {
            magnetsHeader.text = "🧲 Magnets — searching indexers…"
            spawnWorker {
                val res = runCatching { AppState.repo?.magnetsFor(media) ?: emptyList() }
                handler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (res.isFailure) {
                        magnetsHeader.text = "🧲 Magnets — search failed"
                        renderMagnets(emptyList(), t, error = res.exceptionOrNull()?.message?.take(100))
                    } else {
                        magnetsHeader.text = "🧲 Magnets (${res.getOrDefault(emptyList()).size})"
                        renderMagnets(res.getOrDefault(emptyList()), t)
                    }
                }
            }
        } else {
            val mags = Catalog.magnetsByMediaId[media.id].orEmpty().map {
                LiveMagnet(it.name, it.source, it.sizeBytes, it.seeders, it.leechers, downloadUrl = "", magnetUri = it.magnetUri)
            }
            magnetsHeader.text = "🧲 Magnets (${mags.size})"
            renderMagnets(mags, t)
        }
    }

    private fun renderMagnets(mags: List<LiveMagnet>, t: DesignTokens, error: String? = null) {
        magnetsBox.removeAllViews()
        if (error != null || mags.isEmpty()) {
            val box = col(this) {
                gravity = Gravity.CENTER
                val p = dp(t.space.xl); setPadding(p, p, p, p)
                addView(AppText(this@DetailActivity, if (error != null) "Search failed: $error" else "No magnets found on the indexers for this title.", TextStyle.BODY, color = t.colors.textSecondary, t = t))
                addView(SecondaryButton(this@DetailActivity, "Retry", t) { loadMagnets(currentMedia, t) },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(t.space.md); gravity = Gravity.CENTER })
            }
            magnetsBox.addView(box)
            return
        }
        mags.forEachIndexed { i, mag ->
            val row = MagnetRow(this, mag, t) { copyMagnet(mag) }
            magnetsBox.addView(row, magnetsBox.lp(MATCH, WRAP, marginTopDp = if (i == 0) 0 else t.space.sm))
        }
        magnetsBox.staggerIn(stepMs = 30)
    }

    private fun copyMagnet(mag: LiveMagnet) {
        Toast.makeText(this, "Resolving magnet…", Toast.LENGTH_SHORT).show()
        spawnWorker {
            val res = runCatching {
                val uri = if (AppState.liveMode) AppState.repo?.resolveMagnet(mag) ?: "" else mag.magnetUri
                // only a real magnet/torrent link is ever copied — never the apikey-bearing download URL
                if (uri.startsWith("magnet:", ignoreCase = true)) uri
                else throw ApiException("Indexer did not return a magnet link")
            }
            handler.post {
                res.fold(
                    onSuccess = { uri ->
                        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("magnet", uri))
                        Toast.makeText(this, "Magnet link copied", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(this, "Magnet resolve failed: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
}
