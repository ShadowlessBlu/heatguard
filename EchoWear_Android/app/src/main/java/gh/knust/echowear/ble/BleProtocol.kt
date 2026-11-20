package gh.knust.echowear.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Wire format shared with the wearable's echowear_ble.h - protocol v2.
 *
 * The firmware sends packed little-endian structs, so every offset here is
 * fixed by that header. If a field is added on the firmware side it must be
 * appended, never inserted, or these offsets shift. The firmware pins each
 * size with a static_assert, so a mismatch fails its build rather than
 * silently corrupting readings here.
 *
 * v2 adds the 8-byte device id to telemetry and alert frames, so a phone
 * watching several wearables can attribute every reading to the right patient
 * in an exported log - not merely by which socket it arrived on.
 */
object EchoWearUuids {
    val SERVICE: UUID = UUID.fromString("6e400000-b5a3-f393-e0a9-e50e24dcca9e")
    val TELEMETRY: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val IMU: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val ALERT: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
    val CONTROL: UUID = UUID.fromString("6e400005-b5a3-f393-e0a9-e50e24dcca9e")
    val KEM: UUID = UUID.fromString("6e400006-b5a3-f393-e0a9-e50e24dcca9e")
    val STATUS: UUID = UUID.fromString("6e400007-b5a3-f393-e0a9-e50e24dcca9e")
    val IDENTITY: UUID = UUID.fromString("6e400008-b5a3-f393-e0a9-e50e24dcca9e")
    val PAIRING: UUID = UUID.fromString("6e400009-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

const val PROTOCOL_VERSION = 2
const val IMU_BATCH_SAMPLES = 10
const val KEM_PUBLICKEY_BYTES = 800
const val KEM_CIPHERTEXT_BYTES = 768
const val DEVICE_ID_BYTES = 8
const val OWNER_TOKEN_BYTES = 32
const val PAIR_CHALLENGE_BYTES = 16

/** pairing_remaining_s when the wearable's window has no deadline. */
const val PAIR_NO_TIMEOUT = 0xFFFF

/** PATIENT_REF_LEN and DEVICE_LABEL_LEN in echowear_identity.h. */
const val PATIENT_REF_MAX = 16
const val DEVICE_LABEL_MAX = 24

enum class DeviceType(val code: Int, val label: String) {
    UNKNOWN(0, "Unknown"),
    TOUCH_LCD(1, "Touch LCD"),
    HEADLESS(2, "Headless");

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class AlertLevel(val code: Int) {
    NONE(0), FALL_PREDICTED(1), FALL_DETECTED(2),
    FALL_CONFIRMED(3), CARDIAC_FALL(4), MANUAL_SOS(5);

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: NONE
    }

    val isEmergency: Boolean
        get() = this == FALL_DETECTED || this == FALL_CONFIRMED ||
                this == CARDIAC_FALL || this == MANUAL_SOS
}

object ControlCommand {
    const val PACKET_TYPE: Byte = 0xC3.toByte()
    const val GSM_ENABLE: Byte = 1
    const val MANUAL_SMS: Byte = 2
    const val PQC_START: Byte = 3
    const val ALERT_ACK: Byte = 4
    const val TLM_LOGGING: Byte = 5
    const val ML_LOGGING: Byte = 6
    const val IMU_STREAM: Byte = 7
    const val GPS_WAKE: Byte = 8
    const val BUZZER_TEST: Byte = 9
    const val RESET_GAIT: Byte = 10
    const val OPEN_PAIRING: Byte = 11
    const val FACTORY_RESET: Byte = 12

    /**
     * "This phone owns the alert you just sent me."
     *
     * The wearable keeps showing its countdown but does not fire its own SIM,
     * so the caregiver gets one message rather than two. It lapses on the
     * device if the link drops or the phone never acts - see the firmware's
     * countdown block.
     */
    const val ALERT_TAKEOVER: Byte = 13

    fun encode(command: Byte, value: Byte): ByteArray =
        byteArrayOf(PACKET_TYPE, command, value)
}

/** Pairing opcodes, matching PairOp in echowear_identity.h. */
object PairOp {
    const val BEGIN: Byte = 0x01
    const val CLAIM: Byte = 0x02
    const val AUTH: Byte = 0x03
    const val SET_META: Byte = 0x04
    const val UNCLAIM: Byte = 0x05
    const val OPEN: Byte = 0x06
}

enum class PairResult(val code: Int, val message: String) {
    OK(0, "OK"),
    ERR_STATE(1, "The device is not in a state to do that"),
    ERR_CODE(2, "Wrong pairing code"),
    ERR_CLOSED(3, "The pairing window has closed"),
    ERR_CLAIMED(4, "This device already belongs to someone else"),
    ERR_AUTH(5, "Could not prove ownership of this device"),
    ERR_LENGTH(6, "Malformed request");

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: ERR_STATE
    }
}

/** Mirrors EchoWearPairStatus - 32 bytes, plaintext. */
data class PairStatus(
    val protocolVersion: Int,
    val deviceType: DeviceType,
    val claimed: Boolean,
    val authenticated: Boolean,
    val pairingOpen: Boolean,
    val lastResult: PairResult,
    val pairingRemainingSec: Int,
    val deviceId: ByteArray,
    val challenge: ByteArray,
) {
    val deviceIdHex: String get() = deviceId.joinToString("") { "%02x".format(it) }

    /** True when the window has no deadline: it stays open until claimed. */
    val pairingHasNoDeadline: Boolean
        get() = pairingOpen && pairingRemainingSec == PAIR_NO_TIMEOUT
    override fun equals(other: Any?) = this === other
    override fun hashCode() = deviceIdHex.hashCode()
}

/** Parsed from the plaintext IDENTITY characteristic string. */
data class DeviceIdentity(
    val serial: String,
    val type: DeviceType,
    val firmware: String,
    val protocol: Int,
    val label: String,
    val patientRef: String,
    val claimed: Boolean,
) {
    companion object {
        /** "serial=EW-..;type=1;fw=2.1.0;proto=2;label=..;patient=..;claimed=1" */
        fun parse(raw: String): DeviceIdentity? {
            val map = raw.split(';')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
                }.toMap()
            val serial = map["serial"] ?: return null
            return DeviceIdentity(
                serial = serial,
                type = DeviceType.from(map["type"]?.toIntOrNull() ?: 0),
                firmware = map["fw"] ?: "?",
                protocol = map["proto"]?.toIntOrNull() ?: 1,
                label = map["label"]?.ifBlank { serial } ?: serial,
                patientRef = map["patient"].orEmpty(),
                claimed = map["claimed"] == "1",
            )
        }
    }
}

