package app.ultraslim

import app.ultraslim.ui.history.OriginalUriCache
import org.junit.Assert.*
import org.junit.Test

class OriginalUriCacheTest {
    @Test fun relinkDropsOldOriginalAndRejectsLateResolution() {
        val cache = OriginalUriCache()
        val old = OriginalUriCache.Source(1, "content://old")
        val replacement = OriginalUriCache.Source(1, "content://replacement")
        val oldWork = cache.plan(listOf(old))
        val newWork = cache.plan(listOf(replacement))
        cache.complete(newWork, mapOf(newWork.single() to "content://media/new"))
        cache.complete(oldWork, mapOf(oldWork.single() to "content://media/old"))
        assertEquals(mapOf(1L to "content://media/new"), cache.snapshot())

        val third = OriginalUriCache.Source(1, "content://third")
        assertEquals(listOf(third), cache.plan(listOf(third)).map { it.source })
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test fun repeatedRefreshSharesWorkAndUnresolvableRelinkNeverKeepsOldTarget() {
        var now = 0L
        val cache = OriginalUriCache(clockMillis = { now }, negativeTtlMillis = 100L)
        val source = OriginalUriCache.Source(1, "content://source")
        val pending = cache.plan(listOf(source))
        assertTrue(cache.plan(listOf(source)).isEmpty())
        cache.complete(pending, mapOf(pending.single() to "content://media/1"))
        assertTrue(cache.plan(listOf(source)).isEmpty())
        val missing = OriginalUriCache.Source(1, "content://unavailable")
        val work = cache.plan(listOf(missing))
        cache.complete(work, emptyMap())
        assertTrue(cache.snapshot().isEmpty())
        assertTrue("Repeated list changes must reuse a recent unavailable result", cache.plan(listOf(missing)).isEmpty())
        now = 99L
        assertTrue(cache.plan(listOf(missing)).isEmpty())
        now = 100L
        assertEquals(listOf(missing), cache.plan(listOf(missing)).map { it.source })
    }

    @Test fun foregroundRefreshRetriesNegativeResultsAndRejectsOlderSameUriWork() {
        val cache = OriginalUriCache(clockMillis = { 0L })
        val source = OriginalUriCache.Source(1, "content://source")
        val old = cache.plan(listOf(source))
        cache.invalidate()
        val fresh = cache.plan(listOf(source))
        cache.complete(old, mapOf(old.single() to "content://media/stale"))
        cache.abandon(old)
        assertTrue(cache.snapshot().isEmpty())
        assertTrue("Old finally must not abandon the newer lookup", cache.plan(listOf(source)).isEmpty())
        cache.complete(fresh, emptyMap())
        assertTrue(cache.plan(listOf(source)).isEmpty())
        cache.invalidate()
        val authorized = cache.plan(listOf(source))
        cache.complete(authorized, mapOf(authorized.single() to "content://media/authorized"))
        assertEquals(mapOf(1L to "content://media/authorized"), cache.snapshot())
    }

    @Test fun changingAwayAndBackToSameUriCannotAcceptAnOlderResolution() {
        val cache = OriginalUriCache()
        val source = OriginalUriCache.Source(1, "content://source")
        val old = cache.plan(listOf(source))
        cache.plan(listOf(source.copy(uri = "content://other")))
        val fresh = cache.plan(listOf(source))
        cache.complete(old, mapOf(old.single() to "content://media/stale"))
        assertTrue(cache.snapshot().isEmpty())
        cache.complete(fresh, mapOf(fresh.single() to "content://media/current"))
        assertEquals(mapOf(1L to "content://media/current"), cache.snapshot())
    }

    @Test fun leavingAWindowRetainsSelectedSourcesAndRejectsEvictedWork() {
        val cache = OriginalUriCache()
        val selected = OriginalUriCache.Source(1, "content://selected")
        val previousWindow = OriginalUriCache.Source(2, "content://previous")
        val pending = cache.plan(listOf(selected, previousWindow))
        cache.retain(listOf(selected))
        cache.complete(pending, pending.associateWith { "content://media/${it.id}" })
        assertEquals(mapOf(1L to "content://media/1"), cache.snapshot())
        assertTrue(cache.plan(listOf(selected)).isEmpty())
        assertEquals(listOf(previousWindow), cache.plan(listOf(previousWindow)).map { it.source })
    }
}
