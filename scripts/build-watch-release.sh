#!/usr/bin/env bash
# Build the SIGNED RELEASE .app (App Pack) for Huawei AppGallery submission.
#
# This is the STORE artifact, NOT the dev-loop HAP. Differences from
# build-watch.sh:
#   - product=release  -> signs with the release-gt6-EU provisioning profile
#     (build-profile.json5 signingConfigs[release]), not the debug cert.
#   - assembleApp      -> produces the .app App Pack AppGallery requires,
#     not a sideloadable .hap.
#   - does NOT push to the phone; the .app is uploaded to AppGallery Connect.
#
# The release product is wired to the entry module via build-profile.json5
# (`applyToProducts: ["default", "release"]`).
#
# Usage: ./scripts/build-watch-release.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WATCH_DIR="$REPO_ROOT/watch-app"

# Resolve hvigorw.bat (Windows wrapper).
HVIGOR="C:/Program Files/Huawei/DevEco Studio/tools/hvigor/bin/hvigorw.bat"
if [ ! -f "$HVIGOR" ]; then
  echo "ERROR: hvigorw.bat not found at $HVIGOR" >&2
  exit 1
fi

# Release signing material must exist (referenced by build-profile.json5
# signingConfigs[release]). A debug-signed App Pack is rejected by AppGallery.
REL_PROFILE="$REPO_ROOT/cert/release-gt6-EU.p7b"
REL_CERT="$REPO_ROOT/cert/release-gt6-EU.cer"
for f in "$REL_PROFILE" "$REL_CERT"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: release signing material missing: $f" >&2
    echo "  build-profile.json5 signingConfigs[release] points at it." >&2
    exit 1
  fi
done

export DEVECO_SDK_HOME="C:/Program Files/Huawei/DevEco Studio/sdk"
export NODE_HOME="C:/Program Files/Huawei/DevEco Studio/tools/node"
export MSYS_NO_PATHCONV=1   # keep any /literal paths intact under Git Bash

# ALWAYS full clean build (same rationale as build-watch.sh): an incremental
# build can sign the App Pack against a stale bin. For a store artifact this
# MUST be a clean, fully-recompiled-and-signed build.
echo "==> Cleaning (forcing full recompile)"
cd "$WATCH_DIR"
"$HVIGOR" clean --no-daemon 2>/dev/null || true
rm -rf "$WATCH_DIR/entry/build" "$WATCH_DIR/build" "$WATCH_DIR/entry/.preview" "$WATCH_DIR/.hvigor/cache" 2>/dev/null || true
# See build-watch.sh: a Windows file lock can make `rm -rf` silently fail, leaving
# a stale build dir -> incremental no-op ships a STALE App Pack. Retry once, fail loud.
for d in "$WATCH_DIR/entry/build" "$WATCH_DIR/build"; do
  if [ -d "$d" ]; then sleep 1; rm -rf "$d" 2>/dev/null || true; fi
  if [ -d "$d" ]; then
    echo "ERROR: $d survived the clean (file lock?) — refusing to ship a stale App Pack." >&2
    echo "       Close DevEco Studio / kill any lingering hvigor or java, then retry." >&2
    exit 1
  fi
done

echo "==> Building watch release App Pack (product=release, buildMode=release)"
"$HVIGOR" assembleApp --mode project -p product=release -p "buildMode=release" --no-daemon

# Locate the produced .app (path varies by hvigor version / product name).
APP_OUT="$(find "$WATCH_DIR/build/outputs" -name '*.app' -type f 2>/dev/null | head -1 || true)"
if [ -z "$APP_OUT" ]; then
  echo "ERROR: build finished but no .app found under $WATCH_DIR/build/outputs" >&2
  echo "  Inspect the build output for the App Pack location." >&2
  exit 1
fi

SIZE=$(stat -c %s "$APP_OUT")
echo "==> Release App Pack: $APP_OUT ($SIZE bytes)"
echo ""
echo "Next step: upload this .app to AppGallery Connect (the watch app entry)."
