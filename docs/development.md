# Development notes

This file is for contributors who build, test and examine Cassini for Android 0.2. [`CONTRIBUTING.md`](../CONTRIBUTING.md) gives the first steps and the rules for a change. [`architecture.md`](architecture.md) gives the design. [`data-formats.md`](data-formats.md) gives the files, the tables and the export formats.

> **Status.** This file describes the 0.2 target. Some tools in it are planned and are not in the tree yet: the `:bench` module, `CassiniTestRunner`, the test annotations (`@CiSmoke`, `@NeedsModels`, `@NeedsMicrophone`, `@Manual`), the `device-smoke.sh` flags, `DebugCommandReceiver`, `scripts/device-kill-resume.sh`, `scripts/device-language-check.sh`, the API 35 emulator and the weekly models job of CI. Each of these sections says so. Until they come, use the current interfaces that these sections give.

<!-- TODO(P10): Phases P6, P10 and P11 add the tools in the status note. After the merge, compare each name and flag with the code, correct this file, and remove the status note and the "Planned" notes. P6 adds the long clip that device-kill-resume.sh uses: tell how to make it. -->

## Setup

Do these steps one time for each clone:

1. Clone the repository with its submodules:

   ```sh
   git clone --recurse-submodules https://github.com/codemyriad/cassini-android
   ```

2. If the clone has no submodules, run `git submodule update --init`.
3. Install JDK 17. Set `JAVA_HOME` to it before you run `./gradlew`.
4. Install the Android SDK packages: platform `android-37.0`, build tools 36.0.0, CMake 3.22.1 and NDK 28.2.13676358.
5. Set `ANDROID_HOME`, or write `sdk.dir` in `local.properties`.
6. Get the native inputs with `./scripts/setup.sh`.
7. Build the debug APK with `./gradlew :app:assembleDebug`.

`scripts/setup.sh` writes two inputs. The libopus source goes into `.tools/opus/source`. The sherpa-onnx AAR goes into `native/m2/org/`. [`native-runtime.md`](native-runtime.md) tells how the script gets the AAR.

The `:core` tests need only JDK 17 and the submodule:

```sh
./gradlew --configure-on-demand :core:test
```

The flag `--configure-on-demand` stops Gradle from configuring the Android modules. If Gradle configures them, it needs the Android SDK, and it can download the NDK (more than 1 GB). If the submodule is not there, the `:core` tests fail. They do not skip.

These files stay out of commits: `local.properties`, model weights, AAR downloads, recordings, private or YouTube-derived audio, build outputs and SDK paths. Their paths are in `.gitignore`. The committed test inputs are the CC-BY-4.0 FLEURS clip ([`fixtures/README.md`](../fixtures/README.md)), the golden checkpoint in `core/src/test/resources/checkpoint/` and the conformance vectors in the `third_party/cassini-format` submodule.

## Modules

| Module | Content | Build needs |
|---|---|---|
| `:core` | Plain Kotlin on the JVM, package `org.cassini.core`. The portable format, the audio arithmetic, the recognition windows and the speaker rules. The model pins and downloads, the checkpoint codec, the library rules and the export writers. | JDK 17 and the submodule |
| `:engine` | Android library, package `org.cassini.engine`. The sherpa-onnx wrappers, the MediaCodec decoder, the microphone capture, the Opus encoder with its JNI library `libcassini_opus.so`, and `TranscriptionJob` behind its ports. | The Android SDK, the NDK, CMake and `scripts/setup.sh` |
| `:data` | Android library, package `org.cassini.data`. The Room catalogue, the DataStore settings and the file repositories. | The Android SDK |
| `:ui` | Android library, package `org.cassini.ui`. The Compose design system, the stateless screens and the Roborazzi screenshot tests. | The Android SDK |
| `:app` | The application. `AppGraph`, the ViewModels, the navigation, the services, the coordinators and the notifications. | All that `:engine` needs |
| `:bench` | Planned: `settings.gradle.kts` does not include it yet. A `com.android.test` module for experiments and calibration on a device. CI does not run it. | All that `:app` needs |

The dependency graph is in [`architecture.md`](architecture.md), section 2. `tools/asr-eval/` holds a host program that runs the speech recognition pipeline on PCM files. Gradle does not build it. Its README tells how to run it.

