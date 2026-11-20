/*
  =====================================================================
  EchoWear - Headless Wearable  (firmware 2.1.0)
  Board: ESP32-S3 SuperMini, no display
  =====================================================================

  WHAT THIS IS
  ------------
  The same wearable as EchoWear_TouchLCD_Unified, with the screen removed
  and the phone promoted to being the only user interface. It replaces the
  original EchoWear.ino, whose ESP-NOW link to the Cardputer is gone: the
  Android app is now both the receiver and the machine-learning host.

  WIRING IS UNCHANGED from your original EchoWear.ino, deliberately, so an
  existing build does not have to be rewired:

      Wire   SDA 8  / SCL 9    MPU6050, MAX30105
      Wire1  SDA 2  / SCL 3    INA219        (separate bus, as you had it)
      UART1  RX  4  / TX  5    ATGM336H GPS
      UART2  RX  7  / TX  6    SIM800L GSM

  Three optional pins were added for the things a screen used to do. Each
  defaults to -1, which disables it, so the sketch builds and runs on the
  hardware you already have:

      STATUS_LED_PIN    blink patterns: unclaimed / pairing / alert / normal
      BUZZER_PIN        audible alert during the caregiver countdown
      CANCEL_BTN_PIN    press to cancel a false alarm without the phone

  WITHOUT A SCREEN, PAIRING WORKS DIFFERENTLY
  -------------------------------------------
  The touch build shows a random 6-digit code that never leaves the device.
  This one cannot, so its code is derived from the device secret and is
  STATIC - print it on the enclosure label at assembly. That is genuinely
  weaker: anyone who has read the label can claim the device while its
  pairing window is open. The window is the mitigation - it opens for two
  minutes at power-on while unclaimed, and afterwards only on three
  deliberate taps. A claimed device will not open it at all without a
  factory reset. See echowear_identity.h.

  Both the serial number and the pairing code are printed to the serial
  monitor at boot, which is how you read them off a bench unit.

  LIBRARIES
  ---------
    I2Cdev + MPU6050, SparkFun MAX3010x, Adafruit_INA219,
    NimBLE-Arduino (1.x or 2.x), TinyGPS++, PQCMicro
  =====================================================================
*/

#include <Arduino.h>
#include <Wire.h>
#include <cmath>

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

// Identifies this build to the pairing layer: no screen, so the label-code
// path is used instead of a per-session random code.
#define ECHOWEAR_DEVICE_TYPE ECHOWEAR_TYPE_HEADLESS

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

#define ALERT_COUNTDOWN_MS        30000UL
#define FALL_STATE_HOLD_MS         8000UL
#define EMERGENCY_SMS_COOLDOWN_MS 120000UL

// =====================================================================
//  HARDWARE PINS  -  unchanged from the original EchoWear.ino
// =====================================================================
#define PIN_I2C_SDA          8
#define PIN_I2C_SCL          9
#define PIN_I2C_INA219_SDA   2
#define PIN_I2C_INA219_SCL   3

// ---------------------------------------------------------------------
// User-feedback hardware. -1 disables any of these cleanly.
//
// CANCEL_BTN_PIN 0 is the BOOT button that is already on the board: it is
// wired to GND with a pull-up, which is exactly what this code expects, so
// nothing needs soldering. Short press = show the pairing code (and cancel
// a false alarm); five-second hold = factory reset. Do NOT hold it while
// resetting the board - GPIO 0 low at reset is what puts an ESP32 into
// download mode.
//
// STATUS_LED_PIN 48 is where the onboard LED sits on most ESP32-S3
// SuperMini boards. Worth knowing: on many of them that LED is an
// addressable WS2812 rather than a plain one, and a WS2812 ignores
// digitalWrite - so if the blink patterns do nothing, that is why, and the
// fix is either a plain LED on a spare pin or driving 48 with an
// Adafruit_NeoPixel call instead. Everything else works either way; the LED
// is a convenience, not a dependency.
// ---------------------------------------------------------------------
#define STATUS_LED_PIN      48     // onboard LED; see the WS2812 note above
#define BUZZER_PIN          10    // e.g. 10, if you fit one
#define CANCEL_BTN_PIN       0     // onboard BOOT button
#define BUZZER_ACTIVE_HIGH   1

