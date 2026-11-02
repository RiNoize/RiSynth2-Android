#pragma once

// One Synth1 voice. Every law marked (measured) comes from the original's
// renders (tools/s1ref, reference/meta); the rest is still a placeholder.
//
// Signal path so far: osc1 (+sub, +8x detune, FM from osc2) / osc2 (sync,
// ring) -> linear mix -> [filter: TODO] -> amp ADSR * gain * velocity ->
// fixed one-pole lowpass (analog 22 kHz, bilinear without prewarp).

#include "Envelope.hpp"
#include "Filter.hpp"
#include "MeasuredTables.hpp"
#include "Params.hpp"
#include "WaveTables.hpp"

#include <array>
#include <cmath>
#include <cstdint>

namespace oxo::s2 {

struct Rng {
    uint32_t state = 0x9E3779B9u;
    uint32_t NextU32() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return state;
    }
    double Unit() { return NextU32() * (1.0 / 4294967296.0); }      // [0, 1)
    float Bipolar() { return float(Unit() * 2.0 - 1.0); }           // [-1, 1)
};

inline constexpr double kOscAmplitude = 0.2;  // (measured) sine/saw/tri peak at gain 64, vel 127
inline constexpr double kNoiseAmplitude = 1.1265; // (measured) uniform white, rms 0.508 FS after the post lowpass
inline constexpr int kDetuneOscs = 8;
inline constexpr double kDetuneOffsets[kDetuneOscs] = {-7, -5, -3, -1, 1, 3, 5, 7}; // x d cents (measured)

inline double NoteHz(double note) { return 440.0 * std::exp2((note - 69.0) / 12.0); }

