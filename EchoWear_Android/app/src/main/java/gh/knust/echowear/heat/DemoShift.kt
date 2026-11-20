package gh.knust.echowear.heat

import android.content.Context

/**
 * Simulated shifts for the "Play demo shift" button (assets/heat_demo.csv,
 * exported by `python -m sim.export_demo`).
 *
 * The inputs are replayed through a fresh [HeatRiskEngine], so every level on
 * screen is computed by the real engine. `ctTrue` is the toy model's core
 * temperature, shown only so a viewer can compare it with the estimate.
 * `expectedLevel` is what the Python engine produced, for the unit test.
 */
data class DemoMinute(
    val clock: String,
    val input: MinuteInput,
    val working: Boolean,
    val ctTrue: Double,
    val expectedLevel: HeatLevel,
)

data class DemoShift(
    val key: String,
    val title: String,
    val follows: Boolean,
    val profile: WorkerProfile,
    val minutes: List<DemoMinute>,
)

object DemoShifts {
    const val ASSET = "heat_demo.csv"

    fun load(context: Context): List<DemoShift> =
        context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }

    fun parse(text: String): List<DemoShift> {
        val lines = text.lines().filter { it.isNotBlank() }
        val header = lines.first().split(",")
        val rows = lines.drop(1).map { header.zip(it.split(",")).toMap() }
        fun d(r: Map<String, String>, k: String) = r.getValue(k).takeIf { it.isNotEmpty() }?.toDouble()
        return rows.groupBy { it.getValue("scenario") }.map { (key, rs) ->
            val first = rs.first()
            DemoShift(
                key = key,
                title = first.getValue("title"),
                follows = first.getValue("follows") == "1",
                profile = WorkerProfile(
                    age = first.getValue("age").toIntOrNull(),
                    daysAcclimatized = d(first, "days_acclimatized") ?: 0.0,
                    declaredWork = WorkClass.MODERATE,
                ),
                minutes = rs.map { r ->
                    DemoMinute(
                        clock = r.getValue("clock"),
                        input = MinuteInput(
                            tMin = r.getValue("t_min").toInt(),
                            hr = d(r, "hr"), enmoMg = d(r, "enmo_mg"),
                            taC = d(r, "ta_c"), rhPct = d(r, "rh_pct"),
                            fallEvent = r.getValue("fall_event") == "1",
                        ),
                        working = r.getValue("working") == "1",
                        ctTrue = d(r, "ct_true")!!,
                        expectedLevel = HeatLevel.valueOf(r.getValue("expected_level")),
                    )
                },
            )
        }
    }

    /** Runs a shift through a fresh engine; one call per simulated minute. */
    class Replay(val shift: DemoShift) {
        private val engine = HeatRiskEngine(shift.profile)
        var index = 0
            private set
        val done: Boolean get() = index >= shift.minutes.size

        fun step(): Pair<DemoMinute, HeatAssessment>? {
            if (done) return null
            val m = shift.minutes[index++]
            return m to engine.update(m.input)
        }
    }
}