// =====================================================================
//  SENSORS AND ALGORITHM STATE
// =====================================================================
MPU6050 accelgyro;
MAX30105 particleSensor;
Adafruit_INA219 ina219;

TSHGVEngine          fallEngine;
CardiacFallValidator cardiacValidator;
FallPredictor        fallPredictor;
PersonalGaitProfile  gaitProfile;

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
unsigned long last_tlm_ms = 0;
unsigned long last_diag_ms = 0;
unsigned long last_emergency_sms_ms = 0;
unsigned long last_led_ms = 0;
unsigned long last_btn_ms = 0;

float filtered_ir = 0.0f;
long  irValue = 0, redValue = 0;
float bus_v = 0, current_ma = 0, power_mw = 0;

#define IBI_BUFFER_SIZE 10
float ibi_buffer_raw[IBI_BUFFER_SIZE];
float ibi_buffer_filtered[IBI_BUFFER_SIZE];
int   ibi_index = 0;
bool  ibi_buffer_full = false;

#define IMU_BUFFER_SIZE 20
float ax_buf[IMU_BUFFER_SIZE], ay_buf[IMU_BUFFER_SIZE], az_buf[IMU_BUFFER_SIZE];
int   imu_index = 0;

#define GAIT_RING_SIZE GAIT_WINDOW_SAMPLES
float gait_mag_ring[GAIT_RING_SIZE];
float gait_az_ring[GAIT_RING_SIZE];
int   gait_ring_idx = 0;
bool  gait_ring_full = false;
unsigned long last_gait_learn_ms = 0;
GaitFeatures last_gait_features;

bool          stage2_armed = false;
unsigned long stage2_due_ms = 0;
float         stage2_impact_g = 0.0f;

EchoWearImuBatch imuBatch;
uint16_t imu_batch_seq = 0;
unsigned long imu_batch_t0_us = 0;

AlertLevel currentAlertLevel = AlertLevel::NONE;
float lastAlertScore = 0.0f;
float lastGaitDeviation = 0.0f;
unsigned long alertRaisedMs = 0;
bool  countdownActive = false;
bool  smsDispatched = false;

// See the touch build: while the phone has claimed an alert, this device
// shows it and counts down but leaves the SMS to the phone, so the caregiver
// is texted once rather than twice. The claim lapses if the link drops.
bool  phoneOwnsAlert = false;
unsigned long phoneTakeoverMs = 0;

#define SIMPLE_IMPACT_COOLDOWN_MS 1000
unsigned long lastImpactMs = 0;

// Three sharp taps reopen the pairing window. Identical gesture to the touch
// build, so one instruction covers both wearables.
#define TAP_GESTURE_THRESHOLD_G  2.0f
#define TAP_GESTURE_WINDOW_MS    2000UL
#define TAP_GESTURE_COUNT        3
uint8_t       tap_count = 0;
unsigned long tap_first_ms = 0;
unsigned long tap_last_ms = 0;

#define BATT_FULL_V  4.15f
#define BATT_EMPTY_V 3.30f

void raiseAlert(AlertLevel level, float score, float hr = -1.0f,
                bool rescue = false, int outliers = 0);
void acknowledgeAlert(bool notify_phone = true);
void ackFlash();
String buildCaregiverMessage(const char *trigger);

