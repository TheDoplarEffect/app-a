package com.example.appa

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A magnet result for the UI. [magnetUri] is resolved lazily via [LiveRepository.resolveMagnet]. */
data class LiveMagnet(
    val name: String,
    val indexer: String,
    val sizeBytes: Long,
    val seeders: Int,
    val leechers: Int,
    val downloadUrl: String,
    val magnetUri: String = ""   // filled on demand; empty until resolved
)

/**
 * Pure status mapping from live backend state to the app's request status.
 * Exposed for unit tests.
 */
object LiveStatus {
    fun compute(inJellyfin: Boolean, inArrHasFile: Boolean, inQueue: Boolean): RequestStatus =
        when {
            inJellyfin || inArrHasFile -> RequestStatus.AVAILABLE
            inQueue -> RequestStatus.PROCESSING
            else -> RequestStatus.PENDING
        }
}

/**
 * Orchestrates the live services. All methods are blocking — call from a
 * background thread; the UI layer posts results back to the main thread.
 */
class LiveRepository(private val cfg: AppConfig, private val http: HttpTransport = realHttp) {

    private val cfgN = cfg.normalized()
    private val tmdb = TmdbClient(cfgN.tmdbToken, http)
    private val books = OpenLibraryClient(http)
    private val prowlarr by lazy { ProwlarrClient(cfgN.prowlarrUrl, cfgN.prowlarrKey, http) }
    private val sonarr by lazy { SonarrClient(cfgN.sonarrUrl, cfgN.sonarrKey, http) }
    private val radarr by lazy { RadarrClient(cfgN.radarrUrl, cfgN.radarrKey, http) }
    private val jellyfin by lazy { JellyfinClient(cfgN.jellyfinUrl, cfgN.jellyfinKey, http) }

    // Resolved lazily on first use (NOT in the constructor — AppState builds the
    // repo on the main thread and a blocking call here is a NetworkOnMainThread ANR).
    @Volatile private var audiobookIndexerIds: String? = null
    @Volatile private var cachedMovieTmdbIds: Set<String> = emptySet()
    @Volatile private var cachedSeriesTmdbIds: Set<String> = emptySet()

    private fun audiobookIndexerIds(): String {
        val cur = audiobookIndexerIds
        if (cur != null) return cur
        val resolved = if (cfgN.hasProwlarr) prowlarr.indexerIdByName("audiobook")?.toString() ?: "" else ""
        audiobookIndexerIds = resolved
        return resolved
    }

    fun pingAll(): Map<String, Boolean> = mapOf(
        "tmdb" to runCatching { tmdb.trending(MediaType.MOVIES).isNotEmpty() }.getOrDefault(false),
        "prowlarr" to (if (cfgN.hasProwlarr) runCatching { prowlarr.search("test", "") }.isSuccess else false),
        "sonarr" to (if (cfgN.hasSonarr) runCatching { sonarr.seriesStates() }.isSuccess else false),
        "radarr" to (if (cfgN.hasRadarr) runCatching { radarr.movieStates() }.isSuccess else false),
        "jellyfin" to (if (cfgN.hasJellyfin) jellyfin.ping() else false)
    )

    // ---------------- browse / search ----------------

    fun browse(type: MediaType, page: Int = 1): List<Media> = when (type) {
        MediaType.MOVIES -> tmdb.trending(MediaType.MOVIES, page)
        MediaType.SERIES -> tmdb.trending(MediaType.SERIES, page)
        MediaType.AUDIOBOOKS -> books.browse(page)
    }.map { markAvailability(it) }

