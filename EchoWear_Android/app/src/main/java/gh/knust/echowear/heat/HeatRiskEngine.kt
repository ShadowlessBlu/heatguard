package gh.knust.echowear.heat

/*
 * Kotlin port of HeatRisk/heatrisk/engine.py - see HeatScience.kt for the
 * rule about keeping the two in step.
 *
 * Design rules, the same as the rest of EchoWear:
 *  - Unmeasured is null, never 0, and never "safe".
 *  - Escalation is immediate; de-escalation waits `cooldownMin` minutes.
 *  - A collapse stays RED until acknowledged.
 *  - The supervisor view carries level, action and work minutes - no vitals.
 */

enum class HeatLevel { GREEN, YELLOW, ORANGE, RED }

data class WorkerProfile(
    val age: Int? = null,
    val restingHr: Double? = null,
    val daysAcclimatized: Double = 0.0,
    val clothing: String = "work_clothes",
    val hood: Boolean = false,
    val declaredWork: WorkClass = WorkClass.MODERATE,
)

data class MinuteInput(
    val tMin: Int,
    val hr: Double? = null,
    val hrQuality: Double = 1.0,
    val enmoMg: Double? = null,
    val taC: Double? = null,
    val rhPct: Double? = null,
    val tgC: Double? = null,
    val wbgtC: Double? = null,
    val fallEvent: Boolean = false,
)

data class HeatAssessment(
    val tMin: Int,
    val level: HeatLevel,
    val action: String,
    val reasons: List<String>,
    val workMinPerHour: Int?,
    val ctEst: Double?,
    val ctSd: Double?,
    val psi: Double?,
    val hr: Double?,
    val wbgtEff: Double?,
    val wbgtLimit: Double?,
    val workClass: WorkClass,
    val at: Long = System.currentTimeMillis(),
) {
    /** For the supervisor's crew screen. No vitals, by design. */
    fun supervisorView(): Map<String, Any?> = mapOf(
        "t_min" to tMin, "level" to level.name, "action" to action,
        "work_min_per_hour" to workMinPerHour,
    )

    val actionText: String get() = HeatText.action(action)
    fun explain(): List<String> = reasons.map { HeatText.reason(it) }
}

