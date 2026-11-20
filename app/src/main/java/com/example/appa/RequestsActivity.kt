package com.example.appa

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Requests screen: live list of requested titles with status pills that advance
 * pending -> processing -> available (simulated monitoring), persisted across
 * restarts. Polls the store every second.
 */
class RequestsActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var countLabel: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ticker: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val root = Ui.vRow(this)
        Ui.padded(root, 14)
        root.setBackgroundColor(Color.WHITE)

        val header = Ui.hRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            text = "‹ Back"
            setTextColor(Ui.ACCENT)
            background = pillBg(Ui.CARD_BG, 8)
            setOnClickListener { finish() }
        })
        header.addView(Ui.textView(this, "Requests", 18f, Ui.TEXT, bold = true).apply {
            val p = dp(10); setPadding(p, 0, 0, 0)
        })
        countLabel = Ui.textView(this, "", 12f, Ui.DIM)
        header.addView(countLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
        })
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(Ui.textView(
            this,
            "Live monitoring — pending → processing → available",
            11f, Ui.DIM
        ).apply { val p = dp(4); setPadding(0, p, 0, 0) })
        root.addView(Ui.divider(this).apply {
            val lp = this.layoutParams as LinearLayout.LayoutParams
            lp.topMargin = dp(8); lp.bottomMargin = dp(4)
        })

        val scroll = ScrollView(this)
        list = Ui.vRow(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        render()
        ticker = object : Runnable {
            override fun run() {
                val changed = AppState.store.tick()
                if (changed.isNotEmpty()) render()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(ticker, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ticker.isInitialized) handler.removeCallbacks(ticker)
    }

    private fun render() {
        list.removeAllViews()
        val requests = AppState.store.all().sortedByDescending { it.requestedAt }
        countLabel.text = "${requests.size} total"
        if (requests.isEmpty()) {
            list.addView(Ui.textView(this, "No requests yet. Open a title and hit \"＋ Request\".", 13f, Ui.DIM).apply {
                val p = dp(16); setPadding(0, p, 0, 0)
            })
            return
        }
        for (r in requests) {
            val media = AppState.catalog.byId[r.mediaId] ?: continue
            val row = Ui.hRow(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = pillBg(Ui.CARD_BG, 8)
                val p = dp(10); setPadding(p, p, p, p)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            row.addView(PosterView(this, media), LinearLayout.LayoutParams(dp(40), dp(60)))
            val info = Ui.vRow(this).apply {
                val p = dp(10); setPadding(p, 0, 0, 0)
            }
            info.addView(Ui.textView(this, media.title, 13f, Ui.TEXT, bold = true))
            info.addView(Ui.textView(
                this,
                "${media.type.label} · ${media.year} · requested ${Ui.formatTime(r.requestedAt)}",
                11f, Ui.DIM
            ).apply { val p = dp(2); setPadding(0, p, 0, 0) })
            row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val pillText = when (r.status) {
                RequestStatus.PENDING -> "⏳ Pending"
                RequestStatus.PROCESSING -> "⚙ Processing"
                RequestStatus.AVAILABLE -> "✓ Available"
            }
            row.addView(Ui.pill(this, pillText, Ui.statusColor(r.status) ?: Ui.GREEN).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(8)
                layoutParams = lp
            })
            row.addView(Button(this).apply {
                text = "Remove"
                setTextColor(Color.WHITE)
                background = pillBg(Ui.RED, 8)
                setOnClickListener {
                    AppState.store.delete(r.id)
                    Toast.makeText(this@RequestsActivity, "Request removed", Toast.LENGTH_SHORT).show()
                    render()
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(8)
                layoutParams = lp
            })
            list.addView(row)
        }
    }
}
