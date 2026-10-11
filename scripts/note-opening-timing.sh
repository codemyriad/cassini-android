#!/usr/bin/env bash
# The FAST NOTE OPENING timing check (docs/development.md): the time from a tap on a note to its first transcript
# turn, for a document of about one hour and for the 15 s FLEURS document, at the first open, at later opens and
# after process death. It runs NoteOpeningTimingTest twice on one kept scratch root: the first run imports and opens
# the notes, the app is then force-stopped, and the second run opens them again in a new process. The user's notes
# and the models are not touched. At the end it removes the scratch root.
#   scripts/note-opening-timing.sh [--serial SERIAL]
# Install the debug app and its test APK first (adb install -r), for example with scripts/device-smoke.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) export ANDROID_SERIAL=${2:?--serial needs a serial}; shift 2 ;;
        --help|-h) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

package=org.cassini.android
runner=$package.test/$package.CassiniTestRunner
test=$package.flows.NoteOpeningTimingTest
root=androidTest-kept-timing
work=$(mktemp -d)

cleanup() {
    status_code=$?
    adb shell am force-stop "$package" || true
    # androidx.sqlite keeps the database lock file at cacheDir/<absolute database path>.lck.
    adb shell "run-as $package rm -rf files/$root cache/$root cache/data/data/$package/files/$root" \
        "cache/data/user/0/$package/files/$root" || true
    rm -rf "$work"
    exit "$status_code"
}
trap cleanup EXIT

run() {
    adb shell am instrument -w -r -e class "$test#$1" -e keepRoot timing "$runner" | tr -d '\r' > "$work/$1.txt"
    if ! grep -q 'OK (1 test)' "$work/$1.txt" || grep -q 'INSTRUMENTATION_STATUS_CODE: -[234]' "$work/$1.txt"; then
        cat "$work/$1.txt" >&2
        printf 'The timing run %s failed.\n' "$1" >&2
        exit 1
    fi
}

while adb shell ps -A -o ARGS | grep -q '[i]nstrument'; do
    printf 'An instrumentation run is in progress; waiting for it to end.\n'
    sleep 30
done
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell "run-as $package rm -rf files/$root cache/$root"
since=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
adb logcat -c 2>/dev/null || printf 'Could not clear the log; reading lines after %s.\n' "$since" >&2
run firstAndLaterOpens
adb shell am force-stop "$package"
if [ -n "$(adb shell pidof "$package" | tr -d '\r')" ]; then
    printf 'The app process is still running after force-stop.\n' >&2
    exit 1
fi
run opensAfterProcessDeath
adb logcat -d -T "$since" -s CassiniTiming:I | tr -d '\r' > "$work/timing.txt"
python3 -I - "$work/timing.txt" "$(adb shell getprop ro.product.model | tr -d '\r')" \
    "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" <<'PY'
import json, statistics, sys
rows = []
for line in open(sys.argv[1], encoding='utf-8'):
    start = line.find('{')
    if start >= 0:
        rows.append(json.loads(line[start:]))
    elif 'Built ' in line:
        print(line.split('CassiniTiming: ', 1)[-1].strip())
if not rows:
    raise SystemExit('No timing lines in logcat.')
print(f'Device: {sys.argv[2]}, API {sys.argv[3]}')
print('note             audio      open                    ms')
for row in rows:
    print(f"{row['note']:<16} {row['audioMs'] // 1000:>6} s   {row['open']:<22} {row['ms']:>5}")
for note in sorted({r['note'] for r in rows}):
    later = [r['ms'] for r in rows if r['note'] == note and r['open'] == 'later']
    print(f'{note}: median of {len(later)} later opens {statistics.median(later):.0f} ms')
PY
