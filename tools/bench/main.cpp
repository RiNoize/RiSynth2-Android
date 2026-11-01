// bench: engine CPU cost. 16 held voices x 10 s per scenario, prints the
// realtime factor (seconds of audio per second of CPU, one core).
//   clang++ -std=c++20 -O2 tools/bench/main.cpp -o build/bench && ./build/bench
#include "../../EngineCore/Synth.hpp"

#include <chrono>
#include <cstdio>
#include <vector>

using namespace oxo::s2;

static double Run(const char *name, PatchValues p) {
    Synth s;
    s.SetSampleRate(44100);
    p[kPolyphony] = 16;
    s.SetPatch(p);
    for (int k = 0; k < 16; ++k) s.NoteOn(40 + k * 2, 100);
    std::vector<float> l(256), r(256);
    const int blocks = 44100 * 10 / 256;
    const auto t0 = std::chrono::steady_clock::now();
    for (int b = 0; b < blocks; ++b) s.Render(l.data(), r.data(), 256);
    const double cpu = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("  %-28s x%.0f realtime\n", name, 10.0 / cpu);
    return cpu;
}

int main() {
    PatchValues base = DefaultValues();
    base[kFilterAttack] = 40, base[kFilterDecay] = 80, base[kFilterSustain] = 30, base[kFilterAmount] = 100;
    auto with = [&](std::initializer_list<std::pair<int, int>> kv) {
        PatchValues p = base;
        for (auto [k, v] : kv) p[k] = v;
        return p;
    };
    Run("init patch (LP24?)", DefaultValues());
    Run("LP12 + filter env", with({{kFilterType, 0}}));
    Run("LP24 + filter env", with({{kFilterType, 1}}));
    Run("LP24 + env + LFO cutoff", with({{kFilterType, 1}, {kLfo1On, 1}, {kLfo1Dest, 3}, {kLfo1Depth, 60}}));
    Run("detune + sub + LP12", with({{kFilterType, 0}, {kOsc1Detune, 40}, {kSubGain, 60}}));
    Run("LPDL + filter env", with({{kFilterType, 4}, {kFilterRes, 100}}));
    Run("all fx on", with({{kEffectOn, 1}, {kDelayOn, 1}, {kChorusOn, 1}, {kEqLevel, 90}}));
}
