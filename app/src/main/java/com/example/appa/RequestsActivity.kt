package com.example.appa

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Requests screen (organism): glass list of requested titles with animated
 * status pills. Live mode polls Jellyfin + *arr every 5s; placeholder mode
 * runs the simulated ticker.
 */
class RequestsActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var countLabel: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ticker: Runnable

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
                leading = TopBarAction("✕") { finishDropping() }
            )
            addView(topBar, lp(MATCH, WRAP))
            countLabel = AppText(this@RequestsActivity, "", TextStyle.LABEL, t = t)
            addView(countLabel, lp(MATCH, WRAP, marginTopDp = t.space.xs))
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
        ticker = object : Runnable {
            override fun run() {
                if (AppState.liveMode) {
                    AppState.store.tick()
                    Thread {
                        runCatching { AppState.repo?.refreshStatuses(AppState.store) }
                        handler.post { render() }
                    }.start()
                    handler.postDelayed(this, 5000)
                } else {
                    if (AppState.store.tick().isNotEmpty()) render()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(ticker, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ticker.isInitialized) handler.removeCallbacks(ticker)
    }

    private fun render() {
        val t = AppTheme.current
        list.removeAllViews()
        val requests = AppState.store.all().sortedByDescending { it.requestedAt }
        countLabel.text = "${requests.size} total"
        if (requests.isEmpty()) {
            list.addView(EmptyState(this, "📥", "No requests yet", "Open a title and hit \"＋ Request\".", t))
            return
        }
        requests.forEachIndexed { i, r ->
            val media = AppState.mediaById(r.mediaId) ?: return@forEachIndexed
            val card = GlassCard(this, padDp = t.space.md.toInt(), t = t) {
                val rrow = row(this@RequestsActivity) {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    if (media.posterUrl != null) {
                        val img = android.widget.ImageView(this@RequestsActivity).apply {
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            roundedClip(12)
                        }
                        addView(img, LinearLayout.LayoutParams(dp(44), dp(64)))
                        ImageLoader.load(media.posterUrl!!, img)
                    } else {
                        addView(GradientPoster(this@RequestsActivity, media, t).apply { roundedClip(12) },
                            LinearLayout.LayoutParams(dp(44), dp(64)))
                    }
                    val info = col(this@RequestsActivity) {
                        addView(AppText(this@RequestsActivity, media.title, TextStyle.BODY, bold = true, t = t))
                        addView(AppText(
                            this@RequestsActivity,
                            "${media.type.label} · ${media.year} · ${formatTime(r.requestedAt)}" +
                                (if (r.external.isNotBlank()) " · ${r.external.substringBefore(":")}" else " · local"),
                            TextStyle.LABEL, t = t
                        ), lp(MATCH, WRAP, marginTopDp = 2))
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) }
                    }
                    addView(info)
                    StatusPill(this@RequestsActivity, media, r.status, t)?.let { pill ->
                        addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                    }
                    addView(CircleIconButton(this@RequestsActivity, "🗑", 0, t) {
                        AppState.store.delete(r.id)
                        Toast.makeText(this@RequestsActivity, "Request removed", Toast.LENGTH_SHORT).show()
                        render()
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                }
                addView(rrow, lp(MATCH, WRAP))
            }
            list.addView(card, list.lp(MATCH, WRAP, marginTopDp = if (i == 0) 0 else t.space.sm))
        }
        list.staggerIn(stepMs = 30)
    }
}
