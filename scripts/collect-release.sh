#!/usr/bin/env bash
# Collect the signed release artifacts into dist/ with version-stamped filenames,
# so the upload-ready files live in one obvious place instead of three deep
# build/outputs/ paths.
#
# Each app is stamped with ITS OWN version name, read from its own manifest —
# the phone's from build.gradle.kts, the watch's from config.json. They are NOT
# assumed to match: a release may ship one app only (see CLAUDE.md step 0), in
# which case the other app keeps its previous version and must not be restamped.
#
# WARNING: the filename version comes from the MANIFEST, not from the artifact.
# Run without --build and you will stamp whatever stale binary is still sitting in
# build/outputs/ with the CURRENT version number — e.g. collecting right after a
# version bump but before a release build silently produces a 1.0.8-named file
# containing the 1.0.7 build. Prefer --build for anything you intend to upload.
#
# Usage:
#   ./scripts/collect-release.sh                 collect both apps' existing outputs
#   ./scripts/collect-release.sh --phone         phone only (skip the watch entirely)
#   ./scripts/collect-release.sh --watch         watch only
#   ./scripts/collect-release.sh --build         build then collect (honours --phone/--watch)
#
# Output (gitignored): dist/
#   gtwake-phone-<phone versionName>-<versionCode>.apk   (AppGallery / sideload)
#   gtwake-phone-<phone versionName>-<versionCode>.aab   (Google Play)
#   gtwake-watch-<watch version.name>-<version.code>.app (AppGallery Connect)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST="$REPO_ROOT/dist"

GRADLE="$REPO_ROOT/android-app/app/build.gradle.kts"
WATCH_CFG="$REPO_ROOT/watch-app/entry/src/main/config.json"

# Scope: which apps this release covers. Default both.
DO_PHONE=1
DO_WATCH=1
DO_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --phone) DO_WATCH=0 ;;
    --watch) DO_PHONE=0 ;;
    --build) DO_BUILD=1 ;;
    *) echo "ERROR: unknown flag '$arg' (expected --phone, --watch, --build)" >&2; exit 1 ;;
  esac
done
if [ "$DO_PHONE" -eq 0 ] && [ "$DO_WATCH" -eq 0 ]; then
  echo "ERROR: --phone and --watch are mutually exclusive" >&2
  exit 1
fi

# Each app's own version name + code, from its own manifest. head -1 guards
# against stray matches.
PHONE_NAME="$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
PHONE_CODE="$(sed -n 's/.*versionCode[[:space:]]*=[[:space:]]*\([0-9]\{1,\}\).*/\1/p' "$GRADLE" | head -1)"
WATCH_NAME="$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([0-9][^"]*\)".*/\1/p' "$WATCH_CFG" | head -1)"
WATCH_CODE="$(sed -n 's/.*"code"[[:space:]]*:[[:space:]]*\([0-9]\{1,\}\).*/\1/p' "$WATCH_CFG" | head -1)"

if [ "$DO_PHONE" -eq 1 ] && { [ -z "$PHONE_NAME" ] || [ -z "$PHONE_CODE" ]; }; then
  echo "ERROR: could not parse phone version (name='$PHONE_NAME' code='$PHONE_CODE')" >&2
  exit 1
fi
if [ "$DO_WATCH" -eq 1 ] && { [ -z "$WATCH_NAME" ] || [ -z "$WATCH_CODE" ]; }; then
  echo "ERROR: could not parse watch version (name='$WATCH_NAME' code='$WATCH_CODE')" >&2
  exit 1
fi

if [ "$DO_PHONE" -eq 1 ]; then echo "==> phone v$PHONE_NAME (code $PHONE_CODE)"; fi
if [ "$DO_WATCH" -eq 1 ]; then echo "==> watch v$WATCH_NAME (code $WATCH_CODE)"; fi
if [ "$DO_PHONE" -eq 1 ] && [ "$DO_WATCH" -eq 1 ] && [ "$PHONE_NAME" != "$WATCH_NAME" ]; then
  echo "    NOTE: phone and watch version names differ — expected for a single-app release," >&2
  echo "          but if you meant to ship BOTH, bump them to match first." >&2
fi

if [ "$DO_BUILD" -eq 1 ]; then
  echo "==> Building release artifacts (this takes a few minutes)…"
  [ "$DO_PHONE" -eq 1 ] && bash "$SCRIPT_DIR/android.sh" release
  [ "$DO_WATCH" -eq 1 ] && bash "$SCRIPT_DIR/build-watch-release.sh"
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
if [ "$DO_PHONE" -eq 1 ]; then
  copy_one "$APK_SRC" "gtwake-phone-${PHONE_NAME}-${PHONE_CODE}.apk"
  copy_one "$AAB_SRC" "gtwake-phone-${PHONE_NAME}-${PHONE_CODE}.aab"
fi
if [ "$DO_WATCH" -eq 1 ]; then
  copy_one "$APP_SRC" "gtwake-watch-${WATCH_NAME}-${WATCH_CODE}.app"
fi

echo "==> $copied copied, $missing missing  ->  $DIST"
if [ "$missing" -gt 0 ]; then
  echo "    Build the missing artifacts first:" >&2
  [ "$DO_PHONE" -eq 1 ] && echo "      ./scripts/android.sh release" >&2
  [ "$DO_WATCH" -eq 1 ] && echo "      ./scripts/build-watch-release.sh" >&2
  echo "    (or re-run with --build)" >&2
  exit 1
fi
