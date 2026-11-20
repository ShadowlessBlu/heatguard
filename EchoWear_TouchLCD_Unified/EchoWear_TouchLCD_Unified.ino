/*
  =====================================================================
  EchoWear - Unified Touchscreen Wearable  (firmware 2.0.0)
  Board: Waveshare ESP32-S3-Touch-LCD-1.47  (ST7789 172x320 + AXS5106L)
  =====================================================================

  WHAT CHANGED FROM THE PREVIOUS TOUCH-LCD SKETCH
  -----------------------------------------------
  Bugs fixed:

   1. THE SCREEN NEVER UPDATED. The old redraw block ran only when
      `currentScreen != lastDrawnScreen || forceRedraw`, so heart rate,
      HRV, power and gait were painted once on entry and then frozen.
      Only the status bar refreshed. Every screen now has a static part
      (drawn on entry) and a dynamic part (repainted in place at 4 Hz),
      so values are live without full-screen flicker.

   2. FALL STATE WAS CLEARED 10 ms AFTER EVERY IMPACT. The old loop did
      `else { current_fall_state = STATE_NORMAL; }` on every sample
      below threshold, so the state flipped back to NORMAL on the next
      IMU read and the fall screen showed "OK" while the alert was up.
      The state is now latched and cleared by a timer or an ack.

   3. NO SENSOR PRESENCE CHECKS. If the MAX30105, MPU6050 or INA219 was
      absent or unpowered, the loop still called getIR()/getMotion6()/
      getBusVoltage_V() forever and printed garbage voltages. Each
      device now has a presence flag, is retried periodically, and the
      UI says "--" instead of inventing a number.

   4. TAPS WERE READ AS SWIPES. SWIPE_THRESHOLD (10 px) was below
      TAP_THRESHOLD (15 px), so any tap that drifted 10-15 px paged the
      screen instead. Swipe is now 32 px with a dominant-axis test, tap
      is 12 px with a 400 ms limit.

   5. THE SHARED I2C BUS WAS SATURATED. Touch and PPG were both polled
      as fast as the loop ran, on the same bus as the IMU. Touch is now
      polled at 60 Hz and the PPG FIFO at 100 Hz, which is above their
      sample rates and leaves the bus free for the 100 Hz IMU read.

   6. THE GAIT PROFILE WAS FICTIONAL. TS-HGV Stage 2 compared every
      wearer against the hard-coded defaults in motion.h
      (mu_cadence = 100, sigma = 12) which were never updated from the
      wearer, and used a peak-counting cadence estimate and a
      fixed-lag autocorrelation instead of the FFT-band cadence and
      dominant-lag autocorrelation the algorithm is specified with.
      Evaluated on the HIFD wrist dataset (21 subjects, 104 falls,
      245 ADLs) that combination rejected 94% of genuine falls. See
      gait_profile.h - Stage 2 now learns the wearer's own baseline
      online and uses the specified features: 88.5% sensitivity at
      6.5% false-positive rate, against 97.1%/15.5% for the bare 2.5 g
      threshold.

   7. SMS BLOCKED THE WHOLE DEVICE. The old sendCaregiverSMS() chained
      blocking waits up to 15 s, during which the IMU was not sampled
      and the UI was frozen - during the fall. echowear_gsm.h is now a
      non-blocking state machine.

  Added:

   * BLE GATT server (NimBLE) replacing the ESP-NOW link to the
     Cardputer. The phone is now both the receiver and the ML host:
     the wearable streams raw IMU windows at 20 Hz and the Android app
     runs the classifier. See echowear_ble.h.
   * ML-KEM-512 (FIPS 203) session establishment and AES-256-GCM
     sealing of every notification. See echowear_crypto.h.
   * ATGM336H GPS with duty cycling and an emergency wake on alert.
   * SIM800L SMS as the fallback path when the phone is out of range.
   * Nine screens including link/security and location.

  LIBRARIES
  ---------
    Arduino_GFX_Library        (display)
    esp_lcd_touch_axs5106l.h   (touch, Waveshare driver)
    I2Cdev + MPU6050           (IMU)
    SparkFun MAX3010x          (MAX30105.h, heartRate.h)
    Adafruit_INA219            (power)
    NimBLE-Arduino  >= 2.0     (BLE)
    TinyGPS++                  (GPS)
    PQCMicro                   (ML-KEM-512) - set ECHOWEAR_PQC_ENABLED 0
                               in echowear_crypto.h to build without it
  =====================================================================
*/

#include <Arduino.h>
#include <Arduino_GFX_Library.h>
#include <Wire.h>
#include <cmath>
#include "esp_lcd_touch_axs5106l.h"

#include "I2Cdev.h"
#include "MPU6050.h"
#include "MAX30105.h"
#include "heartRate.h"
#include <Adafruit_INA219.h>

#include "heartbeat.h"
#include "motion.h"
#include "fall_prediction.h"
#include "gait_profile.h"

// Stage 3 thresholds must be defined before the validator is included.
#define TS_HGV_CARDIAC_BASELINE_WINDOW_S    60.0f
#define TS_HGV_CARDIAC_POST_WINDOW_S        15.0f
#define TS_HGV_CARDIAC_MIN_HR_DELTA_BPM     10.0f
#define TS_HGV_CARDIAC_RESCUE_MIN_DELTA_BPM 15.0f
#include "cardiac_fall_validator.h"

// Identifies this build to the pairing layer. The touch variant can show a
// fresh random code on screen, so it gets the strong proof-of-possession path.
#define ECHOWEAR_DEVICE_TYPE ECHOWEAR_TYPE_TOUCH_LCD

#include "echowear_crypto.h"
#include "echowear_identity.h"
#include "echowear_ble.h"
#include "echowear_gps.h"
#include "echowear_gsm.h"
#include "echowear_band_model.h"

// ---------------------------------------------------------------------------
// Stale-header check.
//
// Both sketch folders carry their own copy of the shared headers. Update one
// folder, flash the other, and the compiler complains about a missing
// identifier with no hint as to why. This says why.
// ---------------------------------------------------------------------------
#if !defined(ECHOWEAR_BLE_HEADER_REV) || ECHOWEAR_BLE_HEADER_REV < 3
#error "echowear_ble.h in THIS sketch folder is out of date. Copy the shared .h files from the other EchoWear sketch folder into this one and rebuild."
#endif
#if !defined(ECHOWEAR_IDENTITY_HEADER_REV) || ECHOWEAR_IDENTITY_HEADER_REV < 3
#error "echowear_identity.h in THIS sketch folder is out of date. Copy the shared .h files from the other EchoWear sketch folder into this one and rebuild."
#endif


// =====================================================================
//  CONFIGURATION
// =====================================================================
static const char *CAREGIVER_PHONE = "+233000000000";   // set before deployment

#define ALERT_COUNTDOWN_MS        30000UL   // user can cancel before SMS

// Two half-width buttons on the pairing screen, on the same baseline as the
// single full-width button the alert screen uses.
#define PAIR_BTN_W                78
#define PAIR_BTN_L_X              8
#define PAIR_BTN_R_X              (SCREEN_W - PAIR_BTN_W - 8)
#define FALL_STATE_HOLD_MS         8000UL   // how long a fall state stays latched
#define EMERGENCY_SMS_COOLDOWN_MS 120000UL

// =====================================================================
//  HARDWARE PINS - Waveshare ESP32-S3-Touch-LCD-1.47
// =====================================================================
#define LCD_SCK    38
#define LCD_MOSI   39
#define LCD_DC     45
#define LCD_CS     21
#define LCD_RST    40
#define LCD_BL     46

#define TOUCH_SDA  42   // shared bus - also MPU6050 / MAX30105 / INA219
#define TOUCH_SCL  41
#define TOUCH_RST  47
#define TOUCH_INT  48

#define ROTATION     0
#define MADCTL_FIX   0x00
#define SCREEN_W     172
#define SCREEN_H     320

Arduino_DataBus *bus = new Arduino_ESP32SPI(LCD_DC, LCD_CS, LCD_SCK, LCD_MOSI);
Arduino_GFX *gfx = new Arduino_ST7789(
    bus, LCD_RST, ROTATION, true /* IPS */,
    SCREEN_W, SCREEN_H, 34, 0, 34, 0);

#define TOUCH_FLIP_X 0

// =====================================================================
//  UI THEME
// =====================================================================
#define COL_BG        0x0821
#define COL_CARD      0x18C3
#define COL_TEXT      0xFFFF
#define COL_MUTED     0x8410
#define COL_ACCENT    0x067A
#define COL_ACCENT2   0x04F5
#define COL_GOOD      0x2589
#define COL_WARN      0xFD20
#define COL_DANGER    0xF124
#define COL_BTN_DIM   0x3186

enum Screen : uint8_t {
    SCR_HOME = 0, SCR_VITALS, SCR_HRV, SCR_GAIT,
    SCR_LOCATION, SCR_LINK, SCR_POWER, SCR_FALL, SCR_DEVICE,
    SCR_ALERT,             // overlay, not reachable by swiping
    SCR_PAIRING            // overlay, shown while the pairing window is open
};
#define NUM_NAV_SCREENS 9

uint8_t currentScreen   = SCR_HOME;
uint8_t lastDrawnScreen = 255;
bool    forceRedraw     = false;

// Gesture engine ------------------------------------------------------
int  touchStartX = -1, touchStartY = -1, touchLastX = -1, touchLastY = -1;
bool isTouching = false;
unsigned long touchStartTime = 0;
#define SWIPE_THRESHOLD    32     // was 10 - taps were being read as swipes
#define TAP_THRESHOLD      12
#define TAP_MAX_DURATION  400
#define TOUCH_POLL_MS      16     // ~60 Hz; shares the I2C bus with the sensors

// Dismiss button geometry, shared by the drawing and the hit test.
#define BTN_X 10
#define BTN_Y 232
#define BTN_W 152
#define BTN_H 58

// =====================================================================
//  SENSORS AND ALGORITHM STATE
// =====================================================================
MPU6050 accelgyro;
MAX30105 particleSensor;
Adafruit_INA219 ina219;

TSHGVEngine         fallEngine;         // kept for its Stage-1 state machine
CardiacFallValidator cardiacValidator;
FallPredictor       fallPredictor;
PersonalGaitProfile gaitProfile;        // the Stage-2 fix

// ---------------------------------------------------------------------
//  On-band fall model + per-wearer profile (see echowear_band_model.h).
//  The CNN runs on the band's own IMU stream, so a fall is caught with the
//  phone out of range; the profile (alert threshold, resting heart rate) is
//  learned from this wearer only and kept in NVS on this device.
// ---------------------------------------------------------------------
BandFallModel bandModel;
bool          cnn_alert_active = false;     // the alert now showing came from the CNN
unsigned long last_band_save_ms = 0;
#define BAND_SAVE_INTERVAL_MS 900000UL      // 15 minutes
// 1 = judge the post-impact heart-rate rise against this wearer's own resting
// variability; 0 = the fixed TS_HGV_CARDIAC_MIN_HR_DELTA_BPM.
#define ECHOWEAR_PERSONAL_CARDIAC_DELTA 0

