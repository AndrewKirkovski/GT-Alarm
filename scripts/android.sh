#!/usr/bin/env bash
# Android build / verify helper. Wraps the long-form gradle invocations so
# the agent (and humans) only need to approve ONE command pattern instead
# of memorizing JAVA_HOME + GRADLE_OPTS + --max-workers flags each time.
#
# Usage from repo root or anywhere:
#   scripts/android.sh build        # :app:assembleDebug
#   scripts/android.sh lint         # :app:lintDebug
#   scripts/android.sh detekt       # :app:detektDebug
#   scripts/android.sh test         # :app:testDebugUnitTest
#   scripts/android.sh check        # lint + detekt + test (default)
#   scripts/android.sh all          # build + check
#   scripts/android.sh release      # signed assembleRelease (APK) + bundleRelease (AAB)
#   scripts/android.sh raw <task>   # passthrough — e.g. raw :app:dependencies
#
# Env overrides (rarely needed):
#   JAVA_HOME       Defaults to Android Studio's bundled JBR if unset
#   GRADLE_OPTS     Defaults to -Xmx2g -XX:MaxMetaspaceSize=512m
#   MAX_WORKERS     Defaults to 1 (low-memory hosts)
set -euo pipefail

# Locate the android-app/ directory relative to this script so the script
# works regardless of caller cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$REPO_ROOT/android-app"

# Force JAVA_HOME to Android Studio's bundled JBR — full JDK 17 with jlink.
# Override even if the caller has JAVA_HOME pre-set (typically a JDK 11 for
# DevEco / watch builds): AGP 9 + KSP need JDK 17, and DevEco's own JBR is
# a JRE that fails compileDebugJavaWithJavac with "jlink executable does
# not exist". See android-app's CLAUDE.md "Android build environment
# gotcha" section. Set ANDROID_JAVA_HOME to override the override.
if [[ -n "${ANDROID_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$ANDROID_JAVA_HOME"
elif [[ -d "/c/Program Files/Android/Android Studio/jbr" ]]; then
    export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
elif [[ -d "C:/Program Files/Android/Android Studio/jbr" ]]; then
    export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
else
    echo "WARN: Android Studio JBR not found at default path; falling back to caller's JAVA_HOME=$JAVA_HOME" >&2
fi

export GRADLE_OPTS="${GRADLE_OPTS:--Xmx2g -XX:MaxMetaspaceSize=512m}"
MAX_WORKERS="${MAX_WORKERS:-1}"

# Resolve tasks from short command name.
cmd="${1:-check}"
case "$cmd" in
    build)   TASKS=":app:assembleDebug" ;;
    lint)    TASKS=":app:lintDebug" ;;
    detekt)  TASKS=":app:detektDebug" ;;
    test)    TASKS=":app:testDebugUnitTest" ;;
    check)   TASKS=":app:lintDebug :app:detektDebug :app:testDebugUnitTest" ;;
    all)     TASKS=":app:assembleDebug :app:lintDebug :app:detektDebug :app:testDebugUnitTest" ;;
    release) TASKS=":app:assembleRelease :app:bundleRelease" ;;
    raw)
        shift
        if [[ $# -eq 0 ]]; then
            echo "usage: $0 raw <gradle-task> [...]" >&2
            exit 2
        fi
        TASKS="$*"
        ;;
    -h|--help|help|"")
        sed -n '2,18p' "$0" | sed 's/^# \?//'
        exit 0
        ;;
    *)
        echo "unknown command: $cmd" >&2
        echo "valid: build | lint | detekt | test | check | all | release | raw <task>" >&2
        exit 2
        ;;
esac

echo "==> android.sh: $cmd"
echo "    JAVA_HOME=$JAVA_HOME"
echo "    tasks=$TASKS"
echo "    workers=$MAX_WORKERS"
echo

cd "$APP_DIR"

# Release builds MUST be signed with the shared .p12 (its SHA-256 is the
# AGConnect-registered pairing identity). build.gradle.kts only creates the
# signing config when keystore.properties is present; without it AGP
# silently emits an UNSIGNED release APK. Fail loudly instead.
if [[ "$cmd" == "release" && ! -f "$APP_DIR/keystore.properties" ]]; then
    echo "ERROR: release build needs android-app/keystore.properties" >&2
    echo "  (storeFile/storeType/keyAlias + passwords; passwords may instead" >&2
    echo "   come from KEYSTORE_PASSWORD / KEY_PASSWORD env vars)." >&2
    exit 3
fi

# shellcheck disable=SC2086
./gradlew $TASKS --max-workers="$MAX_WORKERS" --no-daemon

if [[ "$cmd" == "release" ]]; then
    echo
    echo "==> Release artifacts:"
    apk="$APP_DIR/app/build/outputs/apk/release/app-release.apk"
    aab="$APP_DIR/app/build/outputs/bundle/release/app-release.aab"
    [[ -f "$apk" ]] && echo "    APK (AppGallery / sideload): $apk ($(stat -c %s "$apk") bytes)"
    [[ -f "$aab" ]] && echo "    AAB (Google Play):           $aab ($(stat -c %s "$aab") bytes)"
    if [[ ! -f "$apk" && ! -f "$aab" ]]; then
        echo "    (no artifacts found — check the gradle output above)" >&2
    fi
fi
