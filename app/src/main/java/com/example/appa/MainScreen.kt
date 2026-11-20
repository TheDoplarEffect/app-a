package com.example.appa

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Main screen (organism): aurora background + top app bar (requests badge,
 * settings) + mode banner + search field + poster grid + bottom navigation
 * with the three tabs (Series / Movies / Audiobooks). All atoms come from
 * Components.kt; all values from AppTheme.
 */

fun showMainScreen(activity: Activity) {
    activity.edgeToEdge()
    MainScreen(activity).attach()
}

class MainScreen(private val activity: Activity) {

    private var currentTab = MediaType.MOVIES
    private var query = ""
    private var items: List<Media> = emptyList()

    private lateinit var search: SearchField
    private lateinit var grid: GridLayout
    private lateinit var scroll: ScrollView
    private lateinit var emptyView: View
    private lateinit var skeletonBox: GridLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNavBar
    private lateinit var topBar: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (AppState.liveMode) {
                AppState.store.tick()
                Thread {
                    runCatching { AppState.repo?.refreshStatuses(AppState.store) }
                    handler.post { renderGrid(); renderRequestsBadge() }
                }.start()
            } else {
                if (AppState.store.tick().isNotEmpty()) {
                    renderGrid()
                    renderRequestsBadge()
                }
            }
            handler.postDelayed(this, if (AppState.liveMode) 5000 else 1000)
        }
    }

    fun attach() {
        AppState.init(activity)
        val t = AppTheme.current

        val root = FrameLayout(activity).withAurora()

        val body = col(activity) {
            padSystemBars(top = true, start = true, end = true, extraTopDp = t.space.sm)
            val px = activity.dp(t.space.lg); setPadding(px, paddingTop, px, 0)

            // top app bar — safely below status bar / pinhole thanks to padSystemBars
            topBar = AppTopBar(
                activity,
                "MediaSeer",
                subtitle = if (AppState.liveMode) "Live" else "Placeholder",
                actions = listOf(
                    TopBarAction("📥", badge = AppState.store.count()) { activity.openRisingTo(RequestsActivity::class.java) },
                    TopBarAction("⚙") { activity.openRisingTo(SettingsActivity::class.java) }
                )
            )
            addView(topBar, lp(MATCH, WRAP, marginTopDp = t.space.xs))

            // mode banner
            addView(ModeBanner(activity, AppState.liveMode, t), lp(WRAP, WRAP, marginTopDp = t.space.sm))

            // search
            search = SearchField(activity, if (AppState.liveMode) "Search TMDB / Open Library…" else "Search titles, genres, authors…") { q ->
                query = q
                loadItems()
            }
            addView(search.root, lp(MATCH, WRAP, marginTopDp = t.space.md))

            // content: skeleton | grid | empty
            content = FrameLayout(activity)
            skeletonBox = GridLayout(activity).apply { columnCount = 3; useDefaultMargins = true }
            scroll = ScrollView(activity)
            grid = GridLayout(activity).apply { columnCount = 3; useDefaultMargins = true }
            scroll.addView(grid)
            emptyView = EmptyState(activity, "🔍", "Nothing here", "No titles match your search.")
            content.addView(skeletonBox, FrameLayout.LayoutParams(MATCH, MATCH))
            content.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            content.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(content, lp(MATCH, 0, weight = 1f, marginTopDp = t.space.md))

            // bottom navigation (three tabs) — padded above the gesture nav bar
            val navWrap = col(activity) {
                padSystemBars(bottom = true, extraBottomDp = t.space.sm)
                val m = activity.dp(t.space.md)
                setPadding(m, 0, m, 0)
                nav = BottomNavBar(
                    activity,
                    listOf(
                        NavItem("📺", "Series"),
                        NavItem("🎥", "Movies"),
                        NavItem("🎧", "Audiobooks")
                    ),
                    initial = MediaType.entries.indexOf(currentTab)
                ) { i ->
                    currentTab = MediaType.entries[i]
                    query = ""
                    search.setTextSilently("")
                    loadItems()
                }
                addView(nav.root, lp(MATCH, WRAP))
            }
            addView(navWrap, lp(MATCH, WRAP))
        }
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))

        activity.setContentView(root)

        skeletonBox.removeAllViews()
        repeat(9) { skeletonBox.addView(Skeleton(activity, 0, 150, t).apply {
            layoutParams = GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) }
        }) }

        showLoading(true)
        loadItems()
        renderRequestsBadge()
        handler.postDelayed(ticker, 1000)
    }

    private fun showLoading(loading: Boolean) {
        skeletonBox.visibility = if (loading) View.VISIBLE else View.GONE
        if (!loading) scroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (!loading && items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadItems() {
        val myJob = ++loadJob
        val live = AppState.liveMode
        val q = query
        if (live) {
            showLoading(true)
            Thread {
                val result = runCatching {
                    if (q.isBlank()) AppState.repo!!.browse(currentTab)
                    else AppState.repo!!.search(q)
                }.getOrElse { emptyList() }
                handler.post {
                    if (myJob != loadJob) return@post
                    AppState.repo?.remember(result)
                    items = result
                    showLoading(false)
                    renderGrid()
                }
            }.start()
        } else {
            items = if (q.isBlank()) AppState.catalog.ofType(currentTab) else AppState.catalog.search(q)
            showLoading(false)
            renderGrid()
        }
    }

    private fun renderGrid() {
        grid.removeAllViews()
        items.forEach { m ->
            grid.addView(PosterCard(activity, m) { activity.openRisingTo(DetailActivity::class.java, "mediaId", m.id) })
        }
        grid.staggerIn()
        showLoading(false)
    }

    private fun renderRequestsBadge() {
        topBar.findViewWithTag<FrameLayout>("top_action_📥")?.setBadge(AppState.store.count())
    }
}
