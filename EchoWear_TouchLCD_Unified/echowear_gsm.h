#ifndef ECHOWEAR_GSM_H_
#define ECHOWEAR_GSM_H_

#include <Arduino.h>

// ============================================================================
//  SIM800L caregiver SMS  -  NON-BLOCKING
// ----------------------------------------------------------------------------
//  The original EchoWear.ino sent SMS with a chain of blocking waits
//  (waitForGsmText up to 15 s). On a fall-detection wearable that is the worst
//  possible place to block: the device stops sampling the IMU, stops updating
//  the screen and stops answering BLE for the whole send - exactly during the
//  event it exists to capture.
//
//  This driver is a state machine. Every call does a bounded amount of work
//  and returns. A full send takes the same wall-clock time on the modem, but
//  the 100 Hz sensor loop, the touch UI and the BLE link all keep running.
// ============================================================================

#define GSM_UART_NUM        2
#define GSM_RX_PIN          7      // ESP32 RX  <- SIM800L TX
#define GSM_TX_PIN          8      // ESP32 TX  -> SIM800L RX
#define GSM_BAUD            9600
#define GSM_MAX_MSG_LEN     480

enum class GsmState : uint8_t {
    OFFLINE = 0, INIT_AT, INIT_ECHO, INIT_CPIN, INIT_TEXTMODE, READY,
    SEND_CMGS, SEND_BODY, SEND_WAIT_OK, COOLDOWN, FAULT
};

class EchoWearGSM {
public:
    bool     enabled = true;
    uint16_t sends_ok = 0, sends_failed = 0;
    int      last_csq = -1;

    void begin() {
        serial_.begin(GSM_BAUD, SERIAL_8N1, GSM_RX_PIN, GSM_TX_PIN);
        delay(100);
        state_ = GsmState::INIT_AT;
        step_started_ms_ = millis();
        issue("AT");
    }

    void setCaregiver(const char *number) {
        strncpy(caregiver_, number, sizeof(caregiver_) - 1);
        caregiver_[sizeof(caregiver_) - 1] = '\0';
    }

    bool isReady() const { return state_ == GsmState::READY; }
    bool isBusy() const {
        return state_ == GsmState::SEND_CMGS || state_ == GsmState::SEND_BODY ||
               state_ == GsmState::SEND_WAIT_OK;
    }
    const char *stateName() const {
        switch (state_) {
            case GsmState::OFFLINE:       return "OFFLINE";
            case GsmState::READY:         return "READY";
            case GsmState::FAULT:         return "FAULT";
            case GsmState::COOLDOWN:      return "COOLDOWN";
            default: return isBusy() ? "SENDING" : "INIT";
        }
    }

    // Queue a message. Returns false if one is already queued or in flight -
    // the caller should not spin waiting; the alert is already going out.
    bool queue(const String &msg) {
        if (queued_) return false;
        size_t n = msg.length();
        if (n > GSM_MAX_MSG_LEN) n = GSM_MAX_MSG_LEN;
        memcpy(pending_, msg.c_str(), n);
        pending_[n] = '\0';
        queued_ = true;
        return true;
    }

