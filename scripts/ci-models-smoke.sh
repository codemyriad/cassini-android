#!/usr/bin/env bash
# The device check of the CI models workflow (.github/workflows/models.yml): the @NeedsModels tests on an emulator
# with the real models, which scripts/fetch-ci-models.py prepared in .tools/ci-models.
#   scripts/ci-models-smoke.sh
# It needs the debug APK and its test APK in app/build/outputs/apk (run-as works only with a debuggable app) and
# writes the instrumentation report and logcat to .tools/models-results.
set -euo pipefail
cd "$(dirname "$0")/.."
export CASSINI_APK=app/build/outputs/apk/debug/app-debug.apk
export CASSINI_TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
test -f "$CASSINI_APK"
test -f "$CASSINI_TEST_APK"
mkdir -p .tools/models-results
collect() {
    adb logcat -d > .tools/models-results/logcat.txt 2>&1 || true
    mkdir -p .tools/models-results/goldens-candidate
    for name in fleurs-it-short.txt fleurs-it-short.srt fleurs-it-short.vtt; do
        adb exec-out run-as io.codemyriad.cassini cat "cache/goldens-candidate/$name" > ".tools/models-results/goldens-candidate/$name" || true
    done
}
trap collect EXIT
adb install -r "$CASSINI_APK"
# Temporary D86 baseline encoder: checksum-verified before staging into this disposable CI emulator.
adb push .tools/d86-old-encoder.onnx /data/local/tmp/d86-old-encoder.onnx
adb shell run-as io.codemyriad.cassini cp /data/local/tmp/d86-old-encoder.onnx files/d86-old-encoder.onnx
./scripts/push-models.sh .tools/ci-models | tee .tools/models-results/push.txt
./scripts/device-smoke.sh --sample fleurs --annotation NeedsModels | tee .tools/models-results/instrumentation.txt
