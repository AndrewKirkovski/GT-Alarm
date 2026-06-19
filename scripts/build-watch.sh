#!/usr/bin/env bash
# Build LiteWearable HAP + push to /sdcard/haps/ on the connected phone
# with a date-suffixed name. The phone's App Debug Assistant picks it up
# from there and sideloads to the watch via Bluetooth / Huawei Health.
#
# Usage: ./scripts/build-watch.sh [debug|release]   (default: debug)
#
# Requires:
#   - DevEco Studio installed (hvigorw.bat under DevEco Studio/tools/hvigor/bin)
#   - adb on PATH OR at C:/Users/ryots/AppData/Local/Android/Sdk/platform-tools/adb.exe
#   - Phone connected via adb, /sdcard/haps/ folder existing

set -euo pipefail

MODE="${1:-debug}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WATCH_DIR="$REPO_ROOT/watch-app"
HAP_OUT="$WATCH_DIR/entry/build/default/outputs/default/entry-default-signed.hap"
DATE="$(date +%Y-%m-%d-%H%M)"
PHONE_DEST="/sdcard/haps/gt-alarm-watch-${DATE}.hap"

# Resolve hvigorw.bat (Windows wrapper).
HVIGOR="C:/Program Files/Huawei/DevEco Studio/tools/hvigor/bin/hvigorw.bat"
if [ ! -f "$HVIGOR" ]; then
  echo "ERROR: hvigorw.bat not found at $HVIGOR" >&2
  exit 1
fi

# Resolve adb.
ADB="${ADB:-C:/Users/ryots/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
if [ ! -f "$ADB" ] && ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found. Set \$ADB env var or install Android platform-tools." >&2
  exit 1
fi
if [ ! -f "$ADB" ]; then
  ADB="$(command -v adb)"
fi

export DEVECO_SDK_HOME="C:/Program Files/Huawei/DevEco Studio/sdk"
export NODE_HOME="C:/Program Files/Huawei/DevEco Studio/tools/node"
export MSYS_NO_PATHCONV=1   # keep /sdcard/... literal under Git Bash

# ALWAYS full clean build. A fast incremental build can leave the HAP wrapper
# signed against a stale bin, which the watch's bundle manager rejects at
# install time with "Installation failed: 10, internal error" (verified
# 2026-06-18). Cleaning guarantees the LiteJS bin and the HAP are compiled +
# signed together. Worth the extra ~15 s for a sideloadable artifact.
echo "==> Cleaning (forcing full recompile)"
cd "$WATCH_DIR"
"$HVIGOR" clean --no-daemon 2>/dev/null || true
rm -rf "$WATCH_DIR/entry/build" "$WATCH_DIR/entry/.preview" "$WATCH_DIR/.hvigor/cache" 2>/dev/null || true
# A Windows file lock can make `rm -rf` silently fail (rm exits 0 even when it
# could not delete a locked file), leaving a stale entry/build. assembleHap then
# does an incremental no-op and ships a STALE HAP (hit 2026-06-19). Retry once,
# then FAIL LOUD rather than hand back stale bits.
if [ -d "$WATCH_DIR/entry/build" ]; then sleep 1; rm -rf "$WATCH_DIR/entry/build" 2>/dev/null || true; fi
if [ -d "$WATCH_DIR/entry/build" ]; then
  echo "ERROR: entry/build survived the clean (file lock?) — refusing to ship a stale HAP." >&2
  echo "       Close DevEco Studio / kill any lingering hvigor or java, then retry." >&2
  exit 1
fi

echo "==> Building watch HAP (buildMode=$MODE)"
"$HVIGOR" assembleHap --mode module -p product=default -p "buildMode=$MODE" --no-daemon

if [ ! -f "$HAP_OUT" ]; then
  echo "ERROR: build claimed success but HAP not at $HAP_OUT" >&2
  exit 1
fi

SIZE=$(stat -c %s "$HAP_OUT")
echo "==> HAP built: $HAP_OUT ($SIZE bytes)"

# Convert the HAP path to Windows-native form (C:/...) so adb.exe — a
# Windows binary — can stat it. Git Bash hands /c/Projects/... to native
# binaries, which they don't understand.
if command -v cygpath >/dev/null 2>&1; then
  HAP_OUT_WIN="$(cygpath -m "$HAP_OUT")"
else
  HAP_OUT_WIN="$HAP_OUT"
fi

# Verify a phone is connected.
DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {c++} END{print c+0}')
if [ "$DEVICES" -eq 0 ]; then
  echo "WARNING: no adb device connected — skipping push. HAP is at:" >&2
  echo "  $HAP_OUT" >&2
  exit 0
fi

echo "==> Pushing to phone: $PHONE_DEST"
"$ADB" push "$HAP_OUT_WIN" "$PHONE_DEST"

# Prune older HAPs so /sdcard/haps/ doesn't accumulate. Keep only the
# freshest .hap (the one we just pushed). The double-quoted shell snippet
# runs entirely on-device. -name expansion is used because some legacy
# Huawei tools drop files with spaces in the name (e.g.
# "entry-default-signed (5).hap"), which xargs without -0 can't handle.
echo "==> Pruning older HAPs"
"$ADB" shell 'cd /sdcard/haps && newest=$(ls -1t *.hap 2>/dev/null | head -1) && for f in *.hap; do [ "$f" != "$newest" ] && rm "$f" && echo "rm $f"; done; true' || true

echo "==> /sdcard/haps/ on phone now:"
"$ADB" shell "ls -la /sdcard/haps/" || true

echo ""
echo "Next step: open App Debug Assistant on the phone, pick the new HAP,"
echo "sideload it to the GT 6 via Huawei Health."
