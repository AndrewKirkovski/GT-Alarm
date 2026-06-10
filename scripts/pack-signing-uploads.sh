#!/usr/bin/env bash
# Pack the GT Wake phone signing key for each store's "App Signing" service via
# Google's pepk tool. ONE source key — deveco.p12, alias `gtwake.phone` (RSA),
# cert SHA-256 95:F6:00:1E:... (the Wear Engine pairing identity, verified on the
# release APK) — encrypted to each store's public key, so the app DELIVERED by
# each store keeps that exact cert.
#
# Why Method 2 / "upload your own app signing key" is MANDATORY here: the watch
# only pairs with a phone app whose signing cert == the watch config.json
# `supportLists` value (95:F6:00:...). If a store generates its own key
# (Method 1) and re-signs the delivered AAB, the delivered cert changes and
# Wear Engine pairing breaks.
#
# Two stores, two pepk encryption methods:
#   AppGallery — Huawei's FIXED hex key (--encryptionkey, baked in below).
#   Google Play — Google's per-app RSA public key via --rsa-aes-encryption
#                 --encryption-key-path=<PEM>. Download the PEM from Play Console
#                 -> App integrity -> App signing and save it as
#                 C:/Projects/!keys/gtwake-googleplay-encryption-key.pem.
#
# Usage: scripts/pack-signing-uploads.sh
#   Always packs AppGallery. Also packs Google Play IFF the PEM above exists.
#
# Outputs (encrypted, safe on disk) to C:/Projects/!keys/ with clear names:
#   gtwake-appgallery-app-signing.zip  -> AGC -> App Signing -> Method 2
#   gtwake-googleplay-app-signing.zip  -> Play -> upload your own app signing key
set -euo pipefail

KEYS="/c/Projects/!keys"
PEPK="$KEYS/pepk.jar"
KEYSTORE="$KEYS/deveco.p12"
ALIAS="gtwake.phone"
GOOGLE_PEM="$KEYS/gtwake-googleplay-encryption-key.pem"
EXPECT_SHA="95:F6:00:1E:2B:44:69:2C:CE:6F:E6:66:3A:98:AA:2B:DD:B6:AE:55:7D:B7:7D:BB:A3:46:D0:3D:BC:33:BE:5C"
# Huawei AppGallery's fixed pepk encryption public key (4-byte identity + P256 point).
HUAWEI_KEY="034200041E224EE22B45D19B23DB91BA9F52DE0A06513E03A5821409B34976FDEED6E0A47DBA48CC249DD93734A6C5D9A0F43461F9E140F278A5D2860846C2CF5D2C3C02"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="$SCRIPT_DIR/../android-app/keystore.properties"

# Java + keytool from a full JDK (Android Studio JBR).
if [ -x "${JAVA_HOME:-}/bin/java" ]; then JBIN="$JAVA_HOME/bin"
elif [ -x "/c/Program Files/Android/Android Studio/jbr/bin/java" ]; then JBIN="/c/Program Files/Android/Android Studio/jbr/bin"
else JBIN="$(dirname "$(command -v java)")"; fi
JAVA="$JBIN/java"; KEYTOOL="$JBIN/keytool"

for f in "$PEPK" "$KEYSTORE" "$PROPS"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 1; }
done

# Passwords read from the gitignored keystore.properties at runtime — never
# hardcoded in this script, never echoed to stdout.
STOREPW="$(grep -E '^storePassword=' "$PROPS" | cut -d= -f2-)"
KEYPW="$(grep -E '^keyPassword=' "$PROPS" | cut -d= -f2-)"
[ -n "$STOREPW" ] || { echo "ERROR: storePassword not in keystore.properties" >&2; exit 1; }
[ -n "$KEYPW" ] || KEYPW="$STOREPW"

pack() {  # $1 output  $2 label  $3.. encryption flags (e.g. --encryptionkey=HEX | --rsa-aes-encryption --encryption-key-path=PEM)
  local out="$1" label="$2"; shift 2
  rm -f "$out"
  echo "==> $label"
  "$JAVA" -jar "$PEPK" --keystore "$KEYSTORE" --alias "$ALIAS" \
    --keystore-pass "$STOREPW" --key-pass "$KEYPW" \
    --output "$out" --include-cert "$@"
  [ -f "$out" ] || { echo "    FAILED: $out not produced" >&2; exit 1; }
  # Verify the packed cert is our identity (guards against a wrong alias/keystore).
  local tmp got; tmp="$(mktemp -d)"
  unzip -oq "$out" -d "$tmp"
  got="$("$KEYTOOL" -printcert -file "$tmp/certificate.pem" 2>/dev/null \
        | grep -oE '([0-9A-F]{2}:){31}[0-9A-F]{2}' | tail -1)"
  rm -rf "$tmp"
  if [ "$got" = "$EXPECT_SHA" ]; then
    echo "    OK  $(basename "$out")  ($(stat -c %s "$out") B)  cert=$got"
  else
    echo "    ERROR: packed cert SHA-256 ($got) != expected ($EXPECT_SHA)" >&2
    exit 1
  fi
}

# AppGallery — Huawei fixed hex key.
pack "$KEYS/gtwake-appgallery-app-signing.zip" "AppGallery (Method 2 — upload the key)" \
     --encryptionkey="$HUAWEI_KEY"

# Google Play — per-app RSA public key (PEM), if present.
if [ -f "$GOOGLE_PEM" ]; then
  pack "$KEYS/gtwake-googleplay-app-signing.zip" "Google Play (upload your own app signing key)" \
       --rsa-aes-encryption --encryption-key-path="$GOOGLE_PEM"
else
  echo "==> Google Play SKIPPED — save the per-app public key from"
  echo "    Play Console -> App integrity -> App signing as:"
  echo "    $GOOGLE_PEM   (then re-run)"
fi

echo
echo "Upload each .zip in its console. The resulting store app-signing cert"
echo "MUST read: $EXPECT_SHA"
