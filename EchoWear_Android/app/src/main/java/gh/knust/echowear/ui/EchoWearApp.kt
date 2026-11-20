package gh.knust.echowear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.alert.AppRole
import gh.knust.echowear.alert.PendingAlert
import gh.knust.echowear.ble.AlertLevel
import gh.knust.echowear.service.DeviceConnection
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.components.BadgeTone
import gh.knust.echowear.ui.components.StatusBadge
import gh.knust.echowear.ui.screens.AnalyticsScreen
import gh.knust.echowear.ui.screens.DevicesScreen
import gh.knust.echowear.ui.screens.EventsScreen
import gh.knust.echowear.ui.screens.HeatScreen
import gh.knust.echowear.ui.screens.LocationScreen
import gh.knust.echowear.ui.screens.MotionScreen
import gh.knust.echowear.ui.screens.PairingScreen
import gh.knust.echowear.ui.screens.ReportScreen
import gh.knust.echowear.ui.screens.RolePicker
import gh.knust.echowear.ui.screens.SecurityScreen
import gh.knust.echowear.ui.screens.SettingsScreen
import gh.knust.echowear.ui.screens.VitalsScreen
import gh.knust.echowear.ui.screens.linkLabel
import gh.knust.echowear.ui.screens.linkTone
import gh.knust.echowear.ui.theme.MinTouchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every destination in the app, and which of them a role sees first.
 *
 * A role does not lock anything away - everything stays reachable from the
 * overflow menu. What it changes is what a person is shown first, which on a
 * phone handed to someone in a hurry is most of the battle.
 */
internal enum class Dest(
    val label: String,
    val icon: ImageVector,
    val needsDevice: Boolean = true,
) {
    PATIENTS("Patients", Icons.Filled.People, needsDevice = false),
    VITALS("Vitals", Icons.Filled.MonitorHeart),
    HEAT("Heat", Icons.Filled.WbSunny),
    MOTION("Motion", Icons.Filled.Timeline),
    LOCATION("Location", Icons.Filled.Place),
    EVENTS("Events", Icons.Filled.History, needsDevice = false),
    TRENDS("Trends", Icons.Filled.ShowChart),
    REPORT("Report", Icons.Filled.Description),
    SECURITY("Security", Icons.Filled.Lock),
    SETTINGS("Settings", Icons.Filled.Settings, needsDevice = false),
}

