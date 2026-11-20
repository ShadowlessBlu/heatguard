package gh.knust.echowear.ml

import android.content.Context
import android.util.Log
import gh.knust.echowear.ble.ImuBatch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The machine-learning half of EchoWear, moved off the Cardputer and onto the
 * phone.
 *
 * The wearable streams 6-axis IMU at 100 Hz in 10-sample batches. This class
 * maintains a rolling 2-second buffer, decimates it to the 50 Hz / 100-sample
 * window the model was trained on (HIFD is a 50 Hz dataset; its acceleration has gravity restored so it matches the wrist IMU),
 * normalises with the training constants, and runs the INT8 CNN.
 *
 * Model provenance and measured performance are in ModelSpec below; both come
 * from the training run in analysis/train_fall_cnn.py, evaluated with
 * subject-wise grouped cross-validation so no subject appears in both the
 * training and test splits.
 */
object ModelSpec {
    const val ASSET_INT8 = "echowear_fall_int8.tflite"
    const val ASSET_FLOAT32 = "echowear_fall_float32.tflite"

    /** Model input: 100 samples at 50 Hz, 6 channels. */
    const val WINDOW = 100
    const val CHANNELS = 6
    const val MODEL_RATE_HZ = 50
    const val DEVICE_RATE_HZ = 100
    const val DECIMATION = DEVICE_RATE_HZ / MODEL_RATE_HZ      // 2

    /** Per-channel standardisation from the training set. */
    val MEAN = floatArrayOf(0.055543f, -0.386876f, 0.355687f, 0.746368f, -0.328791f, -1.105309f)
    val STD = floatArrayOf(0.678298f, 0.477033f, 0.447093f, 75.385605f, 50.464012f, 73.392159f)

    /**
     * Operating point: the lowest threshold that kept false alarms on activity recordings at or
     * under 5% for wearers the model never saw (HIFD, subject-wise 5-fold), with 2 consecutive
     * windows above it. On those wearers: 97% of staged falls detected, 4.5% of activity
     * recordings alarmed; on simulated elderly-like motion 85% and 2%. See ml/out/report.md.
     * Recorded on young volunteers only: no real older-adult data was available.
     */
    const val THRESHOLD = 0.95f
    const val CONSECUTIVE_WINDOWS = 2

    /**
     * Personalisation limits (see PersonalProfile). A wearer's threshold can be raised
     * above THRESHOLD, never above PERSONAL_CAP, so adaptation cannot silence a real fall.
     * PERSONAL_FLOOR only matters if a profile is switched to allowLower.
     */
    const val PERSONAL_CAP = 0.99f
    const val PERSONAL_FLOOR = 0.50f
    const val REFRACTORY_WINDOWS = 10

    /** Inference cadence: a new window every 0.5 s (4 x overlap). */
    const val STRIDE_SAMPLES = 50
}

data class MlVerdict(
    val score: Float,
    val fallDetected: Boolean,
    val consecutiveHigh: Int,
    val peakMagnitudeG: Float,
    val inferenceMicros: Long,
    /** Threshold actually applied to this window: the wearer's own once their profile is learned. */
    val threshold: Float = ModelSpec.THRESHOLD,
    val personalised: Boolean = false,
)

class FallClassifier(context: Context) {

    companion object {
        private const val TAG = "FallClassifier"
    }

    private val interpreter: Interpreter
    private val inputIsInt8: Boolean
    private val outputIsInt8: Boolean
    private var inScale = 1f
    private var inZero = 0
    private var outScale = 1f
    private var outZero = 0

    init {
        val model = loadAsset(context, ModelSpec.ASSET_INT8)
        val options = Interpreter.Options().apply {
            // Two threads is enough for an 11k-parameter CNN and leaves the
            // big cores free; XNNPACK gives no benefit at this size.
            setNumThreads(2)
            setUseXNNPACK(false)
        }
        interpreter = Interpreter(model, options)

        val inT = interpreter.getInputTensor(0)
        val outT = interpreter.getOutputTensor(0)
        inputIsInt8 = inT.dataType() == org.tensorflow.lite.DataType.INT8
        outputIsInt8 = outT.dataType() == org.tensorflow.lite.DataType.INT8
        if (inputIsInt8) {
            inScale = inT.quantizationParams().scale
            inZero = inT.quantizationParams().zeroPoint
        }
        if (outputIsInt8) {
            outScale = outT.quantizationParams().scale
            outZero = outT.quantizationParams().zeroPoint
        }
        Log.i(TAG, "model loaded: in=${inT.dataType()} out=${outT.dataType()} " +
                "shape=${inT.shape().joinToString("x")}")
    }

