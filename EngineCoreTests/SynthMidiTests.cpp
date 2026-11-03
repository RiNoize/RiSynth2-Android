#include "TestFramework.hpp"

#include "../EngineCore/Synth.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

using namespace oxo::s2;

namespace {
Synth RoutedToCutoff(int base, int sens) {
    Synth s;
    s.SetParam(kFilterFreq, base);
    s.SetParam(kCtrlSrc1, 0xB001);
    s.SetParam(kCtrlAssign1, kFilterFreq);
    s.SetParam(kWheelSpeedSens, sens);
    s.SetParam(kCtrlSrc2, 0xB002);
    s.SetParam(kCtrlAssign2, kFilterFreq);
    s.SetParam(kWheelAmountSens, 64);
    return s;
}

void Run(Synth &s, int frames) {
    std::vector<float> l(static_cast<size_t>(frames)), r(static_cast<size_t>(frames));
    s.Render(l.data(), r.data(), frames);
}
} // namespace

// Points of reference/meta/wheel.json (measured on the original).
OXO_TEST(CtrlRoutingMatchesMeasuredLaw) {
    struct P { int base, sens, cc, offset; };
    const P points[] = {{64, 65, 32, 1},  {64, 65, 16, 0},   {64, 66, 16, 1},   {64, 80, 127, 33},
                        {64, 96, 64, 33}, {64, 112, 48, 37}, {64, 63, 127, 0},  {64, 62, 32, -1},
                        {64, 60, 127, -6}, {64, 48, 64, -15}, {64, 16, 63, -47}, {64, 0, 127, -64},
                        {40, 127, 64, 65}, {40, 32, 63, -31}, {40, 1, 8, -8}};
    for (const P &p : points) {
        Synth s = RoutedToCutoff(p.base, p.sens);
        s.ControlChange(1, p.cc);
        OXO_EXPECT_EQ(s.EffectiveParam(kFilterFreq) - p.base, p.offset);
        OXO_EXPECT_EQ(s.Param(kFilterFreq), p.base); // the patch itself never moves
    }
}

OXO_TEST(CtrlRoutingFollowsPatchEdits) {
    Synth s = RoutedToCutoff(64, 80);
    s.ControlChange(1, 127);
    s.SetParam(kFilterFreq, 50);
    OXO_EXPECT_EQ(s.EffectiveParam(kFilterFreq), 50 + 33);
    s.SetParam(kCtrlAssign1, kFilterRes); // re-routing restores the old target
    OXO_EXPECT_EQ(s.EffectiveParam(kFilterFreq), 50);
    OXO_EXPECT_EQ(s.EffectiveParam(kFilterRes), s.Param(kFilterRes) + 33 > 127 ? 127 : s.Param(kFilterRes) + 33);
    s.ControlChange(1, 0);
    OXO_EXPECT_EQ(s.EffectiveParam(kFilterRes), s.Param(kFilterRes));
}

OXO_TEST(DefaultPatchWheelDrivesLfo1) {
    Synth s; // init patch: CC1 -> lfo1 depth / speed, sens 74
    const int depth = s.Param(kLfo1Depth), speed = s.Param(kLfo1Speed);
    s.ControlChange(1, 127);
    OXO_EXPECT_EQ(s.EffectiveParam(kLfo1Depth), depth + 20);
    OXO_EXPECT_EQ(s.EffectiveParam(kLfo1Speed), speed + 20);
}

OXO_TEST(SustainPedalHoldsReleasedKeys) {
    Synth s;
    s.NoteOn(60, 100);
    s.ControlChange(64, 127);
    s.NoteOff(60);
    s.NoteOn(64, 100);
    s.NoteOff(64);
    Run(s, 256);
    OXO_EXPECT_EQ(s.GatedVoices(), 2);
    s.NoteOn(67, 100); // still held when the pedal comes up: keeps sounding
    s.ControlChange(64, 0);
    OXO_EXPECT_EQ(s.GatedVoices(), 1);
    s.NoteOff(67);
    OXO_EXPECT_EQ(s.GatedVoices(), 0);
}

