#ifndef ECHOWEAR_CARDIAC_FALL_VALIDATOR_H_
#define ECHOWEAR_CARDIAC_FALL_VALIDATOR_H_

#include <cstdint>

enum class CardiacCorroboration {
    NOT_EVALUATED,
    CORROBORATED,
    INCONCLUSIVE,
    NOT_CORROBORATED,
};

struct CardiacAssessment {
    CardiacCorroboration status = CardiacCorroboration::NOT_EVALUATED;
    float pre_impact_hr_bpm = -1.0f;
    float post_impact_hr_bpm = -1.0f;
    float hr_delta_bpm = 0.0f;
    bool should_rescue = false;
};

inline const char *cardiacStatusToString(CardiacCorroboration s) {
    switch (s) {
        case CardiacCorroboration::NOT_EVALUATED:   return "NOT_EVALUATED";
        case CardiacCorroboration::CORROBORATED:    return "CORROBORATED";
        case CardiacCorroboration::INCONCLUSIVE:    return "INCONCLUSIVE";
        case CardiacCorroboration::NOT_CORROBORATED:return "NOT_CORROBORATED";
    }
    return "UNKNOWN";
}

class CardiacFallValidator {
public:
    void recordBeat(float instantaneous_bpm, uint32_t timestamp_us) {
        beat_bpm_[beat_head_] = instantaneous_bpm;
        beat_timestamp_us_[beat_head_] = timestamp_us;
        beat_head_ = (beat_head_ + 1) % BEAT_HISTORY_SIZE;
        if (beat_count_ < BEAT_HISTORY_SIZE) beat_count_++;
    }

    void markImpact(uint32_t impact_timestamp_us) {
        impact_timestamp_us_ = impact_timestamp_us;
        impact_marked_ = true;
    }

    // Takes thresholds directly as arguments to keep it decoupled
    CardiacAssessment evaluate(uint32_t now_us, float post_win_s, float base_win_s, float min_delta, float rescue_delta) const {
        CardiacAssessment result{};
        if (!impact_marked_) return result;

        uint32_t post_window_us = (uint32_t)(post_win_s * 1.0e6f);
        if (now_us - impact_timestamp_us_ < post_window_us) {
            return result; 
        }

        uint32_t baseline_window_us = (uint32_t)(base_win_s * 1.0e6f);
        int pre_count = 0, post_count = 0;
        float pre_hr = meanBpmInWindow(impact_timestamp_us_ - baseline_window_us, impact_timestamp_us_, pre_count);
        float post_hr = meanBpmInWindow(impact_timestamp_us_, impact_timestamp_us_ + post_window_us, post_count);

        const int MIN_BEATS_REQUIRED = 3;
        if (pre_count < MIN_BEATS_REQUIRED || post_count < MIN_BEATS_REQUIRED) {
            result.status = CardiacCorroboration::INCONCLUSIVE;
            result.pre_impact_hr_bpm = (pre_count > 0) ? pre_hr : -1.0f;
            result.post_impact_hr_bpm = (post_count > 0) ? post_hr : -1.0f;
            return result;
        }

        result.pre_impact_hr_bpm = pre_hr;
        result.post_impact_hr_bpm = post_hr;
        result.hr_delta_bpm = post_hr - pre_hr;

        if (result.hr_delta_bpm >= min_delta) {
            result.status = CardiacCorroboration::CORROBORATED;
            result.should_rescue = (result.hr_delta_bpm >= rescue_delta);
        } else {
            result.status = CardiacCorroboration::NOT_CORROBORATED;
        }
        return result;
    }

    void reset() {
        impact_marked_ = false;
    }

private:
    static constexpr int BEAT_HISTORY_SIZE = 64;
    float beat_bpm_[BEAT_HISTORY_SIZE] = {0};
    uint32_t beat_timestamp_us_[BEAT_HISTORY_SIZE] = {0};
    int beat_count_ = 0;
    int beat_head_ = 0;

    bool impact_marked_ = false;
    uint32_t impact_timestamp_us_ = 0;

    float meanBpmInWindow(uint32_t start_us, uint32_t end_us, int &count_out) const {
        float sum = 0.0f;
        count_out = 0;
        for (int i = 0; i < beat_count_; ++i) {
            uint32_t ts = beat_timestamp_us_[i];
            if (start_us <= end_us && ts >= start_us && ts < end_us) {
                sum += beat_bpm_[i];
                count_out++;
            }
        }
        return (count_out > 0) ? (sum / count_out) : 0.0f;
    }
};

#endif