#ifndef ECHOWEAR_IDENTITY_H_
#define ECHOWEAR_IDENTITY_H_

// Bumped whenever this header gains something a sketch depends on.
// Both sketch folders keep their own copy of these headers (Arduino
// wants them beside the .ino), so a change made to one folder and not
// the other is easy to miss - and surfaces as a baffling "not declared
// in this scope" rather than as a stale file. The sketches check this.
#define ECHOWEAR_IDENTITY_HEADER_REV 3

#include <Arduino.h>
#include <Preferences.h>
#include <mbedtls/md.h>
#include <esp_system.h>
#include <esp_random.h>
#if __has_include(<esp_mac.h>)
#include <esp_mac.h>
#endif

// ============================================================================
//  EchoWear device identity and pairing
// ----------------------------------------------------------------------------
//  Why this exists
//  ---------------
//  One phone now manages several wearables worn by different patients, so two
//  things have to be true that were not true of the old single-device build:
//
//   1. Every frame must say which device it came from, or a ward with three
//      EchoWears produces one undifferentiated stream of vitals.
//   2. A wearable must belong to somebody. Without that, any phone in
//      Bluetooth range can subscribe to any patient's heart rate, which is
//      not a defensible position for a health device.
//
//  The model
//  ---------
//  DEVICE ID   8 bytes derived from the factory eFuse MAC, rendered as a
//              human-readable serial (EW-XXXX-XXXX). Stable for the life of
//              the device, never changes, printed on the enclosure.
//
//  CLAIM       A device starts life unclaimed. A phone claims it by proving
//              physical possession (see below) and storing a 32-byte owner
//              token in the device's NVS. A claimed device refuses to stream
//              to anyone who cannot prove knowledge of that token.
//
//  PATIENT REF A short opaque reference (e.g. "P-0042") the app assigns at
//              claim time. The device deliberately does NOT store the
//              patient's name - the phone holds that, encrypted. A wearable
//              that falls out of a pocket should not identify its wearer.
//
//  AUTH        After ML-KEM establishes the session key, the phone proves the
//              claim with HMAC-SHA256(owner_token, challenge). Notifications
//              stay silent until that succeeds, so the proof of ownership and
//              the post-quantum session are bound together rather than being
//              two independent checks.
//
//  Proof of possession differs by hardware, and the two are NOT equally strong
//  ----------------------------------------------------------------------------
//  TOUCH BUILD     A random 6-digit code is generated per pairing window and
//                  shown on the screen. It never leaves the device except
//                  through the wearer's eyes. This is the strong variant.
//
//  HEADLESS BUILD  No screen, so the code is derived from the device secret
//                  and is therefore STATIC - it goes on the enclosure label at
//                  assembly. Anyone who has read that label can claim the
//                  device while it is in pairing mode. That is a real
//                  limitation, mitigated only by the pairing window being
//                  time-boxed and physically triggered. Do not treat the two
//                  as equivalent when writing up the threat model.
//
//  In both builds the pairing window is closed by default: it opens for
//  PAIRING_WINDOW_MS at boot while the device is unclaimed, and afterwards
//  only on a deliberate physical gesture (three sharp taps, detected on the
//  IMU) or a UI action. A claimed device will not enter pairing mode at all
//  without a factory reset.
// ============================================================================

#define ECHOWEAR_PROTOCOL_VERSION  2

#define DEVICE_ID_BYTES        8
#define OWNER_TOKEN_BYTES      32
#define PAIR_CHALLENGE_BYTES   16
#define PATIENT_REF_LEN        16
#define DEVICE_LABEL_LEN       24
#define PAIRING_WINDOW_MS      (120UL * 1000UL)

