#!/usr/bin/env bash
# Checks that the app language persists across process death (features.md section 11): sets Italiano through the
# UI with uiautomator, kills the process with kill -9, starts the app again and finds Italian text on a fresh
# screen dump. Then it sets the language back to Phone. A host script, not instrumentation: an instrumented test
# that kills its own process cannot check what the next process shows.
#   scripts/device-language-check.sh [--serial SERIAL]
# The app must be installed (debug build: run-as needs it) and the device language must not be Italian.
set -euo pipefail
cd "$(dirname "$0")/.."

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) export ANDROID_SERIAL=${2:?--serial needs a serial}; shift 2 ;;
        --help|-h) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

package=org.cassini.android
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Writes the current screen to $work/screen.xml. uiautomator fails while the screen animates, so it tries again.
dump() {
    local _
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        if adb shell uiautomator dump /sdcard/cassini-window.xml > /dev/null 2>&1 \
            && adb exec-out cat /sdcard/cassini-window.xml > "$work/screen.xml" \
            && grep -q '<hierarchy' "$work/screen.xml"; then
            adb shell rm -f /sdcard/cassini-window.xml
            return 0
        fi
        sleep 1
    done
    printf 'uiautomator could not dump the screen.\n' >&2
    return 1
}

# Prints the centre "x y" of the first node whose text or content-desc is $1 (any case: section headers are
# upper case); fails when there is none.
find_node() {
    python3 -I - "$work/screen.xml" "$1" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
label = sys.argv[2].casefold()
for node in ET.parse(sys.argv[1]).iter('node'):
    if label in ((node.get('text') or '').casefold(), (node.get('content-desc') or '').casefold()):
        left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
        print((left + right) // 2, (top + bottom) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

# Waits (up to 20 s) until a node labelled $1 is on screen and prints its centre.
wait_node() {
    local _
    for _ in $(seq 20); do
        dump
        if find_node "$1"; then return 0; fi
        sleep 1
    done
    printf 'Not on screen: %s\n' "$1" >&2
    return 1
}

tap() {
    local point
    point=$(wait_node "$1")
    # shellcheck disable=SC2086 # "x y" is two arguments.
    adb shell input tap $point
}

start() {
    local result
    result=$(adb shell am start -W -n "$package/.MainActivity" | tr -d '\r')
    if [[ "$result" != *'Status: ok'* ]]; then
        printf '%s\nThe app did not start.\n' "$result" >&2
        exit 1
    fi
}

adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am force-stop "$package"
start
wait_node 'Settings' > /dev/null || { printf 'Start with the app in English (Phone language, device not in Italian).\n' >&2; exit 1; }

tap 'Settings'
tap 'Phone'    # the App language row, which names the current choice; it opens the sheet
tap 'Italiano'
wait_node "Lingua dell’app" > /dev/null
printf 'Italiano is set; the activity shows Italian.\n'

pid=$(adb shell pidof "$package" | tr -d '\r')
adb shell run-as "$package" kill -9 "$pid"
for _ in $(seq 20); do
    if [ "$(adb shell pidof "$package" | tr -d '\r')" != "$pid" ]; then break; fi
    sleep 1
done
start
new=$(adb shell pidof "$package" | tr -d '\r')
if [ -z "$new" ] || [ "$new" = "$pid" ]; then
    printf 'The process was not killed and started again (pid %s, now %s).\n' "$pid" "$new" >&2
    exit 1
fi
if ! wait_node 'Impostazioni' > /dev/null && ! wait_node "Lingua dell’app" > /dev/null; then
    printf 'After the kill (pid %s, now %s) the app is not in Italian.\n' "$pid" "$new" >&2
    exit 1
fi
printf 'After kill -9 (pid %s, now %s) the app is still in Italian.\n' "$pid" "$new"

# Back to Phone, from the screen the app shows now (Library or Settings).
dump
if find_node 'Impostazioni' > /dev/null; then tap 'Impostazioni'; fi
tap 'Italiano'    # the App language row opens the sheet
tap 'Telefono'
wait_node 'App language' > /dev/null
printf 'Language check passed; the app language is Phone again.\n'
