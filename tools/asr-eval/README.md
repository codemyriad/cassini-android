# ASR evaluation on the host

`AsrEval.kt` runs the app's own speech recognition pipeline (speech detector, window cutting, seam merging, word
gate) on raw PCM files on a desktop JVM, so a Parakeet export or a decode policy can be compared on a large set of
recordings without a phone.

It is not part of the Gradle build and no CI job compiles it. It needs a host (Linux or macOS) build of
`libsherpa-onnx-jni`, which this repository does not produce.

## Input

`AsrEval` takes three arguments:

1. The model directory. It holds `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx` and `tokens.txt`, the
   same files as the app's `parakeet-v3/` directory.
2. The Silero VAD file (`silero_vad.onnx`), or `-` to cut at quiet points without a speech detector.
3. A job list: one job per line, `<f32le file> <sample rate> <output json>`. The PCM file is mono 32-bit float,
   little endian. A job whose output file already has content is skipped, so an interrupted run can continue.

The environment variable `CASSINI_CONTEXT_MS` sets `DecodePolicy.contextMs` (default 0). Each output is the
`cassini.words.v1` body with `x-elapsedMs` and `x-durationMs` added.

## Running it

1. Build `libsherpa-onnx-jni` for the host from the pinned sherpa-onnx source plus
   `scripts/streaming-diarization.patch` (see `docs/native-runtime.md`).
2. Copy `AsrEval.kt` into `engine/src/test/kotlin/org/cassini/tools/asreval/`. Do not commit the copy.
3. Run `org.cassini.tools.asreval.AsrEval.main` from Android Studio with the `:engine` unit-test classpath and
   `-Djava.library.path=<directory of the host libsherpa-onnx-jni>`.

The copy compiles with `./gradlew :engine:compileDebugUnitTestKotlin`. The engine's JVM classes (`Parakeet`,
`ParakeetFiles`) and the sherpa-onnx Kotlin API from the AAR are on that classpath.