static void loadBandProfile() {
    Preferences p;
    if (!p.begin("ewband", true)) return;
    BandFallModel::Blob b;
    size_t n = p.getBytes("prof", &b, sizeof(b));
    p.end();
    if (n == sizeof(b) && bandModel.fromBlob(b)) {
        Serial.printf("BAND | profile restored, threshold %.3f, rest HR %.1f\n",
                      bandModel.threshold(), bandModel.restHr());
    }
}

static void saveBandProfile() {
    BandFallModel::Blob b;
    bandModel.toBlob(b);
    Preferences p;
    if (!p.begin("ewband", false)) return;
    p.putBytes("prof", &b, sizeof(b));
    p.end();
}

EchoWearSession  session;
EchoWearIdentity identity;
EchoWearBLE      ble;
EchoWearGPS      gpsUnit;
EchoWearGSM      gsm;

// Three sharp taps reopen the pairing window without needing the touch UI,
// which is the gesture the headless build also uses. Having both builds share
// it means one instruction works for a carer regardless of which wearable
// they were handed.
#define TAP_GESTURE_THRESHOLD_G  2.0f
#define TAP_GESTURE_WINDOW_MS    2000UL
#define TAP_GESTURE_COUNT        3
uint8_t       tap_count = 0;
unsigned long tap_first_ms = 0;
unsigned long tap_last_ms = 0;

bool have_imu = false, have_ppg = false, have_ina = false;
unsigned long last_sensor_retry_ms = 0;

int16_t ax = 0, ay = 0, az = 0, gx = 0, gy = 0, gz = 0;
float ax_g = 0, ay_g = 0, az_g = 0, gx_dps = 0, gy_dps = 0, gz_dps = 0;

const byte RATE_SIZE = 4;
byte rates[RATE_SIZE] = {0};
byte rateSpot = 0;
long lastBeat = 0;
float beatsPerMinute = 0.0f;
int   beatAvg = 0;

uint8_t current_motion_tier = 0;
float   current_confidence_weight = 1.0f;
float   current_sdnn = 0, current_rmssd = 0, current_pnn50 = 0, current_rsa = 0;
FallState current_fall_state = STATE_NORMAL;
unsigned long fall_state_since_ms = 0;
bool  wearing_check = false;
bool  cardiac_pending = false;

unsigned long last_imu_us = 0;
unsigned long last_ppg_ms = 0;
unsigned long last_touch_ms = 0;
unsigned long last_tlm_ms = 0;
unsigned long last_ui_value_ms = 0;
unsigned long last_diag_ms = 0;
unsigned long last_emergency_sms_ms = 0;

float filtered_ir = 0.0f;
long  irValue = 0, redValue = 0;
float bus_v = 0, current_ma = 0, power_mw = 0;

#define IBI_BUFFER_SIZE 10
float ibi_buffer_raw[IBI_BUFFER_SIZE];
float ibi_buffer_filtered[IBI_BUFFER_SIZE];
int   ibi_index = 0;
bool  ibi_buffer_full = false;

#define IMU_BUFFER_SIZE 20            // 200 ms window for MQG tiering
float ax_buf[IMU_BUFFER_SIZE], ay_buf[IMU_BUFFER_SIZE], az_buf[IMU_BUFFER_SIZE];
int   imu_index = 0;

// 5-second ring for the Stage-2 gait window (500 samples at 100 Hz)
#define GAIT_RING_SIZE GAIT_WINDOW_SAMPLES
float gait_mag_ring[GAIT_RING_SIZE];
float gait_az_ring[GAIT_RING_SIZE];
int   gait_ring_idx = 0;
bool  gait_ring_full = false;
unsigned long last_gait_learn_ms = 0;
GaitFeatures last_gait_features;

// Post-impact Stage-2 evaluation, scheduled rather than run inline so the
// 100 Hz sample loop is never held up by the FFT sweep.
bool          stage2_armed = false;
unsigned long stage2_due_ms = 0;
float         stage2_impact_g = 0.0f;

// BLE IMU batching
EchoWearImuBatch imuBatch;
uint16_t imu_batch_seq = 0;
unsigned long imu_batch_t0_us = 0;

// Alerting
AlertLevel currentAlertLevel = AlertLevel::NONE;
float lastAlertScore = 0.0f;
float lastGaitDeviation = 0.0f;
unsigned long alertRaisedMs = 0;
bool  countdownActive = false;
bool  smsDispatched = false;

// Set when the phone has told us it owns this alert (CTRL_ALERT_TAKEOVER).
// While it holds, the wearable shows its countdown but leaves the SMS to the
// phone, so the caregiver is not texted twice about the same fall. It is
// dropped the moment the link goes away, because a phone that is no longer
// there cannot be relied on to have sent anything.
bool  phoneOwnsAlert = false;
unsigned long phoneTakeoverMs = 0;

#define SIMPLE_IMPACT_THRESHOLD_G 2.5f
#define SIMPLE_IMPACT_COOLDOWN_MS 1000
unsigned long lastImpactMs = 0;

// HRV bar normalisation
#define HRV_SDNN_MAX_MS   100.0f
#define HRV_RMSSD_MAX_MS  100.0f
#define HRV_PNN50_MAX_PCT  50.0f
#define HRV_RSA_MAX         1.0f

#define BATT_FULL_V 4.15f
#define BATT_EMPTY_V 3.30f

// =====================================================================
//  FORWARD DECLARATIONS
// =====================================================================
void drawStatusBar();
void drawNavDots();
void drawScreenStatic(uint8_t s);
void drawScreenDynamic(uint8_t s);
void drawScreenAlert();
void handleTouch();
void handleTap(int x, int y);
void goToScreen(uint8_t s);
void printCentered(const char *txt, int y, uint8_t size, uint16_t color);
void drawCard(int y, int h);
void lcd_reg_init();
void raiseAlert(AlertLevel level, float score, float hr = -1.0f,
                bool rescue = false, int outliers = 0);
void acknowledgeAlert(bool notify_phone = true);
void processControl();
String buildCaregiverMessage(const char *trigger);
void initSensors(bool verbose);
void checkTapGesture(float mag, unsigned long now_ms);
void drawScreenPairing();

// =====================================================================
//  SMALL UI HELPERS
// =====================================================================
void printCentered(const char *txt, int y, uint8_t size, uint16_t color) {
    gfx->setTextSize(size);
    gfx->setTextColor(color);
    int16_t x1, y1; uint16_t w, h;
    gfx->getTextBounds(txt, 0, 0, &x1, &y1, &w, &h);
    gfx->setCursor((SCREEN_W - w) / 2, y);
    gfx->print(txt);
}

void drawCard(int y, int h) { gfx->fillRoundRect(10, y, 152, h, 12, COL_CARD); }

// Repaint one value in place: clear just its box, then print. This is what
// makes live updates possible without a full-screen redraw.
void drawValue(int x, int y, int w, int h, const char *txt, uint8_t size,
               uint16_t color, uint16_t bg = COL_CARD) {
    gfx->fillRect(x, y, w, h, bg);
    gfx->setTextSize(size);
    gfx->setTextColor(color);
    gfx->setCursor(x, y);
    gfx->print(txt);
}

void drawValueCentered(int y, int h, const char *txt, uint8_t size,
                       uint16_t color, uint16_t bg = COL_CARD) {
    gfx->fillRect(12, y, 148, h, bg);
    printCentered(txt, y, size, color);
}

static int pctOf(float val, float maxVal) {
    int p = (int)((val / maxVal) * 100.0f);
    return p < 0 ? 0 : (p > 100 ? 100 : p);
}

static int batteryPercent(float v) {
    if (!have_ina) return -1;
    float p = (v - BATT_EMPTY_V) / (BATT_FULL_V - BATT_EMPTY_V) * 100.0f;
    return (int)(p < 0 ? 0 : (p > 100 ? 100 : p));
}

void goToScreen(uint8_t s) {
    currentScreen = s;
    forceRedraw = true;
}

// =====================================================================
//  ALERTING
// =====================================================================
void raiseAlert(AlertLevel level, float score, float hr, bool rescue, int outliers) {
    // A higher-severity alert may upgrade an active one, but a lower one
    // must not overwrite it.
    if (currentAlertLevel != AlertLevel::NONE && (uint8_t)level < (uint8_t)currentAlertLevel) {
        return;
    }
    currentAlertLevel = level;
    lastAlertScore = score;
    alertRaisedMs = millis();
    smsDispatched = false;
    phoneOwnsAlert = false;            // the phone must claim this one afresh
    phoneTakeoverMs = 0;

    // FALL_PREDICTED is an early warning, not a confirmed event: it shows on
    // screen but never starts the caregiver countdown.
    countdownActive = (level != AlertLevel::FALL_PREDICTED);

    if (countdownActive) gpsUnit.requestEmergencyFix();

    EchoWearAlert pkt = {};
    pkt.type = 2;
    pkt.level = (uint8_t)level;
    pkt.should_rescue = rescue ? 1 : 0;
    pkt.outliers = (uint8_t)outliers;
    pkt.t_ms = alertRaisedMs;
    pkt.score = score;
    pkt.hr_bpm = hr;
    pkt.gait_deviation = lastGaitDeviation;
    pkt.gps_lat = gpsUnit.last_lat;
    pkt.gps_lon = gpsUnit.last_lon;
    ble.notifyAlert(pkt);

    goToScreen(SCR_ALERT);

    const char *name = "NONE";
    switch (level) {
        case AlertLevel::FALL_PREDICTED: name = "FALL_PREDICTED"; break;
        case AlertLevel::FALL_DETECTED:  name = "FALL_DETECTED";  break;
        case AlertLevel::FALL_CONFIRMED: name = "FALL_CONFIRMED"; break;
        case AlertLevel::CARDIAC_FALL:   name = "CARDIAC_FALL";   break;
        case AlertLevel::MANUAL_SOS:     name = "MANUAL_SOS";     break;
        default: break;
    }
    Serial.printf("ALERT,%lu,%s,score=%.3f,hr=%.1f,D=%.2f\n",
                  alertRaisedMs, name, score, hr, lastGaitDeviation);
}

