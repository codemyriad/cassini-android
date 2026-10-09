#!/usr/bin/env bash
# Check that native/m2 holds the pinned sherpa-onnx AAR and that the pin still matches the
# streaming diarization patch and the version catalog. Exits non-zero on any mismatch.
#   --quiet   print nothing, only set the exit status
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/native-common.sh
quiet=false
[ "${1:-}" = --quiet ] && quiet=true
say() { $quiet || printf '%s\n' "$*"; }
status=0
expected=$(pin sherpa-onnx-aar sha256)
if [ ! -f "$aar_installed" ]; then
    say "Missing $aar_installed. Run scripts/setup.sh."
    status=1
elif [ "$(sha256_of "$aar_installed")" != "$expected" ]; then
    say "$aar_installed has SHA-256 $(sha256_of "$aar_installed"), the pin is $expected."
    status=1
else
    say "AAR matches the pin: $expected"
fi
patch=$(pin sherpa-onnx-aar patchSha256)
if [ "$(sha256_of scripts/streaming-diarization.patch)" != "$patch" ]; then
    say "scripts/streaming-diarization.patch changed since the AAR was pinned. Rebuild with scripts/setup.sh --from-source and update the pin."
    status=1
fi
catalog=$(sed -n 's/^sherpaOnnx *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)
if [ "$catalog" != "$aar_version" ]; then
    say "gradle/libs.versions.toml asks for sherpaOnnx $catalog, scripts/native-dependencies.json pins $aar_version."
    status=1
fi
exit $status
