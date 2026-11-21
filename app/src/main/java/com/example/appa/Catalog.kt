package com.example.appa

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure-Kotlin core of the app: placeholder catalog, magnets, request store and
 * the simulated status pipeline. No Android imports, so it is unit-testable
 * on the JVM (see app/src/test).
 */

enum class MediaType(val slug: String, val label: String) {
    SERIES("series", "Series"),
    MOVIES("movies", "Movies"),
    AUDIOBOOKS("audiobooks", "Audiobooks");

    companion object {
        fun fromSlug(s: String?): MediaType? =
            entries.firstOrNull { it.slug.equals(s ?: "", ignoreCase = true) }
    }
}

enum class RequestStatus { PENDING, PROCESSING, AVAILABLE }

data class Media(
    val id: String,
    val type: MediaType,
    val title: String,
    val year: Int,
    val overview: String,
    val genres: List<String>,
    val rating: Double,
    val color1: String,
    val color2: String,
    val seasons: Int? = null,
    val episodes: Int? = null,
    val runtimeMinutes: Int? = null,
    val author: String? = null,
    val narrator: String? = null,
    val durationHours: Double? = null,
    val available: Boolean = false,
    val posterUrl: String? = null,   // live mode: remote poster (TMDB / OpenLibrary)
    val externalId: String = ""      // live mode: tmdb id or openlibrary key
)

data class Magnet(
    val id: String,
    val mediaId: String,
    val name: String,
    val sizeBytes: Long,
    val seeders: Int,
    val leechers: Int,
    val source: String,
    val magnetUri: String,
    val addedAt: Long
)

data class Request(
    val id: String,
    val mediaId: String,
    val requestedAt: Long,
    val status: RequestStatus,
    val statusUpdatedAt: Long,
    val external: String = "",   // live mode: "radarr:<id>" / "sonarr:<id>"; "" = local-only (audiobooks)
    val snapshot: String = ""    // JSON MediaSnapshot — keeps the request readable after a cold start
)

/** External *arr reference. Wire format is "kind:id" (e.g. "radarr:42"). */
data class ExternalRef(val kind: String, val id: String) {
    override fun toString(): String = "$kind:$id"
    companion object {
        fun parse(s: String): ExternalRef? {
            val i = s.indexOf(':')
            if (i <= 0 || i == s.length - 1) return null
            return ExternalRef(s.substring(0, i), s.substring(i + 1))
        }
    }
}

/**
 * Minimal media snapshot persisted with each request, so the Requests screen
 * still shows title/poster after a cold start (when the live media cache is
 * empty and the placeholder catalog doesn't contain the id).
 */
data class MediaSnapshot(
    val title: String,
    val type: String,
    val year: Int,
    val rating: Double = 0.0,
    val posterUrl: String? = null
) {
    fun toJson(): String = buildJsonObject {
        put("title", title); put("type", type); put("year", year)
        put("rating", rating); put("posterUrl", posterUrl ?: "")
    }.toString()

    fun toMedia(id: String): Media = Media(
        id = id,
        type = MediaType.fromSlug(type) ?: MediaType.AUDIOBOOKS,
        title = title,
        year = year,
        overview = "",
        genres = emptyList(),
        rating = rating,
        color1 = "#1f6feb", color2 = "#8250df",
        posterUrl = posterUrl?.ifBlank { null }
    )

    companion object {
        fun of(m: Media): MediaSnapshot = MediaSnapshot(m.title, m.type.slug, m.year, m.rating, m.posterUrl)
        fun fromJson(s: String): MediaSnapshot? = runCatching {
            val o = jsonX.parseToJsonElement(s).obj()
            MediaSnapshot(
                o["title"].str(), o["type"].str(), o["year"].str().toIntOrNull() ?: 0,
                o["rating"].str().toDoubleOrNull() ?: 0.0, o["posterUrl"].str().ifBlank { null }
            )
        }.getOrNull()
    }
}

