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
# Existing files in dist/ are NEVER overwritten (a shipped artifact must not be
# replaced by a rebuild carrying the same version number). Pass --force to allow it.
#
# Usage:
#   ./scripts/collect-release.sh                 collect both apps' existing outputs
#   ./scripts/collect-release.sh --phone         phone only (skip the watch entirely)
#   ./scripts/collect-release.sh --watch         watch only
#   ./scripts/collect-release.sh --build         build then collect (honours --phone/--watch/--hap)
#   ./scripts/collect-release.sh --hap           ALSO collect the standalone watch HAP candidate
#                                                (explicit opt-in; a missing HAP never fails
#                                                a release without this flag)
#   ./scripts/collect-release.sh --force         allow overwriting existing files in dist/
#
# Output (gitignored): dist/
#   gtwake-phone-<phone versionName>-<versionCode>.apk   (AppGallery / sideload)
#   gtwake-phone-<phone versionName>-<versionCode>.aab   (Google Play)
#   gtwake-watch-<watch version.name>-<version.code>.app (AppGallery Connect)
#   candidates/gtwake-watch-<name>-<code>-candidate-<YYYYMMDD-HHMM>.hap
#     (only with --hap; a CANDIDATE, publish only after independent signature,
#      profile and install verification)
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
DO_HAP=0
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --phone) DO_WATCH=0 ;;
    --watch) DO_PHONE=0 ;;
    --build) DO_BUILD=1 ;;
    --hap) DO_HAP=1 ;;
    --force) FORCE=1 ;;
    *) echo "ERROR: unknown flag '$arg' (expected --phone, --watch, --build, --hap, --force)" >&2; exit 1 ;;
  esac
done
if [ "$DO_PHONE" -eq 0 ] && [ "$DO_WATCH" -eq 0 ]; then
  echo "ERROR: --phone and --watch are mutually exclusive" >&2
  exit 1
fi
if [ "$DO_HAP" -eq 1 ] && [ "$DO_WATCH" -eq 0 ]; then
  echo "ERROR: --hap collects a watch artifact and cannot be combined with --phone" >&2
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
  if [ "$DO_PHONE" -eq 1 ]; then bash "$SCRIPT_DIR/android.sh" release; fi
  if [ "$DO_WATCH" -eq 1 ]; then
    watch_args=()
    if [ "$DO_HAP" -eq 1 ]; then watch_args+=(--hap); fi
    watch_rc=0
    bash "$SCRIPT_DIR/build-watch-release.sh" "${watch_args[@]}" || watch_rc=$?
    # build-watch-release.sh cleans watch-app/build first, so an existing .app here
    # was produced by THIS build.
    if [ "$watch_rc" -eq 2 ] && [ "$DO_HAP" -eq 1 ] \
      && [ -f "$REPO_ROOT/watch-app/build/outputs/release/watch-app-release-signed.app" ]; then
      # Exit 2 with the .app present = only the optional HAP step failed. Keep going
      # so the .app is still collected; the missing HAP is reported below.
      echo "    WARNING: standalone HAP step failed; continuing with the .app." >&2
    elif [ "$watch_rc" -ne 0 ]; then
      exit "$watch_rc"
    fi
  fi
fi

APK_SRC="$REPO_ROOT/android-app/app/build/outputs/apk/release/app-release.apk"
AAB_SRC="$REPO_ROOT/android-app/app/build/outputs/bundle/release/app-release.aab"
APP_SRC="$REPO_ROOT/watch-app/build/outputs/release/watch-app-release-signed.app"
# Only the standalone release-product HAP. Never extract a HAP from APP_SRC: the
# App Pack's embedded HAP has different provisioning.
HAP_SRC_DIR="$REPO_ROOT/watch-app/entry/build/release/outputs"

mkdir -p "$DIST"

