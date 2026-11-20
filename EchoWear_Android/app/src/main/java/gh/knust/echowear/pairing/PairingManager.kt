package gh.knust.echowear.pairing

import android.content.Context
import android.util.Base64
import android.util.Log
import gh.knust.echowear.ble.DeviceIdentity
import gh.knust.echowear.ble.OWNER_TOKEN_BYTES
import gh.knust.echowear.ble.PairResult
import gh.knust.echowear.ble.PairStatus
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The phone half of the wearable claim protocol (echowear_identity.h).
 *
 * A wearable starts unclaimed. Claiming it stores a 32-byte owner token on
 * the device and keeps a copy here; from then on the device streams nothing
 * to a phone that cannot prove it holds that token. That is what stops the
 * next phone on the ward from subscribing to this patient's heart rate.
 *
 * The token is the only thing that matters, so it lives in the encrypted
 * store alongside the patient record and never leaves the device except as
 * an HMAC over a fresh challenge.
 */
class PairingManager(context: Context, private val store: DeviceStore) {

    companion object {
        private const val TAG = "PairingManager"
    }

    private val random = SecureRandom()

    /** Generate the token that will be written to a device we are claiming. */
    fun newOwnerToken(): ByteArray =
        ByteArray(OWNER_TOKEN_BYTES).also { random.nextBytes(it) }

    /**
     * Proof that we own this device: HMAC-SHA256(owner token, challenge).
     * The challenge is fresh per connection, so a captured proof is useless
     * on the next one.
     */
    fun authProof(ownerToken: ByteArray, challenge: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ownerToken, "HmacSHA256"))
        return mac.doFinal(challenge)
    }

    fun ownerTokenFor(deviceId: String): ByteArray? = store.ownerToken(deviceId)

    /** Record a successful claim so the device can be re-authenticated later. */
    fun rememberClaim(
        deviceId: String,
        identity: DeviceIdentity,
        ownerToken: ByteArray,
        patientName: String,
        patientRef: String,
    ) {
        val previous = store.get(deviceId)
        store.upsert(
            PairedDevice(
                deviceId = deviceId,
                serial = identity.serial,
                type = identity.type,
                firmware = identity.firmware,
                label = identity.label,
                patientName = patientName,
                patientRef = patientRef,
                claimedAt = System.currentTimeMillis(),
                // Re-pairing the same wearable to the same patient should not
                // silently drop the family's phone number.
                caregiverName = previous?.caregiverName.orEmpty(),
                caregiverPhone = previous?.caregiverPhone.orEmpty(),
                notes = previous?.notes.orEmpty(),
            ),
            ownerToken,
        )
        Log.i(TAG, "claimed ${identity.serial} for $patientRef")
    }

    fun forget(deviceId: String) = store.remove(deviceId)

    /**
     * Turn a pairing failure into something a carer can act on. The raw codes
     * are useless to whoever is standing in front of the patient.
     */
    fun explain(status: PairStatus): String = when {
        status.lastResult == PairResult.OK && status.claimed ->
            "Paired successfully."
        status.lastResult == PairResult.ERR_CODE ->
            "That code was not accepted. Check the code shown on the device " +
                "(or printed on its label) and try again."
        status.lastResult == PairResult.ERR_CLOSED ->
            "The pairing window has closed. Tap the wearable sharply three " +
                "times to reopen it, then try again."
        status.lastResult == PairResult.ERR_CLAIMED ->
            "This wearable already belongs to another phone. Factory-reset it " +
                "first: hold its button for five seconds, or use the app that " +
                "currently owns it."
        status.lastResult == PairResult.ERR_AUTH ->
            "This phone could not prove it owns the wearable. If you " +
                "reinstalled the app you will need to factory-reset and pair again."
        else -> "Pairing did not complete. Move closer to the wearable and retry."
    }

    /**
     * A patient reference the device can hold without identifying anyone.
     *
     * Counting the roster is not enough: remove P-0002 of three and the next
     * suggestion collides with P-0003. The highest number ever issued is what
     * has to advance, so the sequence is taken from the existing refs.
     */
    fun suggestPatientRef(): String {
        val used = store.all().mapNotNull { d ->
            Regex("^P-(\\d+)$").find(d.patientRef)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "P-%04d".format((used.maxOrNull() ?: 0) + 1)
    }

    /** Edit the patient details without touching the owner token. */
    fun updatePatient(
        deviceId: String,
        patientName: String? = null,
        patientRef: String? = null,
        label: String? = null,
        caregiverName: String? = null,
        caregiverPhone: String? = null,
        notes: String? = null,
    ): PairedDevice? {
        val existing = store.get(deviceId) ?: return null
        val updated = existing.copy(
            patientName = patientName ?: existing.patientName,
            patientRef = patientRef ?: existing.patientRef,
            label = label ?: existing.label,
            caregiverName = caregiverName ?: existing.caregiverName,
            caregiverPhone = caregiverPhone ?: existing.caregiverPhone,
            notes = notes ?: existing.notes,
        )
        store.upsert(updated)
        return updated
    }

    fun roster(): List<PairedDevice> = store.all()
}

