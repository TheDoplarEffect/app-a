package com.example.appa

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live service clients (TMDB, Open Library, Prowlarr, Sonarr, Radarr, Jellyfin).
 * Pure Kotlin: all take an injectable [HttpTransport] so they are testable on
 * the JVM, and parse responses via kotlinx.serialization JsonElement (no
 * compiler plugin needed). Shared JSON accessors live in JsonX.kt.
 */

// ---------------------------------------------------------------- TMDB

class TmdbClient(private val token: String, private val http: HttpTransport = realHttp) {
    private val base = "https://api.themoviedb.org/3"
    private val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
    private val img = "https://image.tmdb.org/t/p/w500"

    private fun get(path: String, params: String = ""): JsonElement =
        httpGetJson("$base$path?$params", headers, http)

    fun search(query: String, type: MediaType): List<Media> {
        val q = qenc(query)
        val el = when (type) {
            MediaType.MOVIES -> get("/search/movie", "query=$q&include_adult=false")
            MediaType.SERIES -> get("/search/tv", "query=$q&include_adult=false")
            MediaType.AUDIOBOOKS -> return emptyList() // audiobooks come from Open Library
        }
        return el.obj()["results"].arr().mapNotNull { it.toMedia(type) }
    }

    fun trending(type: MediaType): List<Media> {
        val el = when (type) {
            MediaType.MOVIES -> get("/trending/movie/week")
            MediaType.SERIES -> get("/trending/tv/week")
            MediaType.AUDIOBOOKS -> return emptyList()
        }
        return el.obj()["results"].arr().mapNotNull { it.toMedia(type) }
    }

    fun details(media: Media): Media {
        if (media.externalId.isBlank()) return media
        return try {
            when (media.type) {
                MediaType.MOVIES -> {
                    val o = get("/movie/${media.externalId}").obj()
                    media.copy(
                        overview = o["overview"].str(media.overview),
                        genres = o["genres"].arr().map { it.obj()["name"].str() },
                        rating = o["vote_average"].dbl(media.rating),
                        runtimeMinutes = o["runtime"].int(0).takeIf { it > 0 } ?: media.runtimeMinutes,
                        year = o["release_date"].str().take(4).toIntOrNull() ?: media.year
                    )
                }
                MediaType.SERIES -> {
                    val o = get("/tv/${media.externalId}").obj()
                    media.copy(
                        overview = o["overview"].str(media.overview),
                        genres = o["genres"].arr().map { it.obj()["name"].str() },
                        rating = o["vote_average"].dbl(media.rating),
                        seasons = o["number_of_seasons"].int(0).takeIf { it > 0 },
                        episodes = o["number_of_episodes"].int(0).takeIf { it > 0 },
                        year = o["first_air_date"].str().take(4).toIntOrNull() ?: media.year
                    )
                }
                MediaType.AUDIOBOOKS -> media
            }
        } catch (e: Exception) { media }
    }

    private fun JsonElement.toMedia(type: MediaType): Media? {
        val o = obj()
        val id = o["id"].int(-1)
        if (id <= 0) return null
        val title = when (type) {
            MediaType.MOVIES -> o["title"].str()
            MediaType.SERIES -> o["name"].str()
            else -> return null
        }
        if (title.isBlank()) return null
        val date = when (type) {
            MediaType.MOVIES -> o["release_date"].str()
            MediaType.SERIES -> o["first_air_date"].str()
            else -> ""
        }
        val poster = o["poster_path"].str().ifBlank { null }?.let { "$img$it" }
        return Media(
            id = "${type.slug}-$id",
            type = type,
            title = title,
            year = date.take(4).toIntOrNull() ?: 0,
            overview = o["overview"].str(),
            genres = emptyList(), // filled by details()
            rating = o["vote_average"].dbl(),
            color1 = "#1f6feb", color2 = "#8250df",
            posterUrl = poster,
            externalId = id.toString()
        )
    }
}

