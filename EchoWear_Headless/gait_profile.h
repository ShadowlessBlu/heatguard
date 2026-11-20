#ifndef ECHOWEAR_GAIT_PROFILE_H_
#define ECHOWEAR_GAIT_PROFILE_H_

#include <Arduino.h>
#include <math.h>

// ============================================================================
//  TS-HGV STAGE 2  -  personalised gait profile  (REPLACES the static
//  HistoricalGaitProfile defaults in motion.h)
// ----------------------------------------------------------------------------
//  Why this file exists
//  --------------------
//  The shipped motion.h did two things that make Stage 2 unusable in practice,
//  both confirmed by offline evaluation on the HIFD wrist dataset (21 subjects,
//  104 falls, 245 ADLs):
//
//   1. The gait profile was a hard-coded struct (mu_cadence = 100, sigma = 12,
//      ...) that was never updated from the wearer. Every wearer was therefore
//      compared against the same fictional baseline.
//
//   2. calculateStepCadence() counted local peaks above 0.2 g and
//      calculateSRI() took the autocorrelation at a FIXED lag of 50 samples.
//      Neither matches the algorithm the EchoWear papers describe (FFT peak in
//      the 0.5-4 Hz band; autocorrelation at the DOMINANT stride lag), and both
//      are far less discriminative. With the old features, Stage 2 rejected
//      94% of genuine falls (sensitivity fell from 89.4% to 5.8%). With the
//      features below it keeps them: 88.5% sensitivity at 6.5% FPR.
//
//  The deviation score is the paper's:
//
//      D = SUM_i |f_i - mu_i| / sigma_i      i in {cadence, variance, SRI}
//
//  and a fall is confirmed when D exceeds GAIT_DEVIATION_THRESHOLD. The
//  per-subject thresholds selected by leave-one-subject-out on HIFD had a
//  median of 6.0, which is the default here.
// ============================================================================

#define GAIT_SAMPLE_RATE_HZ        100.0f
#define GAIT_WINDOW_SAMPLES        500      // 5 s at 100 Hz
#define GAIT_EMA_LAMBDA            0.95f    // ~20 min effective memory
#define GAIT_DEVIATION_THRESHOLD   6.0f     // LOSO median on HIFD
#define GAIT_MIN_UPDATES           12       // profile is "warm" after this many
#define GAIT_CADENCE_LO_HZ         0.5f
#define GAIT_CADENCE_HI_HZ         4.0f
#define GAIT_STRIDE_LO_S           0.6f
#define GAIT_STRIDE_HI_S           1.6f

// A window only updates the profile when it looks like ordinary ambulation:
// enough movement to be walking, not so much that it is an impact.
#define GAIT_LEARN_RMS_MIN_G       0.12f
#define GAIT_LEARN_RMS_MAX_G       1.20f