private fun primaryFor(role: AppRole, deviceCount: Int): List<Dest> = when (role) {
    // One wearer with one wearable should never be made to navigate a
    // roster to see their own heart rate.
    AppRole.WEARER -> buildList {
        if (deviceCount > 1) add(Dest.PATIENTS)
        add(Dest.VITALS)
        // Heat replaced Location in the bar (Challenge 07); Location stays in
        // the overflow menu, one tap away.
        add(Dest.HEAT)
        add(Dest.EVENTS)
        add(Dest.SETTINGS)
    }
    AppRole.CLINICIAN -> listOf(
        Dest.PATIENTS, Dest.VITALS, Dest.TRENDS, Dest.REPORT, Dest.SETTINGS,
    )
    // The supervisor's role on a work site: the crew roster and heat first.
    AppRole.ADMIN -> listOf(
        Dest.PATIENTS, Dest.VITALS, Dest.HEAT, Dest.EVENTS, Dest.SETTINGS,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoWearApp(modifier: Modifier = Modifier, onRequestPermissions: () -> Unit) {
    val running by MonitorService.running.collectAsStateWithLifecycle()
    val service = running
    if (service == null) {
        StartingUp(modifier, onRequestPermissions)
        return
    }

    val scope = rememberCoroutineScope()
    val devices by service.devices.collectAsStateWithLifecycle()
    val selectedId by service.selected.collectAsStateWithLifecycle()
    val pending by service.alerts.pending.collectAsStateWithLifecycle()
    val role by service.settings.role.collectAsStateWithLifecycle(AppRole.WEARER)
    val largeText by service.settings.largeText.collectAsStateWithLifecycle(false)
    val highContrast by service.settings.highContrast.collectAsStateWithLifecycle(false)
    val roleChosen by service.settings.roleChosen.collectAsStateWithLifecycle(initialValue = null)

    var dest by remember { mutableStateOf(Dest.VITALS) }
    var pairing by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var patientMenuOpen by remember { mutableStateOf(false) }

    val conn: DeviceConnection? = devices.firstOrNull { it.deviceId == selectedId }
        ?: devices.firstOrNull()

    // First screen: who is using the app. No sign-in in this testing build; the
    // choice is a preference and "Switch role" clears it. While the settings are
    // still loading nothing is drawn, so the picker does not flash on every start.
    if (roleChosen == null) return
    if (roleChosen == false) {
        RolePicker(onPick = { r -> scope.launch { service.settings.setRole(r) } }, modifier = modifier)
        return
    }

    if (pairing) {
        PairingScreen(service, onFinished = { pairing = false }, modifier = modifier)
        return
    }

    // Nothing paired yet: the only useful thing this app can do is pair
    // something, so it says that rather than showing five empty screens.
    if (devices.isEmpty()) {
        NothingPaired(
            modifier,
            onAdd = { pairing = true },
            onSettings = { dest = Dest.SETTINGS },
        )
        return
    }

    // Patient / caregiver and doctor get the minimal role homes (UI option A + C).
    // The administrator (work-site supervisor) keeps the classic tabbed layout below.
    if (role == AppRole.WEARER || role == AppRole.CLINICIAN) {
        val current = conn ?: return
        Box(modifier.fillMaxSize()) {
            RoleShell(
                role, service, devices, current,
                onSwitchRole = { scope.launch { service.settings.clearRole() } },
                onPair = { pairing = true },
                onRequestPermissions = onRequestPermissions,
            )
            AnimatedVisibility(
                visible = pending.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                AlertOverlay(pending, service)
            }
        }
        return
    }

    val primary = primaryFor(role, devices.size)
    val secondary = Dest.entries.filter { it !in primary }

    // Switching role can strand the user on a screen that role's bottom bar
    // no longer offers, with no obvious way back.
    LaunchedEffect(role, devices.size) {
        if (dest !in primary && dest !in secondary) dest = primary.first()
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(dest.label, style = MaterialTheme.typography.titleMedium)
                        if (conn != null && devices.size > 1) {
                            Text(
                                conn.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (devices.size > 1) {
                        Box {
                            IconButton(onClick = { patientMenuOpen = true }) {
                                Icon(Icons.Filled.Watch, "Choose a patient")
                            }
                            DropdownMenu(
                                expanded = patientMenuOpen,
                                onDismissRequest = { patientMenuOpen = false },
                            ) {
                                devices.forEach { d ->
                                    PatientMenuItem(d) {
                                        service.select(d.deviceId)
                                        patientMenuOpen = false
                                    }
                                }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "More")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            secondary.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text(d.label) },
                                    leadingIcon = { Icon(d.icon, null) },
                                    enabled = !d.needsDevice || conn != null,
                                    onClick = { dest = d; overflowOpen = false },
                                )
                            }
                            HorizontalDivider()
                            // The two accessibility switches people reach for
                            // most, one tap from anywhere. The rest live in
                            // Settings.
                            DropdownMenuItem(
                                text = {
                                    Text(if (largeText) "Normal text" else "Larger text")
                                },
                                leadingIcon = { Icon(Icons.Filled.Accessibility, null) },
                                onClick = {
                                    scope.launch { service.settings.setLargeText(!largeText) }
                                    overflowOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (highContrast) {
                                            "Normal contrast"
                                        } else {
                                            "High contrast"
                                        }
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Accessibility, null) },
                                onClick = {
                                    scope.launch {
                                        service.settings.setHighContrast(!highContrast)
                                    }
                                    overflowOpen = false
                                },
                            )
                        }
                    }
                },
            )

            Box(Modifier.weight(1f)) {
                DestContent(
                    dest, service, conn,
                    onAddDevice = { pairing = true },
                    onOpenDevice = { id -> service.select(id); dest = Dest.VITALS },
                    onRequestPermissions = onRequestPermissions,
                )
            }

            NavigationBar {
                primary.forEach { d ->
                    NavigationBarItem(
                        selected = dest == d,
                        onClick = { dest = d },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                        enabled = !d.needsDevice || conn != null,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = pending.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            AlertOverlay(pending, service)
        }
    }
}

