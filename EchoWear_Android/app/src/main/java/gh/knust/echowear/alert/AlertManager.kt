package gh.knust.echowear.alert

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import gh.knust.echowear.ble.AlertLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.settings by preferencesDataStore("echowear_settings")

object SettingsKeys {
    val CAREGIVER_PHONE = stringPreferencesKey("caregiver_phone")
    val CAREGIVER_NAME = stringPreferencesKey("caregiver_name")
    val WEARER_NAME = stringPreferencesKey("wearer_name")
    val SMS_ENABLED = booleanPreferencesKey("sms_enabled")
    val CALL_ENABLED = booleanPreferencesKey("call_enabled")
    val PHONE_ML_ENABLED = booleanPreferencesKey("phone_ml_enabled")

    // --- ported from the React AccessibilityBar / settings-context ---------
    val LARGE_TEXT = booleanPreferencesKey("large_text")
    val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
    val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
    val TEXT_SCALE = floatPreferencesKey("text_scale")
    val ROLE = stringPreferencesKey("role")
    val COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds")
    val CLINICIAN_NAME = stringPreferencesKey("clinician_name")
    val FACILITY = stringPreferencesKey("facility")
}

/**
 * Who is holding the phone. Ported from the dashboard's role guard.
 *
 * This is a presentation role, not a security boundary - the app is on one
 * person's phone and the data is already theirs. What it actually buys is
 * that a patient using EchoWear as their own fall alarm never sees a ward
 * roster, and a clinician reviewing three patients is not made to navigate a
 * single-wearer home screen.
 */
enum class AppRole(val code: String, val label: String) {
    WEARER("wearer", "Wearer"),
    CLINICIAN("clinician", "Clinician"),
    ADMIN("admin", "Administrator");

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: WEARER
    }
}

class SettingsStore(private val context: Context) {
    val caregiverPhone: Flow<String> =
        context.settings.data.map { it[SettingsKeys.CAREGIVER_PHONE] ?: "" }
    val caregiverName: Flow<String> =
        context.settings.data.map { it[SettingsKeys.CAREGIVER_NAME] ?: "" }
    val wearerName: Flow<String> =
        context.settings.data.map { it[SettingsKeys.WEARER_NAME] ?: "" }
    val smsEnabled: Flow<Boolean> =
        context.settings.data.map { it[SettingsKeys.SMS_ENABLED] ?: true }
    val callEnabled: Flow<Boolean> =
        context.settings.data.map { it[SettingsKeys.CALL_ENABLED] ?: false }
    val phoneMlEnabled: Flow<Boolean> =
        context.settings.data.map { it[SettingsKeys.PHONE_ML_ENABLED] ?: true }

    val largeText: Flow<Boolean> =
        context.settings.data.map { it[SettingsKeys.LARGE_TEXT] ?: false }
    val highContrast: Flow<Boolean> =
        context.settings.data.map { it[SettingsKeys.HIGH_CONTRAST] ?: false }
    val reduceMotion: Flow<Boolean> =
        context.settings.data.map { it[SettingsKeys.REDUCE_MOTION] ?: false }
    val textScale: Flow<Float> =
        context.settings.data.map { it[SettingsKeys.TEXT_SCALE] ?: 1.0f }
    val role: Flow<AppRole> =
        context.settings.data.map { AppRole.from(it[SettingsKeys.ROLE]) }
    /**
     * False until someone picks "Patient or caregiver" or "Doctor" on the first
     * screen. There is no sign-in in this testing build: the choice is a
     * preference, and "Switch role" simply clears it.
     */
    val roleChosen: Flow<Boolean> =
        context.settings.data.map { it[SettingsKeys.ROLE] != null }
    val countdownSeconds: Flow<Int> =
        context.settings.data.map { it[SettingsKeys.COUNTDOWN_SECONDS] ?: 30 }
    val clinicianName: Flow<String> =
        context.settings.data.map { it[SettingsKeys.CLINICIAN_NAME] ?: "" }
    val facility: Flow<String> =
        context.settings.data.map { it[SettingsKeys.FACILITY] ?: "" }