// ---------------------------------------------------------------- Open Library (audiobooks)

class OpenLibraryClient(private val http: HttpTransport = realHttp) {
    private val base = "https://openlibrary.org"

    fun search(query: String): List<Media> {
        val el = httpGetJson("$base/search.json?q=${qenc(query)}&limit=30&fields=key,title,author_name,first_publish_year,cover_i,subject,ratings_average", emptyMap(), http)
        return el.obj()["docs"].arr().mapNotNull { doc ->
            val o = doc.obj()
            val key = o["key"].str()
            val title = o["title"].str()
            if (key.isBlank() || title.isBlank()) return@mapNotNull null
            val authors = o["author_name"].arr().map { it.str() }
            val cover = o["cover_i"].int(-1).takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" }
            val subjects = o["subject"].arr().map { it.str() }.take(4)
            Media(
                id = "book-${key.removePrefix("/works/")}",
                type = MediaType.AUDIOBOOKS,
                title = title,
                year = o["first_publish_year"].int(0),
                overview = "Audiobook: $title" + (if (authors.isNotEmpty()) " by ${authors.joinToString(", ")}" else "") +
                    ". Magnets are searched live on AudioBook Bay via Prowlarr.",
                genres = subjects,
                rating = o["ratings_average"].dbl(),
                color1 = "#953800", color2 = "#e16f24",
                author = authors.firstOrNull(),
                narrator = null,
                posterUrl = cover,
                externalId = key
            )
        }
    }

    /** Default browse list for the audiobooks tab when the search box is empty. */
    fun browse(): List<Media> = search("bestselling audiobooks")
}

// ---------------------------------------------------------------- Prowlarr (magnets)

data class RawTorrent(
    val title: String,
    val indexer: String,
    val indexerId: Int,
    val seeders: Int,
    val leechers: Int,
    val size: Long,
    val downloadUrl: String,
    val publishDate: String
)

class ProwlarrClient(private val baseUrl: String, private val apiKey: String, private val http: HttpTransport = realHttp) {
    private val headers = mapOf("X-Api-Key" to apiKey)
    private fun url(path: String, params: String) =
        "$baseUrl$path" + (if (params.isBlank()) "" else "?$params")
    private fun api(path: String, params: String = ""): JsonElement =
        httpGetJsonRetrying(url(path, params), headers, http)

    fun indexerIdByName(fragment: String): Int? =
        runCatching {
            api("/api/v1/indexer").arr().firstOrNull {
                it.obj()["name"].str().contains(fragment, ignoreCase = true)
            }?.obj()?.get("id")?.int()
        }.getOrNull()