## Worktrees

A git worktree shares the repository, but it does not share the ignored files. A new worktree has no `local.properties`, no submodule checkout, no AAR and no libopus source. Do these steps in the new worktree. `MAIN` is the absolute path of a checkout that ran `scripts/setup.sh`. A relative path gives a broken link in step 4, because a link target starts from the directory of the link.

1. Make the worktree:

   ```sh
   git worktree add ../cassini-android-NAME -b BRANCH
   cd ../cassini-android-NAME
   ```

2. Copy the SDK path with `cp MAIN/local.properties .`.
3. Get the submodule with `git submodule update --init`. Each worktree has its own submodule checkout.
4. Link the AAR with `ln -s MAIN/native/m2/org native/m2/org`. Git tracks `native/m2/README.md`, so link only the `org` directory.
5. Link the tools directory with `ln -s MAIN/.tools .tools`. The `.gitignore` entry `.tools` has no slash, so git ignores the link.
6. Run `scripts/verify-native-runtime.sh`. It must say that the AAR matches the pin.

CAUTION: Do not run `scripts/setup.sh --from-source` in a linked worktree. The build writes through the link and replaces the AAR of all worktrees.

CAUTION: Before you remove a worktree, remove the links with `rm .tools native/m2/org`. Do not use `rm -rf .tools/` with a slash, because it deletes the files of `MAIN`.

To remove a worktree, do these steps:

1. In the worktree, remove the links with `rm .tools native/m2/org`.
2. In the worktree, delete the build outputs with `rm -rf build */build engine/.cxx .gradle/configuration-cache`.
3. In a different checkout, run `git worktree remove --force PATH`. The worktree contains a submodule, and without `--force` git does not remove it. Git deletes a link, not its target.

## Test tiers

The root task `ciTest` runs `:core:test`. For each Android module, it runs `verifyRoborazziDebug` if the module has the Roborazzi plugin, else `testDebugUnitTest`. CI runs this command:

```sh
./gradlew detekt ciTest :app:lintDebug :data:lintDebug :ui:lintDebug
```

