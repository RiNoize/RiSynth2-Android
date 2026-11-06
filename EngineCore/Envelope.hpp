#pragma once

// ADSR as measured on the original's amp envelope: LINEAR attack (time =
// kAmpAttackSeconds, 0.5 ms * 2^(v/8.05)), then EXPONENTIAL decay toward the
// sustain level and exponential release toward 0, both with the same
// time-constant law (kEnvTauSeconds, tau doubles every ~10.1 steps).

#include "MeasuredTables.hpp"

#include <algorithm>
#include <cmath>

namespace oxo::s2 {

class Adsr {
public:
    enum class Stage { Idle, Attack, Decay, Release };

    // Knob values 0..127. (measured) the amp env's sustain follows a curve
    // (kAmpSustainLevel), the filter env's is linear S / 127.
    enum class SustainLaw { Amp, Linear };
    void Configure(double sampleRate, int attack, int decay, int sustain, int release,
                   SustainLaw law = SustainLaw::Amp) {
        // Coefficients depend on settings, not the envelope's current stage.
        // Preserve the measured expressions; avoid exp() on every voice chunk.
        if (sampleRate == configuredRate_ && attack == configuredAttack_ && decay == configuredDecay_ &&
            sustain == configuredSustain_ && release == configuredRelease_ && law == configuredLaw_) return;
        configuredRate_ = sampleRate;
        configuredAttack_ = attack; configuredDecay_ = decay;
        configuredSustain_ = sustain; configuredRelease_ = release; configuredLaw_ = law;
        attackStep_ = 1.0 / std::max(1.0, tables::kAmpAttackSeconds[attack] * sampleRate);
        decayCoef_ = 1.0 - std::exp(-1.0 / (tables::kEnvTauSeconds[decay] * sampleRate));
        releaseCoef_ = 1.0 - std::exp(-1.0 / (tables::kEnvTauSeconds[release] * sampleRate));
        sustain_ = law == SustainLaw::Amp ? tables::kAmpSustainLevel[sustain] : sustain / 127.0;
    }

    void Trigger() { stage_ = Stage::Attack; }
    void ReleaseNote() {
        if (stage_ != Stage::Idle) stage_ = Stage::Release;
    }
    void Reset() {
        stage_ = Stage::Idle;
        level_ = 0;
    }

    bool Active() const { return stage_ != Stage::Idle; }
    bool Releasing() const { return stage_ == Stage::Release; }
    bool Attacking() const { return stage_ == Stage::Attack; }
    double Level() const { return level_; }

    double Next() {
        switch (stage_) {
        case Stage::Idle: break;
        case Stage::Attack:
            level_ += attackStep_;
            if (level_ >= 1.0) {
                level_ = 1.0;
                stage_ = Stage::Decay;
            }
            break;
        // (measured, measure_env_tail.py) decay / release aim kAim BELOW
        // their target and stop on it: they land in finite time (a decay
        // to sustain 0 = silent but still held; a release = the end)
        case Stage::Decay:
            level_ += (sustain_ - kAim - level_) * decayCoef_;
            if (level_ <= sustain_) level_ = sustain_;
            break;
        case Stage::Release:
            level_ += (-kAim - level_) * releaseCoef_;
            if (level_ <= 0) Reset();
            break;
        }
        return level_;
    }

private:
    static constexpr double kAim = 1.0 / 2048;

    double configuredRate_ = -1;
    int configuredAttack_ = -1, configuredDecay_ = -1, configuredSustain_ = -1, configuredRelease_ = -1;
    SustainLaw configuredLaw_ = SustainLaw::Amp;
    Stage stage_ = Stage::Idle;
    double level_ = 0;
    double attackStep_ = 1, decayCoef_ = 1, releaseCoef_ = 1, sustain_ = 1;
};

} // namespace oxo::s2