    // Drive the state machine. Call every loop.
    void poll(uint32_t now_ms) {
        drain();

        switch (state_) {
            case GsmState::OFFLINE:
            case GsmState::FAULT:
                // Retry the modem once a minute rather than never.
                if (now_ms - step_started_ms_ > 60000UL) {
                    state_ = GsmState::INIT_AT;
                    step_started_ms_ = now_ms;
                    issue("AT");
                }
                break;

            case GsmState::INIT_AT:
                if (sawOK())      advance(GsmState::INIT_ECHO, "ATE0", now_ms);
                else if (timedOut(now_ms, 2000)) fail(now_ms, "no AT response");
                break;

            case GsmState::INIT_ECHO:
                if (sawOK())      advance(GsmState::INIT_CPIN, "AT+CPIN?", now_ms);
                else if (timedOut(now_ms, 2000)) advance(GsmState::INIT_CPIN, "AT+CPIN?", now_ms);
                break;

            case GsmState::INIT_CPIN:
                if (saw("READY")) advance(GsmState::INIT_TEXTMODE, "AT+CMGF=1", now_ms);
                else if (timedOut(now_ms, 5000)) fail(now_ms, "SIM not ready");
                break;

            case GsmState::INIT_TEXTMODE:
                if (sawOK()) {
                    state_ = GsmState::READY;
                    step_started_ms_ = now_ms;
                    clearRx();
                    issue("AT+CSQ");
                    Serial.println("GSM | SIM800L ready (text mode)");
                } else if (timedOut(now_ms, 3000)) {
                    fail(now_ms, "CMGF failed");
                }
                break;

            case GsmState::READY:
                parseCsq();
                if (queued_ && enabled) startSend(now_ms);
                else if (now_ms - csq_polled_ms_ > 30000UL) {
                    csq_polled_ms_ = now_ms;
                    issue("AT+CSQ");
                }
                break;

            case GsmState::SEND_CMGS:
                if (saw(">")) {
                    serial_.print(pending_);
                    serial_.write(26);            // Ctrl-Z terminates the body
                    state_ = GsmState::SEND_WAIT_OK;
                    step_started_ms_ = now_ms;
                    clearRx();
                } else if (timedOut(now_ms, 6000)) {
                    fail(now_ms, "no SMS prompt");
                }
                break;

            case GsmState::SEND_WAIT_OK:
                if (sawOK() || saw("+CMGS")) {
                    sends_ok++;
                    queued_ = false;
                    Serial.printf("GSM | caregiver SMS sent (#%u)\n", sends_ok);
                    state_ = GsmState::COOLDOWN;
                    step_started_ms_ = now_ms;
                } else if (saw("ERROR")) {
                    sends_failed++;
                    queued_ = false;
                    fail(now_ms, "modem returned ERROR");
                } else if (timedOut(now_ms, 20000)) {
                    sends_failed++;
                    queued_ = false;
                    fail(now_ms, "send timeout");
                }
                break;

            case GsmState::COOLDOWN:
                if (now_ms - step_started_ms_ > 1500UL) {
                    state_ = GsmState::READY;
                    clearRx();
                }
                break;

            default:
                break;
        }
    }

private:
    HardwareSerial serial_{GSM_UART_NUM};
    GsmState state_ = GsmState::OFFLINE;
    uint32_t step_started_ms_ = 0;
    uint32_t csq_polled_ms_ = 0;
    char     rx_[192] = {0};
    size_t   rx_len_ = 0;
    char     pending_[GSM_MAX_MSG_LEN + 1] = {0};
    bool     queued_ = false;
    char     caregiver_[20] = "+233000000000";

    void issue(const char *cmd) {
        clearRx();
        serial_.println(cmd);
    }

    void advance(GsmState next, const char *cmd, uint32_t now_ms) {
        state_ = next;
        step_started_ms_ = now_ms;
        issue(cmd);
    }

    void fail(uint32_t now_ms, const char *why) {
        Serial.printf("GSM | %s\n", why);
        state_ = GsmState::FAULT;
        step_started_ms_ = now_ms;
    }

    bool timedOut(uint32_t now_ms, uint32_t limit) const {
        return now_ms - step_started_ms_ > limit;
    }

    // Read whatever the modem has sent without ever waiting for it.
    void drain() {
        while (serial_.available()) {
            char c = (char)serial_.read();
            if (rx_len_ < sizeof(rx_) - 1) {
                rx_[rx_len_++] = c;
                rx_[rx_len_] = '\0';
            } else {
                // Keep the newest half so a long unsolicited message cannot
                // push the token we are waiting for out of the buffer.
                memmove(rx_, rx_ + sizeof(rx_) / 2, sizeof(rx_) / 2);
                rx_len_ = sizeof(rx_) / 2 - 1;
                rx_[rx_len_] = '\0';
            }
        }
    }

    void clearRx() { rx_len_ = 0; rx_[0] = '\0'; }
    bool saw(const char *tok) const { return strstr(rx_, tok) != nullptr; }
    bool sawOK() const { return saw("OK"); }

    void parseCsq() {
        const char *p = strstr(rx_, "+CSQ:");
        if (!p) return;
        int rssi = -1;
        if (sscanf(p, "+CSQ: %d", &rssi) == 1) last_csq = rssi;
        clearRx();
    }

    void startSend(uint32_t now_ms) {
        clearRx();
        serial_.print("AT+CMGS=\"");
        serial_.print(caregiver_);
        serial_.println("\"");
        state_ = GsmState::SEND_CMGS;
        step_started_ms_ = now_ms;
    }
};

#endif  // ECHOWEAR_GSM_H_
