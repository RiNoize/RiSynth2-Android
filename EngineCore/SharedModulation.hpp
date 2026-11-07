#pragma once

#include "Params.hpp"
#include <algorithm>
#include <array>
#include <cmath>

namespace oxo::s2 {

// Original summation order and expressions, shared by Voice's fallback and
// Synth's global control rendering. This never reduces the control rate.
struct LfoRoute {
    const double *v[2] = {nullptr, nullptr};
    int depth[2] = {0, 0};
    int n = 0;
    explicit operator bool() const { return n > 0; }
    template <typename F> double Sum(int i, F f) const {
        double s = 0;
        for (int k = 0; k < n; ++k) s += depth[k] / 127.0 * f(v[k][i]);
        return s;
    }
    double Signed(int i) const { return Sum(i, [](double x) { return x; }); }
    double Unipolar(int i) const { return Sum(i, [](double x) { return 0.5 * (x + 1.0); }); }
};

// All these LFO terms are global to the instrument, not to a unison voice.
// In particular the pan pow() expressions need run once per sample, not 32
// times. Per-note envelopes, filter state, keyboard tracking and unison pan
// remain independent. Fixed storage, render-thread ownership, no allocation.
struct SharedModulation {
    static constexpr int kFrames = 64;
    std::array<double, kFrames> cutoff{}, width{}, fm{}, amplitude{}, panL{}, panR{};
    bool hasCutoff = false, hasWidth = false, hasFm = false, hasAmplitude = false, hasPan = false;

    void Prepare(const double *const lfo[2], const PatchValues &p, int frames) {
        LfoRoute r[8];
        for (int k = 0; k < 2; ++k) {
            if (!lfo[k]) continue;
            auto &route = r[std::clamp<int>(p[k ? kLfo2Dest : kLfo1Dest], 1, 7)];
            route.v[route.n] = lfo[k];
            route.depth[route.n++] = p[k ? kLfo2Depth : kLfo1Depth];
        }
        hasCutoff = bool(r[3]); hasWidth = bool(r[5]); hasFm = bool(r[6]);
        hasAmplitude = bool(r[4]); hasPan = bool(r[7]);
        for (int i = 0; i < frames; ++i) {
            if (hasCutoff) cutoff[i] = std::min(1.0, r[3].Unipolar(i));
            if (hasWidth) width[i] = r[5].Signed(i);
            if (hasFm) fm[i] = r[6].Unipolar(i);
            if (hasAmplitude) {
                double gain = 1.0;
                for (int k = 0; k < r[4].n; ++k)
                    gain *= 1.0 - r[4].depth[k] / 127.0 * 0.5 * (1.0 - r[4].v[k][i]);
                amplitude[i] = gain;
            }
            if (hasPan) {
                const double s = r[7].Signed(i);
                const double q = s >= 0 ? 1.0 - std::pow(1.0 - s, 3.0) : std::pow(1.0 + s, 3.0) - 1.0;
                panL[i] = q >= 0 ? 1.0 + 0.5 * q : 1.0 + q;
                panR[i] = q >= 0 ? 1.0 - q : 1.0 - 0.5 * q;
            }
        }
    }
};
} // namespace oxo::s2
