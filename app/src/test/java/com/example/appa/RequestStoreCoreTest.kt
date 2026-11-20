package com.example.appa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestStoreCoreTest {

    private fun memStore(): Pair<RequestStoreCore, MutableList<String>> {
        val saved = mutableListOf<String>()
        var stored: String? = null
        val store = RequestStoreCore(
            load = { stored },
            save = { stored = it; saved.add(it) }
        )
        return store to saved
    }

    @Test
    fun `create duplicate delete update`() {
        val (store, _) = memStore()
        val now = 1_000_000L
        val r = store.create("movies-03", now)
        assertNotNull(r)
        assertEquals("movies-03", r!!.mediaId)
        assertEquals(RequestStatus.PENDING, r.status)

        assertNull("duplicate must be rejected", store.create("movies-03", now))
        assertEquals(1, store.count())

        val r2 = store.create("audiobooks-07", now)
        assertNotNull(r2)
        assertEquals(2, store.count())

        val updated = store.updateStatus(r.id, RequestStatus.PROCESSING, now + 5000)
        assertEquals(RequestStatus.PROCESSING, updated!!.status)
        assertEquals(RequestStatus.PROCESSING, store.byMediaId("movies-03")!!.status)

        assertTrue(store.delete(r.id))
        assertFalse(store.delete(r.id))
        assertNull(store.byMediaId("movies-03"))
        assertEquals(1, store.count())
    }

    @Test
    fun `tick advances pending to processing to available`() {
        val (store, _) = memStore()
        val t0 = 1_000_000L
        val r = store.create("series-02", t0)!!

        assertTrue("too early for pending", store.tick(t0 + 2_000).isEmpty())
        assertEquals(RequestStatus.PENDING, store.byMediaId("series-02")!!.status)

        val changed = store.tick(t0 + 4_000)
        assertEquals(listOf(RequestStatus.PROCESSING), changed.map { it.status })

        assertTrue("too early for processing", store.tick(t0 + 4_000 + 9_000).isEmpty())
        val changed2 = store.tick(t0 + 4_000 + 10_000)
        assertEquals(listOf(RequestStatus.AVAILABLE), changed2.map { it.status })

        assertTrue("available is terminal", store.tick(t0 + 999_999_999).isEmpty())
    }

    @Test
    fun `persists and reloads`() {
        var stored: String? = null
        val store1 = RequestStoreCore(load = { stored }, save = { stored = it })
        val r = store1.create("movies-05", 123L)!!
        store1.updateStatus(r.id, RequestStatus.AVAILABLE, 200L)

        // new instance over the same backing string
        val store2 = RequestStoreCore(load = { stored }, save = { stored = it })
        val loaded = store2.byMediaId("movies-05")
        assertNotNull(loaded)
        assertEquals(RequestStatus.AVAILABLE, loaded!!.status)
        assertEquals(123L, loaded.requestedAt)
        assertEquals(200L, loaded.statusUpdatedAt)
        assertEquals(r.id, loaded.id)
    }

    @Test
    fun `corrupt lines are skipped`() {
        val store = RequestStoreCore(load = { "garbage line\n\nok-id\tmovies-01\t5\tPENDING\t5" }, save = {})
        assertEquals(1, store.count())
        assertEquals("movies-01", store.all()[0].mediaId)
    }

    @Test
    fun `status rules`() {
        assertNull(StatusRules.next(RequestStatus.PENDING, 3, 4, 10))
        assertEquals(RequestStatus.PROCESSING, StatusRules.next(RequestStatus.PENDING, 4, 4, 10))
        assertNull(StatusRules.next(RequestStatus.PROCESSING, 9, 4, 10))
        assertEquals(RequestStatus.AVAILABLE, StatusRules.next(RequestStatus.PROCESSING, 10, 4, 10))
        assertNull(StatusRules.next(RequestStatus.AVAILABLE, 10_000, 4, 10))
    }

    @Test
    fun `persists as JSON array and reloads external + snapshot`() {
        var stored: String? = null
        val s1 = RequestStoreCore(load = { stored }, save = { stored = it })
        s1.create("movies-01", 5L, external = "radarr:9", snapshot = """{"title":"Dune","type":"movies","year":2021,"rating":8.1,"posterUrl":""}""")
        assertTrue("expected JSON array, got: $stored", stored!!.startsWith("["))
        val s2 = RequestStoreCore(load = { stored }, save = {})
        val r = s2.byMediaId("movies-01")
        assertNotNull(r)
        assertEquals("radarr:9", r!!.external)
        assertEquals("Dune", MediaSnapshot.fromJson(r.snapshot)!!.title)
    }

    @Test
    fun `legacy TSV lines still load`() {
        val store = RequestStoreCore(load = { "old-id\tseries-02\t7\tPROCESSING\t9\tsonarr:3" }, save = {})
        val r = store.byMediaId("series-02")
        assertNotNull(r)
        assertEquals(RequestStatus.PROCESSING, r!!.status)
        assertEquals("sonarr:3", r.external)
    }
}