// =====================================================================
//  USER FEEDBACK WITHOUT A SCREEN
// ---------------------------------------------------------------------
//  The LED is the only thing a carer can read off this device, so its
//  patterns have to be distinguishable at a glance rather than merely
//  distinct in code:
//     solid-ish fast blink   an alert is counting down
//     double-blink           pairing window open, waiting to be claimed
//     slow single blink      unclaimed and idle
//     brief flash every 3 s  running normally
// =====================================================================
static int batteryPercent(float v) {
    if (!have_ina) return -1;
    float p = (v - BATT_EMPTY_V) / (BATT_FULL_V - BATT_EMPTY_V) * 100.0f;
    return (int)(p < 0 ? 0 : (p > 100 ? 100 : p));
}

void setBuzzer(bool on) {
#if BUZZER_PIN >= 0
    digitalWrite(BUZZER_PIN, (on == (BUZZER_ACTIVE_HIGH != 0)) ? HIGH : LOW);
#else
    (void)on;
#endif
}

void updateStatusLed(unsigned long now_ms) {
#if STATUS_LED_PIN >= 0
    unsigned long phase;
    bool on;
    if (currentAlertLevel != AlertLevel::NONE) {
        phase = now_ms % 250UL;
        on = phase < 125UL;
    } else if (identity.pairingOpen()) {
        phase = now_ms % 1200UL;
        on = (phase < 120UL) || (phase >= 240UL && phase < 360UL);
    } else if (!identity.claimed) {
        phase = now_ms % 2000UL;
        on = phase < 400UL;
    } else {
        phase = now_ms % 3000UL;
        on = phase < 60UL;
    }
    digitalWrite(STATUS_LED_PIN, on ? HIGH : LOW);
#else
    (void)now_ms;
#endif
}

void pollCancelButton(unsigned long now_ms) {
#if CANCEL_BTN_PIN >= 0
    if (now_ms - last_btn_ms < 40) return;       // debounce
    last_btn_ms = now_ms;
    static bool was_down = false;
    static unsigned long down_since = 0;
    bool down = (digitalRead(CANCEL_BTN_PIN) == LOW);

    if (down && !was_down) {
        down_since = now_ms;
    } else if (!down && was_down) {
        unsigned long held = now_ms - down_since;
        if (held > 5000UL) {
            // A long hold is the way back from a lost phone: without it, a
            // claimed device whose owner is gone is permanently unusable.
            identity.factoryReset();
            bandModel.reset();
            saveBandProfile();
            ble.refreshPairStatus();
            ble.refreshIdentity();
            Serial.println("BTN  | factory reset, device is unclaimed again");
            Serial.printf("BTN  | pairing code %06lu\n",
                          (unsigned long)identity.pairingCode());
        } else if (held > 40UL) {
            if (currentAlertLevel != AlertLevel::NONE) {
                acknowledgeAlert();
            } else if (!identity.claimed) {
                // On a device with no screen the button IS the pairing UI:
                // press it and the code appears in the serial monitor, and
                // the LED gives three quick flashes so you know it took.
                identity.openPairing();
                ble.refreshPairStatus();
                Serial.println("BTN  | ------------------------------");
                Serial.printf("BTN  |  PAIRING CODE:  %06lu\n",
                              (unsigned long)identity.pairingCode());
                Serial.printf("BTN  |  SERIAL:        %s\n", identity.serial);
                Serial.println("BTN  |  type the code into the EchoWear app");
                Serial.println("BTN  | ------------------------------");
                ackFlash();
            } else {
                Serial.printf("BTN  | %s, claimed by patient %s\n",
                              identity.serial,
                              identity.patient_ref[0] ? identity.patient_ref : "?");
            }
        }
    }
    was_down = down;
#else
    (void)now_ms;
#endif
}

// Three quick flashes: "the button press registered". Distinct from every
// status pattern because it is deliberately faster than any of them.
void ackFlash() {
#if STATUS_LED_PIN >= 0
    for (int i = 0; i < 3; i++) {
        digitalWrite(STATUS_LED_PIN, HIGH); delay(60);
        digitalWrite(STATUS_LED_PIN, LOW);  delay(60);
    }
#endif
}

