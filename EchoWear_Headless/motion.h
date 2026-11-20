#ifndef MOTION_H
#define MOTION_H

#include <Arduino.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// TS-HGV FALL DETECTION & GAIT PARAMETERS
#define TSHGV_IMPACT_THRESHOLD_G    2.50f  
#define TSHGV_STILLNESS_RMS_G       0.15f  
#define TSHGV_GAIT_SIGMA_MULT       2.00f  
#define TSHGV_WINDOW_SAMPLES        500    
#define TSHGV_GAIT_EMA_LAMBDA       0.95f  

inline float calculateVectorMagnitude(float ax_g, float ay_g, float az_g) {
    return sqrtf((ax_g * ax_g) + (ay_g * ay_g) + (az_g * az_g));
}

inline float calculatePitch(float ax_g, float ay_g, float az_g) {
    return atan2f(ax_g, sqrtf((ay_g * ay_g) + (az_g * az_g))) * (180.0f / M_PI);
}

inline float calculateRoll(float ax_g, float ay_g, float az_g) {
    return atan2f(ay_g, sqrtf((ax_g * ax_g) + (az_g * az_g))) * (180.0f / M_PI);
}

inline float calculateWindowRMS(const float* a_buf, int length) {
    if (length <= 0) return 0.0f;
    float sum_sq = 0.0f;
    for (int i = 0; i < length; i++) {
        sum_sq += (a_buf[i] * a_buf[i]);
    }
    return sqrtf(sum_sq / (float)length);
}

struct HistoricalGaitProfile {
    float mu_cadence = 100.0f;
    float sigma_cadence = 12.0f;
    float mu_variance = 0.08f;
    float sigma_variance = 0.02f;
    float mu_sri = 0.75f;
    float sigma_sri = 0.10f;
};

inline float calculateAccelerationVariance(const float* a_buf, int length) {
    if (length <= 1) return 0.0f;
    float sum = 0.0f;
    for (int i = 0; i < length; i++) sum += a_buf[i];
    float mean = sum / (float)length;

    float var_sum = 0.0f;
    for (int i = 0; i < length; i++) {
        float diff = a_buf[i] - mean;
        var_sum += (diff * diff);
    }
    return var_sum / (float)(length - 1);
}

inline float calculateSRI(const float* a_buf, int length, int stride_lag) {
    if (length <= stride_lag || stride_lag <= 0) return 0.0f;
    
    float sum = 0.0f;
    for (int i = 0; i < length; i++) sum += a_buf[i];
    float mean = sum / (float)length;

    float num = 0.0f;
    float den = 0.0f;
    for (int i = 0; i < length - stride_lag; i++) {
        num += (a_buf[i] - mean) * (a_buf[i + stride_lag] - mean);
    }
    for (int i = 0; i < length; i++) {
        den += powf(a_buf[i] - mean, 2.0f);
    }

    if (den == 0.0f) return 0.0f;
    return num / den;
}

inline float calculateStepCadence(const float* vertical_acc_buf, int length, float sample_rate_hz) {
    if (length <= 2) return 0.0f;
    
    int peak_count = 0;
    for (int i = 1; i < length - 1; i++) {
        if (vertical_acc_buf[i] > vertical_acc_buf[i - 1] && 
            vertical_acc_buf[i] > vertical_acc_buf[i + 1] && 
            vertical_acc_buf[i] > 0.2f) { 
            peak_count++;
        }
    }
    float duration_sec = (float)length / sample_rate_hz;
    if (duration_sec <= 0) return 0.0f;
    return ((float)peak_count / duration_sec) * 60.0f;
}

inline float calculateGaitDeviationScore(float f_cadence, float f_variance, float f_sri,
                                        const HistoricalGaitProfile& profile,
                                        int& outlier_count) {
    float dev_cadence = fabsf(f_cadence - profile.mu_cadence) / profile.sigma_cadence;
    float dev_variance = fabsf(f_variance - profile.mu_variance) / profile.sigma_variance;
    float dev_sri = fabsf(f_sri - profile.mu_sri) / profile.sigma_sri;

    outlier_count = 0;
    if (dev_cadence > TSHGV_GAIT_SIGMA_MULT) outlier_count++;
    if (dev_variance > TSHGV_GAIT_SIGMA_MULT) outlier_count++;
    if (dev_sri > TSHGV_GAIT_SIGMA_MULT) outlier_count++;

    return dev_cadence + dev_variance + dev_sri;
}

enum FallState {
    STATE_NORMAL = 0,
    STATE_IMPACT_DETECTED = 1,
    STATE_STILLNESS_VERIFIED = 2,
    STATE_FALL_CONFIRMED = 3
};