/** pending --(pendingMs)--> processing --(processingMs)--> available */
object StatusRules {
    fun next(status: RequestStatus, ageMs: Long, pendingMs: Long, processingMs: Long): RequestStatus? =
        when (status) {
            RequestStatus.PENDING -> if (ageMs >= pendingMs) RequestStatus.PROCESSING else null
            RequestStatus.PROCESSING -> if (ageMs >= processingMs) RequestStatus.AVAILABLE else null
            RequestStatus.AVAILABLE -> null
        }
}

/**
 * Thread-safe request store. Persistence is delegated to load/save lambdas
 * (SharedPreferences on Android, in-memory in tests). Format is a JSON array;
 * the legacy tab-separated format is still READ for one migration release.
 */
class RequestStoreCore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    val pendingDelayMs: Long = 4_000L,
    val processingDelayMs: Long = 10_000L
) {
    private val lock = Any()
    private val items = mutableListOf<Request>()

    init { reload() }

    fun all(): List<Request> = synchronized(lock) { items.toList() }
    fun byMediaId(mediaId: String): Request? = synchronized(lock) { items.firstOrNull { it.mediaId == mediaId } }
    fun count(): Int = synchronized(lock) { items.size }

    /** Returns null when the media already has a request. */
    fun create(mediaId: String, now: Long = System.currentTimeMillis(), external: String = "", snapshot: String = ""): Request? = synchronized(lock) {
        if (items.any { it.mediaId == mediaId }) return@synchronized null
        val r = Request("req-" + UUID.randomUUID().toString().take(8), mediaId, now, RequestStatus.PENDING, now, external, snapshot)
        items.add(r)
        persist()
        r
    }

    /** Recreate a request with a new external id (used after a live *arr add). */
    fun setExternal(id: String, external: String): Request? = synchronized(lock) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized null
        val u = items[i].copy(external = external)
        items[i] = u
        persist()
        u
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val removed = items.removeIf { it.id == id }
        if (removed) persist()
        removed
    }

    fun updateStatus(id: String, status: RequestStatus, now: Long = System.currentTimeMillis()): Request? =
        synchronized(lock) {
            val i = items.indexOfFirst { it.id == id }
            if (i < 0) return@synchronized null
            val u = items[i].copy(status = status, statusUpdatedAt = now)
            items[i] = u
            persist()
            u
        }

    /** Advances every non-final request whose status aged past its delay. Returns changed requests. */
    fun tick(now: Long = System.currentTimeMillis()): List<Request> = synchronized(lock) {
        val changed = mutableListOf<Request>()
        for (r in items.toList()) {
            val next = StatusRules.next(r.status, now - r.statusUpdatedAt, pendingDelayMs, processingDelayMs)
            if (next != null) {
                val i = items.indexOfFirst { it.id == r.id }
                if (i >= 0) {
                    val u = r.copy(status = next, statusUpdatedAt = now)
                    items[i] = u
                    changed.add(u)
                }
            }
        }
        if (changed.isNotEmpty()) persist()
        changed
    }

    fun reload() {
        synchronized(lock) {
            val text = load() ?: return@synchronized
            val parsed = parseAll(text) ?: return@synchronized // corrupt: keep existing items, never wipe
            items.clear()
            items.addAll(parsed)
        }
    }

    /** Parse the persisted text into requests; null = unreadable (caller keeps current items). */
    private fun parseAll(text: String): List<Request>? {
        if (text.trimStart().startsWith("[")) {
            return runCatching {
                jsonX.parseToJsonElement(text).arr().map { el ->
                    val o = el.obj()
                    Request(
                        o["id"].str(), o["mediaId"].str(),
                        o["requestedAt"].str().toLongOrNull() ?: 0L,
                        runCatching { RequestStatus.valueOf(o["status"].str()) }.getOrDefault(RequestStatus.PENDING),
                        o["statusUpdatedAt"].str().toLongOrNull() ?: 0L,
                        external = o["external"].str(),
                        snapshot = o["snapshot"].str()
                    )
                }
            }.getOrNull()
        }
        // legacy format: TSV lines (read-only fallback), tolerant per line
        val out = mutableListOf<Request>()
        for (line in text.split("\n")) {
            if (line.isBlank()) continue
            val p = line.split("\t").map { it.replace("\\t", "\t").replace("\\n", "\n") }
            if (p.size >= 5) {
                runCatching {
                    out.add(Request(p[0], p[1], p[2].toLong(), RequestStatus.valueOf(p[3]), p[4].toLong(), external = if (p.size > 5) p[5] else ""))
                }
            }
        }
        return out
    }

    private val persistIo = Executors.newSingleThreadExecutor()

    private fun persist() {
        val text = synchronized(lock) {
            buildJsonArray {
                for (r in items) add(buildJsonObject {
                    put("id", r.id)
                    put("mediaId", r.mediaId)
                    put("requestedAt", r.requestedAt)
                    put("status", r.status.name)
                    put("statusUpdatedAt", r.statusUpdatedAt)
                    put("external", r.external)
                    put("snapshot", r.snapshot)
                })
            }.toString()
        }
        persistIo.execute { save(text) } // disk write off the caller (often main) thread
    }
}