    suspend fun setCaregiverPhone(v: String) {
        context.settings.edit { it[SettingsKeys.CAREGIVER_PHONE] = v }
    }
    suspend fun setCaregiverName(v: String) {
        context.settings.edit { it[SettingsKeys.CAREGIVER_NAME] = v }
    }
    suspend fun setWearerName(v: String) {
        context.settings.edit { it[SettingsKeys.WEARER_NAME] = v }
    }
    suspend fun setSmsEnabled(v: Boolean) {
        context.settings.edit { it[SettingsKeys.SMS_ENABLED] = v }
    }
    suspend fun setCallEnabled(v: Boolean) {
        context.settings.edit { it[SettingsKeys.CALL_ENABLED] = v }
    }
    suspend fun setPhoneMlEnabled(v: Boolean) {
        context.settings.edit { it[SettingsKeys.PHONE_ML_ENABLED] = v }
    }
    suspend fun setLargeText(v: Boolean) {
        context.settings.edit { it[SettingsKeys.LARGE_TEXT] = v }
    }
    suspend fun setHighContrast(v: Boolean) {
        context.settings.edit { it[SettingsKeys.HIGH_CONTRAST] = v }
    }
    suspend fun setReduceMotion(v: Boolean) {
        context.settings.edit { it[SettingsKeys.REDUCE_MOTION] = v }
    }
    suspend fun setTextScale(v: Float) {
        context.settings.edit { it[SettingsKeys.TEXT_SCALE] = v.coerceIn(0.85f, 1.8f) }
    }
    suspend fun setRole(v: AppRole) {
        context.settings.edit { it[SettingsKeys.ROLE] = v.code }
    }
    /** Back to the "Who is using EchoWear?" screen. */
    suspend fun clearRole() {
        context.settings.edit { it.remove(SettingsKeys.ROLE) }
    }
    suspend fun setCountdownSeconds(v: Int) {
        context.settings.edit { it[SettingsKeys.COUNTDOWN_SECONDS] = v.coerceIn(5, 120) }
    }
    suspend fun setClinicianName(v: String) {
        context.settings.edit { it[SettingsKeys.CLINICIAN_NAME] = v }
    }
    suspend fun setFacility(v: String) {
        context.settings.edit { it[SettingsKeys.FACILITY] = v }
    }
}

/**
 * One alert counting down, belonging to exactly one wearable.
 *
 * deviceId and patientName are carried on the alert rather than looked up
 * later because by the time the countdown expires the device may have
 * disconnected, and an SMS that cannot say who it is about is close to
 * useless to whoever receives it.
 */
data class PendingAlert(
    val level: AlertLevel,
    val source: String,
    val score: Float,
    val hrBpm: Float,
    val lat: Double,
    val lon: Double,
    val raisedAt: Long,
    val countdownMs: Long,
    val deviceId: String,
    val patientName: String,
    val patientRef: String = "",
    val deviceSerial: String = "",
    val caregiverPhone: String = "",
    val eventId: Long = -1,
) {
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        (countdownMs - (now - raisedAt)).coerceAtLeast(0)

    fun remainingSeconds(now: Long = System.currentTimeMillis()): Int =
        (remainingMs(now) / 1000).toInt()
}

/**
 * Owns the escalation path: raise -> countdown -> SMS -> optional call.
 *
 * The countdown exists because a false positive that immediately dials a
 * caregiver is worse than a slightly slower true positive; 30 seconds is long
 * enough for a wearer who is fine to cancel and short enough to matter when
 * they are not.
 *
 * Alerts are held per wearable. A ward with three EchoWear units can have
 * three countdowns running at once, and cancelling one must not silence the
 * others - so the state here is a list keyed by device, not a single slot.
 */
