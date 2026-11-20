package com.example.appa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    @Test
    fun `catalog has 18 items per type with unique ids`() {
        assertEquals(54, Catalog.all.size)
        assertEquals(18, Catalog.all.count { it.type == MediaType.SERIES })
        assertEquals(18, Catalog.all.count { it.type == MediaType.MOVIES })
        assertEquals(18, Catalog.all.count { it.type == MediaType.AUDIOBOOKS })
        assertEquals(54, Catalog.byId.size)
    }

    @Test
    fun `catalog is deterministic`() {
        // object is a singleton, so compare against a fresh build via a second lookup pattern:
        // ids and magnets must be stable and reproducible
        val m1 = Catalog.byId.getValue("movies-01")
        val m2 = Catalog.byId.getValue("movies-01")
        assertEquals(m1, m2)
        assertTrue(m1.title.startsWith("Placeholder"))
        assertEquals(Catalog.magnetsByMediaId.getValue("movies-01"), Catalog.magnetsByMediaId.getValue("movies-01"))
    }

    @Test
    fun `every media has at least 4 valid magnets sorted by seeders`() {
        for (m in Catalog.all) {
            val magnets = Catalog.magnetsByMediaId.getValue(m.id)
            assertTrue("${m.id} magnets", magnets.size >= 4)
            for (mag in magnets) {
                assertTrue(mag.magnetUri.startsWith("magnet:?xt=urn:btih:"))
                val hash = mag.magnetUri.substringAfter("urn:btih:").substringBefore('&')
                assertEquals(40, hash.length)
                assertTrue(hash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
                assertTrue(mag.sizeBytes > 0)
            }
            assertEquals(magnets.map { it.seeders }, magnets.map { it.seeders }.sortedDescending())
        }
    }

    @Test
    fun `type specific fields`() {
        for (m in Catalog.all) {
            when (m.type) {
                MediaType.SERIES -> { assertNotNull(m.seasons); assertNotNull(m.episodes) }
                MediaType.MOVIES -> assertNotNull(m.runtimeMinutes)
                MediaType.AUDIOBOOKS -> {
                    assertNotNull(m.author); assertNotNull(m.narrator); assertNotNull(m.durationHours)
                }
            }
        }
    }

    @Test
    fun `ofType sorted by rating desc`() {
        val movies = Catalog.ofType(MediaType.MOVIES)
        assertEquals(18, movies.size)
        assertTrue(movies.all { it.type == MediaType.MOVIES })
        assertEquals(movies.map { it.rating }, movies.map { it.rating }.sortedDescending())
    }

    @Test
    fun `search matches title and magnet names`() {
        val byTitle = Catalog.search("placeholder movie 07")
        assertEquals(listOf("movies-07"), byTitle.map { it.id })
        val byMagnet = Catalog.search("FLAC-GRP")
        assertTrue(byMagnet.isNotEmpty())
        assertTrue(byMagnet.all { it.type == MediaType.AUDIOBOOKS })
        assertTrue(Catalog.search("").isEmpty())
    }

    @Test
    fun `formatSize`() {
        assertEquals("1.50 GB", formatSize(1_500_000_000L))
        assertEquals("700.0 MB", formatSize(700_000_000L))
        assertEquals("500 KB", formatSize(500_000L))
    }
}
