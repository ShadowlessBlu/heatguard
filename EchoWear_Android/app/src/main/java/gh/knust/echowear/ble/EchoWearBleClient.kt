package gh.knust.echowear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import gh.knust.echowear.crypto.PqcSession
import gh.knust.echowear.pairing.PairingManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

enum class LinkState {
    IDLE, CONNECTING, DISCOVERING, KEY_EXCHANGE, AUTHENTICATING,
    READY, NEEDS_PAIRING, DISCONNECTED
}

/**
 * BLE central for ONE EchoWear wearable.
 *
 * One instance per device, so a carer's phone can watch several patients at
 * once and each connection keeps its own session key, its own authentication
 * state and its own reconnect timer. Sharing a single client across devices
 * would mean one patient's disconnect resets another's crypto session.
 *
 * Connect sequence:
 *  1. connect and discover services
 *  2. request MTU 247, so a 150-byte sealed IMU batch is one notification
 *  3. enable notifications on telemetry / IMU / alert
 *  4. read IDENTITY, so we know which device this is before trusting anything
 *  5. run the ML-KEM-512 handshake
 *  6. prove ownership with HMAC over the device's challenge
 *  7. only now does the device start sending patient data
 *
 * GATT operations on Android must be issued one at a time - the stack
 * silently drops a second request while one is outstanding - so every write,
 * read and descriptor write goes through a serial queue.
 */
