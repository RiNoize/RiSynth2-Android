# RiSynth2 Android 0.1.0

Independent Android adaptation of the portable Oxo Synth2 C++ engine, with Synth1 `.sy1` compatibility. Not an official Oxo Music or Daichi Laboratory application. Original engine and MIT notices are retained; no original Synth1 binaries, artwork, ROMs or third-party preset banks are bundled.

## First build / installation

GitHub **Actions → Build Android APK → latest successful run → Artifacts → RiSynth2-0.1.0-arm64**. Download and unpack the artifact ZIP, then install the APK on a 64-bit ARM Android device. Development signing only, not a Play Store distribution. The signing key is cached across CI builds; clearing that cache changes the signing identity, so export patches before uninstalling an older build if an update cannot be installed.

For a fork whose Actions are disabled, enable Actions in the repository and use **Run workflow**. A source commit or green host tests alone do not establish that an APK was built: check the build job and APK artifact.

Target device: Samsung Galaxy Tab A9 SM-X110. This is an initial port, not a claim of device-tested performance or audio equivalence. Hardware MIDI, thermal stability, audio routing and practical latency need testing on the tablet.

## Included

- Original `EngineCore/` without DSP changes, including all 99 Synth1 parameters, legacy patch conversion, effects, arpeggiator and polyphony up to 32 voices.
- Native C++ AAudio stereo float output at the actual output device rate. Shared output, optional low-latency request, explicit stop/reopen when changing settings.
- Initial buffer request 512 frames. The UI reports the **actual** buffer and burst; Android may grant a larger buffer. No hidden adaptive buffering.
- Local tablet speaker / wired headphones selected by default, without silently following a USB synth's audio endpoint. Automatic and explicit USB output are optional.
- USB MIDI via Android MIDI service, selectable source/port and channel, note on/off, velocity, sustain, mod wheel, pitch bend, channel pressure, program change and bank select. CC0/32 selects the visible bank index (zero-based); program change selects slots 1–128. Without a Bank Select, Program Change uses the current bank. Empty/out-of-range slots are ignored.
- Native queue between control/MIDI and render. No JNI calls, file reads, locks or allocation in the audio callback. Queue overflow is reported and triggers a note panic.
- `.sy1` / ZIP import through Android's file picker into private app storage, safe staged import, preserved numeric slots, bank / patch buttons and browser. Duplicate imports create separate banks, never overwrite originals.
- Export of the current edited patch, last-session patch/volume/tempo persistence, basic real-time controls and numerical access to every parameter.
- Two-octave multitouch keyboard; hardware QWERTY Z S X D C V G B H N J M for a chromatic octave, Space for sustain. Both landscape orientations and portrait are supported.
- Render timing, peak render cost, voices, actual AAudio xruns, clip samples, queue overflow, sample rate, buffer, selected device and MIDI count.

## First test

Start at low volume, use headphones through the tablet, leave 512 frames and local output selected. INIT is included so a MIDI keyboard or the on-screen keyboard works without importing anything. Import your own Synth1 bank, tap the bank name and choose a patch. Check sustain, pitch bend, bank/patch arrows, then chords with release and effects. Compare the xruns counter before and after. Stop audio before connecting external amplification.

## Deliberate 0.1 limitations

Foreground use only: leaving the app, locking the screen or opening the file picker stops audio, and returning resumes it unless you manually stopped it or lost audio focus. No foreground/background audio service yet. No external MIDI-clock sync, timestamp-accurate MIDI scheduling, MIDI Learn, Bluetooth pairing UI, AU/VST host integration, or complete Synth1-style panel yet. MIDI is applied at the next audio callback. Program/bank selection is based on the current library order, which can change after importing banks.

The render meter is callback wall time divided by available audio time, not whole-app CPU and not measured key-to-sound latency. Output safety clamps non-finite/out-of-range samples; lower master volume if the clip counter rises. No oversampling/quality reduction is introduced into the engine.

Only UTF-8 and Windows-1252 legacy patch-name decoding are implemented here. Other encodings may display incorrect names, without changing numeric synthesis parameters. Import limits: 32 MiB archive, 64 KiB per patch, 4096 patches and 8192 ZIP entries. Export your patches before clearing app data or uninstalling.

## Building locally

JDK 17, Gradle 8.11.1, Android SDK 35 / build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1. Open `android/` in Android Studio or run:

```sh
bash android/test-host.sh
cd android
gradle :app:assembleRelease :app:lintRelease
```

No Apple/VST SDK submodules are required for Android. No Gradle wrapper binary is included; CI provisions the pinned Gradle version. Output: `android/app/build/outputs/apk/release/app-release.apk`.

## Attribution

Oxo Synth2 engine copyright © 2026 Francois Chabloz, MIT (`../LICENSE`). Android adaptation: RiNoize. Synth1 belongs to its respective owner. The root README's Relationship to Synth1 section is retained unchanged. Android has its own name, icon and package `com.rinoize.risynth2`.
