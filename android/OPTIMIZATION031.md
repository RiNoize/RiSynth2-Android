# 0.3.1 — per-sample optimization, compared directly with 0.3.0

## What prompted this work

The user reports almost unchanged CPU/render cost in Preview 0.3.0. That report takes precedence over predictions from a desktop benchmark. Inspection of the shipped 0.3.0 ZIP confirms both APKs contain byte-identical `librisynth2.so` (SHA-256 `0e7a0addc73d7fc7c44399649bddacfa8fa974b98c55ce8c1fec0df5d74cc154`). Installing the standard APK instead of Preview cannot improve its DSP performance. Package identities remain separate to protect existing user data.

## Changes

1. **Immutable Hermite coefficients.** The old wavetable reader reconstructed three polynomial coefficients at every oscillator sample. They now use the same float expressions once when building each table, keeping the same four-point cubic interpolation, harmonics, table sizes and sample precision. All voices share the prepared data. Extra memory: 10.46875 MiB per 44.1-kHz set, 11.40625 MiB per 48-kHz set, in addition to original samples. The sample-rate cache can retain both. Preparation stays off the audio callback.
2. **Shared global LFO work.** Pan's nonlinear `pow` curves, amplitude multipliers, cutoff modulation weights, pulse-width weights and FM weights are computed once per sample rather than once per unison voice. Original summation order, per-voice envelopes/filter state and unison stereo offsets are retained. Portamento's smaller chunks correctly offset the shared arrays.
3. **Oscillator dispatch outside the hot loop.** Four typed kernels select oscillator 1's waveform once per chunk, avoiding repeated shape dispatch in the eight-copy detune loop. Same expressions and state advancement. Zero FM avoids a division whose finite legal-input result was exactly 1.0.
4. **Bounded effect-ring indexing.** Delay and chorus indices wrap with comparisons instead of integer modulo. Fractional interpolation, feedback, original float/double types and free-running phases are unchanged. The exact `rp == size` rounding edge remains handled.

No unison reduction, voice reduction, sample-rate change, skipped effects, lower modulation rate, fast-math or replacement of double with float. CPU gain remains patch- and processor-specific. No claim that every patch gets faster or that 512-frame operation is fixed.

## Validation

Baseline: shipped 0.3.0 `845d88935c02c698907f793c5058bc7f9222018f`, rebuilt in a separate namespace with identical `-O3 -fno-fast-math` flags. `test-engine.sh` checks 2,097,152 wavetable reads over all MIDI notes at 44.1/48 kHz, both table shapes, wrap-edge and random phases. The DSP test checks 22,617,600 channel samples / 76 scenarios bit-for-bit: filters, waveforms, unison, stereo LFOs, FM, detune/sub, low/high registers, sustain, note stealing, automation, patch/rate changes and variable block lengths. It also alternates the optional stage timer path. It is regression against 0.3.0, not proof of identity to every original Synth1 behavior.

Both x86_64 and native ARM64 CI run the same exact tests and 18-scenario benchmark (median of five alternating trials; 48 kHz, 512-frame blocks; warm-up/table creation excluded). Reports include CPU and compiler identity. ARM64 server results are not Tab A9 results and do not include Android audio scheduling, UI, thermal behavior or key-to-audio latency. Reports are uploaded; they are the authority for each execution. Any strict audio failure blocks publication. The existing independently reproduced desktop-only upstream library failure is unchanged and remains explicitly documented in VALIDATION.md.

## Diagnostic changes

`RenderTimings` is an optional render-thread accumulator. Android records voices/LFO and effects/output stage wall times with three monotonic-clock reads per native chunk (maximum 512 frames). It publishes two atomic totals after the callback; the existing overall render timer includes measurement overhead, MIDI queue and output conversion. Host throughput tests omit these timers, while exact tests exercise both paths. Stats indices 0..20 are unchanged; 21/22 hold the stage totals. The UI derives interval percentages from produced audio duration, not CPU time. Concurrent snapshots remain estimates, not a hardware trace.

**Copiar informe + patch** includes the current .sy1 parameters at copy time, distinct from the preceding monitor history. This is local clipboard output only. It enables reproduction of a costly patch without separately locating its file. Favorites, bulk import, routes, buffers and signing identity are unchanged. Install Preview 0.3.1 over Preview 0.3.0; do not uninstall.
