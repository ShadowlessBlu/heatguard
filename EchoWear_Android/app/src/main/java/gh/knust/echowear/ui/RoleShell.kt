package gh.knust.echowear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.alert.AppRole
import gh.knust.echowear.ble.LinkState
import gh.knust.echowear.service.DeviceConnection
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.screens.CareHome
import gh.knust.echowear.ui.screens.DoctorHome
import gh.knust.echowear.ui.screens.linkLabel
import gh.knust.echowear.ui.theme.LocalStatusPalette
import kotlinx.coroutines.launch

/**
 * The frame round the two role homes (UI option "A + C").
 *
 * A slim top row instead of an app bar and a bottom bar: "Switch role" (there is no sign-in yet),
 * the band's status for patients and caregivers, and a menu that keeps every existing screen one tap
 * away. Opening one of those screens swaps the home out and shows "Back".
 */
@Composable
internal fun RoleShell(
    role: AppRole,
    service: MonitorService,
    devices: List<DeviceConnection>,
    conn: DeviceConnection,
    onSwitchRole: () -> Unit,
    onPair: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val largeText by service.settings.largeText.collectAsStateWithLifecycle(false)
    val highContrast by service.settings.highContrast.collectAsStateWithLifecycle(false)
    var detail by remember { mutableStateOf<Dest?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = detail != null) { detail = null }

    val menu: List<Dest> = when (role) {
        AppRole.CLINICIAN -> listOf(
            Dest.PATIENTS, Dest.VITALS, Dest.TRENDS, Dest.EVENTS, Dest.REPORT,
            Dest.MOTION, Dest.LOCATION, Dest.SECURITY, Dest.SETTINGS,
        )
        else -> buildList {
            if (devices.size > 1) add(Dest.PATIENTS)
            add(Dest.VITALS); add(Dest.EVENTS); add(Dest.LOCATION); add(Dest.HEAT); add(Dest.SETTINGS)
        }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val open = detail
            if (open != null) {
                TextButton(onClick = { detail = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Back")
                }
                Text(open.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
            } else {
                TextButton(onClick = onSwitchRole) { Text("‹ Switch role") }
            }
            Spacer(Modifier.weight(1f))
            if (open == null && role != AppRole.CLINICIAN) BandChip(conn)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(if (d == Dest.VITALS) "Live readings" else d.label) },
                            leadingIcon = { Icon(d.icon, null) },
                            onClick = { detail = d; menuOpen = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Pair a wearable") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { menuOpen = false; onPair() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (largeText) "Normal text" else "Larger text") },
                        leadingIcon = { Icon(Icons.Filled.Accessibility, null) },
                        onClick = { scope.launch { service.settings.setLargeText(!largeText) }; menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (highContrast) "Normal contrast" else "High contrast") },
                        leadingIcon = { Icon(Icons.Filled.Accessibility, null) },
                        onClick = { scope.launch { service.settings.setHighContrast(!highContrast) }; menuOpen = false },
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            val open = detail
            when {
                open != null -> DestContent(
                    open, service, conn,
                    onAddDevice = onPair,
                    onOpenDevice = { id -> service.select(id); detail = null },
                    onRequestPermissions = onRequestPermissions,
                )
                role == AppRole.CLINICIAN -> DoctorHome(
                    service, conn, devices,
                    onOpenEvents = { detail = Dest.EVENTS },
                    onOpenReport = { detail = Dest.REPORT },
                )
                else -> CareHome(service, conn, onOpenSettings = { detail = Dest.SETTINGS })
            }
        }
    }
}

@Composable
private fun BandChip(conn: DeviceConnection) {
    val link by conn.client.linkState.collectAsStateWithLifecycle()
    val t by conn.lastTelemetry.collectAsStateWithLifecycle()
    val palette = LocalStatusPalette.current
    val ready = link == LinkState.READY
    val battery = t?.batteryPct?.takeIf { it > 0 }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (ready) palette.ok else palette.idle))
            Text(
                if (ready) "Band connected" + (battery?.let { " · $it%" } ?: "") else "Band: ${linkLabel(link)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