void acknowledgeAlert(bool notify_phone) {
    if (currentAlertLevel == AlertLevel::NONE) return;
    if (cnn_alert_active && currentAlertLevel == AlertLevel::FALL_DETECTED) {
        bandModel.markFalseAlarm();      // cancelled while still a plain CNN detection
        saveBandProfile();
    }
    cnn_alert_active = false;
    Serial.printf("ALERT_ACK,%lu,cancelled by wearer\n", millis());

    // Tell the phone as well. Without this the wearer cancels on the wrist,
    // the wearable falls silent, and the phone carries on counting down and
    // texts the caregiver anyway - which is the worst possible outcome for a
    // cancel button, because the wearer believes they have stopped it.
    if (notify_phone) {
        EchoWearAlert cancel = {};
        cancel.type = 2;
        cancel.level = (uint8_t)AlertLevel::NONE;
        cancel.t_ms = millis();
        ble.notifyAlert(cancel);
    }

    currentAlertLevel = AlertLevel::NONE;
    countdownActive = false;
    smsDispatched = false;
    phoneOwnsAlert = false;
    current_fall_state = STATE_NORMAL;
    fallEngine.resetState();
    cardiac_pending = false;
    cardiacValidator.reset();
    // Back to whichever screen this device belongs on.
    goToScreen(identity.claimed ? SCR_HOME : SCR_PAIRING);
}

void dispatchEmergencySms(const char *trigger) {
    unsigned long now = millis();
    if (last_emergency_sms_ms != 0 &&
        now - last_emergency_sms_ms < EMERGENCY_SMS_COOLDOWN_MS) {
        Serial.println("GSM | emergency SMS suppressed (cooldown)");
        return;
    }
    if (gsm.queue(buildCaregiverMessage(trigger))) {
        last_emergency_sms_ms = now;
        smsDispatched = true;
        Serial.printf("GSM | emergency SMS queued (%s)\n", trigger);
    }
}

String buildCaregiverMessage(const char *trigger) {
    String msg;
    msg.reserve(460);
    msg += "EchoWear EMERGENCY\n";
    // With several wearables in service the caregiver needs to know WHICH one
    // this is before anything else in the message.
    msg += identity.label;
    msg += " (";
    msg += identity.serial;
    msg += ")\n";
    if (identity.patient_ref[0]) {
        msg += "Patient: ";
        msg += identity.patient_ref;
        msg += "\n";
    }
    msg += "Trigger: ";
    msg += trigger;
    msg += "\nFall:"; msg += String((int)current_fall_state);
    msg += " HR:";    msg += String(beatAvg);
    msg += " Motion:";msg += String((int)current_motion_tier);
    msg += "\nSDNN:";  msg += String(current_sdnn, 0);
    msg += " RMSSD:";  msg += String(current_rmssd, 0);
    msg += "\nA:";     msg += String(ax_g, 2); msg += ",";
    msg += String(ay_g, 2); msg += ","; msg += String(az_g, 2);
    if (have_ina) {
        msg += "\nBatt:"; msg += String(bus_v, 2); msg += "V";
    }
    if (gpsUnit.have_fix) {
        msg += "\nGPS:"; msg += String(gpsUnit.last_lat, 6);
        msg += ",";      msg += String(gpsUnit.last_lon, 6);
        msg += " (";     msg += String(gpsUnit.fixAgeSec(millis()));
        msg += "s old)\nMap:https://maps.google.com/?q=";
        msg += String(gpsUnit.last_lat, 6); msg += ",";
        msg += String(gpsUnit.last_lon, 6);
    } else {
        msg += "\nGPS: no fix";
    }
    msg += "\nCheck wearer immediately.";
    return msg;
}

// =====================================================================
//  GESTURES
// =====================================================================
void handleTouch() {
    bsp_touch_read();
    touch_data_t td;
    bool pressed = bsp_touch_get_coordinates(&td);

    if (pressed) {
        int tx = td.coords[0].x;
        int ty = td.coords[0].y;
#if TOUCH_FLIP_X
        tx = SCREEN_W - tx;
#endif
        if (!isTouching) {
            isTouching = true;
            touchStartX = touchLastX = tx;
            touchStartY = touchLastY = ty;
            touchStartTime = millis();
        }
        touchLastX = tx;
        touchLastY = ty;
        return;
    }

    if (!isTouching) return;
    isTouching = false;

    int dx = touchLastX - touchStartX;
    int dy = touchLastY - touchStartY;
    unsigned long dur = millis() - touchStartTime;

    // Swipe: a clear horizontal move that dominates the vertical one.
    if (abs(dx) >= SWIPE_THRESHOLD && abs(dx) > abs(dy) * 2) {
        if (currentScreen == SCR_ALERT) return;      // alert must be dismissed
        currentScreen = (dx < 0)
            ? (currentScreen + 1) % NUM_NAV_SCREENS
            : (currentScreen + NUM_NAV_SCREENS - 1) % NUM_NAV_SCREENS;
        forceRedraw = true;
        return;
    }

    // Tap: small movement and a short press.
    if (abs(dx) < TAP_THRESHOLD && abs(dy) < TAP_THRESHOLD && dur < TAP_MAX_DURATION) {
        handleTap(touchLastX, touchLastY);
    }
}

void handleTap(int x, int y) {
    if (currentScreen == SCR_ALERT) {
        if (x >= BTN_X && x <= BTN_X + BTN_W && y >= BTN_Y && y <= BTN_Y + BTN_H) {
            acknowledgeAlert();
        }
        return;
    }
    if (currentScreen == SCR_PAIRING) {
        if (y >= BTN_Y && y <= BTN_Y + BTN_H) {
            if (x >= PAIR_BTN_L_X && x <= PAIR_BTN_L_X + PAIR_BTN_W) {
                identity.openPairing();        // fresh code, clears a lockout
                ble.refreshPairStatus();
                Serial.printf("PAIR | new code %06lu\n",
                              (unsigned long)identity.pairingCode());
                forceRedraw = true;
            } else if (x >= PAIR_BTN_R_X && x <= PAIR_BTN_R_X + PAIR_BTN_W) {
                // The window is not closed - an unclaimed device stays
                // claimable - this just gets the screen out of the way.
                goToScreen(SCR_HOME);
            }
        }
        return;
    }
    // A long-press-free SOS: tap the bottom band on the Home screen.
    if (currentScreen == SCR_HOME && y > 270) {
        raiseAlert(AlertLevel::MANUAL_SOS, 0.0f);
    }
}

// =====================================================================
//  SCREENS  -  static part (on entry)
// =====================================================================
void drawStatusBar() {
    gfx->fillRect(0, 0, SCREEN_W, 18, COL_BG);
    gfx->setTextSize(1);

    // An unclaimed device has no patient and no data, so WORN/BLE/PQC tell
    // the person holding it nothing they can act on. The pairing code does.
    // Putting it here means it is readable from whatever screen they happen
    // to be on, instead of only from the one screen they have to find first.
    if (!identity.claimed) {
        char pc[24];
        snprintf(pc, sizeof(pc), "PAIR %06lu",
                 (unsigned long)identity.pairingCode());
        gfx->setTextColor(identity.lockedOut() ? COL_DANGER : COL_ACCENT);
        gfx->setCursor(6, 6);
        gfx->print(identity.lockedOut() ? "LOCKED - tap x3" : pc);

        char rb[10];
        int bp = batteryPercent(bus_v);
        if (bp >= 0) snprintf(rb, sizeof(rb), "%d%%", bp);
        else         snprintf(rb, sizeof(rb), "--");
        int16_t bx1, by1; uint16_t bw, bh;
        gfx->getTextBounds(rb, 0, 0, &bx1, &by1, &bw, &bh);
        gfx->setTextColor(COL_MUTED);
        gfx->setCursor(SCREEN_W - 8 - bw, 6);
        gfx->print(rb);
        return;
    }

    gfx->setTextColor(wearing_check ? COL_GOOD : COL_MUTED);
    gfx->setCursor(6, 6);
    gfx->print(wearing_check ? "WORN" : "OFF");

    gfx->setTextColor(ble.connected ? COL_ACCENT : COL_MUTED);
    gfx->setCursor(42, 6);
    gfx->print("BLE");

    gfx->setTextColor(session.state == SessionState::ESTABLISHED ? COL_GOOD : COL_MUTED);
    gfx->setCursor(72, 6);
    gfx->print("PQC");

    char right[10];
    int pct = batteryPercent(bus_v);
    if (pct >= 0) snprintf(right, sizeof(right), "%d%%", pct);
    else          snprintf(right, sizeof(right), "--");
    int16_t x1, y1; uint16_t w, h;
    gfx->getTextBounds(right, 0, 0, &x1, &y1, &w, &h);
    gfx->setTextColor(pct >= 0 && pct < 15 ? COL_DANGER : COL_MUTED);
    gfx->setCursor(SCREEN_W - 8 - w, 6);
    gfx->print(right);
}

void drawNavDots() {
    int startX = (SCREEN_W - (NUM_NAV_SCREENS * 13)) / 2 + 6;
    for (int i = 0; i < NUM_NAV_SCREENS; i++) {
        gfx->fillCircle(startX + i * 13, 310, 3,
                        (i == currentScreen) ? COL_ACCENT : COL_CARD);
    }
}

static void labelAt(int x, int y, const char *t, uint16_t c = COL_MUTED) {
    gfx->setTextSize(1);
    gfx->setTextColor(c);
    gfx->setCursor(x, y);
    gfx->print(t);
}