OXO_TEST(SustainPedalReleasesOnAllNotesOff) {
    Synth s;
    s.ControlChange(64, 127);
    s.NoteOn(60, 100);
    s.NoteOff(60);
    s.ControlChange(123, 0);
    s.ControlChange(64, 0);
    OXO_EXPECT_EQ(s.GatedVoices(), 0);
}

// Synth1's voice squares: each note takes the NEXT idle voice, not voice 1 again.
OXO_TEST(VoicesRoundRobin) {
    Synth s;
    s.SetSampleRate(48000);
    s.SetParam(kAmpRelease, 0);
    const auto voiceOf = [&](uint64_t states) {
        for (int i = 0; i < kMaxVoices; ++i)
            if ((states >> (2 * i) & 3) == Synth::kVoiceHeld) return i;
        return -1;
    };
    for (int n = 0; n < 3; ++n) {
        s.NoteOn(60, 100);
        Run(s, 64);
        OXO_EXPECT_EQ(voiceOf(s.VoiceStates()), n);
        s.NoteOff(60);
        Run(s, 4800); // release over: the voice is idle again
        OXO_EXPECT_EQ(s.VoiceStates(), uint64_t(0));
    }
}

// (measured) with osc phase 0 the osc1 detune copies of a unison stack start
// at unrelated phases: 8 voices add ~8x the power of one (coherent copies:
// ~58x, the old bug).
OXO_TEST(UnisonDetuneCopiesAreIncoherent) {
    const auto power = [](bool unison) {
        Synth s;
        s.SetSampleRate(48000);
        s.SetParam(kOsc1Shape, 0);
        s.SetParam(kOsc1Detune, 127);
        s.SetParam(kAmpRelease, 0);
        s.SetParam(kUnisonOn, unison);
        s.SetParam(kUnisonVoices, 8);
        s.SetParam(kUnisonDetune, 0);
        s.SetParam(kUnisonPan, 64);
        std::vector<float> l(48000), r(48000);
        double sum = 0;
        for (int n = 0; n < 8; ++n) {
            s.NoteOn(69, 127);
            s.Render(l.data(), r.data(), 48000);
            for (float x : l) sum += double(x) * x;
            s.NoteOff(69);
            Run(s, 4800);
        }
        return sum;
    };
    const double ratio = power(true) / power(false);
    OXO_EXPECT(ratio > 4 && ratio < 20);
}

// Master volume: Synth1's measured vol law, not a patch parameter.
OXO_TEST(MasterVolumeScalesOutput) {
    const auto peak = [](int volume) {
        Synth s;
        s.SetSampleRate(48000);
        s.SetVolume(volume);
        std::vector<float> l(2048), r(2048);
        s.Render(l.data(), r.data(), 64); // ramp done
        s.NoteOn(60, 127);
        s.Render(l.data(), r.data(), 2048);
        float p = 0;
        for (float x : l) p = std::max(p, std::fabs(x));
        return p;
    };
    const float unity = peak(64);
    OXO_EXPECT(unity > 0.01f);
    OXO_EXPECT_NEAR(peak(32), unity * 0.4512914f, 1e-5); // -6.9 dB (measured)
    OXO_EXPECT_NEAR(peak(127), unity * 3.498213f, 1e-4);  // +10.9 dB
    OXO_EXPECT_NEAR(peak(0), 0.0f, 1e-9);
    OXO_EXPECT_EQ(ClampValue(kVolumeParamId, 200), 127);
}