    fun search(query: String, indexerIds: String = ""): List<RawTorrent> {
        val params = "query=${qenc(query)}&type=search" + if (indexerIds.isNotBlank()) "&indexerIds=$indexerIds" else ""
        val el = api("/api/v1/search", params)
        val list = el as? JsonArray ?: el.obj()["results"].arr()
        return list.mapNotNull { r ->
            val o = r.obj()
            val dl = o["downloadUrl"].str()
            if (dl.isBlank()) return@mapNotNull null
            RawTorrent(
                title = o["title"].str(),
                indexer = o["indexer"].str(),
                indexerId = o["indexerId"].int(),
                seeders = o["seeders"].int(0),
                leechers = o["leechers"].int(0),
                size = o["size"].run { (this as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L },
                downloadUrl = dl,
                publishDate = o["publishDate"].str()
            )
        }
    }
}

// ---------------------------------------------------------------- Sonarr / Radarr (shared base)

/**
 * Shared plumbing for the *arr apps: URL building, authenticated GET with
 * retry, POST with JSON, and the two lookups the write path depends on.
 * The write-path lookups THROW on failure — a silent "/tv"/"1" default would
 * produce a wrong write with no error (Ousterhout: don't mask exceptions).
 */
private abstract class ArrClient(
    protected val baseUrl: String,
    apiKey: String,
    protected val http: HttpTransport,
    private val serviceName: String
) {
    protected val headers = mapOf("X-Api-Key" to apiKey)

    fun url(path: String, params: String) =
        "$baseUrl$path" + (if (params.isBlank()) "" else "?$params")

    fun api(path: String, params: String = ""): JsonElement =
        httpGetJsonRetrying(url(path, params), headers, http)

    fun post(path: String, body: String): JsonElement {
        val resp = http("POST", url(path, ""), headers + ("Content-Type" to "application/json"), body, true)
        if (!resp.ok) throw ApiException("$serviceName HTTP ${resp.status}: ${resp.body.take(200)}", resp.status)
        return jsonX.parseToJsonElement(resp.body)
    }

    fun rootFolder(): String =
        api("/api/v3/rootfolder").arr().firstOrNull()?.obj()?.get("path").str()
            .takeIf { it.isNotBlank() }
            ?: throw ApiException("$serviceName: no root folder configured")

    fun qualityProfileId(): Int =
        api("/api/v3/qualityprofile").arr().firstOrNull()?.obj()?.get("id").int(-1)
            .takeIf { it > 0 }
            ?: throw ApiException("$serviceName: no quality profile configured")
}

/** Sonarr series library state. */
data class SeriesState(val sonarrId: Int, val tvdbId: Int, val hasFiles: Boolean)

/** Radarr movie library state. */
data class MovieState(val radarrId: Int, val tmdbId: String, val hasFile: Boolean)

class SonarrClient(baseUrl: String, apiKey: String, http: HttpTransport = realHttp) :
    ArrClient(baseUrl, apiKey, http, "Sonarr") {

    /** Look up a series by TMDB id -> returns tvdbId + title. */
    fun lookupTmdb(tmdbId: String): Pair<Int, String>? {
        val el = api("/api/v3/series/lookup", "term=tmdb:$tmdbId")
        val first = el.arr().firstOrNull()?.obj() ?: return null
        val tvdb = first["tvdbId"].int(-1)
        if (tvdb <= 0) return null
        return tvdb to first["title"].str()
    }

    /** Add a series. Returns the new Sonarr series id. */
    fun addSeries(tmdbId: String, title: String): Int {
        val (tvdbId, seriesTitle) = lookupTmdb(tmdbId) ?: throw ApiException("Sonarr: no tvdb mapping for tmdb:$tmdbId")
        val body = buildJsonObject {
            put("tvdbId", JsonPrimitive(tvdbId))
            put("title", JsonPrimitive(seriesTitle.ifBlank { title }))
            put("qualityProfileId", JsonPrimitive(qualityProfileId()))
            put("rootFolderPath", JsonPrimitive(rootFolder()))
            put("monitored", JsonPrimitive(true))
            put("seasonFolder", JsonPrimitive(true))
            put("seriesType", JsonPrimitive("standard"))
            put("addOptions", buildJsonObject {
                put("searchForMissingEpisodes", JsonPrimitive(true))
                put("searchForCutoffUnmetEpisodes", JsonPrimitive(false))
            })
        }
        return post("/api/v3/series", jsonX.encodeToString(JsonObject.serializer(), body)).obj()["id"].int()
    }

    fun seriesStates(): List<SeriesState> =
        runCatching {
            api("/api/v3/series").arr().map { s ->
                val o = s.obj()
                val stats = o["statistics"].obj()
                SeriesState(o["id"].int(), o["tvdbId"].int(), stats["episodeFileCount"].int(0) > 0)
            }
        }.getOrElse { emptyList() }

    fun queueSeriesIds(): Set<Int> =
        runCatching {
            api("/api/v3/queue", "pageSize=200").obj()["records"].arr().mapNotNull { it.obj()["seriesId"].int(-1).takeIf { id -> id > 0 } }.toSet()
        }.getOrElse { emptySet() }
}

class RadarrClient(baseUrl: String, apiKey: String, http: HttpTransport = realHttp) :
    ArrClient(baseUrl, apiKey, http, "Radarr") {

    fun lookupTmdb(tmdbId: String): JsonObject? {
        val el = api("/api/v3/movie/lookup", "term=tmdb:$tmdbId")
        return el.arr().firstOrNull()?.obj()
    }

    /** Add a movie. Returns the new Radarr movie id. */
    fun addMovie(tmdbId: String): Int {
        val tmdb = tmdbId.toIntOrNull() ?: throw ApiException("Radarr: bad tmdb id '$tmdbId'")
        val lookup = lookupTmdb(tmdbId) ?: throw ApiException("Radarr: no mapping for tmdb:$tmdbId")
        val body = buildJsonObject {
            put("tmdbId", JsonPrimitive(tmdb))
            put("title", JsonPrimitive(lookup["title"].str()))
            put("year", JsonPrimitive(lookup["year"].int()))
            put("qualityProfileId", JsonPrimitive(qualityProfileId()))
            put("rootFolderPath", JsonPrimitive(rootFolder()))
            put("monitored", JsonPrimitive(true))
            put("addOptions", buildJsonObject { put("search", JsonPrimitive(true)) })
        }
        return post("/api/v3/movie", jsonX.encodeToString(JsonObject.serializer(), body)).obj()["id"].int()
    }

    fun movieStates(): List<MovieState> =
        runCatching {
            api("/api/v3/movie").arr().map { m ->
                val o = m.obj()
                MovieState(o["id"].int(), o["tmdbId"].str(), o["hasFile"].str() == "true")
            }
        }.getOrElse { emptyList() }

    fun queueMovieIds(): Set<Int> =
        runCatching {
            api("/api/v3/queue", "pageSize=200").obj()["records"].arr().mapNotNull { it.obj()["movieId"].int(-1).takeIf { id -> id > 0 } }.toSet()
        }.getOrElse { emptySet() }
}

// ---------------------------------------------------------------- Jellyfin

class JellyfinClient(private val baseUrl: String, private val apiKey: String, private val http: HttpTransport = realHttp) {
    // Jellyfin 10.9+ expects the API key in the Authorization header, not X-Emby-Token.
    private val headers = mapOf(
        "Authorization" to "MediaBrowser Client=\"app-a\", Device=\"phone\", DeviceId=\"appa-app\", Version=\"3.0\", Token=\"$apiKey\"",
        "Accept" to "application/json"
    )

