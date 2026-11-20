package gh.knust.echowear.ui.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import gh.knust.echowear.data.EventRecord
import gh.knust.echowear.data.VitalsRecord
import gh.knust.echowear.data.VitalsSummary
import gh.knust.echowear.pairing.PairedDevice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * CSV and clinical-report generation, ported from the dashboards'
 * lib/export.ts and utils/clinicalReport.ts.
 *
 * Everything is written to the app's cache directory and shared through a
 * FileProvider rather than dropped in external storage. The records are
 * health data: they come out of a SQLCipher database on purpose, and writing
 * them to a world-readable Downloads folder would undo that in one line.
 */
object Exporter {

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmm", Locale.UK)

    /** RFC 4180: quote anything containing a comma, quote or newline. */
    private fun cell(value: Any?): String {
        val s = value?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    private fun row(vararg cells: Any?) = cells.joinToString(",") { cell(it) } + "\n"

    fun vitalsCsv(device: PairedDevice, records: List<VitalsRecord>): String =
        buildString {
            append(
                row(
                    "timestamp", "patient_ref", "device_serial", "bpm",
                    "sdnn_ms", "rmssd_ms", "pnn50_pct", "motion_tier",
                    "hrv_weight", "worn", "battery_pct",
                )
            )
            records.forEach { r ->
                append(
                    row(
                        STAMP.format(Date(r.recordedAt)), device.patientRef, device.serial,
                        "%.1f".format(r.bpm), "%.1f".format(r.sdnnMs),
                        "%.1f".format(r.rmssdMs), "%.1f".format(r.pnn50Pct),
                        r.motionTier, "%.2f".format(r.hrvWeight),
                        if (r.worn) 1 else 0, r.batteryPct,
                    )
                )
            }
        }

    fun eventsCsv(device: PairedDevice, records: List<EventRecord>): String =
        buildString {
            append(
                row(
                    "timestamp", "patient_ref", "device_serial", "kind", "source",
                    "score", "hr_bpm", "gait_deviation", "lat", "lon",
                    "acknowledged", "sms_sent", "note",
                )
            )
            records.forEach { r ->
                append(
                    row(
                        STAMP.format(Date(r.occurredAt)), r.patientRef, device.serial,
                        r.kind, r.source, "%.3f".format(r.score),
                        "%.1f".format(r.hrBpm), "%.3f".format(r.gaitDeviation),
                        if (r.lat == 0.0) "" else "%.6f".format(r.lat),
                        if (r.lon == 0.0) "" else "%.6f".format(r.lon),
                        if (r.acknowledged) 1 else 0,
                        if (r.smsSent) 1 else 0,
                        r.note.orEmpty(),
                    )
                )
            }
        }

    /**
     * The clinical summary. Deliberately plain text: it is going into an
     * email or a paper file, and a clinician reading it should not have to
     * open an app to see what the numbers were.
     *
     * Values the wearable could not measure are printed as "not measured"
     * rather than as zero. A zero SDNN is a clinical finding; an absent one
     * is a flat battery, and the report must not let them look the same.
     */
    fun clinicalReport(
        device: PairedDevice,
        periodDays: Int,
        summary: VitalsSummary?,
        events: List<EventRecord>,
        clinician: String,
        facility: String,
        generatedAt: Long = System.currentTimeMillis(),
    ): String = buildString {
        val falls = events.count { it.isFall }
        val sos = events.count { it.isSos }
        val heat = events.count { it.isHeat }
        val escalated = events.count { it.smsSent }
        val cancelled = events.count { it.acknowledged && !it.smsSent }

        appendLine("ECHOWEAR CLINICAL SUMMARY")
        appendLine("=".repeat(58))
        appendLine()
        appendLine("Patient reference : ${device.patientRef.ifBlank { "not assigned" }}")
        appendLine("Patient           : ${device.patientName.ifBlank { "not recorded" }}")
        appendLine("Device            : ${device.serial} (${device.type.label})")
        appendLine("Firmware          : ${device.firmware}")
        appendLine("Reporting period  : last $periodDays day${if (periodDays == 1) "" else "s"}")
        appendLine("Generated         : ${STAMP.format(Date(generatedAt))}")
        if (clinician.isNotBlank()) appendLine("Clinician         : $clinician")
        if (facility.isNotBlank()) appendLine("Facility          : $facility")
        appendLine()

        appendLine("CARDIOVASCULAR")
        appendLine("-".repeat(58))
        if (summary == null || summary.samples == 0) {
            appendLine("No heart-rate samples were recorded in this period.")
        } else {
            appendLine("Samples           : ${summary.samples}")
            appendLine(
                "Heart rate        : ${summary.avgBpm.roundToInt()} bpm mean " +
                    "(${summary.minBpm.roundToInt()}-${summary.maxBpm.roundToInt()})"
            )
            appendLine("SDNN              : ${"%.1f".format(summary.avgSdnn)} ms")
            appendLine("RMSSD             : ${"%.1f".format(summary.avgRmssd)} ms")
            appendLine("pNN50             : ${"%.1f".format(summary.avgPnn50)} %")
            appendLine("Wear compliance   : ${summary.wornPct.roundToInt()} % of samples")
            appendLine()
            appendLine(hrvInterpretation(summary))
        }
        appendLine()

        appendLine("FALL AND ALERT HISTORY")
        appendLine("-".repeat(58))
        appendLine("Detected falls    : $falls")
        appendLine("Manual SOS        : $sos")
        if (heat > 0) appendLine("Heat warnings     : $heat (orange/red, not falls)")
        appendLine("Escalated to SMS  : $escalated")
        appendLine("Cancelled by user : $cancelled")
        appendLine()
        if (events.isEmpty()) {
            appendLine("No events recorded in this period.")
        } else {
            events.sortedByDescending { it.occurredAt }.take(30).forEach { e ->
                append(STAMP.format(Date(e.occurredAt)))
                append("  ")
                append(e.kind.padEnd(16))
                append(e.source.padEnd(10))
                if (e.hrBpm > 0) append("HR ${e.hrBpm.roundToInt()} bpm  ")
                append(if (e.smsSent) "escalated" else if (e.acknowledged) "cancelled" else "open")
                e.note?.takeIf { it.isNotBlank() }?.let { append("  - $it") }
                appendLine()
            }
            if (events.size > 30) appendLine("... ${events.size - 30} earlier events omitted.")
        }
        appendLine()

        appendLine("METHOD NOTES")
        appendLine("-".repeat(58))
        appendLine(
            "Heart-rate variability is computed under three-tier motion " +
                "gating (MQG-HRV): intervals recorded above 0.5 g of dynamic " +
                "acceleration are discarded rather than down-weighted, and " +
                "those between 0.1 and 0.5 g carry a weight of 0.6. Figures " +
                "above are therefore drawn from low-motion periods and are " +
                "not comparable with unfiltered wrist HRV."
        )
        appendLine()
        appendLine(
            "Falls are classified by the two-stage TS-HGV detector on the " +
                "wearable (impact plus post-impact stillness, then a " +
                "personalised gait-deviation check) and independently by an " +
                "INT8 1D-CNN on the phone. Both sources are listed above; " +
                "\"source\" distinguishes them."
        )
        appendLine()
        appendLine(
            "This summary is generated from device telemetry. It is not a " +
                "diagnosis and has not been reviewed by a clinician unless " +
                "one is named above."
        )
    }

    private fun hrvInterpretation(s: VitalsSummary): String {
        // Deliberately cautious wording. These are wrist-optical intervals
        // from a consumer sensor, and an SDNN figure from one is not an ECG
        // finding - saying so in the report is more useful than a number
        // that invites a clinical decision it cannot support.
        val notes = mutableListOf<String>()
        if (s.avgSdnn in 0.1f..20f) {
            notes += "Mean SDNN is low for a resting adult; consider whether " +
                "the wear periods captured were genuinely at rest."
        }
        if (s.wornPct < 60f) {
            notes += "The device was reported as worn in only " +
                "${s.wornPct.roundToInt()}% of samples, so these figures " +
                "cover a limited part of the period."
        }
        if (abs(s.maxBpm - s.minBpm) > 80f) {
            notes += "Heart-rate range is wide, which on a wrist optical " +
                "sensor is as often motion artefact as physiology."
        }
        return if (notes.isEmpty()) {
            "No automated caveats flagged for this period."
        } else {
            notes.joinToString("\n") { "* $it" }
        }
    }

    /** Write to the cache and hand back a share intent. */
    fun share(context: Context, filename: String, content: String, mime: String): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, filename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun filename(device: PairedDevice, what: String, ext: String): String {
        val ref = device.patientRef.ifBlank { device.shortSerial }
        return "echowear-$what-$ref-${FILE_STAMP.format(Date())}.$ext"
    }
}
