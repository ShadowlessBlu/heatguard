package gh.knust.echowear.crypto

import android.util.Log
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Layer 2 of the EchoWear security architecture, phone side.
 *
 * The wearable generates a fresh ML-KEM-512 (FIPS 203) keypair per BLE
 * session and serves its 800-byte public key over the KEM characteristic.
 * This class encapsulates a shared secret to that key, returns the 768-byte
 * ciphertext for the phone to write back, and derives the AES-256-GCM
 * session key with the same HKDF parameters the firmware uses
 * (echowear_crypto.h) so both sides land on identical key material without
 * ever transmitting it.
 *
 * The 12-byte GCM nonce is [8-byte HKDF-derived prefix][4-byte counter].
 * The prefix is derived rather than random precisely so that it does not have
 * to be sent.
 */
class PqcSession {

    companion object {
        private const val TAG = "PqcSession"
        private const val KEY_BYTES = 32
        private const val PREFIX_BYTES = 8
        private const val TAG_BITS = 128
        const val TAG_BYTES = 16
        const val COUNTER_BYTES = 4

        /** Must match the firmware byte for byte. */
        private val HKDF_SALT = "EchoWear-salt-01".toByteArray(Charsets.US_ASCII)
        private val HKDF_INFO = "EchoWear-BLE-v1".toByteArray(Charsets.US_ASCII)
    }

    /**
     * Whether a session key is in place.
     *
     * A StateFlow rather than a plain flag because the security screen has to
     * repaint the moment the handshake completes; a @Volatile Boolean is
     * correct for the crypto path but invisible to Compose, so the padlock
     * would stay grey until something unrelated caused a recomposition.
     */
    private val _established = MutableStateFlow(false)
    val established = _established.asStateFlow()

    val isEstablished: Boolean get() = _established.value

    /** Time the encapsulation took, for the security screen. */
    @Volatile var encapsulateMs: Long = 0L
        private set

    private var sessionKey: ByteArray? = null
    private var noncePrefix: ByteArray? = null
    private val random = SecureRandom()

    /** Sequence numbers already seen, so a replayed notification is rejected. */
    private var highestCounterSeen: Long = -1L

    /**
     * Encapsulate to the wearable's public key.
     * @return the 768-byte ciphertext to write back to the KEM characteristic.
     */
    fun encapsulate(devicePublicKey: ByteArray): ByteArray {
        require(devicePublicKey.size == 800) {
            "ML-KEM-512 public key must be 800 bytes, got ${devicePublicKey.size}"
        }
        val started = System.nanoTime()

        val pub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_512, devicePublicKey)
        val generator = MLKEMGenerator(random)
        val encapsulated = generator.generateEncapsulated(pub)

        val sharedSecret = encapsulated.secret          // 32 bytes
        val ciphertext = encapsulated.encapsulation     // 768 bytes

        val okm = hkdf(sharedSecret, HKDF_SALT, HKDF_INFO, KEY_BYTES + PREFIX_BYTES)
        sessionKey = okm.copyOfRange(0, KEY_BYTES)
        noncePrefix = okm.copyOfRange(KEY_BYTES, KEY_BYTES + PREFIX_BYTES)
        okm.fill(0)
        sharedSecret.fill(0)

        highestCounterSeen = -1L
        _established.value = true
        encapsulateMs = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "ML-KEM-512 encapsulation complete in ${encapsulateMs} ms")
        return ciphertext
    }

    fun reset() {
        _established.value = false
        sessionKey?.fill(0)
        noncePrefix?.fill(0)
        sessionKey = null
        noncePrefix = null
        highestCounterSeen = -1L
    }

    /**
     * Open a sealed notification: [4-byte BE counter][ciphertext][16-byte tag].
     * Returns null when the session is not established, the tag fails, or the
     * counter has already been used (replay).
     */
    fun open(sealed: ByteArray): ByteArray? {
        val key = sessionKey ?: return null
        val prefix = noncePrefix ?: return null
        if (sealed.size < COUNTER_BYTES + TAG_BYTES) return null

        val counter = ByteBuffer.wrap(sealed, 0, COUNTER_BYTES)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

        // The firmware's counter is strictly increasing within a session, so
        // anything we have already seen is a replay, not a reorder.
        if (counter <= highestCounterSeen) {
            Log.w(TAG, "dropping replayed frame, counter=$counter")
            return null
        }

        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, PREFIX_BYTES)
        System.arraycopy(sealed, 0, nonce, PREFIX_BYTES, COUNTER_BYTES)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            // The counter is authenticated as additional data, exactly as the
            // firmware passes it to mbedtls_gcm_crypt_and_tag.
            cipher.updateAAD(sealed, 0, COUNTER_BYTES)
            val plain = cipher.doFinal(sealed, COUNTER_BYTES, sealed.size - COUNTER_BYTES)
            highestCounterSeen = counter
            plain
        } catch (e: Exception) {
            Log.w(TAG, "GCM open failed: ${e.message}")
            null
        }
    }

    /** Seal a payload for the wearable (used for control writes when sealed). */
    fun seal(plain: ByteArray, counter: Long): ByteArray? {
        val key = sessionKey ?: return null
        val prefix = noncePrefix ?: return null

        val header = ByteBuffer.allocate(COUNTER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(counter.toInt()).array()
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, PREFIX_BYTES)
        System.arraycopy(header, 0, nonce, PREFIX_BYTES, COUNTER_BYTES)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            header + cipher.doFinal(plain)
        } catch (e: Exception) {
            Log.w(TAG, "GCM seal failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ HKDF
    /** RFC 5869 HKDF-SHA256, matching the firmware's implementation. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = HMac(SHA256Digest())

        // Extract
        hmac.init(KeyParameter(salt))
        hmac.update(ikm, 0, ikm.size)
        val prk = ByteArray(hmac.macSize)
        hmac.doFinal(prk, 0)

        // Expand
        val out = ByteArray(length)
        var t = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            hmac.init(KeyParameter(prk))
            if (t.isNotEmpty()) hmac.update(t, 0, t.size)
            hmac.update(info, 0, info.size)
            hmac.update(counter.toByte())
            t = ByteArray(hmac.macSize)
            hmac.doFinal(t, 0)

            val n = minOf(length - done, t.size)
            System.arraycopy(t, 0, out, done, n)
            done += n
            counter++
        }
        prk.fill(0)
        t.fill(0)
        return out
    }
}
