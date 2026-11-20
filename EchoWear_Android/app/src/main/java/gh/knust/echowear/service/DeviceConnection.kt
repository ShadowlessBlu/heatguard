package gh.knust.echowear.service

import android.content.Context
import gh.knust.echowear.ble.DeviceIdentity
import gh.knust.echowear.ble.EchoWearBleClient
import gh.knust.echowear.ble.LinkState
import gh.knust.echowear.ble.Telemetry
import gh.knust.echowear.heat.HeatMonitor
import gh.knust.echowear.heat.HeatProfileStore
import gh.knust.echowear.heat.HeatSample
import gh.knust.echowear.heat.WorkerProfile
import gh.knust.echowear.ml.FallClassifier
import gh.knust.echowear.ml.HrvAnalyzer
import gh.knust.echowear.ml.MlPipeline
import gh.knust.echowear.ml.PersonalProfile
import gh.knust.echowear.pairing.PairedDevice
import gh.knust.echowear.pairing.PairingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the app keeps per wearable: the link, the classifier's rolling
 * buffer, the HRV history and the last reading.
 *
 * Giving each device its own pipeline is not optional once several patients
 * are being monitored. A shared 2-second IMU buffer fed by three wrists would
 * interleave three people's motion into one window and classify the mixture,
 * which is worse than useless - it would be confidently wrong.
 *
 * The TFLite interpreter itself IS shared, through FallClassifier: the model
 * is stateless between calls, so one instance serves every device and saves
 * re-loading 22 KB of weights per patient.
 */
class DeviceConnection(
    context: Context,
    record: PairedDevice,
    pairing: PairingManager,
    address: String,
    ownerToken: ByteArray?,
    sharedClassifier: FallClassifier,
    heatEntry: HeatProfileStore.Entry = HeatProfileStore.Entry(true, WorkerProfile()),
) {
    val client = EchoWearBleClient(context, address, pairing, ownerToken)
    /** Heat-strain monitoring for outdoor workers; off for wearers who are not. */
    val heat = HeatMonitor(heatEntry.profile)
    private val _heatEnabled = MutableStateFlow(heatEntry.enabled)
    /** Observable, so the Heat screen redraws when monitoring is switched. */
    val heatEnabledFlow = _heatEnabled.asStateFlow()
    var heatEnabled: Boolean
        get() = _heatEnabled.value
        set(v) { _heatEnabled.value = v }
    /** This wearer's learned normal; nothing in it is shared with any other wearable. */
    val profile = PersonalProfile(record.deviceId)
    val ml = MlPipeline(sharedClassifier, profile)
    val hrv = HrvAnalyzer()
    var lastProfileSave: Long = 0L

    /**
     * The roster entry. Mutable because a carer can rename a patient or
     * change the escalation number without the link being torn down, and an
     * alert raised a second later has to carry the new details.
     */
    var record: PairedDevice = record
        private set

    fun updateRecord(updated: PairedDevice) { record = updated }

    private val _lastTelemetry = MutableStateFlow<Telemetry?>(null)
    val lastTelemetry = _lastTelemetry.asStateFlow()

    private val _identity = MutableStateFlow<DeviceIdentity?>(null)
    val identity = _identity.asStateFlow()

    var lastRmsG: Float = 0f
        private set
    var lastVitalsWrite: Long = 0L
    var lastSeen: Long = 0L
        private set

    val deviceId: String get() = record.deviceId
    val displayName: String get() = record.displayName

    fun onTelemetry(t: Telemetry) {
        _lastTelemetry.value = t
        lastSeen = System.currentTimeMillis()
        // The wearable reports gravity-inclusive magnitude, so the 1 g offset
        // comes out before the MQG tier thresholds (defined on dynamic
        // acceleration) can be applied.
        val mag = kotlin.math.sqrt(t.ax * t.ax + t.ay * t.ay + t.az * t.az)
        lastRmsG = kotlin.math.abs(mag - 1f)
        hrv.onTelemetry(t.bpm, t.motionTier, lastSeen)
        profile.observeHr(t.bpm, t.motionTier)
        profile.observeActivity(lastRmsG)
        if (heatEnabled) {
            heat.onSample(HeatSample(
                bpm = t.bpm.toDouble(), motionTier = t.motionTier, worn = t.worn,
                ax = t.ax.toDouble(), ay = t.ay.toDouble(), az = t.az.toDouble(),
            ), lastSeen)
        }
    }

    fun onIdentity(id: DeviceIdentity) { _identity.value = id }

    val isConnected: Boolean
        get() = client.linkState.value == LinkState.READY

    fun start() = client.connect()

    fun stop() {
        client.disconnect()
        ml.reset()
        hrv.clear()
    }
}
