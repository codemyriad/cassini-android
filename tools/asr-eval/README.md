# ASR evaluation on the host

`AsrEval.kt` operates the speech recognition pipeline of the app on raw PCM files, on a desktop JVM. The pipeline
has the speech detector, the window cuts, the seam merge and the word gate. With this program, you can compare a
Parakeet export or a decode policy on a large set of recordings without a phone.

The program is not part of the Gradle build, and no CI job compiles it. It needs a host build (Linux or macOS) of
`libsherpa-onnx-jni`. This repository does not make that build.

## Input

`AsrEval` takes three arguments:

1. The model directory. It holds `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx` and `tokens.txt`.
   These are the same files as in the `parakeet-ultra/` directory of the app.
2. The Silero VAD file (`silero_vad.onnx`). Or `-`: then the program cuts at quiet points without a speech
   detector.
3. A job list, with one job on each line: `<f32le file> <sample rate> <output json>`. The PCM file is mono 32-bit
   float, little endian. If the output file of a job already has content, the program does not do that job again.
   Thus an interrupted run can continue.

The environment variable `CASSINI_CONTEXT_MS` sets `DecodePolicy.contextMs` (the default is 0). Each output is the
`cassini.words.v1` body, with the added keys `x-elapsedMs` and `x-durationMs`.

## Run the program

1. Build `libsherpa-onnx-jni` for the host from the pinned sherpa-onnx source and
   `scripts/streaming-diarization.patch` (see `docs/native-runtime.md`).
2. Copy `AsrEval.kt` into `engine/src/test/kotlin/org/cassini/tools/asreval/`. Do not commit the copy.
3. In Android Studio, run `org.cassini.tools.asreval.AsrEval.main` with the unit-test classpath of `:engine`. Add
   `-Djava.library.path=<directory of the host libsherpa-onnx-jni>`.

`./gradlew :engine:compileDebugUnitTestKotlin` compiles the copy. That classpath has the JVM classes of the engine
(`Parakeet`, `ParakeetFiles`) and the sherpa-onnx Kotlin API of the AAR.
