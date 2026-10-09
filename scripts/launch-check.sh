#!/usr/bin/env bash
# Install an APK over the installed app (same signing key and version, so app data stays) and check
# that the library screen starts and the process is still running a few seconds later, without a crash.
#   scripts/launch-check.sh APK
set -euo pipefail
apk=${1:?Usage: scripts/launch-check.sh APK}
package=org.cassini.android
adb install -r "$apk"
adb shell am force-stop "$package"
adb logcat -c
start=$(adb shell am start -W -n "$package/.LibraryActivity" | tr -d '\r')
printf '%s\n' "$start"
if [[ "$start" != *'Status: ok'* ]]; then
    printf 'The app did not start.\n' >&2
    exit 1
fi
sleep 5
crash=$(adb logcat -d -b crash | tr -d '\r' | grep -F "Process: $package" || true)
if [ -n "$crash" ] || [ -z "$(adb shell pidof "$package" | tr -d '\r')" ]; then
    adb logcat -d -b crash >&2 || true
    printf 'The app crashed or exited after launch.\n' >&2
    exit 1
fi
printf 'The app started and is running.\n'
