# Licenses and sources

Cassini Android is licensed under [GNU GPLv3](LICENSE). Copyright © 2026 Silvio Tomatis and contributors. Source and build instructions are available in this repository; each release tag identifies its app source. The APK includes full license texts in `assets/licenses/`, including the app license.

## Native Android runtime

The APK bundles the upstream [sherpa-onnx 1.13.7 AAR](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7). Its SHA-256 is pinned in `scripts/setup.sh`. It includes unused text-to-speech components, including GPLv3 eSpeak NG, even though Cassini only invokes speech recognition.

| Component | Pinned version | License |
| --- | --- | --- |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.7) | 1.13.7 | Apache-2.0 |
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

Full texts and copyright notices are checked in under [app assets](app/src/main/assets/licenses/). The release’s `cassini-native-sources.tar` contains these pinned source archives, including native build files. [The source manifest](scripts/native-dependencies.json) provides archive URLs and SHA-256 hashes; [native build notes](docs/native-runtime.md) describe the upstream build. Dependency code is unmodified by Cassini.

## Downloaded models and local test fixtures

[NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) weights are CC-BY-4.0, with ONNX conversion published by the sherpa-onnx maintainer. Weights are downloaded separately and are not in the APK. Model revisions and hashes are in `ModelStore.kt`. Cassini runs on-device inference with the converted model.

[Google FLEURS](https://huggingface.co/datasets/google/fleurs) is CC-BY-4.0. Its pinned source, attribution and reference text accompany locally fetched device fixtures; those files are not in the distributed APK or repository. README screenshots show an excerpt of [il Pericolo Invisibile](https://www.youtube.com/watch?v=UmZwQf5TV3c) by Dario Bressanini, used for the documented device checks. No sample audio is distributed.

JUnit, AndroidX Test and the JVM JSON dependency are test-only dependencies and are not packaged in the app APK.
