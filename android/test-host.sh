#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
JAVA="$ROOT/android/app/src/main/java/com/rinoize/risynth2"
javac -encoding UTF-8 -d "$BUILD" "$JAVA/MidiByteParser.java" "$JAVA/PatchStore.java" "$ROOT/android/tests/MidiParserTest.java" "$ROOT/android/tests/PatchStoreTest.java"
java -cp "$BUILD" com.rinoize.risynth2.MidiParserTest
java -cp "$BUILD" com.rinoize.risynth2.PatchStoreTest
${CXX:-clang++} -std=c++20 -O2 -Wall -Wextra -pthread -I"$ROOT" "$ROOT/android/tests/engine_smoke.cpp" -o "$BUILD/engine_smoke"
"$BUILD/engine_smoke"
