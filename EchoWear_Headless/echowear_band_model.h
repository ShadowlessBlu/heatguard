#ifndef ECHOWEAR_BAND_MODEL_H_
#define ECHOWEAR_BAND_MODEL_H_

// ============================================================================
//  On-band fall model + per-wearer personalisation.
//
//  Runs the same CNN as the phone (echowear_fall_cnn.h) on the band's own IMU stream,
//  so a fall is still caught when the phone is out of range, and learns what is NORMAL
//  FOR THIS WEARER:
//
//   * ALERT THRESHOLD. A decayed histogram of the "two-in-a-row" score
//     min(score[t], score[t-1]) over ordinary wear. After ~50 minutes of wear the
//     alert threshold sits just above the top of this wearer's normal range
//     (99.95th percentile + 0.02). It can only be raised above the global threshold,
//     never lowered, and never above FALL_CNN_PERSONAL_CAP, so adaptation cannot
//     silence a real fall.
//   * FALSE-ALARM FEEDBACK. An alert the wearer cancels teaches the band that score was
//     normal for them (markFalseAlarm).
//   * RESTING HEART RATE. Mean and spread of heart rate while still (motion tier 0).
//     cardiacMinDelta() returns how big a post-impact rise has to be, in THIS wearer's
//     own variability, to count as corroboration.
//
//  Everything is stored in NVS on the band (save()/load()); nothing is shared between
//  wearers. Plain C++: builds on a PC for testing (tools/test_band_model.cpp).
// ============================================================================

#include <math.h>
#include <stdint.h>
#include <string.h>
#include "echowear_fall_cnn.h"

#ifndef ECHOWEAR_BAND_CNN_ENABLED
#define ECHOWEAR_BAND_CNN_ENABLED 1
#endif

#define BAND_STRIDE_SAMPLES   25          // 0.5 s at 50 Hz
#define BAND_HIST_BINS        100
#define BAND_WARM_WINDOWS     6000UL      // ~50 min of wear
#define BAND_HALF_LIFE        518400.0f   // windows (~72 h)
#define BAND_TAIL_Q           0.9995f
#define BAND_MARGIN           0.02f
#define BAND_HR_WARM          600         // beats while still
#define BAND_HR_ALPHA         (1.0f / 500.0f)
#define BAND_HR_MIN_SD        2.5f
#define BAND_FA_KEEP          8

class BandFallModel {
public:
    // Call at the IMU rate (100 Hz). Samples are decimated to 50 Hz exactly like the phone
    // does (every second sample), then scored every 0.5 s once 2 s have been collected.
    void feed100Hz(float ax_g, float ay_g, float az_g, float gx_dps, float gy_dps, float gz_dps) {
#if ECHOWEAR_BAND_CNN_ENABLED
        if ((phase_++ & 1) != 0) return;
        float *d = ring_ + head_ * FALL_CNN_CH;
        d[0] = ax_g; d[1] = ay_g; d[2] = az_g; d[3] = gx_dps; d[4] = gy_dps; d[5] = gz_dps;
        head_ = (head_ + 1) % FALL_CNN_WIN;
        if (filled_ < FALL_CNN_WIN) filled_++;
        if (++since_ >= BAND_STRIDE_SAMPLES) {
            since_ = 0;
            if (filled_ == FALL_CNN_WIN) evaluate();
        }
#else
        (void)ax_g; (void)ay_g; (void)az_g; (void)gx_dps; (void)gy_dps; (void)gz_dps;
#endif
    }

    // True once per detected fall. The caller raises the alert.
    bool consumeAlarm() { bool a = alarm_; alarm_ = false; return a; }

    float lastScore() const { return last_; }
    float lastAlarmScore() const { return alarm_score_; }
    bool  isWarm() const { return windows_seen_ >= BAND_WARM_WINDOWS; }
    bool  isPersonalised() const { return isWarm() || fa_n_ > 0; }
    float learningProgress() const {
        float p = (float)windows_seen_ / (float)BAND_WARM_WINDOWS;
        return p > 1.0f ? 1.0f : p;
    }

    // Alert threshold for this wearer (global until the profile is warm).
    float threshold() const {
        float thr = FALL_CNN_THRESHOLD;
        if (isWarm() && hist_total_ > 0.0f) {
            float acc = 0.0f, edge = 1.0f;
            for (int i = 0; i < BAND_HIST_BINS; i++) {
                acc += hist_[i];
                if (acc / hist_total_ >= BAND_TAIL_Q) { edge = (i + 1) / (float)BAND_HIST_BINS; break; }
            }
            float personal = edge + BAND_MARGIN;
            if (personal > thr) thr = personal;
        }
        for (int i = 0; i < fa_n_; i++) {
            if (fa_[i] + BAND_MARGIN > thr) thr = fa_[i] + BAND_MARGIN;
        }
        return thr > FALL_CNN_PERSONAL_CAP ? FALL_CNN_PERSONAL_CAP : thr;
    }

    // The wearer cancelled the alert this model raised: that score was normal for them.
    void markFalseAlarm() {
        if (fa_n_ < BAND_FA_KEEP) fa_[fa_n_++] = alarm_m2_;
        else { memmove(fa_, fa_ + 1, sizeof(float) * (BAND_FA_KEEP - 1)); fa_[BAND_FA_KEEP - 1] = alarm_m2_; }
    }