    private fun loadAsset(context: Context, name: String): MappedByteBuffer {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength,
            )
        }
    }

    /** @param window [WINDOW][CHANNELS] already normalised. */
    fun infer(window: Array<FloatArray>): Pair<Float, Long> {
        val started = System.nanoTime()
        val n = ModelSpec.WINDOW * ModelSpec.CHANNELS

        val input: ByteBuffer
        if (inputIsInt8) {
            input = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
            for (t in 0 until ModelSpec.WINDOW) {
                for (c in 0 until ModelSpec.CHANNELS) {
                    val q = (window[t][c] / inScale + inZero).roundToInt()
                        .coerceIn(-128, 127)
                    input.put(q.toByte())
                }
            }
        } else {
            input = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (t in 0 until ModelSpec.WINDOW) {
                for (c in 0 until ModelSpec.CHANNELS) input.putFloat(window[t][c])
            }
        }
        input.rewind()

        val output = ByteBuffer.allocateDirect(if (outputIsInt8) 1 else 4)
            .order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()

        val score = if (outputIsInt8) {
            (output.get().toInt() - outZero) * outScale
        } else {
            output.float
        }
        return score.coerceIn(0f, 1f) to (System.nanoTime() - started) / 1000
    }

    fun close() = interpreter.close()
}

/**
 * Rolling buffer + decision logic. Feed it BLE IMU batches; it emits a verdict
 * every STRIDE_SAMPLES device samples once the buffer is full.
 */
class MlPipeline(
    private val classifier: FallClassifier,
    /** This wearer's learned normal. Null = the same global threshold for everybody. */
    val profile: PersonalProfile? = null,
) {

    /** Convenience for single-device use; prefer sharing one classifier. */
    constructor(context: Context) : this(FallClassifier(context))

    private var refractory = 0
    private var prevScore = 0f

    /** Two-in-a-row score of the most recent alert, so a cancelled alert can teach the profile. */
    var lastAlertScore = 0f
        private set

    /** The wearer or carer cancelled the last phone-ML alert as a false alarm. */
    fun falseAlarmFeedback() { profile?.markFalseAlarm(lastAlertScore) }

    private val bufferSamples = ModelSpec.WINDOW * ModelSpec.DECIMATION   // 200 @100 Hz
    private val ring = Array(bufferSamples) { FloatArray(ModelSpec.CHANNELS) }
    private var writeIndex = 0
    private var filled = 0
    private var sinceLastInference = 0

    private var consecutiveHigh = 0
    private var lastVerdict: MlVerdict? = null

    /** Rolling window of recent scores, for the live chart. */
    private val scoreHistory = ArrayDeque<Float>()
    val history: List<Float> get() = scoreHistory.toList()

    fun onBatch(batch: ImuBatch): MlVerdict? {
        for (s in batch.samples) {
            ring[writeIndex] = s.copyOf()
            writeIndex = (writeIndex + 1) % bufferSamples
            if (filled < bufferSamples) filled++
            sinceLastInference++
        }
        if (filled < bufferSamples) return null
        if (sinceLastInference < ModelSpec.STRIDE_SAMPLES) return null
        sinceLastInference = 0
        return runInference()
    }

    private fun runInference(): MlVerdict {
        // Unwrap the ring oldest-first and decimate 100 Hz -> 50 Hz.
        val window = Array(ModelSpec.WINDOW) { FloatArray(ModelSpec.CHANNELS) }
        var peak = 0f
        for (i in 0 until ModelSpec.WINDOW) {
            val src = (writeIndex + i * ModelSpec.DECIMATION) % bufferSamples
            val raw = ring[src]
            val mag = sqrt(raw[0] * raw[0] + raw[1] * raw[1] + raw[2] * raw[2])
            if (mag > peak) peak = mag
            for (c in 0 until ModelSpec.CHANNELS) {
                window[i][c] = (raw[c] - ModelSpec.MEAN[c]) / ModelSpec.STD[c]
            }
        }

        val (score, micros) = classifier.infer(window)

        val threshold = profile?.threshold() ?: ModelSpec.THRESHOLD
        consecutiveHigh = if (score >= threshold) consecutiveHigh + 1 else 0
        val fall = consecutiveHigh >= ModelSpec.CONSECUTIVE_WINDOWS

        // Teach the profile only from ordinary wear: not from an alert and not from the
        // seconds after one, so a real fall is never learned as "normal".
        if (fall) { refractory = ModelSpec.REFRACTORY_WINDOWS; lastAlertScore = minOf(score, prevScore) }
        profile?.observeScore(score, include = !fall && refractory == 0)
        if (refractory > 0 && !fall) refractory--
        prevScore = score

        scoreHistory.addLast(score)
        while (scoreHistory.size > 120) scoreHistory.removeFirst()

        val verdict = MlVerdict(score, fall, consecutiveHigh, peak, micros, threshold,
            profile?.isPersonalised == true)
        lastVerdict = verdict
        // A fired alert must not re-fire on every subsequent window.
        if (fall) consecutiveHigh = 0
        return verdict
    }

    fun reset() {
        filled = 0
        writeIndex = 0
        sinceLastInference = 0
        consecutiveHigh = 0
        refractory = 0
        prevScore = 0f
        scoreHistory.clear()
    }

    /**
     * Does NOT close the classifier: it is shared across devices, so the
     * owner closes it once rather than the first pipeline to shut down
     * pulling the model out from under the others.
     */
    fun close() = reset()
}

