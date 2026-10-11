#!/usr/bin/env bash
# Install an APK over the installed app (same signing key and version, so app data stays) and check
# that the app starts and the process is still running a few seconds later, without a crash.
#   scripts/launch-check.sh APK
set -euo pipefail
apk=${1:?Usage: scripts/launch-check.sh APK}
package=org.cassini.android
adb install -r "$apk"
adb shell am force-stop "$package"
# Read only the log lines after this point. Some emulators (API 26) cannot clear the log ("failed to clear
# the 'main' log"), so the clear is best effort and -T limits the read to lines after the device time below.
since=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
adb logcat -c 2>/dev/null || printf 'Could not clear the log; reading lines after %s.\n' "$since" >&2
start=$(adb shell am start -W -n "$package/.MainActivity" | tr -d '\r')
printf '%s\n' "$start"
if [[ "$start" != *'Status: ok'* ]]; then
    printf 'The app did not start.\n' >&2
    exit 1
fi
sleep 5
crash=$(adb logcat -d -b crash -T "$since" | tr -d '\r' | grep -F "Process: $package" || true)
if [ -n "$crash" ] || [ -z "$(adb shell pidof "$package" | tr -d '\r')" ]; then
    adb logcat -d -b crash -T "$since" >&2 || true
    printf 'The app crashed or exited after launch.\n' >&2
    exit 1
fi
printf 'The app started and is running.\n'
