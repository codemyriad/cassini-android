#!/usr/bin/env bash
# Install a same-key APK over the app. Wait for Android's fully-drawn event after startup and check for a crash.
#   scripts/launch-check.sh APK
set -euo pipefail
apk=${1:?Usage: scripts/launch-check.sh APK}
test -f "$apk"
repo_root=$(cd "$(dirname "$0")/.." && pwd)
sdk_path=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk_path" ]; then
    sdk_path=$(python3 -I - "$repo_root/local.properties" <<'PY'
from pathlib import Path
import sys
props = Path(sys.argv[1])
if not props.is_file():
    raise SystemExit("Set ANDROID_HOME or sdk.dir in local.properties.")
print(next(line.removeprefix("sdk.dir=") for line in props.read_text().splitlines() if line.startswith("sdk.dir=")))
PY
    )
fi
package=$("$sdk_path/build-tools/36.0.0/aapt" dump xmltree "$apk" AndroidManifest.xml | python3 -I -c '
import re, sys
match = re.search(r"^\s*A: package=\"([^\"]+)\"", sys.stdin.read(), re.M)
if not match:
    raise SystemExit("Could not read package name from AndroidManifest.xml.")
print(match.group(1))
')
component=$package/io.codemyriad.cassini.MainActivity
adb install -r "$apk"
adb shell am force-stop "$package"

# Use the timestamp of a unique marker to exclude earlier launches, without clearing the device logs.
marker="cassini-launch-$$-$RANDOM"
adb shell log -p i -t CassiniLaunchCheck "$marker"
since=$(adb logcat -d -b main -v epoch -v usec -e "^$marker$" -s CassiniLaunchCheck:I |
    awk '$1 ~ /^[0-9]+\.[0-9]+$/ { print $1 }')
if [ -z "$since" ]; then
    printf 'Could not find the launch marker in logcat.\n' >&2
    exit 1
fi
start=$(adb shell am start -W -n "$component" | tr -d '\r')
printf '%s\n' "$start"
if [[ "$start" != *'Status: ok'* ]]; then
    printf 'The app did not start.\n' >&2
    exit 1
fi

if ! python3 -I - "$since" "$package" "$component" <<'PY'
import re
import subprocess
import sys

since, package, component = sys.argv[1:]
fully_drawn = f"Fully drawn {component}:"
crashed = f"Process: {package}, PID:"
pattern = f"^({re.escape(fully_drawn)}|{re.escape(crashed)})"
try:
    result = subprocess.run(
        ["adb", "logcat", "-b", "main", "-b", "system", "-b", "crash", "-v", "raw",
         "-T", since, "-e", pattern, "-m", "1"],
        check=True, stdout=subprocess.PIPE, text=True, timeout=45,
    )
except subprocess.TimeoutExpired:
    sys.exit("Android did not report that the app was fully drawn.")
print(result.stdout, end="")
if not any(line.startswith(fully_drawn) for line in result.stdout.splitlines()):
    sys.exit("The app crashed before Android reported it fully drawn.")
PY
then
    adb logcat -d -b crash -T "$since" >&2 || true
    exit 1
fi
crash=$(adb logcat -d -b crash -T "$since" | tr -d '\r' | grep -F "Process: $package, PID:" || true)
if [ -n "$crash" ] || [ -z "$(adb shell pidof "$package" | tr -d '\r')" ]; then
    adb logcat -d -b crash -T "$since" >&2 || true
    printf 'The app crashed or exited after launch.\n' >&2
    exit 1
fi
printf 'The app started and is running.\n'
