# Native runtime sources

Cassini uses the unmodified `sherpa-onnx-1.13.7.aar` published by upstream. `scripts/setup.sh` verifies its hash. The packaged ABIs are arm64-v8a and x86_64. The AAR contains sherpa-onnx JNI and ONNX Runtime 1.27.1, including statically linked text-to-speech dependencies that Cassini does not call.

Each release attaches `cassini-native-sources.tar`. Extracting it gives the pinned archives in `sources/`, their URL/hash manifest, these build notes and the dependency notice index. Run `python3 scripts/package-native-sources.py` to reproduce that bundle. Downloads and archives stay in ignored directories.

## Upstream build

The authoritative scripts are in sherpa-onnx 1.13.7’s `.github/workflows/android.yaml`, `build-android-arm64-v8a.sh` and `build-android-x86-64.sh`. They download ONNX Runtime 1.27.1’s Android libraries, build the native JNI libraries, then assemble `android/SherpaOnnxAar`. CMake files pin the other native dependencies to the versions in our source manifest.

To build replacement JNI libraries, extract the sherpa source archive, install an Android NDK and CMake, set `ANDROID_NDK` to that NDK, and run:

```sh
export SHERPA_ONNX_ENABLE_C_API=ON
export SHERPA_ONNX_ONNXRUNTIME_VERSION=1.27.1
./build-android-arm64-v8a.sh
./build-android-x86-64.sh
```

The upstream defaults include text-to-speech and speaker diarization. Do not disable them when reproducing the shipped configuration. To assemble the AAR, follow the copy/build steps in the upstream workflow: copy each ABI’s installed libraries into `android/SherpaOnnxAar/sherpa_onnx/src/main/jniLibs/`, then run `./gradlew :sherpa_onnx:assembleRelease` there. Additional ABIs in upstream’s AAR are excluded by Cassini’s Gradle ABI filters.

ONNX Runtime’s source archive includes its build scripts and pinned dependency download metadata. Its Android library build is maintained in [onnxruntime-libs](https://github.com/csukuangfj/onnxruntime-libs/tree/v1.27.1), `.github/workflows/android-shared.yaml`. That workflow provides the Android NDK/API and build flags, and removes the shared-library VERSION/SOVERSION properties from ONNX Runtime’s CMake build. Native dependency archives retain their own build scripts and licenses. Downloads of transitive build inputs remain available through those upstream manifests.

These instructions describe upstream’s build configuration. They do not claim a byte-for-byte reproduction across different NDK/JDK versions. Cassini’s CI fetches the hash-verified AAR rather than rebuilding it.
