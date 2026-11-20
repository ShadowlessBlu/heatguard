package gh.knust.echowear.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.data.DayCount
import gh.knust.echowear.data.EchoWearDatabase
import gh.knust.echowear.data.EventRecord
import gh.knust.echowear.data.VitalsRecord
import gh.knust.echowear.data.VitalsSummary
import gh.knust.echowear.service.DeviceConnection
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.components.BadgeTone
import gh.knust.echowear.ui.components.BarChart
import gh.knust.echowear.ui.components.EmptyState
import gh.knust.echowear.ui.components.InfoRow
import gh.knust.echowear.ui.components.LineChart
import gh.knust.echowear.ui.components.SectionHeader
import gh.knust.echowear.ui.components.StatusBadge
import gh.knust.echowear.ui.export.Exporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val EVENT_FMT = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK)

/** Reporting windows offered across the history screens. */
enum class Period(val days: Int, val label: String) {
    DAY(1, "24 h"), WEEK(7, "7 days"), MONTH(30, "30 days");

    val sinceMs: Long get() = System.currentTimeMillis() - days * 24L * 3600 * 1000
}

private enum class EventFilter(val label: String) {
    ALL("All"),
    FALLS("Falls"),
    SOS("SOS"),
    HEAT("Heat"),
    ESCALATED("Escalated"),
    CANCELLED("Cancelled");

    fun matches(e: EventRecord): Boolean = when (this) {
        ALL -> true
        FALLS -> e.isFall
        SOS -> e.isSos
        HEAT -> e.isHeat
        ESCALATED -> e.smsSent
        CANCELLED -> e.acknowledged && !e.smsSent
    }
}

// ============================================================== event log
/**
 * The event log, ported from EventLog.tsx and UserEvents.tsx.
 *
 * Filtering is by outcome rather than only by type, because the question a
 * clinician asks of this list is usually "how many of these actually reached
 * anybody" - and a detector's raw count without its cancellation rate is the
 * number that flatters the device rather than describing it.
 */
@Composable
fun EventsScreen(
    conn: DeviceConnection?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val db = remember { EchoWearDatabase.get(context) }
    var filter by remember { mutableStateOf(EventFilter.ALL) }

    val flow = remember(conn?.deviceId) {
        if (conn == null) {
            db.events().recent(300)
        } else {
            db.events().recentForDevice(conn.deviceId, 300)
        }
    }
    val events by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val shown = events.filter { filter.matches(it) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SectionHeader(
            "Events",
            subtitle = conn?.displayName ?: "All patients",
            trailing = if (conn == null) null else {
                { ExportButton(conn, events) }
            },
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EventFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (shown.isEmpty()) {
            EmptyState(
                if (events.isEmpty()) {
                    "Nothing has been recorded yet. That is the outcome to hope for."
                } else {
                    "No events match this filter."
                }
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { e -> EventRow(e) }
            }
        }
    }
}

@Composable
private fun EventRow(e: EventRecord) {
    val tone = when {
        e.smsSent -> BadgeTone.BAD
        e.isHeat -> if (e.kind == "HEAT_RED") BadgeTone.BAD else BadgeTone.WARN
        e.acknowledged -> BadgeTone.OK
        else -> BadgeTone.WARN
    }
    val outcome = when {
        e.smsSent -> "Escalated"
        e.isHeat -> "Heat warning"
        e.acknowledged -> "Cancelled"
        else -> "Open"
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.kind.replace('_', ' ').lowercase()
                        .replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(outcome, tone)
            }
            Text(
                EVENT_FMT.format(Date(e.occurredAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            InfoRow("Source", sourceLabel(e.source))
            if (e.score > 0f) InfoRow("Score", "%.3f".format(e.score))
            if (e.hrBpm > 0f) InfoRow("Heart rate", "${e.hrBpm.roundToInt()} bpm")
            if (e.gaitDeviation > 0f) {
                InfoRow("Gait deviation", "%.2f sigma".format(e.gaitDeviation))
            }
            if (e.lat != 0.0 || e.lon != 0.0) {
                InfoRow("Position", "%.5f, %.5f".format(e.lat, e.lon))
            }
            if (e.patientRef.isNotBlank()) InfoRow("Patient", e.patientRef)
            e.note?.takeIf { it.isNotBlank() }?.let { InfoRow("Note", it) }
        }
    }
}

private fun sourceLabel(source: String) = when (source) {
    "device" -> "Wearable TS-HGV"
    "phone_ml" -> "Phone CNN"
    "phone" -> "Phone (manual)"
    else -> source
}

@Composable
private fun ExportButton(conn: DeviceConnection, events: List<EventRecord>) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val intent = Exporter.share(
            context,
            Exporter.filename(conn.record, "events", "csv"),
            Exporter.eventsCsv(conn.record, events),
            "text/csv",
        )
        context.startActivity(Intent.createChooser(intent, "Share the event log"))
    }) {
        Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("CSV")
    }
}

// =============================================================== analytics
/**
 * Trends and risk, ported from UserAnalytics.tsx and RiskAnalyticsPanel.tsx.
 *
 * The composite figures here are descriptive, not a validated score, and the
 * screen says so. Presenting an unvalidated number as a clinical risk would
 * be the single most harmful thing this app could do, and it would be easy
 * to do accidentally by simply not labelling it.
 */
