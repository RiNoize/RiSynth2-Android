#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY_DIR="$HOME/.android/risynth2-ci"
KEY="$KEY_DIR/debug.keystore"
EXPECTED="$ROOT/android/dev-signing-cert.sha256"
mkdir -p "$KEY_DIR"
if [[ ! -f "$KEY" ]]; then
  if [[ -f "$EXPECTED" ]]; then
    echo "::error::Development signing key is missing. Restore the original cache/key; refusing to silently create an incompatible update."
    exit 1
  fi
  keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=RiSynth2 Development,O=RiNoize,C=AR" >/dev/null
fi
ACTUAL="$(keytool -exportcert -keystore "$KEY" -storepass android -alias androiddebugkey | sha256sum | awk '{print $1}')"
if [[ -f "$EXPECTED" ]] && [[ "$ACTUAL" != "$(tr -d '[:space:]' < "$EXPECTED")" ]]; then
  echo "::error::Development signing certificate differs from the pinned public fingerprint."
  exit 1
fi
# Only the public certificate fingerprint is logged/exported. Never archive the key.
echo "Development signer SHA-256: $ACTUAL"
echo "RISYNTH2_KEYSTORE=$KEY" >> "$GITHUB_ENV"
mkdir -p "$ROOT/dist"
printf '%s\n' "$ACTUAL" > "$ROOT/dist/SIGNING-CERT-SHA256.txt"
