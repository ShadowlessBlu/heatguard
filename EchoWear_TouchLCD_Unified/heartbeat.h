#ifndef HEARTBEAT_H
#define HEARTBEAT_H

#include <Arduino.h>
#include <math.h>

#define MQG_LOW_MOTION_G      0.10f  
#define MQG_HIGH_MOTION_G     0.50f  

inline float applyIIRFilter(float current_sample, float previous_filtered, float alpha) {
    return (alpha * current_sample) + ((1.0f - alpha) * previous_filtered);
}

inline float applyDCBlocker(float current_sample, float previous_sample, float previous_filtered, float beta) {
    return beta * (previous_filtered + current_sample - previous_sample);
}

inline float calculateBeta(float cutoff_freq, float sampling_rate) {
    float tau = 1.0f / (2.0f * M_PI * cutoff_freq);
    float t_s = 1.0f / sampling_rate;
    return tau / (tau + t_s);
}

inline float calculateBPM(float ibi_ms) {
    if (ibi_ms <= 0.0f) return 0.0f;
    return 60000.0f / ibi_ms;
}

inline int filterEctopicBeats(const float* ibi_in, float* ibi_out, int length) {
    if (length < 5) {
        for (int i = 0; i < length; i++) ibi_out[i] = ibi_in[i];
        return 0;
    }

    int ectopic_count = 0;
    for (int i = 0; i < length; i++) {
        float temp[5];
        int w_size = 0;
        
        // FIX for std::min / std::max compilation errors
        int start_idx = (i - 2 > 0) ? (i - 2) : 0;
        int end_idx = (i + 2 < length - 1) ? (i + 2) : (length - 1);
        
        for (int j = start_idx; j <= end_idx; j++) {
            temp[w_size++] = ibi_in[j];
        }
        
        for (int a = 0; a < w_size - 1; a++) {
            for (int b = a + 1; b < w_size; b++) {
                if (temp[a] > temp[b]) {
                    float t = temp[a]; temp[a] = temp[b]; temp[b] = t;
                }
            }
        }
        float median = temp[w_size / 2];

        if (fabsf(ibi_in[i] - median) / median > 0.20f) {
            ectopic_count++;
            ibi_out[i] = median; 
        } else {
            ibi_out[i] = ibi_in[i];
        }
    }
    return ectopic_count;
}

inline float calculateSDNN(const float* ibi_array, int length) {
    if (length <= 1) return 0.0f;
    float sum = 0.0f;
    for (int i = 0; i < length; i++) sum += ibi_array[i];
    float mean = sum / (float)length;

    float variance_sum = 0.0f;
    for (int i = 0; i < length; i++) {
        variance_sum += powf(ibi_array[i] - mean, 2.0f);
    }
    return sqrtf(variance_sum / (float)(length - 1));
}

inline float calculateRMSSD(const float* ibi_array, int length) {
    if (length <= 1) return 0.0f;
    float sum_sq_diff = 0.0f;
    for (int i = 0; i < length - 1; i++) {
        float diff = ibi_array[i + 1] - ibi_array[i];
        sum_sq_diff += (diff * diff);
    }
    return sqrtf(sum_sq_diff / (float)(length - 1));
}

inline float calculatePNN50(const float* ibi_array, int length) {
    if (length <= 1) return 0.0f;
    int count_over_50 = 0;
    for (int i = 0; i < length - 1; i++) {
        float diff = fabsf(ibi_array[i + 1] - ibi_array[i]);
        if (diff > 50.0f) count_over_50++;
    }
    return ((float)count_over_50 / (float)(length - 1)) * 100.0f;
}

inline float calculateSDRR(const float* ibi_array, int length) {
    return calculateSDNN(ibi_array, length);
}

inline float calculateRSA(const float* ibi_array, int length) {
    if (length <= 4) return 0.0f;
    float sum_hf_power = 0.0f;
    for (int i = 1; i < length - 1; i++) {
        float d1 = ibi_array[i] - ibi_array[i - 1];
        float d2 = ibi_array[i + 1] - ibi_array[i];
        sum_hf_power += fabsf(d2 - d1);
    }
    return (sum_hf_power / (float)(length - 2)) * 0.5f;
}

inline float calculateImuRMS(const float* ax_array, const float* ay_array, const float* az_array, int length) {
    if (length <= 0) return 0.0f;
    float sum_sq = 0.0f;
    for (int i = 0; i < length; i++) {
        sum_sq += (ax_array[i] * ax_array[i]) + 
                  (ay_array[i] * ay_array[i]) + 
                  (az_array[i] * az_array[i]);
    }
    return sqrtf(sum_sq / (float)length);
}

inline uint8_t getMotionQualityTier(float imu_rms_g, float& out_confidence_weight) {
    float dynamic_acc_g = fabsf(imu_rms_g - 1.0f); 

    if (dynamic_acc_g < MQG_LOW_MOTION_G) {
        out_confidence_weight = 1.0f;
        return 0; 
    } else if (dynamic_acc_g < MQG_HIGH_MOTION_G) {
        out_confidence_weight = 0.6f;
        return 1; 
    } else {
        out_confidence_weight = 0.0f;
        return 2; 
    }
}

#endif