data class Telemetry(
    val deviceId: String,
    val fallState: Int,
    val motionTier: Int,
    val worn: Boolean,
    val gpsValid: Boolean,
    val gsmUp: Boolean,
    val gaitWarm: Boolean,
    val pqcActive: Boolean,
    val claimed: Boolean,
    val authenticated: Boolean,
    val deviceUptimeMs: Long,
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val irRaw: Long, val redRaw: Long,
    val bpm: Float,
    val sdnnMs: Float, val rmssdMs: Float, val pnn50Pct: Float, val rsaIndex: Float,
    val busVoltageV: Float, val currentMa: Float, val powerMw: Float,
    val gaitCadence: Float, val gaitVariance: Float, val gaitSri: Float,
    val lat: Double, val lon: Double,
    val satellites: Int,
    val batteryPct: Int,
    val receivedAt: Long = System.currentTimeMillis(),
)

data class ImuBatch(
    val seq: Int,
    val count: Int,
    val t0Us: Long,
    val dtUs: Int,
    /** [count][6] as ax, ay, az in g and gx, gy, gz in deg/s. */
    val samples: Array<FloatArray>,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = seq
}

data class DeviceAlert(
    val deviceId: String,
    val level: AlertLevel,
    val shouldRescue: Boolean,
    val outliers: Int,
    val deviceTimeMs: Long,
    val score: Float,
    val hrBpm: Float,
    val gaitDeviation: Float,
    val lat: Double,
    val lon: Double,
    val receivedAt: Long = System.currentTimeMillis(),
)

object PacketParser {

    private fun buf(bytes: ByteArray) =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.readId(): String {
        val id = ByteArray(DEVICE_ID_BYTES)
        get(id)
        return id.joinToString("") { "%02x".format(it) }
    }

