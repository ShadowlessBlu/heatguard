package gh.knust.echowear.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.ble.DeviceType
import gh.knust.echowear.ble.DiscoveredDevice
import gh.knust.echowear.ble.LinkState
import gh.knust.echowear.ble.PAIR_NO_TIMEOUT
import gh.knust.echowear.ble.PairResult
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.components.BadgeTone
import gh.knust.echowear.ui.components.EmptyState
import gh.knust.echowear.ui.components.ErrorState
import gh.knust.echowear.ui.components.InfoRow
import gh.knust.echowear.ui.components.SectionHeader
import gh.knust.echowear.ui.components.StatusBadge
import kotlinx.coroutines.delay

/**
 * Adding a wearable to this phone.
 *
 * The flow is: find it -> connect -> prove you are standing next to it ->
 * say whose it is. The third step is the one that matters and the one a
 * consumer BLE app usually skips. Without it, a phone on the next ward could
 * subscribe to this patient's heart rate simply by being in range, because
 * BLE advertising is public and GATT reads are not authenticated by default.
 *
 * The wearable answers a claim only while its pairing window is open, and
 * the window only opens at power-on or by a deliberate physical action, so
 * possession of the device is what actually authorises the claim.
 */
private enum class PairStep { SCAN, CONNECT, CODE, DETAILS, DONE }

@Composable
fun PairingScreen(
    service: MonitorService,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(PairStep.SCAN) }
    var chosen by remember { mutableStateOf<DiscoveredDevice?>(null) }

    val client by service.pairingClient.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                when (step) {
                    PairStep.SCAN -> onFinished()
                    PairStep.DETAILS, PairStep.DONE -> onFinished()
                    else -> { service.cancelPairing(); step = PairStep.SCAN }
                }
            }) { Icon(Icons.Filled.ArrowBack, "Back") }
            Text("Add a wearable", style = MaterialTheme.typography.titleLarge)
        }

        Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            when (step) {
                PairStep.SCAN -> ScanStep(service) { device ->
                    chosen = device
                    service.beginPairing(device.address)
                    step = PairStep.CONNECT
                }

                PairStep.CONNECT -> ConnectStep(
                    service = service,
                    device = chosen,
                    onReady = { step = PairStep.CODE },
                    onBack = { service.cancelPairing(); step = PairStep.SCAN },
                )

                PairStep.CODE -> CodeStep(
                    service = service,
                    onClaimed = { step = PairStep.DETAILS },
                    onBack = { service.cancelPairing(); step = PairStep.SCAN },
                )

                PairStep.DETAILS -> DetailsStep(
                    service = service,
                    onDone = { step = PairStep.DONE },
                )

                PairStep.DONE -> DoneStep(onFinished)
            }
        }
    }

    // Leaving the screen mid-flow must not leave a half-open connection
    // holding the radio. `step` is read at dispose time, not captured now:
    // the delegate resolves to the same MutableState on every access, so the
    // check sees where the user actually got to.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (step != PairStep.DONE) service.cancelPairing()
        }
    }
}

// ------------------------------------------------------------------- scan
@Composable
private fun ScanStep(service: MonitorService, onPick: (DiscoveredDevice) -> Unit) {
    val results by service.scanner.results.collectAsStateWithLifecycle()
    val scanning by service.scanner.scanning.collectAsStateWithLifecycle()
    val error by service.scanner.error.collectAsStateWithLifecycle()
    val totalSeen by service.scanner.totalSeen.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { service.scanner.start() }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Put the wearable within arm's reach and make sure it is switched " +
                "on. It only accepts a new phone for two minutes after it " +
                "starts up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        error?.let {
            ErrorState(it) {
                Button(onClick = { service.scanner.start() }) { Text("Try again") }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (totalSeen == 0) {
                        "Looking for wearables…"
                    } else {
                        "Looking… $totalSeen Bluetooth device" +
                            "${if (totalSeen == 1) "" else "s"} seen so far"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                TextButton(onClick = { service.scanner.start() }) { Text("Scan again") }
            }
        }

        if (results.isEmpty() && !scanning && error == null) {
            // "Nothing found" has three very different causes and they need
            // different actions, so the screen says which one it is looking
            // at rather than offering one generic paragraph.
            if (totalSeen == 0) {
                ErrorState(
                    "The scan finished without seeing any Bluetooth device at " +
                        "all - not even a nearby phone or headset.\n\n" +
                        "That points at the phone rather than the wearable. " +
                        "Check that Bluetooth is on, that HeatGuard has the " +
                        "\"Nearby devices\" permission, and on Android 11 or " +
                        "older that Location is switched on."
                ) {
                    Button(onClick = { service.scanner.start() }) { Text("Scan again") }
                }
            } else {
                ErrorState(
                    "Saw $totalSeen Bluetooth device${if (totalSeen == 1) "" else "s"} " +
                        "nearby, but none of them is a HeatGuard wearable.\n\n" +
                        "So the phone is scanning correctly and the wearable is " +
                        "not advertising. Check its serial monitor at 115200 " +
                        "baud: it prints \"BLE  | advertising as EchoWear-XXXX\" " +
                        "a second or two after reset. If it prints " +
                        "\"ADVERTISING FAILED TO START\", or nothing at all, the " +
                        "problem is on the device.\n\n" +
                        "One more thing to rule out: a wearable stops " +
                        "advertising while something is connected to it. If " +
                        "you tapped it in this phone's own Bluetooth settings, " +
                        "Android is holding the link. Open Settings > " +
                        "Bluetooth, find the EchoWear entry, and choose " +
                        "Forget."
                ) {
                    Button(onClick = { service.scanner.start() }) { Text("Scan again") }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.address }) { d -> ScanRow(d) { onPick(d) } }
        }
    }
}

