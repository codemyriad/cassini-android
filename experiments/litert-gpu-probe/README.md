# Isolated Parakeet Android GPU probe

This disposable Java Android app measures LiteRT CPU, OpenCL GPU and GPU encoder / CPU decoder execution of multilingual Parakeet TDT v3. It is not included in the Cassini app build. Its decoder is adapted from [Google's Apache-2.0 LiteRT ASR sample](https://github.com/google-ai-edge/litert-samples/tree/385cfe0d09eeeb71f62daafd35ef6f353718720e/samples/litert/speech_recognition); the license is included here.

Use Java 17, the existing Android SDK/local.properties, adb, uv and Python 3.11+. Prepare the user's 30-second sample, then run from the repository root:

```sh
scripts/fetch-youtube-audio.sh 30
scripts/run-litert-gpu-probe.sh
```

The script checks SHA-256 for LiteRT 2.2.0's two AARs, the 614 MB stateful five-second INT8 model and its tokenizer. All downloads/features/reports stay under ignored `.tools/`. A custom input must be exactly 30 seconds of mono PCM16 at 16 kHz:

```sh
scripts/run-litert-gpu-probe.sh path/to/sample.wav
```

Each backend loads once and runs two passes over six independent five-second windows. CPU/GPU use one compiled model; hybrid uses two instances and copies the encoder output to CPU. Selected logits must be finite; decoding has a step limit. The reports include token IDs, frame timestamps, compile time, pass times and audio/elapsed realtime speed. GPU mode requests GPU alone. Inspect `LITERT_CL` node delegation in the logs to establish actual placement; configuration labels alone are insufficient.

The script compares each mode's last pass against CPU. It removes the disposable `org.cassini.probe` APK and its private model after success, then restores Cassini. If interrupted or failed, clean up with `adb uninstall org.cassini.probe`; **do not uninstall Cassini**.

Host feature generation is excluded from reported speed. This benchmark does not implement overlapping windows, Cassini packaging, production frontend parity, full-precision GPU policy, or a labeled quality evaluation. See [findings and measured limitations](../../docs/android-acceleration.md).