// ---------------------------------------------------------------------------
// How long an UNCLAIMED device stays claimable.
//
// 1 (default): indefinitely, until somebody claims it.
// 0          : PAIRING_WINDOW_MS after power-on or a deliberate reopen.
//
// The timed window was the original design and it is the wrong trade here. It
// only ever applies to an unclaimed device - a claimed one never reopens by
// itself - and an unclaimed device is, by definition, holding nobody's data.
// All the timeout achieves is that a person fumbling with a six-digit code
// runs out of time and has to power-cycle the wearable to try again.
//
// What actually protects the claim is unchanged: six digits, a constant-time
// compare, and a lockout after five wrong ones that needs a deliberate reopen
// on the device itself. Anyone close enough to brute-force it is close enough
// to read the code off the screen.
// ---------------------------------------------------------------------------
#ifndef ECHOWEAR_PAIRING_ALWAYS_OPEN
#define ECHOWEAR_PAIRING_ALWAYS_OPEN 1
#endif

// Reported in pairing_remaining_s when the window has no deadline.
#define PAIR_NO_TIMEOUT        0xFFFF

// These are MACROS, not enum values, and that matters. The pairing code path
// is selected with #if, and the preprocessor cannot see an enum: every
// unknown identifier evaluates to 0, so `#if ECHOWEAR_DEVICE_TYPE ==
// DEVICE_TYPE_TOUCH_LCD` would be 0 == 0 and BOTH builds would silently take
// the touch branch - leaving the headless device generating a random pairing
// code it has no screen to display, which makes it unpairable.
#define ECHOWEAR_TYPE_UNKNOWN    0
#define ECHOWEAR_TYPE_TOUCH_LCD  1   // Waveshare ESP32-S3-Touch-LCD-1.47
#define ECHOWEAR_TYPE_HEADLESS   2   // ESP32-S3 SuperMini, no display

// Which build this is. The .ino defines it before including this header.
#ifndef ECHOWEAR_DEVICE_TYPE
#define ECHOWEAR_DEVICE_TYPE ECHOWEAR_TYPE_UNKNOWN
#endif

// The runtime enum mirrors the macros, for anything that needs a typed value.
enum DeviceType : uint8_t {
    DEVICE_TYPE_UNKNOWN   = ECHOWEAR_TYPE_UNKNOWN,
    DEVICE_TYPE_TOUCH_LCD = ECHOWEAR_TYPE_TOUCH_LCD,
    DEVICE_TYPE_HEADLESS  = ECHOWEAR_TYPE_HEADLESS,
};

enum PairOp : uint8_t {
    PAIR_OP_BEGIN     = 0x01,   // app asks for a fresh challenge
    PAIR_OP_CLAIM     = 0x02,   // [3-byte code][32-byte owner token]
    PAIR_OP_AUTH      = 0x03,   // [32-byte HMAC over the challenge]
    PAIR_OP_SET_META  = 0x04,   // [patient_ref][0x00][device_label]
    PAIR_OP_UNCLAIM   = 0x05,   // [32-byte HMAC over the challenge]
    PAIR_OP_OPEN      = 0x06,   // reopen the pairing window (unclaimed only)
};

enum PairResult : uint8_t {
    PAIR_OK            = 0,
    PAIR_ERR_STATE     = 1,   // wrong state for this operation
    PAIR_ERR_CODE      = 2,   // wrong pairing code
    PAIR_ERR_CLOSED    = 3,   // pairing window is not open
    PAIR_ERR_CLAIMED   = 4,   // already claimed by somebody else
    PAIR_ERR_AUTH      = 5,   // HMAC did not verify
    PAIR_ERR_LENGTH    = 6,   // malformed frame
};

// Read back from the PAIRING characteristic. Plaintext by design: a phone has
// to be able to see whether a device is claimable before it has any key.
typedef struct __attribute__((packed)) {
    uint8_t  protocol_version;
    uint8_t  device_type;
    uint8_t  claimed;
    uint8_t  authenticated;
    uint8_t  pairing_open;
    uint8_t  last_result;              // PairResult of the previous write
    uint16_t pairing_remaining_s;
    uint8_t  device_id[DEVICE_ID_BYTES];
    uint8_t  challenge[PAIR_CHALLENGE_BYTES];
} EchoWearPairStatus;

class EchoWearIdentity {
public:
    uint8_t  device_id[DEVICE_ID_BYTES] = {0};
    char     serial[16]  = {0};                  // "EW-XXXX-XXXX"
    char     patient_ref[PATIENT_REF_LEN + 1] = {0};
    char     label[DEVICE_LABEL_LEN + 1] = {0};
    bool     claimed = false;
    bool     authenticated = false;
    uint8_t  last_result = PAIR_OK;

