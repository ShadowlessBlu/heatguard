#ifndef ECHOWEAR_BLE_H_
#define ECHOWEAR_BLE_H_

// Bumped whenever this header gains something a sketch depends on.
// Both sketch folders keep their own copy of these headers (Arduino
// wants them beside the .ino), so a change made to one folder and not
// the other is easy to miss - and surfaces as a baffling "not declared
// in this scope" rather than as a stale file. The sketches check this.
#define ECHOWEAR_BLE_HEADER_REV 3

#include <Arduino.h>
#include <NimBLEDevice.h>
#include "echowear_crypto.h"
#include "echowear_identity.h"

// ============================================================================
//  EchoWear BLE GATT server  -  protocol v2
// ----------------------------------------------------------------------------
//  This replaces the ESP-NOW link to the Cardputer. The phone is now the
//  receiver AND the machine-learning host: the wearable streams raw IMU
//  windows and cardiac features, the phone runs the classifier.
//
//  Service  6e400000-b5a3-f393-e0a9-e50e24dcca9e
//    ..0002  TELEMETRY  notify   sealed EchoWearTelemetry, 2 Hz
//    ..0003  IMU        notify   sealed EchoWearImuBatch, 20 Hz
//    ..0004  ALERT      notify   sealed EchoWearAlert, event-driven
//    ..0005  CONTROL    write    EchoWearControl (plaintext, short)
//    ..0006  KEM        read/wr  ML-KEM-512 public key out, ciphertext in
//    ..0007  STATUS     read     plaintext device descriptor
//    ..0008  IDENTITY   read     plaintext serial / type / firmware / label
//    ..0009  PAIRING    read/wr  claim, authenticate, set patient reference
//
//  WHAT CHANGED IN v2
//  ------------------
//   * Every telemetry and alert frame carries the 8-byte device id, so a
//     phone watching three wearables can attribute each reading to the right
//     patient even in an exported log, not just by which socket it arrived on.
//     The IMU stream deliberately does NOT carry it: at 20 Hz across several
//     devices those 8 bytes are real airtime, and the connection already
//     identifies the source for the one consumer that matters (the on-phone
//     classifier, which is per-connection anyway).
//
//   * Notifications are gated on EchoWearIdentity::mayStream(). A claimed
//     device stays silent until the peer proves ownership. Before v2 anyone
//     in range could subscribe to a patient's vitals.
//
//   * The advertised name carries the serial suffix ("EchoWear-7K4M"), so the
//     app can build a meaningful scan list without connecting to each device
//     in turn.
//
//  Every notified payload on 0002/0003/0004 is AES-256-GCM sealed by
//  EchoWearSession once the ML-KEM-512 handshake has completed.
// ============================================================================

// ---------------------------------------------------------------------------
// Link-layer bonding. OFF by default, and that is a decision rather than an
// oversight.
//
// The payload is already sealed end to end with ML-KEM-512 + AES-256-GCM, so
// bonding adds little confidentiality. What it does add is an Android bond
// that OUTLIVES the wearable's factory reset: hand the device to a second
// patient, and the phone reconnects using keys the wearable has thrown away,
// which fails with an encryption error until somebody knows to "Forget this
// device" in Android's Bluetooth settings. For a wearable whose whole point
// is being re-assigned between patients, that is a bad trade. It also makes
// the device look pairable from the system Bluetooth screen, which is how it
// gets connected by something that is not this app.
//
// Set to 1 if you specifically want LE Secure Connections at the link layer.
// ---------------------------------------------------------------------------
#ifndef ECHOWEAR_BLE_BONDING
#define ECHOWEAR_BLE_BONDING 0
#endif

#define ECHOWEAR_FW_VERSION     "2.1.0"