void drawScreenStatic(uint8_t s) {
    gfx->fillScreen(COL_BG);
    drawStatusBar();
    drawNavDots();

    switch (s) {
        case SCR_HOME:
            drawCard(30, 112);
            labelAt(20, 40, "HEART RATE");
            drawCard(154, 112);
            labelAt(20, 164, "FALL STATE");
            labelAt(20, 218, "MOTION TIER");
            labelAt(28, 282, "tap here to send SOS", COL_BTN_DIM);
            break;

        case SCR_VITALS:
            printCentered("VITALS", 26, 1, COL_MUTED);
            drawCard(44, 116);
            drawCard(172, 120);
            labelAt(20, 182, "IR");
            labelAt(20, 206, "RED");
            labelAt(20, 230, "SIGNAL");
            labelAt(20, 254, "SENSORS");
            break;

        case SCR_HRV:
            printCentered("HRV - LIVE", 26, 1, COL_MUTED);
            drawCard(44, 236);
            break;

        case SCR_GAIT:
            printCentered("GAIT PROFILE", 26, 1, COL_MUTED);
            drawCard(44, 108);
            labelAt(20, 54, "MEAN CADENCE");
            drawCard(164, 126);
            labelAt(20, 174, "Variance");
            labelAt(20, 198, "Stride reg.");
            labelAt(20, 222, "Learned win.");
            labelAt(20, 246, "Last D score");
            break;

        case SCR_LOCATION:
            printCentered("LOCATION", 26, 1, COL_MUTED);
            drawCard(44, 128);
            labelAt(20, 54, "LATITUDE");
            labelAt(20, 104, "LONGITUDE");
            drawCard(184, 106);
            labelAt(20, 194, "Satellites");
            labelAt(20, 218, "Fix age");
            labelAt(20, 242, "Receiver");
            break;

        case SCR_LINK:
            printCentered("LINK & SECURITY", 26, 1, COL_MUTED);
            drawCard(44, 122);
            labelAt(20, 54, "BLUETOOTH");
            labelAt(20, 104, "SESSION");
            drawCard(178, 112);
            labelAt(20, 188, "MTU");
            labelAt(20, 212, "GSM");
            labelAt(20, 236, "Signal");
            break;

        case SCR_POWER:
            printCentered("SYSTEM POWER", 26, 1, COL_MUTED);
            drawCard(44, 104);
            labelAt(20, 54, "BUS VOLTAGE");
            drawCard(158, 130);
            labelAt(20, 170, "Current");
            labelAt(20, 194, "Draw");
            labelAt(20, 218, "Battery");
            labelAt(20, 242, "Uptime");
            break;

        case SCR_FALL:
            printCentered("FALL MONITOR", 26, 1, COL_MUTED);
            drawCard(44, 104);
            drawCard(158, 132);
            labelAt(20, 170, "State code");
            labelAt(20, 194, "Last score");
            labelAt(20, 218, "Gait dev. D");
            labelAt(20, 242, "Profile");
            break;

        case SCR_DEVICE:
            printCentered("THIS DEVICE", 26, 1, COL_MUTED);
            drawCard(44, 76);
            labelAt(20, 54, "SERIAL");
            drawCard(130, 160);
            labelAt(20, 142, "Type");
            labelAt(20, 166, "Assigned");
            labelAt(20, 190, "Patient ref");
            labelAt(20, 214, "Label");
            labelAt(20, 254, "Tap 3x to re-pair", COL_BTN_DIM);
            break;
    }
}

// =====================================================================
//  SCREENS  -  dynamic part (4 Hz, in place)
// =====================================================================
void drawScreenDynamic(uint8_t s) {
    char buf[40];

    switch (s) {
        case SCR_HOME: {
            if (have_ppg && beatAvg > 0) snprintf(buf, sizeof(buf), "%d", beatAvg);
            else                          snprintf(buf, sizeof(buf), "--");
            drawValueCentered(56, 42, buf, 5, wearing_check ? COL_TEXT : COL_MUTED);
            drawValueCentered(104, 12, "bpm", 1, COL_ACCENT);

            bool alerting = current_fall_state != STATE_NORMAL;
            const char *st = alerting ? "ALERT" : "OK";
            drawValue(20, 178, 120, 18, st, 2, alerting ? COL_DANGER : COL_GOOD);
            gfx->fillCircle(142, 186, 6, alerting ? COL_DANGER : COL_GOOD);

            snprintf(buf, sizeof(buf), "%u", current_motion_tier);
            drawValue(20, 232, 100, 18, buf, 2, COL_TEXT);
            break;
        }

        case SCR_VITALS: {
            if (have_ppg && beatAvg > 0) snprintf(buf, sizeof(buf), "%d", beatAvg);
            else                          snprintf(buf, sizeof(buf), "--");
            drawValueCentered(62, 34, buf, 4, COL_ACCENT);
            drawValueCentered(104, 12, "BPM", 1, COL_MUTED);

            snprintf(buf, sizeof(buf), "%ld", irValue);
            drawValue(96, 182, 62, 10, buf, 1, COL_TEXT);
            snprintf(buf, sizeof(buf), "%ld", redValue);
            drawValue(96, 206, 62, 10, buf, 1, COL_TEXT);
            drawValue(96, 230, 62, 10, wearing_check ? "good" : "no finger", 1,
                      wearing_check ? COL_GOOD : COL_WARN);
            snprintf(buf, sizeof(buf), "%c%c%c",
                     have_imu ? 'I' : '-', have_ppg ? 'P' : '-', have_ina ? 'B' : '-');
            drawValue(96, 254, 62, 10, buf, 1, COL_MUTED);
            break;
        }

        case SCR_HRV: {
            struct { const char *n; float v; float max; uint16_t c; const char *unit; }
            m[4] = {
                {"SDNN",  current_sdnn,  HRV_SDNN_MAX_MS,   COL_ACCENT,  "ms"},
                {"RMSSD", current_rmssd, HRV_RMSSD_MAX_MS,  COL_ACCENT,  "ms"},
                {"pNN50", current_pnn50, HRV_PNN50_MAX_PCT, COL_ACCENT2, "%"},
                {"RSA",   current_rsa,   HRV_RSA_MAX,       COL_ACCENT2, ""},
            };
            int y = 60;
            for (int i = 0; i < 4; i++) {
                labelAt(20, y, m[i].n, COL_TEXT);
                if (i == 3) snprintf(buf, sizeof(buf), "%.2f", m[i].v);
                else        snprintf(buf, sizeof(buf), "%d%s", (int)m[i].v, m[i].unit);
                int16_t x1, y1; uint16_t w, h;
                gfx->setTextSize(1);
                gfx->getTextBounds(buf, 0, 0, &x1, &y1, &w, &h);
                gfx->fillRect(80, y, 72, 10, COL_CARD);
                gfx->setTextColor(COL_TEXT);
                gfx->setCursor(152 - w, y);
                gfx->print(buf);

                gfx->fillRoundRect(20, y + 13, 132, 6, 3, COL_BG);
                int fw = (132 * pctOf(m[i].v, m[i].max)) / 100;
                if (fw > 0) gfx->fillRoundRect(20, y + 13, fw, 6, 3, m[i].c);
                y += 56;
            }
            const char *note = !wearing_check ? "no finger detected"
                             : current_motion_tier >= 2 ? "gated: motion too high"
                             : "computing";
            drawValue(20, 288, 140, 10, note, 1,
                      current_motion_tier >= 2 ? COL_WARN : COL_MUTED, COL_BG);
            break;
        }

        case SCR_GAIT: {
            snprintf(buf, sizeof(buf), "%d", (int)gaitProfile.mu[0]);
            drawValue(20, 72, 90, 22, buf, 3, COL_TEXT);
            labelAt(114, 84, "spm", COL_ACCENT);

            snprintf(buf, sizeof(buf), "%.3f", gaitProfile.mu[1]);
            drawValue(100, 174, 58, 10, buf, 1, COL_TEXT);
            snprintf(buf, sizeof(buf), "%.2f", gaitProfile.mu[2]);
            drawValue(100, 198, 58, 10, buf, 1, COL_TEXT);
            snprintf(buf, sizeof(buf), "%lu", (unsigned long)gaitProfile.updates);
            drawValue(100, 222, 58, 10, buf, 1,
                      gaitProfile.isWarm() ? COL_GOOD : COL_WARN);
            snprintf(buf, sizeof(buf), "%.1f", lastGaitDeviation);
            drawValue(100, 246, 58, 10, buf, 1, COL_TEXT);
            break;
        }

        case SCR_LOCATION: {
            if (gpsUnit.have_fix) snprintf(buf, sizeof(buf), "%.5f", gpsUnit.last_lat);
            else                  snprintf(buf, sizeof(buf), "--");
            drawValue(20, 70, 138, 18, buf, 2, COL_TEXT);
            if (gpsUnit.have_fix) snprintf(buf, sizeof(buf), "%.5f", gpsUnit.last_lon);
            else                  snprintf(buf, sizeof(buf), "--");
            drawValue(20, 120, 138, 18, buf, 2, COL_TEXT);

            snprintf(buf, sizeof(buf), "%lu", (unsigned long)gpsUnit.last_sats);
            drawValue(100, 194, 58, 10, buf, 1, COL_TEXT);
            if (gpsUnit.have_fix)
                snprintf(buf, sizeof(buf), "%lus", (unsigned long)gpsUnit.fixAgeSec(millis()));
            else
                snprintf(buf, sizeof(buf), "no fix");
            drawValue(100, 218, 58, 10, buf, 1,
                      gpsUnit.fixIsFresh(millis()) ? COL_GOOD : COL_WARN);
            drawValue(100, 242, 58, 10, gpsUnit.awake ? "searching" : "asleep", 1, COL_MUTED);
            break;
        }

        case SCR_LINK: {
            drawValue(20, 70, 138, 18, ble.connected ? "CONNECTED" : "ADVERTISING",
                      2, ble.connected ? COL_GOOD : COL_MUTED);
            drawValue(20, 120, 138, 18, session.stateName(), 2,
                      session.state == SessionState::ESTABLISHED ? COL_GOOD :
                      session.state == SessionState::FAILED ? COL_DANGER : COL_WARN);

            snprintf(buf, sizeof(buf), "%u", ble.mtu);
            drawValue(100, 188, 58, 10, buf, 1, COL_TEXT);
            drawValue(100, 212, 58, 10, gsm.stateName(), 1,
                      gsm.isReady() ? COL_GOOD : COL_WARN);
            if (gsm.last_csq >= 0 && gsm.last_csq <= 31)
                snprintf(buf, sizeof(buf), "%d/31", gsm.last_csq);
            else
                snprintf(buf, sizeof(buf), "--");
            drawValue(100, 236, 58, 10, buf, 1, COL_TEXT);
            break;
        }

        case SCR_POWER: {
            if (have_ina) snprintf(buf, sizeof(buf), "%.2fV", bus_v);
            else          snprintf(buf, sizeof(buf), "--");
            bool low = have_ina && bus_v < BATT_EMPTY_V + 0.1f;
            drawValue(20, 72, 120, 22, buf, 3, low ? COL_DANGER : COL_GOOD);

            if (have_ina) snprintf(buf, sizeof(buf), "%.0f mA", current_ma);
            else          snprintf(buf, sizeof(buf), "--");
            drawValue(100, 170, 58, 10, buf, 1, COL_TEXT);
            if (have_ina) snprintf(buf, sizeof(buf), "%.0f mW", power_mw);
            else          snprintf(buf, sizeof(buf), "--");
            drawValue(100, 194, 58, 10, buf, 1, COL_TEXT);
            int pct = batteryPercent(bus_v);
            if (pct >= 0) snprintf(buf, sizeof(buf), "%d%%", pct);
            else          snprintf(buf, sizeof(buf), "--");
            drawValue(100, 218, 58, 10, buf, 1, COL_TEXT);
            unsigned long up = millis() / 1000UL;
            snprintf(buf, sizeof(buf), "%luh%02lum", up / 3600UL, (up % 3600UL) / 60UL);
            drawValue(100, 242, 58, 10, buf, 1, COL_MUTED);
            break;
        }

        case SCR_FALL: {
            bool alerting = current_fall_state != STATE_NORMAL;
            gfx->fillCircle(86, 78, 20, alerting ? COL_DANGER : COL_GOOD);
            if (!alerting) {
                gfx->drawLine(77, 78, 83, 86, COL_BG);
                gfx->drawLine(78, 78, 84, 86, COL_BG);
                gfx->drawLine(83, 86, 96, 69, COL_BG);
                gfx->drawLine(84, 86, 97, 69, COL_BG);
            } else {
                gfx->fillRect(84, 68, 4, 13, COL_BG);
                gfx->fillRect(84, 85, 4, 4, COL_BG);
            }
            drawValueCentered(112, 12, alerting ? "Active event" : "Monitoring",
                              1, alerting ? COL_DANGER : COL_TEXT);

            char buf2[24];
            snprintf(buf2, sizeof(buf2), "%d", (int)current_fall_state);
            drawValue(100, 170, 58, 10, buf2, 1, COL_TEXT);
            snprintf(buf2, sizeof(buf2), "%.2f", lastAlertScore);
            drawValue(100, 194, 58, 10, buf2, 1, COL_TEXT);
            snprintf(buf2, sizeof(buf2), "%.1f", lastGaitDeviation);
            drawValue(100, 218, 58, 10, buf2, 1, COL_TEXT);
            drawValue(100, 242, 58, 10,
                      gaitProfile.isWarm() ? "learned" : "learning", 1,
                      gaitProfile.isWarm() ? COL_GOOD : COL_WARN);
            break;
        }

        case SCR_DEVICE: {
            drawValueCentered(72, 18, identity.serial, 2, COL_ACCENT);
            drawValue(100, 142, 58, 10, "Touch LCD", 1, COL_TEXT);
            drawValue(100, 166, 58, 10,
                      identity.claimed ? (identity.authenticated ? "owner here"
                                                                 : "claimed")
                                       : "unassigned",
                      1, identity.claimed ? COL_GOOD : COL_WARN);
            drawValue(100, 190, 58, 10,
                      identity.patient_ref[0] ? identity.patient_ref : "--",
                      1, COL_TEXT);
            drawValue(20, 232, 138, 10, identity.label, 1, COL_MUTED);
            break;
        }
    }
}

