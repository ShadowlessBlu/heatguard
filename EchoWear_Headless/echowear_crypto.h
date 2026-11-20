#ifndef ECHOWEAR_CRYPTO_H_
#define ECHOWEAR_CRYPTO_H_

#include <Arduino.h>
#include <string.h>
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#include <mbedtls/sha256.h>
#include <esp_system.h>
#include <esp_random.h>

// esp_read_mac() / ESP_MAC_WIFI_STA moved out of esp_system.h when the Arduino
// core rebased on ESP-IDF v5. Include the new header when it is there, so this
// builds on both core 2.x and 3.x.
#if __has_include(<esp_mac.h>)
#include <esp_mac.h>
#endif

// ============================================================================
//  EchoWear Layer 1 + Layer 2 security
// ----------------------------------------------------------------------------
//  Layer 2 - session key: ML-KEM-512 (FIPS 203) via the PQCMicro library.
//            A fresh keypair is generated per BLE session, so a session key
//            compromise never reaches past that session.
//  Layer 1 - record sealing: AES-256-GCM using the ESP32-S3 hardware AES
//            engine through mbedTLS. Every notification carries its own
//            counter-derived nonce and a 16-byte authentication tag.
//
//  Set ECHOWEAR_PQC_ENABLED to 0 to build without the PQCMicro dependency.
//  The AES layer still runs; the session key then comes from a device-unique
//  eFuse-derived key instead of a negotiated one, and the firmware reports
//  PQC as unavailable rather than pretending otherwise.
// ============================================================================

#ifndef ECHOWEAR_PQC_ENABLED
#define ECHOWEAR_PQC_ENABLED 1
#endif

#if ECHOWEAR_PQC_ENABLED
#include <PQCMicro.h>
#endif

#define KEM_PUBLICKEY_BYTES   800     // ML-KEM-512 public key
#define KEM_CIPHERTEXT_BYTES  768     // ML-KEM-512 ciphertext
#define KEM_SHAREDSECRET_BYTES 32
#define AES_KEY_BYTES         32
#define AES_TAG_BYTES         16
#define AES_NONCE_BYTES       12
#define KEM_CHUNK_BYTES       160     // fits a 185-byte BLE ATT payload

enum class SessionState : uint8_t {
    IDLE = 0,           // nothing negotiated; payloads sent in the clear
    KEYS_READY = 1,     // our keypair exists, public key is being served
    ESTABLISHED = 2,    // shared secret derived; payloads are sealed
    FAILED = 3
};

class EchoWearSession {
public:
    SessionState state = SessionState::IDLE;
    uint32_t     seq   = 0;            // nonce counter, never reused
    uint32_t     last_keygen_ms = 0;
    uint32_t     keygen_ms = 0, decap_ms = 0;

    // ---------------------------------------------------------------- setup
    bool begin() {
        deriveDeviceKey();
        mbedtls_gcm_init(&gcm_);
        return true;
    }

    // Start a fresh post-quantum session. Returns false if PQC is unavailable
    // or key generation failed; the caller should then fall back to the
    // device key and tell the user the link is not post-quantum protected.
    bool startKeyExchange() {
#if ECHOWEAR_PQC_ENABLED
        uint32_t t0 = millis();
        if (!kyber_.generateKeys()) {
            state = SessionState::FAILED;
            return false;
        }
        keygen_ms = millis() - t0;
        last_keygen_ms = millis();
        memcpy(public_key_, kyber_.getPublicKey(), KEM_PUBLICKEY_BYTES);
        state = SessionState::KEYS_READY;
        return true;
#else
        state = SessionState::FAILED;
        return false;
#endif
    }

    // Serve the public key in BLE-sized chunks. Returns the number of bytes
    // written, or 0 when the offset is past the end.
    size_t publicKeyChunk(uint16_t offset, uint8_t *out, size_t max_len) const {
        if (state == SessionState::IDLE || offset >= KEM_PUBLICKEY_BYTES) return 0;
        size_t n = KEM_PUBLICKEY_BYTES - offset;
        if (n > max_len) n = max_len;
        memcpy(out, public_key_ + offset, n);
        return n;
    }

