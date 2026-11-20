package com.example.appa

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Main screen: three tabs (Series / Movies / Audiobooks), a search field and a
 * poster grid. Requests live behind the header button. All layout is
 * programmatic so no R class / XML layouts are needed.
 */

fun showMainScreen(activity: Activity) {
    MainScreen(activity).attach()
}

fun openDetail(activity: Activity, mediaId: String) {
    activity.startActivity(Intent(activity, DetailActivity::class.java).putExtra("mediaId", mediaId))
}

class MainScreen(private val activity: Activity) {

    private var currentTab = MediaType.MOVIES
    private var query = ""

    private lateinit var requestsBtn: Button
    private lateinit var searchInput: EditText
    private lateinit var grid: GridLayout
    private lateinit var scroll: ScrollView
    private lateinit var empty: View
    private val tabButtons = mutableMapOf<MediaType, Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val changed = AppState.store.tick()
            if (changed.isNotEmpty()) {
                renderGrid()
                renderRequestsCount()
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun attach() {
        AppState.init(activity)

        val root = Ui.vRow(activity)
        Ui.padded(root, 12)
        root.setBackgroundColor(Color.WHITE)

        // ---- header ----
        val header = Ui.hRow(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.textView(activity, "🎬 MediaSeer (app-a)", 20f, Ui.TEXT, bold = true))
        header.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        requestsBtn = Button(activity).apply {
            text = "Requests"
            setTextColor(Color.WHITE)
            background = activity.pillBg(Ui.ACCENT, 10)
            setOnClickListener {
                activity.startActivity(Intent(activity, RequestsActivity::class.java))
            }
        }
        header.addView(requestsBtn)
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- placeholder banner ----
        val banner = Ui.textView(
            activity,
            "🧪 Placeholder mode: titles, posters, magnets and statuses are generated locally until Sonarr/Radarr/TMDB are connected.",
            11f, Ui.BANNER_TEXT
        ).apply {
            background = activity.pillBg(Ui.BANNER_BG, 6)
            val p = activity.dp(8); setPadding(p, p, p, p)
        }
        root.addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(8)
        })

        // ---- search ----
        searchInput = EditText(activity).apply {
            hint = "Search titles, genres, authors, magnets…"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = activity.pillBg(Ui.CARD_BG, 8)
            val p = activity.dp(10); setPadding(p, p, p, p)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString() ?: ""
                    renderGrid()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(10)
        })

        // ---- tabs (exactly three: series / movies / audiobooks) ----
        val tabRow = Ui.hRow(activity).apply { gravity = Gravity.CENTER }
        MediaType.entries.forEach { type ->
            val b = Button(activity).apply {
                text = when (type) {
                    MediaType.SERIES -> "📺 Series"
                    MediaType.MOVIES -> "🎥 Movies"
                    MediaType.AUDIOBOOKS -> "🎧 Audiobooks"
                }
                setOnClickListener {
                    currentTab = type
                    query = ""
                    searchInput.setText("")
                    renderTabs()
                    renderGrid()
                }
            }
            tabRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = activity.dp(4); marginEnd = activity.dp(4)
            })
            tabButtons[type] = b
        }
        root.addView(tabRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(8)
        })

        // ---- grid ----
        grid = GridLayout(activity).apply { columnCount = 3; useDefaultMargins = true }
        scroll = ScrollView(activity).apply { addView(grid) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = activity.dp(10)
        })
        empty = Ui.textView(activity, "No titles match your search.", 14f, Ui.DIM).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
        }
        root.addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = activity.dp(10)
        })

        renderTabs()
        renderGrid()
        renderRequestsCount()
        activity.setContentView(root)
        handler.postDelayed(ticker, 1000)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
    }

    private fun renderTabs() {
        tabButtons.forEach { (type, b) ->
            val active = type == currentTab
            b.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            b.setTextColor(if (active) Ui.ACCENT else Ui.DIM)
        }
    }

    private fun items(): List<Media> =
        if (query.isBlank()) AppState.catalog.ofType(currentTab)
        else AppState.catalog.search(query) // global search across all tabs + magnet names

    private fun renderGrid() {
        grid.removeAllViews()
        val list = items()
        val isEmpty = list.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        scroll.visibility = if (isEmpty) View.GONE else View.VISIBLE
        list.forEach { m ->
            grid.addView(cardView(activity, m) { openDetail(activity, m.id) })
        }
    }

    private fun renderRequestsCount() {
        val n = AppState.store.count()
        requestsBtn.text = if (n > 0) "Requests ($n)" else "Requests"
    }
}

fun cardView(activity: Activity, m: Media, onClick: () -> Unit): View {
    val col = Ui.vRow(activity)
    col.layoutParams = GridLayout.LayoutParams().apply {
        width = 0
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
    }

    val frame = FrameLayout(activity)
    frame.addView(PosterView(activity, m), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

    val rating = Ui.textView(activity, "★ %.1f".format(m.rating), 10f, Color.WHITE, bold = true).apply {
        background = activity.pillBg(0xAA000000.toInt(), 10)
        gravity = Gravity.CENTER
    }
    frame.addView(rating, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.TOP or Gravity.END
        topMargin = activity.dp(4); marginEnd = activity.dp(4)
    })

    val status = AppState.requestFor(m.id)?.status
    val label = Ui.statusLabel(m, status)
    if (label != null) {
        val badge = Ui.pill(activity, label, Ui.statusColor(status) ?: Ui.GREEN)
        frame.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = activity.dp(4); marginStart = activity.dp(4)
        })
    }

    col.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    col.addView(Ui.textView(activity, m.title, 12f, Ui.TEXT, bold = true).apply {
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = activity.dp(4)
    })
    col.addView(Ui.textView(activity, "${m.year} · ${m.genres.joinToString(", ")}", 10f, Ui.DIM).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    })

    col.setOnClickListener { onClick() }
    return col
}
