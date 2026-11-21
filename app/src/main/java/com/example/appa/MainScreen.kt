package com.example.appa

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Main screen (organism). Three tabs live as pages in a [SwipePager] — swipe
 * sideways or tap the bottom nav. Per-tab results are cached process-wide
 * (key "tab|query", 45s TTL, stale shown instantly while refreshing), and the
 * status ticker only re-renders when a signature actually changed and is
 * guarded against overlapping runs. Network errors show a distinct error
 * state with Retry — never masquerade as "no results".
 */

fun showMainScreen(activity: Activity, savedInstanceState: android.os.Bundle? = null): MainScreen {
    activity.edgeToEdge()
    return MainScreen(activity).also { it.attach(savedInstanceState) }
}

class MainScreen(private val activity: Activity) {

    private class Page(val root: FrameLayout, val skeleton: GridLayout, val scroll: ScrollView, val grid: GridLayout, val empty: View) {
        var error: View? = null
        var inlineError: View? = null
    }

    private var currentTab = MediaType.MOVIES
    private var query = ""
    private val pages = mutableMapOf<MediaType, Page>()

    private lateinit var search: SearchField
    private lateinit var pager: SwipePager
    private lateinit var nav: BottomNavBar
    private lateinit var topBar: LinearLayout
    private lateinit var banner: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob = 0
    private var attached = true
    private var navInitiated = false
    private val startPoller = Runnable { poller.start() }
    private val poller = StatusPoller { changed, _ ->
        if (changed) {
            renderCurrent(animate = false)
            renderRequestsBadge()
        }
    }

    /** Process-level result cache: survives rotation (no skeleton flash), evicted when stale. */
    private val cache = Companion.cache
    private val signatures = Companion.signatures

    private fun spawnWorker(block: () -> Unit) = WorkerScope.spawn(block)

    private var configListener: (() -> Unit)? = null