    // Accept the peer's ciphertext one chunk at a time. Once all
    // KEM_CIPHERTEXT_BYTES have arrived, decapsulate and derive the key.
    bool feedCiphertextChunk(uint16_t offset, const uint8_t *data, size_t len) {
        if (offset + len > KEM_CIPHERTEXT_BYTES) return false;
        memcpy(ciphertext_ + offset, data, len);
        ct_received_ += len;
        if (ct_received_ < KEM_CIPHERTEXT_BYTES) return true;
        return finishKeyExchange();
    }

    bool finishKeyExchange() {
#if ECHOWEAR_PQC_ENABLED
        uint32_t t0 = millis();
        if (!kyber_.decapsulate(ciphertext_)) {
            state = SessionState::FAILED;
            ct_received_ = 0;
            return false;
        }
        decap_ms = millis() - t0;
        // HKDF-SHA256(shared secret) binds the AES key to this session and to
        // a context string, so the raw KEM output is never used directly.
        // 40 bytes: 32 for the AES key, 8 for the nonce prefix. The prefix
        // MUST be derived rather than random, because the phone has to arrive
        // at the same one without it ever being transmitted.
        uint8_t okm[40];
        hkdf(kyber_.getSharedSecret(), KEM_SHAREDSECRET_BYTES,
             (const uint8_t *)"EchoWear-BLE-v1", 15, okm, sizeof(okm));
        memcpy(session_key_, okm, AES_KEY_BYTES);
        memcpy(session_nonce_prefix_, okm + AES_KEY_BYTES, 8);
        memset(okm, 0, sizeof(okm));
        ct_received_ = 0;
        seq = 0;
        state = SessionState::ESTABLISHED;
        return true;
#else
        state = SessionState::FAILED;
        return false;
#endif
    }

    void resetSession() {
        state = SessionState::IDLE;
        ct_received_ = 0;
        seq = 0;
        memset(session_key_, 0, sizeof(session_key_));
        memset(session_nonce_prefix_, 0, sizeof(session_nonce_prefix_));
    }

    // ------------------------------------------------------------- sealing
    // Layout: [4-byte big-endian counter][ciphertext][16-byte tag].
    // Returns the sealed length, or 0 on failure.
    size_t seal(const uint8_t *plain, size_t len, uint8_t *out, size_t out_cap) {
        if (out_cap < len + 4 + AES_TAG_BYTES) return 0;

        const bool sess = (state == SessionState::ESTABLISHED);
        const uint8_t *key = sess ? session_key_ : device_key_;
        const uint8_t *prefix = sess ? session_nonce_prefix_ : nonce_prefix_;
        uint32_t counter = ++seq;
        out[0] = (counter >> 24) & 0xFF;
        out[1] = (counter >> 16) & 0xFF;
        out[2] = (counter >> 8) & 0xFF;
        out[3] = counter & 0xFF;

        uint8_t nonce[AES_NONCE_BYTES];
        memcpy(nonce, prefix, 8);
        memcpy(nonce + 8, out, 4);              // counter completes the nonce

        if (mbedtls_gcm_setkey(&gcm_, MBEDTLS_CIPHER_ID_AES, key,
                               AES_KEY_BYTES * 8) != 0) {
            return 0;
        }
        int rc = mbedtls_gcm_crypt_and_tag(
            &gcm_, MBEDTLS_GCM_ENCRYPT, len,
            nonce, AES_NONCE_BYTES,
            out, 4,                              // counter is authenticated AAD
            plain, out + 4,
            AES_TAG_BYTES, out + 4 + len);
        if (rc != 0) return 0;
        return len + 4 + AES_TAG_BYTES;
    }

