# Android port contracts

Read ../AGENTS.md and ../LICENSE first. Keep EngineCore and its measured tables unchanged unless a separately justified, tested DSP fix is requested. Do not bundle third-party preset files, original Synth1 artwork or binaries.

Android sources live here; no SwiftUI, JUCE, Oboe or Apple SDK dependency. Java UI/control uses a single control worker for audio start/stop and file operations. NativeEngine serializes JNI entry against destruction. The AAudio callback only consumes EventQueue and renders; it must never acquire producer locks, allocate, do disk I/O or call Java. Construct tables on the control worker before starting audio.

The local speaker/wired-headphone preference must never fall back silently to USB. Report actual sample rate, actual buffer, xruns and requested buffer separately. Render percent is callback wall cost, not CPU or measured latency. Configuration changes stop and reopen the stream. No hidden automatic buffer growth.

Preserve .sy1 indices and native integer ranges. Patch parsing and legacy conversion use EngineCore/Sy1Codec.hpp. UI builds/imports no factory banks beyond InitPatch. ZIP names are display metadata only; output paths are generated locally. Bounded staged import must remain atomic and must not overwrite banks.

Run bash android/test-host.sh, upstream engine tests and Gradle assembleRelease/lintRelease. Green CI is not a hardware test. Do not claim Tab A9 performance or low latency without measurements from the actual device. VersionName, versionCode, workflow artifact naming and README must agree. Production signing is not configured; cached CI signing is development-only.