#define SVC_UUID   "6e400000-b5a3-f393-e0a9-e50e24dcca9e"
#define CHR_TLM    "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define CHR_IMU    "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
#define CHR_ALERT  "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
#define CHR_CTRL   "6e400005-b5a3-f393-e0a9-e50e24dcca9e"
#define CHR_KEM    "6e400006-b5a3-f393-e0a9-e50e24dcca9e"
#define CHR_STATUS "6e400007-b5a3-f393-e0a9-e50e24dcca9e"
#define CHR_IDENT  "6e400008-b5a3-f393-e0a9-e50e24dcca9e"
#define CHR_PAIR   "6e400009-b5a3-f393-e0a9-e50e24dcca9e"

// ---------------------------------------------------------------------------
//  NimBLE-Arduino 1.x / 2.x compatibility
// ---------------------------------------------------------------------------
//  The two major versions changed every callback signature: 2.x passes a
//  NimBLEConnInfo& where 1.x passed a ble_gap_conn_desc*, and 1.x has
//  one-argument onRead/onWrite overloads that 2.x dropped. NIMBLE_CPP_VERSION_
//  MAJOR only exists from 2.0, so an undefined macro means 1.x.
//
//  All the version-dependent code is confined to the thin virtual overrides at
//  the bottom of this file; the logic lives in version-agnostic handlers.
// ---------------------------------------------------------------------------
#if defined(NIMBLE_CPP_VERSION_MAJOR) && NIMBLE_CPP_VERSION_MAJOR >= 2
#define ECHOWEAR_NIMBLE2 1
#else
#define ECHOWEAR_NIMBLE2 0
#endif

// ---------------------------------------------------------------- payloads
// Packed so the Android side can parse them with a fixed little-endian layout.
typedef struct __attribute__((packed)) {
    uint8_t  type;              // 0 = telemetry
    uint8_t  fall_state;
    uint8_t  motion_tier;
    uint8_t  flags;             // bit0 worn, bit1 gps valid, bit2 gsm up,
                                // bit3 gait profile warm, bit4 pqc session,
                                // bit5 claimed, bit6 authenticated
    uint8_t  device_id[DEVICE_ID_BYTES];
    uint32_t t_ms;
    float    ax_g, ay_g, az_g;
    float    gx_dps, gy_dps, gz_dps;
    uint32_t ir_raw, red_raw;
    float    bpm;
    float    sdnn_ms, rmssd_ms, pnn50_pct, rsa_index;
    float    bus_voltage_v, current_ma, power_mw;
    float    gait_cadence, gait_variance, gait_sri;
    double   gps_lat, gps_lon;
    uint16_t gps_sats;
    uint16_t battery_pct;
} EchoWearTelemetry;

#define IMU_BATCH_SAMPLES 10
typedef struct __attribute__((packed)) {
    uint8_t  type;              // 1 = IMU batch
    uint8_t  count;
    uint16_t seq;
    uint32_t t0_us;             // timestamp of sample 0
    uint16_t dt_us;             // nominal inter-sample interval
    int16_t  ax[IMU_BATCH_SAMPLES];   // milli-g, saturating
    int16_t  ay[IMU_BATCH_SAMPLES];
    int16_t  az[IMU_BATCH_SAMPLES];
    int16_t  gx[IMU_BATCH_SAMPLES];   // deci-dps
    int16_t  gy[IMU_BATCH_SAMPLES];
    int16_t  gz[IMU_BATCH_SAMPLES];
} EchoWearImuBatch;

typedef struct __attribute__((packed)) {
    uint8_t  type;              // 2 = alert
    uint8_t  level;             // AlertLevel
    uint8_t  should_rescue;
    uint8_t  outliers;
    uint8_t  device_id[DEVICE_ID_BYTES];
    uint32_t t_ms;
    float    score;             // risk / impact g / deviation D, by level
    float    hr_bpm;
    float    gait_deviation;
    double   gps_lat, gps_lon;
} EchoWearAlert;

typedef struct __attribute__((packed)) {
    uint8_t type;               // 0xC3
    uint8_t command;
    uint8_t value;
} EchoWearControl;

