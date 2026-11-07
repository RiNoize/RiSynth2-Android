# RiSynth2 0.3.1 validation results

## What was measured

Direct comparison with shipped 0.3.0 (`845d88935c02c698907f793c5058bc7f9222018f`), using identical `-std=c++20 -O3 -fno-fast-math` flags. Median of five alternating baseline/candidate trials, 48 kHz, 512-frame blocks, after warmup. These are Linux ARM64 server measurements, **not Samsung Tab A9 measurements**, and exclude Android scheduling, audio-driver deadlines, UI, thermal effects and diagnostic timer overhead.

The source delta was applied only after SHA-256 verification of every original and resulting file. The native test report's candidate SHA identifies that run's staging commit, not an unmodified engine. Both runs used the same changed executable sources.

| Scenario | Neoverse-N2 reduction in render time | Neoverse-V3 reduction in render time |
|---|---:|---:|
| Pad, unison 3, 15 active voices | 13.2% | 18.8% |
| Pad, unison 3, 32 active voices | 13.9% | 19.7% |
| Detune + sub, 15 voices | 34.6% | 39.5% |
| Stereo LFO, 32 voices | 39.7% | 43.8% |
| Pulse + detune, 32 voices | 37.5% | 41.2% |
| Saw + detune, 32 voices | 34.1% | 39.5% |
| Sine + detune, 15 voices | 0.4% | -1.9% |
| LPDL + unison, 32 voices | 28.8% | 30.5% |

A negative reduction means slower in that execution. The sine case is effectively unchanged/slightly regressed, so there is no universal speedup claim. Do not interpret a render-time reduction as percentage points of app CPU or extrapolate to the user's patch. The exact user patch has not been supplied.

Both ARM64 jobs passed **2,097,152 exact wavetable reads** and **22,617,600 channel samples across 76 DSP scenarios**, bit-for-bit against 0.3.0. The same expanded comparisons also passed on x86_64. This is regression equivalence under the tested conditions, not proof of every possible patch or identity to original Synth1.

## Provenance

- Neoverse-N2: Actions run `42318906248`, job `128238357970`, artifact `13552271764`; staging `cecbe7419a51f8e7dba95cbe73f4e6eed90db4e7`.
- Neoverse-V3: Actions run `42319581156`, job `128240599515`, artifact `13551709588`; staging `bb8e474dffaa711a824017cb6915f41cabcf4829`.
- Compiler in both native ARM64 jobs: Ubuntu Clang 18.1.3.
- The first run's APK assembly, lint, signature and alignment checks passed; only its later source-publication step was rejected because an Actions token cannot edit workflow files. The second run separates workflow publication from the source commit. No executable source was changed for this correction.

See [OPTIMIZATION031.md](OPTIMIZATION031.md) for algorithm changes, extra table memory, stage-timing overhead and measurement limitations. See [VALIDATION.md](VALIDATION.md) for the separately reproduced pre-existing desktop-library test exception.
