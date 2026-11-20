package gh.knust.echowear.heat

import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Kotlin port of HeatRisk/heatrisk/{ectemp,psi,environment,workload}.py.
 *
 * Kept line-for-line with the Python reference and checked against it by
 * HeatGoldenTest, which replays HeatRisk/golden/heat_golden.csv. Change the
 * Python first, re-export the golden file, then change this.
 * Everything is Double: the golden vectors were produced in double precision.
 */

/**
 * Core temperature from heart rate (ECTemp).
 * Buller et al. 2013, Physiol. Meas. 34:781. One-state Kalman filter, 1-minute steps.
 * Validated in soldiers/athletes (~0.3 °C RMSE), not in Gulf outdoor workers.
 */
class ECTemp(var ct: Double = CT_START, var v: Double = V_START) {

    companion object {
        const val A = 1.0
        const val GAMMA = 0.022 * 0.022           // °C² per minute
        const val B0 = -7887.1
        const val B1 = 384.4286
        const val B2 = -4.5714
        const val SIGMA = 18.88 * 18.88           // bpm²
        const val CT_START = 37.1
        const val V_START = 0.0

        fun expectedHr(ct: Double) = B2 * ct * ct + B1 * ct + B0

        fun steadyStateCt(hr: Double): Double {
            val c = B0 - hr
            val disc = B1 * B1 - 4 * B2 * c
            require(disc >= 0) { "heart rate outside the model's range" }
            return (-B1 + sqrt(disc)) / (2 * B2)
        }
    }

    /** One minute with no heart rate: estimate holds, uncertainty grows. */
    fun predict(): Double {
        ct = A * ct
        v = A * A * v + GAMMA
        return ct
    }

    fun update(hr: Double): Double {
        predict()
        val m = 2 * B2 * ct + B1
        val k = v * m / (m * m * v + SIGMA)
        ct += k * (hr - expectedHr(ct))
        v *= 1 - k * m
        return ct
    }

    val sd: Double get() = sqrt(v)
}

/** Physiological Strain Index, Moran et al. 1998. 0..10. */
object Psi {
    private fun part(x: Double, x0: Double, xMax: Double): Double {
        val span = xMax - x0
        if (span <= 0) return 5.0
        return min(5.0, max(0.0, 5.0 * (x - x0) / span))
    }

    fun psi(ct: Double, hr: Double, ct0: Double, hr0: Double): Double =
        part(ct, ct0, 39.5) + part(hr, hr0, 180.0)
}

/** WBGT estimates from cheap sensors. All are estimates; the highest wins. */
object HeatEnvironment {
    fun saturationVpHpa(tC: Double) = 6.105 * exp(17.27 * tC / (237.7 + tC))

    /** Stull 2011 psychrometric wet bulb, RH clamped to 5..99 %. */
    fun wetBulbStull(tC: Double, rhPct: Double): Double {
        val rh = min(99.0, max(5.0, rhPct))
        val t = tC
        return (t * atan(0.151977 * sqrt(rh + 8.313659))
            + atan(t + rh) - atan(rh - 1.676331)
            + 0.00391838 * rh.pow(1.5) * atan(0.023101 * rh)
            - 4.686035)
    }

    /** Australian BoM simplified WBGT. */
    fun wbgtBom(tC: Double, rhPct: Double): Double {
        val e = rhPct / 100.0 * saturationVpHpa(tC)
        return 0.567 * tC + 0.393 * e + 3.94
    }

    /** ISO 7243 outdoor form with psychrometric wet bulb (reads low in sun). */
    fun wbgtOutdoorGlobe(tC: Double, rhPct: Double, tgC: Double) =
        0.7 * wetBulbStull(tC, rhPct) + 0.2 * tgC + 0.1 * tC

    fun wbgtEstimate(tC: Double?, rhPct: Double?, tgC: Double? = null): Double? {
        if (tC == null || rhPct == null) return null
        var best = wbgtBom(tC, rhPct)
        if (tgC != null) best = max(best, wbgtOutdoorGlobe(tC, rhPct, tgC))
        return best
    }
}

enum class WorkClass(val metabolicW: Double) {
    REST(115.0), LIGHT(180.0), MODERATE(300.0), HEAVY(415.0), VERY_HEAVY(520.0);
}

/** ACGIH limits, clothing adjustments, ENMO classes and work/rest scheduling. */
object Workload {
    val CLOTHING_CAV = linkedMapOf(
        "work_clothes" to 0.0,
        "cloth_coveralls" to 0.0,
        "double_layer_woven" to 3.0,
        "sms_polypropylene_coveralls" to 0.5,
        "polyolefin_coveralls" to 1.0,
        "vapour_barrier_coveralls" to 11.0,
    )
    const val HOOD_CAV = 1.0

    const val ENMO_LIGHT_MG = 44.8
    const val ENMO_MODERATE_MG = 100.6
    const val ENMO_VIGOROUS_MG = 428.8
    const val ACCLIMATIZATION_DAYS = 7.0

    fun workClassFromEnmo(enmo: Double): WorkClass = when {
        enmo < ENMO_LIGHT_MG -> WorkClass.REST
        enmo < ENMO_MODERATE_MG -> WorkClass.LIGHT
        enmo < ENMO_VIGOROUS_MG -> WorkClass.MODERATE
        else -> WorkClass.HEAVY
    }

    fun acclimatizationFraction(days: Double) = min(1.0, max(0.0, days / ACCLIMATIZATION_DAYS))

    private fun coeffs(acclFrac: Double): Pair<Double, Double> =
        (59.9 + (56.7 - 59.9) * acclFrac) to (14.1 + (11.5 - 14.1) * acclFrac)

    fun tlv(mW: Double) = 56.7 - 11.5 * log10(mW)
    fun actionLimit(mW: Double) = 59.9 - 14.1 * log10(mW)

    fun wbgtLimit(mW: Double, acclFrac: Double): Double {
        val (a, b) = coeffs(acclFrac)
        return a - b * log10(mW)
    }

    fun effectiveWbgt(wbgt: Double, clothing: String = "work_clothes", hood: Boolean = false) =
        wbgt + (CLOTHING_CAV[clothing] ?: 0.0) + (if (hood) HOOD_CAV else 0.0)

    fun workMinutesPerHour(wbgtEff: Double, work: WorkClass, acclFrac: Double, stepMin: Int = 5): Int {
        val mRest = WorkClass.REST.metabolicW
        val mWork = work.metabolicW
        val (a, b) = coeffs(acclFrac)
        val mMax = 10.0.pow((a - wbgtEff) / b)
        if (mMax >= mWork) return 60
        if (mMax <= mRest || mWork <= mRest) return 0
        val frac = (mMax - mRest) / (mWork - mRest)
        return (pyFloorDiv(60 * frac, stepMin.toDouble()) * stepMin).toInt()
    }

    /** Python's float `//`, so the golden vectors match to the minute. */
    internal fun pyFloorDiv(a: Double, b: Double): Double {
        val mod = a % b
        val div = (a - mod) / b
        var fd = floor(div)
        if (div - fd > 0.5) fd += 1.0
        return fd
    }
}
