# RiSynth2 Android 0.3.3

Independent Android adaptation of the Oxo Synth2 C++ engine, compatible with Synth1 `.sy1` patches. Not an official Oxo Music or Daichi Laboratory application. Original engine and MIT notices are retained. No original Synth1 binaries, artwork, ROMs or third-party preset banks are bundled.

## Install / update

GitHub **Actions → Build Android APK → latest successful run → Artifacts → RiSynth2-0.3.2-preview-arm64**. Preview and standard builds are separate artifacts, each containing one APK. Install Preview 0.3.2 over Preview 0.2.0, 0.3.0 or 0.3.1 without uninstalling or reimporting. **For users of 0.1.0, install `RiSynth2-0.3.2-preview-arm64.apk`.** It appears as **RiSynth2 Preview** and uses `com.rinoize.risynth2.preview`, leaving the original application and its data untouched. Import your bank folders into Preview; private libraries/settings are not automatically shared. Keep 0.1.0 installed until important edited sounds have been exported.

The 0.1.0 workflow attempted to cache a signing-key path that did not exist, so its signing key was not preserved. The normal 0.2.0 APK (`com.rinoize.risynth2`) therefore cannot update that earlier installation in place. Do not uninstall 0.1.0 merely to try it. Preview is the non-destructive alternative and its future updates must retain the Preview package ID.

CI now explicitly provisions/uses `~/.android/risynth2-ci/debug.keystore`, caches that exact directory, and checks `android/dev-signing-cert.sha256` when present. A pinned signer mismatch or missing pinned key stops the build rather than silently rotating it. No private key is uploaded as a build artifact or committed. This remains development signing; a durable owner-managed signing key is needed for production. Do not clear app data: it erases that installation's private banks and settings.

Target: Samsung Galaxy Tab A9 SM-X110, ARM64, Android 13+. The user reported glitches at 512 frames and better results at 1024 in 0.1.0. This release adds measurements, not a claim that those glitches have been fixed. New installations request 1024 frames; an existing explicitly saved setting is kept. Choose **Audio → 1024 frames** for the first comparison. Actual buffer size can differ from the request.

## New in 0.3.3: CPU placement on the DSP callback

**Audio → CPU del hilo de síntesis → Automático / Núcleos de rendimiento.** Automático is the default; switching modes reopens the audio stream while preserving patches, banks and favorites. The same BSD-3-Clause helper as RiMU2000 selects the highest-capacity CPUs **within the callback thread's original allowed affinity** using `cpu_capacity`, or max-frequency only when the capacity data is incomplete. It never assumes CPUs 6/7, forces MHz, changes priority or runs two DSP threads. Errors or missing metadata safely leave the original affinity.

The monitor and copied report now contain CPU mode, owner TID, allowed/requested/effective masks, CPU sampled in the callback, all observed CPUs, criterion and errno. An accepted mask proves placement, not lower computational complexity or fewer XRuns. ADPF remains separate. See [CPU_PLACEMENT_033.md](CPU_PLACEMENT_033.md).

## New in 0.3.2

**Audio → Ayuda de rendimiento (ADPF · experimental)** is an optional, saved OFF/ON setting (initially OFF). The monitor shows whether the actual audio-thread hint session is active or unavailable; active does not guarantee a faster core or fewer glitches. Android service calls run on a separate worker, never in the audio callback. No affinity is forced, no synthesis work is moved to that worker, and no buffer is silently changed. Compare the same patch and buffer, then examine peaks, OV, XRuns and thermal status, not just average CPU. The feature can increase power use/heat.

LPDL now reuses the exact double-precision logarithms of its fixed coefficient table, without changing the sound or quality. This targets LPDL parameter changes, not every patch. See [PERFORMANCE032.md](PERFORMANCE032.md) for the two independent changes, failure handling, headroom target, tests and comparison procedure. Install Preview 0.3.2 **over** your existing Preview, without uninstalling.

## Features retained from 0.3.x

**☆ Guardar favorito** saves an independent .sy1 copy with the current parameter edits. Repeated presses do not duplicate an identical snapshot; changed settings create a new copy even with the same name. **★ Favoritos** opens the collection, also pinned after INIT in the bank list. Collections larger than 128 sounds are paged automatically. Saving leaves the source and current program untouched. Favorites are private app data; export important patches before uninstalling/clearing storage.

The engine now reuses exact calculations for envelopes, tuning and static filters, and shares global pitch-LFO work between voices. No lower-quality mode or reduced polyphony is used. Automated tests compare output sample by sample against 0.2.0. Performance is patch-dependent and needs tablet verification: see [OPTIMIZATION.md](OPTIMIZATION.md). Increasing the buffer alone does not reduce the synthesis work per second.

Install the **Preview 0.3.2** APK over **Preview 0.2.0/0.3.x** without uninstalling: package ID and pinned signing certificate are retained. It remains separate from the old 0.1.0 application.

## Always-visible audio / CPU monitor

The compact monitor is pinned above the scrolling controls. Tap it to open details, **Copiar informe** or **Reiniciar**. It samples nominally every 500 ms, on a separate diagnostics worker; interval arithmetic uses actual elapsed time. Copy includes up to 120 recent samples, settings, patch name, MIDI status and device/version. Reset does not interrupt audio. Reopening audio starts a new monitoring session.

