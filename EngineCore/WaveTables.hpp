#pragma once

// Band-limited single-cycle tables, one per MIDI note (measured: the
// original's saw carries EVERY harmonic below Nyquist at exactly 1/n, alias
// free, and an oscillator whose note index exceeds 127 is silent). Built by
// inverse FFT once per sample rate and shared by every instance.
//
// Shapes (amplitude 1, fundamentals all in phase = sin(2*pi*phase)):
//   saw      falling, +1 at phase 0 (Synth1 >= 1.11)
//   triangle peak +1 at phase 0.25
// Pulse = (saw(p) - saw(p + d)) / 2, sine is computed.

#include <cmath>
#include <complex>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace oxo::s2 {

inline void InverseRealFft(std::vector<std::complex<double>> &a) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = 2 * M_PI / double(len);
        const std::complex<double> wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<double> w(1);
            for (size_t k = 0; k < len / 2; ++k) {
                auto u = a[i + k], v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

struct WaveTable {
    uint32_t size = 0; // power of two
    uint32_t mask = 0;
    std::vector<float> data; // size + 3 guard samples for cubic reads

    // phase in [0, 1); 4-point Hermite interpolation.
    float Read(double phase) const {
        const double pos = phase * size;
        const uint32_t i = uint32_t(pos);
        const float f = float(pos - i);
        const float xm1 = data[(i + size - 1) & mask], x0 = data[i & mask];
        const float x1 = data[(i + 1) & mask], x2 = data[(i + 2) & mask];
        const float c1 = 0.5f * (x1 - xm1);
        const float c2 = xm1 - 2.5f * x0 + 2.0f * x1 - 0.5f * x2;
        const float c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1);
        return ((c3 * f + c2) * f + c1) * f + x0;
    }
};

class WaveTableSet {
public:
    static constexpr int kNotes = 128;

    static std::shared_ptr<const WaveTableSet> ForSampleRate(double sr) {
        static std::mutex mutex;
        static std::vector<std::shared_ptr<const WaveTableSet>> cache;
        std::lock_guard<std::mutex> lock(mutex);
        for (auto &s : cache)
            if (s->sampleRate_ == sr) return s;
        auto set = std::shared_ptr<const WaveTableSet>(new WaveTableSet(sr));
        cache.push_back(set);
        return set;
    }

    const WaveTable &Saw(int note) const { return saw_[note]; }
    const WaveTable &Triangle(int note) const { return tri_[note]; }

private:
    explicit WaveTableSet(double sr) : sampleRate_(sr) {
        for (int n = 0; n < kNotes; ++n) {
            const double f = 440.0 * std::pow(2.0, (n - 69) / 12.0);
            const int harmonics = std::max(1, int(std::ceil(sr / 2 / f)) - 1);
            uint32_t size = 1024;
            while (size < uint32_t(4 * harmonics)) size <<= 1;
            saw_[n] = Build(size, harmonics, [](int h) { return 2.0 / (M_PI * h); });
            tri_[n] = Build(size, harmonics, [](int h) {
                if (!(h & 1)) return 0.0;
                return ((h >> 1) & 1 ? -8.0 : 8.0) / (M_PI * M_PI * h * h);
            });
        }
    }

    // Sine series: x(p) = sum amp(h) * sin(2*pi*h*p).
    template <typename Amp> static WaveTable Build(uint32_t size, int harmonics, Amp amp) {
        std::vector<std::complex<double>> spec(size);
        for (int h = 1; h <= harmonics && h < int(size / 2); ++h) {
            const double a = amp(h);
            spec[h] = std::complex<double>(0, -a / 2);
            spec[size - h] = std::complex<double>(0, a / 2);
        }
        InverseRealFft(spec);
        WaveTable t;
        t.size = size;
        t.mask = size - 1;
        t.data.resize(size + 3);
        for (uint32_t i = 0; i < size + 3; ++i) t.data[i] = float(spec[i % size].real());
        return t;
    }

    double sampleRate_;
    WaveTable saw_[kNotes];
    WaveTable tri_[kNotes];
};

} // namespace oxo::s2