// =====================================================================
//  ALERTING
// =====================================================================
void acknowledgeAlert(bool notify_phone) {
    if (currentAlertLevel == AlertLevel::NONE) return;
    if (cnn_alert_active && currentAlertLevel == AlertLevel::FALL_DETECTED) {
        bandModel.markFalseAlarm();      // cancelled while still a plain CNN detection
        saveBandProfile();
    }
    cnn_alert_active = false;
    Serial.printf("ALERT_ACK,%lu,cancelled\n", millis());

    // Tell the phone too, or the wearer presses the button, the buzzer stops,
    // and the phone texts the caregiver thirty seconds later anyway.
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
    setBuzzer(false);
    current_fall_state = STATE_NORMAL;
    fallEngine.resetState();
    cardiac_pending = false;
    cardiacValidator.reset();
}

void raiseAlert(AlertLevel level, float score, float hr, bool rescue, int outliers) {
    if (currentAlertLevel != AlertLevel::NONE &&
        (uint8_t)level < (uint8_t)currentAlertLevel) {
        return;
    }
    currentAlertLevel = level;
    lastAlertScore = score;
    alertRaisedMs = millis();
    smsDispatched = false;
    phoneOwnsAlert = false;
    phoneTakeoverMs = 0;
    countdownActive = (level != AlertLevel::FALL_PREDICTED);

    if (countdownActive) {
        gpsUnit.requestEmergencyFix();
        setBuzzer(true);
    }

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
    ble.notifyAlert(pkt);                 // stamps device_id

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
//  SENSOR BRING-UP  (retried, never fatal)
// ---------------------------------------------------------------------
//  The original EchoWear.ino did `while(1);` if the MAX30105 was missing,
//  which bricks a fall detector because one optional sensor did not answer.
//  Every device is now optional and retried.
// =====================================================================
void initSensors(bool verbose) {
    if (!have_imu) {
        accelgyro.initialize();
        accelgyro.setFullScaleAccelRange(MPU6050_ACCEL_FS_16);   // 2048 LSB/g
        accelgyro.setFullScaleGyroRange(MPU6050_GYRO_FS_2000);   // 16.4 LSB/dps
        have_imu = accelgyro.testConnection();
        if (verbose) Serial.println(have_imu ? "MPU6050 | connected"
                                             : "MPU6050 | NOT FOUND");
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
        have_ina = ina219.begin(&Wire1);            // separate bus, as wired
        if (have_ina) ina219.setCalibration_32V_1A();
        if (verbose) Serial.println(have_ina ? "INA219 | connected"
                                             : "INA219 | NOT FOUND");
    }
}

// =====================================================================
//  PAIRING GESTURE
// =====================================================================
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
            Serial.printf("PAIR | window reopened by gesture, code %06lu\n",
                          (unsigned long)identity.pairingCode());
        }
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
            acknowledgeAlert(false);     // cancelled from the phone
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
            break;
        case CTRL_FACTORY_RESET:
            identity.factoryReset();
            bandModel.reset();
            saveBandProfile();
            gaitProfile = PersonalGaitProfile();
            ble.refreshPairStatus();
            ble.refreshIdentity();
            Serial.println("CTRL | factory reset, device is unclaimed again");
            break;
        default:
            Serial.printf("CTRL | unknown command %u\n", cmd);
            break;
    }
}