    bool open(const uint8_t *sealed, size_t len, uint8_t *out, size_t out_cap) {
        if (len < 4 + AES_TAG_BYTES) return false;
        size_t plain_len = len - 4 - AES_TAG_BYTES;
        if (plain_len > out_cap) return false;

        const bool sess = (state == SessionState::ESTABLISHED);
        const uint8_t *key = sess ? session_key_ : device_key_;
        const uint8_t *prefix = sess ? session_nonce_prefix_ : nonce_prefix_;
        uint8_t nonce[AES_NONCE_BYTES];
        memcpy(nonce, prefix, 8);
        memcpy(nonce + 8, sealed, 4);

        if (mbedtls_gcm_setkey(&gcm_, MBEDTLS_CIPHER_ID_AES, key,
                               AES_KEY_BYTES * 8) != 0) {
            return false;
        }
        return mbedtls_gcm_auth_decrypt(
                   &gcm_, plain_len, nonce, AES_NONCE_BYTES,
                   sealed, 4, sealed + 4 + plain_len, AES_TAG_BYTES,
                   sealed + 4, out) == 0;
    }

    const char *stateName() const {
        switch (state) {
            case SessionState::IDLE:        return "IDLE";
            case SessionState::KEYS_READY:  return "KEY-EXCHANGE";
            case SessionState::ESTABLISHED: return "PQC-SECURED";
            default:                        return "FAILED";
        }
    }

    bool isPqcBuilt() const { return ECHOWEAR_PQC_ENABLED; }

private:
#if ECHOWEAR_PQC_ENABLED
    PQCKyber kyber_;
#endif
    mbedtls_gcm_context gcm_;
    uint8_t public_key_[KEM_PUBLICKEY_BYTES]  = {0};
    uint8_t ciphertext_[KEM_CIPHERTEXT_BYTES] = {0};
    uint8_t session_key_[AES_KEY_BYTES]       = {0};
    uint8_t session_nonce_prefix_[8]          = {0};   // HKDF-derived
    uint8_t device_key_[AES_KEY_BYTES]        = {0};
    uint8_t nonce_prefix_[8]                  = {0};   // random, at-rest only
    size_t  ct_received_ = 0;

    // Layer 1 at-rest key: HKDF over the factory eFuse MAC, so it is unique
    // per device and never transmitted.
    void deriveDeviceKey() {
        uint8_t mac[6] = {0};
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        hkdf(mac, sizeof(mac), (const uint8_t *)"EchoWear-at-rest-v1", 19,
             device_key_, AES_KEY_BYTES);
        // Random per-boot nonce prefix: even if the counter restarts after a
        // reset, the full nonce does not repeat across boots.
        esp_fill_random(nonce_prefix_, sizeof(nonce_prefix_));
    }

    static void hkdf(const uint8_t *ikm, size_t ikm_len,
                     const uint8_t *info, size_t info_len,
                     uint8_t *out, size_t out_len) {
        const mbedtls_md_info_t *md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
        uint8_t prk[32];
        const uint8_t salt[16] = {'E','c','h','o','W','e','a','r',
                                  '-','s','a','l','t','-','0','1'};
        mbedtls_md_hmac(md, salt, sizeof(salt), ikm, ikm_len, prk);

        // RFC 5869 Expand, multi-block: T(i) = HMAC(PRK, T(i-1) | info | i).
        mbedtls_md_context_t ctx;
        mbedtls_md_init(&ctx);
        mbedtls_md_setup(&ctx, md, 1);

        uint8_t t[32];
        size_t t_len = 0, done = 0;
        for (uint8_t counter = 1; done < out_len; counter++) {
            mbedtls_md_hmac_starts(&ctx, prk, sizeof(prk));
            if (t_len) mbedtls_md_hmac_update(&ctx, t, t_len);
            mbedtls_md_hmac_update(&ctx, info, info_len);
            mbedtls_md_hmac_update(&ctx, &counter, 1);
            mbedtls_md_hmac_finish(&ctx, t);
            t_len = 32;

            size_t n = out_len - done;
            if (n > 32) n = 32;
            memcpy(out + done, t, n);
            done += n;
        }
        mbedtls_md_free(&ctx);
        memset(t, 0, sizeof(t));
        memset(prk, 0, sizeof(prk));
    }
};

#endif  // ECHOWEAR_CRYPTO_H_
