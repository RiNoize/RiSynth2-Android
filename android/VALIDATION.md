# Validation scope

The first CI run passed the Android MIDI parser tests, bounded/atomic ZIP import tests, stereo engine rendering at 44.1 and 48 kHz, sustain/panic smoke checks, and 20,000 multi-producer queue events.

The unchanged upstream Linux test suite reported 52 of 53 tests passing. `PatchManagerMoveRenamesAndSwaps` failed two name-order assertions in the desktop PatchLibrary. Android does not use this class: it uses PatchStore.java and its own import tests.

`test-upstream.sh` treats this specific failure as informational ONLY if EngineCore/EngineCoreTests are byte-for-byte unchanged relative to the original commit, the exact 53-test / one-failure pattern matches, AND running the original baseline reproduces the same assertions. It rejects every other failure and does not modify or skip the original tests.

A successful APK build, lint and signing/alignment verification still do not constitute a tablet playback test. USB MIDI, routing, practical latency, sustained chords, temperature behavior and sound comparison require actual-device testing.

Minimum supported Android version for the first APK: Android 13 (API 33), ARM64. Test signing is not a production release key.

## 0.2.0 additions

Host tests additionally exercise a synthetic tree of 220 banks / 28,160 .sy1 patches under a 192 MiB Java heap, nested/root directories, numeric-slot reservations with duplicate numbers, ignored files and malformed patches, alias/cycle protection, 260-patch bank splitting, rollback after a bank has been staged, duplicate-import safety, lazy patch reads and migration of 0.1.0 banks. Separate deterministic tests cover process CPU percentages above 100%, nonuniform sample intervals, render-budget percentages, OV rates/resets, unsupported xrun readings, bounded diagnostics history and callback sizes distinct from the output buffer. These tests do not simulate every SAF document provider or establish audio performance on ARM hardware.

The user's 0.1.0 report (glitches with 512 frames; improved at 1024) is a device observation, not a benchmark of 0.2.0. No synthesis algorithms or quality settings were changed in 0.2.0.

## Signing continuity

The first 0.2.0 build revealed a different signing certificate from the delivered 0.1.0 APK. CI logs confirmed the original cache path did not exist, so no signing cache was saved. The 0.2.0 Preview package installs alongside 0.1.0 without touching its data; it is not an in-place migration. Both current variants use an explicit cached development keystore, with a public fingerprint guard. Compare fingerprints before claiming that any APK can replace an installed one.
