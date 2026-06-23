#!/usr/bin/env bash
# Collect the signed release artifacts into dist/ with version-stamped filenames,
# so the upload-ready files live in one obvious place instead of three deep
# build/outputs/ paths. versionName is read from the phone build.gradle.kts
# (it's the shared version); the per-app integer codes go in the filenames too.
#
# Usage:
#   ./scripts/collect-release.sh           collect existing release build outputs
#   ./scripts/collect-release.sh --build   build BOTH apps' release artifacts first, then collect
#
# Output (gitignored): dist/
#   gtwake-phone-<versionName>-<versionCode>.apk   (AppGallery / sideload)
#   gtwake-phone-<versionName>-<versionCode>.aab   (Google Play)
#   gtwake-watch-<versionName>-<version.code>.app  (AppGallery Connect)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST="$REPO_ROOT/dist"

GRADLE="$REPO_ROOT/android-app/app/build.gradle.kts"
WATCH_CFG="$REPO_ROOT/watch-app/entry/src/main/config.json"

# versionName (shared) + phone versionCode from the gradle defaultConfig;
# watch version.code from config.json. head -1 guards against stray matches.
VERSION_NAME="$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
PHONE_CODE="$(sed -n 's/.*versionCode[[:space:]]*=[[:space:]]*\([0-9]\{1,\}\).*/\1/p' "$GRADLE" | head -1)"
WATCH_CODE="$(sed -n 's/.*"code"[[:space:]]*:[[:space:]]*\([0-9]\{1,\}\).*/\1/p' "$WATCH_CFG" | head -1)"

if [ -z "$VERSION_NAME" ] || [ -z "$PHONE_CODE" ] || [ -z "$WATCH_CODE" ]; then
  echo "ERROR: could not parse versions (name='$VERSION_NAME' phone='$PHONE_CODE' watch='$WATCH_CODE')" >&2
  exit 1
fi

echo "==> Release v$VERSION_NAME  (phone code $PHONE_CODE, watch code $WATCH_CODE)"

if [ "${1:-}" = "--build" ]; then
  echo "==> Building release artifacts for both apps (this takes a few minutes)…"
  bash "$SCRIPT_DIR/android.sh" release
  bash "$SCRIPT_DIR/build-watch-release.sh"
fi

APK_SRC="$REPO_ROOT/android-app/app/build/outputs/apk/release/app-release.apk"
AAB_SRC="$REPO_ROOT/android-app/app/build/outputs/bundle/release/app-release.aab"
APP_SRC="$REPO_ROOT/watch-app/build/outputs/release/watch-app-release-signed.app"

mkdir -p "$DIST"

copied=0
missing=0
copy_one() {
  # $1 = source path, $2 = destination filename (under dist/)
  if [ -f "$1" ]; then
    cp -f "$1" "$DIST/$2"
    printf '    %-46s %12d bytes\n' "$2" "$(stat -c %s "$DIST/$2")"
    copied=$((copied + 1))
  else
    echo "    MISSING: $1" >&2
    missing=$((missing + 1))
  fi
}

echo "==> Collecting into dist/"
copy_one "$APK_SRC" "gtwake-phone-${VERSION_NAME}-${PHONE_CODE}.apk"
copy_one "$AAB_SRC" "gtwake-phone-${VERSION_NAME}-${PHONE_CODE}.aab"
copy_one "$APP_SRC" "gtwake-watch-${VERSION_NAME}-${WATCH_CODE}.app"

echo "==> $copied copied, $missing missing  ->  $DIST"
if [ "$missing" -gt 0 ]; then
  echo "    Build the missing artifacts first:" >&2
  echo "      ./scripts/android.sh release && ./scripts/build-watch-release.sh" >&2
  echo "    (or re-run: ./scripts/collect-release.sh --build)" >&2
  exit 1
fi