class Voice {
public:
    void Prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        tables_ = WaveTableSet::ForSampleRate(sampleRate);
        filter_.Prepare(sampleRate);
        const double k = M_PI * 22000.0 / sampleRate; // (measured) post lowpass
        postA_ = k / (1 + k);
        postB_ = (1 - k) / (1 + k);
    }

    // detuneCents / pan (-1..1) / pitchSemis / phase (cycles): this voice's
    // place in a unison stack.
    void Start(int note, int velocity, const PatchValues &p, Rng &rng, uint64_t age, double detuneCents = 0,
               double pan = 0, double pitchSemis = 0, double unisonPhase = 0) {
        unisonSemis_ = detuneCents / 100.0 + pitchSemis;
        unisonPan_ = pan;
        note_ = note;
        glideNote_ = glideFrom_ >= 0 ? glideFrom_ : note;
        glideFrom_ = -1;
        velocity_ = velocity;
        age_ = age;
        gate_ = true;
        amp_.Configure(sampleRate_, p[kAmpAttack], p[kAmpDecay], p[kAmpSustain], p[kAmpRelease]);
        amp_.Trigger();
        // (measured) the filter env shares the amp env's laws
        filterEnv_.Configure(sampleRate_, p[kFilterAttack], p[kFilterDecay], p[kFilterSustain], p[kFilterRelease],
                             Adsr::SustainLaw::Linear);
        filterEnv_.Trigger();
        // (measured) mod env = AD with the amp env's attack / decay laws
        modEnv_.Configure(sampleRate_, p[kModEnvAttack], p[kModEnvDecay], 0, 0, Adsr::SustainLaw::Linear);
        modEnv_.Trigger();
        // (measured) osc phase 0 = free phases; > 0 = osc1 starts at phase 0,
        // osc2 at (v - 1) / 126 of half a cycle (127 = 180 degrees).
        osc1Phase_ = rng.Unit();
        osc2Phase_ = rng.Unit();
        if (p[kOscPhase] > 0) {
            osc1Phase_ = 0;
            osc2Phase_ = 0.5 * (p[kOscPhase] - 1) / 126.0;
        }
        osc1Phase_ = Wrap(osc1Phase_ + unisonPhase);
        osc2Phase_ = Wrap(osc2Phase_ + unisonPhase);
        // (measured) the sub runs phase-locked to osc1: its harmonic at osc1's
        // pitch is in phase with osc1's fundamental.
        const double subRatio = p[kSubOctave] ? 0.5 : 1.0;
        subPhase_ = osc1Phase_ * subRatio;
        for (int i = 0; i < kDetuneOscs; ++i) {
            detunePhase_[i] = rng.Unit();
            // (measured) with osc phase > 0 copy i starts kCopyPhaseDeg[i] * (v - 1) / 126 after osc1
            static constexpr double kCopyPhaseDeg[kDetuneOscs] = {321, 172, 291, 202, 0, 69, 210, 126};
            if (p[kOscPhase] > 0) detunePhase_[i] = Wrap(osc1Phase_ + kCopyPhaseDeg[i] / 360.0 * (p[kOscPhase] - 1) / 126.0);
            subDetunePhase_[i] = detunePhase_[i] * subRatio;
        }
        noiseRng_.state = rng.NextU32() | 1u;
        postZ_ = postY_ = 0;
        filter_.Reset();
    }

    // Portamento source for the next Start (pitch of the previous note).
    void SetGlideFrom(double note) { glideFrom_ = note; }
    double CurrentPitch() const { return glideNote_; }

    // Mono / legato note change on a sounding voice.
    void ChangeNote(int note, int velocity, bool retrigger, bool glide) {
        note_ = note;
        if (!glide) glideNote_ = note;
        gate_ = true;
        if (retrigger) {
            if (velocity >= 0) velocity_ = velocity; // < 0: keep the current one
            amp_.Trigger();
            filterEnv_.Trigger();
            modEnv_.Trigger();
        }
    }

    void Release() {
        gate_ = false;
        amp_.ReleaseNote();
        filterEnv_.ReleaseNote();
    }
    void Kill() { amp_.Reset(); }

    bool Active() const { return amp_.Active(); }
    bool Gate() const { return gate_; }
    int Note() const { return note_; }
    uint64_t Age() const { return age_; }

    // lfo[k] = LFO k's per-sample value (nullptr when that LFO is off).
    // cutoffKnob / cutoffStep: the smoothed cutoff knob at the block start and
    // its per-sample ramp (Synth's 50 ms glide, measured).
    void Render(float *left, float *right, int frames, const PatchValues &p, double bendSemis,
                const double *const lfo[2], double cutoffKnob, double cutoffStep) {
        if (!Active()) return;
        // ---- LFO routing (measured depth laws per destination). Both LFOs on
        // one destination (measured, measure_lfo_sum.py): pitch = sum of each
        // LFO's cents; cutoff / FM / pw / pan = the law applied to the
        // depth-weighted sum of the LFO values; amp = product of the gains.
        LfoRoute r[8];
        for (int k = 0; k < 2; ++k) {
            if (!lfo[k]) continue;
            LfoRoute &route = r[std::clamp<int>(p[k ? kLfo2Dest : kLfo1Dest], 1, 7)];
            route.v[route.n] = lfo[k];
            route.depth[route.n++] = p[k ? kLfo2Depth : kLfo1Depth];
        }
        const auto pitchCents = [&](const LfoRoute &route, int i) {
            double c = 0;
            for (int k = 0; k < route.n; ++k) c += tables::kLfoPitchCents[route.depth[k]] * route.v[k][i];
            return c;
        };
        // ---- per-block parameters ----
        const double tuneSemis = tables::kFineCents[p[kOscFineTune]] / 100.0;
        // (measured) portamento: exponential glide, tau per knob
        const double glideCoef = p[kPortaTime] > 0 ? 1.0 - std::exp(-double(frames) / (PortaTau(p[kPortaTime]) * sampleRate_)) : 1.0;
        glideNote_ += (note_ - glideNote_) * glideCoef;
        const double base = glideNote_ + p[kOscKeyShift] + tuneSemis + bendSemis + unisonSemis_;
        const double f1 = NoteHz(base);
        const double osc2Note = (p[kOsc2KbdTrack] ? base : 57.0 + tuneSemis + bendSemis) +
                                tables::kOsc2PitchSemis[p[kOsc2Pitch]] + tables::kFineCents[p[kOsc2Fine]] / 100.0;
        const double f2 = NoteHz(osc2Note);
        const int t1 = int(std::lround(base)), t2 = int(std::lround(osc2Note));
        const bool osc1Silent = t1 > 127, osc2Silent = t2 > 127; // (measured) no table above note 127
        const WaveTable &saw1 = tables_->Saw(std::clamp(t1, 0, 127));
        const WaveTable &tri1 = tables_->Triangle(std::clamp(t1, 0, 127));
        const int tSub = p[kSubOctave] ? t1 - 12 : t1;
        const WaveTable &sawSub = tables_->Saw(std::clamp(tSub, 0, 127));
        const WaveTable &triSub = tables_->Triangle(std::clamp(tSub, 0, 127));
        const WaveTable &saw2 = tables_->Saw(std::clamp(t2, 0, 127));
        const WaveTable &tri2 = tables_->Triangle(std::clamp(t2, 0, 127));
        const double inc1 = f1 / sampleRate_, inc2 = f2 / sampleRate_;
        const double width = p[kOscPulseWidth] / 254.0; // (measured) duty of the low part, 127 = square
        const double mix2 = p[kOscMix] / 127.0, mix1 = 1.0 - mix2; // (measured) linear crossfade
        const double fmBeta = p[kOsc2Ring] ? 0.0 : tables::kFmBeta[p[kOsc1FM]];
        const bool ring = p[kOsc2Ring] != 0, sync = p[kOsc2Sync] != 0;
        const int shape1 = p[kOsc1Shape], shape2 = p[kOsc2Shape];
        const double subGainKnob = p[kSubGain];
        const double subW = subGainKnob / (subGainKnob + 32.0), mainW = 1.0 - subW; // (measured) auto level
        const bool subOn = p[kSubGain] > 0;
        const int subShape = p[kSubShape];
        const double subRatio = p[kSubOctave] ? 0.5 : 1.0;
        const bool detuneOn = p[kOsc1Detune] > 0;
        double detuneRatio[kDetuneOscs];
        for (int i = 0; i < kDetuneOscs; ++i)
            detuneRatio[i] = std::exp2(kDetuneOffsets[i] * (20.0 * p[kOsc1Detune] / 127.0) / 1200.0);
        const double detuneGain = shape1 == 0 ? 0.288 : 0.297; // (measured) per detuned copy

        // ---- cutoff modulation in knob units (measured): kbd track pivots on
        // key 48 (1 octave = 12.624 knob units), env amount (v - 64) * 1.984
        // knob units at env = 1, scaled by velocity when the switch is on.
        // (measured, measure_filter_env.py) the env sweeps from base - 2.1
        // (env 0) to the env-1 TARGET clamped to [-2, 125]: a target beyond
        // the top still moves the cutoff over the whole attack / decay.
        const int filterType = p[kFilterType], filterRes = p[kFilterRes];
        const double cutoffBase = cutoffKnob + tables::kKbdTrack[p[kFilterKbdTrack]] * (note_ - 48) * (12.624 / 12.0);
        const double envDepth = (p[kFilterAmount] - 64) * 1.984 *
                                (p[kFilterVelSwitch] ? tables::kFilterVelScale[velocity_] : 1.0);

        filterEnv_.Configure(sampleRate_, p[kFilterAttack], p[kFilterDecay], p[kFilterSustain], p[kFilterRelease],
                             Adsr::SustainLaw::Linear);
        amp_.Configure(sampleRate_, p[kAmpAttack], p[kAmpDecay], p[kAmpSustain], p[kAmpRelease]);
        const double satA = tables::kSaturationA[p[kFilterSat]];
        modEnv_.Configure(sampleRate_, p[kModEnvAttack], p[kModEnvDecay], 0, 0, Adsr::SustainLaw::Linear);
        const bool modOn = p[kModEnvOn] != 0;
        const int modDest = p[kModEnvDest];
        // (measured) amount -> -1..+1: (amt - 64) / 63 above 64, / 64 below
        const double modAmt = (p[kModEnvAmount] - 64) / (p[kModEnvAmount] > 64 ? 63.0 : 64.0);

        const double ampGain = kOscAmplitude * tables::kAmpGain[p[kAmpGain]] *
                               std::pow(10.0, -15.0 * (p[kAmpVelSens] / 64.0) * (127 - velocity_) / 126.0 / 20.0);

        double curWidth = width;
        auto shape1At = [&](double ph) -> double {
            switch (shape1) {
            case 0: return std::sin(2 * M_PI * ph);
            case 1: return saw1.Read(ph);
            case 2: return 0.5 * (saw1.Read(ph) - saw1.Read(Wrap(ph + curWidth)));
            default: return tri1.Read(ph);
            }
        };
        auto subAt = [&](double ph) -> double {
            switch (subShape) {
            case 0: return std::sin(2 * M_PI * ph);
            case 1: return triSub.Read(ph);
            case 2: return sawSub.Read(ph);
            default: return 0.5 * (sawSub.Read(ph) - sawSub.Read(Wrap(ph + 0.5)));
            }
        };

        // (measured, key-synced square LFO: measure_lfo_keysync.py /
        // measure_lfo_cutoff.py) cutoff, amp and pan are UNIPOLAR, u =
        // (lfo + 1) / 2 (linear in between assumed):
        // cutoff -> pulled toward knob 125 by (depth / 127) * u;
        // amp    -> gain 1 - (depth / 127) * (1 - u);
        // pan    -> position q = 1 - (1 - s)^3 for s = depth / 127 * lfo >= 0
        //           (odd), gains toward side 1 + |q| / 2, away side 1 - |q|.
        // pw     -> (measured, measure_lfo_pw.py) the low-part duty moves toward
        //           1 (lfo > 0) or 0 (lfo < 0) by depth / 127 * |lfo| of the
        //           room left on that side: never wraps, +-0.5 for a square.

        // balance law (measured on unison spread): centre 1/1, hard side 1/0
        const double pan = std::clamp((p[kPan] - 64) / 63.0 + unisonPan_, -1.0, 1.0);
        const double panL = std::min(1.0, 1.0 - pan), panR = std::min(1.0, 1.0 + pan);

        for (int i = 0; i < frames; ++i) {
            // ---- per-sample modulation ----
            const double me = modOn ? modEnv_.Next() * modAmt : 0.0;
            const double mAll = r[2] ? std::exp2(pitchCents(r[2], i) / 1200.0) : 1.0;
            const double m2 = r[1] ? std::exp2(pitchCents(r[1], i) / 1200.0) : 1.0;
            // (measured) mod env -> osc2 pitch: +-60 semitones at env 1
            const double mEnv2 = (modOn && modDest == 0) ? std::exp2(me * 60.0 / 12.0) : 1.0;
            const double incA = inc1 * mAll, incB = inc2 * mAll * m2 * mEnv2;
            double w = width;
            if (r[5]) {
                const double l = r[5].Signed(i);
                w += l * (l > 0 ? 1.0 - width : width);
            }
            if (modOn && modDest == 2) {
                // (measured, measure_modenv_pw.py) same room-left law
                const double m = std::clamp(me, -1.0, 1.0);
                w += m * (m > 0 ? 1.0 - w : w);
            }
            // (measured, measure_pw_limits.py) the final duty never reaches 0 / 1:
            // pw 0 = pw 1 = 1.25 / 254, modulation tops out at 1 - 1.375 / 254
            w = std::clamp(w, 1.25 / 254.0, 1.0 - 1.375 / 254.0);
            double fmB = fmBeta;
            if (!ring && (r[6] || (modOn && modDest == 1))) {
                // (measured, measure_fm_mod.py) in FM knob units (fractional):
                // LFO unipolar toward 127 by depth / 127 * u; mod env
                // bipolar room-left, m = (amt - 64) / 64 at env 1.
                double knob = p[kOsc1FM];
                if (r[6]) knob += (127.0 - knob) * r[6].Unipolar(i);
                if (modOn && modDest == 1) {
                    // the DECAY reaches FM in dB: 0 dB -> 1, -80 dB -> 0 (the
                    // offset falls LINEARLY in time, to 0 at 9.2 taus); the
                    // attack ramp is linear as elsewhere
                    const double e = modEnv_.Level();
                    const double g = modEnv_.Attacking() ? e : e > 1e-4 ? 1.0 + std::log(e) / std::log(1e4) : 0.0;
                    const double m = std::clamp(modAmt * g, -1.0, 1.0);
                    knob += m * (m > 0 ? 127.0 - knob : knob);
                }
                const double k = std::clamp(knob, 0.0, 127.0);
                const int j = std::min(int(k), 126);
                fmB = tables::kFmBeta[j] + (tables::kFmBeta[j + 1] - tables::kFmBeta[j]) * (k - j);
            }
            curWidth = w;
            // ---- osc2 ----
            double o2 = 0;
            switch (shape2) {
            case 1: o2 = saw2.Read(osc2Phase_); break;
            case 2: o2 = 0.5 * (saw2.Read(osc2Phase_) - saw2.Read(Wrap(osc2Phase_ + w))); break;
            case 3: o2 = tri2.Read(osc2Phase_); break;
            default: o2 = kNoiseAmplitude * noiseRng_.Bipolar(); break;
            }
            if (osc2Silent && shape2 != 4) o2 = 0;

            // ---- osc1 (+ detune copies, + sub) ----
            const double fm = 1.0 + fmB * o2;
            double o1 = shape1At(osc1Phase_);
            double sub = subOn ? subAt(subPhase_) : 0.0;
            if (detuneOn) {
                double acc = o1, subAcc = sub;
                for (int d = 0; d < kDetuneOscs; ++d) {
                    acc += shape1At(detunePhase_[d]);
                    if (subOn) subAcc += subAt(subDetunePhase_[d]);
                    detunePhase_[d] = Wrap(detunePhase_[d] + incA * detuneRatio[d] * fm);
                    subDetunePhase_[d] = Wrap(subDetunePhase_[d] + incA * subRatio * detuneRatio[d] * fm);
                }
                o1 = acc * detuneGain;
                sub = subAcc * detuneGain;
            }
            if (osc1Silent) o1 = sub = 0;
            const double osc1Out = subOn ? mainW * o1 + subW * sub : o1;
            if (ring) o2 *= o1;

            osc1Phase_ += incA * fm;
            const bool wrapped = osc1Phase_ >= 1.0 || osc1Phase_ < 0.0;
            osc1Phase_ = Wrap(osc1Phase_);
            subPhase_ = Wrap(subPhase_ + incA * subRatio * fm);
            if (sync && wrapped && fm > 0) {
                // (measured, alias energy matches to 0.1 dB) the original's hard
                // sync is NAIVE: osc2 restarts at phase 0, no band-limiting
                osc2Phase_ = 0;
                // (measured, v1.12 manual "synced noise") the noise generator
                // restarts too, from one fixed state shared by every voice and
                // note: synced noise repeats per osc1 cycle (pitched)
                noiseRng_.state = Rng{}.state;
            } else {
                osc2Phase_ = Wrap(osc2Phase_ + incB);
            }

            const double mixed = mix1 * osc1Out + mix2 * o2;
            // ---- filter -> saturation -> amp (measured order) ----
            const double base = cutoffBase + cutoffStep * i, low = base - 2.1;
            double cutoff = low + (std::clamp(base + envDepth, -2.0, 125.0) - low) * filterEnv_.Next();
            if (r[3]) cutoff += std::max(0.0, 125.0 - cutoff) * std::min(1.0, r[3].Unipolar(i)); // tops out (measured)
            filter_.SetParams(filterType, cutoff, filterRes);
            const double filtered = Saturate(filter_.Process(mixed), satA);
            const double env = amp_.Next();
            double gainMod = 1.0;
            for (int k = 0; k < r[4].n; ++k) gainMod *= 1.0 - r[4].depth[k] / 127.0 * 0.5 * (1.0 - r[4].v[k][i]);
            const double x = filtered * env * ampGain * gainMod;
            // ---- post lowpass ----
            const double y = postA_ * (x + postZ_) + postB_ * postY_;
            postZ_ = x;
            postY_ = y;
            double gl = panL, gr = panR;
            if (r[7]) {
                const double s = r[7].Signed(i); // + = left; unclamped (measured: 127 + 127 -> gains 2 / -1)
                const double q = s >= 0 ? 1.0 - std::pow(1.0 - s, 3.0) : std::pow(1.0 + s, 3.0) - 1.0;
                gl *= q >= 0 ? 1.0 + 0.5 * q : 1.0 + q;
                gr *= q >= 0 ? 1.0 - q : 1.0 - 0.5 * q;
            }
            left[i] += float(y * gl);
            right[i] += float(y * gr);
        }
    }

