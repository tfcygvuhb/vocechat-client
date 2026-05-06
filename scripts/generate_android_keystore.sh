#!/usr/bin/env bash
set -euo pipefail
OUT="${1:-android/release/note-upload-key.jks}"
ALIAS="${ANDROID_KEY_ALIAS:-note}"
STORE_PASS="${ANDROID_KEYSTORE_PASSWORD:-change-this-store-password}"
KEY_PASS="${ANDROID_KEY_PASSWORD:-change-this-key-password}"
mkdir -p "$(dirname "$OUT")"
keytool -genkeypair -v \
  -storetype JKS \
  -keystore "$OUT" \
  -storepass "$STORE_PASS" \
  -alias "$ALIAS" \
  -keypass "$KEY_PASS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=note,O=note,C=CN"
echo "Keystore created at $OUT"
echo "Base64 for GitHub secret ANDROID_KEYSTORE_BASE64:"
base64 -w 0 "$OUT" || base64 "$OUT"