    fun attach(savedInstanceState: android.os.Bundle? = null) {
        AppState.init(activity)
        savedInstanceState?.let { // rotation: keep tab + search query instead of resetting to Movies/""
            currentTab = MediaType.entries[it.getInt("tab", MediaType.entries.indexOf(currentTab)).coerceIn(0, MediaType.entries.size - 1)]
            query = it.getString("query", "")
        }
        val listener: () -> Unit = {
            cache.clear(); signatures.clear() // mode may have flipped — never serve placeholder results as live
            refreshChrome()
            loadItems()
        }
        configListener = listener
        AppState.onConfigChanged = listener
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
                    TopBarAction("📥", badge = AppState.store.count(), contentDescription = "Requests") { activity.openRisingTo(RequestsActivity::class.java) },
                    TopBarAction("⚙", contentDescription = "Settings") { activity.openRisingTo(SettingsActivity::class.java) }
                )
            )
            addView(topBar, lp(MATCH, WRAP, marginTopDp = t.space.xs))

            banner = ModeBanner(activity, AppState.liveMode, t)
            banner.setOnClickListener { activity.openRisingTo(SettingsActivity::class.java) }
            banner.pressable()
            addView(banner, lp(WRAP, WRAP, marginTopDp = t.space.sm))

            search = SearchField(activity, searchHint(), t) { q ->
                query = q
                loadItems()
            }
            if (query.isNotEmpty()) search.input.setText(query) // restored search (see onSaveState)
            addView(search.root, lp(MATCH, WRAP, marginTopDp = t.space.md))

            pager = SwipePager(activity)
            pager.pageCount = MediaType.entries.size
            MediaType.entries.forEach { type ->
                val page = buildPage(type, t)
                pages[type] = page
                pager.addView(page.root)
            }
            pager.onPageSelected = { i ->
                currentTab = MediaType.entries[i]
                if (i != nav.selected) nav.select(i) // recursion guard: only sync the nav when the page actually changed
                if (navInitiated) { // scroll to top only for explicit nav taps, not swipes
                    pages[currentTab]?.scroll?.smoothScrollTo(0, 0)
                    navInitiated = false
                    hideKeyboard()
                }
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
                ) { i -> navInitiated = true; if (i != pager.current) pager.goToPage(i) } // recursion guard
                addView(nav.root, lp(MATCH, WRAP))
            }
            addView(navWrap, lp(MATCH, WRAP))
        }
        root.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        activity.setContentView(root)

        loadItems()
        renderRequestsBadge()
        handler.postDelayed(startPoller, 1000)
    }

    /** Called from MainActivity.onResume — re-render chrome in case config changed while away. */
    fun onResume() {
        refreshChrome()
        loadItems()
    }

    /** Called from MainActivity.onStart. */
    fun onStart() {
        attached = true
        handler.removeCallbacks(startPoller)
        poller.start()
    }

    /** Called from MainActivity.onStop — pause polling while covered. */
    fun onStop() {
        attached = false
        handler.removeCallbacks(startPoller)
        poller.stop()
    }

    /** Called from MainActivity.onDestroy — stops the poller and interrupts workers. */
    fun detach() {
        attached = false
        handler.removeCallbacks(startPoller)
        poller.stop()
        // no WorkerScope.cancelAll(): it interrupts OTHER screens' in-flight workers process-wide
        if (AppState.onConfigChanged === configListener) AppState.onConfigChanged = null // CAS: rotation must not null the new screen's listener
        configListener = null
    }

    /** Persisted across rotation via MainActivity.onSaveInstanceState. */
    fun onSaveState(out: android.os.Bundle) {
        out.putInt("tab", MediaType.entries.indexOf(currentTab))
        out.putString("query", query)
    }

    private fun searchHint(): String =
        if (AppState.liveMode) "Search TMDB / Open Library…" else "Search titles, genres, authors…"

    /** Re-render top bar subtitle, mode banner and search hint after a config change. */
    private fun refreshChrome() {
        if (!::topBar.isInitialized) return
        val t = AppTheme.current
        topBar.setSubtitle(if (AppState.liveMode) "Live" else "Placeholder")
        if (::banner.isInitialized) {
            val live = AppState.liveMode
            val (text, color) = if (live)
                "🌐 Live — TMDB · Sonarr/Radarr · Prowlarr · Jellyfin" to t.colors.success
            else
                "🧪 Placeholder mode — tap to connect your services in Settings" to t.colors.warning
            banner.text = text
            banner.restyleChip(color, t)
            banner.contentDescription = if (live) "Live mode active. Tap to open settings." else "Placeholder mode. Tap to open settings."
        }
        if (::search.isInitialized) search.input.hint = searchHint()
        renderRequestsBadge()
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }

    private fun cacheKey(tab: MediaType, q: String) = (if (AppState.liveMode) "L" else "P") + "|$tab|$q"

    private fun buildPage(type: MediaType, t: DesignTokens): Page {
        val skeleton = GridLayout(activity).apply { columnCount = GRID_COLUMNS; useDefaultMargins = true }
        repeat(9) {
            skeleton.addView(Skeleton(activity, 0, 150, t).apply {
                layoutParams = GridLayout.LayoutParams().apply { width = 0; height = activity.dp(150); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) } // explicit height: WRAP would measure 0 and the skeleton would be invisible
            })
        }
        val grid = GridLayout(activity).apply { columnCount = GRID_COLUMNS; useDefaultMargins = true }
        val scroll = ScrollView(activity).apply { addView(grid) }
        val empty = EmptyState(activity, "🔍", "Nothing here", "", t)
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

    private fun clearError(p: Page) {
        p.error?.let { p.root.removeView(it); p.error = null }
        p.inlineError?.let { p.root.removeView(it); p.inlineError = null }
        if (p.empty.parent == null) p.root.addView(p.empty, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Small banner at the top of the page; the grid below stays untouched (stale content stays visible). */
    private fun showInlineError(p: Page, error: Throwable?) {
        clearError(p)
        val t = AppTheme.current
        val v = AppText(activity, "⚠ Refresh failed: ${error?.message?.take(80) ?: "network error"} — showing cached results", TextStyle.LABEL, color = t.colors.warning, t = t).apply {
            val px = activity.dp(t.space.md); val py = activity.dp(t.space.xs)
            setPadding(px, py, px, py)
            background = withAlpha(t.colors.warning, 0.18f).let { android.graphics.drawable.ColorDrawable(it) }
        }
        p.root.addView(v, FrameLayout.LayoutParams(MATCH, WRAP, android.view.Gravity.TOP))
        p.inlineError = v
    }

    private fun evictCache() {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        cache.entries.removeAll { it.value.first < cutoff }
        if (cache.size > CACHE_MAX_ENTRIES) { // LRU-ish: drop the oldest half
            val oldest = cache.entries.sortedBy { it.value.first }.take(cache.size - CACHE_MAX_ENTRIES)
            oldest.forEach { cache.remove(it.key) }
        }
        signatures.entries.removeAll { !cache.containsKey(it.key) }
    }

    private fun loadItems() {
        val myJob = ++loadJob
        val live = AppState.liveMode
        val q = query
        val tab = currentTab
        val p = pages[tab] ?: return
        val key = cacheKey(tab, q)
        val now = System.currentTimeMillis()
        evictCache()

        if (!live) {
            val all = if (q.isBlank()) Catalog.ofType(tab) else Catalog.search(q)
            val hadCache = cache.containsKey(key)
            cache[key] = now to all.filter { q.isBlank() || it.type == tab }
            renderCurrent(animate = !hadCache)
            return
        }

        val cached = cache[key]
        val animateFirst = cached == null
        if (cached != null) {
            renderCurrent(animate = false) // stale-while-revalidate: show instantly
            if (now - cached.first < CACHE_TTL_MS) return // still fresh, no fetch
            showLoading(p, loading = true, hasItems = true)
        } else {
            clearError(p)
            val hasItems = p.grid.childCount > 0 // keep previous results visible during search
            showLoading(p, loading = true, hasItems = hasItems)
        }
        spawnWorker {
            val repo = AppState.repo
            // live search merges all types — scope to the current tab
            val result = if (repo == null) {
                Result.failure(ApiException("Live mode off"))
            } else runCatching {
                val raw = if (q.isBlank()) repo.browse(tab) else repo.search(q)
                raw.filter { it.type == tab }
            }
            handler.post {
                if (myJob != loadJob || !attached) return@post
                val items = result.getOrElse { null }
                if (items == null) {
                    val stale = cache[key]?.second
                    if (!stale.isNullOrEmpty()) showInlineError(p, result.exceptionOrNull())
                    else showError(p, result.exceptionOrNull())
                    return@post
                }
                clearError(p)
                AppState.repo?.remember(items)
                cache[key] = System.currentTimeMillis() to items
                renderCurrent(animate = animateFirst)
            }
        }
    }

    private fun showError(p: Page, error: Throwable?) {
        clearError(p) // single error view — never stack overlays
        p.skeleton.visibility = View.GONE
        p.scroll.visibility = View.GONE
        p.empty.visibility = View.GONE
        val t = AppTheme.current
        val errView = col(activity) {
            gravity = android.view.Gravity.CENTER
            val pad = activity.dp(t.space.xl); setPadding(pad, pad, pad, pad)
            addView(AppText(activity, "😕", TextStyle.DISPLAY, t = t).apply { gravity = android.view.Gravity.CENTER })
            addView(AppText(activity, "Couldn't load titles", TextStyle.TITLE, bold = true, t = t).apply {
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = activity.dp(8) }
            })
            addView(AppText(
                activity,
                (error?.message ?: "Network error").take(120) +
                    if (AppState.liveMode) "\nCheck the service URLs in ⚙ Settings." else "",
                TextStyle.BODY, color = t.colors.textSecondary, t = t
            ).apply {
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = activity.dp(4) }
            })
            addView(PrimaryButton(activity, "Retry", t = t) { loadItems() }, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = activity.dp(16)
                gravity = android.view.Gravity.CENTER
            })
        }
        p.root.addView(errView, FrameLayout.LayoutParams(MATCH, MATCH))
        p.error = errView
    }

    private fun renderCurrent(animate: Boolean) {
        val tab = currentTab
        val p = pages[tab] ?: return
        val key = cacheKey(tab, query)
        val items = cache[key]?.second ?: emptyList()
        val signature = items.joinToString("|") { "${it.id}:${it.available}:${AppState.requestFor(it.id)?.status ?: "-"}" }
        if (signature == signatures[key] && !animate) return
        signatures[key] = signature

        clearError(p)
        p.grid.removeAllViews()
        val t = AppTheme.current
        items.forEach { m ->
            p.grid.addView(PosterCard(activity, m, status = AppState.requestFor(m.id)?.status, t = t) {
                hideKeyboard()
                activity.openRisingTo(DetailActivity::class.java, "mediaId", m.id)
            })
        }
        if (items.isEmpty()) {
            val e = p.empty as LinearLayout
            (e.getChildAt(1) as android.widget.TextView).text = "Nothing here"
            (e.getChildAt(2) as android.widget.TextView).text =
                if (query.isBlank()) "Start typing to search, or browse the trending titles." else "No titles match your search."
        }
        showLoading(p, loading = false, hasItems = items.isNotEmpty())
        if (items.isNotEmpty() && animate) p.grid.staggerIn() // no re-animation on data ticks
    }

    private fun renderRequestsBadge() {
        if (::topBar.isInitialized) topBar.findViewWithTag<FrameLayout>("top_action_📥")?.setBadge(AppState.store.count())
    }

    private companion object {
        const val GRID_COLUMNS = 3
        const val CACHE_TTL_MS = 45_000L
        const val CACHE_MAX_AGE_MS = 5 * 60_000L
        const val CACHE_MAX_ENTRIES = 24
        // process-level: survives activity recreation (rotation)
        val cache = mutableMapOf<String, Pair<Long, List<Media>>>()      // key "tab|query" -> (fetchedAt, items)
        val signatures = mutableMapOf<String, String>()                    // key -> grid signature
    }
}
