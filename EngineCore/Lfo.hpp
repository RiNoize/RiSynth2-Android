#pragma once

// Synth1 LFO (measured): global, free running from instance creation (key
// sync resets on the first note of a phrase), bipolar +-1.
// Native types: 0 saw (falling, +1 at phase 0), 1 triangle (-1 at phase 0),
// 2 square (+1 first half), 3 sample & hold, 4 smooth random, 5 sine.
// Speed: Hz = 2^(speed / 12.0367) / 12.

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace oxo::s2 {

class Lfo {
public:
    void Reset() {
        phase_ = 0;
        held_ = 0;
        from_ = 0;
        to_ = Random();
    }
    void ResetPhase() { phase_ = 0; }

    // (measured, measure_lfo_speed.py: square LFO flip times, 0.02 %) speed 0
    // = a 12 s cycle
    static double SpeedHz(int speed) { return std::exp2(speed / 12.0367) / 12.0; }

    // (measured, measure_lfo_sync.py: every knob value) tempo sync: 30 steps,
    // step = floor(speed * 30 / 128), LFO cycle in beats (quarter notes),
    // 16 bars at 0 ... 1/64T at 127 (= the labels on Synth1's own display).
    static constexpr int kSyncSteps = 30;
    static constexpr double kSyncBeats[kSyncSteps] = {
        64, 48, 36, 32, 24, 18, 16, 12, 9, 8, 6, 4, 3.5, 3, 2, 1.75, 1.5, 4.0 / 3, 1, 0.875, 0.75, 2.0 / 3, 0.5,
        0.375, 1.0 / 3, 0.25, 1.0 / 6, 0.125, 1.0 / 12, 1.0 / 24};
    static int SyncStep(int speed) { return std::clamp(speed, 0, 127) * kSyncSteps / 128; }
    static double SyncBeats(int speed) { return kSyncBeats[SyncStep(speed)]; }

    // Fills out[0..n) with the waveform at `hz`.
    void Render(double *out, int n, int type, double hz, double sampleRate) {
        const double inc = hz / sampleRate;
        for (int i = 0; i < n; ++i) {
            switch (type) {
            case 0: out[i] = 1.0 - 2.0 * phase_; break;
            case 1: out[i] = phase_ < 0.5 ? -1.0 + 4.0 * phase_ : 3.0 - 4.0 * phase_; break;
            case 2: out[i] = phase_ < 0.5 ? 1.0 : -1.0; break;
            case 3: out[i] = held_; break;
            case 4: out[i] = from_ + (to_ - from_) * phase_; break;
            default: out[i] = std::sin(2.0 * M_PI * phase_); break;
            }
            phase_ += inc;
            if (phase_ >= 1.0) {
                phase_ -= std::floor(phase_);
                held_ = Random();
                from_ = to_;
                to_ = Random();
            }
        }
    }

private:
    double Random() {
        state_ ^= state_ << 13;
        state_ ^= state_ >> 17;
        state_ ^= state_ << 5;
        return state_ * (2.0 / 4294967296.0) - 1.0;
    }

    double phase_ = 0, held_ = 0, from_ = 0, to_ = 0;
    uint32_t state_ = 0x2545F491u;
};

} // namespace oxo::s2