private:
    // The LFOs (0..2) routed to one destination.
    struct LfoRoute {
        const double *v[2] = {nullptr, nullptr};
        int depth[2] = {0, 0};
        int n = 0;
        explicit operator bool() const { return n > 0; }
        // sum of depth / 127 * f(lfo value)
        template <typename F> double Sum(int i, F f) const {
            double s = 0;
            for (int k = 0; k < n; ++k) s += depth[k] / 127.0 * f(v[k][i]);
            return s;
        }
        double Signed(int i) const { return Sum(i, [](double x) { return x; }); }
        double Unipolar(int i) const { return Sum(i, [](double x) { return 0.5 * (x + 1.0); }); }
    };

    static double Wrap(double ph) { return ph - std::floor(ph); }
    static double PortaTau(int v) {
        static constexpr double knob[9] = {0, 16, 32, 48, 64, 80, 96, 112, 127};
        static constexpr double tau[9] = {0.0005, 0.0074, 0.0196, 0.039, 0.0716, 0.130, 0.29, 0.6, 1.2};
        int i = 0;
        while (i < 7 && v > knob[i + 1]) ++i;
        const double t = (v - knob[i]) / (knob[i + 1] - knob[i]);
        return std::exp(std::log(tau[i]) * (1 - t) + std::log(tau[i + 1]) * t);
    }

    double sampleRate_ = 44100;
    std::shared_ptr<const WaveTableSet> tables_;
    Adsr amp_;
    Adsr filterEnv_;
    Adsr modEnv_;
    VoiceFilter filter_;
    int note_ = 0, velocity_ = 0;
    double unisonSemis_ = 0, unisonPan_ = 0;
    double glideNote_ = 60, glideFrom_ = -1;
    uint64_t age_ = 0;
    bool gate_ = false;
    double osc1Phase_ = 0, osc2Phase_ = 0, subPhase_ = 0;
    std::array<double, kDetuneOscs> detunePhase_{}, subDetunePhase_{};
    Rng noiseRng_;
    double postA_ = 1, postB_ = 0, postZ_ = 0, postY_ = 0;
};

} // namespace oxo::s2
