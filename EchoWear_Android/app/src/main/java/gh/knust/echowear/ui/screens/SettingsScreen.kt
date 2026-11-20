package gh.knust.echowear.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.alert.AppRole
import gh.knust.echowear.data.EchoWearDatabase
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.components.InfoRow
import gh.knust.echowear.ui.components.SectionHeader
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings, merged from SettingsPanel.tsx, UserSettings.tsx and the
 * accessibility context in the Bolt dashboard.
 *
 * Grouped by who the setting is for rather than by what subsystem it touches:
 * the person setting up a wearable for a relative reads this top to bottom
 * once, and "Escalation" then "Display" is the order they think in.
 */
@Composable
fun SettingsScreen(
    service: MonitorService,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = service.settings

    val caregiverName by settings.caregiverName.collectAsStateWithLifecycle("")
    val caregiverPhone by settings.caregiverPhone.collectAsStateWithLifecycle("")
    val wearerName by settings.wearerName.collectAsStateWithLifecycle("")
    val smsEnabled by settings.smsEnabled.collectAsStateWithLifecycle(true)
    val callEnabled by settings.callEnabled.collectAsStateWithLifecycle(false)
    val phoneMl by settings.phoneMlEnabled.collectAsStateWithLifecycle(true)
    val role by settings.role.collectAsStateWithLifecycle(AppRole.WEARER)
    val largeText by settings.largeText.collectAsStateWithLifecycle(false)
    val highContrast by settings.highContrast.collectAsStateWithLifecycle(false)
    val reduceMotion by settings.reduceMotion.collectAsStateWithLifecycle(false)
    val textScale by settings.textScale.collectAsStateWithLifecycle(1f)
    val clinician by settings.clinicianName.collectAsStateWithLifecycle("")
    val facility by settings.facility.collectAsStateWithLifecycle("")
    val lastSms by service.alerts.lastSmsResult.collectAsStateWithLifecycle()

    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        // ------------------------------------------------------ escalation
        SectionHeader(
            "Escalation",
            subtitle = "Who gets contacted when nobody cancels an alert",
        )
        PersistedTextField(
            stored = caregiverName,
            onCommit = { v -> scope.launch { settings.setCaregiverName(v) } },
            label = "Caregiver name",
        )
        PersistedTextField(
            stored = caregiverPhone,
            onCommit = { v -> scope.launch { settings.setCaregiverPhone(v) } },
            label = "Caregiver phone",
            supporting = "Used for any wearable that has no caregiver of its own. " +
                "Include the country code.",
            keyboardType = KeyboardType.Phone,
        )
        PersistedTextField(
            stored = wearerName,
            onCommit = { v -> scope.launch { settings.setWearerName(v) } },
            label = "Default wearer name",
            supporting = "Used in the SMS when a wearable has no patient name.",
        )

        Spacer(Modifier.height(10.dp))
        SettingSwitch(
            "Send an SMS",
            "The phone texts the caregiver when the countdown expires.",
            smsEnabled,
        ) { v -> scope.launch { settings.setSmsEnabled(v) } }
        SettingSwitch(
            "Open the dialler as well",
            "After the SMS, bring up the caregiver's number ready to call.",
            callEnabled,
        ) { v -> scope.launch { settings.setCallEnabled(v) } }

        lastSms?.let {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Last SMS attempt", style = MaterialTheme.typography.labelMedium)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ------------------------------------------------------- detection
        SectionHeader(
            "Detection",
            subtitle = "The wearable's own detector always runs; this is the second opinion",
        )
        SettingSwitch(
            "Run the classifier on this phone",
            "An INT8 1D-CNN scores the raw accelerometer stream. It catches " +
                "falls the wearable's threshold detector misses, at the cost " +
                "of the wearable streaming IMU data.",
            phoneMl,
        ) { v -> scope.launch { settings.setPhoneMlEnabled(v) } }

        // ----------------------------------------------------------- roles
        SectionHeader(
            "This phone is used by",
            subtitle = "Changes which screens are shown, not what is recorded",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppRole.entries.forEach { r ->
                FilterChip(
                    selected = role == r,
                    onClick = { scope.launch { settings.setRole(r) } },
                    label = { Text(r.label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when (role) {
                AppRole.WEARER ->
                    "One wearer, large controls, and the alert button always " +
                        "in reach."
                AppRole.CLINICIAN ->
                    "A patient roster, trends and the clinical summary."
                AppRole.ADMIN ->
                    "Everything, including device management and the security " +
                        "detail of each link."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (role != AppRole.WEARER) {
            Spacer(Modifier.height(10.dp))
            PersistedTextField(
                stored = clinician,
                onCommit = { v -> scope.launch { settings.setClinicianName(v) } },
                label = "Clinician name",
                supporting = "Printed on exported summaries.",
            )
            PersistedTextField(
                stored = facility,
                onCommit = { v -> scope.launch { settings.setFacility(v) } },
                label = "Facility",
            )
        }

        // --------------------------------------------------- accessibility
        SectionHeader(
            "Display",
            subtitle = "Ported from the dashboard's accessibility controls",
        )
        SettingSwitch(
            "Larger text",
            "Scales every label and reading. Equivalent to setting the slider " +
                "below to 1.3.",
            largeText,
        ) { v -> scope.launch { settings.setLargeText(v) } }
        SettingSwitch(
            "High contrast",
            "Black on white, heavier outlines, and status colours chosen to " +
                "stay distinguishable without relying on hue.",
            highContrast,
        ) { v -> scope.launch { settings.setHighContrast(v) } }
        SettingSwitch(
            "Reduce motion",
            "Suppresses the animated transitions. Helps if movement on screen " +
                "is uncomfortable.",
            reduceMotion,
        ) { v -> scope.launch { settings.setReduceMotion(v) } }

        Spacer(Modifier.height(8.dp))
        // The slider drives local state and only writes on release. Writing
        // on every frame would push the whole app through a theme rebuild
        // per pixel of drag, which on an A52 is visibly janky.
        var scale by remember(textScale) { mutableStateOf(textScale) }
        Text(
            "Text size  ${(scale * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = scale,
            onValueChange = { scale = it },
            onValueChangeFinished = { scope.launch { settings.setTextScale(scale) } },
            valueRange = 0.85f..1.8f,
            steps = 18,
            modifier = Modifier.fillMaxWidth(),
        )

        // ----------------------------------------------------- permissions
        SectionHeader("Permissions")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "HeatGuard needs Bluetooth to reach the wearable, " +
                        "notifications to show a countdown you can cancel, and " +
                        "SMS to contact a caregiver. On Android 11 and older, " +
                        "a Bluetooth scan also needs location permission - " +
                        "that is an Android rule, not something this app uses " +
                        "your location for.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Review permissions") }
            }
        }

        // ------------------------------------------------------------ data
        SectionHeader("Stored data")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                InfoRow("Database", "SQLCipher, AES-256")
                InfoRow("Key storage", "Android hardware keystore")
                InfoRow("Retention", "Vitals pruned after 7 days")
                InfoRow("Wearables paired", service.devices.value.size.toString())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Readings never leave this phone unless you export them. " +
                        "The alert SMS carries a name, a time and a map link, " +
                        "and nothing else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete all stored readings") }
            }
        }

        SectionHeader("About")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                InfoRow("App protocol", "v2")
                InfoRow("Key agreement", "ML-KEM-512 (FIPS 203)")
                InfoRow("Record cipher", "AES-256-GCM")
                InfoRow("Fall detection", "TS-HGV on device, 1D-CNN on phone")
                InfoRow("HRV gating", "MQG three-tier")
                Spacer(Modifier.height(8.dp))
                Text(
                    "HeatGuard is a research prototype. It is not a medical " +
                        "device and must not be relied on as the only means of " +
                        "summoning help.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all stored readings?") },
            text = {
                Text(
                    "Every vitals sample and every event for every patient is " +
                        "removed from this phone. Paired wearables are kept. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val db = EchoWearDatabase.get(context)
                    scope.launch {
                        service.devices.value.forEach { conn ->
                            db.vitals().purgeDevice(conn.deviceId)
                            db.events().purgeDevice(conn.deviceId)
                        }
                        // Rows from a wearable that has since been released
                        // would otherwise survive a "delete everything".
                        db.vitals().prune(System.currentTimeMillis())
                    }
                    confirmClear = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A text field whose value is persisted, without the cursor jumping.
 *
 * Binding a field straight to a DataStore flow looks tidy and behaves badly:
 * every keystroke writes, the write comes back asynchronously, and the
 * recomposition can land mid-word and move the caret. The field keeps its own
 * state and is reseeded only when the stored value changes from elsewhere.
 */
@Composable
private fun PersistedTextField(
    stored: String,
    onCommit: (String) -> Unit,
    label: String,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by remember(stored) { mutableStateOf(stored) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}
