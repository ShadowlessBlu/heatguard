package gh.knust.echowear.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gh.knust.echowear.ble.ImuBatch
import gh.knust.echowear.ble.LinkState
import gh.knust.echowear.ble.Telemetry
import gh.knust.echowear.ml.HrvAnalyzer
import gh.knust.echowear.service.DeviceConnection
import gh.knust.echowear.service.MonitorService
import gh.knust.echowear.ui.OsmMap
import gh.knust.echowear.ui.components.AccelChart
import gh.knust.echowear.ui.components.BadgeTone
import gh.knust.echowear.ui.components.EmptyState
import gh.knust.echowear.ui.components.ErrorState
import gh.knust.echowear.ui.components.InfoRow
import gh.knust.echowear.ui.components.LineChart
import gh.knust.echowear.ui.components.MeterBar
import gh.knust.echowear.ui.components.ScoreGauge
import gh.knust.echowear.ui.components.SectionHeader
import gh.knust.echowear.ui.components.StatusBadge
import gh.knust.echowear.ui.components.StatusDot
import gh.knust.echowear.ui.components.VitalCard
import gh.knust.echowear.ui.theme.MinTouchTarget
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Motion tier names, matching the MQG-HRV thresholds in the firmware. */
fun motionTierLabel(tier: Int): String = when (tier) {
    0 -> "Low"
    1 -> "Moderate"
    else -> "High"
}

fun batteryTone(pct: Int): BadgeTone = when {
    pct >= 40 -> BadgeTone.OK
    pct >= 15 -> BadgeTone.WARN
    else -> BadgeTone.BAD
}

/**
 * Live vitals for one wearable.
 *
 * Ported from the dashboards' UserHome / VitalCard layout, with one change
 * that matters: HRV is shown with its motion tier attached. A 12 ms RMSSD
 * recorded while the wearer was walking is not a finding, and a panel that
 * prints the number without the tier invites it to be read as one.
 */
@Composable
fun VitalsScreen(
    service: MonitorService,
    conn: DeviceConnection,
    modifier: Modifier = Modifier,
) {
    val telemetry by conn.lastTelemetry.collectAsStateWithLifecycle()
    val link by conn.client.linkState.collectAsStateWithLifecycle()
    val undecryptable by conn.client.undecryptable.collectAsStateWithLifecycle()

    // The HRV window and the BPM trend are plain objects rather than flows,
    // so a slow tick pulls them forward. One second is far below the 10 s
    // vitals write interval and well above what the eye resolves.
    var hrvWindow by remember { mutableStateOf<HrvAnalyzer.Window?>(null) }
    val bpmTrend = remember { mutableStateListOf<Float>() }
    LaunchedEffect(conn.deviceId) {
        while (true) {
            hrvWindow = conn.hrv.window(conn.lastRmsG)
            conn.lastTelemetry.value?.bpm?.takeIf { it > 0f }?.let {
                bpmTrend.add(it)
                if (bpmTrend.size > 120) bpmTrend.removeAt(0)
            }
            delay(1000)
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LinkBanner(link, undecryptable, conn)

        val t = telemetry
        if (t == null) {
            val sealed by conn.client.session.established.collectAsStateWithLifecycle()
            val bandSession by conn.client.bandSession.collectAsStateWithLifecycle()
            EmptyState(
                when (link) {
                    LinkState.NEEDS_PAIRING ->
                        "The wearable will not send readings to this phone " +
                            "until it is paired again."
                    LinkState.READY -> when {
                        !sealed -> "Setting up the secure link with the band…"
                        undecryptable > 0 && bandSession != "PQC-SECURED" ->
                            "The band has not accepted the secure link yet " +
                                "(it reports ${bandSession ?: "no state"}). Tap Reconnect to try again."
                        undecryptable > 0 ->
                            "The band is sending readings but this phone cannot read them " +
                                "($undecryptable dropped). Tap Reconnect to set the link up again."
                        else -> "Secure link ready. Waiting for the band's first reading…"
                    }
                    else -> "Not connected."
                }
            )
            if (link == LinkState.READY || link == LinkState.KEY_EXCHANGE || link == LinkState.AUTHENTICATING) {
                OutlinedButton(
                    onClick = { conn.client.reconnect() },
                    modifier = Modifier.fillMaxWidth().height(MinTouchTarget),
                ) { Text("Reconnect") }
            }
        } else {
            VitalsGrid(t, hrvWindow, bpmTrend.toList())
        }

        SosButton(service, conn)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VitalsGrid(
    t: Telemetry,
    hrv: HrvAnalyzer.Window?,
    trend: List<Float>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VitalCard(
            label = "Heart rate",
            value = if (t.bpm > 0) t.bpm.roundToInt().toString() else "--",
            unit = "bpm",
            tone = when {
                t.bpm <= 0f -> BadgeTone.NEUTRAL
                t.bpm < 50 || t.bpm > 120 -> BadgeTone.WARN
                else -> BadgeTone.OK
            },
            caption = if (t.worn) null else "Device not on the wrist",
            trend = trend,
            icon = Icons.Filled.Favorite,
            modifier = Modifier.weight(1f),
        )
        VitalCard(
            label = "Battery",
            value = t.batteryPct.toString(),
            unit = "%",
            tone = batteryTone(t.batteryPct),
            caption = "%.2f V".format(t.busVoltageV),
            icon = Icons.Filled.BatteryFull,
            modifier = Modifier.weight(1f),
        )
    }

    SectionHeader(
        "Heart-rate variability",
        subtitle = "Motion-gated over the last 60 seconds",
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            val tier = hrv?.tier ?: t.motionTier
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Motion tier", style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.weight(1f))
                StatusBadge(
                    motionTierLabel(tier),
                    when (tier) {
                        0 -> BadgeTone.OK
                        1 -> BadgeTone.WARN
                        else -> BadgeTone.BAD
                    },
                )
            }
            Spacer(Modifier.height(8.dp))

            if (tier >= 2) {
                Text(
                    "The wearer is moving too much for these intervals to mean " +
                        "anything, so they are discarded rather than reported. " +
                        "Values return when they settle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InfoRow("SDNN", fmtMs(hrv?.sdnn ?: t.sdnnMs))
                InfoRow("RMSSD", fmtMs(hrv?.rmssd ?: t.rmssdMs))
                InfoRow("pNN50", fmtPct(hrv?.pnn50 ?: t.pnn50Pct))
                InfoRow("RSA index", "%.2f".format(t.rsaIndex))
                hrv?.let {
                    InfoRow("Beats in window", it.beats.toString())
                    InfoRow("Confidence weight", "%.1f".format(it.weight))
                    InfoRow("Ectopic beats removed", "%.0f %%".format(it.ectopicRate * 100))
                }
            }
        }
    }

    SectionHeader("Gait")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DirectionsWalk, null,
                     tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (t.gaitWarm) {
                        "Personal gait profile learned"
                    } else {
                        "Still learning this wearer's gait"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("Cadence", "%.0f steps/min".format(t.gaitCadence))
            InfoRow("Step-time variance", "%.3f".format(t.gaitVariance))
            InfoRow("Stride regularity", "%.2f".format(t.gaitSri))
            if (!t.gaitWarm) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Stage 2 of the fall detector compares against this " +
                        "profile. Until it is warm, a detected impact is " +
                        "escalated on stillness alone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SectionHeader("Power")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            MeterBar(t.batteryPct / 100f, tone = batteryTone(t.batteryPct))
            Spacer(Modifier.height(10.dp))
            InfoRow("Bus voltage", "%.2f V".format(t.busVoltageV))
            InfoRow("Current", "%.0f mA".format(t.currentMa))
            InfoRow("Power", "%.0f mW".format(t.powerMw))
            InfoRow("Uptime", fmtUptime(t.deviceUptimeMs))
        }
    }
}

