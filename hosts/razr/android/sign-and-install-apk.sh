#!/usr/bin/env bash
# Re-sign a freshly built SideSystem APK with the persistent local keystore on m2air
# and install it in-place (`adb install -r`) so Android never uninstalls the app,
# preserving cover-screen pinning, launcher placement, and Magisk superuser UID.
set -euo pipefail

APK_IN="${1:?Usage: $0 <path-to-SideSystem.apk>}"
KEYSTORE_DIR="${HOME}/.local/share/sidesystem"
KEYSTORE="${KEYSTORE_DIR}/sidesystem.keystore"
APK_OUT="${APK_IN%.apk}-signed.apk"

mkdir -p "$KEYSTORE_DIR"
if [ ! -f "$KEYSTORE" ]; then
    nix shell nixpkgs#jdk21_headless --command keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias sidesystem \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass sidesystem-local-key -keypass sidesystem-local-key \
        -dname "CN=SideSystem, OU=Personal, O=Th0rgal, L=Local, C=FR"
    chmod 600 "$KEYSTORE"
fi

nix shell nixpkgs#apksigner --command apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias sidesystem \
    --ks-pass pass:sidesystem-local-key \
    --key-pass pass:sidesystem-local-key \
    --out "$APK_OUT" \
    "$APK_IN"

nix shell nixpkgs#apksigner --command apksigner verify --print-certs "$APK_OUT" | head -n 5
adb install -r --no-incremental "$APK_OUT"
echo "In-place update complete (no uninstall, cover-screen & Magisk UID preserved)."
