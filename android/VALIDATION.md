# Validation scope

The first CI run passed the Android MIDI parser tests, bounded/atomic ZIP import tests, stereo engine rendering at 44.1 and 48 kHz, sustain/panic smoke checks, and 20,000 multi-producer queue events.

The unchanged upstream Linux test suite reported 52 of 53 tests passing. `PatchManagerMoveRenamesAndSwaps` failed two name-order assertions in the desktop PatchLibrary. Android does not use this class: it uses PatchStore.java and its own import tests.

`test-upstream.sh` treats this specific failure as informational ONLY if EngineCore/EngineCoreTests are byte-for-byte unchanged relative to the original commit, the exact 53-test / one-failure pattern matches, AND running the original baseline reproduces the same assertions. It rejects every other failure and does not modify or skip the original tests.

A successful APK build, lint and signing/alignment verification still do not constitute a tablet playback test. USB MIDI, routing, practical latency, sustained chords, temperature behavior and sound comparison require actual-device testing.

Minimum supported Android version for the first APK: Android 13 (API 33), ARM64. Test signing is not a production release key.
