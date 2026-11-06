# RiSynth2 Android 0.2.0

Independent Android adaptation of the Oxo Synth2 C++ engine, compatible with Synth1 `.sy1` patches. Not an official Oxo Music or Daichi Laboratory application. Original engine and MIT notices are retained. No original Synth1 binaries, artwork, ROMs or third-party preset banks are bundled.

## Install / update

GitHub **Actions → Build Android APK → latest successful run → Artifacts → RiSynth2-0.2.0-arm64**. Unpack the ZIP and install the APK over the previous version. Application ID is unchanged. Development signing is cached between CI builds; clearing that cache changes the signing identity. Do not uninstall or clear app data merely to try an update: private banks and settings would be erased. Back up patches before doing either.

Target: Samsung Galaxy Tab A9 SM-X110, ARM64, Android 13+. The user reported glitches at 512 frames and better results at 1024 in 0.1.0. This release adds measurements, not a claim that those glitches have been fixed. New installations request 1024 frames; an existing explicitly saved setting is kept. Choose **Audio → 1024 frames** for the first comparison. Actual buffer size can differ from the request.

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

Original `EngineCore/` and all 99 parameter indices/ranges are unchanged. Stereo AAudio float output at the actual device rate; shared output, optional low-latency request; explicit stop/reopen for settings. Local tablet speaker/wired output is preferred without silently selecting USB audio. USB MIDI source/port/channel selection, sustain, mod wheel, pitch bend, channel pressure, program change and bank select. CC0/32 selects the visible bank index, zero-based; Program Change selects slots 1–128. Library order can change after import.

INIT, real-time controls, all-parameter editor, patch export and last-session persistence remain. Multitouch keyboard and QWERTY Z S X D C V G B H N J M; Space sustain. Portrait and both landscape orientations. Start at low volume. Export important edited patches before clearing storage/uninstalling.

## Limits / validation

Foreground audio only: leaving/locking the app stops sound. No foreground service, external MIDI-clock synchronization, timestamp-accurate MIDI scheduling, MIDI Learn, Bluetooth pairing UI or full Synth1-style panel yet. Legacy name decoding: UTF-8 and Windows-1252. Import tests do not establish behavior for every Android/cloud document provider.

`bash android/test-host.sh` includes a synthetic 220-bank / 28,160-patch tree, cancellation after staging a bank, nested names, numeric slots/duplicates, invalid files, split banks, lazy reads and 0.1.0 migration. Monitor arithmetic and native callback metrics have deterministic tests. Engine audio smoke tests remain at 44.1 and 48 kHz. See `VALIDATION.md` for the known upstream test exception. CI also builds ARM64 and runs Android lint. Actual CPU, 512/1024 behavior, provider performance and thermal stability still need testing on the tablet.

## Build

JDK 17, Gradle 8.11.1, Android SDK 35 / build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1.

```sh
bash android/test-host.sh
bash android/test-upstream.sh
cd android
gradle :app:assembleRelease :app:lintRelease
```

No Apple/VST SDK submodules are needed. CI provisions the pinned Gradle version and archives the exact tracked sources with each build. No Gradle wrapper binary is included. APK: `android/app/build/outputs/apk/release/app-release.apk`.
