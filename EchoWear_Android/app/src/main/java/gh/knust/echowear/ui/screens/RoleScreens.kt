package gh.knust.echowear.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.alert.AppRole
import gh.knust.echowear.ble.LinkState
import gh.knust.echowear.data.EchoWearDatabase
import gh.knust.echowear.data.EventRecord
import gh.knust.echowear.data.VitalsRecord
import gh.knust.echowear.data.VitalsSummary
import gh.knust.echowear.ml.ModelSpec
import gh.knust.echowear.service.DeviceConnection
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.components.LineChart
import gh.knust.echowear.ui.export.Exporter
import gh.knust.echowear.ui.theme.LocalStatusPalette
import gh.knust.echowear.ui.theme.MinTouchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The role-based home screens (UI option "A + C", November 2026).
 *
 *   RolePicker  the first screen: "Patient or caregiver" or "Doctor". No sign-in in this testing build.
 *   CareHome    patient and caregiver: one status sentence, the heart-rate ring, today's timeline,
 *               and Call / Get help always at the bottom.
 *   DoctorHome  doctor: range switch, four tiles with the change against the previous period,
 *               the heart-rate chart, more measures, and the event table.
 *
 * Everything shown comes from the live link, the wearer's learned profile and the event and
 * vitals tables. Nothing here is sample data.
 */

/** White text on this orange is 5.2:1. */
private val HelpOrange = Color(0xFFC2410C)
private const val DAY_MS = 24L * 3600 * 1000

// ============================================================================ role picker
@Composable
fun RolePicker(onPick: (AppRole) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MonitorHeart, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "HeatGuard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "A safety net on the wrist.\nWho is using it?",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        RoleCard(
            Icons.Filled.Favorite, "Patient or caregiver", "How they are today, and help in one tap",
        ) { onPick(AppRole.WEARER) }
        Spacer(Modifier.height(14.dp))
        RoleCard(
            Icons.Filled.MonitorHeart, "Doctor", "Trends, heart-rate variability, falls and reports",
        ) { onPick(AppRole.CLINICIAN) }
        Spacer(Modifier.height(22.dp))
        Text(
            "Testing build · no sign-in yet",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF4A3A20),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFFFF4E5))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "You can switch role at any time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ============================================================================ shared pieces
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

private fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun agoText(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return when {
        min < 1 -> "Updated just now"
        min == 1 -> "Updated 1 minute ago"
        min < 60 -> "Updated $min minutes ago"
        else -> "Updated ${min / 60} h ago"
    }
}

private fun firstName(full: String, fallback: String) = full.trim().substringBefore(' ').ifBlank { fallback }

private enum class Tone { NOW, NORMAL, WARN, BAD }

private fun sourceLabel(source: String) = when (source) {
    "device" -> "band"
    "phone_ml" -> "phone model"
    "phone" -> "phone"
    else -> source
}

// ============================================================================ patient & caregiver
private enum class HrState { NONE, LEARNING, NORMAL, HIGH, LOW }

private data class TimelineItem(val time: String, val title: String, val sub: String, val tone: Tone)

