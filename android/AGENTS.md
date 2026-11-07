# Android port contracts

Read ../AGENTS.md and ../LICENSE first. Keep EngineCore and its measured tables unchanged unless a separately justified, tested DSP fix is requested. Do not bundle third-party preset files, original Synth1 artwork or binaries.

Android sources live here; no SwiftUI, JUCE, Oboe or Apple SDK dependency. Java UI/control uses a single control worker for audio start/stop and file operations. NativeEngine serializes JNI entry against destruction. The AAudio callback only consumes EventQueue and renders; it must never acquire producer locks, allocate, do disk I/O or call Java. Construct tables on the control worker before starting audio.

The local speaker/wired-headphone preference must never fall back silently to USB. Report actual sample rate, actual buffer, xruns and requested buffer separately. Render percent is callback wall cost, not CPU or measured latency. Configuration changes stop and reopen the stream. No hidden automatic buffer growth.

Preserve .sy1 indices and native integer ranges. Patch parsing and legacy conversion use EngineCore/Sy1Codec.hpp. UI builds/imports no factory banks beyond InitPatch. ZIP names are display metadata only; output paths are generated locally. Bounded staged import must remain atomic and must not overwrite banks.

Run bash android/test-host.sh, upstream engine tests and Gradle assembleRelease/lintRelease. Green CI is not a hardware test. Do not claim Tab A9 performance or low latency without measurements from the actual device. VersionName, versionCode, workflow artifact naming and README must agree. Production signing is not configured; cached CI signing is development-only.

## 0.2 monitoring / bulk-import contracts

`RenderMetrics.hpp` tracks callback wall nanoseconds, produced frames, count, deadline overruns, interval peak and actual callback frames using lock-free atomics. Stats 0..12 retain the old layout; 13..20 are documented in `DiagnosticMonitor`. Only the diagnostics worker calls stats (it consumes the interval peak). CPU process time comes from Android Process; use actual monotonic sample intervals and keep the >100%-per-core convention. Reset UI baselines without restarting audio. Never conflate OV, AAudio xruns, clipped samples or queue loss.

`DocumentSource` is the SAF adapter; `FolderImporter` is host-testable and stages a whole tree through `PatchStore.ImportBatch`. Source labels never form local paths. Bound traversal/count/bytes/depth, reserve numeric slots, split extra patches, skip/report malformed .sy1, abort and roll back on cancellation/read errors. Folder imports are not limited to the ZIP importer's 4096 patches. Preserve originals and existing private banks. Audio stays stopped during file import. Cancellation is checked before commit, including ZIP imports.

Private banks have `index.tsv` metadata (versioned header, slot and Base64 display name). Scan indexes, not all patch text; migrate old banks once. `Patch.readText()` is worker-only. UI/MIDI program selection queues a load and coalesces stale requests. Keep the library lock short. Large-tree and diagnostics tests belong in test-host.sh. The pinned monitor must remain visible while the instrument panel scrolls.

## Signing / package identity

0.1.0's development signer was not retained (wrong cache path). `-Prisynth2SideBySide=true` builds package `com.rinoize.risynth2.preview`, label RiSynth2 Preview, as a non-destructive parallel install. Do not claim the normal APK can update 0.1.0. Keep Preview ID stable across future Preview updates. JNI namespace remains `com.rinoize.risynth2`. CI supplies RISYNTH2_KEYSTORE from prepare-signing.sh and caches the exact containing directory. Pin/check only the public certificate SHA-256; never upload/commit the private key. A lost pinned key is a build failure, never permission to silently generate a replacement.

## 0.3 exact optimizations / favorites

The user explicitly requested engine optimization. Envelope.hpp, Voice.hpp, Filter.hpp and Synth.hpp now use exact-input memoization and shared global pitch LFOs; all other DSP files/tables are unchanged. `test-engine.sh` compares 67 scenarios bit-for-bit against shipped 0.2.0 (`450c699`) and records a separate host benchmark. Do not replace formulas, lower quality, or loosen equality to make a failing regression pass. Gains are patch-specific; never claim a host benchmark is a Tab A9 measurement.

`FavoriteStore` keeps immutable content-addressed snapshots under the private bank root's hidden `.favorites` directory. Staging+rename commits both patch and name. Repeated identical saves deduplicate; edited copies never overwrite originals. 128-slot pages use stable `:favorites:N` keys and are inserted after INIT. Only the worker performs disk I/O. NativeEngine.snapshot captures matching name+parameter state under one JNI-entry lock at tap time. Saving must not switch the sound or accidentally save the next browsed patch. Do not change the Preview application ID or pinned signer.

## 0.3.1 exact hot-loop optimization

WaveTable caches the original float Hermite polynomial coefficients during preparation; never replace cubic interpolation with linear or remove harmonics. Each sample-rate cache costs an additional ~10.47 MiB at 44.1 kHz or ~11.41 MiB at 48 kHz; the existing shared cache can retain both rates. SharedModulation moves global cutoff/PW/FM weights, amplitude factors and stereo-pan pow() to one calculation per sample; Voice retains the direct-call fallback and offsets it correctly during portamento splits. Specialized oscillator kernels preserve operations and state. Effects ring indices use proven bounded conditional wrap rather than integer division. FM=0 skips only a finite, positive-denominator computation whose result is exactly one.

`test-engine.sh` now compares against shipped 0.3.0 `845d889`, with all-note wavetable tests plus expanded DSP cases. Native ARM64 strict tests are required as well as x86_64. Never claim improvement on the tablet from either server benchmark. Stats 0..20 remain unchanged; 21/22 are cumulative voices/LFO and effects/output wall nanoseconds. These are diagnostic time shares, not CPU utilization. Three timer reads per 512-frame chunk are included in the callback's total render meter. Report copying includes a current patch snapshot, explicitly labelled in the button; no network transmission. Preview/standard APKs go in separate artifacts.
