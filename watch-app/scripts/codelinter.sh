#!/usr/bin/env bash
# Run Huawei CodeLinter against the watch-app source tree.
# Exits non-zero if any defects are reported.
#
# The codelinter binary ships with DevEco Studio (no separate install).
# `--sdkPath` MUST point at the `default/openharmony` subdir, NOT the bare
# `sdk/` root — otherwise the linter logs "HarmonyOS SDK is not found" and
# silently disables API-version-aware rules.
set -euo pipefail

DEVECO_HOME="${DEVECO_HOME:-/c/Program Files/Huawei/DevEco Studio}"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"

CODELINTER_DIR="$DEVECO_HOME/plugins/codelinter"
SDK_DIR="$DEVECO_HOME/sdk/default/openharmony"
NODE_BIN="$DEVECO_HOME/tools/node"

PATH="$NODE_BIN:$PATH"

LOG_DIR="$PROJECT_DIR/.local"
mkdir -p "$LOG_DIR"

OUTPUT="$(node "$CODELINTER_DIR/index.js" \
  --project "$PROJECT_DIR" \
  --config "$PROJECT_DIR/code-linter.json5" \
  --sdkPath "$SDK_DIR" \
  --workdir "$CODELINTER_DIR" \
  --logPath "$LOG_DIR/codelinter.log" \
  --dir "[\"$PROJECT_DIR/entry/src/main/ets\"]" \
  --sdkNumberVersion 18 \
  --sdkStringVersion "5.1.0" 2>&1)"

# Print full output for visibility
echo "$OUTPUT"

# Fail if any defects are reported
if echo "$OUTPUT" | grep -q '"defects":'; then
  echo ""
  echo "ERROR: codelinter reported defects (see above)" >&2
  exit 1
fi

# Fail if codelinter returned a hard error
if echo "$OUTPUT" | grep -q '"messageType":-1'; then
  echo ""
  echo "ERROR: codelinter encountered a hard error (see above)" >&2
  exit 1
fi

echo ""
echo "codelinter: clean"
