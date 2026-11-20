package gh.knust.echowear.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.ble.LinkState
import gh.knust.echowear.pairing.PairedDevice
import gh.knust.echowear.service.DeviceConnection
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.components.BadgeTone
import gh.knust.echowear.ui.components.EmptyState
import gh.knust.echowear.ui.components.InfoRow
import gh.knust.echowear.ui.components.SectionHeader
import gh.knust.echowear.ui.components.StatusBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SEEN_FMT = SimpleDateFormat("dd MMM HH:mm", Locale.UK)

fun linkTone(state: LinkState): BadgeTone = when (state) {
    LinkState.READY -> BadgeTone.OK
    LinkState.NEEDS_PAIRING -> BadgeTone.BAD
    LinkState.DISCONNECTED, LinkState.IDLE -> BadgeTone.NEUTRAL
    else -> BadgeTone.WARN
}

fun linkLabel(state: LinkState): String = when (state) {
    LinkState.IDLE -> "Idle"
    LinkState.CONNECTING -> "Connecting"
    LinkState.DISCOVERING -> "Discovering"
    LinkState.KEY_EXCHANGE -> "Securing"
    LinkState.AUTHENTICATING -> "Authenticating"
    LinkState.READY -> "Live"
    LinkState.NEEDS_PAIRING -> "Needs pairing"
    LinkState.DISCONNECTED -> "Offline"
}

/**
 * The roster.
 *
 * With more than one wearable this is the screen that matters most: it has to
 * answer "is everyone covered right now" in one glance, which means link
 * state and last-reading age are more prominent than any vital sign. A
 * wearable that is offline is a patient who is not being watched, and that
 * has to be louder than a heart rate that is merely high.
 */
@Composable
fun DevicesScreen(
    service: MonitorService,
    onAddDevice: () -> Unit,
    onOpenDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val devices by service.devices.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PairedDevice?>(null) }
    var removing by remember { mutableStateOf<PairedDevice?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionHeader(
            "Wearables",
            subtitle = if (devices.isEmpty()) null else {
                val live = devices.count { it.isConnected }
                "$live of ${devices.size} connected"
            },
            trailing = {
                IconButton(onClick = onAddDevice) {
                    Icon(Icons.Filled.Add, "Add a wearable")
                }
            },
        )

        if (devices.isEmpty()) {
            EmptyState(
                "No wearables are paired with this phone yet. " +
                    "Add one to start monitoring."
            )
            Button(onClick = onAddDevice, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add a wearable")
            }
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            devices.forEach { conn ->
                DeviceCard(
                    conn = conn,
                    onOpen = { onOpenDevice(conn.deviceId) },
                    onEdit = { editing = conn.record },
                    onRemove = { removing = conn.record },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAddDevice, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add another wearable")
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { record ->
        EditPatientDialog(
            record = record,
            onDismiss = { editing = null },
            onSave = { name, ref, label, cgName, cgPhone, notes ->
                service.updatePatient(record.deviceId, name, ref, label, cgName, cgPhone, notes)
                editing = null
            },
        )
    }

    removing?.let { record ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Release ${record.displayName}?") },
            text = {
                Text(
                    "This phone will stop monitoring the wearable and will " +
                        "forget the key that proves it owns it. The wearable " +
                        "is told to release itself, so it can be paired to " +
                        "another phone straight away.\n\n" +
                        "Recorded vitals and events for this patient stay on " +
                        "the phone unless you clear them in Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    service.removeDevice(record.deviceId)
                    removing = null
                }) { Text("Release") }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DeviceCard(
    conn: DeviceConnection,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val link by conn.client.linkState.collectAsStateWithLifecycle()
    val telemetry by conn.lastTelemetry.collectAsStateWithLifecycle()
    val record = conn.record

    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Watch, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        record.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${record.patientRef.ifBlank { "no reference" }} · " +
                            "${record.serial} · ${record.type.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(linkLabel(link), linkTone(link))
            }

            Spacer(Modifier.height(10.dp))

            val t = telemetry
            if (t != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Metric("Heart rate", if (t.bpm > 0) "${t.bpm.toInt()}" else "--", "bpm")
                    Metric("Battery", "${t.batteryPct}", "%")
                    Metric("Worn", if (t.worn) "Yes" else "No")
                }
            } else {
                Text(
                    if (link == LinkState.NEEDS_PAIRING) {
                        "This phone can reach the wearable but cannot prove it " +
                            "owns it. Release and pair it again."
                    } else {
                        "No reading yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (record.lastSeenAt > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last reading ${SEEN_FMT.format(Date(record.lastSeenAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                TextButton(onClick = { conn.client.connect() }) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reconnect")
                }
                TextButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Release")
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String = "") {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold)
            if (unit.isNotBlank()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EditPatientDialog(
    record: PairedDevice,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(record.patientName) }
    var ref by remember { mutableStateOf(record.patientRef) }
    var label by remember { mutableStateOf(record.label) }
    var cgName by remember { mutableStateOf(record.caregiverName) }
    var cgPhone by remember { mutableStateOf(record.caregiverPhone) }
    var notes by remember { mutableStateOf(record.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit patient") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoRow("Wearable", record.serial)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    ref, { if (it.length <= 16) ref = it },
                    label = { Text("Patient reference") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    label, { if (it.length <= 24) label = it },
                    label = { Text("Device label") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    cgName, { cgName = it },
                    label = { Text("Caregiver name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    cgPhone, { cgPhone = it },
                    label = { Text("Caregiver phone") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    notes, { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name.trim(), ref.trim(), label.trim(),
                       cgName.trim(), cgPhone.trim(), notes.trim())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
