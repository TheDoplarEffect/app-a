package com.example.appa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the live-mode core: parsing of TMDB / Open Library / Prowlarr /
 * Sonarr / Radarr / Jellyfin responses via an injected fake [HttpTransport],
 * magnet resolution, config round-trip and the live status mapping.
 */
class LiveParsingTest {

    /** Routes requests by URL substring to canned responses; records all URLs hit. */
    private fun fakeHttp(
        routes: Map<String, Resp>,
        onRequest: ((String, String, String?) -> Unit)? = null
    ): HttpTransport = { method, url, headers, body, followRedirects ->
        onRequest?.invoke(method, url, body)
        val route = routes.entries.firstOrNull { url.contains(it.key) }
            ?: throw AssertionError("no fake route for $url")
        route.value
    }

    private fun resp(body: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
        Resp(status, body, body.toByteArray(), headers)

    private val tmdbMovieSearch = """
        {"page":1,"results":[
          {"id":680,"title":"Pulp Fiction","release_date":"1994-10-14","poster_path":"/d5iIlFn5s0ImszYzBPb8JPIfbXD.jpg","vote_average":8.5,"overview":"A hitman..."},
          {"id":550,"title":"Fight Club","release_date":"1999-10-15","poster_path":null,"vote_average":8.4,"overview":"An insomniac..."}
        ]}
    """.trimIndent()

    private val tmdbTvSearch = """
        {"results":[
          {"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20","poster_path":"/ggFHVNu6YYI5L9pCfOacjizRGt.jpg","vote_average":8.9,"overview":"A chemistry teacher..."}
        ]}
    """.trimIndent()

    private val tmdbMovieDetails = """
        {"id":680,"overview":"Two hitmen.","genres":[{"id":80,"name":"Crime"},{"id":53,"name":"Thriller"}],
         "vote_average":8.5,"runtime":154,"release_date":"1994-10-14"}
    """.trimIndent()

    // ---------------------------------------------------------------- TMDB

    @Test
    fun `tmdb movie search parses id title year poster externalId`() {
        val http = fakeHttp(mapOf("/search/movie" to resp(tmdbMovieSearch)))
        val tmdb = TmdbClient("token", http)
        val out = tmdb.search("pulp", MediaType.MOVIES)
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("movies-680", first.id)
        assertEquals(MediaType.MOVIES, first.type)
        assertEquals("Pulp Fiction", first.title)
        assertEquals(1994, first.year)
        assertEquals(8.5, first.rating, 0.001)
        assertEquals("https://image.tmdb.org/t/p/w500/d5iIlFn5s0ImszYzBPb8JPIfbXD.jpg", first.posterUrl)
        assertEquals("680", first.externalId)
        assertNull("null poster_path stays null", out[1].posterUrl)
    }

    @Test
    fun `tmdb tv search uses name and first_air_date`() {
        val http = fakeHttp(mapOf("/search/tv" to resp(tmdbTvSearch)))
        val out = TmdbClient("t", http).search("breaking", MediaType.SERIES)
        assertEquals(1, out.size)
        assertEquals("series-1396", out[0].id)
        assertEquals("Breaking Bad", out[0].title)
        assertEquals(2008, out[0].year)
        assertEquals("1396", out[0].externalId)
    }

    @Test
    fun `tmdb details fills genres runtime and keeps fallback`() {
        val http = fakeHttp(mapOf("/movie/680" to resp(tmdbMovieDetails)))
        val base = Media(
            id = "movies-680", type = MediaType.MOVIES, title = "Pulp Fiction", year = 1994,
            overview = "", genres = emptyList(), rating = 0.0, color1 = "#000", color2 = "#111",
            externalId = "680"
        )
        val d = TmdbClient("t", http).details(base)
        assertEquals(listOf("Crime", "Thriller"), d.genres)
        assertEquals(154, d.runtimeMinutes)
        assertEquals("Two hitmen.", d.overview)
    }

    @Test
    fun `tmdb search on audiobooks returns empty without network`() {
        val http = fakeHttp(emptyMap())
        assertTrue(TmdbClient("t", http).search("dune", MediaType.AUDIOBOOKS).isEmpty())
        assertTrue(TmdbClient("t", http).trending(MediaType.AUDIOBOOKS).isEmpty())
    }

    // ---------------------------------------------------------------- Open Library

    private val olSearch = """
        {"numFound":2,"docs":[
          {"key":"/works/OL82563W","title":"Dune","author_name":["Frank Herbert"],"first_publish_year":1965,"cover_i":8367269,"ratings_average":4.2,"subject":["Science fiction","Epic"]},
          {"key":"/works/OL1W","title":"No cover book","first_publish_year":2001}
        ]}
    """.trimIndent()

    @Test
    fun `openlibrary search parses docs cover author`() {
        val http = fakeHttp(mapOf("openlibrary.org/search.json" to resp(olSearch)))
        val out = OpenLibraryClient(http).search("dune")
        assertEquals(2, out.size)
        val d = out[0]
        assertEquals("book-OL82563W", d.id)
        assertEquals(MediaType.AUDIOBOOKS, d.type)
        assertEquals("Dune", d.title)
        assertEquals(1965, d.year)
        assertEquals("Frank Herbert", d.author)
        assertEquals("https://covers.openlibrary.org/b/id/8367269-M.jpg", d.posterUrl)
        assertEquals(4.2, d.rating, 0.001)
        assertTrue(d.genres.contains("Science fiction"))
        assertEquals("/works/OL82563W", d.externalId)
        assertNull("missing cover_i -> null poster", out[1].posterUrl)
    }

    // ---------------------------------------------------------------- Prowlarr + magnets

    private val prowlarrSearch = """
        [
          {"title":"Dune 1965 FLAC","indexer":"AudioBook Bay","indexerId":4,"seeders":42,"leechers":3,"size":812345678,
           "downloadUrl":"http://prowlarr:9696/4/download?apikey=K&link=abc","publishDate":"2024-01-01"},
          {"title":"no download url","indexer":"1337x","indexerId":1,"seeders":1,"leechers":0,"size":1,"downloadUrl":""}
        ]
    """.trimIndent()

    @Test
    fun `prowlarr search parses top level array and skips missing downloadUrl`() {
        val http = fakeHttp(mapOf("/api/v1/search" to resp(prowlarrSearch)))
        val p = ProwlarrClient("http://prowlarr:9696", "K", http)
        val out = p.search("dune")
        assertEquals(1, out.size)
        val r = out[0]
        assertEquals("Dune 1965 FLAC", r.title)
        assertEquals("AudioBook Bay", r.indexer)
        assertEquals(4, r.indexerId)
        assertEquals(42, r.seeders)
        assertEquals(812345678L, r.size)
    }

    @Test
    fun `magnetFromDownloadUrl follows 301 to magnet location`() {
        val magnet = "magnet:?xt=urn:btih:ABC123&dn=dune"
        val http = fakeHttp(mapOf(
            "/download" to resp("", status = 301, headers = mapOf("Location" to listOf(magnet)))
        ))
        assertEquals(magnet, magnetFromDownloadUrl("http://prowlarr/4/download?link=abc", http))
    }

    @Test
    fun `magnetFromDownloadUrl rejects non-magnet redirect targets`() {
        val url = "http://prowlarr/4/download?link=abc"
        val http = fakeHttp(mapOf("/download" to resp("", status = 301, headers = mapOf("Location" to listOf("https://1337x.to/some-page.html")))))
        // an HTML redirect is NOT a magnet — fall back to the download url, never the page
        assertEquals(url, magnetFromDownloadUrl(url, http))
    }

    @Test
    fun `magnetFromDownloadUrl accepts torrent redirects`() {
        val http = fakeHttp(mapOf("/download" to resp("", status = 302, headers = mapOf("Location" to listOf("https://itorrents.org/t/123.torrent")))))
        assertEquals("https://itorrents.org/t/123.torrent", magnetFromDownloadUrl("http://p/download?link=x", http))
    }

    @Test
    fun `magnetFromDownloadUrl falls back to url when not a redirect`() {
        val url = "http://prowlarr/4/download?link=abc"
        val http = fakeHttp(mapOf("/download" to resp("binary", status = 200)))
        assertEquals(url, magnetFromDownloadUrl(url, http))
    }

    // ---------------------------------------------------------------- Sonarr / Radarr

    @Test
    fun `sonarr lookupTmdb returns tvdb id`() {
        val http = fakeHttp(mapOf("/api/v3/series/lookup" to resp("""[{"tvdbId":81189,"title":"Breaking Bad"}]""")))
        val s = SonarrClient("http://sonarr:8989", "K", http)
        assertEquals(81189 to "Breaking Bad", s.lookupTmdb("1396"))
    }

    @Test
    fun `sonarr seriesStates reads episodeFileCount`() {
        val body = """[{"id":1,"tvdbId":81189,"statistics":{"episodeFileCount":12}},{"id":2,"tvdbId":1,"statistics":{"episodeFileCount":0}}]"""
        val http = fakeHttp(mapOf("/api/v3/series" to resp(body)))
        val states = SonarrClient("http://sonarr:8989", "K", http).seriesStates()
        assertEquals(listOf(SeriesState(1, 81189, true), SeriesState(2, 1, false)), states)
    }

    @Test
    fun `radarr lookup and movieStates`() {
        val http = fakeHttp(mapOf(
            "/api/v3/movie/lookup" to resp("""[{"tmdbId":680,"title":"Pulp Fiction","year":1994}]"""),
            "/api/v3/movie" to resp("""[{"id":7,"tmdbId":"680","hasFile":true}]""")
        ))
        val r = RadarrClient("http://radarr:7878", "K", http)
        assertEquals("Pulp Fiction", r.lookupTmdb("680")!!["title"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(listOf(MovieState(7, "680", true)), r.movieStates())
    }

    @Test
    fun `sonarr addSeries posts tvdbId and returns new id`() {
        var postedBody: String? = null
        val http = fakeHttp(
            routes = mapOf(
                "/api/v3/series/lookup" to resp("""[{"tvdbId":81189,"title":"Breaking Bad"}]"""),
                "/api/v3/qualityprofile" to resp("""[{"id":1}]"""),
                "/api/v3/rootfolder" to resp("""[{"path":"/tv"}]"""),
                "/api/v3/series" to resp("""{"id":42}""")
            ),
            onRequest = { method, url, body -> if (method == "POST" && url.contains("/api/v3/series")) postedBody = body }
        )
        val id = SonarrClient("http://sonarr:8989", "K", http).addSeries("1396", "Breaking Bad")
        assertEquals(42, id)
        assertTrue(postedBody!!.contains("\"tvdbId\":81189"))
        assertTrue(postedBody!!.contains("\"monitored\":true"))
        assertTrue(postedBody!!.contains("\"searchForMissingEpisodes\":true"))
    }

    // ---------------------------------------------------------------- Jellyfin

    @Test
    fun `jellyfin paginates items and collects tmdb ids`() {
        // page 1 must be full (200 items) for pagination to continue
        val full = (1..200).joinToString(",") { """{"ProviderIds":{"Tmdb":"m$it"}}""" }
        val page1 = """{"Items":[$full,{"ProviderIds":{"Tmdb":"680"}},{"ProviderIds":{"Tmdb":"550"}},{"ProviderIds":{}}]}"""
        // second page: 1 item (< page size) -> stop
        val page2 = """{"Items":[{"ProviderIds":{"Tmdb":"13"}}]}"""
        val http = fakeHttp(mapOf(
            "StartIndex=0" to resp(page1),
            "StartIndex=200" to resp(page2)
        ))
        val j = JellyfinClient("https://jellyfin.example", "K", http)
        val ids = j.movieTmdbIds()
        assertEquals(203, ids.size)
        assertTrue(ids.containsAll(setOf("680", "550", "13", "m1", "m200")))
    }

    @Test
    fun `jellyfin ping uses MediaBrowser authorization header`() {
        var authHeader: String? = null
        val http: HttpTransport = { method, url, headers, body, followRedirects ->
            if (url.contains("/System/Info")) authHeader = headers["Authorization"]
            resp("{}", 200)
        }
        val j = JellyfinClient("https://jf", "SECRET", http)
        assertTrue(j.ping())
        assertTrue(authHeader!!.contains("MediaBrowser"))
        assertTrue(authHeader!!.contains("Token=\"SECRET\""))
    }

    // ---------------------------------------------------------------- Config + status

    @Test
    fun `config roundtrip and normalized`() {
        val cfg = AppConfig(
            tmdbToken = " tok ", sonarrUrl = "http://s:8989/", sonarrKey = "sk",
            radarrUrl = "http://r:7878/", radarrKey = "rk",
            prowlarrUrl = "http://p:9696/", prowlarrKey = "pk",
            jellyfinUrl = "https://jf/", jellyfinKey = "jk", liveMode = true
        )
        val n = cfg.normalized()
        assertEquals("http://s:8989", n.sonarrUrl)
        assertEquals("tok", n.tmdbToken)
        val back = AppConfig.fromJson(n.toJson())
        assertEquals(n, back)
        assertTrue(back.liveReady)
        assertTrue(back.hasTmdb && back.hasSonarr && back.hasRadarr && back.hasProwlarr && back.hasJellyfin)
    }

    @Test
    fun `config fromJson tolerates garbage`() {
        assertEquals(AppConfig(), AppConfig.fromJson("not json"))
        assertEquals(AppConfig(), AppConfig.fromJson("{\"tmdbToken\":{\"x\":1}}"))
    }

    @Test
    fun `config without tmdb is not live ready`() {
        val cfg = AppConfig(sonarrUrl = "http://s", sonarrKey = "k", liveMode = true)
        assertFalse(cfg.liveReady)
    }

    @Test
    fun `urls without http scheme are rejected`() {
        assertFalse(AppConfig(sonarrUrl = "192.168.178.42:8989", sonarrKey = "k").hasSonarr)
        assertTrue(AppConfig(sonarrUrl = "http://192.168.178.42:8989", sonarrKey = "k").hasSonarr)
        assertEquals("URL needs http:// or https://", AppConfig().urlError("192.168.178.42:8989"))
        assertNull(AppConfig().urlError("https://jellyfin.example"))
        assertNull(AppConfig().urlError(""))
    }

    @Test
    fun `live status truth table`() {
        assertEquals(RequestStatus.AVAILABLE, LiveStatus.compute(true, false, false))
        assertEquals(RequestStatus.AVAILABLE, LiveStatus.compute(false, true, true))
        assertEquals(RequestStatus.PROCESSING, LiveStatus.compute(false, false, true))
        assertEquals(RequestStatus.PENDING, LiveStatus.compute(false, false, false))
    }

    // ---------------------------------------------------------------- store back-compat

    @Test
    fun `request store parses legacy 5-field lines with empty external`() {
        val legacy = "req-1\tmovies-03\t1000\tPENDING\t1000"
        var stored: String? = legacy
        val store = RequestStoreCore(load = { stored }, save = { stored = it })
        val r = store.byMediaId("movies-03")
        assertEquals("req-1", r!!.id)
        assertEquals("", r.external)
        // after any mutation the line is persisted with 6 fields
        store.setExternal("req-1", "radarr:7")
        assertTrue(stored!!.contains("radarr:7"))
        assertEquals("radarr:7", store.byMediaId("movies-03")!!.external)
    }

    @Test
    fun `magnetsFor lists results lazily without resolving download urls`() {
        var downloadHits = 0
        val http: HttpTransport = { method, url, headers, body, followRedirects ->
            if (url.contains("/download")) { downloadHits++; throw AssertionError("must not resolve eagerly") }
            when {
                url.contains("/api/v1/indexer") -> resp("""[{"id":4,"name":"AudioBook Bay"}]""")
                url.contains("/api/v1/search") -> resp(prowlarrSearch)
                else -> throw AssertionError("unexpected url $url")
            }
        }
        val repo = LiveRepository(
            AppConfig(prowlarrUrl = "http://p:9696", prowlarrKey = "K", liveMode = true), http
        )
        val media = Media(
            id = "book-OL1W", type = MediaType.AUDIOBOOKS, title = "Dune", year = 1965,
            overview = "", genres = emptyList(), rating = 0.0, color1 = "#000", color2 = "#111",
            author = "Frank Herbert", externalId = "/works/OL1W"
        )
        val mags = repo.magnetsFor(media)
        assertEquals(1, mags.size)
        assertEquals("", mags[0].magnetUri) // unresolved until resolveMagnet
        assertEquals(0, downloadHits)
        // lazy resolution goes through the 301 Location
        val http2: HttpTransport = { method, url, headers, body, followRedirects ->
            resp("", 301, mapOf("Location" to listOf("magnet:?xt=urn:btih:XYZ")))
        }
        val repo2 = LiveRepository(
            AppConfig(prowlarrUrl = "http://p:9696", prowlarrKey = "K", liveMode = true), http2
        )
        assertEquals("magnet:?xt=urn:btih:XYZ", repo2.resolveMagnet(mags[0].copy(downloadUrl = "http://p/4/download?link=a")))
    }

    @Test
    fun `qenc encodes spaces and special chars`() {
        assertEquals("dune%20messiah", qenc("dune messiah"))
        assertEquals("a%26b%3Dc", qenc("a&b=c"))
    }

    @Test
    fun `Resp header lookup tolerates the null status-line key`() {
        val hm = java.util.HashMap<String, List<String>>()
        hm.put(null, listOf("HTTP/1.1 301 Moved")) // platform type: Java headerFields really has a null key
        hm.put("Location", listOf("magnet:?xt=urn:btih:X"))
        val resp = Resp(301, "", ByteArray(0), hm)
        assertEquals("magnet:?xt=urn:btih:X", resp.header("Location"))
        assertEquals("magnet:?xt=urn:btih:X", resp.header("location")) // case-insensitive
        assertNull(resp.header("Missing"))
    }

    @Test
    fun `isSafeExternalUrl rejects bad scheme, credentials, loopback, metadata`() {
        assertTrue(isSafeExternalUrl("https://93.184.216.34/t/1.torrent"))
        assertTrue(isSafeExternalUrl("http://192.168.178.42:9696/api")) // LAN host allowed
        assertFalse(isSafeExternalUrl("ftp://93.184.216.34/"))
        assertFalse(isSafeExternalUrl("http://user:pass@93.184.216.34/"))
        assertFalse(isSafeExternalUrl("http://127.0.0.1/"))
        assertFalse(isSafeExternalUrl("http://169.254.169.254/latest/meta-data"))
        assertFalse(isSafeExternalUrl("not a url"))
    }

    @Test
    fun `ExternalRef round-trips and rejects junk`() {
        assertEquals("radarr:42", ExternalRef("radarr", "42").toString())
        assertEquals(ExternalRef("sonarr", "7"), ExternalRef.parse("sonarr:7"))
        assertNull(ExternalRef.parse(""))
        assertNull(ExternalRef.parse("noColon"))
        assertNull(ExternalRef.parse(":x"))
        assertNull(ExternalRef.parse("x:"))
    }

    @Test
    fun `MediaSnapshot json round-trips to a Media`() {
        val m = Media("movies-1", MediaType.MOVIES, "Dune", 2021, "ov", listOf("Sci-Fi"), 8.1, "#111111", "#222222", runtimeMinutes = 155, posterUrl = "https://img/x.jpg")
        val snap = MediaSnapshot.of(m)
        val back = MediaSnapshot.fromJson(snap.toJson())!!.toMedia("movies-1")
        assertEquals("Dune", back.title)
        assertEquals(MediaType.MOVIES, back.type)
        assertEquals(2021, back.year)
        assertEquals("https://img/x.jpg", back.posterUrl)
        assertEquals(8.1, back.rating, 1e-9)
    }
}
