#ifndef ECHOWEAR_GPS_H_
#define ECHOWEAR_GPS_H_

#include <Arduino.h>
#include <TinyGPS++.h>

// ============================================================================
//  ATGM336H GPS with duty cycling
// ----------------------------------------------------------------------------
//  Ported from EchoWear.ino with three corrections:
//   * the sleep/wake state machine no longer reuses one timestamp variable for
//     both the search timeout and the sleep interval, which in the original
//     could hold the receiver awake indefinitely after a timeout;
//   * an emergency wake path so a fall alert does not have to wait out the
//     5-minute sleep before it has a position;
//   * the last valid fix is retained with its age, so an alert raised indoors
//     still carries the most recent known location rather than 0,0.
// ============================================================================

#define GPS_UART_NUM            1
#define GPS_RX_PIN              4
#define GPS_TX_PIN              5
#define GPS_BAUD                9600
#define GPS_SLEEP_INTERVAL_MS   (5UL * 60UL * 1000UL)
#define GPS_SEARCH_TIMEOUT_MS   (60UL * 1000UL)
#define GPS_FIX_STALE_MS        (15UL * 60UL * 1000UL)

class EchoWearGPS {
public:
    double   last_lat = 0.0, last_lon = 0.0;
    uint32_t last_sats = 0;
    bool     have_fix = false;
    uint32_t last_fix_ms = 0;
    bool     awake = true;

    void begin() {
        serial_.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
        delay(50);
        wake("boot");
    }

    // Call every loop; cheap when there is nothing to read.
    void poll(uint32_t now_ms) {
        while (serial_.available() > 0) gps_.encode(serial_.read());

        if (awake) {
            bool got = gps_.location.isUpdated() && gps_.location.isValid();
            if (got) {
                last_lat = gps_.location.lat();
                last_lon = gps_.location.lng();
                last_sats = gps_.satellites.isValid() ? gps_.satellites.value() : 0;
                have_fix = true;
                last_fix_ms = now_ms;
                Serial.printf("GPS_FIX,%lu,Lat:%.6f,Lon:%.6f,Sats:%lu\n",
                              now_ms, last_lat, last_lon, (unsigned long)last_sats);
                sleep(now_ms, "fix acquired");
            } else if (now_ms - wake_started_ms_ > GPS_SEARCH_TIMEOUT_MS) {
                Serial.printf("GPS_TIMEOUT,%lu,no fix (indoors?)\n", now_ms);
                sleep(now_ms, "search timeout");
            }
        } else if (now_ms - sleep_started_ms_ >= GPS_SLEEP_INTERVAL_MS) {
            wake("scheduled");
        }
    }

    // A fall alert needs a position now, not in five minutes.
    void requestEmergencyFix() {
        if (!awake) wake("emergency");
    }

    bool fixIsFresh(uint32_t now_ms) const {
        return have_fix && (now_ms - last_fix_ms) < GPS_FIX_STALE_MS;
    }

    uint32_t fixAgeSec(uint32_t now_ms) const {
        return have_fix ? (now_ms - last_fix_ms) / 1000UL : 0;
    }

    TinyGPSPlus &raw() { return gps_; }

private:
    HardwareSerial serial_{GPS_UART_NUM};
    TinyGPSPlus gps_;
    uint32_t wake_started_ms_ = 0;
    uint32_t sleep_started_ms_ = 0;

    void wake(const char *why) {
        serial_.print("W");                 // any byte wakes the ATGM336H
        awake = true;
        wake_started_ms_ = millis();
        Serial.printf("GPS_WAKE,%lu,%s\n", wake_started_ms_, why);
    }

    void sleep(uint32_t now_ms, const char *why) {
        serial_.println("$PCAS11,3*1F");    // CASIC low-power mode
        awake = false;
        sleep_started_ms_ = now_ms;
        Serial.printf("GPS_SLEEP,%lu,%s\n", now_ms, why);
    }
};

#endif  // ECHOWEAR_GPS_H_