class HeatRiskEngine(
    var profile: WorkerProfile = WorkerProfile(),
    val cooldownMin: Int = 10,
    val hrMinQuality: Double = 0.5,
    val hrStaleMin: Int = 10,
    val baselineMinutes: Int = 5,
) {
    private val filter = ECTemp()
    private val ct0 = filter.ct
    private var hr0: Double? = profile.restingHr
    private val baselineHrs = mutableListOf<Double>()
    private val recentHr = ArrayDeque<Double>()
    private var lastValidHrT: Int? = null
    private var level = HeatLevel.GREEN
    private var lowerSince: Int? = null
    private val lowerWindow = mutableListOf<HeatLevel>()
    private var latched: String? = null

    val isLatched: Boolean get() = latched != null

    private fun validHr(x: MinuteInput): Double? {
        val hr = x.hr ?: return null
        if (x.hrQuality < hrMinQuality) return null
        if (hr < 30.0 || hr > 220.0) return null
        return hr
    }

    /** Someone confirmed a collapse/fall alert has been handled. */
    fun acknowledge() { latched = null }

    fun update(x: MinuteInput): HeatAssessment {
        val p = profile
        val accl = Workload.acclimatizationFraction(p.daysAcclimatized)
        val reasons = mutableListOf<String>()

        // Workload: the higher of declared and measured.
        var work = p.declaredWork
        x.enmoMg?.let { work = maxOf(work, Workload.workClassFromEnmo(it)) }

        // Environment.
        val wbgt = x.wbgtC ?: HeatEnvironment.wbgtEstimate(x.taC, x.rhPct, x.tgC)
        var envLevel: HeatLevel? = null
        var wbgtEff: Double? = null
        var limit: Double? = null
        var workMin: Int? = null
        if (wbgt == null) {
            reasons += "env_not_measured"
        } else {
            val eff = Workload.effectiveWbgt(wbgt, p.clothing, p.hood)
            val lim = Workload.wbgtLimit(work.metabolicW, accl)
            val wm = Workload.workMinutesPerHour(eff, work, accl)
            wbgtEff = eff; limit = lim; workMin = wm
            val excess = eff - lim
            envLevel = when {
                excess >= 0 && wm == 0 -> { reasons += "env_no_safe_work"; HeatLevel.ORANGE }
                excess >= 0 -> { reasons += "env_over_limit"; HeatLevel.YELLOW }
                excess >= -2.0 -> { reasons += "env_near_limit"; HeatLevel.YELLOW }
                else -> HeatLevel.GREEN
            }
        }

        // Physiology.
        val hr = validHr(x)
        if (hr != null) {
            filter.update(hr)
            recentHr.addLast(hr)
            if (recentHr.size > 5) recentHr.removeFirst()
            lastValidHrT = x.tMin
            if (hr0 == null) {
                baselineHrs += hr
                if (baselineHrs.size >= baselineMinutes) hr0 = minOf(median(baselineHrs), 100.0)
            }
        } else {
            filter.predict()
        }

        val stale = lastValidHrT.let { it == null || x.tMin - it > hrStaleMin }
        var physLevel: HeatLevel? = null
        var ctEst: Double? = null
        var ctSd: Double? = null
        var psiVal: Double? = null
        if (stale) {
            reasons += "hr_not_measured"
            recentHr.clear()
        } else {
            val ct = filter.ct
            ctEst = ct
            ctSd = filter.sd
            var phys = HeatLevel.GREEN
            val ctStop = 38.0 + 0.5 * accl
            when {
                ct >= ctStop -> { phys = HeatLevel.RED; reasons += "core_temp_limit" }
                ct >= ctStop - 0.3 -> { phys = HeatLevel.ORANGE; reasons += "core_temp_high" }
                ct >= ctStop - 0.6 -> { phys = HeatLevel.YELLOW; reasons += "core_temp_rising" }
            }
            val base = hr0
            if (base != null && hr != null) {
                val s = Psi.psi(filter.ct, hr, ct0, base)
                psiVal = s
                when {
                    s >= 7 -> { phys = maxOf(phys, HeatLevel.RED); reasons += "strain_high" }
                    s >= 5 -> { phys = maxOf(phys, HeatLevel.ORANGE); reasons += "strain_moderate" }
                    s >= 3 -> { phys = maxOf(phys, HeatLevel.YELLOW); reasons += "strain_low" }
                }
            }
            val age = p.age
            if (age != null && recentHr.size == 5 && recentHr.sum() / 5 > 180 - age) {
                phys = HeatLevel.RED
                reasons += "hr_sustained_high"
            }
            physLevel = phys
        }

        var raw = listOfNotNull(envLevel, physLevel).maxOrNull() ?: HeatLevel.GREEN

        // Collapse: latched until acknowledged.
        if (x.fallEvent) {
            latched = if (raw >= HeatLevel.YELLOW) "collapse_in_heat" else "fall"
        }
        latched?.let { reasons += it; raw = HeatLevel.RED }

        val lv = smooth(raw, x.tMin)
        val action = action(lv, "env_over_limit" in reasons)

        return HeatAssessment(
            tMin = x.tMin, level = lv, action = action, reasons = reasons,
            workMinPerHour = workMin, ctEst = ctEst, ctSd = ctSd, psi = psiVal,
            hr = hr, wbgtEff = wbgtEff, wbgtLimit = limit, workClass = work,
        )
    }

    private fun smooth(raw: HeatLevel, t: Int): HeatLevel {
        if (raw >= level) {                     // escalate (or hold) immediately
            level = raw
            lowerSince = null
            lowerWindow.clear()
            return level
        }
        val since = lowerSince ?: t.also { lowerSince = it }
        lowerWindow += raw
        if (t - since + 1 >= cooldownMin) {
            level = lowerWindow.max()
            lowerSince = null
            lowerWindow.clear()
        }
        return level
    }

    private fun action(lv: HeatLevel, pace: Boolean): String = when (lv) {
        HeatLevel.RED -> if (latched == "collapse_in_heat") "emergency" else "stop_get_help"
        HeatLevel.YELLOW -> if (pace) "work_rest" else "water_shade"
        HeatLevel.GREEN -> "keep_going"
        HeatLevel.ORANGE -> "stop_and_cool"
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}

/**
 * Plain-language text. Keys are what gets translated (Day 7 of the plan:
 * Arabic, Hindi/Urdu, Bengali, Tagalog). English is the fallback.
 */
object HeatText {
    private val ACTIONS = mapOf(
        "keep_going" to "Keep working. Drink water every 15-20 minutes.",
        "water_shade" to "Drink water now and take a short break in shade.",
        "work_rest" to "Pace the work: each hour, work only the minutes shown, rest the rest in shade.",
        "stop_and_cool" to "Stop work. Rest in shade, drink water, loosen clothing.",
        "stop_get_help" to "Stop now. Move to shade and tell your supervisor.",
        "emergency" to "Emergency: possible heat collapse. Call for medical help and start cooling.",
    )
    private val REASONS = mapOf(
        "env_near_limit" to "Site heat is close to the safe limit for this work.",
        "env_over_limit" to "Site heat is over the safe limit for continuous work.",
        "env_no_safe_work" to "Site heat is too high for this work, even with rest breaks.",
        "env_not_measured" to "Site heat not measured.",
        "core_temp_rising" to "Estimated core temperature is rising.",
        "core_temp_high" to "Estimated core temperature is near the stop limit.",
        "core_temp_limit" to "Estimated core temperature reached the stop limit.",
        "strain_low" to "Heat strain is low.",
        "strain_moderate" to "Heat strain is moderate.",
        "strain_high" to "Heat strain is high.",
        "hr_sustained_high" to "Heart rate has stayed very high for 5 minutes.",
        "hr_not_measured" to "Heart rate not measured recently.",
        "collapse_in_heat" to "Fall or collapse detected during heat strain.",
        "fall" to "Fall detected.",
    )

    fun action(key: String) = ACTIONS[key] ?: key
    fun reason(key: String) = REASONS[key] ?: key
    val actionKeys: Set<String> get() = ACTIONS.keys
    val reasonKeys: Set<String> get() = REASONS.keys
}