    // ------------------------------------------------------------ lifecycle
    void begin() {
        deriveDeviceId();
        prefs_.begin("echowear", false);
        claimed = prefs_.getBool("claimed", false);
        if (claimed) {
            prefs_.getBytes("owner", owner_token_, OWNER_TOKEN_BYTES);
            prefs_.getString("patient", patient_ref, sizeof(patient_ref));
            prefs_.getString("label", label, sizeof(label));
        }
        if (label[0] == '\0') {
            snprintf(label, sizeof(label), "EchoWear %s", serial + 3);
        }
        newChallenge();

        // An unclaimed device is claimable for a window after power-on: that
        // is the normal out-of-box path. A claimed one never auto-opens.
        if (!claimed) openPairing();

        Serial.printf("ID   | serial=%s type=%u claimed=%s\n",
                      serial, (unsigned)ECHOWEAR_DEVICE_TYPE,
                      claimed ? "yes" : "no");
        if (!claimed) {
            Serial.printf("ID   | pairing code for this window: %06lu\n",
                          (unsigned long)pairingCode());
        }
    }

    // A connection is a fresh one: drop any authentication from the last peer.
    void onDisconnect() {
        authenticated = false;
        newChallenge();
    }

    // ------------------------------------------------------- pairing window
    void openPairing() {
        if (claimed) return;               // a claimed device is not claimable
        pairing_opened_ms_ = millis();
        pairing_open_ = true;
        failed_attempts_ = 0;              // a deliberate reopen clears the lockout
        locked_out_ = false;
#if ECHOWEAR_DEVICE_TYPE == ECHOWEAR_TYPE_TOUCH_LCD
        // Fresh random code per window - it is shown on the screen and never
        // transmitted, so it cannot be harvested from the air or the label.
        session_code_ = (uint32_t)(esp_random() % 1000000UL);
#else
        // No screen: the code has to be stable so it can be printed on the
        // enclosure. Weaker, and documented as such.
        session_code_ = labelCode();
#endif
    }

    void closePairing() { pairing_open_ = false; }

    bool pairingOpen() {
        if (claimed) return false;
        // Five wrong codes shuts it until someone reopens it ON the device.
        // That is the brute-force control, and it survives the change below.
        if (locked_out_) return false;
#if ECHOWEAR_PAIRING_ALWAYS_OPEN
        if (!pairing_open_) {
            // An unclaimed device is always ready to be set up: if something
            // closed the window, reopen it rather than stranding the user.
            pairing_opened_ms_ = millis();
            pairing_open_ = true;
        }
        return true;
#else
        if (!pairing_open_) return false;
        if (millis() - pairing_opened_ms_ > PAIRING_WINDOW_MS) {
            pairing_open_ = false;
        }
        return pairing_open_;
#endif
    }

    bool lockedOut() const { return locked_out_; }

    uint16_t pairingRemainingSec() {
        if (!pairingOpen()) return 0;
#if ECHOWEAR_PAIRING_ALWAYS_OPEN
        return PAIR_NO_TIMEOUT;            // the app renders this as "no limit"
#else
        uint32_t elapsed = millis() - pairing_opened_ms_;
        return (uint16_t)((PAIRING_WINDOW_MS - elapsed) / 1000UL);
#endif
    }

    uint32_t pairingCode() const { return session_code_; }

    // The label code must be reproducible at manufacture without storing
    // anything extra, yet must NOT be computable by a stranger in range.
    //
    // It is therefore keyed on label_secret_, which is the tail of the same
    // HMAC that produced device_id and is never transmitted. Deriving it from
    // device_id instead would be a hole rather than a weakness: device_id is
    // published in the plaintext pairing status, so any phone that can see
    // the device could compute the code and skip the factor entirely.
    uint32_t labelCode() const {
        uint8_t out[32];
        hmac(label_secret_, sizeof(label_secret_),
             (const uint8_t *)"EchoWear-label-code", 19, out);
        uint32_t v = ((uint32_t)out[0] << 16) | ((uint32_t)out[1] << 8) | out[2];
        return v % 1000000UL;
    }

