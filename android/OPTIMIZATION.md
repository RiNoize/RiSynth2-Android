# 0.3.0 — exact-cache optimization and favorites

## Scope

The user reported roughly 80% app CPU/render with a 3-voice-unison pad and five held notes, and about 97% at 32 active voices, even at a requested 2048-frame buffer. The exact third-party patch has not been provided. The synthetic pad benchmark is not that patch and the development host is not the Tab A9.

This release changes execution cost, not patch interpretation: no polyphony reduction, lower sample rate, reduced control-update rate, float-for-double substitution, reduced effects, or fast-math. All existing parameter IDs and ranges remain. Timing is still patch- and device-dependent.

## Engine changes

- Cache ADSR coefficients by sample rate, four controls and sustain law. Trigger, release and live edits still take effect as before.
- Cache per-voice base oscillator frequencies, bend ratio, detune ratios and velocity gain by their exact inputs.
- Compute global pitch-LFO ratios once per sample for all voices, retaining the original summation order and 1/128-octave pitch quantization. Cache exp2 for identical quantized pitch values. Portamento and modulation envelopes remain per voice.
- Cache filter settings only on exact equality. Recalculate at every changed cutoff value; interpolate only the coefficient set used by that filter. No new cutoff approximation is introduced.

No allocation, Java callback, producer lock or file I/O was added to the audio render callback. Cached values are owned by the render thread and invalidated by the inputs they depend on. Waveforms, measured coefficient tables and effects are unchanged.

## Reproducible validation

`bash android/test-engine.sh` builds the engine shipped at `450c699c3c44a526cc5e821c4a53fc6529706690` in a separate namespace, alongside the candidate. Both use `-O3 -fno-fast-math`. It requires bit-identical float output over 19,939,200 channel samples / 67 synthetic scenarios, 44.1 and 48 kHz, all five filters, oscillator waveforms, both LFOs, 3-voice unison, sustain, bend, note stealing, patch changes, every parameter, variable callback lengths, and sample-rate changes. This is regression equivalence to 0.2.0, not proof of complete identity to original Synth1 or every possible patch.

The benchmark reports median elapsed render time of five alternating baseline/candidate trials, 48 kHz and 512-frame blocks. Warmup and table construction are excluded for both. It is not a deadline, output-driver, thermal or key-to-audio latency test. Results are written to `app/build/reports/engine-performance.txt` and uploaded with CI diagnostics. Do not extrapolate percentages to the tablet.

In one development x86_64 run, the synthetic unison-pad cases (15 and 32 active voices) required about 25% less render time, simple filter cases about 6–13% less, detune/sub about 5% less, and LPDL was essentially unchanged (a small regression within the observed run-to-run variation). The exact benchmark report, not these rounded numbers, is authoritative for each build. There is no claim that every patch gets faster.

52 of the 53 original upstream tests pass. The unchanged desktop-only `PatchManagerMoveRenamesAndSwaps` failure is accepted only when reproduced identically on original `48322f7`, with its library/dependencies/tests unchanged. Android uses its separately tested PatchStore, not that desktop library.

## Favorites

`☆ Guardar favorito` takes an atomic JNI snapshot of the selected patch's name and parameter state at tap time. Subsequent browsing cannot substitute a different sound. Saving does not change the selected bank or program. A new directory in the private `.favorites` folder is committed by rename, containing `patch.sy1` and display metadata. Originals are never rewritten. SHA-256 deduplicates identical snapshots; an edited sound with the same name is a separate copy, not an overwrite.

`★ Favoritos` opens only the favorites banks. They are also pinned after INIT in the main bank chooser. More than 128 sounds create additional pages. On-disk names are generated, never derived from untrusted patch names. Patch data remains lazily loaded. Saving refreshes only favorites metadata rather than rescanning all imported banks. Favorites are private app data: uninstalling or clearing storage removes them. Export important selected favorites with Exportar .sy1. Master volume/tempo are not patch parameters and are not stored in .sy1.

Host tests cover 262 snapshots, duplicate presses, changed values with the same name, restart, page boundaries, Unicode/path-like names, lazy loading, source preservation, failed and interrupted writes. Android UI and actual device performance still require tablet testing.
