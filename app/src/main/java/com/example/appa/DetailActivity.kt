package com.example.appa

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Detail screen for one title: metadata, request button and its magnet list. */
class DetailActivity : Activity() {

    private lateinit var magnetsHeader: TextView
    private lateinit var magnetsBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        val mediaId = intent.getStringExtra("mediaId") ?: run { finish(); return }
        var media = AppState.mediaById(mediaId) ?: run { finish(); return }

        val root = Ui.vRow(this)
        Ui.padded(root, 14)
        root.setBackgroundColor(Color.WHITE)

        // header with back
        val header = Ui.hRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            text = "‹ Back"
            setTextColor(Ui.ACCENT)
            background = pillBg(Ui.CARD_BG, 8)
            setOnClickListener { finish() }
        })
        header.addView(Ui.textView(this, "Details", 18f, Ui.TEXT, bold = true).apply {
            val p = dp(10); setPadding(p, 0, 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this)
        val body = Ui.vRow(this)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // live: fetch full details (genres/overview/runtime) in background
        if (AppState.liveMode && media.type != MediaType.AUDIOBOOKS) {
            Thread {
                val d = runCatching { AppState.repo!!.details(media) }.getOrDefault(media)
                handler.post {
                    AppState.repo?.remember(listOf(d))
                    if (d.id == mediaId) recreate()
                }
            }.start()
        }
        media = AppState.mediaById(mediaId) ?: media

        // poster + info row
        val top = Ui.hRow(this).apply { gravity = Gravity.TOP }
        if (media.posterUrl != null) {
            val img = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
            }
            top.addView(img, LinearLayout.LayoutParams(dp(110), dp(165)))
            ImageLoader.load(media.posterUrl!!, img)
        } else {
            top.addView(PosterView(this, media), LinearLayout.LayoutParams(dp(110), dp(165)))
        }
        val info = Ui.vRow(this).apply {
            val p = dp(12); setPadding(p, 0, 0, 0)
        }
        info.addView(Ui.textView(this, media.title, 18f, Ui.TEXT, bold = true))
        info.addView(Ui.textView(this, "${media.year} · ${media.type.label} · ★ %.1f".format(media.rating), 12f, Ui.DIM).apply {
            val p = dp(4); setPadding(0, p, 0, 0)
        })
        if (media.genres.isNotEmpty())
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
        if (extra.isNotEmpty())
            info.addView(Ui.textView(this, extra.joinToString("\n"), 12f, Ui.DIM).apply {
                val p = dp(6); setPadding(0, p, 0, 0)
            })
        top.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        body.addView(Ui.textView(this, media.overview.ifBlank { "No overview available." }, 13f, Ui.TEXT).apply {
            val p = dp(10); setPadding(0, p, 0, 0)
        })

        // ---- request actions ----
        val actions = Ui.hRow(this).apply { gravity = Gravity.CENTER_VERTICAL }
        renderActions(actions, media)
        body.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        // ---- magnets ----
        magnetsHeader = Ui.textView(this, "🧲 Magnets", 15f, Ui.TEXT, bold = true).apply {
            val p = dp(14); setPadding(0, p, 0, 0)
        }
        body.addView(magnetsHeader)
        magnetsBox = Ui.vRow(this)
        body.addView(magnetsBox)

        loadMagnets(media)
        setContentView(root)
    }

    private fun renderActions(actions: LinearLayout, media: Media) {
        actions.removeAllViews()
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
            actions.addView(Button(this).apply { text = "✓ $statusText"; isEnabled = false })
            actions.addView(Button(this).apply {
                text = "Cancel request"
                setTextColor(Color.WHITE)
                background = pillBg(Ui.RED, 8)
                setOnClickListener {
                    AppState.store.delete(request.id)
                    Toast.makeText(this@DetailActivity, "Request removed", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(8)
                layoutParams = lp
            })
        } else {
            actions.addView(Button(this).apply {
                text = "＋ Request"
                setTextColor(Color.WHITE)
                background = pillBg(Ui.ACCENT, 8)
                setOnClickListener { doRequest(media) }
            })
        }
    }

    private fun doRequest(media: Media) {
        Toast.makeText(this, "Submitting request…", Toast.LENGTH_SHORT).show()
        if (AppState.liveMode) {
            Thread {
                val result = runCatching {
                    val external = AppState.repo!!.request(media)
                    AppState.store.create(media.id, external = external)
                    "Request added to ${if (external.startsWith("radarr")) "Radarr" else if (external.startsWith("sonarr")) "Sonarr" else "local list"}"
                }
                handler.post {
                    val msg = result.getOrElse { "Failed: ${it.message}" }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    recreate()
                }
            }.start()
        } else {
            AppState.store.create(media.id)
            Toast.makeText(this, "Request submitted — monitoring started", Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    private fun loadMagnets(media: Media) {
        if (AppState.liveMode) {
            magnetsHeader.text = "🧲 Magnets (searching indexers…)"
            Thread {
                val mags = runCatching { AppState.repo!!.magnetsFor(media) }.getOrElse { emptyList() }
                handler.post {
                    magnetsHeader.text = "🧲 Magnets (${mags.size})"
                    renderMagnets(mags)
                }
            }.start()
        } else {
            val mags = Catalog.magnetsByMediaId[media.id].orEmpty()
            magnetsHeader.text = "🧲 Magnets (${mags.size})"
            renderMagnets(mags.map {
                LiveMagnet(it.name, it.source, it.sizeBytes, it.seeders, it.leechers, downloadUrl = "", magnetUri = it.magnetUri)
            })
        }
    }

    private fun renderMagnets(mags: List<LiveMagnet>) {
        magnetsBox.removeAllViews()
        if (mags.isEmpty()) {
            magnetsBox.addView(Ui.textView(this, "No magnets found on the indexers for this title.", 12f, Ui.DIM).apply {
                val p = dp(6); setPadding(0, p, 0, 0)
            })
            return
        }
        mags.forEach { mag ->
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
                "${formatSize(mag.sizeBytes)} · S ${mag.seeders} / L ${mag.leechers} · ${mag.indexer}",
                11f, Ui.DIM
            ).apply { val p = dp(3); setPadding(0, p, 0, 0) })
            val copyBtn = Button(this).apply {
                text = "Copy magnet"
                setTextColor(Ui.ACCENT)
                background = pillBg(Color.WHITE, 6)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            copyBtn.setOnClickListener {
                copyBtn.isEnabled = false
                copyBtn.text = "Resolving…"
                Thread {
                    val uri = runCatching {
                        if (AppState.liveMode) AppState.repo!!.resolveMagnet(mag) else mag.magnetUri
                    }.getOrElse { mag.downloadUrl }
                    handler.post {
                        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("magnet", uri))
                        Toast.makeText(this@DetailActivity, "Magnet link copied", Toast.LENGTH_SHORT).show()
                        copyBtn.isEnabled = true
                        copyBtn.text = "Copy magnet"
                    }
                }.start()
            }
            card.addView(copyBtn)
            magnetsBox.addView(card)
        }
    }
}