    /**
     * Telemetry, packed:
     *   4 header + 8 device id + 4 t_ms + 12 accel + 12 gyro + 8 ir/red
     * + 4 bpm + 16 HRV + 12 power + 12 gait + 16 GPS + 2 sats + 2 battery = 112.
     */
    const val TELEMETRY_SIZE = 112

    fun parseTelemetry(bytes: ByteArray): Telemetry? {
        if (bytes.size < TELEMETRY_SIZE) return null
        val b = buf(bytes)
        if (b.get().toInt() != 0) return null           // type
        val fallState = b.get().toInt() and 0xFF
        val motionTier = b.get().toInt() and 0xFF
        val flags = b.get().toInt() and 0xFF
        val deviceId = b.readId()
        val tMs = b.int.toLong() and 0xFFFFFFFFL
        val ax = b.float; val ay = b.float; val az = b.float
        val gx = b.float; val gy = b.float; val gz = b.float
        val ir = b.int.toLong() and 0xFFFFFFFFL
        val red = b.int.toLong() and 0xFFFFFFFFL
        val bpm = b.float
        val sdnn = b.float; val rmssd = b.float; val pnn50 = b.float; val rsa = b.float
        val volts = b.float; val ma = b.float; val mw = b.float
        val cadence = b.float; val variance = b.float; val sri = b.float
        // The firmware struct is packed, so the doubles are NOT 8-byte aligned;
        // ByteBuffer handles that because we never call align().
        val lat = b.double; val lon = b.double
        val sats = b.short.toInt() and 0xFFFF
        val batt = b.short.toInt() and 0xFFFF

        return Telemetry(
            deviceId = deviceId,
            fallState = fallState,
            motionTier = motionTier,
            worn = flags and 0x01 != 0,
            gpsValid = flags and 0x02 != 0,
            gsmUp = flags and 0x04 != 0,
            gaitWarm = flags and 0x08 != 0,
            pqcActive = flags and 0x10 != 0,
            claimed = flags and 0x20 != 0,
            authenticated = flags and 0x40 != 0,
            deviceUptimeMs = tMs,
            ax = ax, ay = ay, az = az, gx = gx, gy = gy, gz = gz,
            irRaw = ir, redRaw = red, bpm = bpm,
            sdnnMs = sdnn, rmssdMs = rmssd, pnn50Pct = pnn50, rsaIndex = rsa,
            busVoltageV = volts, currentMa = ma, powerMw = mw,
            gaitCadence = cadence, gaitVariance = variance, gaitSri = sri,
            lat = lat, lon = lon, satellites = sats, batteryPct = batt,
        )
    }

    /**
     * IMU batch: 1+1+2+4+2 + 6*10*2 = 130 bytes. Deliberately carries no
     * device id - at 20 Hz across several wearables those 8 bytes are real
     * airtime, and the classifier that consumes this stream is per-connection.
     */
    const val IMU_BATCH_SIZE = 130

    fun parseImuBatch(bytes: ByteArray): ImuBatch? {
        if (bytes.size < IMU_BATCH_SIZE) return null
        val b = buf(bytes)
        if (b.get().toInt() != 1) return null
        val count = (b.get().toInt() and 0xFF).coerceAtMost(IMU_BATCH_SAMPLES)
        val seq = b.short.toInt() and 0xFFFF
        val t0 = b.int.toLong() and 0xFFFFFFFFL
        val dt = b.short.toInt() and 0xFFFF

        val ax = ShortArray(IMU_BATCH_SAMPLES) { b.short }
        val ay = ShortArray(IMU_BATCH_SAMPLES) { b.short }
        val az = ShortArray(IMU_BATCH_SAMPLES) { b.short }
        val gx = ShortArray(IMU_BATCH_SAMPLES) { b.short }
        val gy = ShortArray(IMU_BATCH_SAMPLES) { b.short }
        val gz = ShortArray(IMU_BATCH_SAMPLES) { b.short }

        val samples = Array(count) { i ->
            floatArrayOf(
                ax[i] / 1000f, ay[i] / 1000f, az[i] / 1000f,   // milli-g -> g
                gx[i] / 10f, gy[i] / 10f, gz[i] / 10f,          // deci-dps -> dps
            )
        }
        return ImuBatch(seq, count, t0, dt, samples)
    }

