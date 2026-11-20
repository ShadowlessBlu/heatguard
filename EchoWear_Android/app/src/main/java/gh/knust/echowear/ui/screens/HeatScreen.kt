package gh.knust.echowear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import gh.knust.echowear.heat.DemoMinute
import gh.knust.echowear.heat.DemoShifts
import gh.knust.echowear.heat.HeatAssessment
import kotlinx.coroutines.delay
import gh.knust.echowear.heat.HeatLevel
import gh.knust.echowear.heat.HeatProfileStore
import gh.knust.echowear.heat.SiteReading
import gh.knust.echowear.heat.WorkClass
import gh.knust.echowear.heat.Workload
import gh.knust.echowear.service.DeviceConnection
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.components.BadgeTone
import gh.knust.echowear.ui.components.EmptyState
import gh.knust.echowear.ui.components.InfoRow
import gh.knust.echowear.ui.components.SectionHeader
import gh.knust.echowear.ui.components.StatusBadge
import gh.knust.echowear.ui.theme.LocalStatusPalette
import gh.knust.echowear.ui.theme.MinTouchTarget
import kotlin.math.roundToInt

private fun HeatLevel.tone() = when (this) {
    HeatLevel.GREEN -> BadgeTone.OK
    HeatLevel.YELLOW, HeatLevel.ORANGE -> BadgeTone.WARN
    HeatLevel.RED -> BadgeTone.BAD
}

/** Words as well as colour: the level must survive colour blindness and glare. */
private fun HeatLevel.word() = when (this) {
    HeatLevel.GREEN -> "GREEN - OK"
    HeatLevel.YELLOW -> "YELLOW - CAUTION"
    HeatLevel.ORANGE -> "ORANGE - STOP AND COOL"
    HeatLevel.RED -> "RED - DANGER"
}

private fun ageText(ms: Long): String {
    val m = ((System.currentTimeMillis() - ms) / 60_000).toInt()
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        else -> "${m / 60} h ${m % 60} min ago"
    }
}

/**
 * Heat strain for one outdoor worker (Future Health Challenge 07).
 *
 * Top to bottom in the order someone glances at it: the level and the one
 * thing to do; why; the numbers behind it; then the site and worker settings.
 */
