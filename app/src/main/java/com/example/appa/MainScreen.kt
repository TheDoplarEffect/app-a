package com.example.appa

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Main screen (organism). Three tabs live as pages in a [SwipePager] — swipe
 * sideways or tap the bottom nav. Each tab keeps its own results + scroll
 * position (tab caching), browse results are cached for 45s, and the 5s
 * status ticker only re-renders when something actually changed (no flicker).
 */

fun showMainScreen(activity: Activity) {
    activity.edgeToEdge()
    MainScreen(activity).attach()
}

class MainScreen(private val activity: Activity) {

    private class Page(val root: FrameLayout, val skeleton: GridLayout, val scroll: ScrollView, val grid: GridLayout, val empty: View)

    private var currentTab = MediaType.MOVIES
    private var query = ""
    private val pages = mutableMapOf<MediaType, Page>()
    private val itemsByTab = mutableMapOf<MediaType, List<Media>>()
    private val loadedAt = mutableMapOf<MediaType, Long>()
    private val searchCache = mutableMapOf<String, Pair<Long, List<Media>>>()
    private val signatureByTab = mutableMapOf<MediaType, String>()
    private var searchSignature = ""

    private lateinit var search: SearchField
    private lateinit var pager: SwipePager
    private lateinit var nav: BottomNavBar
    private lateinit var topBar: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (AppState.liveMode) {
                AppState.store.tick()
                Thread {
                    val changed = runCatching { AppState.repo?.refreshStatuses(AppState.store) ?: emptyList() }.getOrElse { emptyList() }
                    handler.post {
                        if (changed.isNotEmpty()) renderCurrent(animate = false)
                        renderRequestsBadge()
                    }
                }.start()
            } else {
                if (AppState.store.tick().isNotEmpty()) renderCurrent(animate = false)
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
            addView(ModeBanner(activity, AppState.liveMode, t), lp(WRAP, WRAP, marginTopDp = t.space.sm))

            search = SearchField(activity, if (AppState.liveMode) "Search TMDB / Open Library…" else "Search titles, genres, authors…", t) { q ->
                query = q
                loadItems()
            }
            addView(search.root, lp(MATCH, WRAP, marginTopDp = t.space.md))

            // swipeable pages, one per tab
            pager = SwipePager(activity)
            pager.setPageCount(MediaType.entries.size)
            MediaType.entries.forEach { type ->
                val page = buildPage(type, t)
                pages[type] = page
                pager.addView(page.root)
            }
            pager.onPageSelected = { i ->
                currentTab = MediaType.entries[i]
                nav.select(i)
                loadItems()
            }
            addView(pager, lp(MATCH, 0, weight = 1f, marginTopDp = t.space.md))

            val navWrap = col(activity) {
                padSystemBars(bottom = true, extraBottomDp = t.space.sm)
                val m = activity.dp(t.space.md)
                setPadding(m, 0, m, 0)
                nav = BottomNavBar(
                    activity,
                    listOf(NavItem("📺", "Series"), NavItem("🎥", "Movies"), NavItem("🎧", "Audiobooks")),
                    initial = MediaType.entries.indexOf(currentTab),
                    t
                ) { i -> pager.goToPage(i) }
                addView(nav.root, lp(MATCH, WRAP))
            }
            addView(navWrap, lp(MATCH, WRAP))
        }
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        activity.setContentView(root)