// (measured) a cutoff change glides linearly over 50 ms while a voice sounds.
OXO_TEST(CutoffKnobGlides) {
    const auto render = [](bool jump) {
        Synth s;
        s.SetSampleRate(48000);
        s.SetParam(kFilterFreq, 40);
        s.SetParam(kFilterRes, 0);
        s.NoteOn(60, 127);
        std::vector<float> l(4800), r(4800), out;
        s.Render(l.data(), r.data(), 4800);
        if (jump) s.SetParam(kFilterFreq, 100);
        for (int b = 0; b < 4; ++b) { // 4 x 25 ms
            s.Render(l.data(), r.data(), 1200);
            float e = 0;
            for (int i = 0; i < 1200; ++i) e += l[size_t(i)] * l[size_t(i)];
            out.push_back(e);
        }
        return out;
    };
    const auto still = render(false), moved = render(true);
    std::vector<float> gain;
    for (size_t b = 0; b < 4; ++b) gain.push_back(moved[b] / still[b]); // brightening vs no jump
    // first 25 ms: brighter, but short of where it ends up
    OXO_EXPECT(gain[0] > 1.1f);
    OXO_EXPECT(gain[0] < gain[3] * 0.85f);
    // after 50 ms the glide is over
    OXO_EXPECT_NEAR(gain[2] / gain[3], 1.0f, 0.05);
}

// Patch changes while keys are held (the browser keeps notes playing): the
// mono key stack must follow every key, whatever the play mode was when it
// went down / up, or a released key comes back as a stuck note.
OXO_TEST(PlayModeChangesWithHeldKeysLeaveNoStuckNote) {
    const auto held = [](Synth &s) {
        Run(s, 64);
        const uint64_t v = s.VoiceStates();
        int n = 0;
        for (int i = 0; i < kMaxVoices; ++i) n += (v >> (2 * i) & 3) == Synth::kVoiceHeld;
        return n;
    };
    const auto mono = [](Synth &s, bool on) {
        s.SetParam(kPlayMode, on ? 1 : 0);
        s.SetParam(kPolyphony, on ? 1 : 8);
    };
    { // key down in mono, up in poly, then mono again
        Synth s;
        s.SetSampleRate(48000);
        mono(s, true);
        s.NoteOn(57, 100);
        mono(s, false);
        s.NoteOff(57);
        mono(s, true);
        s.NoteOn(60, 100);
        s.NoteOff(60);
        OXO_EXPECT_EQ(held(s), 0);
    }
    { // poly chord held, patch -> mono, keys up
        Synth s;
        s.SetSampleRate(48000);
        mono(s, false);
        s.NoteOn(57, 100);
        s.NoteOn(60, 100);
        mono(s, true);
        s.NoteOn(64, 100);
        s.NoteOff(60);
        s.NoteOff(64);
        s.NoteOff(57);
        OXO_EXPECT_EQ(held(s), 0);
    }
    { // all notes off (CC 123) forgets the held keys
        Synth s;
        s.SetSampleRate(48000);
        mono(s, true);
        s.NoteOn(57, 100);
        s.ControlChange(123, 0);
        s.NoteOn(60, 100);
        s.NoteOff(60);
        OXO_EXPECT_EQ(held(s), 0);
    }
}

// Same with the arpeggiator switched by a patch change under a held key.
OXO_TEST(ArpChangesWithHeldKeysLeaveNoStuckNote) {
    const auto held = [](Synth &s) {
        Run(s, 4800);
        const uint64_t v = s.VoiceStates();
        int n = 0;
        for (int i = 0; i < kMaxVoices; ++i) n += (v >> (2 * i) & 3) == Synth::kVoiceHeld;
        return n;
    };
    { // key down without arp, arp on, key up
        Synth s;
        s.SetSampleRate(48000);
        s.SetParam(kArpOn, 0);
        s.NoteOn(57, 100);
        Run(s, 480);
        s.SetParam(kArpOn, 1);
        Run(s, 480);
        s.NoteOff(57);
        OXO_EXPECT_EQ(held(s), 0);
    }
    { // key down with arp, arp off, key up
        Synth s;
        s.SetSampleRate(48000);
        s.SetParam(kArpOn, 1);
        s.NoteOn(57, 100);
        Run(s, 4800);
        s.SetParam(kArpOn, 0);
        Run(s, 480);
        s.NoteOff(57);
        OXO_EXPECT_EQ(held(s), 0);
    }
}