- **CPU app**: process CPU-time delta divided by monotonic elapsed wall time. 100% means one fully occupied core; values above 100% are possible. This is not whole-device CPU utilization.
- **Render**: measured callback wall-time delta divided by the duration of the audio frames produced, plus interval and since-reset peaks. It includes scheduling interruptions during the measured work, so it is not the same as CPU time.
- **OV / overruns**: callbacks whose measured work exceeds `callback frames / sample rate`. Includes total, rate per actual second and proportion of callbacks. The callback's frame count, not the output-buffer setting, defines this budget.
- **XRuns / underruns**: AAudio's reported output underrun counter, with newly reported events. `n/d` means unsupported/error. This is distinct from OV: extra buffering can absorb an overrun, and scheduling can cause underruns without expensive DSP. Driver counters need not report every audible glitch.
- Active voices, clipped/non-finite samples, MIDI/control queue drops, actual sample rate, requested/actual buffer/capacity, burst, last callback size, output device, performance mode, error and Android thermal status.

Buffer duration is explicitly **not** measured key-to-sound latency. Thermal status is an Android severity level, not a temperature in degrees. Native counter snapshots are concurrent samples, not a hardware trace. The audio callback does not allocate, perform I/O, acquire producer locks or call Java. Diagnostics do not alter DSP quality or adapt the buffer silently.

## Import a whole collection

**Importar → Carpeta completa (incluye subcarpetas)** → choose the folder containing all your banks → **Usar esta carpeta**. No ZIP creation or individual patch selection is necessary. Android may prevent selection of storage/Downloads roots; choose the actual bank folder inside them.

A directory containing `.sy1` files becomes a bank; nested names are preserved in labels such as `Colección / Autor / Pads`. `.sy1` files directly inside the selected root are included. Numeric slots 001–128 are reserved before duplicate-numbered or unnumbered files use free slots. More than 128 patches are split into numbered bank parts, without discarding the extra sounds. Uppercase `.SY1` is accepted. Hidden folders and non-`.sy1` files are skipped; ZIPs inside a tree are not automatically expanded.

Import shows progress and supports cancellation. The whole tree is staged privately and committed by one rename. Cancellation, permission/read errors and global limits do not install a half-imported tree or overwrite any existing bank. Invalid `.sy1` files with readable contents are omitted and reported with counts and example names. Originals are only read, never changed. Audio is paused during import and resumes afterward only if it was wanted and the app is foregrounded.

Folder limits: 131,072 candidate patches, 200,000 entries, depth 32, 256 MiB patch bytes, 64 KiB per patch. These are safety limits, not a device performance guarantee. Only one bank's sound text is held during copying. Banks are indexed for fast subsequent scans; full patch data is read by the control worker only when selected. Existing 0.1.0 banks are indexed without rewriting their patch files. The bank chooser has search.

**Archivos .sy1 / un banco ZIP** also supports selecting several `.sy1` files together, grouping them rather than creating a bank for each file. A single ZIP uses the original ZIP importer (32 MiB archive/expanded patch bytes, 4,096 patches, 8,192 entries). Select ZIP files one at a time; use folder import for hundreds of unpacked banks.

## Existing features retained

All 99 parameter indices/ranges are unchanged. Engine calculations are now memoized as documented in OPTIMIZATION.md; measured tables and synthesis formulas are retained. Stereo AAudio float output at the actual device rate; shared output, optional low-latency request; explicit stop/reopen for settings. Local tablet speaker/wired output is preferred without silently selecting USB audio. USB MIDI source/port/channel selection, sustain, mod wheel, pitch bend, channel pressure, program change and bank select. CC0/32 selects the visible bank index, zero-based; Program Change selects slots 1–128. Library order can change after import.

INIT, real-time controls, all-parameter editor, patch export and last-session persistence remain. Multitouch keyboard and QWERTY Z S X D C V G B H N J M; Space sustain. Portrait and both landscape orientations. Start at low volume. Export important edited patches before clearing storage/uninstalling.

## Limits / validation

Foreground audio only: leaving/locking the app stops sound. No foreground service, external MIDI-clock synchronization, timestamp-accurate MIDI scheduling, MIDI Learn, Bluetooth pairing UI or full Synth1-style panel yet. Legacy name decoding: UTF-8 and Windows-1252. Import tests do not establish behavior for every Android/cloud document provider.

`bash android/test-host.sh` includes a synthetic 220-bank / 28,160-patch tree, cancellation after staging a bank, nested names, numeric slots/duplicates, invalid files, split banks, lazy reads and 0.1.0 migration. Monitor arithmetic and native callback metrics have deterministic tests. Engine audio smoke tests remain at 44.1 and 48 kHz. See `VALIDATION.md` for the known upstream test exception. CI also builds ARM64 and runs Android lint. Actual CPU, 512/1024 behavior, provider performance and thermal stability still need testing on the tablet.

## Build

JDK 17, Gradle 8.11.1, Android SDK 35 / build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1.

```sh
bash android/test-host.sh
bash android/test-engine.sh
bash android/test-upstream.sh
cd android
gradle -Prisynth2SideBySide=true :app:assembleRelease :app:lintRelease
```

No Apple/VST SDK submodules are needed. CI provisions the pinned Gradle version and archives the exact tracked sources with each build. No Gradle wrapper binary is included. APK: `android/app/build/outputs/apk/release/app-release.apk`.


## 0.3.1 per-sample optimization

See `OPTIMIZATION031.md`. This is an additional exact optimization relative to shipped 0.3.0, not reuse of its comparison against 0.2.0. Both native ARM64 and x86_64 tests must pass before publishing. No claim of Tab A9 performance until measured there. The monitor details now separate voices/LFO from effects/output wall time. **Copiar informe + patch** includes the current .sy1 snapshot so a difficult sound can be reproduced; it only copies to your clipboard, never uploads anything. Preview/standard APKs have the same DSP and differ in Android package identity. CI packages them in separate downloads.
