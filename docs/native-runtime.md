# Native runtime sources

Cassini uses a sherpa-onnx 1.13.7 AAR that it builds itself: upstream release 1.13.7 plus Sortformer speaker diarization for [Nemotron-3-Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization). The source is branch [`feat/nemotron3-diarization-android`](https://github.com/codemyriad/sherpa-onnx/tree/feat/nemotron3-diarization-android) of the Cassini fork: two commits on tag `v1.13.7`, cherry-picked from the contribution for upstream issue k2-fsa/sherpa-onnx#4006. They add the C++ runtime and the Kotlin/JNI configuration; speech recognition code is unchanged. `scripts/streaming-diarization.patch` adds a stateful Sortformer chunk call through C++ and Kotlin/JNI. Its bounded arrival-order speaker cache survives calls; ordinary `process` calls still start independent recordings. `scripts/setup.sh` checks the local AAR and patch hashes and builds the runtime when needed. The packaged ABIs are arm64-v8a and x86_64. The AAR contains sherpa-onnx JNI and the upstream ONNX Runtime 1.27.1 Android library, including statically linked text-to-speech dependencies that Cassini does not call.

Each release attaches `cassini-native-sources.tar`. Extracting it gives the pinned archives in `sources/`, their URL/hash manifest, the incremental JNI patch, these build notes and the dependency notice index. Run `python3 scripts/package-native-sources.py` to reproduce that bundle. Downloads and archives stay in ignored directories.

## Build

`scripts/build-native-aar.sh` reproduces the AAR. It needs an Android NDK (built with r28c, 28.2.13676358), CMake and JDK 17 or later:

```sh
ANDROID_NDK=$ANDROID_HOME/ndk/28.2.13676358 scripts/build-native-aar.sh
```

It extracts the pinned sherpa source archive from [the source manifest](../scripts/native-dependencies.json) (or uses `SHERPA_ONNX_SRC`), applies `scripts/streaming-diarization.patch`, runs upstream's `build-android-arm64-v8a.sh` and `build-android-x86-64.sh` with `SHERPA_ONNX_ENABLE_C_API=ON` and ONNX Runtime 1.27.1, copies the libraries into `android/SherpaOnnxAar` and runs `./gradlew :sherpa_onnx:assembleRelease` there, as upstream's `.github/workflows/android.yaml` does. The result is `app/libs/sherpa-onnx-1.13.7-nemotron.aar`. Text-to-speech and speaker diarization stay enabled, as upstream ships them.

ONNX Runtime's source archive includes its build scripts and pinned dependency download metadata. Its Android library build is maintained in [onnxruntime-libs](https://github.com/csukuangfj/onnxruntime-libs/tree/v1.27.1), `.github/workflows/android-shared.yaml`. That workflow provides the Android NDK/API and build flags, and removes the shared-library VERSION/SOVERSION properties from ONNX Runtime's CMake build. Native dependency archives retain their own build scripts and licenses. Downloads of transitive build inputs remain available through those upstream manifests.

These instructions do not claim a byte-for-byte reproduction across different NDK/JDK versions. The incremental runtime is currently built from source; its local hash marker also covers the patch.

## Resumable processing

The streaming Sortformer patch exports and restores its bounded embedding/FIFO cache and activity probabilities. The app saves this with the ASR overlap window and completed words, selected disk voice clips and sample clocks, using atomic metadata commits after completed chunks. Labels and merges commit independently. Decoding seeks with one second of preroll and preserves the absolute resampling phase.

Same-pass streaming encoding uses libopus 1.5.2, built by `scripts/build-opus-jni.sh` for arm64-v8a and x86_64. `scripts/setup.sh` validates the build fingerprint. Its position-independent encoder state, incomplete PCM frame, pending Ogg packet, sequence and granule clock are checkpointed; restore truncates any uncommitted file tail. Codec state is checksummed and restricted to the same pinned runtime and ABI. The source URL/hash and BSD license are in the dependency manifest and APK notices.
