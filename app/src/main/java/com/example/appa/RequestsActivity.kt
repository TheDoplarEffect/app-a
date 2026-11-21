package com.example.appa

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Requests screen (organism): glass list of requested titles with animated
 * status pills. Uses the shared [StatusPoller] (in-flight-guarded) — live
 * mode refreshes Jellyfin + *arr state, placeholder mode runs the simulated
 * ticker. A failed refresh shows a one-line stale warning instead of silence.
 */
class RequestsActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var countLabel: android.widget.TextView
    private lateinit var staleLabel: android.widget.TextView
    private val dialogs = java.util.Collections.synchronizedList(mutableListOf<AlertDialog>())
    private val poller = StatusPoller { changed, failed ->
        if (isFinishing || isDestroyed) return@StatusPoller // never touch views from a dead activity
        if (failed) staleLabel.text = "⚠ Last status refresh failed — showing cached statuses"
        else if (staleLabel.text.isNotEmpty()) staleLabel.text = ""
        if (changed || failed) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        AppState.init(this)
        val t = AppTheme.current

        val root = FrameLayout(this).withAurora()
        val body = col(this) {
            padSystemBars(top = true, bottom = true, start = true, end = true, extraTopDp = t.space.sm, extraBottomDp = t.space.sm)
            val px = dp(t.space.lg); setPadding(px, paddingTop, px, paddingBottom)

            val topBar = AppTopBar(
                this@RequestsActivity,
                "Requests",
                leading = TopBarAction("✕", contentDescription = "Close") { finishDropping() }
            )
            addView(topBar, lp(MATCH, WRAP))
            countLabel = AppText(this@RequestsActivity, "", TextStyle.LABEL, t = t)
            addView(countLabel, lp(MATCH, WRAP, marginTopDp = t.space.xs))
            staleLabel = AppText(this@RequestsActivity, "", TextStyle.LABEL, color = t.colors.warning, t = t)
            addView(staleLabel, lp(MATCH, WRAP, marginTopDp = t.space.xs))
            addView(AppText(
                this@RequestsActivity,
                if (AppState.liveMode) "Live status from Jellyfin + Sonarr/Radarr — polled every 5s"
                else "Simulated monitoring — pending → processing → available",
                TextStyle.LABEL, t = t
            ), lp(MATCH, WRAP, marginTopDp = t.space.xs))

            val scroll = ScrollView(this@RequestsActivity)
            list = col(this@RequestsActivity)
            scroll.addView(list)
            addView(scroll, lp(MATCH, 0, weight = 1f, marginTopDp = t.space.md))
        }
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        render()
    }

    override fun onStart() {
        super.onStart()
        poller.start()
    }

    override fun onStop() {
        poller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        poller.stop()
        synchronized(dialogs) { dialogs.forEach { runCatching { it.dismiss() } }; dialogs.clear() }
        super.onDestroy()
    }

    private var lastSignature: String? = null
    private var lastCount = -1 // stagger-animate only on first fill / count change, not every status tick

    private fun render() {
        if (isFinishing || isDestroyed) return
        val t = AppTheme.current
        val requests = AppState.store.all().sortedByDescending { it.requestedAt }
        val signature = requests.joinToString("|") { "${it.id}:${it.status}:${it.external}" }
        if (signature == lastSignature) return // null sentinel: first render always draws
        lastSignature = signature
        list.removeAllViews()
        val renderable = requests.mapNotNull { r -> AppState.mediaById(r.mediaId)?.let { m -> r to m } }
        countLabel.text = "${renderable.size} total"
        val animate = lastCount != renderable.size
        lastCount = renderable.size
        if (renderable.isEmpty()) {
            list.addView(EmptyState(this, "📥", "No requests yet", "Open a title and hit \"＋ Request\".", t))
            return
        }
        renderable.forEachIndexed { i, (r, media) ->
            val card = GlassCard(this, padDp = t.space.md.toInt(), t = t) {
                val rrow = row(this@RequestsActivity) {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    if (media.posterUrl != null) {
                        val img = android.widget.ImageView(this@RequestsActivity).apply {
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            roundedClip(t.radius.thumb)
                            contentDescription = "${media.title} poster"
                        }
                        addView(img, LinearLayout.LayoutParams(dp(44), dp(64)))
                        ImageLoader.load(media.posterUrl!!, img)
                    } else {
                        addView(GradientPoster(this@RequestsActivity, media, t).apply {
                            roundedClip(t.radius.thumb)
                            contentDescription = "${media.title} poster"
                        }, LinearLayout.LayoutParams(dp(44), dp(64)))
                    }
                    val info = col(this@RequestsActivity) {
                        addView(AppText(this@RequestsActivity, media.title, TextStyle.BODY, bold = true, t = t))
                        addView(AppText(
                            this@RequestsActivity,
                            "${media.type.label} · ${media.year} · ${formatTime(r.requestedAt)}" +
                                (if (r.external.isNotBlank()) " · ${ExternalRef.parse(r.external)?.kind ?: "local"}" else " · local"),
                            TextStyle.LABEL, t = t
                        ), lp(MATCH, WRAP, marginTopDp = 2))
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) }
                    }
                    addView(info)
                    StatusPill(this@RequestsActivity, media, r.status, t)?.let { pill ->
                        addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                    }
                    addView(CircleIconButton(this@RequestsActivity, "🗑", 0, t, contentDescription = "Remove request for ${media.title}") {
                        val d = AlertDialog.Builder(this@RequestsActivity)
                            .setTitle("Remove request?")
                            .setMessage("Remove the request for \"${media.title}\"?")
                            .setPositiveButton("Remove") { _, _ ->
                                AppState.store.delete(r.id)
                                lastSignature = null // force re-render after delete
                                Toast.makeText(this@RequestsActivity, "Request removed", Toast.LENGTH_SHORT).show()
                                render()
                            }
                            .setNegativeButton("Keep", null)
                            .show()
                        dialogs.add(d)
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                }
                addView(rrow, lp(MATCH, WRAP))
            }
            card.contentDescription = "${media.title}, open details"
            card.setOnClickListener { openRisingTo(DetailActivity::class.java, "mediaId", r.mediaId) }
            card.pressable() // press feedback on the card
            list.addView(card, list.lp(MATCH, WRAP, marginTopDp = if (i == 0) 0 else t.space.sm))
        }
        if (animate && list.childCount > 0) list.staggerIn(stepMs = 30) // animate only the initial fill / count change, not every status tick
    }
}