@Composable
private fun ScanRow(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Watch, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "HeatGuard ${device.serialSuffix}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (device.bonded) {
                        "Paired in Android settings - not advertising"
                    } else {
                        "${device.nearness} · ${device.rssi} dBm"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(
                "Connect",
                when {
                    device.bonded -> BadgeTone.WARN
                    device.rssi > -70 -> BadgeTone.OK
                    else -> BadgeTone.NEUTRAL
                },
            )
        }
    }
}

// ---------------------------------------------------------------- connect
@Composable
private fun ConnectStep(
    service: MonitorService,
    device: DiscoveredDevice?,
    onReady: () -> Unit,
    onBack: () -> Unit,
) {
    val client = service.pairingClient.collectAsStateWithLifecycle().value
    if (client == null) {
        // The service dropped the transient connection - most likely the
        // radio went away. Send the user back rather than spinning forever.
        ErrorState("The connection to the wearable was lost.") {
            OutlinedButton(onClick = onBack) { Text("Back to the list") }
        }
        return
    }
    val link by client.linkState.collectAsStateWithLifecycle()
    val identity by client.identity.collectAsStateWithLifecycle()
    val status by client.pairStatus.collectAsStateWithLifecycle()

    LaunchedEffect(link, status) {
        if (link == LinkState.READY || link == LinkState.NEEDS_PAIRING) {
            if (status != null) onReady()
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(
            when (link) {
                LinkState.CONNECTING -> "Connecting to the wearable…"
                LinkState.DISCOVERING -> "Reading what it can do…"
                LinkState.KEY_EXCHANGE ->
                    "Agreeing a post-quantum key so nothing readable crosses the air…"
                LinkState.AUTHENTICATING -> "Checking whether this phone already owns it…"
                LinkState.DISCONNECTED -> "Lost the connection."
                else -> "Working…"
            },
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            device?.let { "HeatGuard ${it.serialSuffix}" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        identity?.let {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    InfoRow("Serial", it.serial)
                    InfoRow("Model", it.type.label)
                    InfoRow("Firmware", it.firmware)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (link == LinkState.DISCONNECTED) {
            OutlinedButton(onClick = onBack) { Text("Back to the list") }
        }
    }
}

// ------------------------------------------------------------------- code
@Composable
private fun CodeStep(
    service: MonitorService,
    onClaimed: () -> Unit,
    onBack: () -> Unit,
) {
    val client = service.pairingClient.collectAsStateWithLifecycle().value ?: return
    val status by client.pairStatus.collectAsStateWithLifecycle()
    val identity by client.identity.collectAsStateWithLifecycle()
    val result by client.pairResult.collectAsStateWithLifecycle()
    val confirmed by client.claimConfirmed.collectAsStateWithLifecycle()

    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(0) }

    LaunchedEffect(confirmed) { if (confirmed) onClaimed() }

    // The window closes on the device, not here, so this countdown is
    // refreshed from the device's own figure rather than run independently.
    // Current firmware keeps an unclaimed device claimable indefinitely and
    // reports PAIR_NO_TIMEOUT; only an older build counts down.
    val noDeadline = status?.pairingHasNoDeadline == true
    LaunchedEffect(status?.pairingRemainingSec) {
        val reported = status?.pairingRemainingSec ?: 0
        remaining = if (reported == PAIR_NO_TIMEOUT) 0 else reported
        while (remaining > 0) { delay(1000); remaining -= 1 }
    }

    val type = identity?.type ?: status?.deviceType ?: DeviceType.UNKNOWN
    val claimedByOther = status?.claimed == true && !confirmed
    val windowOpen = status?.pairingOpen == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (claimedByOther) {
            AlreadyClaimed(onBack)
            return@Column
        }

        SectionHeader(
            "Enter the pairing code",
            subtitle = identity?.serial?.let { "Wearable $it" },
        )

        Text(
            when (type) {
                DeviceType.TOUCH_LCD ->
                    "The wearable shows its six-digit code as soon as it starts " +
                        "up, and keeps it in the bar along the top of every " +
                        "screen until it is paired.\n\n" +
                        "Cannot see it? Press NEW CODE on the wearable's pairing " +
                        "screen, or tap the wearable sharply three times from " +
                        "anywhere to jump back to it with a fresh code."
                DeviceType.HEADLESS ->
                    "This wearable has no screen. Its code is printed on the " +
                        "case, and it does not change.\n\n" +
                        "Mislaid it? Connect the wearable by USB and open a " +
                        "serial monitor at 115200: the code is printed at " +
                        "start-up and repeated every 20 seconds. Pressing the " +
                        "board's BOOT button prints it on demand and flashes " +
                        "the LED three times."
                DeviceType.UNKNOWN ->
                    "Enter the six-digit code shown on the wearable, printed on " +
                        "its case, or written to its serial monitor."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { v -> if (v.length <= 6 && v.all(Char::isDigit)) code = v },
            label = { Text("Pairing code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
            isError = submitted && result == PairResult.ERR_CODE,
            supportingText = {
                val st = status
                when {
                    submitted && st != null && result != null && result != PairResult.OK ->
                        Text(service.pairing.explain(st))
                    !windowOpen ->
                        Text(
                            "The wearable is not accepting a new phone right now.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    remaining > 0 -> Text("Window closes in ${remaining}s")
                    noDeadline -> Text("Six digits. No rush - the code stays valid.")
                    else -> Text("Six digits")
                }
            },
        )

        if (remaining > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (remaining / 120f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                submitted = true
                client.claim(code.toInt())
            },
            enabled = code.length == 6 && windowOpen,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Pair this wearable") }

        if (!windowOpen) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { client.openPairingWindow() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ask the wearable to reopen its window") }
            Spacer(Modifier.height(6.dp))
            Text(
                "This only works on a wearable that has never been paired. " +
                    "A wearable that already belongs to a phone has to be " +
                    "released from that phone, or reset by holding its button " +
                    "for five seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onBack) { Text("Choose a different wearable") }
    }
}

@Composable
private fun AlreadyClaimed(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        ErrorState(
            "This wearable already belongs to another phone.\n\n" +
                "To move it, either release it from the phone that owns it, " +
                "or hold the wearable's button for five seconds to factory " +
                "reset it. A reset wipes the stored owner and the patient " +
                "reference, and the wearable then reopens for pairing."
        ) {
            OutlinedButton(onClick = onBack) { Text("Back to the list") }
        }
    }
}

// ---------------------------------------------------------------- details
@Composable
private fun DetailsStep(service: MonitorService, onDone: () -> Unit) {
    val client = service.pairingClient.collectAsStateWithLifecycle().value
    val identity = client?.identity?.collectAsStateWithLifecycle()?.value

    var patientName by remember { mutableStateOf("") }
    var patientRef by remember { mutableStateOf(service.pairing.suggestPatientRef()) }
    var label by remember { mutableStateOf("") }
    var caregiverName by remember { mutableStateOf("") }
    var caregiverPhone by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle, null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Paired with ${identity?.serial ?: "the wearable"}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Who is going to wear it? The name stays on this phone. Only the " +
                "reference is written to the wearable, so a device that is " +
                "lost or handed in does not identify anybody.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = patientName,
            onValueChange = { patientName = it },
            label = { Text("Patient or wearer name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = patientRef,
            onValueChange = { if (it.length <= 16) patientRef = it },
            label = { Text("Patient reference") },
            supportingText = { Text("Stored on the wearable. Keep it pseudonymous.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { if (it.length <= 24) label = it },
            label = { Text("Device label (optional)") },
            supportingText = { Text("Shown on the wearable, e.g. \"Ward B bed 3\".") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(
            "Who should be called",
            subtitle = "Leave blank to use the app-wide caregiver from Settings.",
        )
        OutlinedTextField(
            value = caregiverName,
            onValueChange = { caregiverName = it },
            label = { Text("Caregiver name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = caregiverPhone,
            onValueChange = { caregiverPhone = it },
            label = { Text("Caregiver phone") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                service.completePairing(
                    patientName = patientName.trim(),
                    patientRef = patientRef.trim(),
                    label = label.trim(),
                    caregiverName = caregiverName.trim(),
                    caregiverPhone = caregiverPhone.trim(),
                )
                onDone()
            },
            enabled = patientRef.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Finish") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DoneStep(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { delay(900); onFinished() }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Bluetooth, null,
            Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text("Wearable added", style = MaterialTheme.typography.titleMedium)
    }
}