// =====================================================================
//  SETUP
// =====================================================================
void setup() {
    Serial.begin(115200);
    delay(600);
    Serial.println("\n=== EchoWear Headless  fw " ECHOWEAR_FW_VERSION " ===");

#if STATUS_LED_PIN >= 0
    pinMode(STATUS_LED_PIN, OUTPUT);
    digitalWrite(STATUS_LED_PIN, LOW);
#endif
#if BUZZER_PIN >= 0
    pinMode(BUZZER_PIN, OUTPUT);
    setBuzzer(false);
#endif
#if CANCEL_BTN_PIN >= 0
    pinMode(CANCEL_BTN_PIN, INPUT_PULLUP);
#endif

    // Two I2C buses, exactly as the original sketch had them wired.
    Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, 400000);
    Wire1.begin(PIN_I2C_INA219_SDA, PIN_I2C_INA219_SCL, 400000);

    Serial.println("I2C | scanning primary bus:");
    for (uint8_t addr = 1; addr < 127; addr++) {
        Wire.beginTransmission(addr);
        if (Wire.endTransmission() == 0) Serial.printf("  found 0x%02X\n", addr);
    }

    initSensors(true);

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

    Serial.printf("=== ready | serial %s | %s ===\n", identity.serial,
                  identity.claimed ? "claimed" : "UNCLAIMED, pairing open");
}

