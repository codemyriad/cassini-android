# Test fixtures

The `audio/` directory holds a short Italian speech clip. The device tests, the `:data` unit tests and some `:app` unit tests read this clip. No file in this directory comes from a real user. The directory has no voiceprints and no private recordings.

## audio/

`fleurs-it-short.{wav,m4a,mp3,opus}` is one recording from the Italian dev split of Google FLEURS (file `10017639081645832204.wav`, dataset revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`), licensed [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/). Attribution: Google FLEURS contributors. `fleurs-it-short.json` records the source, the reference text, the licence, the conversion and the SHA-256 of each file. The WAV is 16 kHz mono PCM; the other three are ffmpeg re-encodings of it. Nothing was cut or edited.

`app/build.gradle.kts` adds this directory to the androidTest assets. Thus, a test opens `fleurs-it-short.wav` and the other files by name. The `:data` unit tests (Robolectric) also read these files, through the `cassini.fixtures` property that `data/build.gradle.kts` sets. The `:app` unit tests `AppGraphTest`, `ProcessingCoordinatorTest` and `ProcessingServiceTest` read them in the same way, and `app/build.gradle.kts` sets the same property. These tests use the length of the WAV: 253,440 frames (15.84 s). The two builds declare the files as test inputs. Thus, if you make the files again, Gradle runs these tests again. `scripts/fetch-smoke-audio.py` makes the files again from the pinned revision. This script needs ffmpeg. The output of ffmpeg can change between versions. Before you commit new files, examine the diff.

`.gitignore` ignores all other files in `audio/`. Thus, a recording that you copy into `audio/` does not go into a commit.

Three other sets of clips are not in the repository:

- The long clip of the background device tests and of `scripts/device-kill-resume.sh`. `scripts/make-long-clip.sh` makes it on the host with ffmpeg: the FLEURS WAV 20 times in a row, 316.8 s of 16 kHz mono PCM. The script writes it to `app/src/androidTest/assets/generated/fleurs-it-x20.wav`, which `.gitignore` ignores. `scripts/device-smoke.sh` runs the script before it builds the test APK. If the source WAV changes, the script makes the clip again.
- The clips of the acceptance walk. `scripts/acceptance-audio.sh` makes them on the host with ffmpeg: the FLEURS WAV 2, 3, 8 and 23 times in a row, as `app/src/androidTest/assets/generated/acceptance-x<N>.wav`. Each note of the walk has a different clip, so that no import is a copy of another note. `.gitignore` ignores these files.
- The YouTube excerpts from `tools/fetch-youtube-audio.sh`. The script writes them to `app/src/androidTest/assets/`, which `.gitignore` ignores.
- The private meeting clips for `VoiceprintCalibrationTest`. You put them in `tools/private-fixtures/meetings/`, which `.gitignore` ignores. When the clips are in this directory, the build adds them to the `:bench` APK.

## Other test data

- The `:core` tests use the published conformance vectors for the portable `.opus` format. These vectors come from the `third_party/cassini-format` submodule.
- `core/src/test/resources/checkpoint/golden-v1/` holds one `checkpoint.json` and its `names.json`. A 0.1.0 job wrote these two files on an emulator. `CheckpointGoldenTest` uses them to test the codec (the code that reads and writes the checkpoint format).

The 0.2 storage layer will not read app data from 0.0.x or 0.1.0. Thus, there are no captured app-data trees (copies of an old `files/` directory). The golden checkpoint above is the only test data that a 0.1.0 job wrote. It stays on purpose, because 0.2 still reads and writes `checkpoint.json` version 1 (`docs/architecture.md`, section 3.5).
