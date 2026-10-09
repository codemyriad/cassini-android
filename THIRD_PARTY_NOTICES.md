# Licenses and sources

Cassini Android is licensed under [GNU GPLv3](LICENSE). Copyright © 2026 Silvio Tomatis and contributors. Source and build instructions are available in this repository; each release tag identifies its app source. The APK includes full license texts in `assets/licenses/`, including the app license.

## Native Android runtime

The APK contains a sherpa-onnx 1.13.7 AAR that Cassini builds. The AAR is the [upstream release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7) plus Sortformer speaker diarization for Nemotron-3-Diarization, from the [Cassini fork](https://github.com/codemyriad/sherpa-onnx/tree/feat/nemotron3-diarization-android). The AAR comes from one of two sources:

- `scripts/build-native-aar.sh` builds it from the pinned source plus `scripts/streaming-diarization.patch`.
- `scripts/fetch-native-aar.sh` downloads it from the GitHub release [`native-sherpa-onnx-1.13.7-nemotron.2`](https://github.com/codemyriad/cassini-android/releases/tag/native-sherpa-onnx-1.13.7-nemotron.2).

The `sherpa-onnx-aar` entry of `scripts/native-dependencies.json` pins the SHA-256 of the AAR. The AAR also contains text-to-speech components that Cassini does not use, including eSpeak NG under GPLv3. Cassini uses only speech recognition and speaker diarization.

| Component | Pinned version | License |
| --- | --- | --- |
| [sherpa-onnx](https://github.com/codemyriad/sherpa-onnx/tree/feat/nemotron3-diarization-android) | 1.13.7 with Sortformer diarization | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime/tree/v1.27.1) | 1.27.1 | MIT, with upstream third-party notices |
| [Eigen](https://gitlab.com/libeigen/eigen/-/tree/5.0.1) | 5.0.1 | MPL-2.0, with component notices |
| [nlohmann JSON](https://github.com/nlohmann/json/tree/v3.12.0) | 3.12.0 | MIT |
| [kaldi-decoder](https://github.com/k2-fsa/kaldi-decoder/tree/v0.3.0) | 0.3.0 | Apache-2.0 |
| [kaldi-native-fbank](https://github.com/csukuangfj/kaldi-native-fbank/tree/v1.22.3) | 1.22.3 | Apache-2.0 |
| [OpenFst](https://github.com/csukuangfj/openfst/tree/v1.8.5-2026-07-09) | 1.8.5-2026-07-09 | Apache-2.0 |
| [simple-sentencepiece](https://github.com/pkufool/simple-sentencepiece/tree/v0.7) | 0.7 | Apache-2.0 |
| [hclust-cpp](https://github.com/csukuangfj/hclust-cpp/tree/2026-02-25) | 2026-02-25 | BSD-2-Clause |
| [piper-phonemize](https://github.com/csukuangfj/piper-phonemize/tree/f3ff95afc03640bc1399e113e83361192a2fafb4) | f3ff95afc03640bc1399e113e83361192a2fafb4 | MIT, including uni-algo notices |
| [eSpeak NG](https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1) | ed530aa113046142eb5115cf2fc9157854d0ffe1 | GPL-3.0, with Apache/BSD/Unicode component notices |

The APK also bundles [zstd-jni](https://central.sonatype.com/artifact/com.github.luben/zstd-jni/1.5.7-22) 1.5.7-22 (BSD-2-Clause, including Zstandard under BSD-3-Clause) from Maven Central, to decompress the speaker model download.

The streaming encoder bundles unmodified [libopus 1.5.2](https://github.com/xiph/opus/tree/v1.5.2), under its BSD license. Cassini’s JNI wrapper and its CMake build files are included in the corresponding source archive.

Full texts and copyright notices are checked in under [app assets](app/src/main/assets/licenses/). The release’s `cassini-native-sources.tar` contains these pinned source archives, including native build files. [The source manifest](scripts/native-dependencies.json) provides archive URLs and SHA-256 hashes; [native build notes](docs/native-runtime.md) describe the upstream build. Apart from the sherpa-onnx diarization changes, dependency code is unmodified by Cassini.

## Android and Kotlin libraries

The APK contains these libraries from Maven Central and the Google Maven repository. Their authors publish them under the Apache License 2.0. The full text of this license is in `assets/licenses/sherpa-onnx/LICENSE` in the APK.

| Library | Version |
| --- | --- |
| [AndroidX](https://developer.android.com/jetpack/androidx) Core, AppCompat, Activity, Lifecycle and Annotation | Core 1.19.1, AppCompat 1.8.0, Activity Compose 1.13.0, Lifecycle 2.11.0, Annotation 1.9.1 |
| [Room](https://developer.android.com/jetpack/androidx/releases/room) | 2.8.5 |
| [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore) Preferences | 1.2.1 |
| [Navigation 3](https://developer.android.com/jetpack/androidx/releases/navigation3) | 1.2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/androidx/releases/compose) UI, Foundation and Material 3 | Compose BOM 2026.09.00 |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | 2.4.21 |
| [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.11.0 |
| [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.11.0 |
| [kotlinx-collections-immutable](https://github.com/Kotlin/kotlinx.collections.immutable) | 0.5.2 |

`gradle/libs.versions.toml` pins these versions. The libraries are unmodified.

## Fonts

The APK contains two font families. Their authors publish them under the SIL Open Font License, Version 1.1. The font files are unmodified copies of the upstream static TTF files. Cassini gives the files different resource names, but it does not change the fonts in the files.

| Family | Weights | Upstream | Copyright |
| --- | --- | --- | --- |
| Instrument Sans | 400, 500, 600, 700 | [github.com/Instrument/instrument-sans](https://github.com/Instrument/instrument-sans/tree/7fa22308a3d0c94ee2b3cd537a1196b65db34a3e) at commit `7fa22308a3d0c94ee2b3cd537a1196b65db34a3e`, `fonts/ttf/` | Copyright 2022 The Instrument Sans Project Authors (https://github.com/Instrument/instrument-sans) |
| IBM Plex Mono | 400, 500 | [github.com/IBM/plex](https://github.com/IBM/plex/tree/%40ibm/plex-mono%402.5.0) at tag `@ibm/plex-mono@2.5.0`, `packages/plex-mono/fonts/complete/ttf/` | Copyright © 2017 IBM Corp. with Reserved Font Name "Plex" |

`third_party/fonts/fonts.json` gives the path and the SHA-256 of each font file. The full license texts are in `assets/licenses/fonts/` in the APK.

## Downloaded models and local test fixtures

[NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) weights are CC-BY-4.0, with ONNX conversion published by the sherpa-onnx maintainer. Weights are downloaded separately and are not in the APK. Model revisions and hashes are in `ModelCatalog.kt`. Cassini runs on-device inference with the converted model.

[Silero VAD](https://github.com/snakers4/silero-vad) is MIT-licensed. Its ONNX model (`silero_vad.onnx`, 630 KB) is downloaded separately from the sherpa-onnx `asr-models` release and is not in the APK. Its size and SHA-256 are in `ModelCatalog.kt`.

[NVIDIA Nemotron-3-Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization) is the optional speaker-identification model, under the [OpenMDW License Agreement 1.1](https://openmdw.ai/license/1-1/). Cassini exported it to INT8 ONNX for sherpa-onnx and publishes it Zstandard-compressed on `dist.gocassini.com` (about 62 MiB). It downloads separately and is not in the APK. The source revision, hashes and download URL are pinned in `ModelCatalog.kt`; the license text is included in the APK. Speaker labels are anonymous estimates.

[3D-Speaker CAM++](https://github.com/modelscope/3D-Speaker) (`3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`, Apache-2.0) computes voiceprints for naming speakers. It downloads from the [sherpa-onnx release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models) (about 28 MiB) and is not in the APK; its size and SHA-256 are pinned in `ModelCatalog.kt`.

[Google FLEURS](https://huggingface.co/datasets/google/fleurs) is CC-BY-4.0 (attribution: Google FLEURS contributors). One Italian FLEURS clip, at a pinned dataset revision, is committed in the repository under `fixtures/audio/` (WAV, M4A, MP3 and Opus encodings) and its samples and recognised words are reused in the golden checkpoint under `core/src/test/resources/checkpoint/`; `fixtures/README.md` and `fixtures/audio/fleurs-it-short.json` give its source, attribution, reference text and changes. It is packaged only in the device test APK, not in the app APK. The historical device notes in `docs/notes/` used an excerpt of [il Pericolo Invisibile](https://www.youtube.com/watch?v=UmZwQf5TV3c) by Dario Bressanini. `tools/fetch-youtube-audio.sh` fetches that audio locally. The repository and the APK do not contain it.

JUnit, AndroidX Test, Robolectric, Roborazzi, Turbine, the Compose test libraries and the JVM JSON library are test dependencies. The app APK does not contain them.