// ---------------------------------------------------------------------------
//  F1 - step cadence from the FFT peak of vertical acceleration (0.5-4 Hz).
//  A full FFT is unnecessary for a single band peak, so this uses a Goertzel
//  sweep: O(N) per candidate frequency, no buffers, no FFT library.
// ---------------------------------------------------------------------------
inline float gaitCadenceFFT(const float *vert, int length,
                            float fs = GAIT_SAMPLE_RATE_HZ) {
    if (length < 16) return 0.0f;

    float mean = 0.0f;
    for (int i = 0; i < length; i++) mean += vert[i];
    mean /= (float)length;

    const int    STEPS   = 72;                       // ~0.05 Hz (3 spm) resolution
    const float  df      = (GAIT_CADENCE_HI_HZ - GAIT_CADENCE_LO_HZ) / (STEPS - 1);
    float best_power = 0.0f;
    float best_freq  = 0.0f;

    for (int k = 0; k < STEPS; k++) {
        float f = GAIT_CADENCE_LO_HZ + k * df;
        float w = 2.0f * (float)M_PI * f / fs;
        float coeff = 2.0f * cosf(w);
        float s0 = 0.0f, s1 = 0.0f, s2 = 0.0f;

        for (int i = 0; i < length; i++) {
            // Hann window suppresses the spectral leakage that would otherwise
            // let the DC/low-frequency shoulder dominate the band.
            float win = 0.5f * (1.0f - cosf(2.0f * (float)M_PI * i / (length - 1)));
            s0 = (vert[i] - mean) * win + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        float power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        if (power > best_power) {
            best_power = power;
            best_freq  = f;
        }
    }
    return best_freq * 60.0f;          // steps per minute
}

// ---------------------------------------------------------------------------
//  F2 - acceleration variance (unchanged in spirit from motion.h)
// ---------------------------------------------------------------------------
inline float gaitVariance(const float *a, int length) {
    if (length <= 1) return 0.0f;
    float mean = 0.0f;
    for (int i = 0; i < length; i++) mean += a[i];
    mean /= (float)length;

    float acc = 0.0f;
    for (int i = 0; i < length; i++) {
        float d = a[i] - mean;
        acc += d * d;
    }
    return acc / (float)(length - 1);
}

// ---------------------------------------------------------------------------
//  F3 - stride regularity at the DOMINANT lag, searched over a plausible
//  stride period rather than pinned to one hard-coded lag.
// ---------------------------------------------------------------------------
inline float gaitStrideRegularity(const float *a, int length,
                                  float fs = GAIT_SAMPLE_RATE_HZ) {
    int lag_lo = (int)(GAIT_STRIDE_LO_S * fs);
    int lag_hi = (int)(GAIT_STRIDE_HI_S * fs);
    if (lag_hi > length - 2) lag_hi = length - 2;
    if (lag_hi <= lag_lo) return 0.0f;

    float mean = 0.0f;
    for (int i = 0; i < length; i++) mean += a[i];
    mean /= (float)length;

    float den = 0.0f;
    for (int i = 0; i < length; i++) {
        float d = a[i] - mean;
        den += d * d;
    }
    if (den <= 0.0f) return 0.0f;

    float best = 0.0f;
    // Step of 2 samples: stride autocorrelation is smooth, so half the work
    // costs nothing in accuracy and keeps this inside the 100 Hz budget.
    for (int lag = lag_lo; lag <= lag_hi; lag += 2) {
        float num = 0.0f;
        for (int i = 0; i + lag < length; i++) {
            num += (a[i] - mean) * (a[i + lag] - mean);
        }
        float r = num / den;
        if (r > best) best = r;
    }
    return best;
}

// ---------------------------------------------------------------------------
//  Online personalised profile
// ---------------------------------------------------------------------------
struct GaitFeatures {
    float cadence = 0.0f;
    float variance = 0.0f;
    float sri = 0.0f;
};

class PersonalGaitProfile {
public:
    // Running mean / standard deviation per feature, updated by EMA.
    float mu[3]    = {100.0f, 0.08f, 0.35f};   // seeded, then learned
    float sigma[3] = {20.0f,  0.04f, 0.15f};
    uint32_t updates = 0;

    bool isWarm() const { return updates >= GAIT_MIN_UPDATES; }

    // Feed an ordinary (non-impact) ambulatory window.
    void update(const GaitFeatures &f, float window_rms_g) {
        if (window_rms_g < GAIT_LEARN_RMS_MIN_G ||
            window_rms_g > GAIT_LEARN_RMS_MAX_G) {
            return;                      // too still, or an impact: not gait
        }
        const float v[3] = {f.cadence, f.variance, f.sri};
        const float lam  = GAIT_EMA_LAMBDA;

        for (int i = 0; i < 3; i++) {
            float prev_mu = mu[i];
            mu[i] = lam * mu[i] + (1.0f - lam) * v[i];
            // EMA of the absolute deviation, scaled to an equivalent sigma
            // (1.2533 = sqrt(pi/2), the MAD-to-sigma factor for a normal).
            float dev = fabsf(v[i] - prev_mu);
            sigma[i] = lam * sigma[i] + (1.0f - lam) * dev * 1.2533f;
        }
        // Floors stop a very regular walker from producing a near-zero sigma
        // that would make every post-impact window look like a fall.
        if (sigma[0] < 4.0f)    sigma[0] = 4.0f;
        if (sigma[1] < 0.01f)   sigma[1] = 0.01f;
        if (sigma[2] < 0.03f)   sigma[2] = 0.03f;

        if (updates < 0xFFFFFFFF) updates++;
    }

    // Stage 2 score for a post-impact window.
    float deviationScore(const GaitFeatures &f, int &outliers_out,
                         float tau = 2.0f) const {
        const float v[3] = {f.cadence, f.variance, f.sri};
        float total = 0.0f;
        outliers_out = 0;
        for (int i = 0; i < 3; i++) {
            float d = fabsf(v[i] - mu[i]) / sigma[i];
            total += d;
            if (d > tau) outliers_out++;
        }
        return total;
    }

    // Until the profile is warm the device must not silently swallow falls, so
    // an unverified Stage 2 accepts the Stage 1 candidate (fail-safe).
    bool confirmsFall(const GaitFeatures &f, float &score_out,
                      int &outliers_out) const {
        score_out = deviationScore(f, outliers_out);
        if (!isWarm()) return true;
        return score_out > GAIT_DEVIATION_THRESHOLD;
    }
};

// Convenience: compute all three features over a window.
inline GaitFeatures computeGaitFeatures(const float *a_mag, const float *az,
                                        int length,
                                        float fs = GAIT_SAMPLE_RATE_HZ) {
    GaitFeatures f;
    f.cadence  = gaitCadenceFFT(az, length, fs);
    f.variance = gaitVariance(a_mag, length);
    f.sri      = gaitStrideRegularity(a_mag, length, fs);
    return f;
}

#endif  // ECHOWEAR_GAIT_PROFILE_H_
