#!/usr/bin/env bash
# Linux/macOS: requires JDK 17+, Android SDK 35, curl, unzip and sha256sum/shasum.
set -euo pipefail
cd "$(dirname "$0")"
command -v java >/dev/null || { echo 'Install JDK 17+.' >&2; exit 1; }
if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" && ! -f local.properties ]]; then
    echo 'Set ANDROID_HOME to your Android SDK (platform android-35).' >&2
    exit 1
fi
VERSION=8.9
CACHE="$PWD/.gradle-distributions"
GRADLE="$CACHE/gradle-$VERSION/bin/gradle"
if [[ ! -x "$GRADLE" ]]; then
    mkdir -p "$CACHE"
    ZIP="$CACHE/gradle-$VERSION-bin.zip"
    URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
    curl --fail --location --connect-timeout 15 "$URL" -o "$ZIP"
    EXPECTED="$(curl --fail --location --silent --show-error "$URL.sha256" | tr -d '\r\n')"
    [[ "$EXPECTED" =~ ^[[:xdigit:]]{64}$ ]] || { echo 'Bad checksum response' >&2; exit 1; }
    if command -v sha256sum >/dev/null; then
        ACTUAL="$(sha256sum "$ZIP" | cut -d ' ' -f 1)"
    else
        ACTUAL="$(shasum -a 256 "$ZIP" | cut -d ' ' -f 1)"
    fi
    [[ "$ACTUAL" == "$EXPECTED" ]] || { echo 'Gradle SHA256 mismatch' >&2; exit 1; }
    unzip -q -o "$ZIP" -d "$CACHE"
    rm "$ZIP"
fi
"$GRADLE" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/MikroTikFirewallControl-v0.2.10-debug.apk
echo "APK: $PWD/dist/MikroTikFirewallControl-v0.2.10-debug.apk"