copied=0
missing=0
refused=0
copy_one() {
  # $1 = source path, $2 = destination path relative to dist/
  local dest="$DIST/$2"
  if [ ! -f "$1" ]; then
    echo "    MISSING: $1" >&2
    missing=$((missing + 1))
    return 0
  fi
  if [ -e "$dest" ] && [ "$FORCE" -ne 1 ]; then
    echo "    REFUSED (already exists, use --force to overwrite): $2" >&2
    refused=$((refused + 1))
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  cp -f "$1" "$dest"
  printf '    %-62s %12d bytes\n' "$2" "$(stat -c %s "$dest")"
  copied=$((copied + 1))
}

# Read the HAP's own version name/code out of its config.json. A Lite Wearable
# HAP (see build-watch-release.sh --hap) holds one signed .bin instead, with
# config.json stored inside it as plain text, so fall back to that entry.
hap_field() {
  # $1 = hap, $2 = name|code
  # Never fails: an unreadable config.json yields an empty string, and the caller
  # then prints a clear refusal (set -e / pipefail must not abort silently here).
  { { unzip -p "$1" config.json 2>/dev/null || unzip -p "$1" '*.bin' 2>/dev/null; } | tr -d '\r\n' \
    | grep -a -o '"version"[[:space:]]*:[[:space:]]*{[^}]*}' | head -1 \
    | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\\{0,1\\}\\([^\",}[:space:]]*\\)\"\\{0,1\\}.*/\\1/p"; } || true
}

collect_hap() {
  local haps
  mapfile -t haps < <(find "$HAP_SRC_DIR" -type f -name '*-signed.hap' -print 2>/dev/null | sort)
  if [ "${#haps[@]}" -ne 1 ]; then
    echo "    MISSING/AMBIGUOUS: expected exactly one standalone signed HAP under $HAP_SRC_DIR; found ${#haps[@]}" >&2
    missing=$((missing + 1))
    return 0
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "    REFUSED: unzip not found, cannot check the HAP's own version against the manifest" >&2
    refused=$((refused + 1))
    return 0
  fi
  local hname hcode
  hname="$(hap_field "${haps[0]}" name)"
  hcode="$(hap_field "${haps[0]}" code)"
  if [ "$hname" != "$WATCH_NAME" ] || [ "$hcode" != "$WATCH_CODE" ]; then
    echo "    REFUSED: could not read, or does not match, the HAP's own version. HAP config.json says '${hname:-?}' / '${hcode:-?}' but the manifest says '$WATCH_NAME' / '$WATCH_CODE'" >&2
    refused=$((refused + 1))
    return 0
  fi
  copy_one "${haps[0]}" "candidates/gtwake-watch-${WATCH_NAME}-${WATCH_CODE}-candidate-$(date +%Y%m%d-%H%M).hap"
}

echo "==> Collecting into dist/"
if [ "$DO_PHONE" -eq 1 ]; then
  copy_one "$APK_SRC" "gtwake-phone-${PHONE_NAME}-${PHONE_CODE}.apk"
  copy_one "$AAB_SRC" "gtwake-phone-${PHONE_NAME}-${PHONE_CODE}.aab"
fi
if [ "$DO_WATCH" -eq 1 ]; then
  copy_one "$APP_SRC" "gtwake-watch-${WATCH_NAME}-${WATCH_CODE}.app"
  if [ "$DO_HAP" -eq 1 ]; then collect_hap; fi
fi

echo "==> $copied copied, $missing missing, $refused refused  ->  $DIST"
if [ "$missing" -gt 0 ]; then
  echo "    Build the missing artifacts first:" >&2
  if [ "$DO_PHONE" -eq 1 ]; then echo "      ./scripts/android.sh release" >&2; fi
  if [ "$DO_WATCH" -eq 1 ]; then echo "      ./scripts/build-watch-release.sh$([ "$DO_HAP" -eq 1 ] && echo ' --hap')" >&2; fi
  echo "    (or re-run with --build)" >&2
fi
if [ "$missing" -gt 0 ] || [ "$refused" -gt 0 ]; then
  exit 1
fi