    fun search(query: String, page: Int = 1): List<Media> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<Media>()
        results += tmdb.search(q, MediaType.MOVIES, page)
        results += tmdb.search(q, MediaType.SERIES, page)
        results += books.search(q, page)
        return results.map { markAvailability(it) }
    }

    fun details(media: Media): Media {
        val detailed = when (media.type) {
            MediaType.MOVIES, MediaType.SERIES -> tmdb.details(media)
            MediaType.AUDIOBOOKS -> media
        }
        return markAvailability(detailed)
    }

    // ---------------- magnets ----------------

    fun magnetsFor(media: Media): List<LiveMagnet> {
        if (!cfgN.hasProwlarr) return emptyList()
        val query = when (media.type) {
            MediaType.AUDIOBOOKS -> media.title + (media.author?.let { " $it" } ?: "")
            else -> "${media.title} ${media.year}"
        }
        val indexerIds = if (media.type == MediaType.AUDIOBOOKS) audiobookIndexerIds() else ""
        val raws = prowlarr.search(query, indexerIds).take(25)
        // NOTE: magnets are resolved lazily (resolveMagnet) — resolving all
        // results up-front would mean one slow indexer redirect per result.
        return raws.mapNotNull { raw ->
            LiveMagnet(
                name = raw.title,
                indexer = raw.indexer,
                sizeBytes = raw.size,
                seeders = raw.seeders,
                leechers = raw.leechers,
                downloadUrl = raw.downloadUrl
            )
        }.sortedByDescending { it.seeders }
    }

    /** Resolve one result's download URL to a real magnet link (blocking; call off the UI thread). */
    fun resolveMagnet(m: LiveMagnet): String =
        m.magnetUri.ifBlank { magnetFromDownloadUrl(m.downloadUrl, http) }

    // ---------------- requests ----------------

    /**
     * Add the media to the right *arr. Returns the external reference
     * ("radarr:<id>" / "sonarr:<id>") or "" for audiobooks (local-only).
     */
    fun request(media: Media): String = when (media.type) {
        MediaType.MOVIES -> {
            if (!cfgN.hasRadarr) throw ApiException("Radarr not configured")
            ExternalRef("radarr", radarr.addMovie(media.externalId).toString()).toString()
        }
        MediaType.SERIES -> {
            if (!cfgN.hasSonarr) throw ApiException("Sonarr not configured")
            ExternalRef("sonarr", sonarr.addSeries(media.externalId, media.title).toString()).toString()
        }
        MediaType.AUDIOBOOKS -> "" // no Readarr; tracked locally only
    }

    /** Refresh request statuses from Jellyfin + *arr state. Returns changed requests. */
    fun refreshStatuses(store: RequestStoreCore): List<Request> {
        if (cfgN.hasJellyfin) {
            runCatching { cachedMovieTmdbIds = jellyfin.movieTmdbIds() }
            runCatching { cachedSeriesTmdbIds = jellyfin.seriesTmdbIds() }
        }
        // fetch each *arr as one Result: a transient failure must not downgrade statuses with empty data
        val radarrRes = if (cfgN.hasRadarr) runCatching { radarr.movieStates() to radarr.queueMovieIds() } else null
        val sonarrRes = if (cfgN.hasSonarr) runCatching { sonarr.seriesStates() to sonarr.queueSeriesIds() } else null

        val changed = mutableListOf<Request>()
        val movieById = radarrRes?.getOrNull()?.first?.associateBy { it.radarrId } ?: emptyMap()
        val radarrQueue = radarrRes?.getOrNull()?.second ?: emptySet()
        val seriesById = sonarrRes?.getOrNull()?.first?.associateBy { it.sonarrId } ?: emptyMap()
        val sonarrQueue = sonarrRes?.getOrNull()?.second ?: emptySet()
        for (r in store.all()) {
            if (r.external.isBlank()) continue // local-only (audiobooks): simulated ticker handles it
            // fall back to the request's own snapshot so statuses don't freeze after a cold start
            val media = mediaById(r.mediaId)
                ?: store.byMediaId(r.mediaId)?.snapshot?.let { sn -> MediaSnapshot.fromJson(sn)?.toMedia(r.mediaId) }
                ?: continue
            val inJellyfin = when (media.type) {
                MediaType.MOVIES -> media.externalId in cachedMovieTmdbIds
                MediaType.SERIES -> media.externalId in cachedSeriesTmdbIds
                MediaType.AUDIOBOOKS -> false
            }
            val ref = ExternalRef.parse(r.external) ?: continue
            val id = ref.id.toIntOrNull() ?: -1
            val (hasFile, inQueue) = when (ref.kind) {
                "radarr" -> {
                    if (radarrRes?.isFailure == true) continue // fetch failed: keep current status
                    (movieById[id]?.hasFile ?: false) to (id in radarrQueue)
                }
                "sonarr" -> {
                    if (sonarrRes?.isFailure == true) continue // fetch failed: keep current status
                    (seriesById[id]?.hasFiles ?: false) to (id in sonarrQueue)
                }
                else -> false to false
            }
            val target = LiveStatus.compute(inJellyfin, hasFile, inQueue)
            if (target != r.status) {
                store.updateStatus(r.id, target)?.let { changed.add(it) }
            }
        }
        return changed
    }

    // ---------------- helpers ----------------

    private val mediaCache: MutableMap<String, Media> = java.util.concurrent.ConcurrentHashMap()

    fun remember(media: List<Media>) { media.forEach { mediaCache[it.id] = it } }
    fun mediaById(id: String): Media? = mediaCache[id] ?: Catalog.byId[id]

    private fun markAvailability(m: Media): Media {
        val inJellyfin = when (m.type) {
            MediaType.MOVIES -> m.externalId in cachedMovieTmdbIds
            MediaType.SERIES -> m.externalId in cachedSeriesTmdbIds
            MediaType.AUDIOBOOKS -> false
        }
        if (inJellyfin && !m.available) {
            mediaCache[m.id] = m.copy(available = true)
            return m.copy(available = true)
        }
        mediaCache[m.id] = m
        return m
    }
}

/** Single construction rule for the live repository (null when live mode can't run). */
object RepositoryFactory {
    fun create(cfg: AppConfig, http: HttpTransport = realHttp): LiveRepository? {
        val n = cfg.normalized()
        return if (n.liveReady) LiveRepository(n, http) else null
    }
}
