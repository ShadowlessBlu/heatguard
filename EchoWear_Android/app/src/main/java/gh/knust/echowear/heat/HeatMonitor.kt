package gh.knust.echowear.heat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.sqrt

/** One telemetry packet, reduced to what the heat engine needs. */
data class HeatSample(
    val bpm: Double,
    val motionTier: Int,
    val worn: Boolean,
    val ax: Double, val ay: Double, val az: Double,
)

/**
 * Turns ~120 telemetry packets (2 Hz) into one [MinuteInput].
 *
 * Heart-rate quality reuses the wearable's MQG motion tiers: still = 1.0,
 * moderate = 0.6, high motion or off-wrist = 0. The minute's quality is the
 * mean, and its heart rate the median of the beats that carried any weight.
 * During heavy work most packets are tier 2, so the heart rate goes "not
 * measured" rather than reporting a PPG reading that motion has corrupted.
 *
 * ENMO here comes from the telemetry's 2 Hz accelerometer snapshot, not the
 * 50 Hz IMU stream (off by default for battery). That estimates the mean well
 * enough for a four-way work class, not much more.
 */
class MinuteAggregator {
    private val bpms = ArrayList<Double>()
    private var weightSum = 0.0
    private var packets = 0
    private var enmoSum = 0.0
    private var enmoN = 0
    private var fall = false

    fun add(s: HeatSample) {
        packets++
        val w = if (!s.worn) 0.0 else when (s.motionTier) { 0 -> 1.0; 1 -> 0.6; else -> 0.0 }
        weightSum += w
        if (w > 0 && s.bpm in 30.0..220.0) bpms += s.bpm
        if (s.worn) {
            val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            enmoSum += max(0.0, mag - 1.0) * 1000.0
            enmoN++
        }
    }

    fun markFall() { fall = true }

    /** Close the minute and start the next. */
    fun drain(tMin: Int, site: SiteReading?): MinuteInput {
        val hr = if (bpms.isEmpty()) null else bpms.sorted().let { s ->
            val n = s.size
            if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
        val x = MinuteInput(
            tMin = tMin,
            hr = hr,
            hrQuality = if (packets == 0) 0.0 else weightSum / packets,
            enmoMg = if (enmoN == 0) null else enmoSum / enmoN,
            taC = site?.taC, rhPct = site?.rhPct, tgC = site?.tgC, wbgtC = site?.wbgtC,
            fallEvent = fall,
        )
        bpms.clear(); weightSum = 0.0; packets = 0; enmoSum = 0.0; enmoN = 0; fall = false
        return x
    }
}

/**
 * Heat monitoring for one wearable: aggregator + engine + the latest result.
 *
 * Thread-safe: telemetry arrives on the BLE collector, the minute tick on the
 * service's own loop.
 */
class HeatMonitor(profile: WorkerProfile) {

    companion object {
        /** A gap this long with no telemetry ends the shift; the next reading starts a new one. */
        const val NEW_SHIFT_GAP_MS = 3L * 3600 * 1000
    }

    private val lock = Any()
    private var engine = HeatRiskEngine(profile)
    private val agg = MinuteAggregator()
    private var minute = 0
    private var lastSampleAt = 0L
    private var shiftStartedAt = 0L

    private val _assessment = MutableStateFlow<HeatAssessment?>(null)
    val assessment: StateFlow<HeatAssessment?> = _assessment.asStateFlow()

    val profile: WorkerProfile get() = synchronized(lock) { engine.profile }
    val shiftStart: Long get() = synchronized(lock) { shiftStartedAt }
    val isLatched: Boolean get() = synchronized(lock) { engine.isLatched }

    fun onSample(s: HeatSample, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (lastSampleAt != 0L && now - lastSampleAt > NEW_SHIFT_GAP_MS) startShiftLocked(now)
        if (shiftStartedAt == 0L) shiftStartedAt = now
        lastSampleAt = now
        agg.add(s)
    }

    /** The fall pipeline raised an alert; the next minute carries it. */
    fun onFall() = synchronized(lock) { agg.markFall() }

    fun acknowledge() = synchronized(lock) { engine.acknowledge() }

    /** Profile edits keep the shift's filter state; only a new shift resets it. */
    fun updateProfile(p: WorkerProfile) = synchronized(lock) { engine.profile = p }

    fun startShift(now: Long = System.currentTimeMillis()) = synchronized(lock) { startShiftLocked(now) }

    private fun startShiftLocked(now: Long) {
        engine = HeatRiskEngine(engine.profile)
        minute = 0
        shiftStartedAt = now
        _assessment.value = null
    }

    /**
     * Close one minute. Called once a minute whether or not telemetry came in:
     * a silent wearable must turn into "not measured", not into a frozen
     * last-known-good reading.
     */
    fun tick(site: SiteReading?): HeatAssessment? = synchronized(lock) {
        if (shiftStartedAt == 0L) return null       // never worn this session
        val a = engine.update(agg.drain(minute++, site))
        _assessment.value = a
        a
    }
}