// =====================================================================
//  PAIRING SCREEN
// ---------------------------------------------------------------------
//  The code shown here is generated fresh for each pairing window and is
//  never transmitted, so possession of the device is what authorises the
//  claim. The headless build cannot do this and falls back to a code printed
//  on its label - see echowear_identity.h.
// =====================================================================
void drawScreenPairing() {
    gfx->fillScreen(COL_BG);
    printCentered("PAIR THIS DEVICE", 28, 1, COL_ACCENT);
    printCentered(identity.serial, 48, 1, COL_MUTED);

    drawCard(10, 86);
    char code[12];
    snprintf(code, sizeof(code), "%06lu", (unsigned long)identity.pairingCode());
    printCentered(code, 104, 4, identity.lockedOut() ? COL_DANGER : COL_TEXT);

    if (identity.lockedOut()) {
        printCentered("Too many wrong codes.", 170, 1, COL_DANGER);
        printCentered("Press NEW CODE below", 186, 1, COL_MUTED);
        printCentered("to try again.", 202, 1, COL_MUTED);
    } else {
        printCentered("Type this code into the", 170, 1, COL_MUTED);
        printCentered("EchoWear app when it asks.", 186, 1, COL_MUTED);
        printCentered("It stays valid until paired.", 202, 1, COL_MUTED);
    }

    // Two buttons side by side. NEW CODE is the way out of every pairing
    // problem this device can get into - a code that was misread, a lockout
    // after five wrong tries, or a screen that was not looked at in time -
    // so it is on the screen rather than hidden behind a shake gesture.
    gfx->fillRoundRect(PAIR_BTN_L_X, BTN_Y, PAIR_BTN_W, BTN_H, 12, COL_ACCENT2);
    gfx->setTextSize(1);
    gfx->setTextColor(COL_TEXT);
    gfx->setCursor(PAIR_BTN_L_X + 14, BTN_Y + 26);
    gfx->print("NEW CODE");

    gfx->fillRoundRect(PAIR_BTN_R_X, BTN_Y, PAIR_BTN_W, BTN_H, 12, COL_BTN_DIM);
    gfx->setTextColor(COL_MUTED);
    gfx->setCursor(PAIR_BTN_R_X + 26, BTN_Y + 26);
    gfx->print("LATER");
}

void drawPairingCountdown() {
    // Kept as the pairing screen's dynamic half, but there is no longer a
    // deadline to count down: an unclaimed device stays claimable. What
    // changes here is whether a phone is in range to be paired with.
    char buf[32];
    uint16_t remain = identity.pairingRemainingSec();
    if (remain != PAIR_NO_TIMEOUT && remain > 0) {
        snprintf(buf, sizeof(buf), "closes in %us", (unsigned)remain);
    } else if (ble.connected) {
        snprintf(buf, sizeof(buf), "phone connected");
    } else {
        snprintf(buf, sizeof(buf), "waiting for the app");
    }
    gfx->fillRect(12, 224, 148, 12, COL_BG);
    printCentered(buf, 224, 1, ble.connected ? COL_GOOD : COL_MUTED);
}

// =====================================================================
//  ALERT SCREEN
// =====================================================================
void drawScreenAlert() {
    gfx->fillScreen(COL_BG);
    gfx->fillTriangle(86, 24, 66, 60, 106, 60, COL_DANGER);
    gfx->fillRect(84, 36, 4, 12, COL_BG);
    gfx->fillRect(84, 52, 4, 4, COL_BG);

    const char *label = "ALERT";
    switch (currentAlertLevel) {
        case AlertLevel::FALL_PREDICTED: label = "FALL PREDICTED"; break;
        case AlertLevel::FALL_DETECTED:  label = "FALL DETECTED";  break;
        case AlertLevel::FALL_CONFIRMED: label = "FALL CONFIRMED"; break;
        case AlertLevel::CARDIAC_FALL:   label = "CARDIAC FALL";   break;
        case AlertLevel::MANUAL_SOS:     label = "SOS SENT";       break;
        default: break;
    }
    printCentered(label, 76, 1, COL_DANGER);

    char buf[32];
    snprintf(buf, sizeof(buf), "Score %.2f  HR %d", lastAlertScore, beatAvg);
    printCentered(buf, 96, 1, COL_MUTED);

    if (currentAlertLevel == AlertLevel::FALL_PREDICTED) {
        printCentered("early warning only", 118, 1, COL_WARN);
        printCentered("no caregiver alert sent", 132, 1, COL_MUTED);
    }

    gfx->fillRoundRect(BTN_X, BTN_Y, BTN_W, BTN_H, 12, COL_BTN_DIM);
    printCentered("I'm OK", BTN_Y + 22, 2, COL_TEXT);
}

void drawAlertCountdown() {
    if (currentAlertLevel == AlertLevel::NONE) return;
    char buf[40];
    unsigned long elapsed = millis() - alertRaisedMs;

    if (countdownActive && !smsDispatched) {
        long remain = (long)((ALERT_COUNTDOWN_MS - elapsed) / 1000UL);
        if (remain < 0) remain = 0;
        snprintf(buf, sizeof(buf), "%ld", remain);
        gfx->fillRect(12, 148, 148, 46, COL_BG);
        printCentered(buf, 148, 6, remain <= 5 ? COL_DANGER : COL_WARN);
        gfx->fillRect(12, 198, 148, 12, COL_BG);
        printCentered("until caregiver alert", 198, 1, COL_MUTED);
    } else if (smsDispatched) {
        gfx->fillRect(12, 148, 148, 62, COL_BG);
        printCentered("Caregiver alerted", 158, 1, COL_GOOD);
        printCentered(gsm.isBusy() ? "sending SMS..." : "SMS sent", 178, 1, COL_MUTED);
    }
}

// =====================================================================
//  PANEL INIT
// =====================================================================
void lcd_reg_init(void) {
    static const uint8_t init_operations[] = {
        BEGIN_WRITE, WRITE_COMMAND_8, 0x11, END_WRITE, DELAY, 120,
        BEGIN_WRITE, WRITE_C8_D16, 0xDF, 0x98, 0x53, WRITE_C8_D8, 0xB2, 0x23,
        WRITE_COMMAND_8, 0xB7, WRITE_BYTES, 4, 0x00, 0x47, 0x00, 0x6F,
        WRITE_COMMAND_8, 0xBB, WRITE_BYTES, 6, 0x1C, 0x1A, 0x55, 0x73, 0x63, 0xF0,
        WRITE_C8_D16, 0xC0, 0x44, 0xA4, WRITE_C8_D8, 0xC1, 0x16,
        WRITE_COMMAND_8, 0xC3, WRITE_BYTES, 8, 0x7D, 0x07, 0x14, 0x06, 0xCF, 0x71, 0x72, 0x77,
        WRITE_COMMAND_8, 0xC4, WRITE_BYTES, 12, 0x00, 0x00, 0xA0, 0x79, 0x0B, 0x0A, 0x16, 0x79, 0x0B, 0x0A, 0x16, 0x82,
        WRITE_COMMAND_8, 0xC8, WRITE_BYTES, 32, 0x3F, 0x32, 0x29, 0x29, 0x27, 0x2B, 0x27, 0x28, 0x28, 0x26, 0x25, 0x17, 0x12, 0x0D, 0x04, 0x00, 0x3F, 0x32, 0x29, 0x29, 0x27, 0x2B, 0x27, 0x28, 0x28, 0x26, 0x25, 0x17, 0x12, 0x0D, 0x04, 0x00,
        WRITE_COMMAND_8, 0xD0, WRITE_BYTES, 5, 0x04, 0x06, 0x6B, 0x0F, 0x00,
        WRITE_C8_D16, 0xD7, 0x00, 0x30, WRITE_C8_D8, 0xE6, 0x14, WRITE_C8_D8, 0xDE, 0x01,
        WRITE_COMMAND_8, 0xB7, WRITE_BYTES, 5, 0x03, 0x13, 0xEF, 0x35, 0x35,
        WRITE_COMMAND_8, 0xC1, WRITE_BYTES, 3, 0x14, 0x15, 0xC0,
        WRITE_C8_D16, 0xC2, 0x06, 0x3A, WRITE_C8_D16, 0xC4, 0x72, 0x12, WRITE_C8_D8, 0xBE, 0x00, WRITE_C8_D8, 0xDE, 0x02,
        WRITE_COMMAND_8, 0xE5, WRITE_BYTES, 3, 0x00, 0x02, 0x00,
        WRITE_COMMAND_8, 0xE5, WRITE_BYTES, 3, 0x01, 0x02, 0x00,
        WRITE_C8_D8, 0xDE, 0x00, WRITE_C8_D8, 0x35, 0x00, WRITE_C8_D8, 0x3A, 0x05,
        WRITE_COMMAND_8, 0x2A, WRITE_BYTES, 4, 0x00, 0x22, 0x00, 0xCD,
        WRITE_COMMAND_8, 0x2B, WRITE_BYTES, 4, 0x00, 0x00, 0x01, 0x3F,
        WRITE_C8_D8, 0xDE, 0x02,
        WRITE_COMMAND_8, 0xE5, WRITE_BYTES, 3, 0x00, 0x02, 0x00,
        WRITE_C8_D8, 0xDE, 0x00, WRITE_C8_D8, 0x36, 0x00, WRITE_COMMAND_8, 0x21,
        END_WRITE, DELAY, 10,
        BEGIN_WRITE, WRITE_COMMAND_8, 0x29, END_WRITE
    };
    bus->batchOperation(init_operations, sizeof(init_operations));
}