class TSHGVEngine {
public: // <-- Just add this public variable so Echowear.ino can read it
int last_outliers = 0;

private:
    HistoricalGaitProfile profile;
    FallState current_state = STATE_NORMAL;
    
    float ring_a[TSHGV_WINDOW_SAMPLES];
    float ring_az[TSHGV_WINDOW_SAMPLES];
    int ring_idx = 0;
    bool ring_full = false;

    uint8_t impact_samples_count = 0;
    unsigned long impact_time_ms = 0;
    unsigned long stillness_start_ms = 0;

public:
    TSHGVEngine() {
        memset(ring_a, 0, sizeof(ring_a));
        memset(ring_az, 0, sizeof(ring_az));
    }

    FallState processSample(unsigned long now_ms, float ax_g, float ay_g, float az_g,
                            float gx_dps, float gy_dps, float gz_dps) {
        
        float mag_g = calculateVectorMagnitude(ax_g, ay_g, az_g);
        float pitch_deg = calculatePitch(ax_g, ay_g, az_g);
        float roll_deg = calculateRoll(ax_g, ay_g, az_g);

        ring_a[ring_idx] = mag_g;
        ring_az[ring_idx] = az_g; 
        ring_idx = (ring_idx + 1) % TSHGV_WINDOW_SAMPLES;
        if (ring_idx == 0) ring_full = true;

        if (mag_g >= TSHGV_IMPACT_THRESHOLD_G) {
            impact_samples_count++;
            if (impact_samples_count >= 2 && current_state == STATE_NORMAL) {
                current_state = STATE_IMPACT_DETECTED;
                impact_time_ms = now_ms;
                stillness_start_ms = 0;
                Serial.printf("STAGE1_IMPACT,%lu,%.4g,%.4g,%.4g\n", now_ms, mag_g, pitch_deg, roll_deg);
            }
        } else {
            if (current_state == STATE_NORMAL) impact_samples_count = 0;
        }

        if (current_state == STATE_IMPACT_DETECTED) {
            if (now_ms - impact_time_ms <= 3000) {
                float current_rms = calculateWindowRMS(ring_a, 100);
                if (current_rms < TSHGV_STILLNESS_RMS_G) {
                    if (stillness_start_ms == 0) stillness_start_ms = now_ms;
                    if (now_ms - stillness_start_ms >= 1000) {
                        current_state = STATE_STILLNESS_VERIFIED;
                        Serial.printf("STAGE1_STILLNESS,%lu,%.4g\n", now_ms, current_rms);
                    }
                } else {
                    stillness_start_ms = 0; 
                }
            } else {
                current_state = STATE_NORMAL;
                impact_samples_count = 0;
                Serial.printf("STAGE1_REJECT_ADL,%lu,Timeout_No_Stillness\n", now_ms);
            }
        }

        if (current_state == STATE_STILLNESS_VERIFIED && ring_full) {
            float f_cadence = calculateStepCadence(ring_az, TSHGV_WINDOW_SAMPLES, 100.0f);
            float f_variance = calculateAccelerationVariance(ring_a, TSHGV_WINDOW_SAMPLES);
            float f_sri = calculateSRI(ring_a, TSHGV_WINDOW_SAMPLES, 50); 

            int outliers = 0;
            float dev_score = calculateGaitDeviationScore(f_cadence, f_variance, f_sri, profile, outliers);
            last_outliers = outliers;

            Serial.printf("STAGE2_EVAL,%lu,%.4g,%.4g,%.4g,%.4g,%d\n",
                          now_ms, f_cadence, f_variance, f_sri, dev_score, outliers);

            if (outliers >= 2) {
                current_state = STATE_FALL_CONFIRMED;
                Serial.printf("TSHGV_FALL_CONFIRMED,%lu,Impact_g:%.4g,DevScore:%.4g\n", now_ms, mag_g, dev_score);
            } else {
                current_state = STATE_NORMAL;
                impact_samples_count = 0;
                Serial.printf("TSHGV_FALL_REJECTED_ADL,%lu,DevScore:%.4g\n", now_ms, dev_score);
            }
        }

        Serial.printf("KINEMATICS,%lu,%.4g,%.4g,%.4g,%.4g,%.4g,%.4g,%.4g,%.4g,%.4g,%d\n",
                      now_ms, ax_g, ay_g, az_g, gx_dps, gy_dps, gz_dps, 
                      mag_g, pitch_deg, roll_deg, (int)current_state);

        return current_state;
    }

    void resetState() {
        current_state = STATE_NORMAL;
        impact_samples_count = 0;
    }

    HistoricalGaitProfile getProfile() const { return profile; }
};

#endif