// The Android parser (BleProtocol.kt) hard-codes these sizes. If a field is
// added here the build must fail rather than silently desynchronising.
static_assert(sizeof(EchoWearTelemetry) == 112, "telemetry layout changed - update BleProtocol.kt");
static_assert(sizeof(EchoWearImuBatch)  == 130, "IMU batch layout changed - update BleProtocol.kt");
static_assert(sizeof(EchoWearAlert)     ==  44, "alert layout changed - update BleProtocol.kt");
static_assert(sizeof(EchoWearControl)   ==   3, "control layout changed - update BleProtocol.kt");
static_assert(sizeof(EchoWearPairStatus) == 32, "pair status changed - update BleProtocol.kt");

enum class AlertLevel : uint8_t {
    NONE = 0, FALL_PREDICTED = 1, FALL_DETECTED = 2,
    FALL_CONFIRMED = 3, CARDIAC_FALL = 4, MANUAL_SOS = 5
};

#define CONTROL_PACKET_TYPE 0xC3
enum ControlCommand : uint8_t {
    CTRL_GSM_ENABLE   = 1,
    CTRL_MANUAL_SMS   = 2,
    CTRL_PQC_START    = 3,
    CTRL_ALERT_ACK    = 4,
    CTRL_TLM_LOGGING  = 5,
    CTRL_ML_LOGGING   = 6,
    CTRL_IMU_STREAM   = 7,     // phone turns the raw stream on/off
    CTRL_GPS_WAKE     = 8,
    CTRL_BUZZER_TEST  = 9,
    CTRL_RESET_GAIT   = 10,
    CTRL_OPEN_PAIRING = 11,    // reopen the pairing window (unclaimed only)
    CTRL_FACTORY_RESET= 12,    // requires an authenticated peer
    // The phone claiming responsibility for an alert it has just received.
    // While a takeover is in force the wearable runs its screen countdown but
    // does NOT send its own SMS, so the caregiver gets one message rather
    // than two. It lapses if the link drops - see EchoWearBLE::alertOwnedByPhone().
    CTRL_ALERT_TAKEOVER = 13,
};

// --------------------------------------------------------------- the server
class EchoWearBLE {
public:
    volatile bool connected = false;
    volatile bool imu_stream_enabled = true;
    volatile bool control_pending = false;
    volatile uint8_t pending_command = 0;
    volatile uint8_t pending_value = 0;
    uint16_t mtu = 23;
    portMUX_TYPE ctrl_mux_ = portMUX_INITIALIZER_UNLOCKED;

