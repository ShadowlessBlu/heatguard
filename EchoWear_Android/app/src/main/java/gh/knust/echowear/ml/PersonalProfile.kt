package gh.knust.echowear.ml

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What EchoWear has learned about ONE wearer. One instance per paired wearable, so
 * nothing is shared between people: a frail 80-year-old's slow shuffle and a healthy
 * 30-year-old's brisk walk each set their own alert threshold and heart-rate baseline.
 *
 * The fall network itself is trained once on public data and is the same for everyone.
 * What is personal is everything around it:
 *
 *  1. ALERT THRESHOLD. Every inference window gives a score. The profile keeps a
 *     time-decayed histogram of the "two-in-a-row" score min(score[t], score[t-1]) over
 *     this wearer's ordinary, non-alert wear. After ~50 minutes of wear the alert
 *     threshold becomes just above the top of that wearer's own normal range
 *     (99.95th percentile + margin), never lower than the model's global threshold unless
 *     [allowLower] is set, and never above [ModelSpec.PERSONAL_CAP] so that adaptation
 *     cannot silence a real fall. Offline, on held-out subjects, this cut false alarms
 *     without losing falls (see ml/out/report.md).
 *
 *  2. FALSE-ALARM FEEDBACK. When the wearer or carer cancels a phone-ML alert, the score
 *     that triggered it is recorded as "normal for this person", which raises the
 *     threshold above it next time.
 *
 *  3. HEART RATE BASELINE. Resting heart rate and its short-term spread are tracked only
 *     while the wearer is still (motion tier 0). An alert's heart rate is reported as a
 *     z-score against that wearer's own baseline, not against a population number, so a
 *     beta-blocker patient resting at 52 bpm and an athlete at 48 are each judged by
 *     their own normal.
 *
 * Nothing here leaves the phone. The profile is stored in the SQLCipher database.
 */
class PersonalProfile(val deviceId: String) {

    companion object {
        const val BINS = 100
        /** ~50 minutes of wear at 2 windows per second. */
        const val WARM_WINDOWS = 6000L
        /** Histogram memory: older wear counts for half after this many windows (~72 h). */
        const val HALF_LIFE_WINDOWS = 2.0 * 3600 * 72
        const val TAIL_QUANTILE = 0.9995
        const val MARGIN = 0.02f

        const val HR_WARM_SAMPLES = 1200      // ~10 minutes of still, valid readings
        const val HR_ALPHA = 1.0 / 900.0      // ~7.5 min time constant at 2 Hz
        const val HR_MIN_SD = 2.5f

        private const val FA_KEEP = 20
    }

    /** Raise-only by default: personal adaptation may make the alert stricter, not looser. */
    var allowLower: Boolean = false

    private val hist = DoubleArray(BINS)
    private var histTotal = 0.0
    private val decay = 0.5.pow(1.0 / HALF_LIFE_WINDOWS)
    var windowsSeen: Long = 0L
        private set
    private var prevScore = 0f

    /** Scores (two-in-a-row) of alerts the wearer cancelled as false. */
    private val falseAlarms = ArrayDeque<Float>()

    var restHr: Float = 0f
        private set
    private var restHrVar: Float = 0f
    var restHrSamples: Int = 0
        private set
    val restHrSd: Float get() = max(sqrt(restHrVar), HR_MIN_SD)

    /** EMA of dynamic acceleration RMS (g) during ordinary movement, for display. */
    var activityRmsG: Float = 0f
        private set

    val isWarm: Boolean get() = windowsSeen >= WARM_WINDOWS
    val hrIsWarm: Boolean get() = restHrSamples >= HR_WARM_SAMPLES

    /** 0..1 progress toward a personal threshold, for the UI. */
    val learningProgress: Float get() = min(1f, windowsSeen.toFloat() / WARM_WINDOWS)

    // --------------------------------------------------------------- scores
    /**
     * @param score model output for the newest window
     * @param include false while an alert is active/refractory, so a real fall is never
     *                learned as "normal"
     */
    @Synchronized
    fun observeScore(score: Float, include: Boolean) {
        val m2 = min(score, prevScore)
        prevScore = score
        if (!include) return
        windowsSeen++
        for (i in 0 until BINS) hist[i] *= decay
        histTotal *= decay
        val bin = (m2 * BINS).toInt().coerceIn(0, BINS - 1)
        hist[bin] += 1.0
        histTotal += 1.0
    }