/**
 * MQG-HRV on the phone side.
 *
 * The wearable already gates HRV computation by motion tier, but it only keeps
 * a 10-beat buffer. The phone accumulates a longer series, so it can apply the
 * same three-tier gate over a clinically meaningful 60-second window and track
 * the confidence-weighted trend the papers describe.
 */
class HrvAnalyzer {

    data class Window(
        val sdnn: Float,
        val rmssd: Float,
        val pnn50: Float,
        val meanHr: Float,
        val weight: Float,
        val tier: Int,
        val beats: Int,
        val ectopicRate: Float,
    )

    private val ibis = ArrayDeque<Pair<Long, Float>>()   // (timestamp, IBI ms)
    private var lastBpm = 0f

    /** Motion tier thresholds, identical to heartbeat.h. */
    private fun tierFor(rmsDynamicG: Float): Pair<Int, Float> = when {
        rmsDynamicG < 0.10f -> 0 to 1.0f
        rmsDynamicG < 0.50f -> 1 to 0.6f
        else -> 2 to 0.0f
    }

    fun onTelemetry(bpm: Float, motionTier: Int, now: Long) {
        if (bpm < 30f || bpm > 220f) return
        // The wearable reports averaged BPM, not raw intervals, so reconstruct
        // an interval only when the value actually changed - repeating the same
        // averaged value would fabricate variability that is not there.
        if (abs(bpm - lastBpm) < 0.01f) return
        lastBpm = bpm
        ibis.addLast(now to 60_000f / bpm)
        while (ibis.isNotEmpty() && now - ibis.first().first > 120_000L) ibis.removeFirst()
    }

    fun window(rmsDynamicG: Float, windowMs: Long = 60_000L): Window? {
        val now = System.currentTimeMillis()
        val series = ibis.filter { now - it.first <= windowMs }.map { it.second }
        if (series.size < 6) return null

        val (tier, weight) = tierFor(rmsDynamicG)
        if (tier >= 2) {
            // HIGH motion: discard rather than report a misleading value. This
            // is the whole point of MQG-HRV.
            return Window(0f, 0f, 0f, 0f, 0f, tier, series.size, 0f)
        }

        val (clean, ectopic) = filterEctopic(series)
        val mean = clean.average().toFloat()
        val sdnn = sqrt(clean.map { (it - mean) * (it - mean) }.average()).toFloat()
        var sumSq = 0.0
        for (i in 0 until clean.size - 1) {
            val d = clean[i + 1] - clean[i]
            sumSq += (d * d).toDouble()
        }
        val rmssd = sqrt(sumSq / (clean.size - 1)).toFloat()
        val over50 = (0 until clean.size - 1).count { abs(clean[it + 1] - clean[it]) > 50f }
        val pnn50 = over50.toFloat() / (clean.size - 1) * 100f

        return Window(
            sdnn = sdnn, rmssd = rmssd, pnn50 = pnn50,
            meanHr = 60_000f / mean, weight = weight, tier = tier,
            beats = clean.size, ectopicRate = ectopic.toFloat() / series.size,
        )
    }

    /** 5-beat median filter, >20% deviation replaced - matches heartbeat.h. */
    private fun filterEctopic(series: List<Float>): Pair<List<Float>, Int> {
        if (series.size < 5) return series to 0
        var count = 0
        val out = series.toMutableList()
        for (i in series.indices) {
            val lo = maxOf(0, i - 2)
            val hi = minOf(series.size - 1, i + 2)
            val median = series.subList(lo, hi + 1).sorted()[(hi - lo) / 2]
            if (median > 0f && abs(series[i] - median) / median > 0.20f) {
                out[i] = median
                count++
            }
        }
        return out to count
    }

    fun clear() {
        ibis.clear()
        lastBpm = 0f
    }
}