@Composable
fun AnalyticsScreen(conn: DeviceConnection?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { EchoWearDatabase.get(context) }
    var period by remember { mutableStateOf(Period.WEEK) }

    var vitals by remember { mutableStateOf<List<VitalsRecord>>(emptyList()) }
    var summary by remember { mutableStateOf<VitalsSummary?>(null) }
    var daily by remember { mutableStateOf<List<DayCount>>(emptyList()) }
    var events by remember { mutableStateOf<List<EventRecord>>(emptyList()) }

    LaunchedEffect(conn?.deviceId, period) {
        val id = conn?.deviceId ?: return@LaunchedEffect
        val since = period.sinceMs
        vitals = db.vitals().sinceForDevice(id, since)
        summary = db.vitals().summary(id, since)
        daily = db.events().dailyCounts(id, since)
        events = db.events().sinceForDevice(id, since)
    }

    if (conn == null) {
        EmptyState("Choose a patient to see their trends.", modifier)
        return
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        SectionHeader("Trends", subtitle = conn.displayName)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(p.label) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (vitals.isEmpty()) {
            EmptyState("No readings stored for this period yet.")
        } else {
            Text("Heart rate", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LineChart(
                vitals.map { it.bpm }.filter { it > 0f },
                yLabel = { "${it.roundToInt()}" },
                band = 50f..100f,
            )

            Spacer(Modifier.height(18.dp))
            Text("RMSSD (low-motion samples)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val rmssd = vitals
                .filter { it.motionTier == 0 && it.rmssdMs > 0f }
                .map { it.rmssdMs }
            if (rmssd.size > 2) {
                LineChart(rmssd, yLabel = { "${it.roundToInt()}" })
            } else {
                EmptyState(
                    "Not enough low-motion samples yet. HRV is only computed " +
                        "when the wearer is still."
                )
            }
        }

        SectionHeader("Events per day")
        BarChart(daily.map { it.day.takeLast(5) to it.n.toFloat() })

        SectionHeader(
            "Composite indicators",
            subtitle = "Descriptive only - not a validated clinical risk score",
        )
        RiskPanel(summary, events, period, vitals)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RiskPanel(
    summary: VitalsSummary?,
    events: List<EventRecord>,
    period: Period,
    vitals: List<VitalsRecord>,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            val falls = events.count { it.isFall }
            val perWeek = falls * 7f / period.days
            val highMotionShare = if (vitals.isEmpty()) {
                0f
            } else {
                vitals.count { it.motionTier >= 2 }.toFloat() / vitals.size
            }

            InfoRow("Falls in period", falls.toString())
            InfoRow("Rate", "%.1f per week".format(perWeek))
            InfoRow("Escalated to a caregiver", events.count { it.smsSent }.toString())
            InfoRow(
                "Cancelled by the wearer",
                events.count { it.acknowledged && !it.smsSent }.toString(),
            )
            InfoRow("Samples above 0.5 g", "%.0f %%".format(highMotionShare * 100))
            summary?.let {
                InfoRow("Wear compliance", "${it.wornPct.roundToInt()} %")
                InfoRow("Mean SDNN", "%.1f ms".format(it.avgSdnn))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "A high cancellation rate usually means the detector is firing " +
                    "on activities of daily living rather than falls; a high " +
                    "share of samples above 0.5 g means most of the period was " +
                    "too active for HRV to be computed at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ================================================================= report
/**
 * The clinical summary, ported from ClinicalReport.tsx.
 *
 * It is rendered as monospaced text on purpose: what leaves this screen is
 * exactly what a clinician receives, so there is no gap between what was
 * reviewed here and what was sent.
 */
@Composable
fun ReportScreen(
    service: MonitorService,
    conn: DeviceConnection?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val db = remember { EchoWearDatabase.get(context) }
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(Period.MONTH) }
    var report by remember { mutableStateOf("") }

    val clinician by service.settings.clinicianName.collectAsStateWithLifecycle("")
    val facility by service.settings.facility.collectAsStateWithLifecycle("")

    LaunchedEffect(conn?.deviceId, period, clinician, facility) {
        val c = conn ?: return@LaunchedEffect
        val since = period.sinceMs
        report = Exporter.clinicalReport(
            device = c.record,
            periodDays = period.days,
            summary = db.vitals().summary(c.deviceId, since),
            events = db.events().sinceForDevice(c.deviceId, since),
            clinician = clinician,
            facility = facility,
        )
    }

    if (conn == null) {
        EmptyState("Choose a patient to generate a report.", modifier)
        return
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SectionHeader("Clinical summary", subtitle = conn.displayName)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(p.label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val intent = Exporter.share(
                    context,
                    Exporter.filename(conn.record, "summary", "txt"),
                    report, "text/plain",
                )
                context.startActivity(Intent.createChooser(intent, "Share the summary"))
            }) {
                Icon(Icons.Filled.Share, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val rows = db.vitals().sinceForDevice(conn.deviceId, period.sinceMs)
                    val intent = Exporter.share(
                        context,
                        Exporter.filename(conn.record, "vitals", "csv"),
                        Exporter.vitalsCsv(conn.record, rows),
                        "text/csv",
                    )
                    context.startActivity(Intent.createChooser(intent, "Share the vitals"))
                }
            }) {
                Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Vitals CSV")
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Text(
                report.ifBlank { "Preparing…" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