    private fun get(path: String, params: String = ""): JsonElement =
        httpGetJson("$baseUrl$path?$params", headers, http)

    fun ping(): Boolean = runCatching {
        val resp = http("GET", "$baseUrl/System/Info", headers, null, true)
        resp.ok
    }.getOrDefault(false)

    /** All TMDB ids present in the Movies library. */
    fun movieTmdbIds(): Set<String> = itemTmdbIds("Movie")

    /** All TMDB ids present in the Shows library. */
    fun seriesTmdbIds(): Set<String> = itemTmdbIds("Series")

    private fun itemTmdbIds(type: String): Set<String> {
        val out = mutableSetOf<String>()
        var start = 0
        val page = 200
        while (start < MAX_LIBRARY_ITEMS) { // bounded: never loop forever on a weird server
            val el = get("/Items", "IncludeItemTypes=$type&Recursive=true&Fields=ProviderIds&StartIndex=$start&Limit=$page")
            val items = el.obj()["Items"].arr()
            if (items.isEmpty()) break
            for (i in items) {
                val tmdb = i.obj()["ProviderIds"].obj()["Tmdb"].str()
                if (tmdb.isNotBlank()) out += tmdb
            }
            start += page
            if (items.size < page) break
        }
        return out
    }

    private companion object { const val MAX_LIBRARY_ITEMS = 5000 }
}