/** Deterministic placeholder catalog: titles, colors, magnets. Stable across restarts. */
object Catalog {
    private val genrePool = listOf(
        "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary",
        "Drama", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi",
        "Thriller", "Western", "Family", "History"
    )
    private val colorPairs = listOf(
        "#1f6feb" to "#8250df", "#d29922" to "#fb6f92", "#1a7f37" to "#2da44e",
        "#8250df" to "#bf3989", "#9a6700" to "#d29922", "#0969da" to "#57ab5a",
        "#cf222e" to "#8250df", "#0550ae" to "#f778ba", "#116329" to "#aeaefe",
        "#6e40c9" to "#d670d6", "#953800" to "#e16f24", "#0a3069" to "#2188ff"
    )

    val all: List<Media> by lazy {
        buildType(MediaType.SERIES, "Series", 18) +
            buildType(MediaType.MOVIES, "Movie", 18) +
            buildType(MediaType.AUDIOBOOKS, "Audiobook", 18)
    }
    val byId: Map<String, Media> by lazy { all.associateBy { it.id } }
    val magnetsByMediaId: Map<String, List<Magnet>> by lazy { all.associate { it.id to magnetsFor(it) } }

    fun ofType(type: MediaType): List<Media> =
        all.filter { it.type == type }.sortedWith(compareByDescending<Media> { it.rating }.thenBy { it.title })