    void begin(EchoWearSession *session, EchoWearIdentity *identity) {
        session_ = session;
        identity_ = identity;

        // Advertising the serial suffix lets the app build a scan list that
        // distinguishes three wearables on a ward without connecting to each.
        snprintf(adv_name_, sizeof(adv_name_), "EchoWear-%.4s", identity_->serial + 3);

        NimBLEDevice::init(adv_name_);
#if ECHOWEAR_NIMBLE2
        NimBLEDevice::setPower(9);                  // dBm in 2.x
#else
        NimBLEDevice::setPower(ESP_PWR_LVL_P9);     // enum in 1.x
#endif
        NimBLEDevice::setMTU(247);
#if ECHOWEAR_BLE_BONDING
        // LE Secure Connections with bonding: the link layer is encrypted in
        // addition to the ML-KEM/AES sealing that rides on top of it.
        NimBLEDevice::setSecurityAuth(true, false, true);
#endif

        server_ = NimBLEDevice::createServer();
        // One-argument form: both versions default deleteCallbacks to true.
        server_->setCallbacks(new ServerCb(this));

        NimBLEService *svc = server_->createService(SVC_UUID);

        chr_tlm_ = svc->createCharacteristic(CHR_TLM, NIMBLE_PROPERTY::NOTIFY);
        chr_imu_ = svc->createCharacteristic(CHR_IMU, NIMBLE_PROPERTY::NOTIFY);
        chr_alert_ = svc->createCharacteristic(
            CHR_ALERT, NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::INDICATE);
        chr_ctrl_ = svc->createCharacteristic(
            CHR_CTRL, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
        chr_ctrl_->setCallbacks(new CtrlCb(this));
        chr_kem_ = svc->createCharacteristic(
            CHR_KEM, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
        chr_kem_->setCallbacks(new KemCb(this));
        chr_status_ = svc->createCharacteristic(CHR_STATUS, NIMBLE_PROPERTY::READ);
        chr_ident_ = svc->createCharacteristic(CHR_IDENT, NIMBLE_PROPERTY::READ);
        chr_pair_ = svc->createCharacteristic(
            CHR_PAIR, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
        chr_pair_->setCallbacks(new PairCb(this));

        svc->start();

        NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();

        // ---------------------------------------------------------------
        // Both payloads are built by hand rather than left to the library,
        // and that is the difference between a device the phone can find
        // and one it cannot.
        //
        // A BLE advertisement is 31 bytes. The flags take 3. A 128-bit
        // service UUID takes 2 + 16 = 18. That leaves 10, and the name
        // "EchoWear-7K4M" needs 2 + 13 = 15. Let NimBLE lay it out and
        // ble_gap_adv_set_fields() fails with BLE_HS_EMSGSIZE, start()
        // returns false, and THE DEVICE NEVER ADVERTISES - while the sketch
        // cheerfully prints that it is advertising, which is how you lose an
        // evening to it.
        //
        // So: the UUID stays in the primary advertisement, because that is
        // what Android's offloaded scan filter matches against; the name
        // goes in the scan response, where Android still reads it back
        // through ScanRecord.getDeviceName().
        // ---------------------------------------------------------------
        NimBLEAdvertisementData adv_data;
        adv_data.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
        adv_data.setCompleteServices(NimBLEUUID(SVC_UUID));

        NimBLEAdvertisementData scan_data;
        scan_data.setName(adv_name_);

        adv->setAdvertisementData(adv_data);
        adv->setScanResponseData(scan_data);
#if ECHOWEAR_NIMBLE2
        adv->enableScanResponse(true);
#else
        adv->setScanResponse(true);
#endif
        bool advertising = adv->start();

        refreshStatus();
        refreshIdentity();
        refreshPairStatus();

        if (advertising) {
            Serial.printf("BLE  | advertising as %s\n", adv_name_);
            Serial.printf("BLE  | service %s\n", SVC_UUID);
        } else {
            // Never claim to be advertising when we are not.
            Serial.println("BLE  | *** ADVERTISING FAILED TO START ***");
            Serial.println("BLE  | the phone will not be able to find this device");
        }
    }

    void refreshStatus() {
        char buf[160];
        snprintf(buf, sizeof(buf),
                 "EchoWear;proto=%d;fw=%s;pqc=%d;session=%s;imu=%d;stream=%d",
                 ECHOWEAR_PROTOCOL_VERSION, ECHOWEAR_FW_VERSION,
                 session_->isPqcBuilt() ? 1 : 0, session_->stateName(),
                 imu_stream_enabled ? 1 : 0,
                 identity_->mayStream() ? 1 : 0);
        chr_status_->setValue((uint8_t *)buf, strlen(buf));
    }

    void refreshIdentity() {
        char buf[160];
        snprintf(buf, sizeof(buf),
                 "serial=%s;type=%u;fw=%s;proto=%d;label=%s;patient=%s;claimed=%d",
                 identity_->serial, (unsigned)ECHOWEAR_DEVICE_TYPE,
                 ECHOWEAR_FW_VERSION, ECHOWEAR_PROTOCOL_VERSION,
                 identity_->label,
                 // The patient reference is an opaque key, not a name, and is
                 // only exposed once the peer has proved it owns the device.
                 identity_->authenticated ? identity_->patient_ref : "",
                 identity_->claimed ? 1 : 0);
        chr_ident_->setValue((uint8_t *)buf, strlen(buf));
    }

    void refreshPairStatus() {
        EchoWearPairStatus s;
        identity_->fillStatus(s);
        chr_pair_->setValue((uint8_t *)&s, sizeof(s));
    }

    // Sealed notifications -------------------------------------------------
    // Why telemetry did or did not go out, for the serial LINK line. Before these existed, three different
    // reasons (no phone, ownership not proved, sealing failed) all looked identical: nothing arrived.
    uint32_t tlm_sent = 0, tlm_gated = 0, tlm_seal_fail = 0;

    void notifyTelemetry(EchoWearTelemetry &t) {
        stampIdentity(t.device_id, t.flags);
        switch (sendSealed(chr_tlm_, (const uint8_t *)&t, sizeof(t))) {
            case SEND_OK:        tlm_sent++;      break;
            case SEND_GATED:     tlm_gated++;     break;
            case SEND_SEAL_FAIL: tlm_seal_fail++; break;
            default: break;                       // no phone connected: not worth counting
        }
    }
    void notifyImu(const EchoWearImuBatch &b) {
        if (!imu_stream_enabled) return;
        sendSealed(chr_imu_, (const uint8_t *)&b, sizeof(b));
    }
    void notifyAlert(EchoWearAlert &a) {
        memcpy(a.device_id, identity_->device_id, DEVICE_ID_BYTES);
        sendSealed(chr_alert_, (const uint8_t *)&a, sizeof(a));
    }

    // NimBLE callbacks run in the host task, which may be on the other core,
    // so the handover needs a spinlock rather than an interrupt mask.
    bool takeControl(uint8_t &cmd, uint8_t &val) {
        if (!control_pending) return false;
        portENTER_CRITICAL(&ctrl_mux_);
        cmd = pending_command;
        val = pending_value;
        control_pending = false;
        portEXIT_CRITICAL(&ctrl_mux_);
        return true;
    }

private:
    EchoWearSession *session_ = nullptr;
    EchoWearIdentity *identity_ = nullptr;
    NimBLEServer *server_ = nullptr;
    NimBLECharacteristic *chr_tlm_ = nullptr, *chr_imu_ = nullptr,
                         *chr_alert_ = nullptr, *chr_ctrl_ = nullptr,
                         *chr_kem_ = nullptr, *chr_status_ = nullptr,
                         *chr_ident_ = nullptr, *chr_pair_ = nullptr;
    uint16_t kem_read_offset_ = 0;
    uint8_t  tx_[360];
    char     adv_name_[24] = "EchoWear";

    void stampIdentity(uint8_t *dst, uint8_t &flags) {
        memcpy(dst, identity_->device_id, DEVICE_ID_BYTES);
        if (identity_->claimed)       flags |= 0x20;
        if (identity_->authenticated) flags |= 0x40;
    }

    enum SendResult { SEND_OK = 0, SEND_NO_LINK = 1, SEND_GATED = 2, SEND_SEAL_FAIL = 3 };

    SendResult sendSealed(NimBLECharacteristic *chr, const uint8_t *data, size_t len) {
        if (!connected || chr == nullptr) return SEND_NO_LINK;
        // The ownership gate. A claimed device streams nothing to a peer that
        // has not proved it owns the device, which is the whole point of
        // pairing: one ward, three wearables, three different patients.
        if (!identity_->mayStream()) return SEND_GATED;
        size_t n = session_->seal(data, len, tx_, sizeof(tx_));
        if (n == 0) return SEND_SEAL_FAIL;       // sealing failed: send nothing
        chr->setValue(tx_, n);
        chr->notify();
        return SEND_OK;
    }

    // ------------------------------------------------- version-agnostic core
    // These hold the actual logic. The virtual overrides below are thin
    // adapters, so only the signatures differ between NimBLE 1.x and 2.x.
    void handleConnect(NimBLEServer *s, uint16_t conn_handle, uint16_t peer_mtu) {
        connected = true;
        if (peer_mtu > 0) mtu = peer_mtu;
        // 30-60 ms connection interval keeps the 20 Hz IMU stream flowing
        // without pinning the radio on.
        s->updateConnParams(conn_handle, 24, 48, 0, 400);

        // Keep advertising even though we now have a central.
        //
        // A BLE peripheral stops advertising the instant something connects.
        // That is the spec-correct default and it is a trap here, because the
        // first thing that connects is very often NOT this app: it is the
        // phone's own Bluetooth settings screen, which lists the wearable,
        // invites the user to tap it, and then bonds and holds the link. From
        // that moment the wearable is invisible to every scan on the phone,
        // including ours, and the user is left looking at a device that is
        // plainly working and plainly undiscoverable.
        //
        // Restarting the advertisement here costs a few hundred microamps and
        // makes the device findable no matter who got there first. NimBLE
        // allows CONFIG_BT_NIMBLE_MAX_CONNECTIONS centrals (3 by default);
        // any extra one still gets nothing without an owner token, because
        // mayStream() gates the data, not the connection.
        NimBLEDevice::getAdvertising()->start();

        Serial.printf("BLE  | connected, MTU=%u (still advertising)\n", mtu);
    }

    void handleDisconnect(int reason) {
        connected = false;
        session_->resetSession();               // no key survives a disconnect
        identity_->onDisconnect();              // nor does authentication
        refreshPairStatus();
        Serial.printf("BLE | disconnected (reason %d), advertising again\n", reason);
        NimBLEDevice::getAdvertising()->start();
    }

    void handleCtrlWrite(NimBLECharacteristic *c) {
        // std::string rather than NimBLEAttValue: NimBLEAttValue converts to
        // it in both versions, so this one line works on either.
        std::string v = c->getValue();
        if (v.size() != sizeof(EchoWearControl)) return;
        EchoWearControl ctl;
        memcpy(&ctl, v.data(), sizeof(ctl));
        if (ctl.type != CONTROL_PACKET_TYPE) return;

        // Commands that change the device's state belong to its owner only.
        bool privileged = (ctl.command == CTRL_FACTORY_RESET ||
                           ctl.command == CTRL_MANUAL_SMS ||
                           ctl.command == CTRL_RESET_GAIT);
        if (privileged && !identity_->authenticated) {
            Serial.println("CTRL | refused: peer is not the owner");
            return;
        }

        portENTER_CRITICAL(&ctrl_mux_);
        pending_command = ctl.command;
        pending_value = ctl.value;
        control_pending = true;
        portEXIT_CRITICAL(&ctrl_mux_);
    }

    void handlePairWrite(NimBLECharacteristic *c) {
        std::string v = c->getValue();
        identity_->handleWrite((const uint8_t *)v.data(), v.size());
        refreshPairStatus();
        refreshIdentity();
        refreshStatus();
    }

    void handlePairRead(NimBLECharacteristic *) { refreshPairStatus(); }

    // Serve the next public-key chunk, prefixed with its offset. The chunk
    // size comes from the MTU tracked on connect, which both versions give us.
    void handleKemRead(NimBLECharacteristic *c) {
        uint8_t buf[KEM_CHUNK_BYTES + 2];
        size_t payload = (mtu > 5) ? (size_t)(mtu - 5) : 20;
        if (payload > KEM_CHUNK_BYTES) payload = KEM_CHUNK_BYTES;

        size_t n = session_->publicKeyChunk(kem_read_offset_, buf + 2, payload);
        buf[0] = (kem_read_offset_ >> 8) & 0xFF;
        buf[1] = kem_read_offset_ & 0xFF;
        c->setValue(buf, n + 2);
        kem_read_offset_ += n;
        if (kem_read_offset_ >= KEM_PUBLICKEY_BYTES) {
            kem_read_offset_ = 0;               // wrap for a repeat fetch
        }
    }

    // [2-byte offset][ciphertext chunk], or a single 0xFF to restart.
    void handleKemWrite(NimBLECharacteristic *c) {
        std::string v = c->getValue();
        const uint8_t *d = (const uint8_t *)v.data();
        if (v.size() == 1 && d[0] == 0xFF) {
            kem_read_offset_ = 0;
            session_->startKeyExchange();
            refreshStatus();
            return;
        }
        if (v.size() < 3) return;
        uint16_t off = ((uint16_t)d[0] << 8) | d[1];
        if (!session_->feedCiphertextChunk(off, d + 2, v.size() - 2)) {
            Serial.println("BLE | KEM ciphertext rejected");
        }
        refreshStatus();
    }

    // -------------------------------------------------------- BLE callbacks
    class ServerCb : public NimBLEServerCallbacks {
    public:
        explicit ServerCb(EchoWearBLE *o) : o_(o) {}
#if ECHOWEAR_NIMBLE2
        void onConnect(NimBLEServer *s, NimBLEConnInfo &info) override {
            o_->handleConnect(s, info.getConnHandle(), info.getMTU());
        }
        void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int reason) override {
            o_->handleDisconnect(reason);
        }
        void onMTUChange(uint16_t mtu, NimBLEConnInfo &) override { o_->mtu = mtu; }
#else
        void onConnect(NimBLEServer *s, ble_gap_conn_desc *desc) override {
            o_->handleConnect(s, desc->conn_handle,
                              s->getPeerMTU(desc->conn_handle));
        }
        // 1.x carries no disconnect reason in this callback.
        void onDisconnect(NimBLEServer *) override { o_->handleDisconnect(0); }
        void onMTUChange(uint16_t mtu, ble_gap_conn_desc *) override { o_->mtu = mtu; }
#endif
    private:
        EchoWearBLE *o_;
    };

    class CtrlCb : public NimBLECharacteristicCallbacks {
    public:
        explicit CtrlCb(EchoWearBLE *o) : o_(o) {}
#if ECHOWEAR_NIMBLE2
        void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
            o_->handleCtrlWrite(c);
        }
#else
        void onWrite(NimBLECharacteristic *c) override { o_->handleCtrlWrite(c); }
#endif
    private:
        EchoWearBLE *o_;
    };

    class KemCb : public NimBLECharacteristicCallbacks {
    public:
        explicit KemCb(EchoWearBLE *o) : o_(o) {}
#if ECHOWEAR_NIMBLE2
        void onRead(NimBLECharacteristic *c, NimBLEConnInfo &) override {
            o_->handleKemRead(c);
        }
        void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
            o_->handleKemWrite(c);
        }
#else
        void onRead(NimBLECharacteristic *c) override { o_->handleKemRead(c); }
        void onWrite(NimBLECharacteristic *c) override { o_->handleKemWrite(c); }
#endif
    private:
        EchoWearBLE *o_;
    };

    class PairCb : public NimBLECharacteristicCallbacks {
    public:
        explicit PairCb(EchoWearBLE *o) : o_(o) {}
#if ECHOWEAR_NIMBLE2
        void onRead(NimBLECharacteristic *c, NimBLEConnInfo &) override {
            o_->handlePairRead(c);
        }
        void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &) override {
            o_->handlePairWrite(c);
        }
#else
        void onRead(NimBLECharacteristic *c) override { o_->handlePairRead(c); }
        void onWrite(NimBLECharacteristic *c) override { o_->handlePairWrite(c); }
#endif
    private:
        EchoWearBLE *o_;
    };
};

#endif  // ECHOWEAR_BLE_H_