    // ---- heart rate ----------------------------------------------------------------
    // bpm: instantaneous beat rate; still: motion tier 0
    void observeHr(float bpm, bool still) {
        if (!still || bpm < 38.0f || bpm > 140.0f) return;
        if (hr_n_ == 0) { hr_mean_ = bpm; hr_var_ = 0.0f; }
        else {
            float d = bpm - hr_mean_;
            hr_mean_ += BAND_HR_ALPHA * d;
            hr_var_ = (1.0f - BAND_HR_ALPHA) * (hr_var_ + BAND_HR_ALPHA * d * d);
        }
        if (hr_n_ < 0x7FFFFFFF) hr_n_++;
    }
    bool  hrIsWarm() const { return hr_n_ >= BAND_HR_WARM; }
    float restHr() const { return hr_mean_; }
    float restHrSd() const { float s = sqrtf(hr_var_); return s < BAND_HR_MIN_SD ? BAND_HR_MIN_SD : s; }

    // Minimum post-impact heart-rate rise (bpm) that counts as corroboration for THIS wearer:
    // 2.5 of their own standard deviations, kept between 6 and 15 bpm. Before the baseline is
    // learned this is the fixed default.
    float cardiacMinDelta(float fallback) const {
        if (!hrIsWarm()) return fallback;
        float d = 2.5f * restHrSd();
        return d < 6.0f ? 6.0f : (d > 15.0f ? 15.0f : d);
    }

    void reset() { *this = BandFallModel(); }

    // ---- persistence (NVS blob) ---------------------------------------------------------
    struct Blob {
        uint32_t magic;
        uint32_t seen;
        float    total;
        float    hist[BAND_HIST_BINS];
        float    fa[BAND_FA_KEEP];
        int32_t  fa_n;
        float    hr_mean, hr_var;
        int32_t  hr_n;
    };
    static const uint32_t BLOB_MAGIC = 0xEC0F0001u;
    void toBlob(Blob &b) const {
        b.magic = BLOB_MAGIC; b.seen = (uint32_t)windows_seen_; b.total = hist_total_;
        memcpy(b.hist, hist_, sizeof(hist_)); memcpy(b.fa, fa_, sizeof(fa_)); b.fa_n = fa_n_;
        b.hr_mean = hr_mean_; b.hr_var = hr_var_; b.hr_n = hr_n_;
    }
    bool fromBlob(const Blob &b) {
        if (b.magic != BLOB_MAGIC || b.fa_n < 0 || b.fa_n > BAND_FA_KEEP) return false;
        windows_seen_ = b.seen; hist_total_ = b.total;
        memcpy(hist_, b.hist, sizeof(hist_)); memcpy(fa_, b.fa, sizeof(fa_)); fa_n_ = b.fa_n;
        hr_mean_ = b.hr_mean; hr_var_ = b.hr_var; hr_n_ = b.hr_n;
        return true;
    }

private:
    void evaluate() {
        static float win[FALL_CNN_WIN * FALL_CNN_CH];
        for (int i = 0; i < FALL_CNN_WIN; i++) {
            const float *s = ring_ + ((head_ + i) % FALL_CNN_WIN) * FALL_CNN_CH;
            float *d = win + i * FALL_CNN_CH;
            for (int c = 0; c < FALL_CNN_CH; c++) d[c] = s[c];
        }
        float s = echowear_fall_cnn_score(win);
        float thr = threshold();
        run_ = (s >= thr) ? run_ + 1 : 0;
        bool fall = run_ >= FALL_CNN_VOTE;
        if (fall) {
            refractory_ = FALL_CNN_REFRACT;
            run_ = 0;
            alarm_ = true;
            alarm_score_ = s;
            alarm_m2_ = s < prev_ ? s : prev_;
        }
        // learn only from ordinary wear: never from an alert or the seconds after one
        observeScore(s, !fall && refractory_ == 0);
        if (refractory_ > 0 && !fall) refractory_--;
        prev_ = s;
        last_ = s;
    }

    void observeScore(float s, bool include) {
        float m2 = s < prev_ ? s : prev_;
        if (!include) return;
        windows_seen_++;
        const float decay = 1.0f - 0.6931472f / BAND_HALF_LIFE;
        for (int i = 0; i < BAND_HIST_BINS; i++) hist_[i] *= decay;
        hist_total_ *= decay;
        int bin = (int)(m2 * BAND_HIST_BINS);
        if (bin < 0) bin = 0;
        if (bin > BAND_HIST_BINS - 1) bin = BAND_HIST_BINS - 1;
        hist_[bin] += 1.0f;
        hist_total_ += 1.0f;
    }

    float ring_[FALL_CNN_WIN * FALL_CNN_CH] = {0};
    int   head_ = 0, filled_ = 0, since_ = 0;
    uint32_t phase_ = 0;
    float prev_ = 0.0f, last_ = 0.0f;
    int   run_ = 0, refractory_ = 0;
    bool  alarm_ = false;
    float alarm_score_ = 0.0f, alarm_m2_ = 0.0f;

    float    hist_[BAND_HIST_BINS] = {0};
    float    hist_total_ = 0.0f;
    uint32_t windows_seen_ = 0;
    float    fa_[BAND_FA_KEEP] = {0};
    int      fa_n_ = 0;
    float    hr_mean_ = 0.0f, hr_var_ = 0.0f;
    int32_t  hr_n_ = 0;
};

#endif