    /** Alert: 4 header + 8 device id + 4 t_ms + 12 floats + 16 GPS = 44 bytes. */
    const val ALERT_SIZE = 44

    fun parseAlert(bytes: ByteArray): DeviceAlert? {
        if (bytes.size < ALERT_SIZE) return null
        val b = buf(bytes)
        if (b.get().toInt() != 2) return null
        val level = AlertLevel.from(b.get().toInt() and 0xFF)
        val rescue = (b.get().toInt() and 0xFF) != 0
        val outliers = b.get().toInt() and 0xFF
        val deviceId = b.readId()
        val tMs = b.int.toLong() and 0xFFFFFFFFL
        val score = b.float
        val hr = b.float
        val dev = b.float
        val lat = b.double
        val lon = b.double
        return DeviceAlert(deviceId, level, rescue, outliers, tMs, score, hr,
                           dev, lat, lon)
    }

    /** Pair status: 6 flags + 2 remaining + 8 id + 16 challenge = 32 bytes. */
    const val PAIR_STATUS_SIZE = 32

    fun parsePairStatus(bytes: ByteArray): PairStatus? {
        if (bytes.size < PAIR_STATUS_SIZE) return null
        val b = buf(bytes)
        val proto = b.get().toInt() and 0xFF
        val type = DeviceType.from(b.get().toInt() and 0xFF)
        val claimed = (b.get().toInt() and 0xFF) != 0
        val authed = (b.get().toInt() and 0xFF) != 0
        val open = (b.get().toInt() and 0xFF) != 0
        val result = PairResult.from(b.get().toInt() and 0xFF)
        val remaining = b.short.toInt() and 0xFFFF
        val id = ByteArray(DEVICE_ID_BYTES); b.get(id)
        val challenge = ByteArray(PAIR_CHALLENGE_BYTES); b.get(challenge)
        return PairStatus(proto, type, claimed, authed, open, result,
                          remaining, id, challenge)
    }

    // ------------------------------------------------------------- encoders
    fun encodePairBegin(): ByteArray = byteArrayOf(PairOp.BEGIN)

    fun encodePairOpen(): ByteArray = byteArrayOf(PairOp.OPEN)

    /** [op][3-byte BCD-free code, big endian][32-byte owner token] */
    fun encodePairClaim(code: Int, ownerToken: ByteArray): ByteArray {
        require(ownerToken.size == OWNER_TOKEN_BYTES)
        val out = ByteArray(1 + 3 + OWNER_TOKEN_BYTES)
        out[0] = PairOp.CLAIM
        out[1] = ((code shr 16) and 0xFF).toByte()
        out[2] = ((code shr 8) and 0xFF).toByte()
        out[3] = (code and 0xFF).toByte()
        System.arraycopy(ownerToken, 0, out, 4, OWNER_TOKEN_BYTES)
        return out
    }

    fun encodePairAuth(hmac: ByteArray): ByteArray {
        require(hmac.size == 32)
        return byteArrayOf(PairOp.AUTH) + hmac
    }

    fun encodePairUnclaim(hmac: ByteArray): ByteArray {
        require(hmac.size == 32)
        return byteArrayOf(PairOp.UNCLAIM) + hmac
    }

    /**
     * [op]<patient ref>\0<device label>
     *
     * Truncation is by byte, not by character, and never mid-sequence. The
     * firmware copies a fixed number of bytes into a fixed buffer, so
     * trimming to 16 *characters* of, say, an Akan or Ewe name can hand it
     * more bytes than fit and leave half a UTF-8 sequence on the device -
     * which then reads back as a broken string in the identity record.
     */
    fun encodePairSetMeta(patientRef: String, label: String): ByteArray {
        val ref = truncateUtf8(patientRef, PATIENT_REF_MAX)
        val lab = truncateUtf8(label, DEVICE_LABEL_MAX)
        return byteArrayOf(PairOp.SET_META) + ref + byteArrayOf(0) + lab
    }

    private fun truncateUtf8(value: String, maxBytes: Int): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var end = maxBytes
        // A UTF-8 continuation byte is 10xxxxxx; step back off one.
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
        return raw.copyOfRange(0, end)
    }
}