// =====================================================================
//  LOOP
// =====================================================================
void loop() {
    unsigned long now_ms = millis();
    unsigned long now_us = micros();

    processControl();
    gpsUnit.poll(now_ms);
    gsm.poll(now_ms);
    pollCancelButton(now_ms);
    updateStatusLed(now_ms);

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

        gait_mag_ring[gait_ring_idx] = mag;
        gait_az_ring[gait_ring_idx] = az_g;
        gait_ring_idx = (gait_ring_idx + 1) % GAIT_RING_SIZE;
        if (gait_ring_idx == 0) gait_ring_full = true;

        FallState prev = current_fall_state;
        FallState engine_state = fallEngine.processSample(now_ms, ax_g, ay_g, az_g,
                                                          gx_dps, gy_dps, gz_dps);

        // Latched, not cleared on the next sub-threshold sample.
        if (engine_state != STATE_NORMAL) {
            current_fall_state = engine_state;
            fall_state_since_ms = now_ms;
        } else if (current_fall_state != STATE_NORMAL &&
                   now_ms - fall_state_since_ms > FALL_STATE_HOLD_MS &&
                   currentAlertLevel == AlertLevel::NONE) {
            current_fall_state = STATE_NORMAL;
        }

        if (prev == STATE_NORMAL && engine_state == STATE_IMPACT_DETECTED) {
            if (now_ms - lastImpactMs >= SIMPLE_IMPACT_COOLDOWN_MS) {
                lastImpactMs = now_ms;
                stage2_impact_g = mag;
                cardiacValidator.markImpact(now_us);
                cardiac_pending = true;
                stage2_armed = true;
                stage2_due_ms = now_ms + 5000UL;
                raiseAlert(AlertLevel::FALL_DETECTED, mag);
            }
        }

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
        } else if (currentAlertLevel == AlertLevel::FALL_DETECTED) {
            acknowledgeAlert(true);      // stand the phone's countdown down too
            currentAlertLevel = AlertLevel::NONE;
            countdownActive = false;
            setBuzzer(false);
            current_fall_state = STATE_NORMAL;
            fallEngine.resetState();
            cardiac_pending = false;
            cardiacValidator.reset();
        }
    }

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
            float d = mag_lin2[i] - 1.0f;
            sumsq += d * d;
        }
        float window_rms = sqrtf(sumsq / GAIT_RING_SIZE);
        last_gait_features = computeGaitFeatures(mag_lin2, az_lin2, GAIT_RING_SIZE);
        gaitProfile.update(last_gait_features, window_rms);
    }

    // =================================================================
    //  3. PPG at 100 Hz
    // =================================================================
    if (have_ppg && now_ms - last_ppg_ms >= 10) {
        last_ppg_ms = now_ms;
        irValue = particleSensor.getIR();
        redValue = particleSensor.getRed();
        filtered_ir = applyIIRFilter((float)irValue, filtered_ir, 0.85f);

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
                if (delta >= 270 && delta <= 2000) {
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
    //  4b. While unclaimed, keep saying so
    // =================================================================
    // A serial monitor opened after boot misses the one-shot banner, and on
    // a device with no screen that is the whole of the pairing UI. Repeating
    // it costs one line every twenty seconds.
    static unsigned long last_pair_hint_ms = 0;
    if (!identity.claimed && now_ms - last_pair_hint_ms >= 20000UL) {
        last_pair_hint_ms = now_ms;
        Serial.printf("PAIR | %s unclaimed - code %06lu - open the EchoWear app\n",
                      identity.serial,
                      (unsigned long)identity.pairingCode());
    }

    // =================================================================
    //  5. Countdown -> caregiver SMS
    // =================================================================
    if (countdownActive && !smsDispatched &&
        now_ms - alertRaisedMs >= ALERT_COUNTDOWN_MS) {

        if (phoneOwnsAlert && !ble.connected) {
            phoneOwnsAlert = false;
            Serial.println("ALERT| phone went away mid-countdown, taking the SMS back");
        }
        if (phoneOwnsAlert && now_ms - phoneTakeoverMs > 60000UL) {
            phoneOwnsAlert = false;
            Serial.println("ALERT| phone claimed the alert but never acted");
        }
        // Deliberately not `return`: this sits inside loop(), and bailing
        // out here would silently skip telemetry, the IMU and everything
        // below it for as long as the phone held the alert.
        if (!phoneOwnsAlert) {
            dispatchEmergencySms(currentAlertLevel == AlertLevel::MANUAL_SOS
                                     ? "Manual SOS" : "Automatic fall alert");
            setBuzzer(false);
        }
        // While the phone owns it the buzzer keeps going: the alert is still
        // live and the wearer can still cancel it.
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
        ble.notifyTelemetry(t);            // stamps device_id and claim flags
    }

    // =================================================================
    //  7. Serial diagnostics (2 s)  -  the only console this build has
    // =================================================================
    if (now_ms - last_diag_ms >= 2000) {
        last_diag_ms = now_ms;
        Serial.printf("DEV  | %s %s%s\n", identity.serial,
                      identity.claimed ? "claimed" : "UNCLAIMED",
                      identity.pairingOpen() ? ", pairing open" : "");
        Serial.printf("IMU  | %s Ax=%.2f Ay=%.2f Az=%.2f Tier=%u\n",
                      have_imu ? "ok" : "ABSENT", ax_g, ay_g, az_g, current_motion_tier);
        Serial.printf("HRV  | %s Worn=%s BPM=%d SDNN=%.0f RMSSD=%.0f\n",
                      have_ppg ? "ok" : "ABSENT", wearing_check ? "Y" : "N",
                      beatAvg, current_sdnn, current_rmssd);
        Serial.printf("GAIT | cad=%.1f+-%.1f var=%.4f sri=%.2f n=%lu %s\n",
                      gaitProfile.mu[0], gaitProfile.sigma[0], gaitProfile.mu[1],
                      gaitProfile.mu[2], (unsigned long)gaitProfile.updates,
                      gaitProfile.isWarm() ? "warm" : "learning");
        Serial.printf("LINK | BLE=%s MTU=%u session=%s stream=%s GSM=%s csq=%d tlm sent/gated/sealfail=%lu/%lu/%lu\n",
                      ble.connected ? "up" : "adv", ble.mtu, session.stateName(),
                      identity.mayStream() ? "allowed" : "GATED",
                      gsm.stateName(), gsm.last_csq,
                      (unsigned long)ble.tlm_sent, (unsigned long)ble.tlm_gated,
                      (unsigned long)ble.tlm_seal_fail);
        Serial.printf("SYS  | fall=%d alert=%u batt=%.2fV %.0fmA free=%lu\n",
                      (int)current_fall_state, (unsigned)currentAlertLevel,
                      bus_v, current_ma, (unsigned long)ESP.getFreeHeap());
    }
}