// =====================================================================
//  SENSOR BRING-UP  (retried, never fatal)
// =====================================================================
// Reads the IMU's WHO_AM_I register straight off the bus. Returns false when nothing
// acknowledges at 0x68 (who is then 0xFF).
//
// Why not accelgyro.testConnection(): it only accepts a genuine MPU6050 (register value
// 0x68). The MPU6500 / MPU9250 family reports 0x70 / 0x71 / 0x73, and clones report 0x72 /
// 0x98 and others, yet all of them answer at 0x68 and share the accel/gyro registers used
// here. testConnection() therefore called a working IMU "absent", while the plain
// MPU6050_raw example (which prints the result but reads data regardless) kept working.
static bool probeImu(uint8_t &who) {
    who = 0xFF;
    Wire.beginTransmission(0x68);
    if (Wire.endTransmission() != 0) return false;                  // nothing answered
    Wire.beginTransmission(0x68);
    Wire.write(0x75);                                               // WHO_AM_I
    if (Wire.endTransmission(false) != 0) return false;
    if (Wire.requestFrom((uint8_t)0x68, (uint8_t)1) != 1) return false;
    who = Wire.read();
    return who != 0x00 && who != 0xFF;                              // a floating bus reads 0xFF or 0x00
}

void initSensors(bool verbose) {
    if (!have_imu) {
        uint8_t who;
        bool answers = probeImu(who);
        if (answers) {
            accelgyro.initialize();                                 // wakes the chip from sleep
            accelgyro.setFullScaleAccelRange(MPU6050_ACCEL_FS_16);  // 2048 LSB/g
            accelgyro.setFullScaleGyroRange(MPU6050_GYRO_FS_2000);  // 16.4 LSB/dps
            have_imu = true;
        }
        if (verbose) {
            if (have_imu)
                Serial.printf("MPU6050 | connected, WHO_AM_I 0x%02X%s\n", who,
                              accelgyro.testConnection() ? "" : " (not a genuine MPU6050, accepted)");
            else if (who == 0xFF)
                Serial.println("MPU6050 | NOT FOUND, no answer at 0x68");
            else
                Serial.printf("MPU6050 | NOT FOUND, 0x68 answered but WHO_AM_I read 0x%02X\n", who);
        }
    }
    if (!have_ppg) {
        have_ppg = particleSensor.begin(Wire, I2C_SPEED_FAST);
        if (have_ppg) {
            particleSensor.setup();
            particleSensor.setPulseAmplitudeRed(0x0A);
            particleSensor.setPulseAmplitudeIR(0x24);   // the beat detector uses IR
            particleSensor.setPulseAmplitudeGreen(0);
        }
        if (verbose) Serial.println(have_ppg ? "MAX30105 | initialised"
                                             : "MAX30105 | NOT FOUND");
    }
    if (!have_ina) {
        have_ina = ina219.begin(&Wire);
        if (have_ina) ina219.setCalibration_32V_1A();  // 1A range suits this load
        if (verbose) Serial.println(have_ina ? "INA219 | connected"
                                             : "INA219 | NOT FOUND");
    }
}

// =====================================================================
//  CONTROL COMMANDS FROM THE PHONE
// =====================================================================
void processControl() {
    uint8_t cmd, val;
    if (!ble.takeControl(cmd, val)) return;

    switch (cmd) {
        case CTRL_GSM_ENABLE:
            gsm.enabled = (val != 0);
            Serial.printf("CTRL | GSM %s\n", gsm.enabled ? "enabled" : "disabled");
            break;
        case CTRL_MANUAL_SMS:
            dispatchEmergencySms("Manual request from phone");
            break;
        case CTRL_PQC_START:
            if (session.startKeyExchange()) {
                Serial.printf("CTRL | ML-KEM-512 keypair generated in %lu ms\n",
                              (unsigned long)session.keygen_ms);
            } else {
                Serial.println("CTRL | ML-KEM-512 key generation unavailable");
            }
            ble.refreshStatus();
            break;
        case CTRL_ALERT_ACK:
            // Cancelled from the phone: no need to notify it back.
            acknowledgeAlert(false);
            break;
        case CTRL_ALERT_TAKEOVER:
            if (currentAlertLevel != AlertLevel::NONE) {
                if (val == 2) {
                    // "I have sent it." The wearable must never follow with a
                    // duplicate, so this latches rather than merely deferring.
                    smsDispatched = true;
                    phoneOwnsAlert = true;
                    Serial.println("CTRL | phone reports the caregiver SMS was sent");
                } else {
                    phoneOwnsAlert = (val != 0);
                    phoneTakeoverMs = millis();
                    Serial.printf("CTRL | phone %s this alert\n",
                                  phoneOwnsAlert ? "has taken" : "has released");
                }
            }
            break;
        case CTRL_IMU_STREAM:
            ble.imu_stream_enabled = (val != 0);
            ble.refreshStatus();
            Serial.printf("CTRL | IMU stream %s\n", val ? "on" : "off");
            break;
        case CTRL_GPS_WAKE:
            gpsUnit.requestEmergencyFix();
            break;
        case CTRL_RESET_GAIT:
            gaitProfile = PersonalGaitProfile();
            Serial.println("CTRL | gait profile reset");
            break;
        case CTRL_OPEN_PAIRING:
            identity.openPairing();
            ble.refreshPairStatus();
            if (identity.pairingOpen()) goToScreen(SCR_PAIRING);
            break;
        case CTRL_FACTORY_RESET:
            // handleCtrlWrite() has already refused this from a peer that is
            // not the owner, so reaching here means the claim was proved.
            identity.factoryReset();
            bandModel.reset();
            saveBandProfile();
            gaitProfile = PersonalGaitProfile();
            ble.refreshPairStatus();
            ble.refreshIdentity();
            goToScreen(SCR_PAIRING);
            Serial.println("CTRL | factory reset, device is unclaimed again");
            break;
        default:
            Serial.printf("CTRL | unknown command %u\n", cmd);
            break;
    }
}

// Three sharp impacts inside a 2 s window reopen pairing. It has to be
// deliberate - a single knock while the wearer is walking must not expose the
// device to being claimed by a stranger.
void checkTapGesture(float mag, unsigned long now_ms) {
    if (mag < TAP_GESTURE_THRESHOLD_G) return;
    if (now_ms - tap_last_ms < 120) return;          // debounce one impact
    if (now_ms - tap_first_ms > TAP_GESTURE_WINDOW_MS) {
        tap_count = 0;
        tap_first_ms = now_ms;
    }
    tap_last_ms = now_ms;
    if (++tap_count >= TAP_GESTURE_COUNT) {
        tap_count = 0;
        if (identity.claimed) {
            Serial.println("PAIR | gesture ignored: device is claimed");
        } else {
            identity.openPairing();
            ble.refreshPairStatus();
            goToScreen(SCR_PAIRING);
            Serial.printf("PAIR | window reopened by gesture, code %06lu\n",
                          (unsigned long)identity.pairingCode());
        }
    }
}

// =====================================================================
//  SETUP
// =====================================================================
void setup() {
    Serial.begin(115200);
    delay(600);
    Serial.println("\n=== EchoWear Touch LCD  fw " ECHOWEAR_FW_VERSION " ===");

    // 1. Display
    if (!gfx->begin()) Serial.println("LCD | gfx->begin() failed");
    lcd_reg_init();
    bus->beginWrite(); bus->writeCommand(0x36); bus->write(MADCTL_FIX); bus->endWrite();
    gfx->invertDisplay(false);
    gfx->fillScreen(COL_BG);
    pinMode(LCD_BL, OUTPUT);
    digitalWrite(LCD_BL, HIGH);
    printCentered("EchoWear", 130, 3, COL_ACCENT);
    printCentered("starting...", 165, 1, COL_MUTED);

    // 2. Touch reset before the shared bus comes up
    pinMode(TOUCH_RST, OUTPUT);
    digitalWrite(TOUCH_RST, LOW);  delay(20);
    digitalWrite(TOUCH_RST, HIGH); delay(100);
    pinMode(TOUCH_INT, INPUT_PULLUP);

    // 3. Shared I2C
    Wire.begin(TOUCH_SDA, TOUCH_SCL, 400000);
    Serial.println("I2C | scanning shared bus:");
    for (uint8_t addr = 1; addr < 127; addr++) {
        Wire.beginTransmission(addr);
        if (Wire.endTransmission() == 0) Serial.printf("  found 0x%02X\n", addr);
    }
    bsp_touch_init(&Wire, TOUCH_RST, TOUCH_INT, ROTATION, SCREEN_W, SCREEN_H);
    Serial.println("Touch | initialised");

    initSensors(true);

    // 4. Security, radio, peripherals
    session.begin();
    identity.begin();          // must precede BLE: it supplies the advertised name
    ble.begin(&session, &identity);
    gpsUnit.begin();
    gsm.setCaregiver(CAREGIVER_PHONE);
    gsm.begin();

    loadBandProfile();
    memset(gait_mag_ring, 0, sizeof(gait_mag_ring));
    memset(gait_az_ring, 0, sizeof(gait_az_ring));
    imuBatch.type = 1;
    imuBatch.count = 0;

    // An unclaimed device opens on its pairing screen. There is nothing else
    // worth showing - it has no patient, it is streaming to nobody, and the
    // one thing the person holding it needs is the six-digit code. This is
    // the difference between "where do I find the code" and "the code is the
    // first thing I see".
    if (!identity.claimed) {
        goToScreen(SCR_PAIRING);
        Serial.printf("PAIR | unclaimed - code %06lu shown on screen\n",
                      (unsigned long)identity.pairingCode());
    } else {
        goToScreen(SCR_HOME);
    }
    Serial.println("=== ready ===");
}

