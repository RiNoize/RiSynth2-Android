#!/usr/bin/env bash
# Rebuild the previous shipped engine in a separate namespace. Never compare
# against an edited fixture or claim desktop measurements are tablet results.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=450c699c3c44a526cc5e821c4a53fc6529706690
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/source" "$BUILD/baseline" "$ROOT/android/app/build/reports"
if [[ -n "${RISYNTH2_BASELINE_DIR:-}" ]]; then
  cp -R "$RISYNTH2_BASELINE_DIR/EngineCore" "$BUILD/source/"
else
  git -C "$ROOT" archive "$BASE" EngineCore | tar -x -C "$BUILD/source"
fi
python3 - "$BUILD" <<'PY'
import sys
from pathlib import Path
root=Path(sys.argv[1])
for f in (root/'source/EngineCore').glob('*.hpp'):
    (root/'baseline'/f.name).write_text(f.read_text().replace('oxo::s2','reference::s2'))
PY
REPORT="$ROOT/android/app/build/reports/engine-performance.txt"
{
  echo "Reference commit: $BASE"
  echo "Candidate commit: $(git -C "$ROOT" rev-parse HEAD)"
  uname -sm
  ${CXX:-clang++} --version | head -n 1
  echo 'Identical compiler flags: -std=c++20 -O3 -fno-fast-math'
} > "$REPORT"
${CXX:-clang++} -std=c++20 -O3 -fno-fast-math -Wall -Wextra -I"$ROOT" -I"$BUILD" "$ROOT/android/tests/engine_compare.cpp" -o "$BUILD/compare"
"$BUILD/compare" | tee -a "$REPORT"
"$BUILD/compare" --bench | tee -a "$REPORT"