    // ------------------------------------------------------------ handlers
    // Returns a PairResult. `out`/`out_len` receive an optional reply body.
    uint8_t handleWrite(const uint8_t *data, size_t len) {
        if (len < 1) return finish(PAIR_ERR_LENGTH);
        uint8_t op = data[0];
        const uint8_t *body = data + 1;
        size_t body_len = len - 1;

        switch (op) {
            case PAIR_OP_BEGIN:
                newChallenge();
                return finish(PAIR_OK);

            case PAIR_OP_OPEN:
                if (claimed) return finish(PAIR_ERR_CLAIMED);
                openPairing();
                return finish(PAIR_OK);

            case PAIR_OP_CLAIM: {
                if (body_len != 3 + OWNER_TOKEN_BYTES) return finish(PAIR_ERR_LENGTH);
                if (claimed) return finish(PAIR_ERR_CLAIMED);
                if (!pairingOpen()) return finish(PAIR_ERR_CLOSED);

                uint32_t code = ((uint32_t)body[0] << 16) |
                                ((uint32_t)body[1] << 8) | body[2];
                // Constant-time compare: a timing oracle on a 6-digit code is
                // a real shortcut for an attacker who can retry.
                if (!constEqual((const uint8_t *)&code, (const uint8_t *)&session_code_, 4)) {
                    if (++failed_attempts_ >= 5) {
                        locked_out_ = true;
                        closePairing();     // rate limit: close on 5 bad codes
                        Serial.println(
                            "PAIR | too many bad codes - locked out. Reopen on "
                            "the device (tap it three times, or press its "
                            "button) to try again.");
                    }
                    return finish(PAIR_ERR_CODE);
                }

                memcpy(owner_token_, body + 3, OWNER_TOKEN_BYTES);
                claimed = true;
                authenticated = true;       // the claimant is authenticated
                failed_attempts_ = 0;
                closePairing();
                persist();
                Serial.printf("PAIR | claimed, serial=%s\n", serial);
                return finish(PAIR_OK);
            }

            case PAIR_OP_AUTH: {
                if (body_len != 32) return finish(PAIR_ERR_LENGTH);
                if (!claimed) return finish(PAIR_ERR_STATE);
                uint8_t expect[32];
                hmac(owner_token_, OWNER_TOKEN_BYTES,
                     challenge_, PAIR_CHALLENGE_BYTES, expect);
                if (!constEqual(expect, body, 32)) {
                    Serial.println("PAIR | auth failed");
                    return finish(PAIR_ERR_AUTH);
                }
                authenticated = true;
                Serial.println("PAIR | peer authenticated");
                return finish(PAIR_OK);
            }

            case PAIR_OP_SET_META: {
                if (!authenticated) return finish(PAIR_ERR_AUTH);
                // "<patient_ref>\0<label>"
                size_t i = 0;
                while (i < body_len && body[i] != 0) i++;
                size_t ref_len = i < PATIENT_REF_LEN ? i : PATIENT_REF_LEN;
                memset(patient_ref, 0, sizeof(patient_ref));
                memcpy(patient_ref, body, ref_len);

                if (i + 1 < body_len) {
                    size_t lab_len = body_len - i - 1;
                    if (lab_len > DEVICE_LABEL_LEN) lab_len = DEVICE_LABEL_LEN;
                    memset(label, 0, sizeof(label));
                    memcpy(label, body + i + 1, lab_len);
                }
                persist();
                Serial.printf("PAIR | meta set: patient=%s label=%s\n",
                              patient_ref, label);
                return finish(PAIR_OK);
            }

            case PAIR_OP_UNCLAIM: {
                if (body_len != 32) return finish(PAIR_ERR_LENGTH);
                if (!claimed) return finish(PAIR_ERR_STATE);
                uint8_t expect[32];
                hmac(owner_token_, OWNER_TOKEN_BYTES,
                     challenge_, PAIR_CHALLENGE_BYTES, expect);
                if (!constEqual(expect, body, 32)) return finish(PAIR_ERR_AUTH);
                factoryReset();
                Serial.println("PAIR | unclaimed by owner");
                return finish(PAIR_OK);
            }

            default:
                return finish(PAIR_ERR_STATE);
        }
    }