    /** The score that triggered an alert the wearer then cancelled: normal for this person. */
    @Synchronized
    fun markFalseAlarm(twoInARowScore: Float) {
        falseAlarms.addLast(twoInARowScore)
        while (falseAlarms.size > FA_KEEP) falseAlarms.removeFirst()
    }

    /** Alert threshold for this wearer. Equals [globalThreshold] until the profile is warm. */
    @Synchronized
    fun threshold(globalThreshold: Float = ModelSpec.THRESHOLD): Float {
        var thr = globalThreshold
        if (isWarm && histTotal > 0) {
            var acc = 0.0
            var edge = 1.0f
            for (i in 0 until BINS) {
                acc += hist[i]
                if (acc / histTotal >= TAIL_QUANTILE) { edge = (i + 1) / BINS.toFloat(); break }
            }
            val personal = (edge + MARGIN).coerceAtMost(ModelSpec.PERSONAL_CAP)
            thr = if (allowLower) max(personal, ModelSpec.PERSONAL_FLOOR) else max(personal, globalThreshold)
        }
        falseAlarms.maxOrNull()?.let { thr = max(thr, it + MARGIN) }
        return thr.coerceAtMost(ModelSpec.PERSONAL_CAP)
    }

    val isPersonalised: Boolean get() = isWarm || falseAlarms.isNotEmpty()

    // ---------------------------------------------------------- heart rate
    /** @param stillTier MQG motion tier of the reading; only tier 0 (still) teaches the baseline */
    @Synchronized
    fun observeHr(bpm: Float, stillTier: Int) {
        if (stillTier != 0 || bpm < 38f || bpm > 140f) return
        if (restHrSamples == 0) {
            restHr = bpm
            restHrVar = 0f
        } else {
            val d = bpm - restHr
            restHr += (HR_ALPHA * d).toFloat()
            restHrVar = ((1 - HR_ALPHA) * (restHrVar + HR_ALPHA * d * d)).toFloat()
        }
        restHrSamples++
    }

    @Synchronized
    fun observeActivity(rmsG: Float) {
        if (rmsG < 0.08f || rmsG > 1.5f) return
        activityRmsG = if (activityRmsG == 0f) rmsG else activityRmsG + 0.001f * (rmsG - activityRmsG)
    }

    /** How many of this wearer's own standard deviations the reading is above their resting HR. */
    @Synchronized
    fun hrZ(bpm: Float): Float? = if (hrIsWarm) (bpm - restHr) / restHrSd else null

    @Synchronized
    fun describeHr(bpm: Float): String? {
        val z = hrZ(bpm) ?: return null
        return "HR %.0f bpm = %+.1f sd from this wearer's resting %.0f bpm".format(bpm, z, restHr)
    }

    // ------------------------------------------------------------ persistence
    @Synchronized
    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("seen", windowsSeen)
        put("total", histTotal)
        put("hist", JSONArray().also { a -> hist.forEach { a.put(Math.round(it * 100) / 100.0) } })
        put("prev", prevScore.toDouble())
        put("fa", JSONArray().also { a -> falseAlarms.forEach { a.put(it.toDouble()) } })
        put("hr", restHr.toDouble())
        put("hrVar", restHrVar.toDouble())
        put("hrN", restHrSamples)
        put("act", activityRmsG.toDouble())
        put("lower", allowLower)
    }.toString()

    @Synchronized
    fun restore(json: String) {
        runCatching {
            val o = JSONObject(json)
            windowsSeen = o.optLong("seen", 0L)
            histTotal = o.optDouble("total", 0.0)
            val h = o.optJSONArray("hist")
            if (h != null) for (i in 0 until min(BINS, h.length())) hist[i] = h.getDouble(i)
            prevScore = o.optDouble("prev", 0.0).toFloat()
            falseAlarms.clear()
            o.optJSONArray("fa")?.let { a -> for (i in 0 until a.length()) falseAlarms.addLast(a.getDouble(i).toFloat()) }
            restHr = o.optDouble("hr", 0.0).toFloat()
            restHrVar = o.optDouble("hrVar", 0.0).toFloat()
            restHrSamples = o.optInt("hrN", 0)
            activityRmsG = o.optDouble("act", 0.0).toFloat()
            allowLower = o.optBoolean("lower", false)
        }
    }

    @Synchronized
    fun reset() {
        hist.fill(0.0); histTotal = 0.0; windowsSeen = 0; prevScore = 0f
        falseAlarms.clear(); restHr = 0f; restHrVar = 0f; restHrSamples = 0; activityRmsG = 0f
    }
}