class AlertManager(
    private val context: Context,
    @Suppress("unused") private val settings: SettingsStore,
) {
    companion object {
        private const val TAG = "AlertManager"
        const val COUNTDOWN_MS = 30_000L
        private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
    }

    private val _pending = MutableStateFlow<List<PendingAlert>>(emptyList())
    val pending = _pending.asStateFlow()

    private val _lastSmsResult = MutableStateFlow<String?>(null)
    val lastSmsResult = _lastSmsResult.asStateFlow()

    /** The alert for one wearable, if it is counting down. */
    fun pendingFor(deviceId: String?): PendingAlert? =
        deviceId?.let { id -> _pending.value.firstOrNull { it.deviceId == id } }

    /** The most severe alert anywhere, which is what a banner should show. */
    fun mostSevere(): PendingAlert? =
        _pending.value.maxByOrNull { it.level.code.toInt() }

    /**
     * @return true if this alert became (or replaced) the pending alert for
     *         its device, false if an equal-or-worse one was already running.
     */
    @Synchronized
    fun raise(alert: PendingAlert): Boolean {
        val current = _pending.value
        val existing = current.firstOrNull { it.deviceId == alert.deviceId }
        // A higher-severity alert may take over; a lower one must not reset
        // the countdown that is already running for the same patient.
        if (existing != null && alert.level.code <= existing.level.code) return false
        _pending.value = current.filterNot { it.deviceId == alert.deviceId } + alert
        vibrate(alert.level)
        return true
    }

    /** Remove and return the alert for one wearable. */
    @Synchronized
    fun cancel(deviceId: String): PendingAlert? {
        val current = _pending.value
        val hit = current.firstOrNull { it.deviceId == deviceId } ?: return null
        _pending.value = current.filterNot { it.deviceId == deviceId }
        return hit
    }

    @Synchronized
    fun cancelAll(): List<PendingAlert> {
        val all = _pending.value
        _pending.value = emptyList()
        return all
    }

    fun isExpired(deviceId: String, now: Long = System.currentTimeMillis()): Boolean =
        pendingFor(deviceId)?.remainingMs(now)?.let { it <= 0 } ?: false

    @SuppressLint("MissingPermission")
    suspend fun dispatch(alert: PendingAlert, wearer: String, caregiver: String): Boolean {
        val number = alert.caregiverPhone.ifBlank { caregiver }
        if (number.isBlank()) {
            _lastSmsResult.value = "No caregiver number configured"
            Log.w(TAG, "no caregiver number; SMS not sent")
            return false
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _lastSmsResult.value = "SMS permission not granted"
            return false
        }

        val body = buildMessage(alert, wearer)
        return try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            // A message with a map link is well over 160 characters, so it has
            // to go out as a multipart message or the tail is silently lost.
            val parts = manager.divideMessage(body)
            manager.sendMultipartTextMessage(number, null, parts, null, null)
            _lastSmsResult.value = "Sent ${parts.size} part(s) to $number"
            Log.i(TAG, "caregiver SMS sent (${parts.size} parts)")
            true
        } catch (e: Exception) {
            _lastSmsResult.value = "Send failed: ${e.message}"
            Log.e(TAG, "SMS send failed", e)
            false
        }
    }

    /**
     * The message mirrors what the wearable's own GSM modem sends, so a
     * caregiver who receives both does not have to reconcile two formats.
     * The wearer line comes first because that is the only part that matters
     * in the two seconds before someone decides whether to run.
     */
    fun buildMessage(alert: PendingAlert, wearer: String): String {
        val who = alert.patientName.ifBlank { wearer.ifBlank { "The HeatGuard wearer" } }
        val what = when (alert.level) {
            AlertLevel.MANUAL_SOS -> "pressed the SOS button"
            AlertLevel.CARDIAC_FALL -> "may have had a fall with a cardiac response"
            AlertLevel.FALL_CONFIRMED -> "has had a confirmed fall"
            AlertLevel.FALL_DETECTED -> "may have fallen"
            else -> "triggered a HeatGuard alert"
        }
        return buildString {
            append("ECHOWEAR ALERT\n")
            append("$who $what.\n")
            if (alert.patientRef.isNotBlank()) append("Patient ref: ${alert.patientRef}\n")
            if (alert.deviceSerial.isNotBlank()) append("Device: ${alert.deviceSerial}\n")
            append("Time: ${TIMESTAMP.format(Date(alert.raisedAt))}\n")
            append("Source: ${alert.source}\n")
            if (alert.hrBpm > 0) append("Heart rate: ${alert.hrBpm.toInt()} bpm\n")
            if (alert.lat != 0.0 || alert.lon != 0.0) {
                append(
                    "Location: https://maps.google.com/?q=%.6f,%.6f\n"
                        .format(alert.lat, alert.lon)
                )
            } else {
                append("Location: not available\n")
            }
            append(
                "They did not cancel within ${alert.countdownMs / 1000}s. " +
                    "Please check on them."
            )
        }
    }

    fun placeCall(caregiver: String) {
        if (caregiver.isBlank()) return
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$caregiver"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "could not open dialler", it) }
    }

    private fun vibrate(level: AlertLevel) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
                )?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        val pattern = if (level == AlertLevel.FALL_PREDICTED) {
            longArrayOf(0, 120, 80, 120)
        } else {
            longArrayOf(0, 400, 150, 400, 150, 700)
        }
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
}
