#!/usr/bin/env bash
# Usage: POLICY=WHOLE_SPANS|REFERENCE CUTTING=SPEECH|QUIET|WHOLE|FIXED [CONTEXT_MS=] [TAIL_MS=] run.sh <jobs.txt> [vad|-]
# jobs.txt lines: <f32le mono file> <rate> <out.json>
set -euo pipefail
exec /usr/lib/jvm/java-17-openjdk-amd64/bin/java -Djava.library.path=/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/build-jni/lib \
  -cp "/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/eval/classes:$(cat /tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/eval/classpath.txt)" org.cassini.tools.hosteval.HostEval \
  /tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/models/parakeet-ultra-int8 "${2:-/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/models/vad/silero_vad.onnx}" "$1"
