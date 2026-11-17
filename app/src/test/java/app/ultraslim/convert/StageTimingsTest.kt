package app.ultraslim.convert

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StageTimingsTest {
    @Test fun repeatedStagesAccumulateWhileTotalIncludesUnmeasuredWork() = runBlocking {
        var now = 0L
        val timings = StageTimings { now }
        timings.measure("save") { now += 3_000_000 }
        timings.measure("save") { now += 2_000_000 }
        now += 7_000_000
        val snapshot = timings.snapshot()
        assertEquals(12.0, snapshot.totalMs, 0.0)
        assertEquals(5.0, snapshot.stagesMs.getValue("save"), 0.0)
        timings.measure("probe") { now += 1_000_000 }
        assertFalse(snapshot.stagesMs.containsKey("probe"))
    }

    @Test fun failureAndCancellationAreMeasuredAndPropagatedUnchanged() = runBlocking {
        var now = 0L
        val timings = StageTimings { now }
        for (failure in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
            try {
                timings.measure("encode") { now += 2_000_000; throw failure }
                fail("Expected original exception")
            } catch (actual: Exception) { assertSame(failure, actual) }
        }
        assertEquals(4.0, timings.snapshot().stagesMs.getValue("encode"), 0.0)
    }
}