private fun fmtMs(v: Float) = if (v <= 0f) "not measured" else "%.1f ms".format(v)
private fun fmtPct(v: Float) = if (v <= 0f) "not measured" else "%.1f %%".format(v)

private fun fmtUptime(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

@Composable
private fun LinkBanner(state: LinkState, undecryptable: Int, conn: DeviceConnection) {
    val sealed by conn.client.session.established.collectAsStateWithLifecycle()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                LinkState.READY -> MaterialTheme.colorScheme.primaryContainer
                LinkState.NEEDS_PAIRING -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conn.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(linkLabel(state), linkTone(state))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatusDot(
                    if (sealed) "Encrypted" else "Not sealed",
                    if (sealed) BadgeTone.OK else BadgeTone.WARN,
                )
                if (undecryptable > 0) {
                    StatusDot("$undecryptable dropped", BadgeTone.WARN)
                }
            }
        }
    }
}

@Composable
private fun SosButton(service: MonitorService, conn: DeviceConnection) {
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { service.triggerSos(conn.deviceId) },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.fillMaxWidth().height(MinTouchTarget),
    ) {
        Icon(Icons.Filled.Emergency, null)
        Spacer(Modifier.width(10.dp))
        Text("Send an alert for ${conn.displayName}",
             style = MaterialTheme.typography.titleSmall)
    }
    Text(
        "There is a ten-second grace period before the caregiver is contacted.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

// ============================================================ motion panel
/**
 * Live accelerometer trace and the phone-side classifier's opinion.
 *
 * Ported from MotionPanel.tsx. The IMU stream is off by default: ten samples
 * at 50 Hz sealed and notified is around 2 kB/s over the air, which is fine
 * for a minute of inspection and not fine as a permanent state of affairs
 * for a device expected to run all day on a small cell.
 */
@Composable
fun MotionScreen(conn: DeviceConnection, modifier: Modifier = Modifier) {
    var streaming by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<FloatArray>() }
    var lastVerdictScore by remember { mutableStateOf(0f) }

    LaunchedEffect(conn.deviceId) {
        conn.client.imu.collect { batch: ImuBatch ->
            samples.addAll(batch.samples.toList())
            while (samples.size > 250) samples.removeAt(0)
        }
    }
    LaunchedEffect(conn.deviceId) {
        while (true) {
            conn.ml.history.lastOrNull()?.let { lastVerdictScore = it }
            delay(500)
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        SectionHeader(
            "Motion",
            subtitle = "Raw accelerometer from ${conn.displayName}",
            trailing = {
                Switch(
                    checked = streaming,
                    onCheckedChange = {
                        streaming = it
                        conn.client.setImuStream(it)
                        if (!it) samples.clear()
                    },
                )
            },
        )

        if (!streaming) {
            EmptyState(
                "Streaming is off. Turn it on to watch the raw signal - it " +
                    "costs battery on the wearable, so leave it off for " +
                    "day-to-day monitoring."
            )
        } else if (samples.size < 4) {
            EmptyState("Waiting for samples…")
        } else {
            AccelChart(samples.toList())
        }

        SectionHeader(
            "Phone classifier",
            subtitle = "INT8 1D-CNN over a 2-second window",
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Fall score  %.3f".format(lastVerdictScore),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                ScoreGauge(lastVerdictScore)
                Spacer(Modifier.height(10.dp))
                Text(
                    "The wearable runs its own two-stage detector regardless. " +
                        "This second opinion runs on the phone because it has " +
                        "the memory for a model the wearable does not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conn.ml.history.size > 2) {
                    Spacer(Modifier.height(10.dp))
                    LineChart(
                        conn.ml.history,
                        yLabel = { "%.1f".format(it) },
                        band = 0f..0.5f,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ================================================================ location
@Composable
fun LocationScreen(conn: DeviceConnection, modifier: Modifier = Modifier) {
    val telemetry by conn.lastTelemetry.collectAsStateWithLifecycle()
    val t = telemetry

    Column(modifier.fillMaxSize().padding(16.dp)) {
        SectionHeader("Location", subtitle = conn.displayName)

        if (t == null || (t.lat == 0.0 && t.lon == 0.0)) {
            EmptyState(
                "No position fix. The GPS module is powered down between " +
                    "alerts to save battery; ask for a fix to wake it."
            )
            OutlinedButton(
                onClick = { conn.client.requestGpsFix() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.LocationOn, null)
                Spacer(Modifier.width(8.dp))
                Text("Request a fix now")
            }
            return@Column
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                InfoRow("Latitude", "%.6f".format(t.lat))
                InfoRow("Longitude", "%.6f".format(t.lon))
                InfoRow("Satellites", t.satellites.toString())
                InfoRow("Fix", if (t.gpsValid) "Valid" else "Stale")
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            OsmMap(t.lat, t.lon, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { conn.client.requestGpsFix() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh the fix") }
    }
}

// ================================================================ security
/**
 * What is actually protecting this link, in plain terms.
 *
 * Ported from SystemStatusPanel.tsx and extended: the dashboard showed a
 * padlock, which tells a user nothing they can act on. What they can act on
 * is whether the session key was agreed, whether this phone proved ownership,
 * and whether any frames are failing to decrypt - the last of which is the
 * signature of a mismatched build, not of an attack, and saying so saves a
 * long evening.
 */
@Composable
fun SecurityScreen(conn: DeviceConnection, modifier: Modifier = Modifier) {
    val sealed by conn.client.session.established.collectAsStateWithLifecycle()
    val link by conn.client.linkState.collectAsStateWithLifecycle()
    val status by conn.client.pairStatus.collectAsStateWithLifecycle()
    val identity by conn.client.identity.collectAsStateWithLifecycle()
    val undecryptable by conn.client.undecryptable.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        SectionHeader("Security", subtitle = conn.displayName)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock, null,
                        Modifier.size(20.dp),
                        tint = if (sealed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (sealed) {
                            "Link sealed with ML-KEM-512 + AES-256-GCM"
                        } else {
                            "Link is not sealed"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.height(10.dp))
                InfoRow("Key agreement", if (sealed) "ML-KEM-512 (FIPS 203)" else "none")
                InfoRow("Record cipher", if (sealed) "AES-256-GCM" else "none")
                InfoRow("Key derivation", "HKDF-SHA256")
                InfoRow("Ownership proof", "HMAC-SHA256 over a fresh challenge")
                InfoRow("This phone owns it", if (status?.authenticated == true) "Yes" else "No")
                InfoRow("Link state", linkLabel(link))
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Device", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                InfoRow("Serial", identity?.serial ?: conn.record.serial)
                InfoRow("Model", (identity?.type ?: conn.record.type).label)
                InfoRow("Firmware", identity?.firmware ?: conn.record.firmware)
                InfoRow("Protocol", "v${identity?.protocol ?: "?"}")
                InfoRow("Device id", conn.deviceId)
                InfoRow("Patient reference", conn.record.patientRef.ifBlank { "not set" })
            }
        }

        if (undecryptable > 0) {
            Spacer(Modifier.height(12.dp))
            ErrorState(
                "$undecryptable frames could not be decrypted.\n\n" +
                    "This is almost always a version mismatch rather than an " +
                    "attack: the wearable and the phone must agree on the " +
                    "frame layout, and a firmware built from a different " +
                    "revision of echowear_ble.h will fail every frame. " +
                    "Reflash the wearable from the same release as this app."
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
