# Cassini Android prototype

Native Kotlin app: import audio, transcribe locally with **Parakeet TDT 0.6B v3**, display clickable timed words, play/search the recording, and save **`cassini.words.v1` JSON**. Italian is the selected transcript language; Parakeet automatically recognizes among its supported languages rather than accepting a forced Italian decoder setting.

Choose **INT8** (640 MiB) or **FP32** (2.37 GiB) in the app. Both use sherpa-onnx 1.13.7 on CPU, greedy decoding, two threads, and the stock frontend. Weights download from a pinned Hugging Face revision into private app storage and are SHA-256 checked before installation. After that, audio processing is offline. Models are installed separately; switching precision preserves each download.

## Build and install

Requires JDK 17, Android SDK platform/build tools 34, and an ARM64 phone with Android 8+ (x86_64 is also packaged for emulator experiments). Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.

```sh
./scripts/setup.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.cassini.android/.MainActivity
```

The AAR is downloaded separately and verified by `setup.sh`; weights are downloaded on the phone. Neither is committed. Gradle uses its normal dependency repositories. This is a debug APK for local testing, not a Play Store release.

## Try it

1. Select INT8 or FP32 and download that model once. Keep the app open during download/inference.
2. Choose an audio file of **at most 30 seconds** and at most 64 MB. PCM16 WAV, MP3, M4A/AAC and Ogg Opus are the tested import paths. FLAC is routed through Android's decoder but has not been tested here. WAV supports 16-bit PCM mono/stereo; decoded sample rates can be 8–96 kHz.
3. Tap **Transcribe Italian**. Words appear as a native paragraph. Tap a word to seek/play; use the playback slider or search field to inspect it.
4. Tap **Save Cassini words JSON** to choose an export destination. The body names the unidentified speaker `spk_1`.

There is no microphone recording, diarization, meeting library, background service, or native portable Opus reader/writer yet. The JSON is the published transcript **body**, not a complete Cassini portable meeting: that needs an Ogg Opus file with a manifest, speakers, payload chunks and audio digest. The app writes its latest successful body and processing details into private `files/latest.words.json` and `files/latest.processing.json`; it does not restore the view after process termination.

## Device checks and supplied sample

Prepare the regression fixtures (one attributed Google FLEURS recording converted to several codecs) and the supplied YouTube excerpt:

```sh
python3 scripts/fetch-smoke-audio.py  # requires ffmpeg
./scripts/fetch-youtube-audio.sh     # uses uvx yt-dlp + ffmpeg
./scripts/device-smoke.sh int8 youtube
./scripts/device-smoke.sh fp32 youtube
```

The supplied sample is [il Pericolo Invisibile](https://www.youtube.com/watch?v=UmZwQf5TV3c), **00:28–00:58**. Extraction produces exactly 480,000 mono PCM16 samples at 16 kHz, with a 30.000-second duration. Audio/downloads are local ignored test artifacts, not distributed in this repository. `fetch-smoke-audio.py` records the FLEURS source, pinned revision, reference text and CC-BY-4.0 attribution alongside its local fixture.

`device-smoke.sh` installs the app and test APK and invokes instrumentation through adb, retaining model downloads. Avoid Gradle's `connectedDebugAndroidTest` on an installation you want to preserve: its managed device-test cleanup can uninstall the app and erase its data. Inference tests download the selected model if necessary. They verify actual JNI inference, basic Italian content on the FLEURS sample, and valid word timestamps; they do **not** establish ASR accuracy. Decoder checks compare duration and speech onset across WAV, MP3, AAC and Opus.

Device outputs are `files/device-smoke.INT8.words.json`, `files/device-smoke.FP32.words.json` and corresponding `.metrics.json` files:

```sh
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.metrics.json
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.words.json > transcript.words.json
```

See [the feasibility assessment](docs/feasibility.md) for format/viewer compatibility, limitations and the path to a full app.
Measured runs on the connected Pixel 8 are in [the device results](docs/pixel8-results.md): about 7 seconds / 1.02 GiB peak RSS for INT8 and 19.4 seconds / 2.60 GiB for FP32 on the supplied 30-second clip.

## Dependencies and attribution

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache-2.0, with ONNX Runtime, MIT, in the upstream Android AAR. Packaging their complete notices is required before distributing a release.
- [NVIDIA Parakeet v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), CC-BY-4.0 weights; ONNX bundles published by the sherpa maintainer. Model revision, sizes and SHA-256 hashes are pinned in `ModelStore.kt`.
- [Google FLEURS](https://huggingface.co/datasets/google/fleurs), CC-BY-4.0, used only for locally fetched device regression fixtures.
