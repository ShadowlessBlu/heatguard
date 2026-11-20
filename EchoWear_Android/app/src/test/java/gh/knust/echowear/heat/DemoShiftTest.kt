package gh.knust.echowear.heat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DemoShiftTest {

    private val shifts = DemoShifts.parse(File("src/main/assets/${DemoShifts.ASSET}").readText())

    @Test
    fun threeShiftsOfAFullMorning() {
        assertEquals(listOf("A", "B", "C"), shifts.map { it.key })
        shifts.forEach { assertEquals(390, it.minutes.size) }
    }

    /** The app's replay must show exactly what the Python demo (and the docs) report. */
    @Test
    fun replayMatchesPythonEveryMinute() {
        for (s in shifts) {
            val r = DemoShifts.Replay(s)
            while (!r.done) {
                val (m, a) = r.step()!!
                assertEquals("${s.key} ${m.clock}", m.expectedLevel, a.level)
            }
        }
    }

    @Test
    fun ignoringWarningsReachesRedAt0828AndCollapses() {
        val a = shifts.first { it.key == "A" }
        val r = DemoShifts.Replay(a)
        var firstRed: String? = null
        while (!r.done) {
            val (m, x) = r.step()!!
            if (firstRed == null && x.level == HeatLevel.RED) firstRed = m.clock
        }
        assertEquals("08:28", firstRed)
        assertTrue(a.minutes.any { it.input.fallEvent })
    }
}