    /** Matches title, genres, author, narrator or any magnet name of the item. */
    fun search(query: String): List<Media> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.filter { m ->
            m.title.lowercase().contains(q) ||
                m.genres.any { it.lowercase().contains(q) } ||
                (m.author?.lowercase()?.contains(q) == true) ||
                (m.narrator?.lowercase()?.contains(q) == true) ||
                magnetsByMediaId[m.id].orEmpty().any { it.name.lowercase().contains(q) }
        }.sortedWith(compareByDescending<Media> { it.rating }.thenBy { it.title })
    }

    private fun buildType(type: MediaType, label: String, count: Int): List<Media> =
        (1..count).map { i ->
            val seed = Random("${type.slug}-$i".hashCode().toLong())
            val title = "Placeholder $label ${"%02d".format(i)}"
            val id = "${type.slug}-${"%02d".format(i)}"
            val year = 1995 + seed.nextInt(31)
            val genres = List(2) { genrePool[seed.nextInt(genrePool.size)] }.distinct()
            val rating = 5.0 + seed.nextInt(50) / 10.0
            val (c1, c2) = colorPairs[seed.nextInt(colorPairs.size)]
            Media(
                id = id,
                type = type,
                title = title,
                year = year,
                overview = "Placeholder overview for \"$title\" ($year). This text is generated locally and " +
                    "will be replaced by real metadata once TMDB / Sonarr / Radarr are connected.",
                genres = genres,
                rating = rating,
                color1 = c1,
                color2 = c2,
                seasons = if (type == MediaType.SERIES) 1 + seed.nextInt(6) else null,
                episodes = if (type == MediaType.SERIES) 6 + seed.nextInt(19) else null,
                runtimeMinutes = if (type == MediaType.MOVIES) 85 + seed.nextInt(106) else null,
                author = if (type == MediaType.AUDIOBOOKS) "Author Placeholder ${"%02d".format(1 + seed.nextInt(12))}" else null,
                narrator = if (type == MediaType.AUDIOBOOKS) "Narrator Placeholder ${"%02d".format(1 + seed.nextInt(12))}" else null,
                durationHours = if (type == MediaType.AUDIOBOOKS) 4.0 + seed.nextInt(20) + seed.nextInt(10) / 10.0 else null,
                available = i % 6 == 0
            )
        }

    private fun magnetsFor(m: Media): List<Magnet> {
        val rnd = Random(("magnets:" + m.id).hashCode().toLong())
        val count = 4 + rnd.nextInt(3)
        val qualities = when (m.type) {
            MediaType.SERIES, MediaType.MOVIES -> listOf(
                "2160p.WEB-DL.x265-GRP", "1080p.BluRay.x264-GRP", "1080p.WEB-DL.x264-GRP",
                "720p.WEBRip.x264-GRP", "720p.BluRay.x264-GRP", "2160p.BluRay.REMUX-GRP",
                "1080p.WEBRip.x265-GRP"
            )
            MediaType.AUDIOBOOKS -> listOf(
                "FLAC-GRP", "M4B.128kbps-GRP", "MP3.320kbps-GRP", "MP3.128kbps-GRP", "FLAC.24bit-GRP"
            )
        }
        val base = 1_767_744_000_000L // 2026-01-05T00:00:00Z
        return (0 until count).map { i ->
            val q = qualities[rnd.nextInt(qualities.size)]
            val name = when (m.type) {
                MediaType.AUDIOBOOKS -> "${m.title.replace(' ', '.')}.${m.author?.replace(' ', '.')}.[$q]"
                else -> "${m.title.replace(' ', '.')}.${m.year}.$q"
            }
            val hash = sha256Hex("${m.id}#$i").take(40)
            val sizeBytes = when (m.type) {
                MediaType.AUDIOBOOKS ->
                    if (q.startsWith("FLAC")) rnd.nextLong(300_000_000L, 1_500_000_000L)
                    else rnd.nextLong(60_000_000L, 350_000_000L)
                MediaType.SERIES, MediaType.MOVIES -> when {
                    q.startsWith("2160p.BluRay") -> rnd.nextLong(15_000_000_000L, 45_000_000_000L)
                    q.startsWith("2160p") -> rnd.nextLong(8_000_000_000L, 25_000_000_000L)
                    q.startsWith("1080p") -> rnd.nextLong(1_500_000_000L, 8_000_000_000L)
                    else -> rnd.nextLong(600_000_000L, 2_000_000_000L)
                }
            }
            Magnet(
                id = "mag-${m.id}-$i",
                mediaId = m.id,
                name = name,
                sizeBytes = sizeBytes,
                seeders = rnd.nextInt(5, 4200),
                leechers = rnd.nextInt(0, 420),
                source = "PlaceholderIndex ${'A' + rnd.nextInt(5)}",
                magnetUri = "magnet:?xt=urn:btih:$hash&dn=${qenc(name)}" +
                    "&tr=udp%3A%2F%2Ftracker.placeholder.local%3A1337%2Fannounce",
                addedAt = base + i.toLong() * 86_400_000L * 7L
            )
        }.sortedByDescending { it.seeders }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes >= 1_000_000_000L) return "%.2f GB".format(bytes / 1e9)
    if (bytes >= 1_000_000L) return "%.1f MB".format(bytes / 1e6)
    return "%.0f KB".format(bytes / 1e3)
}
