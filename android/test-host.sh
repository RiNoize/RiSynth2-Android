#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
JAVA="$ROOT/android/app/src/main/java/com/rinoize/risynth2"
javac -encoding UTF-8 -d "$BUILD" "$JAVA/MidiByteParser.java" "$JAVA/PatchStore.java" "$JAVA/FavoriteStore.java" "$JAVA/FolderImporter.java" "$JAVA/DiagnosticMonitor.java" "$ROOT/android/tests/MidiParserTest.java" "$ROOT/android/tests/PatchStoreTest.java" "$ROOT/android/tests/FavoriteStoreTest.java" "$ROOT/android/tests/FolderImporterTest.java" "$ROOT/android/tests/DiagnosticMonitorTest.java"
java -cp "$BUILD" com.rinoize.risynth2.MidiParserTest
java -cp "$BUILD" com.rinoize.risynth2.PatchStoreTest
java -cp "$BUILD" com.rinoize.risynth2.FavoriteStoreTest
java -Xmx192m -cp "$BUILD" com.rinoize.risynth2.FolderImporterTest
java -cp "$BUILD" com.rinoize.risynth2.DiagnosticMonitorTest
${CXX:-clang++} -std=c++20 -O2 -Wall -Wextra -pthread -I"$ROOT" "$ROOT/android/tests/engine_smoke.cpp" -o "$BUILD/engine_smoke"
"$BUILD/engine_smoke"

${CXX:-clang++} -std=c++20 -O2 -Wall -Wextra -I"$ROOT" "$ROOT/android/tests/metrics_test.cpp" -o "$BUILD/metrics_test"
"$BUILD/metrics_test"

${CXX:-clang++} -std=c++20 -O2 -Wall -Wextra -Werror -pthread -I"$ROOT" "$ROOT/android/tests/hints_test.cpp" -o "$BUILD/hints_test"
"$BUILD/hints_test"
