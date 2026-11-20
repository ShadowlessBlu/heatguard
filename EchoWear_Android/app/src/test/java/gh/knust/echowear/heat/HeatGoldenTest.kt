package gh.knust.echowear.heat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Replays HeatRisk/golden/heat_golden.csv (exported by `python -m sim.export_golden`)
 * through the Kotlin engine. Every minute must give the same level, action,
 * reasons and numbers as the Python reference.
 */
class HeatGoldenTest {

    private data class Row(val f: Map<String, String>) {
        fun s(k: String) = f.getValue(k)
        fun d(k: String) = s(k).takeIf { it.isNotEmpty() }?.toDouble()
        fun i(k: String) = s(k).takeIf { it.isNotEmpty() }?.toInt()
        fun b(k: String) = s(k) == "1"
    }

    private fun load(): List<Row> {
        val text = javaClass.classLoader!!.getResourceAsStream("heat_golden.csv")
            ?.bufferedReader()?.readText()
            ?: error("heat_golden.csv missing from src/test/resources - run python -m sim.export_golden")
        val lines = text.lines().filter { it.isNotBlank() }
        val header = lines.first().split(",")
        return lines.drop(1).map { line ->
            val cells = line.split(",")
            check(cells.size == header.size) { "bad row: $line" }
            Row(header.zip(cells).toMap())
        }
    }

    private fun profileOf(r: Row) = WorkerProfile(
        age = r.i("age"),
        restingHr = r.d("resting_hr"),
        daysAcclimatized = r.d("days_acclimatized")!!,
        clothing = r.s("clothing"),
        hood = r.b("hood"),
        declaredWork = WorkClass.valueOf(r.s("declared_work")),
    )

    private fun close(expected: Double?, actual: Double?, what: String, ctx: String) {
        if (expected == null) { assertNull("$what should be null at $ctx", actual); return }
        if (actual == null) fail("$what is null, expected $expected at $ctx")
        assertTrue("$what $actual != $expected at $ctx", abs(expected - actual!!) <= 1e-9)
    }

    @Test
    fun kotlinMatchesPythonEveryMinute() {
        val rows = load()
        assertTrue("golden file looks truncated", rows.size > 1000)

        var scenario = ""
        var engine = HeatRiskEngine()
        var checked = 0
        for (r in rows) {
            if (r.s("scenario") != scenario) {
                scenario = r.s("scenario")
                engine = HeatRiskEngine(profileOf(r))
            }
            if (r.b("ack_before")) engine.acknowledge()
            val a = engine.update(
                MinuteInput(
                    tMin = r.i("t_min")!!, hr = r.d("hr"), hrQuality = r.d("hr_quality")!!,
                    enmoMg = r.d("enmo_mg"), taC = r.d("ta_c"), rhPct = r.d("rh_pct"),
                    tgC = r.d("tg_c"), wbgtC = r.d("wbgt_c"), fallEvent = r.b("fall_event"),
                )
            )
            val ctx = "$scenario t=${r.s("t_min")}"
            assertEquals("level at $ctx", r.s("level"), a.level.name)
            assertEquals("action at $ctx", r.s("action"), a.action)
            assertEquals("reasons at $ctx", r.s("reasons"), a.reasons.joinToString(";"))
            assertEquals("work minutes at $ctx", r.i("work_min_per_hour"), a.workMinPerHour)
            assertEquals("work class at $ctx", r.s("work_class"), a.workClass.name)
            close(r.d("ct_est"), a.ctEst, "ct_est", ctx)
            close(r.d("ct_sd"), a.ctSd, "ct_sd", ctx)
            close(r.d("psi"), a.psi, "psi", ctx)
            close(r.d("out_hr"), a.hr, "hr", ctx)
            close(r.d("wbgt_eff"), a.wbgtEff, "wbgt_eff", ctx)
            close(r.d("wbgt_limit"), a.wbgtLimit, "wbgt_limit", ctx)
            checked++
        }
        assertEquals(rows.size, checked)
    }

    @Test
    fun everyKeyHasText() {
        val rows = load()
        val reasons = rows.flatMap { it.s("reasons").split(";") }.filter { it.isNotEmpty() }.toSet()
        val actions = rows.map { it.s("action") }.toSet()
        assertTrue("untranslated reasons: ${reasons - HeatText.reasonKeys}",
                   HeatText.reasonKeys.containsAll(reasons))
        assertTrue("untranslated actions: ${actions - HeatText.actionKeys}",
                   HeatText.actionKeys.containsAll(actions))
    }

    @Test
    fun publishedReferencePoints() {
        assertEquals(83.1, ECTemp.expectedHr(37.1), 0.2)
        assertEquals(13.7, HeatEnvironment.wetBulbStull(20.0, 50.0), 0.1)
        assertEquals(28.2, Workload.tlv(300.0), 0.1)
        assertEquals(25.0, Workload.actionLimit(300.0), 0.1)
    }

    @Test
    fun supervisorViewHasNoVitals() {
        val a = HeatRiskEngine().update(MinuteInput(tMin = 0, hr = 90.0, taC = 30.0, rhPct = 40.0))
        assertEquals(setOf("t_min", "level", "action", "work_min_per_hour"), a.supervisorView().keys)
    }
}
