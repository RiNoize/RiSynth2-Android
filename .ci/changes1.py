CHANGES = [
    ('.github/workflows/android.yml', '1d2cef9c875949a054cec11bbab1045a6a8162dc', '32c6750cf53e585fb82d76bbd2642b506d9b8e34', [
        (35, 35, r'''      - name: Exact audio regression and host performance comparison
        run: bash android/test-engine.sh
'''),
        (57, 58, r'''          cp app/build/outputs/apk/release/app-release.apk ../dist/RiSynth2-0.3.0-arm64.apk
'''),
        (59, 60, r'''          cp app/build/outputs/apk/release/app-release.apk ../dist/RiSynth2-0.3.0-preview-arm64.apk
'''),
        (66, 67, r'''          "$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging dist/RiSynth2-0.3.0-preview-arm64.apk | grep -E "^(package:|application-label:)"
'''),
        (70, 71, r'''          name: RiSynth2-0.3.0-arm64
'''),
    ]),
    ('AGENTS.md', 'aca380dbcf7d850aa932a6362127ff341f356059', 'cc7ecd9a4ed19f10baaa7ebf44358a562cde1504', [
        (690, 690, r'''
## RiSynth2 Android 0.3.0 fork

Android-specific documentation is in android/AGENTS.md. At the user's request, the fork now memoizes exact envelope/voice/filter inputs and shares global pitch-LFO calculations; original measured tables/formulas remain. `android/test-engine.sh` is the mandatory bit-for-bit regression against the 0.2.0 shipped engine, with separate non-device host benchmarks. Favorites are immutable .sy1 snapshots managed outside the audio thread. See android/OPTIMIZATION.md.
'''),
    ]),
    ('ANDROID.md', '2ecb0cfe4ae407300bcdd8028ac64456f388e8fb', '51e0648a21643cc86d5f78474b0e4b926292a026', [
        (0, 1, r'''# RiSynth2 Android 0.3.0
'''),
        (4, 5, r'''**Descarga:** Actions → Build Android APK → una ejecución terminada correctamente → Artifacts → RiSynth2-0.3.0-arm64. Descomprimir el ZIP. Si tenés la 0.1.0, instalar **RiSynth2-0.3.0-preview-arm64.apk**: aparecerá como **RiSynth2 Preview**, sin reemplazar ni borrar la anterior. Los bancos se importan nuevamente desde sus carpetas; los datos privados no se comparten entre ambas aplicaciones. La firma de 0.1.0 no quedó preservada por un error de caché; la APK normal no puede actualizarla directamente. No desinstales la anterior para probar esta versión.
'''),
        (10, 11, r'''El motor conserva sus fórmulas, tablas y polifonía; 0.3.0 evita cálculos repetidos y comparte la modulación global de afinación entre voces. Ver `android/OPTIMIZATION.md` y las comparaciones exactas de audio. El usuario reportó cortes con 512 y mejor funcionamiento con 1024 en 0.1.0: este monitor ayudará a investigarlos, no implica que estén corregidos. La compilación y las pruebas automáticas no sustituyen las pruebas en la tablet. Ver detalles y límites en android/README.md.

## Favoritos y actualización 0.3.0

**☆ Guardar favorito** copia el sonido actual con tus ajustes a la colección privada **★ Favoritos**. No modifica el banco original ni cambia el sonido seleccionado; evita duplicar una copia idéntica. Al superar 128 sonidos crea otro banco de favoritos. Los favoritos se conservan al cerrar y abrir la aplicación. Exportá los importantes antes de desinstalar o borrar datos.

Para actualizar **Preview 0.2.0**, instalá **Preview 0.3.0 encima**, sin desinstalar ni reimportar los bancos. Conserva el mismo paquete y certificado de firma fijado en el repositorio. La mejora de CPU depende del patch y debe medirse en la tablet; no se promete corregir todos los cortes.
'''),
    ]),
    ('EngineCore/Envelope.hpp', '8c8cb6962e7171a074efbeae3192c5130e32d8b8', '911e79f163fb76967d253a121663ddaa6c01bb3e', [
        (23, 23, r'''        // Coefficients depend on settings, not the envelope's current stage.
        // Preserve the measured expressions; avoid exp() on every voice chunk.
        if (sampleRate == configuredRate_ && attack == configuredAttack_ && decay == configuredDecay_ &&
            sustain == configuredSustain_ && release == configuredRelease_ && law == configuredLaw_) return;
        configuredRate_ = sampleRate;
        configuredAttack_ = attack; configuredDecay_ = decay;
        configuredSustain_ = sustain; configuredRelease_ = release; configuredLaw_ = law;
'''),
        (71, 71, r'''    double configuredRate_ = -1;
    int configuredAttack_ = -1, configuredDecay_ = -1, configuredSustain_ = -1, configuredRelease_ = -1;
    SustainLaw configuredLaw_ = SustainLaw::Amp;
'''),
    ]),
    ('EngineCore/Filter.hpp', '35f4b8082e525456aea39939e8da4198555c6f1d', '082851a4b7e227d4a4774e19568fd0b06de96695', [
        (62, 62, r'''    double LookupSvf(double knob) const {
        const double x = std::clamp(knob, 0.0, 127.0) * kSteps;
        const int i = std::min(int(x), kSize - 2);
        const double t = x - i;
        return svfF_[i] + (svfF_[i + 1] - svfF_[i]) * t;
    }
    void LookupLadder(double knob, double &a, double &comp) const {
        const double x = std::clamp(knob, 0.0, 127.0) * kSteps;
        const int i = std::min(int(x), kSize - 2);
        const double t = x - i;
        a = ladderA_[i] + (ladderA_[i + 1] - ladderA_[i]) * t;
        comp = comp_[i] + (comp_[i + 1] - comp_[i]) * t;
    }

'''),
        (86, 86, r'''        configuredType_ = -1; // coefficients are sample-rate dependent
'''),
        (103, 103, r'''        // Do not recompute static coefficients during held pads. This is
        // exact equality, not a lower control rate or cutoff quantization.
        if (type == configuredType_ && res == configuredRes_ && cutoffKnob == configuredCutoff_) return;
        configuredType_ = type; configuredRes_ = res; configuredCutoff_ = cutoffKnob;
'''),
        (105, 111, r'''        if (type == 1) {
            double comp;
            tables_->LookupLadder(cutoffKnob, ladderA_, comp);
            ladderK_ = std::max(0.0, 0.0297 * res - 0.076) * comp;
        } else {
            svfF_ = tables_->LookupSvf(cutoffKnob);
            const double r = res / 127.0;
            svfD_ = std::max(1.0 - r, 0.0);
            svfG_ = 1.0 - 0.7 * r;
        }
'''),
        (249, 250, r'''    int type_ = 1, configuredType_ = -1, configuredRes_ = -1;
    double configuredCutoff_ = 0;
'''),
    ]),
    ('EngineCore/Synth.hpp', '186b31461899f4f8b71c62219a87b9b8c5a98196', '86981d70db4c8f0510654aaca3ba6132118fa262', [
        (429, 429, r'''            // Shared, sample-accurate pitch modulation. The oscillator base
            // pitch, portamento and mod envelope remain per voice.
            bool pitchModulated = false;
            for (int k = 0; k < 2; ++k) {
                const int dest = std::clamp<int>(values_[k ? kLfo2Dest : kLfo1Dest], 1, 7);
                if (lfo[k] && (dest == 1 || dest == 2)) pitchModulated = true;
            }
            if (pitchModulated) {
                const double bendCents = bendSemis * 100.0;
                const auto ratio = [&](int which, double cents) {
                    const double quantized = 9.375 * std::trunc(cents / 9.375);
                    if (quantized != pitchCentsCache_[which]) {
                        pitchCentsCache_[which] = quantized;
                        pitchRatioCache_[which] = std::exp2(quantized / 1200.0);
                    }
                    return pitchRatioCache_[which];
                };
                for (int i = 0; i < n; ++i) {
                    double all = 0, second = 0;
                    for (int k = 0; k < 2; ++k) {
                        if (!lfo[k]) continue;
                        const int dest = std::clamp<int>(values_[k ? kLfo2Dest : kLfo1Dest], 1, 7);
                        const double depth = tables::kLfoPitchCents[values_[k ? kLfo2Depth : kLfo1Depth]];
                        if (dest == 2) all += depth * lfo[k][i];
                        if (dest == 1) second += depth * lfo[k][i];
                    }
                    pitchAll_[i] = ratio(0, bendCents + all);
                    pitchOsc2_[i] = ratio(1, bendCents + all + second);
                }
            }
'''),
        (432, 433, r'''                v.Render(left + pos, right + pos, n, blockPos_ + pos, blockEnd_, values_, bendSemis, lfo, cutoffStart, cutoffStep,
                         pitchModulated ? pitchAll_ : nullptr, pitchModulated ? pitchOsc2_ : nullptr);
'''),
        (597, 597, r'''    double pitchAll_[kLfoBlock] = {}, pitchOsc2_[kLfoBlock] = {};
    double pitchCentsCache_[2] = {-1e30, -1e30}, pitchRatioCache_[2] = {1, 1};
'''),
    ]),
    ('EngineCore/Voice.hpp', '04deee4b71364a8dcf78adf0e7722888ffe0149c', '5da5edf9587524a28a2c009baa6f1cf5190735d2', [
        (156, 157, r'''                double bendSemis, const double *const lfo[2], double cutoffKnob, double cutoffStep,
                const double *pitchAll = nullptr, const double *pitchOsc2 = nullptr) {
'''),
        (164, 165, r'''            RenderChunk(left + off, right + off, n, pos + off, blockEnd, p, bendSemis, at, cutoffKnob + cutoffStep * off, cutoffStep,
                        pitchAll ? pitchAll + off : nullptr, pitchOsc2 ? pitchOsc2 + off : nullptr);
'''),
        (178, 179, r'''                     double bendSemis, const double *const lfo[2], double cutoffKnob, double cutoffStep,
                     const double *pitchAll, const double *pitchOsc2) {
'''),
        (202, 203, r'''        if (base != cachedBase_) { cachedBase_ = base; cachedF1_ = NoteHz(base); }
        const double f1 = cachedF1_;
'''),
        (205, 206, r'''        if (osc2Note != cachedOsc2Note_) { cachedOsc2Note_ = osc2Note; cachedF2_ = NoteHz(osc2Note); }
        const double f2 = cachedF2_;
'''),
        (207, 208, r'''        const double bendCents = bendSemis * 100.0;
        if (bendCents != cachedBend_) {
            cachedBend_ = bendCents; cachedBendRatio_ = std::exp2(PitchMod(bendCents) / 1200.0);
        }
        const double bendRatio = cachedBendRatio_;
'''),
        (231, 234, r'''        if (p[kOsc1Detune] != cachedDetune_) {
            cachedDetune_ = p[kOsc1Detune];
            for (int i = 0; i < kDetuneOscs; ++i)
                detuneRatio_[i] = std::exp2(kDetuneOffsets[i] * (20.0 * p[kOsc1Detune] / 127.0) / 1200.0);
        }
        const auto &detuneRatio = detuneRatio_;
'''),
        (257, 259, r'''        if (p[kAmpGain] != cachedGain_ || p[kAmpVelSens] != cachedVelSens_ || velocity_ != cachedVelocity_) {
            cachedGain_ = p[kAmpGain]; cachedVelSens_ = p[kAmpVelSens]; cachedVelocity_ = velocity_;
            cachedAmpGain_ = kOscAmplitude * tables::kAmpGain[p[kAmpGain]] *
                            std::pow(10.0, -15.0 * (p[kAmpVelSens] / 64.0) * (127 - velocity_) / 126.0 / 20.0);
        }
        const double ampGain = cachedAmpGain_;
'''),
        (301, 304, r'''            // Pitch LFOs and bend are global: Synth computes the exact same
            // quantized ratios once per sample, not once per unison voice.
            // Keep the original path for direct Voice callers (tests/tools).
            const double lfoAll = !pitchAll && r[2] ? pitchCents(r[2], i) : 0.0;
            const double mAll = pitchAll ? pitchAll[i] : r[2] ? std::exp2(PitchMod(bendCents + lfoAll) / 1200.0) : bendRatio;
            const double m2 = pitchOsc2 ? pitchOsc2[i] : r[1] ? std::exp2(PitchMod(bendCents + lfoAll + pitchCents(r[1], i)) / 1200.0) : mAll;
'''),
        (459, 459, r'''    // Exact memoization: no tables approximated, no precision/voice reduction.
    double cachedBase_ = -1e30, cachedOsc2Note_ = -1e30, cachedBend_ = -1e30;
    double cachedF1_ = 0, cachedF2_ = 0, cachedBendRatio_ = 1, cachedAmpGain_ = 0;
    int cachedDetune_ = -1, cachedGain_ = -1, cachedVelSens_ = -1, cachedVelocity_ = -1;
    std::array<double, kDetuneOscs> detuneRatio_{};
'''),
    ]),
    ('android/AGENTS.md', '399db0d0e738c97d42916326d8a5daedf7834aff', '7d1a3985dadb46c8f66744d22764dc1c284b4ccc', [
        (23, 23, r'''
## 0.3 exact optimizations / favorites

The user explicitly requested engine optimization. Envelope.hpp, Voice.hpp, Filter.hpp and Synth.hpp now use exact-input memoization and shared global pitch LFOs; all other DSP files/tables are unchanged. `test-engine.sh` compares 67 scenarios bit-for-bit against shipped 0.2.0 (`450c699`) and records a separate host benchmark. Do not replace formulas, lower quality, or loosen equality to make a failing regression pass. Gains are patch-specific; never claim a host benchmark is a Tab A9 measurement.

`FavoriteStore` keeps immutable content-addressed snapshots under the private bank root's hidden `.favorites` directory. Staging+rename commits both patch and name. Repeated identical saves deduplicate; edited copies never overwrite originals. 128-slot pages use stable `:favorites:N` keys and are inserted after INIT. Only the worker performs disk I/O. NativeEngine.snapshot captures matching name+parameter state under one JNI-entry lock at tap time. Saving must not switch the sound or accidentally save the next browsed patch. Do not change the Preview application ID or pinned signer.
'''),
    ]),
]