/** One destination's screen, shared by the classic tabbed layout and the role homes' menu. */
@Composable
internal fun DestContent(
    dest: Dest,
    service: MonitorService,
    conn: DeviceConnection?,
    onAddDevice: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onRequestPermissions: () -> Unit,
) {
    when (dest) {
        Dest.PATIENTS -> DevicesScreen(service, onAddDevice = onAddDevice, onOpenDevice = onOpenDevice)
        Dest.VITALS -> conn?.let { VitalsScreen(service, it) }
        Dest.HEAT -> conn?.let { HeatScreen(service, it) }
        Dest.MOTION -> conn?.let { MotionScreen(it) }
        Dest.LOCATION -> conn?.let { LocationScreen(it) }
        Dest.EVENTS -> EventsScreen(conn)
        Dest.TRENDS -> AnalyticsScreen(conn)
        Dest.REPORT -> ReportScreen(service, conn)
        Dest.SECURITY -> conn?.let { SecurityScreen(it) }
        Dest.SETTINGS -> SettingsScreen(service, onRequestPermissions)
    }
}

@Composable
private fun PatientMenuItem(conn: DeviceConnection, onClick: () -> Unit) {
    val link by conn.client.linkState.collectAsStateWithLifecycle()
    DropdownMenuItem(
        text = {
            Column {
                Text(conn.displayName)
                Text(
                    conn.record.patientRef.ifBlank { conn.record.serial },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingIcon = { StatusBadge(linkLabel(link), linkTone(link)) },
        onClick = onClick,
    )
}

@Composable
private fun StartingUp(modifier: Modifier, onRequestPermissions: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Starting the monitoring service", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "HeatGuard needs Bluetooth, notification and SMS permissions to " +
                "watch a wearable and reach a caregiver.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequestPermissions) { Text("Grant permissions") }
    }
}

@Composable
private fun NothingPaired(
    modifier: Modifier,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Watch, null,
            Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("No wearable paired", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Pair a HeatGuard wearable with this phone to start monitoring. " +
                "You will need the six-digit code the wearable shows on its " +
                "screen, or the one printed on the case if it has no screen.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("Pair a wearable")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Set up the caregiver first")
        }
    }
}

/**
 * The countdown, over everything else.
 *
 * With several patients there can be more than one at a time. They are
 * stacked most-severe first rather than merged, because "two people have
 * fallen" is a different situation from "someone has fallen", and the screen
 * must not quietly round it down to the second.
 */
@Composable
private fun AlertOverlay(alerts: List<PendingAlert>, service: MonitorService) {
    if (alerts.isEmpty()) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (alerts.size > 1) {
                Text(
                    "${alerts.size} alerts are counting down",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(12.dp))
            }

            alerts.sortedByDescending { it.level.code }.forEach { alert ->
                AlertCard(alert, now) { service.cancelAlert(alert.deviceId) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AlertCard(alert: PendingAlert, now: Long, onCancel: () -> Unit) {
    val seconds = alert.remainingSeconds(now)
    val fraction = (alert.remainingMs(now).toFloat() / alert.countdownMs).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            alert.patientName.ifBlank { "The wearer" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when (alert.level) {
                AlertLevel.MANUAL_SOS -> "SOS"
                AlertLevel.CARDIAC_FALL -> "Fall with a cardiac response"
                AlertLevel.FALL_CONFIRMED -> "Confirmed fall"
                else -> "Possible fall"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "$seconds",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            "seconds until the caregiver is contacted",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBadge(alert.source, BadgeTone.NEUTRAL)
            if (alert.hrBpm > 0) {
                StatusBadge("${alert.hrBpm.toInt()} bpm", BadgeTone.WARN)
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth().height(MinTouchTarget),
        ) {
            Text("I'm OK - cancel", style = MaterialTheme.typography.titleMedium)
        }
    }
}
