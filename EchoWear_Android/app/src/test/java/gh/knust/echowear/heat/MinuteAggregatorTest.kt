package gh.knust.echowear.heat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinuteAggregatorTest {

    private fun still(bpm: Double) = HeatSample(bpm, 0, true, 0.0, 0.0, 1.0)

    @Test
    fun stillMinuteGivesMedianAndFullQuality() {
        val m = MinuteAggregator()
        listOf(80.0, 82.0, 200.0, 81.0).forEach { m.add(still(it)) }
        val x = m.drain(3, null)
        assertEquals(3, x.tMin)
        assertEquals(81.5, x.hr!!, 1e-9)          // median resists the 200 spike
        assertEquals(1.0, x.hrQuality, 1e-9)
        assertEquals(0.0, x.enmoMg!!, 1e-9)
    }

    @Test
    fun heavyMotionMakesHeartRateNotMeasured() {
        val m = MinuteAggregator()
        repeat(120) { m.add(HeatSample(150.0, 2, true, 0.3, 0.3, 1.4)) }
        val x = m.drain(0, null)
        assertNull(x.hr)
        assertEquals(0.0, x.hrQuality, 1e-9)
        assertTrue(x.enmoMg!! > 400)               // but the work is still seen
    }

    @Test
    fun offWristCountsAgainstQualityAndMotion() {
        val m = MinuteAggregator()
        repeat(60) { m.add(still(80.0)) }
        repeat(60) { m.add(HeatSample(0.0, 0, false, 0.0, 0.0, 1.0)) }
        val x = m.drain(0, null)
        assertEquals(0.5, x.hrQuality, 1e-9)
        assertEquals(80.0, x.hr!!, 1e-9)
    }

    @Test
    fun emptyMinuteIsNotMeasuredAndResets() {
        val m = MinuteAggregator()
        m.markFall()
        val first = m.drain(0, SiteReading(taC = 35.0, rhPct = 40.0, source = SiteSource.MANUAL))
        assertTrue(first.fallEvent)
        assertNull(first.hr)
        assertNull(first.enmoMg)
        assertEquals(35.0, first.taC!!, 1e-9)
        assertFalse(m.drain(1, null).fallEvent)
    }

    @Test
    fun monitorDoesNotTickBeforeFirstReading() {
        val h = HeatMonitor(WorkerProfile())
        assertNull(h.tick(null))
        h.onSample(still(75.0), now = 1_000L)
        val a = h.tick(null)!!
        assertEquals(0, a.tMin)
        assertTrue("env_not_measured" in a.reasons)
    }

    @Test
    fun longGapStartsANewShift() {
        val h = HeatMonitor(WorkerProfile())
        h.onSample(still(75.0), now = 1_000L)
        h.tick(null); h.tick(null)
        h.onSample(still(75.0), now = 1_000L + HeatMonitor.NEW_SHIFT_GAP_MS + 1)
        assertEquals(0, h.tick(null)!!.tMin)
    }
}