@SuppressLint("MissingPermission")
class EchoWearBleClient(
    private val context: Context,
    val address: String,
    private val pairing: PairingManager,
    /** Set when this device is already ours; null while pairing a new one. */
    private var ownerToken: ByteArray?,
) {
    companion object {
        private const val TAG = "EchoWearBle"
        private const val RECONNECT_DELAY_MS = 4_000L
        private const val KEM_WRITE_CHUNK = 160
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    val session = PqcSession()

    private var gatt: BluetoothGatt? = null
    private var shouldReconnect = true
    private var negotiatedMtu = 23

    private val _linkState = MutableStateFlow(LinkState.IDLE)
    val linkState = _linkState.asStateFlow()

    private val _identity = MutableStateFlow<DeviceIdentity?>(null)
    val identity = _identity.asStateFlow()

    private val _pairStatus = MutableStateFlow<PairStatus?>(null)
    val pairStatus = _pairStatus.asStateFlow()

    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry = _telemetry.asStateFlow()

    private val _imu = MutableSharedFlow<ImuBatch>(extraBufferCapacity = 64)
    val imu = _imu.asSharedFlow()

    private val _alerts = MutableSharedFlow<DeviceAlert>(extraBufferCapacity = 16)
    val alerts = _alerts.asSharedFlow()

    private val _undecryptable = MutableStateFlow(0)
    val undecryptable = _undecryptable.asStateFlow()

    /** Notifications that decrypted and parsed, so the screen can tell "nothing arrives" from "nothing decrypts". */
    private val _framesOk = MutableStateFlow(0)
    val framesOk = _framesOk.asStateFlow()

    /**
     * The session state the wearable itself reports in its STATUS characteristic
     * ("PQC-SECURED", "KEY-EXCHANGE", "IDLE", ...). The phone can only say "I derived a key";
     * only the band can say "I have the same one".
     */
    private val _bandSession = MutableStateFlow<String?>(null)
    val bandSession = _bandSession.asStateFlow()

    // Handshake confirmation and "linked but silent" recovery.
    private var awaitingKemConfirm = false
    private var kemAttempts = 0
    private var silentRecoveries = 0
    private var readyWatchdog: Runnable? = null

    /**
     * The owner token this phone wrote during a claim.
     *
     * It is deliberately NOT cleared when the claim succeeds. The pairing
     * screen finishes by asking the service to persist the token, and that
     * happens after the device has already answered "claimed" - so clearing
     * it here would leave the caller with nothing to store, and the next
     * connection would find a device it owns but cannot authenticate to.
     */
    @Volatile var pendingClaimCode: Int? = null
    @Volatile var pendingClaimToken: ByteArray? = null

    /** The outcome of the last pairing write, for the pairing screen. */
    private val _pairResult = MutableStateFlow<PairResult?>(null)
    val pairResult = _pairResult.asStateFlow()

    /** True once the device has confirmed this phone owns it. */
    private val _claimConfirmed = MutableStateFlow(false)
    val claimConfirmed = _claimConfirmed.asStateFlow()

    val deviceId: String? get() = _pairStatus.value?.deviceIdHex

    // ------------------------------------------------------- operation queue
    private sealed interface Op {
        data class EnableNotify(val uuid: UUID) : Op
        data class Read(val uuid: UUID) : Op
        data class Write(val uuid: UUID, val data: ByteArray) : Op
        data object RequestMtu : Op
    }

    private val queue = ArrayDeque<Op>()
    private var opInFlight = false

    private fun enqueue(op: Op) {
        synchronized(queue) { queue.add(op) }
        drain()
    }

    private fun drain() {
        synchronized(queue) {
            if (opInFlight) return
            val op = queue.poll() ?: return
            opInFlight = true
            if (!execute(op)) {
                opInFlight = false
                handler.post { drain() }
            }
        }
    }

    private fun opComplete() {
        synchronized(queue) { opInFlight = false }
        drain()
    }

    private fun execute(op: Op): Boolean {
        val g = gatt ?: return false
        return when (op) {
            is Op.RequestMtu -> g.requestMtu(247)
            is Op.EnableNotify -> {
                val chr = characteristic(op.uuid) ?: return false
                if (!g.setCharacteristicNotification(chr, true)) return false
                val cccd = chr.getDescriptor(EchoWearUuids.CCCD) ?: return false
                val value = if (chr.properties and
                    BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                ) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") run { cccd.value = value }
                    @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                }
            }
            is Op.Read -> characteristic(op.uuid)?.let { g.readCharacteristic(it) } ?: false
            is Op.Write -> {
                val chr = characteristic(op.uuid) ?: return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        chr, op.data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        chr.value = op.data
                        chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    @Suppress("DEPRECATION") g.writeCharacteristic(chr)
                }
            }
        }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(EchoWearUuids.SERVICE)?.getCharacteristic(uuid)

    // ---------------------------------------------------------------- public
    fun connect() {
        shouldReconnect = true
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: run {
            Log.e(TAG, "no adapter or bad address $address")
            _linkState.value = LinkState.DISCONNECTED
            return
        }
        _linkState.value = LinkState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        shouldReconnect = false
        disarmWatchdog()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        session.reset()
        _linkState.value = LinkState.IDLE
    }

    /**
     * Drop the link and bring it straight back, starting the whole secure handshake again.
     * For the "link is up but no readings" case, where a stale session on either side is the usual cause.
     */
    fun reconnect() {
        Log.i(TAG, "manual reconnect requested")
        silentRecoveries = 0
        kemAttempts = 0
        awaitingKemConfirm = false
        disarmWatchdog()
        session.reset()
        _telemetry.value = null
        _undecryptable.value = 0
        _framesOk.value = 0
        _bandSession.value = null
        shouldReconnect = true
        val g = gatt
        if (g != null) g.disconnect() else connect()
    }

    private fun disarmWatchdog() {
        readyWatchdog?.let { handler.removeCallbacks(it) }
        readyWatchdog = null
    }

    /**
     * Authenticated, so the wearable should now be sending. If ten seconds pass with no reading
     * decoded, assume the encrypted session is out of step and redo the handshake (twice at most).
     */
    private fun armWatchdog() {
        disarmWatchdog()
        val r = Runnable {
            if (_linkState.value == LinkState.READY && _telemetry.value == null) {
                if (silentRecoveries < 2) {
                    silentRecoveries++
                    Log.w(TAG, "no readings 10 s after READY (dropped=${_undecryptable.value}, " +
                            "band session=${_bandSession.value}); redoing the secure handshake, attempt $silentRecoveries")
                    beginKeyExchange()
                } else {
                    Log.e(TAG, "still no readings after $silentRecoveries handshake retries")
                }
            }
        }
        readyWatchdog = r
        handler.postDelayed(r, 10_000L)
    }

    private fun markReady() {
        _linkState.value = LinkState.READY
        armWatchdog()
    }

    /**
     * Disconnect once every queued operation has been answered.
     *
     * GATT writes are queued and answered asynchronously, so calling
     * disconnect() straight after enqueuing one throws it away. That is how
     * a freshly paired wearable ends up with no patient reference: the
     * SET_META write is issued and the socket is closed in the same
     * millisecond, and nothing ever reaches the device.
     */
    fun disconnectWhenIdle(timeoutMs: Long = 4_000L, onDone: () -> Unit = {}) {
        val deadline = System.currentTimeMillis() + timeoutMs
        lateinit var poll: Runnable
        poll = Runnable {
            val idle = synchronized(queue) { !opInFlight && queue.isEmpty() }
            if (idle || System.currentTimeMillis() > deadline) {
                if (!idle) Log.w(TAG, "queue did not drain before disconnect")
                disconnect()
                onDone()
            } else {
                handler.postDelayed(poll, 120)
            }
        }
        handler.post(poll)
    }

    fun setOwnerToken(token: ByteArray?) { ownerToken = token }

    fun sendControl(command: Byte, value: Byte) {
        enqueue(Op.Write(EchoWearUuids.CONTROL, ControlCommand.encode(command, value)))
    }

    fun acknowledgeAlertOnDevice() = sendControl(ControlCommand.ALERT_ACK, 1)
    fun requestGpsFix() = sendControl(ControlCommand.GPS_WAKE, 1)
    fun setImuStream(on: Boolean) = sendControl(ControlCommand.IMU_STREAM, if (on) 1 else 0)
    fun requestCaregiverSms() = sendControl(ControlCommand.MANUAL_SMS, 1)

    /** Claim, or release, responsibility for the alert the device just sent. */
    fun takeOverAlert(owned: Boolean) =
        sendControl(ControlCommand.ALERT_TAKEOVER, if (owned) 1 else 0)

    /**
     * "The caregiver SMS has gone out from this phone."
     *
     * A plain takeover only defers the wearable; it gives up and sends after
     * a minute in case the phone died holding the alert. Once the message has
     * actually gone, that safety net becomes a duplicate text, so this
     * latches the wearable's own send off for good.
     */
    fun reportSmsSentByPhone() = sendControl(ControlCommand.ALERT_TAKEOVER, 2)
    fun openPairingWindow() = sendControl(ControlCommand.OPEN_PAIRING, 1)
    fun factoryResetDevice() = sendControl(ControlCommand.FACTORY_RESET, 1)

    /** Refresh the pairing status (and with it a fresh challenge). */
    fun refreshPairStatus() {
        enqueue(Op.Write(EchoWearUuids.PAIRING, PacketParser.encodePairBegin()))
        enqueue(Op.Read(EchoWearUuids.PAIRING))
    }

    /**
     * Claim an unclaimed device. The code comes from the wearable's screen
     * (touch build) or its label (headless build).
     */
    fun claim(code: Int) {
        val token = pairing.newOwnerToken()
        pendingClaimToken = token
        pendingClaimCode = code
        _pairResult.value = null
        _claimConfirmed.value = false
        enqueue(Op.Write(EchoWearUuids.PAIRING, PacketParser.encodePairClaim(code, token)))
        enqueue(Op.Read(EchoWearUuids.PAIRING))
        enqueue(Op.Read(EchoWearUuids.IDENTITY))
    }

    fun setDeviceMeta(patientRef: String, label: String) {
        enqueue(Op.Write(EchoWearUuids.PAIRING,
                         PacketParser.encodePairSetMeta(patientRef, label)))
        enqueue(Op.Read(EchoWearUuids.IDENTITY))
    }

    fun unclaim() {
        val token = ownerToken ?: return
        val challenge = _pairStatus.value?.challenge ?: return
        val proof = pairing.authProof(token, challenge)
        enqueue(Op.Write(EchoWearUuids.PAIRING, PacketParser.encodePairUnclaim(proof)))
        enqueue(Op.Read(EchoWearUuids.PAIRING))
    }

    // ------------------------------------------------------------ KEM state
    private val devicePublicKey = ByteArray(KEM_PUBLICKEY_BYTES)
    private var publicKeyBytesReceived = 0
    private var kemCiphertext: ByteArray? = null
    private var kemWriteOffset = 0

    private fun beginKeyExchange() {
        _linkState.value = LinkState.KEY_EXCHANGE
        publicKeyBytesReceived = 0
        kemWriteOffset = 0
        kemCiphertext = null
        session.reset()
        enqueue(Op.Write(EchoWearUuids.KEM, byteArrayOf(0xFF.toByte())))
        enqueue(Op.Read(EchoWearUuids.KEM))
    }

    private fun onKemRead(value: ByteArray) {
        if (value.size < 3) {
            Log.w(TAG, "short KEM read, aborting handshake")
            afterKeyExchange()
            return
        }
        val offset = ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        val chunk = value.copyOfRange(2, value.size)
        if (offset + chunk.size > KEM_PUBLICKEY_BYTES) return
        System.arraycopy(chunk, 0, devicePublicKey, offset, chunk.size)
        publicKeyBytesReceived = maxOf(publicKeyBytesReceived, offset + chunk.size)

        if (publicKeyBytesReceived < KEM_PUBLICKEY_BYTES) {
            enqueue(Op.Read(EchoWearUuids.KEM))
            return
        }

        val ct = try {
            session.encapsulate(devicePublicKey)
        } catch (e: Exception) {
            Log.e(TAG, "encapsulation failed: ${e.message}")
            afterKeyExchange()
            return
        }
        kemCiphertext = ct
        kemWriteOffset = 0
        writeNextCiphertextChunk()
    }

    private fun writeNextCiphertextChunk() {
        val ct = kemCiphertext ?: return
        if (kemWriteOffset >= ct.size) {
            kemCiphertext = null
            // The phone has derived its key, but only the band can confirm it did too. Ask before going on:
            // proceeding on a half-established session looks exactly like "linked, waiting for readings".
            Log.i(TAG, "ML-KEM-512 ciphertext delivered; asking the band to confirm its session")
            awaitingKemConfirm = true
            enqueue(Op.Read(EchoWearUuids.STATUS))
            return
        }
        val n = minOf(KEM_WRITE_CHUNK, ct.size - kemWriteOffset, maxOf(20, negotiatedMtu - 5))
        val payload = ByteArray(n + 2)
        payload[0] = ((kemWriteOffset shr 8) and 0xFF).toByte()
        payload[1] = (kemWriteOffset and 0xFF).toByte()
        System.arraycopy(ct, kemWriteOffset, payload, 2, n)
        kemWriteOffset += n
        enqueue(Op.Write(EchoWearUuids.KEM, payload))
    }

    /**
     * With the session key in place, prove we own the device. Until this
     * succeeds a claimed wearable sends nothing, so an unauthenticated link
     * looks alive but carries no patient data - which is the intended
     * behaviour, not a fault.
     */
    private fun afterKeyExchange() {
        refreshPairStatus()
        handler.postDelayed({ attemptAuth() }, 300)
    }

    private fun attemptAuth() {
        val status = _pairStatus.value
        val token = ownerToken

        if (status != null && !status.claimed) {
            // Unclaimed: the device streams during its pairing window so the
            // installer can see live values while setting it up.
            if (status.pairingOpen) markReady() else _linkState.value = LinkState.NEEDS_PAIRING
            return
        }
        if (token == null || status == null) {
            _linkState.value = LinkState.NEEDS_PAIRING
            Log.w(TAG, "device is claimed but this phone holds no owner token")
            return
        }
        _linkState.value = LinkState.AUTHENTICATING
        val proof = pairing.authProof(token, status.challenge)
        enqueue(Op.Write(EchoWearUuids.PAIRING, PacketParser.encodePairAuth(proof)))
        enqueue(Op.Read(EchoWearUuids.PAIRING))
    }

    // --------------------------------------------------------- GATT callback
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _linkState.value = LinkState.DISCOVERING
                synchronized(queue) { queue.clear(); opInFlight = false }
                awaitingKemConfirm = false
                kemAttempts = 0
                g.discoverServices()
            } else {
                Log.w(TAG, "$address disconnected (status $status)")
                disarmWatchdog()
                session.reset()
                _telemetry.value = null
                _linkState.value = LinkState.DISCONNECTED
                runCatching { g.close() }
                gatt = null
                if (shouldReconnect) {
                    handler.postDelayed({ if (shouldReconnect) connect() }, RECONNECT_DELAY_MS)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS ||
                g.getService(EchoWearUuids.SERVICE) == null
            ) {
                Log.e(TAG, "no EchoWear service on $address")
                g.disconnect()
                return
            }
            // MTU first: a 150-byte sealed IMU batch will not fit in the
            // default 23-byte MTU and would be dropped.
            enqueue(Op.RequestMtu)
            enqueue(Op.EnableNotify(EchoWearUuids.ALERT))
            enqueue(Op.EnableNotify(EchoWearUuids.TELEMETRY))
            enqueue(Op.EnableNotify(EchoWearUuids.IMU))
            enqueue(Op.Read(EchoWearUuids.IDENTITY))
            enqueue(Op.Read(EchoWearUuids.PAIRING))
            enqueue(Op.Read(EchoWearUuids.STATUS))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            Log.i(TAG, "MTU negotiated: $mtu")
            opComplete()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD write failed for ${descriptor.characteristic.uuid}: $status")
            }
            opComplete()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(chr.uuid, value)
            opComplete()
        }

        @Deprecated("Pre-Android 13 signature")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic, status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handleRead(chr.uuid, chr.value ?: ByteArray(0))
                }
                opComplete()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic, status: Int,
        ) {
            if (chr.uuid == EchoWearUuids.KEM && kemCiphertext != null) {
                opComplete()
                writeNextCiphertextChunk()
                return
            }
            opComplete()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic, value: ByteArray,
        ) = handleNotification(chr.uuid, value)

        @Deprecated("Pre-Android 13 signature")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleNotification(chr.uuid, chr.value ?: return)
            }
        }
    }

    private fun handleRead(uuid: UUID, value: ByteArray) {
        when (uuid) {
            EchoWearUuids.IDENTITY -> {
                DeviceIdentity.parse(String(value, Charsets.UTF_8))?.let {
                    _identity.value = it
                    Log.i(TAG, "identity: ${it.serial} (${it.type.label}) fw ${it.firmware}")
                    if (it.protocol != PROTOCOL_VERSION) {
                        Log.w(TAG, "device speaks protocol v${it.protocol}, " +
                                "this app speaks v$PROTOCOL_VERSION")
                    }
                }
            }

            EchoWearUuids.PAIRING -> {
                val st = PacketParser.parsePairStatus(value) ?: return
                _pairStatus.value = st

                _pairResult.value = st.lastResult

                // A claim we issued has landed: adopt the token so this
                // connection is authenticated from here on. The token stays in
                // pendingClaimToken until the service has written it to the
                // encrypted store - see the field comment.
                val token = pendingClaimToken
                if (token != null && st.claimed && st.authenticated) {
                    ownerToken = token
                    _claimConfirmed.value = true
                    if (_linkState.value != LinkState.READY) markReady()
                } else if (st.authenticated) {
                    // The pair status is re-read for other reasons while connected; only the first
                    // transition into READY starts the "is anything arriving?" watchdog.
                    if (_linkState.value != LinkState.READY) markReady()
                } else if (st.claimed && token == null) {
                    // Claimed by somebody, and not by us on this connection.
                    _linkState.value = LinkState.NEEDS_PAIRING
                }
            }

            EchoWearUuids.STATUS -> {
                val text = String(value, Charsets.UTF_8)
                Log.i(TAG, "status: $text")
                val bandState = Regex("session=([^;]+)").find(text)?.groupValues?.get(1)
                _bandSession.value = bandState
                if (awaitingKemConfirm) {
                    awaitingKemConfirm = false
                    when {
                        bandState == "PQC-SECURED" -> {
                            Log.i(TAG, "band confirms the session: ML-KEM-512 session established")
                            kemAttempts = 0
                            afterKeyExchange()
                        }
                        kemAttempts < 2 -> {
                            kemAttempts++
                            Log.w(TAG, "band reports session=$bandState, not PQC-SECURED; retrying handshake ($kemAttempts)")
                            beginKeyExchange()
                        }
                        else -> {
                            Log.e(TAG, "band never confirmed the session (last state $bandState); continuing unsealed")
                            afterKeyExchange()
                        }
                    }
                } else if (text.contains("pqc=1")) {
                    kemAttempts = 0
                    beginKeyExchange()
                } else {
                    Log.w(TAG, "firmware reports no PQC support; link is not sealed")
                    afterKeyExchange()
                }
            }

            EchoWearUuids.KEM -> onKemRead(value)
        }
    }

    private fun handleNotification(uuid: UUID, sealed: ByteArray) {
        val plain = session.open(sealed)
        if (plain == null) {
            // Expected for the frame or two that arrive between connect and
            // handshake completion; a persistent count means a key mismatch.
            _undecryptable.value = _undecryptable.value + 1
            return
        }
        _framesOk.value = _framesOk.value + 1
        when (uuid) {
            EchoWearUuids.TELEMETRY ->
                PacketParser.parseTelemetry(plain)?.let {
                    if (_telemetry.value == null) Log.i(TAG, "first reading decoded")
                    _telemetry.value = it
                    silentRecoveries = 0
                    disarmWatchdog()
                }
            EchoWearUuids.IMU ->
                PacketParser.parseImuBatch(plain)?.let { _imu.tryEmit(it) }
            EchoWearUuids.ALERT ->
                PacketParser.parseAlert(plain)?.let { _alerts.tryEmit(it) }
        }
    }
}