        loadItems()
        renderRequestsBadge()
        handler.postDelayed(ticker, 1000)
    }

    private fun buildPage(type: MediaType, t: DesignTokens): Page {
        val skeleton = GridLayout(activity).apply { columnCount = 3; useDefaultMargins = true }
        repeat(9) {
            skeleton.addView(Skeleton(activity, 0, 150, t).apply {
                layoutParams = GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) }
            })
        }
        val grid = GridLayout(activity).apply { columnCount = 3; useDefaultMargins = true }
        val scroll = ScrollView(activity).apply { addView(grid) }
        val empty = EmptyState(activity, "🔍", "Nothing here", "No titles match your search.", t)
        val root = FrameLayout(activity)
        root.addView(skeleton, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        skeleton.visibility = View.GONE
        scroll.visibility = View.GONE
        empty.visibility = View.GONE
        return Page(root, skeleton, scroll, grid, empty)
    }

    private fun showLoading(p: Page, loading: Boolean, hasItems: Boolean) {
        p.skeleton.visibility = if (loading && !hasItems) View.VISIBLE else View.GONE
        p.scroll.visibility = if (hasItems) View.VISIBLE else View.GONE
        p.empty.visibility = if (!loading && !hasItems) View.VISIBLE else View.GONE
    }

    private fun loadItems() {
        val myJob = ++loadJob
        val live = AppState.liveMode
        val q = query
        val tab = currentTab
        val p = pages[tab] ?: return
        val now = System.currentTimeMillis()

        if (!live) {
            itemsByTab[tab] = if (q.isBlank()) AppState.catalog.ofType(tab) else AppState.catalog.search(q)
            renderCurrent(animate = true)
            return
        }

        // cache: fresh browse results (<45s) render instantly, then refresh quietly
        if (q.isBlank()) {
            val cached = itemsByTab[tab]
            val fresh = loadedAt[tab]?.let { now - it < 45_000 } == true
            if (cached != null && fresh) {
                renderCurrent(animate = false)
            } else if (cached != null) {
                renderCurrent(animate = false) // show stale instantly
                showLoading(p, loading = true, hasItems = true)
            } else {
                showLoading(p, loading = true, hasItems = false)
            }
            Thread {
                val result = runCatching { AppState.repo!!.browse(tab) }.getOrElse { emptyList() }
                handler.post {
                    if (myJob != loadJob) return@post
                    AppState.repo?.remember(result)
                    itemsByTab[tab] = result
                    loadedAt[tab] = now
                    if (tab == currentTab) renderCurrent(animate = false)
                }
            }.start()
        } else {
            val key = q
            val cached = searchCache[key]
            if (cached != null && now - cached.first < 45_000) {
                itemsByTab[tab] = cached.second
                renderCurrent(animate = false)
                return
            }
            if (cached != null) {
                itemsByTab[tab] = cached.second
                renderCurrent(animate = false)
            } else {
                showLoading(p, loading = true, hasItems = false)
            }
            Thread {
                val result = runCatching { AppState.repo!!.search(key) }.getOrElse { emptyList() }
                handler.post {
                    if (myJob != loadJob) return@post
                    AppState.repo?.remember(result)
                    searchCache[key] = now to result
                    itemsByTab[tab] = result
                    if (tab == currentTab) renderCurrent(animate = false)
                }
            }.start()
        }
    }

    private fun renderCurrent(animate: Boolean) {
        val tab = currentTab
        val p = pages[tab] ?: return
        val items = itemsByTab[tab] ?: emptyList()
        // diff-based: only touch the grid when something actually changed
        val signature = if (query.isBlank()) {
            items.joinToString("|") { "${it.id}:${it.available}:${AppState.requestFor(it.id)?.status ?: "-"}" }
        } else {
            items.joinToString("|") { "${it.id}:${it.available}" }
        }
        val lastSig = if (query.isBlank()) signatureByTab[tab] else searchSignature
        if (signature == lastSig && !animate) return
        if (query.isBlank()) signatureByTab[tab] = signature else searchSignature = signature

        p.grid.removeAllViews()
        items.forEach { m ->
            p.grid.addView(PosterCard(activity, m, t = AppTheme.current) { activity.openRisingTo(DetailActivity::class.java, "mediaId", m.id) })
        }
        showLoading(p, loading = false, hasItems = items.isNotEmpty())
        if (animate) p.grid.staggerIn() else p.grid.fadeIn(durationMs = AppTheme.current.motion.shortMs)
    }

    private fun renderRequestsBadge() {
        topBar.findViewWithTag<FrameLayout>("top_action_📥")?.setBadge(AppState.store.count())
    }
}