@Composable
fun HeatScreen(service: MonitorService, conn: DeviceConnection, modifier: Modifier = Modifier) {
    val assessment by conn.heat.assessment.collectAsStateWithLifecycle()
    val site by service.site.current.collectAsStateWithLifecycle()
    val enabled by conn.heatEnabledFlow.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // No heading here: the app bar already reads "Heat - <wearer>".
        if (!enabled) {
            EmptyState("Heat monitoring is off for this wearer. Turn it on under Worker settings.")
        } else {
            LevelBanner(assessment)
            assessment?.let { a ->
                if (conn.heat.isLatched) {
                    Button(
                        onClick = { service.acknowledgeHeat(conn.deviceId) },
                        modifier = Modifier.fillMaxWidth().height(MinTouchTarget),
                    ) { Text("Collapse has been handled - clear the alarm") }
                }
                Reasons(a)
                Numbers(a)
            }
        }

        DemoCard()
        SiteCard(service, site)
        WorkerCard(service, conn, enabled)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LevelBanner(a: HeatAssessment?) {
    val palette = LocalStatusPalette.current
    if (a == null) {
        EmptyState(
            "The heat check starts after the first full minute of readings from the wearable."
        )
        return
    }
    val colour = when (a.level) {
        HeatLevel.GREEN -> palette.ok
        HeatLevel.YELLOW, HeatLevel.ORANGE -> palette.warn
        HeatLevel.RED -> palette.bad
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colour.copy(alpha = 0.16f))
            .padding(16.dp)
            .semantics { contentDescription = "Heat level ${a.level.word()}. ${a.actionText}" },
    ) {
        Text(a.level.word(), style = MaterialTheme.typography.titleLarge,
             fontWeight = FontWeight.Bold, color = colour)
        Spacer(Modifier.height(6.dp))
        Text(a.actionText, style = MaterialTheme.typography.titleMedium)
        val wm = a.workMinPerHour
        if (a.action == "work_rest" && wm != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Each hour: work $wm min, rest ${60 - wm} min in shade",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Updated ${ageText(a.at)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Reasons(a: HeatAssessment) {
    if (a.reasons.isEmpty()) return
    Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(14.dp)) {
            Text("Why", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            a.explain().forEach {
                Text("•  $it", style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun Numbers(a: HeatAssessment) {
    Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(14.dp)) {
            Text("Behind the level", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            // ±: the filter's own spread plus ECTemp's published ~0.3 °C error.
            InfoRow("Core temperature (estimate)", a.ctEst?.let { ct ->
                "%.1f °C ± %.1f".format(ct, (a.ctSd ?: 0.0) + 0.3)
            } ?: "not measured")
            InfoRow("Heat strain (0-10)", a.psi?.let { "%.1f".format(it) } ?: "not measured")
            InfoRow("Heart rate (minute median)", a.hr?.let { "${it.roundToInt()} bpm" } ?: "not measured")
            InfoRow("Site heat, WBGT incl. clothing", a.wbgtEff?.let { "%.1f °C".format(it) } ?: "not measured")
            InfoRow("Safe limit for this work", a.wbgtLimit?.let { "%.1f °C".format(it) } ?: "not measured")
            InfoRow("Workload", a.workClass.name.lowercase().replace('_', ' '))
            Spacer(Modifier.height(6.dp))
            Text(
                "Core temperature is estimated from heart rate (ECTemp). It is " +
                    "not a thermometer reading and has not been validated for " +
                    "this workforce. Heat strain uses the Physiological Strain Index.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Replays a simulated hot shift through a fresh copy of the real engine, for
 * demonstrations without a band. Clearly labelled: nothing here is live, and
 * nothing here touches the live engine, alerts or stored events.
 */
@Composable
private fun DemoCard() {
    val context = LocalContext.current
    val shifts = remember { runCatching { DemoShifts.load(context) }.getOrDefault(emptyList()) }
    if (shifts.isEmpty()) return
    var pick by remember { mutableStateOf(shifts.first().key) }
    var playing by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Pair<DemoMinute, HeatAssessment>?>(null) }
    var firstRed by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playing, pick) {
        if (!playing) return@LaunchedEffect
        val replay = DemoShifts.Replay(shifts.first { it.key == pick })
        firstRed = null
        while (!replay.done) {
            val f = replay.step() ?: break
            frame = f
            if (firstRed == null && f.second.level == HeatLevel.RED) firstRed = f.first.clock
            delay(150)          // ~6.7 simulated minutes per second
        }
        playing = false
    }

    SectionHeader("Demo shift", subtitle = "Simulated worker, replayed through the real engine")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            StatusBadge("SIMULATED - not live data", BadgeTone.INFO)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shifts.forEach { s ->
                    FilterChip(
                        selected = pick == s.key,
                        enabled = !playing,
                        onClick = { pick = s.key; frame = null; firstRed = null },
                        label = { Text("Shift ${s.key}") },
                    )
                }
            }
            Text(
                shifts.first { it.key == pick }.title +
                    ". Hot morning, 06:00-12:30, moderate work.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { playing = !playing }, modifier = Modifier.height(MinTouchTarget)) {
                Text(if (playing) "Stop" else "Play demo shift")
            }

            frame?.let { (m, a) ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "${m.clock}  -  ${if (m.working) "working" else "resting in shade"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                LevelBanner(a)
                Spacer(Modifier.height(6.dp))
                InfoRow("Core temp, estimated from heart rate",
                        a.ctEst?.let { "%.1f °C".format(it) } ?: "not measured")
                InfoRow("Core temp, simulated truth", "%.1f °C".format(m.ctTrue))
                InfoRow("Heart rate", a.hr?.let { "${it.roundToInt()} bpm" } ?: "not measured")
                InfoRow("Site WBGT", a.wbgtEff?.let { "%.1f °C".format(it) } ?: "not measured")
                firstRed?.let { InfoRow("First red warning", it) }
                if (a.reasons.contains("collapse_in_heat")) {
                    Text(
                        "Simulated heat collapse: the fall detector's alert, during " +
                            "heat strain, latches an emergency until someone confirms it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SiteCard(service: MonitorService, site: SiteReading?) {
    val weatherOn by service.site.weatherEnabled.collectAsStateWithLifecycle()
    var ta by remember { mutableStateOf("") }
    var rh by remember { mutableStateOf("") }
    var tg by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    SectionHeader("Site conditions", subtitle = "Shared by every worker on this phone")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (site == null) {
                StatusBadge("Not measured", BadgeTone.WARN)
                Spacer(Modifier.height(6.dp))
                Text(
                    "No site heat reading. Enter one below, switch on weather, " +
                        "or bring the site beacon in range.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                InfoRow("Source", site.source.label)
                InfoRow("Measured", ageText(site.at))
                site.taC?.let { InfoRow("Air temperature", "%.1f °C".format(it)) }
                site.rhPct?.let { InfoRow("Humidity", "%.0f %%".format(it)) }
                site.tgC?.let { InfoRow("Black globe", "%.1f °C".format(it)) }
                site.wbgtEstimate?.let { InfoRow("WBGT (estimate)", "%.1f °C".format(it)) }
            }

            Spacer(Modifier.height(12.dp))
            Text("Enter a reading", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Air °C", ta, { ta = it }, Modifier.weight(1f))
                NumberField("RH %", rh, { rh = it }, Modifier.weight(1f))
                NumberField("Globe °C", tg, { tg = it }, Modifier.weight(1f))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val t = ta.toDoubleOrNull()
                    val h = rh.toDoubleOrNull()
                    val g = tg.takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    error = when {
                        t == null || t !in -10.0..60.0 -> "Air temperature must be -10 to 60 °C."
                        h == null || h !in 0.0..100.0 -> "Humidity must be 0 to 100 %."
                        tg.isNotBlank() && (g == null || g !in -10.0..90.0) -> "Globe must be -10 to 90 °C, or blank."
                        else -> null
                    }
                    if (error == null) service.site.setManual(t!!, h!!, g)
                }) { Text("Use this reading") }
                TextButton(onClick = { service.site.clearManual() }) { Text("Clear") }
            }
            Text(
                "A typed reading is used for 4 hours. A globe reading (sun-exposed " +
                    "black ball) makes the estimate much better in direct sun.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use weather when nothing else", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Sends the site's position, rounded to about 1 km, to " +
                            "Open-Meteo. No worker data is sent. Shade air " +
                            "temperature misses the sun's heat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = weatherOn, onCheckedChange = { service.site.setWeatherEnabled(it) })
            }
        }
    }
}

@Composable
private fun WorkerCard(service: MonitorService, conn: DeviceConnection, enabled: Boolean) {
    val start = remember(conn.deviceId) { service.heatProfiles.get(conn.deviceId) }
    var on by remember(conn.deviceId) { mutableStateOf(enabled) }
    var age by remember(conn.deviceId) { mutableStateOf(start.profile.age?.toString() ?: "") }
    var days by remember(conn.deviceId) {
        mutableStateOf(start.profile.daysAcclimatized.roundToInt().toString())
    }
    var work by remember(conn.deviceId) { mutableStateOf(start.profile.declaredWork) }
    var clothing by remember(conn.deviceId) { mutableStateOf(start.profile.clothing) }
    var hood by remember(conn.deviceId) { mutableStateOf(start.profile.hood) }
    var saved by remember { mutableStateOf(false) }

    SectionHeader("Worker settings", subtitle = "Stored on this phone only")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Heat monitoring", style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { on = it; saved = false })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Age", age, { age = it; saved = false }, Modifier.weight(1f))
                NumberField("Days in this heat", days, { days = it; saved = false }, Modifier.weight(1f))
            }
            Text(
                "New to the heat (under 7 days) gets stricter limits. Someone back " +
                    "from a week or more away counts as new again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("Usual task", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(WorkClass.LIGHT, WorkClass.MODERATE, WorkClass.HEAVY, WorkClass.VERY_HEAVY).forEach { wc ->
                    FilterChip(
                        selected = work == wc,
                        onClick = { work = wc; saved = false },
                        label = { Text(wc.name.lowercase().replace('_', ' ')) },
                    )
                }
            }
            Text(
                "Light: inspecting, driving. Moderate: steady walking, light lifting. " +
                    "Heavy: shovelling, carrying. The wrist can only raise this, never lower it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("Clothing", style = MaterialTheme.typography.titleSmall)
            Column {
                listOf(
                    "work_clothes" to "Work clothes",
                    "double_layer_woven" to "Double-layer coveralls",
                    "polyolefin_coveralls" to "Polyolefin coveralls",
                    "vapour_barrier_coveralls" to "Vapour-barrier suit",
                ).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { (key, label) ->
                            FilterChip(
                                selected = clothing == key,
                                onClick = { clothing = key; saved = false },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hood", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = hood, onCheckedChange = { hood = it; saved = false })
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val p = start.profile.copy(
                        age = age.toIntOrNull()?.takeIf { it in 14..90 },
                        daysAcclimatized = (days.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 365.0),
                        declaredWork = work,
                        clothing = clothing.takeIf { it in Workload.CLOTHING_CAV } ?: "work_clothes",
                        hood = hood,
                    )
                    service.updateHeatProfile(conn.deviceId, HeatProfileStore.Entry(on, p))
                    saved = true
                }) { Text(if (saved) "Saved" else "Save") }
                OutlinedButton(onClick = { service.startHeatShift(conn.deviceId) }) {
                    Text("Start a new shift")
                }
            }
            Text(
                "A new shift relearns the resting heart rate from the first 5 minutes. " +
                    "It also starts by itself after 3 hours without readings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        // Many keyboards offer ',' as the decimal key; accept it as '.'
        // rather than silently turning "38,5" into "385".
        onValueChange = { v ->
            onChange(v.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }.take(6))
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
