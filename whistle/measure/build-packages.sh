#!/usr/bin/env bash
# Compare clean baseline/candidate release packages. Prepare each worktree's external output links first.
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/heavy-lock.sh
. "$script_dir/../../scripts/heavy-lock.sh"
heavy_lock "$0" "$@"
if [ "$#" -ne 3 ]; then
    echo 'Usage: build-packages.sh BASELINE_WORKTREE CANDIDATE_WORKTREE EXTERNAL_OUTPUT' >&2
    exit 2
fi
: "${JAVA_HOME:?JDK 17 required}"
: "${GRADLE_USER_HOME:?Use an external Gradle cache}"
: "${TMPDIR:?Use an external temporary directory}"
: "${ANDROID_USER_HOME:?Use an external Android user directory}"
# This comparison creates local unsigned files. It never uses the release keystore or publishes a package.
unset CASSINI_RELEASE_KEYSTORE_FILE CASSINI_RELEASE_STORE_PASSWORD CASSINI_RELEASE_KEY_ALIAS CASSINI_RELEASE_KEY_PASSWORD
baseline=$(cd "$1" && pwd)
candidate=$(cd "$2" && pwd)
mkdir -p "$3"
output=$(cd "$3" && pwd)
if [ "$baseline" = "$candidate" ]; then
    echo 'Baseline and candidate must be different clean worktrees' >&2
    exit 2
fi
active_repo=
trap 'if [ -n "$active_repo" ]; then "$active_repo/gradlew" --stop > "$output/daemon-stop.log" 2>&1 || true; fi' EXIT
for variant in baseline candidate; do
    if [ "$variant" = baseline ]; then repo=$baseline; else repo=$candidate; fi
    cd "$repo"
    if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
        echo "Measurement worktree is not clean: $repo" >&2
        exit 2
    fi
    active_repo=$repo
    for abi in universal arm64-v8a x86_64; do
        python3 "$script_dir/../drift/host.py" --baseline "${CASSINI_MEMORY_BASELINE:-$output/host-memory.json}"
        export CASSINI_MEASURE_ABI=$abi
        result="$output/$variant/$abi"
        mkdir -p "$result"
        git rev-parse HEAD > "$result/commit.txt"
        ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8' \
            -Pkotlin.compiler.execution.strategy=in-process --init-script "$script_dir/abi.init.gradle" \
            :app:assembleRelease :app:bundleRelease > "$result/build.log" 2>&1
        cp app/build/outputs/apk/release/app-release-unsigned.apk "$result/app-release-unsigned.apk"
        cp app/build/outputs/bundle/release/app-release.aab "$result/app-release.aab"
        python3 "$script_dir/package.py" --expected-abi "$abi" "$result/app-release-unsigned.apk" "$result/app-release.aab" > "$result/size.json"
    done
done