/** One wearable this phone owns. */
data class PairedDevice(
    val deviceId: String,
    val serial: String,
    val type: gh.knust.echowear.ble.DeviceType,
    val firmware: String,
    val label: String,
    val patientName: String,
    val patientRef: String,
    val claimedAt: Long,
    val lastSeenAt: Long = 0L,
    /**
     * Per-patient escalation contact. One phone watching three patients
     * cannot send all three alerts to the same number: the whole reason a
     * family member carries that number is that it is about their relative.
     * Blank falls back to the app-wide caregiver in settings.
     */
    val caregiverName: String = "",
    val caregiverPhone: String = "",
    val notes: String = "",
) {
    /** What a carer should see in a list: the person, not the hardware. */
    val displayName: String get() = patientName.ifBlank { label.ifBlank { serial } }

    /** "EW-7K4M-2P9X" is unreadable in a list; the last group is enough. */
    val shortSerial: String get() = serial.substringAfterLast('-').ifBlank { serial }

    val isHeadless: Boolean
        get() = type == gh.knust.echowear.ble.DeviceType.HEADLESS
}

/**
 * Persistence for the device roster.
 *
 * Owner tokens are the sensitive part and are stored wrapped by the same
 * hardware-keystore key that protects the vitals database, not in plain
 * preferences.
 */
class DeviceStore(private val context: Context) {

    companion object {
        private const val PREFS = "echowear_devices"
        private const val KEY_LIST = "roster"
        private const val TOKEN_PREFIX = "tok_"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun all(): List<PairedDevice> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return raw.split('\u001E').mapNotNull { decode(it) }
    }

    fun get(deviceId: String): PairedDevice? = all().firstOrNull { it.deviceId == deviceId }

    fun upsert(device: PairedDevice, ownerToken: ByteArray? = null) {
        val list = all().filterNot { it.deviceId == device.deviceId } + device
        prefs.edit()
            .putString(KEY_LIST, list.joinToString("\u001E") { encode(it) })
            .apply()
        if (ownerToken != null) {
            prefs.edit()
                .putString(TOKEN_PREFIX + device.deviceId,
                           SecretBox.seal(context, ownerToken))
                .apply()
        }
    }

    fun touch(deviceId: String) {
        get(deviceId)?.let { upsert(it.copy(lastSeenAt = System.currentTimeMillis())) }
    }

    fun ownerToken(deviceId: String): ByteArray? {
        val blob = prefs.getString(TOKEN_PREFIX + deviceId, null) ?: return null
        return SecretBox.open(context, blob)
    }

    fun remove(deviceId: String) {
        val list = all().filterNot { it.deviceId == deviceId }
        prefs.edit()
            .putString(KEY_LIST, list.joinToString("\u001E") { encode(it) })
            .remove(TOKEN_PREFIX + deviceId)
            .apply()
    }

    // Unit separator between records, field separator inside - patient names
    // can contain commas and semicolons, so neither is safe as a delimiter.
    private fun encode(d: PairedDevice) = listOf(
        d.deviceId, d.serial, d.type.code.toString(), d.firmware,
        d.label, d.patientName, d.patientRef,
        d.claimedAt.toString(), d.lastSeenAt.toString(),
        d.caregiverName, d.caregiverPhone, d.notes,
    ).joinToString("\u001F")

    // Fields 9..11 were added after the first build, so a roster written by
    // an earlier install still decodes - it just has no per-patient contact.
    private fun decode(raw: String): PairedDevice? {
        val f = raw.split('\u001F')
        if (f.size < 9) return null
        fun at(i: Int) = f.getOrNull(i).orEmpty()
        return PairedDevice(
            deviceId = f[0], serial = f[1],
            type = gh.knust.echowear.ble.DeviceType.from(f[2].toIntOrNull() ?: 0),
            firmware = f[3], label = f[4], patientName = f[5], patientRef = f[6],
            claimedAt = f[7].toLongOrNull() ?: 0L,
            lastSeenAt = f[8].toLongOrNull() ?: 0L,
            caregiverName = at(9), caregiverPhone = at(10), notes = at(11),
        )
    }
}

/** Wraps a secret with an AES key held in the Android hardware keystore. */
private object SecretBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "echowear_owner_token_key"

    fun seal(context: Context, plain: ByteArray): String {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
               Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun open(context: Context, blob: String): ByteArray? = try {
        val (ivB64, ctB64) = blob.split(':', limit = 2)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE, key(),
            javax.crypto.spec.GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)),
        )
        cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
    } catch (e: Exception) {
        Log.w("SecretBox", "could not open owner token: ${e.message}")
        null
    }

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val gen = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                // The monitor service must re-authenticate to a wearable while
                // the phone is locked, so this key cannot require user auth.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }
}
