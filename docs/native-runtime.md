# Native runtime

Cassini uses two native libraries: a sherpa-onnx AAR that Cassini builds itself, and `libcassini_opus.so`. This file tells where they come from, how the repository pins them and how to build them from source.

## The sherpa-onnx AAR

The AAR is sherpa-onnx release 1.13.7 with Sortformer speaker diarization for [Nemotron-3-Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization). The source is the branch [`feat/nemotron3-diarization-android`](https://github.com/codemyriad/sherpa-onnx/tree/feat/nemotron3-diarization-android) of the Cassini fork. It has two commits on the tag `v1.13.7`. These commits come from the contribution for upstream issue k2-fsa/sherpa-onnx#4006. These commits add the C++ runtime and the Kotlin/JNI configuration. They do not change the speech recognition code. `scripts/streaming-diarization.patch` adds the streaming API (see [The patch API](#the-patch-api)).

The AAR contains the sherpa-onnx JNI library and the ONNX Runtime 1.27.1 Android library, for arm64-v8a and x86_64. It also contains text-to-speech parts that Cassini does not call.

The app uses the AAR as the Maven coordinate `org.cassini.native:sherpa-onnx-nemotron:1.13.7-1f23907a`. Gradle reads this coordinate from the repository-local Maven directory `native/m2/` (see [its README](../native/m2/README.md)). Git ignores all of `native/m2/` except the README.

## Prebuilt or from source

`scripts/setup.sh` gets the AAR from the first source that operates:

1. The AAR that is already in `native/m2`, if its SHA-256 agrees with the pin.
2. The file `app/libs/sherpa-onnx-1.13.7-nemotron.aar` of an older checkout, if its SHA-256 agrees with the pin.
3. The prebuilt AAR of the GitHub release `native-sherpa-onnx-1.13.7-nemotron.2`, with `scripts/fetch-native-aar.sh`.
4. A build from source, with `scripts/build-native-aar.sh`.

| Command | Result |
|---|---|
| `scripts/setup.sh` | The prebuilt AAR if possible, else a build from source |
| `scripts/setup.sh --prebuilt-only` | The prebuilt AAR. If the script cannot get it, it stops with an error. CI uses this command. |
| `scripts/setup.sh --from-source` | Always a build from source. This takes about 25 minutes and needs the NDK. |

The prebuilt AAR has the SHA-256 `d376f7e25707cf6834208db297e5955c8e02117131740df7ff7fd6ca17216068`. A build from source does not always give the same bytes, because the NDK and JDK versions can change the output. If the hash of a build from source is different, the script installs the AAR and shows a warning.

### Build from source

`scripts/build-native-aar.sh` needs an Android NDK (the pinned build used r28c, 28.2.13676358), CMake and JDK 17 or later:

```sh
ANDROID_NDK=$ANDROID_HOME/ndk/28.2.13676358 scripts/build-native-aar.sh
```

The script does these steps:

1. It downloads the pinned sherpa-onnx source archive and makes sure that its SHA-256 is correct. If `SHERPA_ONNX_SRC` is set, it uses that checkout.
2. It applies `scripts/streaming-diarization.patch`.
3. It runs the upstream scripts `build-android-arm64-v8a.sh` and `build-android-x86-64.sh` with `SHERPA_ONNX_ENABLE_C_API=ON` and ONNX Runtime 1.27.1.
4. It copies the libraries into `android/SherpaOnnxAar` and runs `./gradlew :sherpa_onnx:assembleRelease` there, as the upstream `.github/workflows/android.yaml` does.
5. It installs the AAR and a minimal POM into `native/m2/org/cassini/native/sherpa-onnx-nemotron/1.13.7-1f23907a/`.

The upstream build scripts download a prebuilt ONNX Runtime Android library. Its build is in [onnxruntime-libs](https://github.com/csukuangfj/onnxruntime-libs/tree/v1.27.1), `.github/workflows/android-shared.yaml`. That workflow gives the NDK, the Android API level and the build flags. It also removes the VERSION and SOVERSION properties of the shared library from the CMake build of ONNX Runtime. The source archives of the dependencies keep their own build scripts and licenses.

## The lock

`scripts/native-dependencies.json` is the lock file of the native inputs. Each entry has a name, a version, a URL and a SHA-256. These entries are important for the AAR:

| Entry | Content |
|---|---|
| `sherpa-onnx` | The source archive of the fork at commit `1f23907a` |
| `sherpa-onnx-aar` | The URL and SHA-256 of the prebuilt AAR, and `patchSha256`, the SHA-256 of the patch that the AAR contains |
| `onnxruntime` and the other entries | The source archives of the dependencies |

`scripts/verify-native-runtime.sh` makes sure of three things:

- The installed AAR has the SHA-256 of the `sherpa-onnx-aar` entry.
- `scripts/streaming-diarization.patch` has the SHA-256 of `patchSha256`.
- The `sherpaOnnx` version in `gradle/libs.versions.toml` is the version of the entry.

The CI workflow `native.yml` builds the AAR from source. It operates each week, when you start it by hand, and after a change to the native inputs ([`development.md`](development.md#nativeyml)). It builds and tests the app with that AAR, and it compares the SHA-256 of the AAR with the pin. A difference gives a warning, not a failure.

To change the patch, do these steps:

1. Change `scripts/streaming-diarization.patch`.
2. Build the AAR with `scripts/setup.sh --from-source`.
3. Test the app with the new AAR.
4. Ask the owner to publish the AAR and the patch in a new GitHub release `native-sherpa-onnx-1.13.7-nemotron.N`.
5. Update the `sherpa-onnx-aar` entry with the new URL, SHA-256 and `patchSha256`.
6. Run `scripts/verify-native-runtime.sh`.

## The patch API

Upstream sherpa-onnx processes each recording in one call. `scripts/streaming-diarization.patch` adds a chunk call that keeps its state between calls. It changes the C++ classes, the JNI layer and the Kotlin class `com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization`:

```kotlin
fun processStreamingChunk(samples: FloatArray): Array<OfflineSpeakerDiarizationSegment>
fun saveStreamingState(): FloatArray
fun restoreStreamingState(values: FloatArray): Boolean
```

- `processStreamingChunk` diarizes one chunk. The Sortformer arrival-order speaker cache stays between calls, so a speaker keeps its number in all chunks. The usual `process` call still starts a new recording each time.
- `saveStreamingState` returns the speaker cache, the FIFO and the activity probabilities as one float array.
- `restoreStreamingState` puts a saved array back. It returns `false` if the diarizer cannot use the array. Only the Sortformer implementation supports it.

`org.cassini.engine.speakers.SortformerStream` uses these calls. The checkpoint keeps the saved array (at most 200000 floats) in `diarizer.cache` ([`data-formats.md`](data-formats.md#checkpointjson)).

## The superseded release .1

The GitHub release `native-sherpa-onnx-1.13.7-nemotron.1` (SHA-256 `d03a06f8…`) holds a build without the streaming patch. It does not have `processStreamingChunk`, `saveStreamingState` or `restoreStreamingState`. The app calls these methods, so it cannot use this build. Do not use it. The pin and `scripts/fetch-native-aar.sh` use the release `.2`.

## libopus

The same pass that recognizes the words also encodes the Opus audio with libopus 1.5.2. `scripts/fetch-opus-source.sh` downloads the pinned source into `.tools/opus/source`, and `scripts/setup.sh` runs it. The `:engine` module compiles `libcassini_opus.so` from this source for arm64-v8a and x86_64 (`externalNativeBuild`, CMake 3.22.1, NDK 28.2.13676358, `engine/src/main/cpp/CMakeLists.txt`).

Each variant, debug included, compiles with the CMake Release flags (`-O3 -DNDEBUG`). Thus the encoder gives the same output in each build. If the source is not there, the `checkOpusSource` task stops the build with the message "Missing libopus source … Run scripts/setup.sh."

## Resumable encoding

The checkpoint does not keep the libopus encoder state, because that state holds absolute pointers into the library. It keeps these items:

- The last two encoded frames.
- The incomplete PCM frame.
- The pending Ogg packet.
- The serial number, the sequence number and the granule clock.

At a restore, the app cuts the file tail that the checkpoint did not commit. The new encoder encodes the two saved frames again and does not keep their packets. Thus its lookahead holds the same audio, and the stream continues on the same clock.

The longer-term encoder state does not come back. Thus the packets after a resume point are not always equal to the packets of an encode without a stop. On the x86_64 API 34 emulator they are different from the first packet after the checkpoint. For this reason, `DeviceSmokeTest#opusCheckpointContinuesIdenticalPacketsAfterClosingTheEncoder` has `@Ignore`. `DeviceSmokeTest#opusCheckpointContinuesTheSameStreamAfterClosingTheEncoder` tests what a resume gives:

- Valid framing.
- Equal packets up to the checkpoint.
- The same packet count, granule clock and duration.
- Decoded audio within 1 % of the signal power of the encode without a stop (measured: 0.2 %).

## Source bundle

Each release attaches `cassini-native-sources.tar`. It holds the pinned archives in `sources/`. It also holds these files at their repository paths:

- `scripts/native-dependencies.json` and the JNI patch.
- The AAR and libopus build scripts.
- The JNI wrapper with its CMake files.
- This file and the notice index.

Run `python3 scripts/package-native-sources.py` to make the bundle again. Downloads and archives stay in directories that git ignores.
