#!/usr/bin/env bash
# The process-death check of docs/architecture.md section 9. It starts a long transcription in a debug build on a
# scratch root (files/debug-root.txt), kills the process with kill -9 after the first checkpoint, waits for Android
# to redeliver the service intent (or opens the app again), waits for the sealed document and checks it with the
# portable-format tools. The user's notes and the models are never touched: the notes go to the scratch root, and
# the models stay in files/. At the end it removes debug-root.txt and the scratch root.
set -euo pipefail
# shellcheck source=scripts/heavy-lock.sh
. "$(dirname "$0")/heavy-lock.sh"
heavy_lock "$0" "$@"
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage: scripts/device-kill-resume.sh [--serial SERIAL] [--keep DIR] [--help]

Needs the debug build of the app on the device (adb install -r app/build/outputs/apk/debug/app-debug.apk), its
models installed in files/, ffmpeg on the host (for scripts/make-long-clip.sh), and python3. When ../gocassini
exists, the document is also read with the web viewer's reader (node 24+).

  --serial SERIAL   The adb serial of the device (default: $ANDROID_SERIAL, or the only device).
  --keep DIR        Keeps the pulled document in DIR.
  --help            Shows this text.
EOF
}

keep=
while [ $# -gt 0 ]; do
    case "$1" in
        --serial) export ANDROID_SERIAL=${2:?--serial needs a serial}; shift 2 ;;
        --keep) keep=${2:?--keep needs a directory}; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'Unknown argument: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

package=io.codemyriad.cassini.talk
name=killtest-$(date +%s)-$$
clip=$(scripts/make-long-clip.sh --print-path)
clip_name=$(basename "$clip")
work=$(mktemp -d)
began=$(date +%s)
watcher=
scratch_started=false

log() { printf '[%4ss] %s\n' "$(( $(date +%s) - began ))" "$*"; }
as_app() { adb shell "run-as $package $*" | tr -d '\r'; }
read_file() { adb exec-out "run-as $package cat files/$name/$1 2>/dev/null" || true; }
pid() { adb shell pidof "$package" | tr -d '\r' || true; }
# Prints the value of a Python expression over the status file as J (the note as N when NOTE is set), or nothing.
status() {
    read_file debug-status.json > "$work/status.json"
    python3 -I - "$work/status.json" "${note:-}" "$1" <<'PY'
import json, sys
try:
    J = json.load(open(sys.argv[1]))
except ValueError:
    raise SystemExit(0)
N = next((n for n in J.get('notes', []) if n['id'] == sys.argv[2]), None)
try:
    value = eval(sys.argv[3], {'J': J, 'N': N})
except (KeyError, TypeError):
    value = None
if value is not None and value is not False:
    print(value)
PY
}
# Wait for the next atomic status write or process exit. Read the current state first so a publication that
# preceded this call still satisfies the predicate. The deadline only bounds a missing event.
wait_for_value() {
    local seconds=$1 value deadline
    shift
    deadline=$((SECONDS + seconds))
    while :; do
        value=$("$@")
        if [ -n "$value" ]; then printf '%s\n' "$value"; return 0; fi
        wait_for_update "$deadline" || return 1
    done
}

wait_for_update() {
    local remaining=$(( $1 - SECONDS )) _update
    [ "$remaining" -gt 0 ] && IFS= read -r -t "$remaining" _update <&3
}

start_updates() {
    local since
    since=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
    mkfifo "$work/updates"
    adb logcat -b main -b events -v raw -T "$since" \
        -e "^Published debug-(status|command)[.]json in $name$|^\[" -s DebugStatus:I am_proc_died:I > "$work/updates" &
    watcher=$!
    exec 3<"$work/updates"
}

command_note() {
    read_file debug-command.json | python3 -I -c 'import json, sys
try:
    print(json.load(sys.stdin).get("noteId", ""))
except ValueError:
    pass'
}

exited() {
    if [ "$(pid)" != "$1" ]; then printf 'yes\n'; fi
}

cleanup() {
    status_code=$?
    if [ "$status_code" -ne 0 ] && [ -s "$work/status.json" ]; then
        printf 'Last status:\n' >&2
        cat "$work/status.json" >&2
    fi
    if [ -n "$watcher" ]; then
        exec 3<&-
        kill "$watcher" 2>/dev/null || true
        wait "$watcher" 2>/dev/null || true
    fi
    if $scratch_started; then
        # Stop the process first, so that nothing writes into the scratch root while it is removed.
        adb shell am force-stop "$package" || true
        # androidx.sqlite keeps the database lock file at cacheDir/<absolute database path>.lck.
        adb shell "run-as $package rm -rf files/debug-root.txt files/$name cache/$name" \
            "cache/data/data/$package/files/$name cache/data/user/0/$package/files/$name" || true
        if [ -n "$(as_app test -e files/debug-root.txt '&&' echo present)" ]; then
            printf 'Could not remove debug-root.txt\n' >&2
        fi
    fi
    rm -rf "$work"
    exit "$status_code"
}