@Composable
fun CareHome(
    service: MonitorService,
    conn: DeviceConnection,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val db = remember { EchoWearDatabase.get(context) }
    val t by conn.lastTelemetry.collectAsStateWithLifecycle()
    val link by conn.client.linkState.collectAsStateWithLifecycle()
    val appCaregiver by service.settings.caregiverName.collectAsStateWithLifecycle("")
    val appPhone by service.settings.caregiverPhone.collectAsStateWithLifecycle("")
    val eventsFlow = remember(conn.deviceId) { db.events().recentForDevice(conn.deviceId, 100) }
    val events by eventsFlow.collectAsStateWithLifecycle(emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val name = firstName(conn.displayName, "The wearer")
    val caregiver = firstName(conn.record.caregiverName.ifBlank { appCaregiver }, "your caregiver")
    val phone = conn.record.caregiverPhone.ifBlank { appPhone }
    val reading = t
    val live = link == LinkState.READY && reading != null
    val bpm = if (live) reading!!.bpm else 0f
    val profile = conn.profile
    val z = if (bpm > 0f) profile.hrZ(bpm) else null
    val hrState = when {
        bpm <= 0f -> HrState.NONE
        z == null -> HrState.LEARNING
        z >= 3f -> HrState.HIGH
        z <= -3f -> HrState.LOW
        else -> HrState.NORMAL
    }
    val headline = when {
        link != LinkState.READY -> "Not connected to $name's band"
        reading == null -> "Waiting for $name's first reading"
        !reading.worn -> "$name's band is off the wrist"
        hrState == HrState.HIGH -> "$name's heart rate is high"
        hrState == HrState.LOW -> "$name's heart rate is low"
        else -> "$name is doing well"
    }
    val today = events.filter { it.occurredAt >= startOfDay(now) }.sortedByDescending { it.occurredAt }
    val fallAlertsToday = today.count { it.isFall }
    val palette = LocalStatusPalette.current
    val hhmm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val timeline = buildList {
        if (bpm > 0f) {
            add(TimelineItem(
                hhmm.format(Date(now)), "Now: heart rate ${bpm.roundToInt()}",
                when (hrState) {
                    HrState.NORMAL -> "Normal for $name"
                    HrState.HIGH -> "Higher than usual for $name"
                    HrState.LOW -> "Lower than usual for $name"
                    else -> "Still learning $name's usual range"
                },
                Tone.NOW,
            ))
        }
        today.take(8).forEach { e -> add(describeForCare(e, caregiver, hhmm)) }
        if (today.isEmpty()) add(TimelineItem("", "Nothing unusual so far today", "No falls, no help requests", Tone.NORMAL))
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                headline,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = headline },
            )
            Text(
                if (conn.lastSeen > 0) agoText(now - conn.lastSeen) else "No reading yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ring = when (hrState) {
                        HrState.NORMAL -> palette.ok
                        HrState.HIGH, HrState.LOW -> palette.warn
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        Modifier.size(104.dp).semantics(mergeDescendants = true) {
                            contentDescription = if (bpm > 0f) "Heart rate ${bpm.roundToInt()} beats per minute" else "No heart-rate reading"
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = 8.dp.toPx()
                            drawCircle(ring, radius = size.minDimension / 2 - w / 2, style = Stroke(width = w))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (bpm > 0f) "${bpm.roundToInt()}" else "--",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("bpm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (hrState) {
                                HrState.NORMAL -> "Heart rate is normal"
                                HrState.HIGH -> "Higher than usual"
                                HrState.LOW -> "Lower than usual"
                                HrState.LEARNING -> "Learning $name's usual heart rate"
                                HrState.NONE -> "No heart-rate reading"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        val usual = if (profile.hrIsWarm) {
                            val lo = (profile.restHr - 2 * profile.restHrSd).roundToInt()
                            val hi = (profile.restHr + 2 * profile.restHrSd).roundToInt()
                            "Usually $lo to $hi for $name. "
                        } else ""
                        val falls = if (fallAlertsToday == 0) "No falls today." else "$fallAlertsToday fall alert${if (fallAlertsToday > 1) "s" else ""} today."
                        val worn = when {
                            reading == null -> ""
                            reading.worn -> " Band on the wrist."
                            else -> " Band not on the wrist."
                        }
                        Text(
                            usual + falls + worn,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Panel {
                Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                timeline.forEachIndexed { i, item -> TimelineRow(item, last = i == timeline.lastIndex) }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Always on screen, whatever has been scrolled.
        Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (phone.isNotBlank()) service.alerts.placeCall(phone) else onOpenSettings()
                        },
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).height(MinTouchTarget),
                    ) {
                        Text(
                            if (phone.isNotBlank()) "Call $caregiver" else "Add a caregiver",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HoldForHelpButton(
                        onTrigger = { service.triggerSos(conn.deviceId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (phone.isNotBlank()) {
                        "Hold \"Get help now\" for 2 seconds: $caregiver gets a text with $name's location."
                    } else {
                        "Hold \"Get help now\" for 2 seconds. Add a caregiver's number so the text has somewhere to go."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun describeForCare(e: EventRecord, caregiver: String, hhmm: SimpleDateFormat): TimelineItem {
    val time = hhmm.format(Date(e.occurredAt))
    return when {
        e.isSos -> when {
            e.smsSent -> TimelineItem(time, "Help requested", "$caregiver was sent a text", Tone.BAD)
            e.acknowledged -> TimelineItem(time, "Help request cancelled", "Cancelled during the countdown", Tone.WARN)
            else -> TimelineItem(time, "Help requested", "Waiting for the countdown", Tone.BAD)
        }
        e.isHeat -> TimelineItem(
            time, if (e.kind == "HEAT_RED") "Heat risk: red" else "Heat risk: orange",
            "Rest in the shade and drink water", Tone.WARN,
        )
        e.smsSent -> TimelineItem(time, "Fall, help was sent", "$caregiver was sent a text with the location", Tone.BAD)
        e.acknowledged -> TimelineItem(time, "Fall alert, cancelled", "\"I'm OK\" was pressed in time", Tone.WARN)
        else -> TimelineItem(time, "Possible fall", "Detected by the ${sourceLabel(e.source)}", Tone.WARN)
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, last: Boolean) {
    val palette = LocalStatusPalette.current
    val dot = when (item.tone) {
        Tone.BAD -> palette.bad
        Tone.WARN -> HelpOrange
        else -> MaterialTheme.colorScheme.primary
    }
    val titleColour = when (item.tone) {
        Tone.BAD -> palette.bad
        Tone.WARN -> Color(0xFF9A3412)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
        Text(
            item.time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp).padding(top = 1.dp),
        )
        Column(Modifier.width(18.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            val filled = item.tone != Tone.NORMAL
            Box(
                Modifier.padding(top = 3.dp).size(12.dp).clip(CircleShape)
                    .background(if (filled) dot else Color.Transparent)
                    .then(
                        if (filled) Modifier else Modifier.background(Color.Transparent)
                    ),
            ) {
                if (!filled) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(dot, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    }
                }
            }
            if (!last) {
                Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = titleColour)
            Text(item.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Press and hold for two seconds, so a pocket or a stray hand does not raise an SOS. TalkBack users
 * get a plain double-tap through the semantics action instead, because a timed hold is hard to do
 * with a screen reader; the 10-second SOS countdown still lets anyone cancel.
 */
@Composable
private fun HoldForHelpButton(onTrigger: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val trigger by rememberUpdatedState(onTrigger)
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(MinTouchTarget)
            .clip(RoundedCornerShape(18.dp))
            .background(HelpOrange)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    val job = scope.launch {
                        val start = System.currentTimeMillis()
                        while (true) {
                            val p = (System.currentTimeMillis() - start) / 2000f
                            progress = p.coerceAtMost(1f)
                            if (p >= 1f) {
                                trigger()
                                break
                            }
                            delay(16)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    holding = false
                    progress = 0f
                })
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Get help now"
                onClick(label = "send a help request") {
                    trigger()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress)
                .background(Color.Black.copy(alpha = 0.22f)),
        )
        Text(
            if (holding) "Keep holding" else "Get help now",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ============================================================================ doctor
private class DoctorData(
    val cur: VitalsSummary?,
    val prev: VitalsSummary?,
    val vitals: List<VitalsRecord>,
    val events: List<EventRecord>,
    val prevEvents: List<EventRecord>,
    val minBattery: Int?,
)

private fun VitalsSummary?.has() = this != null && samples > 0

private fun signed(v: Float, decimals: Int = 0): String {
    val r = if (decimals == 0) v.roundToInt().toFloat() else v
    return when {
        abs(r) < 0.05f -> "0"
        r > 0 -> "+" + "%.${decimals}f".format(r)
        else -> "−" + "%.${decimals}f".format(-r)
    }
}

@Composable
fun DoctorHome(
    service: MonitorService,
    conn: DeviceConnection,
    devices: List<DeviceConnection>,
    onOpenEvents: () -> Unit,
    onOpenReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val db = remember { EchoWearDatabase.get(context) }
    var period by remember { mutableStateOf(Period.WEEK) }
    var data by remember(conn.deviceId) { mutableStateOf<DoctorData?>(null) }
    var patientMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }

    LaunchedEffect(conn.deviceId, period) {
        val id = conn.deviceId
        val now = System.currentTimeMillis()
        val span = period.days * DAY_MS
        val since = now - span
        val prevSince = since - span
        val ev = db.events().sinceForDevice(id, prevSince)
        data = DoctorData(
            cur = db.vitals().summary(id, since),
            prev = db.vitals().summaryBetween(id, prevSince, since),
            vitals = db.vitals().sinceForDevice(id, since),
            events = ev.filter { it.occurredAt >= since }.sortedByDescending { it.occurredAt },
            prevEvents = ev.filter { it.occurredAt < since },
            minBattery = db.vitals().minBattery(id, since),
        )
    }

    val d = data
    val periodWord = when (period) { Period.DAY -> "day"; Period.WEEK -> "week"; Period.MONTH -> "month" }

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(
                    onClick = { if (devices.size > 1) patientMenu = true },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Text(
                        "Patient " + conn.record.patientRef.ifBlank { conn.record.shortSerial } +
                            if (devices.size > 1) " ▾" else "",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                DropdownMenu(expanded = patientMenu, onDismissRequest = { patientMenu = false }) {
                    devices.forEach { other ->
                        DropdownMenuItem(
                            text = { Text("${other.displayName} · ${other.record.patientRef.ifBlank { other.record.shortSerial }}") },
                            onClick = { service.select(other.deviceId); patientMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { exportMenu = true }, shape = RoundedCornerShape(12.dp)) {
                    Text("Export ▾", style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Events (CSV)") },
                        enabled = d != null,
                        onClick = {
                            exportMenu = false
                            val dd = d ?: return@DropdownMenuItem
                            val intent = Exporter.share(
                                context, Exporter.filename(conn.record, "events", "csv"),
                                Exporter.eventsCsv(conn.record, dd.events), "text/csv",
                            )
                            context.startActivity(Intent.createChooser(intent, "Share the event log"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Readings (CSV)") },
                        enabled = d != null,
                        onClick = {
                            exportMenu = false
                            val dd = d ?: return@DropdownMenuItem
                            val intent = Exporter.share(
                                context, Exporter.filename(conn.record, "vitals", "csv"),
                                Exporter.vitalsCsv(conn.record, dd.vitals), "text/csv",
                            )
                            context.startActivity(Intent.createChooser(intent, "Share the readings"))
                        },
                    )
                    DropdownMenuItem(text = { Text("Full report") }, onClick = { exportMenu = false; onOpenReport() })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(conn.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            (if (conn.record.isHeadless) "Headless band" else "Touch band") + " · firmware ${conn.record.firmware}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        PeriodSwitch(period) { period = it }
        Spacer(Modifier.height(6.dp))
        Text(
            "Change is against the previous ${period.label}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        val cur = d?.cur
        val prev = d?.prev
        val falls = d?.events?.count { it.isFall && it.smsSent } ?: 0
        val cancelled = d?.events?.count { it.isFall && it.acknowledged && !it.smsSent } ?: 0
        val prevFalls = d?.prevEvents?.count { it.isFall && it.smsSent } ?: 0
        val profile = conn.profile

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                "Average heart rate", if (cur.has()) "${cur!!.avgBpm.roundToInt()}" else "--", "bpm",
                when {
                    profile.hrIsWarm -> "resting about ${profile.restHr.roundToInt()}"
                    cur.has() -> "range ${cur!!.minBpm.roundToInt()} to ${cur.maxBpm.roundToInt()}"
                    else -> "no readings yet"
                },
                if (cur.has() && prev.has()) signed(cur!!.avgBpm - prev!!.avgBpm) else "",
                worse = cur.has() && prev.has() && cur!!.avgBpm - prev!!.avgBpm >= 5f,
                modifier = Modifier.weight(1f),
            )
            Tile(
                "HRV (RMSSD)", if (cur.has()) "${cur!!.avgRmssd.roundToInt()}" else "--", "ms",
                if (cur.has()) "SDNN ${cur!!.avgSdnn.roundToInt()} ms" else "no readings yet",
                if (cur.has() && prev.has()) signed(cur!!.avgRmssd - prev!!.avgRmssd) else "",
                worse = cur.has() && prev.has() && cur!!.avgRmssd - prev!!.avgRmssd <= -3f,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                "Falls confirmed", if (d != null) "$falls" else "--", "",
                "$cancelled alert${if (cancelled == 1) "" else "s"} cancelled",
                if (d != null) signed((falls - prevFalls).toFloat()) else "",
                worse = falls > prevFalls,
                modifier = Modifier.weight(1f),
            )
            Tile(
                "Band worn", if (cur.has()) "${cur!!.wornPct.roundToInt()}%" else "--", "",
                "of the $periodWord",
                if (cur.has() && prev.has()) signed(cur!!.wornPct - prev!!.wornPct) else "",
                worse = cur.has() && prev.has() && cur!!.wornPct - prev!!.wornPct <= -10f,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Text("Heart rate, last ${period.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val hr = d?.vitals?.map { it.bpm }?.filter { it > 0f }.orEmpty()
            if (hr.size < 2) {
                Text(
                    "No readings stored for this period yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val band = if (profile.hrIsWarm) {
                    (profile.restHr - 2 * profile.restHrSd)..(profile.restHr + 2 * profile.restHrSd)
                } else 50f..100f
                LineChart(downsample(hr, 300), height = 120.dp, band = band, yLabel = { "${it.roundToInt()}" })
                Text(
                    if (profile.hrIsWarm) "Shaded: this wearer's usual range" else "Shaded: 50 to 100 bpm (usual range still being learned)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Text("More measures", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            MeasureRow("Measure", "Value", "Change", header = true)
            MeasureRow(
                "Max heart rate", if (cur.has()) "${cur!!.maxBpm.roundToInt()} bpm" else "--",
                if (cur.has() && prev.has()) signed(cur!!.maxBpm - prev!!.maxBpm) else "-",
            )
            MeasureRow(
                "HRV, SDNN", if (cur.has()) "${cur!!.avgSdnn.roundToInt()} ms" else "--",
                if (cur.has() && prev.has()) signed(cur!!.avgSdnn - prev!!.avgSdnn) else "-",
            )
            MeasureRow(
                "HRV, pNN50", if (cur.has()) "${cur!!.avgPnn50.roundToInt()}%" else "--",
                if (cur.has() && prev.has()) signed(cur!!.avgPnn50 - prev!!.avgPnn50) else "-",
            )
            val heat = d?.events?.count { it.isHeat } ?: 0
            val prevHeat = d?.prevEvents?.count { it.isHeat } ?: 0
            MeasureRow("Heat alerts", if (d != null) "$heat" else "--", if (d != null) signed((heat - prevHeat).toFloat()) else "-")
            val sos = d?.events?.count { it.isSos } ?: 0
            val prevSos = d?.prevEvents?.count { it.isSos } ?: 0
            MeasureRow("Help requests", if (d != null) "$sos" else "--", if (d != null) signed((sos - prevSos).toFloat()) else "-")
            MeasureRow("Lowest band battery", d?.minBattery?.let { "$it%" } ?: "--", "-", last = true)
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Events", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenEvents) { Text("See all") }
            }
            val shown = d?.events?.take(8).orEmpty()
            if (shown.isEmpty()) {
                Text(
                    "No events in this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val fmt = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
                EventRow("When", "Event", "Source", "Score", header = true, colour = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.forEachIndexed { i, e ->
                    val (label, tone) = eventLabel(e)
                    EventRow(
                        fmt.format(Date(e.occurredAt)), label, sourceLabel(e.source),
                        if (e.score > 0f) "%.2f".format(e.score) else "-",
                        colour = if (tone == Tone.BAD) LocalStatusPalette.current.bad else MaterialTheme.colorScheme.onSurface,
                        last = i == shown.lastIndex,
                    )
                }
                shown.firstOrNull { it.isFall && it.smsSent }?.let { e ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${fmt.format(Date(e.occurredAt))}: HR ${e.hrBpm.roundToInt()} bpm" +
                            (if (e.gaitDeviation != 0f) " · gait %+.1f SD".format(e.gaitDeviation) else "") +
                            " · caregiver texted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Fall alert at %.2f, two windows in a row · personal threshold %.2f".format(ModelSpec.THRESHOLD, profile.threshold()) +
                if (!profile.isWarm) " · learning this wearer, ${(profile.learningProgress * 100).roundToInt()}%" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
    }
}

private fun eventLabel(e: EventRecord): Pair<String, Tone> = when {
    e.isSos -> (if (e.acknowledged && !e.smsSent) "Help, cancelled" else "Help request") to Tone.BAD
    e.isHeat -> (if (e.kind == "HEAT_RED") "Heat, red" else "Heat, orange") to Tone.WARN
    e.smsSent -> "Fall, confirmed" to Tone.BAD
    e.acknowledged -> "Fall, cancelled" to Tone.NORMAL
    else -> "Possible fall" to Tone.WARN
}

private fun downsample(values: List<Float>, max: Int): List<Float> {
    if (values.size <= max) return values
    val step = values.size.toFloat() / max
    return List(max) { i ->
        val a = (i * step).toInt()
        val b = ((i + 1) * step).toInt().coerceAtMost(values.size).coerceAtLeast(a + 1)
        values.subList(a, b).average().toFloat()
    }
}

@Composable
private fun PeriodSwitch(selected: Period, onPick: (Period) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Period.entries.forEach { p ->
            val on = p == selected
            Surface(
                onClick = { onPick(p) },
                shape = RoundedCornerShape(9.dp),
                color = if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                shadowElevation = if (on) 1.dp else 0.dp,
                modifier = Modifier.weight(1f).height(40.dp).semantics { role = Role.Tab },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        p.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(
    label: String,
    value: String,
    unit: String,
    sub: String,
    delta: String,
    worse: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            Row {
                Text(
                    sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 2,
                )
                if (delta.isNotEmpty()) {
                    Text(
                        delta,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (worse) FontWeight.Bold else FontWeight.Normal,
                        color = if (worse) Color(0xFFC2410C) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MeasureRow(label: String, value: String, change: String, header: Boolean = false, last: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(label, style = style, color = if (header) muted else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(value, style = style, fontWeight = if (header) FontWeight.Normal else FontWeight.SemiBold,
                color = if (header) muted else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
            Text(change, style = style, color = muted, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EventRow(
    whenText: String,
    event: String,
    source: String,
    score: String,
    header: Boolean = false,
    colour: Color,
    last: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(whenText, style = style, color = if (header) muted else MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(82.dp))
            Text(event, style = style, color = colour, fontWeight = if (header) FontWeight.Normal else FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(source, style = style, color = if (header) muted else MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(78.dp))
            Text(score, style = style, color = if (header) muted else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