// =====================================================================
//  LOOP
// =====================================================================
void loop() {
    unsigned long now_ms = millis();
    unsigned long now_us = micros();

    // --- touch, rate-limited so it does not starve the I2C bus ----------
    if (now_ms - last_touch_ms >= TOUCH_POLL_MS) {
        last_touch_ms = now_ms;
        handleTouch();
    }

    processControl();
    gpsUnit.poll(now_ms);
    gsm.poll(now_ms);

    // --- retry absent sensors every 5 s ---------------------------------
    if ((!have_imu || !have_ppg || !have_ina) && now_ms - last_sensor_retry_ms > 5000) {
        last_sensor_retry_ms = now_ms;
        initSensors(false);
    }

    // =================================================================
    //  1. IMU at 100 Hz: TS-HGV Stage 1, MQG tiering, gait ring, BLE
    // =================================================================
    if (have_imu && now_us - last_imu_us >= 10000UL) {
        last_imu_us = now_us;
        accelgyro.getMotion6(&ax, &ay, &az, &gx, &gy, &gz);

        ax_g = ax / 2048.0f;  ay_g = ay / 2048.0f;  az_g = az / 2048.0f;
        gx_dps = gx / 16.4f;  gy_dps = gy / 16.4f;  gz_dps = gz / 16.4f;

        float mag = sqrtf(ax_g * ax_g + ay_g * ay_g + az_g * az_g);

        checkTapGesture(mag, now_ms);

        // ---- gait ring (5 s) ----
        gait_mag_ring[gait_ring_idx] = mag;
        gait_az_ring[gait_ring_idx] = az_g;
        gait_ring_idx = (gait_ring_idx + 1) % GAIT_RING_SIZE;
        if (gait_ring_idx == 0) gait_ring_full = true;

        // ---- TS-HGV Stage 1 (impact + stillness) via the engine ----
        FallState prev = current_fall_state;
        FallState engine_state = fallEngine.processSample(now_ms, ax_g, ay_g, az_g,
                                                          gx_dps, gy_dps, gz_dps);

        // Latch: a fall state persists for FALL_STATE_HOLD_MS instead of being
        // cleared on the next sample below threshold (the old bug).
        if (engine_state != STATE_NORMAL) {
            current_fall_state = engine_state;
            fall_state_since_ms = now_ms;
        } else if (current_fall_state != STATE_NORMAL &&
                   now_ms - fall_state_since_ms > FALL_STATE_HOLD_MS &&
                   currentAlertLevel == AlertLevel::NONE) {
            current_fall_state = STATE_NORMAL;
        }

        // Stage 1 fired: raise the detected alert, arm the cardiac validator,
        // and schedule Stage 2 for 5 s later once the post-impact window exists.
        if (prev == STATE_NORMAL && engine_state == STATE_IMPACT_DETECTED) {
            if (now_ms - lastImpactMs >= SIMPLE_IMPACT_COOLDOWN_MS) {
                lastImpactMs = now_ms;
                stage2_impact_g = mag;
                cardiacValidator.markImpact(now_us);
                cardiac_pending = true;
                stage2_armed = true;
                stage2_due_ms = now_ms + 5000UL;      // 5 s post-impact window
                raiseAlert(AlertLevel::FALL_DETECTED, mag);
            }
        }

        // ---- MQG motion tier + pre-impact predictor (every 20 samples) ----
        // ---- On-band CNN (second, independent Stage 1) ----
        // Scores the last 2 s of IMU every 0.5 s. It raises the same FALL_DETECTED alert as the
        // rule-based impact detector but is not sent through Stage 2: the network already
        // looks at the whole movement, and the wearer's own threshold keeps it quiet.
        bandModel.feed100Hz(ax_g, ay_g, az_g, gx_dps, gy_dps, gz_dps);
        if (bandModel.consumeAlarm() && current_fall_state == STATE_NORMAL &&
            currentAlertLevel == AlertLevel::NONE && !stage2_armed &&
            now_ms - lastImpactMs >= SIMPLE_IMPACT_COOLDOWN_MS) {
            lastImpactMs = now_ms;
            stage2_impact_g = mag;
            cardiacValidator.markImpact(now_us);
            cardiac_pending = true;
            Serial.printf("BAND,%lu,CNN_FALL,score=%.3f,thr=%.3f,%s\n", now_ms,
                          bandModel.lastAlarmScore(), bandModel.threshold(),
                          bandModel.isPersonalised() ? "personal" : "global");
            raiseAlert(AlertLevel::FALL_DETECTED, bandModel.lastAlarmScore());
            cnn_alert_active = true;
        }
        if (currentAlertLevel == AlertLevel::NONE) cnn_alert_active = false;

        ax_buf[imu_index] = ax_g; ay_buf[imu_index] = ay_g; az_buf[imu_index] = az_g;
        if (++imu_index >= IMU_BUFFER_SIZE) {
            imu_index = 0;
            float rms = calculateImuRMS(ax_buf, ay_buf, az_buf, IMU_BUFFER_SIZE);
            current_motion_tier = getMotionQualityTier(rms, current_confidence_weight);

            if (current_fall_state == STATE_NORMAL &&
                currentAlertLevel == AlertLevel::NONE) {
                float risk = fallPredictor.computeRiskHeuristic(
                    ax_buf, ay_buf, az_buf, IMU_BUFFER_SIZE);
                if (fallPredictor.shouldFireAlert(risk, now_ms)) {
                    raiseAlert(AlertLevel::FALL_PREDICTED, risk);
                }
            }
        }

        // ---- BLE raw IMU stream for the phone's ML pipeline ----
        if (imuBatch.count == 0) imu_batch_t0_us = now_us;
        int i = imuBatch.count;
        imuBatch.ax[i] = (int16_t)constrain(ax_g * 1000.0f, -32768.0f, 32767.0f);
        imuBatch.ay[i] = (int16_t)constrain(ay_g * 1000.0f, -32768.0f, 32767.0f);
        imuBatch.az[i] = (int16_t)constrain(az_g * 1000.0f, -32768.0f, 32767.0f);
        imuBatch.gx[i] = (int16_t)constrain(gx_dps * 10.0f, -32768.0f, 32767.0f);
        imuBatch.gy[i] = (int16_t)constrain(gy_dps * 10.0f, -32768.0f, 32767.0f);
        imuBatch.gz[i] = (int16_t)constrain(gz_dps * 10.0f, -32768.0f, 32767.0f);
        imuBatch.count++;

        if (imuBatch.count >= IMU_BATCH_SAMPLES) {
            imuBatch.seq = imu_batch_seq++;
            imuBatch.t0_us = imu_batch_t0_us;
            imuBatch.dt_us = 10000;
            ble.notifyImu(imuBatch);
            imuBatch.count = 0;
        }
    }

    // =================================================================
    //  2. TS-HGV Stage 2  (deferred, so the FFT never blocks sampling)
    // =================================================================
    if (stage2_armed && now_ms >= stage2_due_ms && gait_ring_full) {
        stage2_armed = false;

        // Unwrap the ring into chronological order for the feature pass.
        static float mag_lin[GAIT_RING_SIZE], az_lin[GAIT_RING_SIZE];
        for (int i = 0; i < GAIT_RING_SIZE; i++) {
            int src = (gait_ring_idx + i) % GAIT_RING_SIZE;
            mag_lin[i] = gait_mag_ring[src];
            az_lin[i]  = gait_az_ring[src];
        }
        GaitFeatures f = computeGaitFeatures(mag_lin, az_lin, GAIT_RING_SIZE);
        int outliers = 0;
        float D = 0.0f;
        bool confirmed = gaitProfile.confirmsFall(f, D, outliers);
        lastGaitDeviation = D;

        Serial.printf("STAGE2,%lu,cad=%.1f,var=%.4f,sri=%.3f,D=%.2f,out=%d,%s\n",
                      now_ms, f.cadence, f.variance, f.sri, D, outliers,
                      confirmed ? "CONFIRMED" : "REJECTED_ADL");

        if (confirmed) {
            current_fall_state = STATE_FALL_CONFIRMED;
            fall_state_since_ms = now_ms;
            raiseAlert(AlertLevel::FALL_CONFIRMED, stage2_impact_g,
                       (float)beatAvg, false, outliers);
        } else {
            // Stage 2 says this was an ADL: stand the whole event down.
            if (currentAlertLevel == AlertLevel::FALL_DETECTED) {
                // Stage 2 stood it down, so the phone must stop counting too.
                acknowledgeAlert(true);
                currentAlertLevel = AlertLevel::NONE;
                countdownActive = false;
                current_fall_state = STATE_NORMAL;
                fallEngine.resetState();
                cardiac_pending = false;
                cardiacValidator.reset();
                goToScreen(SCR_HOME);
            }
        }
    }

    // ---- learn the personal gait baseline from ordinary walking ----
    if (gait_ring_full && current_fall_state == STATE_NORMAL &&
        currentAlertLevel == AlertLevel::NONE &&
        now_ms - last_gait_learn_ms >= 2500UL) {
        last_gait_learn_ms = now_ms;

        static float mag_lin2[GAIT_RING_SIZE], az_lin2[GAIT_RING_SIZE];
        float sumsq = 0.0f;
        for (int i = 0; i < GAIT_RING_SIZE; i++) {
            int src = (gait_ring_idx + i) % GAIT_RING_SIZE;
            mag_lin2[i] = gait_mag_ring[src];
            az_lin2[i]  = gait_az_ring[src];
            float d = mag_lin2[i] - 1.0f;         // remove the 1 g offset
            sumsq += d * d;
        }
        float window_rms = sqrtf(sumsq / GAIT_RING_SIZE);
        last_gait_features = computeGaitFeatures(mag_lin2, az_lin2, GAIT_RING_SIZE);
        gaitProfile.update(last_gait_features, window_rms);
    }

    // =================================================================
    //  3. PPG at 100 Hz  (FIFO is 25-400 Hz; polling flat out starves I2C)
    // =================================================================
    if (have_ppg && now_ms - last_ppg_ms >= 10) {
        last_ppg_ms = now_ms;
        irValue = particleSensor.getIR();
        redValue = particleSensor.getRed();
        filtered_ir = applyIIRFilter((float)irValue, filtered_ir, 0.85f);

        // Hysteresis: a single threshold made the "worn" flag flap on and off
        // at the boundary, resetting the IBI buffer each time.
        const long FINGER_ON = 50000, FINGER_OFF = 35000;
        if (!wearing_check && irValue > FINGER_ON)       wearing_check = true;
        else if (wearing_check && irValue < FINGER_OFF)  wearing_check = false;

        if (!wearing_check) {
            beatsPerMinute = 0.0f;
            beatAvg = 0;
            lastBeat = 0;
            for (byte i = 0; i < RATE_SIZE; i++) rates[i] = 0;
            rateSpot = 0;
            ibi_index = 0;
            ibi_buffer_full = false;
            current_sdnn = current_rmssd = current_pnn50 = current_rsa = 0.0f;
        } else if (checkForBeat(irValue)) {
            unsigned long beatTime = millis();
            if (lastBeat != 0) {
                unsigned long delta = beatTime - lastBeat;
                if (delta >= 270 && delta <= 2000) {       // 30-220 bpm
                    beatsPerMinute = 60000.0f / (float)delta;
                    if (beatsPerMinute >= 30.0f && beatsPerMinute <= 220.0f) {
                        rates[rateSpot] = (byte)beatsPerMinute;
                        rateSpot = (rateSpot + 1) % RATE_SIZE;

                        byte valid = 0; int sum = 0;
                        for (byte x = 0; x < RATE_SIZE; x++) {
                            if (rates[x] > 0) { sum += rates[x]; valid++; }
                        }
                        if (valid > 0) beatAvg = sum / valid;

                        cardiacValidator.recordBeat(beatsPerMinute, micros());
                        bandModel.observeHr(beatsPerMinute, current_motion_tier == 0);

                        ibi_buffer_raw[ibi_index] = (float)delta;
                        if (++ibi_index >= IBI_BUFFER_SIZE) {
                            ibi_index = 0;
                            ibi_buffer_full = true;
                        }
                        if (ibi_buffer_full) {
                            filterEctopicBeats(ibi_buffer_raw, ibi_buffer_filtered,
                                               IBI_BUFFER_SIZE);
                            // MQG-HRV gate: HIGH-motion windows are discarded
                            // rather than reported with a misleading value.
                            if (current_motion_tier < 2) {
                                current_sdnn  = calculateSDNN(ibi_buffer_filtered, IBI_BUFFER_SIZE);
                                current_rmssd = calculateRMSSD(ibi_buffer_filtered, IBI_BUFFER_SIZE);
                                current_pnn50 = calculatePNN50(ibi_buffer_filtered, IBI_BUFFER_SIZE);
                                current_rsa   = calculateRSA(ibi_buffer_filtered, IBI_BUFFER_SIZE);
                            }
                        }
                    }
                }
            }
            lastBeat = beatTime;
        }
    }

    // =================================================================
    //  4. Stage 3 cardiac corroboration
    // =================================================================
    if (cardiac_pending) {
        CardiacAssessment a = cardiacValidator.evaluate(
            now_us, TS_HGV_CARDIAC_POST_WINDOW_S, TS_HGV_CARDIAC_BASELINE_WINDOW_S,
            ECHOWEAR_PERSONAL_CARDIAC_DELTA ? bandModel.cardiacMinDelta(TS_HGV_CARDIAC_MIN_HR_DELTA_BPM)
                                            : TS_HGV_CARDIAC_MIN_HR_DELTA_BPM,
            TS_HGV_CARDIAC_RESCUE_MIN_DELTA_BPM);

        if (a.status == CardiacCorroboration::CORROBORATED) {
            raiseAlert(AlertLevel::CARDIAC_FALL, a.hr_delta_bpm,
                       a.post_impact_hr_bpm, a.should_rescue);
            cardiac_pending = false;
            cardiacValidator.reset();
        } else if (a.status != CardiacCorroboration::NOT_EVALUATED) {
            Serial.printf("CARDIAC,%lu,%s,pre=%.1f,post=%.1f\n", now_ms,
                          cardiacStatusToString(a.status),
                          a.pre_impact_hr_bpm, a.post_impact_hr_bpm);
            cardiac_pending = false;
            cardiacValidator.reset();
        }
    }

    if (now_ms - last_band_save_ms >= BAND_SAVE_INTERVAL_MS) {
        last_band_save_ms = now_ms;
        if (bandModel.learningProgress() > 0.0f) saveBandProfile();
    }

    // =================================================================
    //  5. Countdown -> caregiver SMS
    // =================================================================
    if (countdownActive && !smsDispatched &&
        now_ms - alertRaisedMs >= ALERT_COUNTDOWN_MS) {

        // A takeover only counts while the phone is still connected. If the
        // wearer has walked out of range mid-countdown - which is exactly the
        // situation the on-board SIM exists for - the claim lapses and this
        // device sends the message itself.
        if (phoneOwnsAlert && !ble.connected) {
            phoneOwnsAlert = false;
            Serial.println("ALERT| phone went away mid-countdown, taking the SMS back");
        }

        if (phoneOwnsAlert) {
            // The phone has it. It will ask us explicitly (CTRL_MANUAL_SMS)
            // if its own send fails.
            if (now_ms - phoneTakeoverMs > 60000UL) {
                // ...but not forever. A phone that claimed the alert a minute
                // ago and has neither sent nor asked has failed silently.
                phoneOwnsAlert = false;
                Serial.println("ALERT| phone claimed the alert but never acted");
            }
        } else {
            dispatchEmergencySms(currentAlertLevel == AlertLevel::MANUAL_SOS
                                     ? "Manual SOS" : "Automatic fall alert");
        }
    }

    // =================================================================
    //  6. Power rail + telemetry to the phone (2 Hz)
    // =================================================================
    if (now_ms - last_tlm_ms >= 500) {
        last_tlm_ms = now_ms;

        if (have_ina) {
            bus_v = ina219.getBusVoltage_V();
            current_ma = ina219.getCurrent_mA();
            power_mw = ina219.getPower_mW();
        }

        EchoWearTelemetry t = {};
        t.type = 0;
        t.fall_state = (uint8_t)current_fall_state;
        t.motion_tier = current_motion_tier;
        t.flags = (wearing_check ? 0x01 : 0)
                | (gpsUnit.fixIsFresh(now_ms) ? 0x02 : 0)
                | (gsm.isReady() ? 0x04 : 0)
                | (gaitProfile.isWarm() ? 0x08 : 0)
                | (session.state == SessionState::ESTABLISHED ? 0x10 : 0);
        t.t_ms = now_ms;
        t.ax_g = ax_g; t.ay_g = ay_g; t.az_g = az_g;
        t.gx_dps = gx_dps; t.gy_dps = gy_dps; t.gz_dps = gz_dps;
        t.ir_raw = (uint32_t)irValue;
        t.red_raw = (uint32_t)redValue;
        t.bpm = (float)beatAvg;
        t.sdnn_ms = current_sdnn;  t.rmssd_ms = current_rmssd;
        t.pnn50_pct = current_pnn50; t.rsa_index = current_rsa;
        t.bus_voltage_v = bus_v; t.current_ma = current_ma; t.power_mw = power_mw;
        t.gait_cadence = gaitProfile.mu[0];
        t.gait_variance = gaitProfile.mu[1];
        t.gait_sri = gaitProfile.mu[2];
        t.gps_lat = gpsUnit.last_lat;
        t.gps_lon = gpsUnit.last_lon;
        t.gps_sats = (uint16_t)gpsUnit.last_sats;
        int pct = batteryPercent(bus_v);
        t.battery_pct = (uint16_t)(pct < 0 ? 0 : pct);
        ble.notifyTelemetry(t);
    }

    // =================================================================
    //  7. UI  -  static on entry, values at 4 Hz
    // =================================================================
    // The pairing window closing on its own must take the screen with it,
    // or the device keeps showing a code that no longer works.
    if (currentScreen == SCR_PAIRING && !identity.pairingOpen()) {
        goToScreen(SCR_DEVICE);
    }

    if (currentScreen != lastDrawnScreen || forceRedraw) {
        forceRedraw = false;
        if (currentScreen == SCR_ALERT)        drawScreenAlert();
        else if (currentScreen == SCR_PAIRING) drawScreenPairing();
        else                                   drawScreenStatic(currentScreen);
        lastDrawnScreen = currentScreen;
        last_ui_value_ms = 0;              // force an immediate value paint
    }

    if (now_ms - last_ui_value_ms >= 250) {
        last_ui_value_ms = now_ms;
        if (currentScreen == SCR_ALERT) {
            drawAlertCountdown();
        } else if (currentScreen == SCR_PAIRING) {
            drawPairingCountdown();
        } else {
            drawStatusBar();
            drawScreenDynamic(currentScreen);
        }
    }

    // =================================================================
    //  8. Serial diagnostics (2 s)
    // =================================================================
    if (now_ms - last_diag_ms >= 2000) {
        last_diag_ms = now_ms;
        Serial.printf("IMU | %s Ax=%.2f Ay=%.2f Az=%.2f Tier=%u\n",
                      have_imu ? "ok" : "ABSENT", ax_g, ay_g, az_g, current_motion_tier);
        Serial.printf("HRV | %s Worn=%s BPM=%d SDNN=%.0f RMSSD=%.0f pNN50=%.0f\n",
                      have_ppg ? "ok" : "ABSENT", wearing_check ? "Y" : "N",
                      beatAvg, current_sdnn, current_rmssd, current_pnn50);
        Serial.printf("GAIT| cad=%.1f+-%.1f var=%.4f sri=%.2f n=%lu %s\n",
                      gaitProfile.mu[0], gaitProfile.sigma[0], gaitProfile.mu[1],
                      gaitProfile.mu[2], (unsigned long)gaitProfile.updates,
                      gaitProfile.isWarm() ? "warm" : "learning");
        Serial.printf("LINK| BLE=%s MTU=%u session=%s stream=%s GSM=%s csq=%d tlm sent/gated/sealfail=%lu/%lu/%lu\n",
                      ble.connected ? "up" : "adv", ble.mtu, session.stateName(),
                      identity.mayStream() ? "allowed" : "GATED",
                      gsm.stateName(), gsm.last_csq,
                      (unsigned long)ble.tlm_sent, (unsigned long)ble.tlm_gated,
                      (unsigned long)ble.tlm_seal_fail);
        {
            // Live receiver health, read straight from the NMEA parser so it stays valid
            // while the duty cycle has the driver "asleep". Same facts as the standalone
            // GPS test: satellites, fix or no fix, coordinates, and whether any NMEA
            // arrives at all (chars stays near 0 when the TX/RX wires are wrong).
            TinyGPSPlus &g = gpsUnit.raw();
            uint32_t chars = g.charsProcessed();
            bool fixed = g.location.isValid() && g.location.age() < 5000;
            Serial.printf("GPS | %s sats=%u chars=%lu duty=%s",
                          chars < 10 ? "NO-DATA" : (fixed ? "fix" : "searching"),
                          g.satellites.isValid() ? (unsigned)g.satellites.value() : 0u,
                          (unsigned long)chars, gpsUnit.awake ? "awake" : "asleep");
            if (fixed) Serial.printf(" lat=%.6f lon=%.6f", g.location.lat(), g.location.lng());
            if (chars < 10) Serial.print(" (no NMEA: check GPS TX->pin 4, RX->pin 5)");
            Serial.println();
        }
        Serial.printf("SYS | fall=%d alert=%u batt=%.2fV %.0fmA free=%lu\n",
                      (int)current_fall_state, (unsigned)currentAlertLevel,
                      bus_v, current_ma, (unsigned long)ESP.getFreeHeap());
    }
}
