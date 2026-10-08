# Licenses and sources

Cassini Android is licensed under [GNU GPLv3](LICENSE). Copyright © 2026 Silvio Tomatis and contributors. Source and build instructions are available in this repository; each release tag identifies its app source. The APK includes full license texts in `assets/licenses/`, including the app license.

## Native Android runtime

The APK bundles a sherpa-onnx 1.13.7 AAR built by Cassini: the [upstream release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7) plus Sortformer speaker diarization for Nemotron-3-Diarization, from the [Cassini fork](https://github.com/codemyriad/sherpa-onnx/tree/feat/nemotron3-diarization-android). Its SHA-256 is pinned in `scripts/setup.sh`; `scripts/build-native-aar.sh` rebuilds it. It includes unused text-to-speech components, including GPLv3 eSpeak NG, even though Cassini only invokes speech recognition and diarization.

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

The APK also bundles [zstd-jni](https://github.com/luben/zstd-jni/tree/v1.5.7-6) 1.5.7-6 (BSD-2-Clause, including Zstandard under BSD-3-Clause) from Maven Central, to decompress the speaker model download.

Full texts and copyright notices are checked in under [app assets](app/src/main/assets/licenses/). The release’s `cassini-native-sources.tar` contains these pinned source archives, including native build files. [The source manifest](scripts/native-dependencies.json) provides archive URLs and SHA-256 hashes; [native build notes](docs/native-runtime.md) describe the upstream build. Apart from the sherpa-onnx diarization changes, dependency code is unmodified by Cassini.

## Downloaded models and local test fixtures

[NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) weights are CC-BY-4.0, with ONNX conversion published by the sherpa-onnx maintainer. Weights are downloaded separately and are not in the APK. Model revisions and hashes are in `ModelStore.kt`. Cassini runs on-device inference with the converted model.

[Silero VAD](https://github.com/snakers4/silero-vad) is MIT-licensed. Its ONNX model (`silero_vad.onnx`, 630 KB) is downloaded separately from the sherpa-onnx `asr-models` release and is not in the APK. Its size and SHA-256 are in `ModelStore.kt`.

[NVIDIA Nemotron-3-Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization) is the optional speaker-identification model, under the [OpenMDW License Agreement 1.1](https://openmdw.ai/license/1-1/). Cassini exported it to INT8 ONNX for sherpa-onnx and publishes it Zstandard-compressed on `dist.gocassini.com` (about 62 MiB). It downloads separately and is not in the APK. The source revision, hashes and download URL are pinned in `DiarizationModels.kt`; the license text is included in the APK. Speaker labels are anonymous estimates.

[3D-Speaker CAM++](https://github.com/modelscope/3D-Speaker) (`3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`, Apache-2.0) computes voiceprints for naming speakers. It downloads from the [sherpa-onnx release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models) (about 28 MiB) and is not in the APK; its size and SHA-256 are pinned in `VoiceprintModel.kt`.

[Google FLEURS](https://huggingface.co/datasets/google/fleurs) is CC-BY-4.0. Its pinned source, attribution and reference text accompany locally fetched device fixtures; those files are not in the distributed APK or repository. README screenshots show an excerpt of [il Pericolo Invisibile](https://www.youtube.com/watch?v=UmZwQf5TV3c) by Dario Bressanini, used for the documented device checks. No sample audio is distributed.

JUnit, AndroidX Test and the JVM JSON dependency are test-only dependencies and are not packaged in the app APK.
