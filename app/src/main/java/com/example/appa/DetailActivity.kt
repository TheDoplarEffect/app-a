package com.example.appa

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Detail screen for one title: metadata, request button and its magnet list. */
class DetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        val mediaId = intent.getStringExtra("mediaId") ?: run { finish(); return }
        val media = AppState.catalog.byId[mediaId] ?: run { finish(); return }

        val root = Ui.vRow(this)
        Ui.padded(root, 14)
        root.setBackgroundColor(Color.WHITE)

        // header with back
        val header = Ui.hRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = Button(this).apply {
            text = "‹ Back"
            setTextColor(Ui.ACCENT)
            background = pillBg(Ui.CARD_BG, 8)
            setOnClickListener { finish() }
        }
        header.addView(back)
        header.addView(Ui.textView(this, "Details", 18f, Ui.TEXT, bold = true).apply {
            val p = dp(10); setPadding(p, 0, 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this)
        val body = Ui.vRow(this)
        Ui.padded(body, 2)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // poster + info row
        val top = Ui.hRow(this).apply { gravity = Gravity.TOP }
        val poster = PosterView(this, media)
        top.addView(poster, LinearLayout.LayoutParams(dp(110), dp(165)))
        val info = Ui.vRow(this).apply {
            val p = dp(12); setPadding(p, 0, 0, 0)
        }
        info.addView(Ui.textView(this, media.title, 18f, Ui.TEXT, bold = true))
        info.addView(Ui.textView(this, "${media.year} · ${media.type.label} · ★ %.1f".format(media.rating), 12f, Ui.DIM).apply {
            val p = dp(4); setPadding(0, p, 0, 0)
        })
        info.addView(Ui.textView(this, media.genres.joinToString(" · "), 12f, Ui.ACCENT).apply {
            val p = dp(4); setPadding(0, p, 0, 0)
        })
        val extra = mutableListOf<String>()
        if (media.type == MediaType.SERIES && media.seasons != null)
            extra += "${media.seasons} seasons · ${media.episodes} episodes"
        if (media.type == MediaType.MOVIES && media.runtimeMinutes != null)
            extra += "Runtime ${media.runtimeMinutes} min"
        if (media.type == MediaType.AUDIOBOOKS) {
            if (media.author != null) extra += "Author ${media.author}"
            if (media.narrator != null) extra += "Narrator ${media.narrator}"
            if (media.durationHours != null) extra += "Duration %.1f h".format(media.durationHours)
        }
        info.addView(Ui.textView(this, extra.joinToString("\n"), 12f, Ui.DIM).apply {
            val p = dp(6); setPadding(0, p, 0, 0)
        })
        top.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        body.addView(Ui.textView(this, media.overview, 13f, Ui.TEXT).apply {
            val p = dp(10); setPadding(0, p, 0, 0)
        })

        // ---- request actions ----
        val actions = Ui.hRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val request = AppState.requestFor(media.id)
        if (media.available) {
            actions.addView(Button(this).apply {
                text = "✓ Available in library"
                isEnabled = false
            })
        } else if (request != null) {
            val statusText = when (request.status) {
                RequestStatus.PENDING -> "Requested (pending…)"
                RequestStatus.PROCESSING -> "Requested (processing…)"
                RequestStatus.AVAILABLE -> "Requested (available)"
            }
            actions.addView(Button(this).apply {
                text = "✓ $statusText"
                isEnabled = false
            })
            actions.addView(Button(this).apply {
                text = "Cancel request"
                setTextColor(Color.WHITE)
                background = pillBg(Ui.RED, 8)
                setOnClickListener {
                    AppState.store.delete(request.id)
                    Toast.makeText(this@DetailActivity, "Request removed", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }.also { b ->
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(8)
                actions.addView(b, lp)
            })
        } else {
            actions.addView(Button(this).apply {
                text = "＋ Request"
                setTextColor(Color.WHITE)
                background = pillBg(Ui.ACCENT, 8)
                setOnClickListener {
                    AppState.store.create(media.id)
                    Toast.makeText(this@DetailActivity, "Request submitted — monitoring started", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            })
        }
        body.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        // ---- magnets ----
        body.addView(Ui.textView(this, "🧲 Magnets (${AppState.catalog.magnetsByMediaId[media.id]?.size ?: 0})", 15f, Ui.TEXT, bold = true).apply {
            val p = dp(14); setPadding(0, p, 0, 0)
        })
        val magnets = AppState.catalog.magnetsByMediaId[media.id].orEmpty()
        magnets.forEach { mag ->
            val card = Ui.vRow(this).apply {
                background = pillBg(Ui.CARD_BG, 8)
                val p = dp(10); setPadding(p, p, p, p)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            card.addView(Ui.textView(this, mag.name, 12f, Ui.TEXT, bold = true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(Ui.textView(
                this,
                "${formatSize(mag.sizeBytes)} · S ${mag.seeders} / L ${mag.leechers} · ${mag.source} · ${Ui.formatTime(mag.addedAt)}",
                11f, Ui.DIM
            ).apply { val p = dp(3); setPadding(0, p, 0, 0) })
            card.addView(Button(this).apply {
                text = "Copy magnet"
                setTextColor(Ui.ACCENT)
                background = pillBg(Color.WHITE, 6)
                setOnClickListener {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("magnet", mag.magnetUri))
                    Toast.makeText(this@DetailActivity, "Magnet link copied", Toast.LENGTH_SHORT).show()
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            })
            body.addView(card)
        }

        setContentView(root)
    }
}