trap cleanup EXIT
if [ "$(as_app id -u 2>/dev/null | grep -c '^[0-9]')" -ne 1 ]; then
    printf 'run-as %s fails: install the debug build first.\n' "$package" >&2
    exit 1
fi
processes=$(adb shell dumpsys activity processes "$package")
if [[ "$processes" == *'Active instrumentation:'* ]]; then
    printf 'Instrumentation is active. Run this probe after it finishes.\n' >&2
    exit 1
fi
if [ -n "$(as_app test -e files/debug-root.txt '&&' echo present)" ]; then
    printf 'files/debug-root.txt exists already: another check runs, or an earlier one did not clean up.\n' >&2
    exit 1
fi
start_updates
scratch_started=true

# The debug root is read once, at process start.
adb shell am force-stop "$package"
as_app mkdir -p "cache/$name"
adb exec-in "run-as $package sh -c 'cat > cache/$name/$clip_name'" < "$clip"
printf '%s\n' "$name" | adb exec-in "run-as $package sh -c 'cat > files/debug-root.txt'"
log "Scratch root files/$name; clip $clip_name ($(stat -c %s "$clip") bytes)"

adb shell am start -W -n "$package/io.codemyriad.cassini.debug.DebugCommandActivity" --es cmd import --es file "$clip_name" | tr -d '\r'
note=$(wait_for_value 60 command_note) || note=
read_file debug-command.json
printf '\n'
if [ -z "$note" ]; then printf 'The import did not start a job.\n' >&2; exit 1; fi
log "Note $note queued"

checkpoint=$(wait_for_value 600 status "N and N.get('checkpointMs') and J['running'] and J['running']['noteId'] == N['id'] and N['checkpointMs']") \
    || { printf 'No checkpoint within 10 min.\n' >&2; exit 1; }
before=$(pid)
log "Checkpoint at $checkpoint ms; kill -9 of pid $before"
adb shell "run-as $package kill -9 $before"
wait_for_value 20 exited "$before" >/dev/null || { printf 'The process survived kill -9.\n' >&2; exit 1; }
log 'Process killed'

# Android can restart the service with START_REDELIVER_INTENT. If it does not, opening the app resumes the queue.
resumed="J['pid'] != $before and J['running'] and J['running']['noteId'] == N['id']"
resumed_by=
if wait_for_value 90 status "$resumed" >/dev/null; then resumed_by=redelivery; fi
if [ -z "$resumed_by" ]; then
    log 'No redelivery within 90 s; opening the app'
    adb shell am start -W -n "$package/io.codemyriad.cassini.debug.DebugCommandActivity" --es cmd open | tr -d '\r'
    wait_for_value 60 status "$resumed" >/dev/null \
        || { printf 'The job did not start again.\n' >&2; exit 1; }
    resumed_by=relaunch
fi
log "Resumed by $resumed_by in pid $(pid): $(status "'attempts %s, running %s' % (N.get('attempts'), J['running'])")"

last=
deadline=$((SECONDS + 1200))
while :; do
    done_state=$(status "N['jobState'] is None and N['audioKind'] == 'DOCUMENT' and N['documentState']")
    if [ -n "$done_state" ]; then break; fi
    failed=$(status "N['jobState'] in ('FAILED', 'PAUSED', 'NEEDS_CONSENT') and '%s %s %s' % (N['jobState'], N['pauseReason'], N['failure'])")
    if [ -n "$failed" ]; then printf 'The job stopped: %s\n' "$failed" >&2; exit 1; fi
    progress=$(status "J['running'] and '%s %s/%s ms' % (J['running']['phase'], J['running'].get('doneMs'), J['running'].get('totalMs'))")
    if [ -n "$progress" ] && [ "$progress" != "$last" ]; then log "$progress"; last=$progress; fi
    wait_for_update "$deadline" || { printf 'The job did not end within 20 min.\n' >&2; exit 1; }
done
log "Done: document $done_state, $(status "'%s words, %s ms' % (N['wordCount'], N['durationMs'])")"
if [ "$done_state" != OK ]; then printf 'The sealed document is %s, not OK.\n' "$done_state" >&2; exit 1; fi

document="$work/$note.opus"
adb exec-out "run-as $package cat files/$name/notes/$note/document.opus" > "$document"
log "Pulled document.opus ($(stat -c %s "$document") bytes)"
python3 third_party/cassini-format/tools/cassini-extract.py --check "$document"
if [ -d ../gocassini ]; then
    node scripts/check-portable-document.mjs "$document"
else
    log 'No ../gocassini checkout: skipped check-portable-document.mjs'
fi
if [ -n "$keep" ]; then mkdir -p "$keep" && cp "$document" "$keep/" && log "Kept $keep/$note.opus"; fi
log "Kill and resume passed (resumed by $resumed_by)"
