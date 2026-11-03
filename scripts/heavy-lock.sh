#!/usr/bin/env bash
# One heavy run at a time on a shared machine. The Cassini agents of Android, iOS and the Mac share one 16 GB Mac, and
# two heavy runs at once (builds from source, Gradle builds, emulator device tests, Xcode simulators) made every run
# slower and tight waits fail. Each heavy run takes an flock on ~/.cassini-heavy.lock, the same lock that the iOS
# scripts take (cassini-ios #27), and waits while another run holds it. The holder writes one line into the file that
# names it; the file is emptied when it lets go.
#
# In a script, right after `set -euo pipefail` and before any `cd`:
#   . "$(dirname "$0")/heavy-lock.sh"
#   heavy_lock "$0" "$@"
# heavy_lock runs the script again under the lock and does not return. It returns at once, so the script goes on
# without taking the lock, when this run already holds it (CASSINI_HEAVY_LOCK_HELD=1, set for every command under the
# lock, so a locked script that calls another one does not wait for itself), on CI (CI=true: a runner has one job),
# for --help, or with CASSINI_HEAVY_LOCK=off.
#
# For a heavy command of its own, for example a Gradle build:
#   scripts/heavy-lock.sh ./gradlew :app:assembleDebug
#
# Environment: CASSINI_HEAVY_LOCK, the lock file (default ~/.cassini-heavy.lock; off: no lock), and
# CASSINI_LOCK_MINUTES, how long to wait for it (default 60). A run that waits longer fails with exit status 75.
#
# The lock is held by flock(1) or lockf(1) (macOS), not by the command: neither passes the lock's descriptor on to it
# (flock -o), so a Gradle daemon that outlives the command does not keep the lock.

# heavy_lock_run COMMAND [ARG...]: runs the command under the host lock and returns its exit status.
heavy_lock_run() {
    local lock="${CASSINI_HEAVY_LOCK:-$HOME/.cassini-heavy.lock}"
    local seconds=$(( ${CASSINI_LOCK_MINUTES:-60} * 60 ))
    local note
    note="cassini-android $(basename "$1") (pid $$, since $(date '+%Y-%m-%d %H:%M:%S'))"
    local -a take probe
    if [ -x /usr/bin/lockf ]; then
        # -k keeps the file: other tools hold the same file by name.
        take=(/usr/bin/lockf -k -t "$seconds" "$lock")
        probe=(/usr/bin/lockf -k -t 0 "$lock" true)
    elif command -v flock > /dev/null; then
        take=(flock -o -w "$seconds" -E 75 "$lock")
        probe=(flock -o -n "$lock" true)
    else
        printf 'warning: neither lockf nor flock is installed; running without the host lock %s\n' "$lock" >&2
        CASSINI_HEAVY_LOCK_HELD=1 "$@"
        return
    fi
    if ! "${probe[@]}" 2> /dev/null; then
        local holder
        holder=$(cat "$lock" 2> /dev/null || true)
        printf 'Waiting for the host lock %s: %s holds it (at most %s min)\n' \
            "$lock" "${holder:-another run}" "${CASSINI_LOCK_MINUTES:-60}" >&2
    fi
    local status=0
    # The note goes into the file only once the lock is held, and the file is emptied before it is let go.
    # shellcheck disable=SC2016 # the inner script expands its own arguments.
    "${take[@]}" env CASSINI_HEAVY_LOCK_HELD=1 bash -c '
        lock=$1 note=$2
        shift 2
        printf "%s\n" "$note" > "$lock"
        status=0
        "$@" || status=$?
        : > "$lock"
        exit "$status"' heavy-lock "$lock" "$note" "$@" || status=$?
    if [ "$status" -eq 75 ]; then
        printf 'The host lock %s stayed busy for %s min: %s\n' "$lock" "${CASSINI_LOCK_MINUTES:-60}" \
            "$(cat "$lock" 2> /dev/null || true)" >&2
    fi
    return "$status"
}

# heavy_lock SCRIPT [ARG...]: see above.
heavy_lock() {
    if [ "${CASSINI_HEAVY_LOCK_HELD:-}" = 1 ] || [ "${CI:-}" = true ] || [ "${CASSINI_HEAVY_LOCK:-}" = off ]; then
        return 0
    fi
    case "${2:-}" in --help | -h) return 0 ;; esac
    local status=0
    heavy_lock_run "$@" || status=$?
    exit "$status"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    set -euo pipefail
    if [ "$#" -eq 0 ] || [ "$1" = --help ]; then
        printf 'Usage: scripts/heavy-lock.sh COMMAND [ARG...]: runs a heavy command under the host lock\n' >&2
        exit 2
    fi
    if [ "${CASSINI_HEAVY_LOCK_HELD:-}" = 1 ] || [ "${CASSINI_HEAVY_LOCK:-}" = off ]; then exec "$@"; fi
    heavy_lock_run "$@"
fi
