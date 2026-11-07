# RiSynth2 0.3.2 validation

## Scope and provenance

Source is compared with shipped 0.3.1, commit `8200beadd03549cd08ef9148914901d639c81b27`. The feature validation run is `42368009029`, staging commit `7f90843ac84b60dfb99e656980435e17a1a891de`. The staging payload is checked before application and verifies the exact before/after SHA-256 of each source file; the ARM report identifies the staging commit with these deltas applied. The build job records the LPDL and ADPF changes as separate commits. No workflow changes are included in the Actions-token source commits.

Native ARM64 runner: Linux aarch64, Neoverse-N2, Ubuntu Clang 18.1.3, identical `-std=c++20 -O3 -fno-fast-math` flags for reference and candidate. These are host measurements, **not measurements on a Samsung Tab A9**. The offline engine benchmark does not invoke Android performance hints or test audio-device deadlines.

## Exact numerical checks

- 1,689,600 double-precision LPDL output samples: all 128 resonance values, 44.1/48/96 kHz, moving/static/repeated cutoff, jumps, update-threshold and extrapolation edges.
- 2,097,152 wavetable reads, all 128 MIDI notes, saw/triangle, wrap-edge and random phases.
- 22,617,600 channel samples in 76 whole-engine scenarios, including variable blocks, automation, sample-rate changes, sustain, unison, effects and note stealing.

All passed bit-for-bit on the native ARM64 runner and x86_64 build job. This establishes regression equivalence for the tested conditions, not every possible Synth1 patch.

## Focused LPDL benchmark

Median of seven alternating reference/candidate trials, one million filter samples per trial, 48 kHz. Table construction/warmup excluded.

| Scenario | Reference seconds | Candidate seconds | Reduction |
|---|---:|---:|---:|
| Static cutoff, filter only | 0.046772 | 0.046892 | -0.3% |
| Moving cutoff, filter only | 0.203790 | 0.152948 | 24.9% |

A negative reduction means slower in that run. Static-cutoff processing is effectively unchanged. These are **filter-only** timings, not a 24.9% reduction for the complete synthesizer or the user's pad. Whole-engine scenarios were nearly unchanged (-1.0% to +2.0% in that execution); their exact results are in the uploaded `engine-performance.txt`. The unchanged synthesis paths must not be advertised as accelerated.

## ADPF and application checks

The ARM64 job passed the hint-controller/queue/lifecycle tests with address and undefined-behavior sanitizers. A fake Android backend tests real callback-TID registration, target/work durations, unavailable service/session, update/report failures, changed callback size/TID, stale/overflow samples, idle close, stop/restart, concurrent queue transport and a deliberately blocked service. These tests show that simulated service delay does not make callback submission wait. They do not establish actual Android/Samsung service support or effectiveness.

The x86_64 host suite retains MIDI, monitor, render timing, event queue, favorites (262 snapshots) and folder import (220 banks / 28,160 patches) checks. The independently reproduced pre-existing desktop-library test exception remains documented in `VALIDATION.md`; it was not removed or generalized.

The Preview APK is built with versionName 0.3.2 / versionCode 5 and `com.rinoize.risynth2.preview`. Its signing certificate must match `android/dev-signing-cert.sha256`; update over the existing Preview without uninstalling. ARM64 execution on the tablet, ADPF acceptance, sustained thermal behavior and audible stability still require the user's OFF/ON comparison.

See `PERFORMANCE032.md` for implementation details, default-OFF behavior, the explicit 80%-of-callback target, diagnostic limitations and primary Android API references.