// Fuzz: notes, pedal and patch switches (play mode, polyphony, unison, arp)
// in random order; once every key and the pedal are up, no voice is held.
OXO_TEST(RandomPatchSwitchesUnderHeldKeysLeaveNoStuckNote) {
    uint32_t seed = 12345;
    const auto rnd = [&](int n) {
        seed = seed * 1664525u + 1013904223u;
        return int((seed >> 8) % uint32_t(n));
    };
    for (int round = 0; round < 200; ++round) {
        Synth s;
        s.SetSampleRate(48000);
        bool down[128] = {};
        for (int step = 0; step < 60; ++step) {
            const int key = 48 + rnd(12);
            switch (rnd(9)) {
            case 0: case 1: case 2: s.NoteOn(key, 100); down[key] = true; break;
            case 3: case 4: s.NoteOff(key); down[key] = false; break;
            case 5: s.SetParam(kPlayMode, rnd(3)); s.SetParam(kPolyphony, rnd(2) ? 1 : 1 + rnd(16)); break;
            case 6: s.SetParam(kUnisonOn, rnd(2)); break;
            case 7: s.SetParam(kArpOn, rnd(4) == 0); break;
            case 8: s.ControlChange(64, rnd(2) ? 127 : 0); break;
            }
            Run(s, 64 + rnd(2000));
        }
        for (int k = 0; k < 128; ++k)
            if (down[k]) s.NoteOff(k);
        s.ControlChange(64, 0);
        Run(s, 48000);
        const uint64_t v = s.VoiceStates();
        int held = 0;
        for (int i = 0; i < kMaxVoices; ++i) held += (v >> (2 * i) & 3) == Synth::kVoiceHeld;
        OXO_EXPECT_EQ(held, 0);
        if (held) return;
    }
}

// A patch change empties the delay: the echoes fade out (no click), then
// silence, and the delay echoes new notes again.
OXO_TEST(ClearDelayFadesOutEchoes) {
    const auto setup = [](Synth &s) {
        s.SetSampleRate(48000);
        s.SetTransport(120, 0, false);
        s.SetParam(kEffectOn, 0);
        s.SetParam(kChorusOn, 0);
        s.SetParam(kDelayOn, 1);
        s.SetParam(kDelayTime, 5); // 1/4 beat = 125 ms
        s.SetParam(kDelayFeedback, 120);
        s.SetParam(kDelayDryWet, 127); // echoes only
        s.SetParam(kDelaySpread, 64);
        s.SetParam(kDelayTone, 64);
        s.SetParam(kDelayType, 0);
        s.NoteOn(60, 127);
        Run(s, 4800);
        s.Panic();
        Run(s, 21000); // echoes only, no voice left; the clear lands inside an echo
    };
    const auto peak = [](const std::vector<float> &x, size_t from, size_t to) {
        float p = 0;
        for (size_t i = from; i < to; ++i) p = std::max(p, std::fabs(x[i]));
        return p;
    };
    std::vector<float> kl(4800), kr(4800), l(4800), r(4800);
    Synth kept;
    setup(kept);
    kept.Render(kl.data(), kr.data(), 4800);
    OXO_EXPECT(peak(kl, 2400, 4800) > 0.01f); // without a clear the echoes go on

    Synth s;
    setup(s);
    s.ClearDelay();
    s.Render(l.data(), r.data(), 4800);
    bool faded = true;
    for (size_t i = 0; i < 960; ++i) {
        faded &= std::fabs(l[i]) <= std::fabs(kl[i]) + 1e-6f;                       // never louder
        if (i < 48) faded &= std::fabs(l[i] - kl[i]) <= 0.1f * std::fabs(kl[i]) + 1e-6f; // starts at full level
    }
    OXO_EXPECT(faded);
    OXO_EXPECT(peak(kl, 0, 48) > 0.01f);
    OXO_EXPECT_EQ(peak(l, 960, 4800), 0.0f); // empty after the fade (< 20 ms)
    OXO_EXPECT_EQ(peak(r, 960, 4800), 0.0f);

    s.NoteOn(60, 127);
    Run(s, 4800);
    s.Panic();
    s.Render(l.data(), r.data(), 4800);
    OXO_EXPECT(peak(l, 0, 4800) > 0.01f); // echoes again
}