| Tier | Command | Content |
|---|---|---|
| JVM | `:core:test`, `:engine:testDebugUnitTest`, `:app:testDebugUnitTest` | The engine tests, the codec goldens, the pure rules, `TranscriptionJob` with fakes, the coordinators and the ViewModels |
| Robolectric | `:data:testDebugUnitTest` and the Robolectric tests of the other modules | The repositories and DAOs, the full-text search, the purge order, the catalogue rebuild, the font and license assets |
| Screenshots | `:ui:verifyRoborazziDebug` | Each board compared with its golden, in light, dark, Italian and 1.5x font scale |
| Device | `scripts/device-smoke.sh` | Instrumentation tests on an emulator or a phone (see [Device scripts](#device-scripts)) |
| Host persistence | `scripts/device-kill-resume.sh`, `scripts/device-language-check.sh` (planned) | Process death and the app language, driven from the host |
| Acceptance | Planned | An instrumented walk through each board on emulator-5554, with device screenshots that a reviewer compares with the design |

Robolectric runs at SDK 35. Each module that uses it has `src/test/resources/robolectric.properties` with `sdk=35`. SDK 36 and later need a JDK 21 runtime, and the build uses JDK 17. One test in each module makes sure that `Build.VERSION.SDK_INT` is 35.

### Screenshot tests

The `:ui` tests draw the screens with Robolectric. They compare each picture with a committed PNG file (a golden) in `ui/src/test/screenshots/`. We record and compare the goldens on Linux x86_64 only, because font rendering on other systems can change the pixels.

If you change a screen on purpose, do these steps on Linux x86_64:

1. Run `./gradlew :ui:recordRoborazziDebug`.
2. Run `git diff --stat ui/src/test/screenshots` to find the changed goldens.
3. Examine each changed PNG.
4. Commit the goldens together with the change.

`./gradlew :ui:compareRoborazziDebug` writes the pictures and the differences to `ui/build/outputs/roborazzi/`. If the pixel check fails in CI, CI uploads that directory as the `roborazzi-diffs` artifact.

On macOS or Windows, do not run `ciTest` or `verifyRoborazziDebug`. Do not record or commit goldens there. Run the same tests without the pixel check:

```sh
./gradlew :core:test :engine:testDebugUnitTest :data:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest
```

The Compose accessibility checks (`enableAccessibilityChecks()`) do not operate under Robolectric. Thus the `:ui` tests do not have them. The device flow tests of `:app` will call them on an emulator. These device flow tests are planned and are not in the tree yet.

## Device safety

CAUTION: Do not run the Gradle `connected*` tasks or the managed-device tasks. Their cleanup can uninstall the app, and the uninstall erases the app data and the models (about 770 MB).

CAUTION: Do not run `adb uninstall org.cassini.android` or `adb shell pm clear org.cassini.android`. These commands erase the app data and the models.

Install an APK with `adb install -r`. Run instrumentation with `scripts/device-smoke.sh` or `adb shell am instrument`.

Most device tests use a scratch root (see [CassiniTestRunner](#cassinitestrunner)), and the host scripts use a debug root. Before you run a test that has no scratch root, make a backup of the app files without the models:

```sh
adb exec-out run-as org.cassini.android tar c \
    --exclude=files/parakeet-v3 --exclude=files/vad --exclude=files/speaker-models files > files-backup.tar
```

Before you start a device run, make sure that no other run uses the device. `adb shell ps | grep instrument` shows a run in progress. If a run is in progress, wait for it to end. Do not stop it.

## Device scripts

### CassiniTestRunner

> **Planned.** `CassiniTestRunner` is not in the tree yet. The current runner is `androidx.test.runner.AndroidJUnitRunner`, and the current device tests use the real app root.

The instrumentation runner `org.cassini.android.CassiniTestRunner` gives each run a scratch root. It sets `AppPaths.root` to `files/androidTest-<run>` and the cache to `cacheDir/androidTest-<run>`. The model root stays `filesDir`, so the tests use the installed models. A test for the download screens can ask for an empty scratch model root. The runner deletes its scratch root at the end of the run. Thus the tests do not touch the notes of the user.

The device tests use the committed FLEURS clip as audio.

### device-smoke.sh

`scripts/device-smoke.sh` builds or takes the APKs, installs them with `adb install -r` and runs a selection of tests with `adb shell am instrument`.

> **Planned.** The flags, the dry run and the annotations below are not in the tree yet. The current script takes two positional arguments and uses the device that `adb` selects:
>
> ```sh
> scripts/device-smoke.sh [AUDIO_SAMPLE] [CLASS[#METHOD][,CLASS...]]
> ```
>
> `AUDIO_SAMPLE` is `fleurs` if you do not give it. If you do not give a class, the script runs all device tests. The script passes `downloadModels=true`. If the device has no models, `DeviceSmokeTest` downloads Parakeet and Silero VAD (about 640 MB). To prevent this download, give a class selection that needs no models. If you set `CASSINI_EXPECTED_TESTS`, the run fails when the number of tests is different. To select a device, set `ANDROID_SERIAL`, for example `ANDROID_SERIAL=emulator-5554 scripts/device-smoke.sh`.

| Flag | Meaning |
|---|---|
| `--annotation NAME` | Run the tests with this annotation, for example `CiSmoke` |
| `--class CLASS[#METHOD]` | Run this class or method. You can give the flag more than one time. |
| `--serial SERIAL` | Use this device |
| `--help` | Show the usage |

The script first does a dry run (`am instrument -e log true`) of the same selection to get the expected count. The run fails if the count is different or if a test skips.

| Annotation | Tests | Where they run |
|---|---|---|
| `@CiSmoke` | Tests that need no models | The CI emulators, API 26, 29, 34 and 35 |
| `@NeedsModels` | Tests that need the installed models | emulator-5554, and the weekly CI models job on API 34 |
| `@NeedsMicrophone` | Tests that record from the microphone | An emulator or a phone. Emulator audio can be silence, so these tests examine the files and the states. |
| `@Manual` | Tests that CI does not run | By hand, with `--class` |

Examples:

```sh
scripts/device-smoke.sh --serial emulator-5554 --annotation CiSmoke
scripts/device-smoke.sh --serial emulator-5554 --annotation NeedsModels
scripts/device-smoke.sh --serial emulator-5554 --class org.cassini.android.flows.NoteFlowTest
```

Unlock the phone before a run. The script wakes the phone and dismisses a keyguard without security. If the phone asks for authentication, the script stops.

To test the APKs from CI, set these variables:

- `CASSINI_APK=/absolute/path/app.apk`: the APK under test. The script finds out if it is `debug` or `releaseTest` and builds the matching test APK.
- `CASSINI_TEST_APK=/absolute/path/test.apk`: the test APK from the same CI run.

The CI artifact `cassini-android-device-tests` holds both APKs. The script refuses an APK that R8 minified. R8 removes the members that only the tests call, so the minified `release` APK cannot be instrumented. Kotlin internal names are different in each variant, so use a test APK of the same variant. [`release.md`](release.md) describes `releaseTest`.

### device-kill-resume.sh

> **Planned.** This script and `DebugCommandReceiver` are not in the tree yet.

`scripts/device-kill-resume.sh` makes sure that a transcription continues after the system kills the app process. It does these steps:

1. It copies the long clip into the app cache with `run-as`.
2. It writes `files/debug-root.txt`. This file names a scratch root `files/killtest-<time>`. Only debug builds read it. The models stay in `files/`.
3. It starts a job through the debug-only `DebugCommandReceiver`.
4. It reads a debug status file in the scratch root until the job saved a checkpoint.
5. It kills the process with `adb shell run-as org.cassini.android kill -9 <pid>`.
6. It waits for the system to deliver the service intent again, or it starts the app with `am start`.
7. It reads the status file until the job is done.
8. It copies the document to the host and runs `cassini-extract.py --check` on it. If `../gocassini` exists, it also runs `check-portable-document.mjs`.
9. It deletes `files/debug-root.txt` and the scratch root.

CAUTION: If the script stops before step 9, delete the debug root file with `adb shell run-as org.cassini.android rm files/debug-root.txt`. Until you delete it, the debug app uses the scratch root and does not show your notes.

A host script does this test because the two Android tools do not kill the process in the same way as the system:

- An instrumentation test runs in the app process. If the test kills the process, the test also stops. At the end of each run, the system force-stops the app. A force-stop cancels the service intent, so the system does not start the job again.
- `adb shell am kill` kills only a process that is safe to kill. It does not kill a process that has a foreground service, and `ProcessingService` is a foreground service.

`kill -9` from `run-as` operates with the user ID of the app. It stops the process as a low-memory kill does. The system keeps the `START_REDELIVER_INTENT` intent and starts the service again.

### device-language-check.sh

> **Planned.** This script is not in the tree yet.

`scripts/device-language-check.sh` makes sure that the app language stays after process death. It selects Italiano in Settings with `uiautomator`. Then it kills the process with `kill -9`, starts the app with `am start -W` and finds Italian text in a new `uiautomator dump`. At the end, it sets the language back to the phone language.

## Portable file checks

The `:core` JVM tests run each conformance vector of the `third_party/cassini-format` submodule. The submodule is pinned to the revision that this app conforms to. To run the vectors of another checkout, for example a newer `../cassini-format`, add `-PcassiniConformanceDir=/path/to/spec/conformance`.

To examine a document from a device, do these steps:

1. Copy the document to the host. Use **Save a copy** in the app, or copy the file of a debug build:

   ```sh
   adb exec-out run-as org.cassini.android cat files/notes/NOTE_ID/document.opus > note.opus
   ```

2. Run the reference reader and the digest tool:

   ```sh
   python3 third_party/cassini-format/tools/cassini-extract.py note.opus --check
   python3 third_party/cassini-format/tools/cassini-opus-digest.py note.opus
   ```

3. If `../gocassini` exists, run the reader of the web viewer with Node 24 or later:

   ```sh
   node scripts/check-portable-document.mjs note.opus
   ```

[`data-formats.md`](data-formats.md) tells which files are portable. Only the sealed `.opus` document is a complete portable artifact. A `*.words.json` file from a device test, and a TXT, SRT or VTT export, holds only the words.

## Differences from the desktop runtime

The desktop pipeline is in `../gocassini`. The Android app is different in these ways:

- Android uses sherpa-onnx 1.13.7 with the Sortformer patch ([`native-runtime.md`](native-runtime.md)), on the CPU, with two threads and greedy decoding. It uses only the INT8 Parakeet model.
- Android does not use the modified Parakeet feature frontend of the desktop runtime. [`notes/feasibility.md`](notes/feasibility.md) gives the history.
- Android cuts a recording at speech pauses with the Silero VAD values of the desktop. It cuts a span of more than 28 s at its quietest 30 ms, between 20 s and 25 s after the previous cut. The window arithmetic, the seam alignment and the word gate come from `gocassini` `transcribe/stt.go`, together with their tests.
- The desktop takes the speaker names from the call signaling and does no voice analysis. Android finds the speakers in the audio with Nemotron-3 Sortformer (at most 8, in the order of arrival). It compares them with CAM++ voiceprints that stay on the phone.
- A saved voice gets its name automatically at a cosine similarity of 0.72 or more and at least 0.08 more than the next voice. Android suggests the name from 0.50. These values come from a small calibration on call recordings (`core/src/main/kotlin/org/cassini/core/speakers/VoiceprintSelection.kt`).

[`data-formats.md`](data-formats.md) gives the differences in the documents and the exports.

## Verbatim engine code

The engine code came from 0.1.0 into `:core` and `:engine` with a package rename and a short list of edits. The last field of each row in the `groups` table of `scripts/check-verbatim.sh` gives the permitted edits for that file group. CI runs the script as a gate.

```sh
scripts/check-verbatim.sh --summary   # changed line counts for each file group
scripts/check-verbatim.sh Parakeet    # the diff of one group
```

The script removes `package` lines, `import` lines and the `internal` modifier before it compares. It ignores changes in white space (`--exact` shows them). It needs the base commit, so a shallow clone needs `git fetch --unshallow`.

The script fails in two conditions. A "package only" group has a difference, or the changed lines of a group are not equal to the reviewed lines in `scripts/check-verbatim.baseline`. If you change verbatim code on purpose, do these steps:

1. Read the diff of the group with `scripts/check-verbatim.sh GROUP`.
2. Make sure that the `groups` table permits each edit. If an edit is new, add it to the table.
3. Record the diff as reviewed with `scripts/check-verbatim.sh --update GROUP`.
4. Commit the baseline together with the change.

The script shows the restructured groups (`Models`, `ProcessingCheckpoint`) for reference, but it does not compare them.

## Continuous integration

`.github/workflows/android.yml` runs on each push to `main`, each pull request, each tag `v*`, a weekly schedule and a manual start (`workflow_dispatch`). If you push a branch and do not open a pull request, CI does not run. [`architecture.md`](architecture.md), section 8, gives the jobs. They are the static checks, a `:core` job with only a JDK, the build and an emulator matrix. A weekly job builds the native runtime. A weekly job that tests the models is planned.

> **Planned.** The current emulator matrix is API 26, 29 and 34. API 35 and the models job come with the final CI workflow. On a tag, the release job publishes the release. A selected device test that skips makes the job fail. The emulator jobs save the instrumentation output and logcat.

## Pre-release gate

CI does not do these checks. Before the owner makes a release tag, do them by hand with the release candidate:

1. On a Pixel 8 (API 37), record a note, transcribe it with speakers, name a speaker, export the note and transcribe it again.
2. Play music in another app. Then start a recording. Make sure that the music continues and the recording does not pause.
3. Start a recording on an emulator. Send a call with `adb emu gsm call 5551234`. Make sure that the recording pauses.
4. End the call with `adb emu gsm cancel 5551234`. Make sure that the recording stays paused until you push Resume.
5. On an API 35 or 36 AVD, start a transcription. Make sure that `adb shell dumpsys activity services org.cassini.android` shows the type `mediaProcessing`.
6. Make the time limit short with `adb shell device_config put activity_manager media_processing_fgs_timeout_duration 60000`.
7. Wait for the limit. Make sure that the notification "Paused by Android" ("Paused at … of …. Open to resume.") shows and that the note shows Paused, not an error.
8. Reset the limit with `adb shell device_config delete activity_manager media_processing_fgs_timeout_duration`.
9. Copy a document that the new engine sealed. Run `cassini-extract.py --check` and `check-portable-document.mjs` on it (see [Portable file checks](#portable-file-checks)).
10. Write the results in a dated file in [`notes/`](notes/).

The `device_config` key comes from the Android 15 documentation of the foreground service timeouts. If the AVD does not accept it, read the documentation for the current key.
