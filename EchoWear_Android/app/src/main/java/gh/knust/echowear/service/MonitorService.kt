package gh.knust.echowear.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import gh.knust.echowear.MainActivity
import gh.knust.echowear.R
import gh.knust.echowear.alert.AlertManager
import gh.knust.echowear.alert.PendingAlert
import gh.knust.echowear.alert.SettingsStore
import gh.knust.echowear.ble.AlertLevel
import gh.knust.echowear.ble.BleScanner
import gh.knust.echowear.ble.DeviceIdentity
import gh.knust.echowear.ble.EchoWearBleClient
import gh.knust.echowear.ble.LinkState
import gh.knust.echowear.data.EchoWearDatabase
import gh.knust.echowear.data.EventRecord
import gh.knust.echowear.data.VitalsRecord
import gh.knust.echowear.heat.HeatAssessment
import gh.knust.echowear.heat.HeatLevel
import gh.knust.echowear.heat.HeatProfileStore
import gh.knust.echowear.heat.SiteConditions
import gh.knust.echowear.ml.FallClassifier
import gh.knust.echowear.pairing.DeviceStore
import gh.knust.echowear.pairing.PairedDevice
import gh.knust.echowear.pairing.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service holding every paired wearable at once.
 *
 * A fall-detection app that only works with its own activity in the
 * foreground is not a fall-detection app, so everything that has to survive
 * lives here and the UI is a thin observer.
 *
 * Multi-patient behaviour worth knowing:
 *  - one BLE connection, ML pipeline and HRV history per wearable;
 *  - one shared TFLite interpreter, because the model is stateless;
 *  - alerts are queued per patient, and a second patient falling while the
 *    first is counting down does NOT overwrite the first alert.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ONGOING = "echowear_monitor"
        private const val CHANNEL_ALERTS = "echowear_alerts"
        private const val CHANNEL_HEAT = "echowear_heat"
        private const val NOTIF_ONGOING = 1001
        private const val NOTIF_ALERT_BASE = 2000
        private const val NOTIF_HEAT_BASE = 3000

        const val ACTION_START = "gh.knust.echowear.START"
        const val ACTION_STOP = "gh.knust.echowear.STOP"
        const val ACTION_CANCEL_ALERT = "gh.knust.echowear.CANCEL_ALERT"
        const val ACTION_SOS = "gh.knust.echowear.SOS"
        const val EXTRA_DEVICE_ID = "device_id"

        private val _running = MutableStateFlow<MonitorService?>(null)
        /**
         * Observable, because the UI composes before the service has finished
         * onCreate (startForegroundService is asynchronous). A plain field was
         * read once and the app sat on "Starting the monitoring service"
         * forever whenever the service won that race by a few milliseconds.
         */
        val running = _running.asStateFlow()
        val instance: MonitorService? get() = _running.value
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsStore; private set
    lateinit var alerts: AlertManager; private set
    lateinit var store: DeviceStore; private set
    lateinit var pairing: PairingManager; private set
    lateinit var scanner: BleScanner; private set
    lateinit var site: SiteConditions; private set
    lateinit var heatProfiles: HeatProfileStore; private set

    private lateinit var db: EchoWearDatabase
    private lateinit var classifier: FallClassifier

    /** deviceId -> live connection. */
    private val connections = linkedMapOf<String, DeviceConnection>()
    private val watchers = mutableMapOf<String, MutableList<Job>>()

    private val _devices = MutableStateFlow<List<DeviceConnection>>(emptyList())
    val devices = _devices.asStateFlow()

    /** The device whose detail the UI is showing; null means the overview. */
    private val _selected = MutableStateFlow<String?>(null)
    val selected = _selected.asStateFlow()

    /** A transient client used only while pairing something new. */
    private val _pairingClient = MutableStateFlow<EchoWearBleClient?>(null)
    val pairingClient = _pairingClient.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createChannels()

        settings = SettingsStore(this)
        alerts = AlertManager(this, settings)
        store = DeviceStore(this)
        pairing = PairingManager(this, store)
        scanner = BleScanner(this)
        db = EchoWearDatabase.get(this)
        classifier = FallClassifier(this)
        site = SiteConditions(this)
        heatProfiles = HeatProfileStore(this)

        restoreRoster()
        runCountdownLoop()
        runHeatLoop()
        // Published last, so the UI never sees a half-built service.
        _running.value = this
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); return START_NOT_STICKY }
            ACTION_CANCEL_ALERT -> {
                cancelAlert(intent.getStringExtra(EXTRA_DEVICE_ID))
                return START_STICKY
            }
            ACTION_SOS -> {
                scope.launch { raiseSos(intent.getStringExtra(EXTRA_DEVICE_ID)) }
                return START_STICKY
            }
        }
        startForegroundCompat()
        connectAll()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _running.value = null
        connections.values.forEach { conn ->
            runCatching { kotlinx.coroutines.runBlocking { saveProfile(conn) } }
            runCatching { conn.stop() }
        }
        _pairingClient.value?.let { runCatching { it.disconnect() } }
        classifier.close()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- roster
    private fun restoreRoster() {
        store.all().forEach { addConnection(it) }
        publishDevices()
        if (_selected.value == null) _selected.value = connections.keys.firstOrNull()
    }

    private fun addConnection(record: PairedDevice) {
        if (connections.containsKey(record.deviceId)) return
        val address = addressFor(record.deviceId) ?: run {
            Log.w(TAG, "no BLE address stored for ${record.serial}; will pair again")
            return
        }
        val conn = DeviceConnection(
            context = this,
            record = record,
            pairing = pairing,
            address = address,
            ownerToken = store.ownerToken(record.deviceId),
            sharedClassifier = classifier,
            heatEntry = heatProfiles.get(record.deviceId),
        )
        connections[record.deviceId] = conn
        // Restore what the app already learned about this wearer.
        scope.launch {
            runCatching { db.profiles().get(record.deviceId)?.let { conn.profile.restore(it.json) } }
        }
        watch(conn)
    }

    private suspend fun saveProfile(conn: DeviceConnection) {
        runCatching {
            db.profiles().upsert(
                gh.knust.echowear.data.PersonalProfileRecord(
                    conn.deviceId, conn.profile.toJson(), System.currentTimeMillis()))
        }
        conn.lastProfileSave = System.currentTimeMillis()
    }

    /** The MAC is what connectGatt needs, and it is not the device id. */
    private fun addressFor(deviceId: String): String? =
        getSharedPreferences("echowear_addresses", Context.MODE_PRIVATE)
            .getString(deviceId, null)

    fun rememberAddress(deviceId: String, address: String) {
        getSharedPreferences("echowear_addresses", Context.MODE_PRIVATE)
            .edit().putString(deviceId, address).apply()
    }

    private fun publishDevices() { _devices.value = connections.values.toList() }

    fun select(deviceId: String?) { _selected.value = deviceId }

    fun connectionFor(deviceId: String?): DeviceConnection? =
        deviceId?.let { connections[it] }

    fun selectedConnection(): DeviceConnection? = connectionFor(_selected.value)

    private fun connectAll() {
        connections.values.forEach { conn ->
            if (conn.client.linkState.value == LinkState.IDLE ||
                conn.client.linkState.value == LinkState.DISCONNECTED
            ) {
                runCatching { conn.start() }
                    .onFailure { Log.e(TAG, "connect failed (permissions?)", it) }
            }
        }
    }

    // ------------------------------------------------------------ watchers
    private fun watch(conn: DeviceConnection) {
        val jobs = mutableListOf<Job>()

        jobs += conn.client.telemetry.onEach { t ->
            t ?: return@onEach
            conn.onTelemetry(t)
            store.touch(conn.deviceId)

            val now = System.currentTimeMillis()
            if (now - conn.lastVitalsWrite >= 10_000L) {
                conn.lastVitalsWrite = now
                val w = conn.hrv.window(conn.lastRmsG)
                db.vitals().insert(
                    VitalsRecord(
                        recordedAt = now,
                        deviceId = conn.deviceId,
                        bpm = t.bpm,
                        sdnnMs = w?.sdnn ?: t.sdnnMs,
                        rmssdMs = w?.rmssd ?: t.rmssdMs,
                        pnn50Pct = w?.pnn50 ?: t.pnn50Pct,
                        motionTier = t.motionTier,
                        hrvWeight = w?.weight ?: 0f,
                        worn = t.worn,
                        batteryPct = t.batteryPct,
                    )
                )
                db.vitals().prune(now - 7L * 24 * 3600 * 1000)
            }
            if (now - conn.lastProfileSave >= 300_000L) saveProfile(conn)
            publishDevices()
        }.launchIn(scope)

        jobs += conn.client.identity.onEach { id ->
            id ?: return@onEach
            conn.onIdentity(id)
            // The device's own label may have been changed from another phone.
            if (id.label.isNotBlank() && id.label != conn.record.label) {
                val updated = conn.record.copy(label = id.label)
                store.upsert(updated)
                conn.updateRecord(updated)
                publishDevices()
            }
        }.launchIn(scope)

        jobs += conn.client.imu.onEach { batch ->
            if (!settings.phoneMlEnabled.first()) return@onEach
            val verdict = conn.ml.onBatch(batch) ?: return@onEach
            if (!verdict.fallDetected) return@onEach

            val t = conn.lastTelemetry.value
            Log.i(TAG, "phone ML fall on ${conn.displayName}: score=${verdict.score}")
            raiseAlert(
                conn,
                PendingAlert(
                    level = AlertLevel.FALL_CONFIRMED,
                    source = "Phone ML classifier",
                    score = verdict.score,
                    hrBpm = t?.bpm ?: 0f,
                    lat = t?.lat ?: 0.0,
                    lon = t?.lon ?: 0.0,
                    raisedAt = System.currentTimeMillis(),
                    countdownMs = AlertManager.COUNTDOWN_MS,
                    deviceId = conn.deviceId,
                    patientName = conn.displayName,
                ),
                kind = "ML_FALL", sourceTag = "phone_ml", gaitDeviation = 0f,
                note = buildString {
                    append("threshold %.2f".format(verdict.threshold))
                    append(if (verdict.personalised) " (personal)" else " (global)")
                    conn.profile.describeHr(t?.bpm ?: 0f)?.let { append("; ").append(it) }
                },
            )
        }.launchIn(scope)

        jobs += conn.client.alerts.onEach { a ->
            // Level NONE is the wearable telling us the wearer cancelled on
            // the device, or that stage 2 stood the event down. Without this
            // the wrist button stops the buzzer and the phone texts the
            // caregiver anyway thirty seconds later - the one outcome a
            // cancel button must never produce.
            if (a.level == AlertLevel.NONE) {
                if (alerts.pendingFor(conn.deviceId) != null) {
                    Log.i(TAG, "${conn.displayName} cancelled on the wearable")
                    cancelAlert(conn.deviceId)
                }
                return@onEach
            }
            if (a.level == AlertLevel.FALL_PREDICTED) {
                notify(NOTIF_ALERT_BASE + conn.deviceId.hashCode().and(0xFF),
                       simpleAlertNotification(
                           "Possible fall risk - ${conn.displayName}",
                           "The wearable flagged a pre-impact pattern. No alert was sent."))
                return@onEach
            }
            if (!a.level.isEmergency) return@onEach

            raiseAlert(
                conn,
                PendingAlert(
                    level = a.level,
                    source = "Wearable TS-HGV",
                    score = a.score,
                    hrBpm = a.hrBpm,
                    lat = a.lat,
                    lon = a.lon,
                    raisedAt = System.currentTimeMillis(),
                    countdownMs = AlertManager.COUNTDOWN_MS,
                    deviceId = conn.deviceId,
                    patientName = conn.displayName,
                ),
                kind = a.level.name, sourceTag = "device",
                gaitDeviation = a.gaitDeviation,
            )
        }.launchIn(scope)

        jobs += conn.client.linkState.onEach {
            publishDevices()
            notify(NOTIF_ONGOING, ongoingNotification())
        }.launchIn(scope)

        watchers[conn.deviceId] = jobs
    }

    private fun unwatch(deviceId: String) {
        watchers.remove(deviceId)?.forEach { it.cancel() }
    }

    // ------------------------------------------------------------- pairing
    /** Open a transient connection to a device we do not own yet. */
    @SuppressLint("MissingPermission")
    fun beginPairing(address: String) {
        _pairingClient.value?.disconnect()
        val client = EchoWearBleClient(this, address, pairing, null)
        _pairingClient.value = client
        client.connect()
    }

    fun cancelPairing() {
        _pairingClient.value?.disconnect()
        _pairingClient.value = null
    }

    /**
     * Finish a claim: store the roster entry, wire up a permanent connection
     * and tell the device which patient it now belongs to.
     */
    fun completePairing(
        patientName: String,
        patientRef: String,
        label: String,
        caregiverName: String = "",
        caregiverPhone: String = "",
    ) {
        val client = _pairingClient.value ?: return
        val identity: DeviceIdentity = client.identity.value ?: return
        val deviceId = client.deviceId ?: return
        val token = client.pendingClaimToken ?: pairing.ownerTokenFor(deviceId) ?: return

        pairing.rememberClaim(deviceId, identity, token, patientName, patientRef)
        if (caregiverName.isNotBlank() || caregiverPhone.isNotBlank()) {
            pairing.updatePatient(
                deviceId,
                caregiverName = caregiverName,
                caregiverPhone = caregiverPhone,
            )
        }
        rememberAddress(deviceId, client.address)
        client.setDeviceMeta(patientRef, label.ifBlank { patientName })

        // The device appears in the roster straight away so the UI can move
        // on, but it is NOT connected yet: the metadata write above is queued
        // rather than sent, and the transient socket has to stay up until it
        // has been answered. Opening the permanent connection now would mean
        // two GATT links to one wearable, which on Android is how you get a
        // connection that reports success and then silently delivers nothing.
        store.get(deviceId)?.let { addConnection(it) }
        publishDevices()
        _selected.value = deviceId

        client.disconnectWhenIdle {
            _pairingClient.value = null
            connectAll()
        }
    }

    /**
     * Edit the patient details of an already-paired wearable.
     *
     * The record lives in two places on purpose: the reference and the label
     * go to the device so its own SMS can identify the wearer, and the name
     * and caregiver number stay on the phone so a lost wearable identifies
     * nobody. Both halves are updated here.
     */
    fun updatePatient(
        deviceId: String,
        patientName: String,
        patientRef: String,
        label: String,
        caregiverName: String,
        caregiverPhone: String,
        notes: String,
    ) {
        val updated = pairing.updatePatient(
            deviceId, patientName, patientRef, label,
            caregiverName, caregiverPhone, notes,
        ) ?: return
        connections[deviceId]?.let { conn ->
            conn.updateRecord(updated)
            if (conn.isConnected) {
                runCatching {
                    conn.client.setDeviceMeta(
                        updated.patientRef,
                        updated.label.ifBlank { updated.patientName },
                    )
                }
            }
        }
        publishDevices()
    }

    fun removeDevice(deviceId: String) {
        connections.remove(deviceId)?.let { conn ->
            unwatch(deviceId)
            runCatching { conn.client.unclaim() }
            runCatching { conn.stop() }
        }
        store.remove(deviceId)
        heatProfiles.remove(deviceId)
        scope.launch { runCatching { db.profiles().delete(deviceId) } }
        publishDevices()
        if (_selected.value == deviceId) _selected.value = connections.keys.firstOrNull()
    }

    // ---------------------------------------------------------------- alerts
    private suspend fun raiseAlert(
        conn: DeviceConnection, alert: PendingAlert,
        kind: String, sourceTag: String, gaitDeviation: Float,
        note: String? = null,
    ) {
        val id = db.events().insert(
            EventRecord(
                occurredAt = alert.raisedAt, deviceId = conn.deviceId,
                patientRef = conn.record.patientRef,
                kind = kind, source = sourceTag,
                score = alert.score, hrBpm = alert.hrBpm, gaitDeviation = gaitDeviation,
                lat = alert.lat, lon = alert.lon,
                acknowledged = false, smsSent = false, note = note,
            )
        )
        // A fall during heat strain is a possible heat collapse: the heat
        // engine latches RED with an emergency action until someone
        // acknowledges it. A manual SOS is not a fall, so it is left out.
        if (kind != "MANUAL_SOS" && conn.heatEnabled) conn.heat.onFall()

        // The alert carries everything the SMS will need, because by the
        // time the countdown expires the device may be gone.
        val stamped = alert.copy(
            eventId = id,
            patientRef = conn.record.patientRef,
            deviceSerial = conn.record.serial,
            caregiverPhone = conn.record.caregiverPhone,
        )
        if (alerts.raise(stamped)) {
            notify(notifIdFor(conn.deviceId), countdownNotification(stamped))
            // Tell the wearable the phone has this one, so it does not send
            // its own SMS as well. Only for alerts the device raised: an
            // alert this phone invented (phone ML, phone SOS) was never the
            // device's to send in the first place.
            if (sourceTag == "device") runCatching { conn.client.takeOverAlert(true) }
        }
    }

    private suspend fun raiseSos(deviceId: String?) {
        val conn = connectionFor(deviceId ?: _selected.value) ?: return
        val t = conn.lastTelemetry.value
        raiseAlert(
            conn,
            PendingAlert(
                level = AlertLevel.MANUAL_SOS, source = "Phone SOS button",
                score = 0f, hrBpm = t?.bpm ?: 0f,
                lat = t?.lat ?: 0.0, lon = t?.lon ?: 0.0,
                raisedAt = System.currentTimeMillis(),
                // An explicit SOS gets a shorter grace period than an
                // automatic detection: the wearer already decided.
                countdownMs = 10_000L,
                deviceId = conn.deviceId, patientName = conn.displayName,
            ),
            kind = "MANUAL_SOS", sourceTag = "phone", gaitDeviation = 0f,
        )
    }

    fun cancelAlert(deviceId: String?) {
        val target = deviceId ?: alerts.pending.value.firstOrNull()?.deviceId ?: return
        val p = alerts.cancel(target) ?: return
        connectionFor(target)?.let {
            // "I'm OK" also clears the heat engine's collapse latch.
            it.heat.acknowledge()
            // Cancelling a phone-ML alert tells the app this score was normal for this wearer.
            if (p.source == "Phone ML classifier") it.ml.falseAlarmFeedback()
            it.ml.reset()
            runCatching { it.client.acknowledgeAlertOnDevice() }
            runCatching { it.client.takeOverAlert(false) }
        }
        scope.launch { if (p.eventId >= 0) db.events().acknowledge(p.eventId) }
        notificationManager().cancel(notifIdFor(target))
    }

    fun triggerSos(deviceId: String? = null) = scope.launch { raiseSos(deviceId) }

    private fun notifIdFor(deviceId: String) =
        NOTIF_ALERT_BASE + (deviceId.hashCode() and 0xFF)

    /** Ticks every pending countdown; a second patient does not cancel the first. */
    private fun runCountdownLoop() = scope.launch {
        while (true) {
            val pending = alerts.pending.value
            if (pending.isEmpty()) { delay(500); continue }

            for (p in pending) {
                if (p.remainingMs() > 0) {
                    notify(notifIdFor(p.deviceId), countdownNotification(p))
                    continue
                }
                alerts.cancel(p.deviceId)
                // A wearable with its own caregiver overrides the app-wide
                // number: three patients on one phone rarely share a family.
                val phone = p.caregiverPhone.ifBlank { settings.caregiverPhone.first() }
                val wearer = p.patientName.ifBlank { settings.wearerName.first() }
                var sent = false
                if (settings.smsEnabled.first()) sent = alerts.dispatch(p, wearer, phone)
                // Belt and braces: if the phone cannot send, ask the wearable
                // to use its own SIM.
                connectionFor(p.deviceId)?.let { conn ->
                    if (sent) {
                        // Stand the wearable's own SIM down for good.
                        runCatching { conn.client.reportSmsSentByPhone() }
                    } else {
                        // Belt and braces: ask the wearable to send instead.
                        runCatching { conn.client.requestCaregiverSms() }
                    }
                }
                if (p.eventId >= 0 && sent) db.events().markSmsSent(p.eventId)
                if (settings.callEnabled.first()) alerts.placeCall(phone)

                notify(notifIdFor(p.deviceId), simpleAlertNotification(
                    if (sent) "Caregiver alerted - $wearer"
                    else "Alert escalated to the wearable - $wearer",
                    if (sent) "An SMS was sent to $phone."
                    else "The phone could not send an SMS; the wearable's SIM was asked instead."))
            }
            delay(1_000)
        }
    }

    // ------------------------------------------------------------------ heat
    /**
     * Closes one heat minute for every wearer, whether or not readings came
     * in: a wearable that went quiet has to become "not measured", not a
     * frozen last-known-good level.
     */
    private fun runHeatLoop() = scope.launch {
        while (true) {
            val now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)          // on the minute

            // Weather fallback (opt-in) uses the first wearable with a GPS fix.
            val fix = connections.values.firstNotNullOfOrNull { c ->
                c.lastTelemetry.value?.takeIf { it.gpsValid }?.let { it.lat to it.lon }
            }
            runCatching { site.maybeRefreshWeather(fix?.first, fix?.second) }
            val reading = site.refresh()

            for (conn in connections.values.toList()) {
                if (!conn.heatEnabled) continue
                val before = conn.heat.assessment.value?.level ?: HeatLevel.GREEN
                val a = runCatching { conn.heat.tick(reading) }
                    .onFailure { Log.e(TAG, "heat tick failed for ${conn.displayName}", it) }
                    .getOrNull() ?: continue
                if (a.level > before && a.level >= HeatLevel.ORANGE) onHeatEscalation(conn, a)
                if (a.level < HeatLevel.ORANGE) notificationManager().cancel(heatNotifIdFor(conn.deviceId))
            }
            publishDevices()
        }
    }

    private suspend fun onHeatEscalation(conn: DeviceConnection, a: HeatAssessment) {
        val t = conn.lastTelemetry.value
        runCatching {
            db.events().insert(
                EventRecord(
                    occurredAt = a.at, deviceId = conn.deviceId,
                    patientRef = conn.record.patientRef,
                    kind = "HEAT_${a.level.name}", source = "heat_engine",
                    score = (a.psi ?: 0.0).toFloat(), hrBpm = (a.hr ?: 0.0).toFloat(),
                    gaitDeviation = 0f,
                    lat = t?.lat ?: 0.0, lon = t?.lon ?: 0.0,
                    acknowledged = false, smsSent = false,
                    note = a.reasons.joinToString("; ") +
                        (a.wbgtEff?.let { "; WBGT %.1f °C".format(it) } ?: "") +
                        (a.ctEst?.let { "; est. core %.1f °C".format(it) } ?: ""),
                )
            )
        }
        // The notification carries the level and the action, not vitals: it can
        // be read off a lock screen by whoever is holding the phone.
        notify(heatNotifIdFor(conn.deviceId), simpleAlertNotification(
            "Heat ${a.level.name.lowercase()} - ${conn.displayName}",
            a.actionText,
            channel = CHANNEL_HEAT,
        ))
    }

    private fun heatNotifIdFor(deviceId: String) = NOTIF_HEAT_BASE + (deviceId.hashCode() and 0xFF)

    /** Supervisor confirmed a heat collapse has been handled. */
    fun acknowledgeHeat(deviceId: String) {
        connectionFor(deviceId)?.heat?.acknowledge()
        notificationManager().cancel(heatNotifIdFor(deviceId))
    }

    fun startHeatShift(deviceId: String) {
        connectionFor(deviceId)?.heat?.startShift()
        publishDevices()
    }

    fun updateHeatProfile(deviceId: String, entry: HeatProfileStore.Entry) {
        heatProfiles.put(deviceId, entry)
        connectionFor(deviceId)?.let {
            it.heatEnabled = entry.enabled
            it.heat.updateProfile(entry.profile)
        }
        publishDevices()
    }

    // ------------------------------------------------------------- lifecycle
    private fun startForegroundCompat() {
        val notification = ongoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIF_ONGOING, notification, type)
        } else {
            startForeground(NOTIF_ONGOING, notification)
        }
    }

    private fun stopMonitoring() {
        connections.values.forEach { it.stop() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    // --------------------------------------------------------- notifications
    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Monitoring",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows which wearables HeatGuard is watching"
                setShowBadge(false)
            })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Fall alerts",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fall detection and SOS alerts"
                enableVibration(true)
                setBypassDnd(true)
            })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HEAT, "Heat warnings",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Orange and red heat-strain warnings for outdoor workers"
                enableVibration(true)
            })
    }

    private fun notify(id: Int, notification: Notification) {
        runCatching { notificationManager().notify(id, notification) }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoingNotification(): Notification {
        val live = connections.values.count { it.isConnected }
        val total = connections.size
        val detail = when {
            total == 0 -> "No wearables paired yet"
            live == 0 -> "Reconnecting to $total wearable${if (total == 1) "" else "s"}"
            live == total && total == 1 ->
                connections.values.first().let { c ->
                    val t = c.lastTelemetry.value
                    if (t != null) "${c.displayName} - ${t.bpm.toInt()} bpm, battery ${t.batteryPct}%"
                    else "${c.displayName} - connected"
                }
            else -> "$live of $total wearables connected"
        }
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("HeatGuard monitoring")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_monitor)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun countdownNotification(alert: PendingAlert): Notification {
        val seconds = (alert.remainingMs() / 1000).toInt()
        val who = alert.patientName.ifBlank { "the wearer" }
        return NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("Fall detected - $who - alerting in ${seconds}s")
            .setContentText("Tap \"I'm OK\" to cancel. Source: ${alert.source}")
            .setSmallIcon(R.drawable.ic_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(contentIntent(), true)
            .addAction(0, "I'm OK", PendingIntent.getService(
                this, alert.deviceId.hashCode(),
                Intent(this, MonitorService::class.java)
                    .setAction(ACTION_CANCEL_ALERT)
                    .putExtra(EXTRA_DEVICE_ID, alert.deviceId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    private fun simpleAlertNotification(
        title: String, body: String, channel: String = CHANNEL_ALERTS,
    ): Notification =
        NotificationCompat.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
}