    void fillStatus(EchoWearPairStatus &s) {
        memset(&s, 0, sizeof(s));
        s.protocol_version = ECHOWEAR_PROTOCOL_VERSION;
        s.device_type = ECHOWEAR_DEVICE_TYPE;
        s.claimed = claimed ? 1 : 0;
        s.authenticated = authenticated ? 1 : 0;
        s.pairing_open = pairingOpen() ? 1 : 0;
        s.last_result = last_result;
        s.pairing_remaining_s = pairingRemainingSec();
        memcpy(s.device_id, device_id, DEVICE_ID_BYTES);
        memcpy(s.challenge, challenge_, PAIR_CHALLENGE_BYTES);
    }

    // A wearer holding the button, or three deliberate taps, wipes the claim.
    // This is the only way back from a lost phone.
    void factoryReset() {
        claimed = false;
        authenticated = false;
        memset(owner_token_, 0, sizeof(owner_token_));
        memset(patient_ref, 0, sizeof(patient_ref));
        prefs_.clear();
        newChallenge();
        failed_attempts_ = 0;
        locked_out_ = false;
        openPairing();
    }

    // May this peer receive patient data?
    bool mayStream() {
        // Unclaimed and mid-pairing: allow, so the installer can see live
        // values while setting the device up. Claimed: proof required.
        if (!claimed) return pairingOpen();
        return authenticated;
    }

private:
    Preferences prefs_;
    uint8_t  owner_token_[OWNER_TOKEN_BYTES] = {0};
    uint8_t  challenge_[PAIR_CHALLENGE_BYTES] = {0};
    // Bytes 8..31 of the device-id HMAC. device_id is bytes 0..7 and is
    // public; this tail never leaves the chip and keys the label code.
    uint8_t  label_secret_[24] = {0};
    uint32_t session_code_ = 0;
    uint32_t pairing_opened_ms_ = 0;
    bool     pairing_open_ = false;
    uint8_t  failed_attempts_ = 0;
    bool     locked_out_ = false;

    uint8_t finish(uint8_t r) { last_result = r; return r; }

    void persist() {
        prefs_.putBool("claimed", claimed);
        prefs_.putBytes("owner", owner_token_, OWNER_TOKEN_BYTES);
        prefs_.putString("patient", patient_ref);
        prefs_.putString("label", label);
    }

    void newChallenge() { esp_fill_random(challenge_, sizeof(challenge_)); }

    void deriveDeviceId() {
        uint8_t mac[6] = {0};
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        uint8_t out[32];
        hmac((const uint8_t *)"EchoWear-device-id", 18, mac, sizeof(mac), out);
        memcpy(device_id, out, DEVICE_ID_BYTES);
        memcpy(label_secret_, out + DEVICE_ID_BYTES, sizeof(label_secret_));

        // Crockford-style base32 without the letters that look like digits, so
        // a serial read off a label is not ambiguous.
        static const char A[] = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        char body[9];
        for (int i = 0; i < 8; i++) body[i] = A[device_id[i] & 0x1F];
        body[8] = '\0';
        snprintf(serial, sizeof(serial), "EW-%.4s-%.4s", body, body + 4);
    }

    static bool constEqual(const uint8_t *a, const uint8_t *b, size_t n) {
        uint8_t diff = 0;
        for (size_t i = 0; i < n; i++) diff |= (uint8_t)(a[i] ^ b[i]);
        return diff == 0;
    }

    static void hmac(const uint8_t *key, size_t key_len,
                     const uint8_t *msg, size_t msg_len, uint8_t *out32) {
        const mbedtls_md_info_t *md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
        mbedtls_md_hmac(md, key, key_len, msg, msg_len, out32);
    }
};

#endif  // ECHOWEAR_IDENTITY_H_
