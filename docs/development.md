# Development notes

This file is for contributors who build, test and examine Cassini for Android 0.2. [`CONTRIBUTING.md`](../CONTRIBUTING.md) gives the first steps and the rules for a change. [`architecture.md`](architecture.md) gives the design. [`data-formats.md`](data-formats.md) gives the files, the tables and the export formats.

> **Status.** This file describes the 0.2 target. Some tools in it are planned and are not in the tree yet: the API 35 emulator and the weekly models job of CI. Each of these sections says so.

<!-- TODO(P11): Phase P11 adds the tools in the status note. When they are in the tree, compare each name and flag with the code, correct this file, and remove the status note and the "Planned" notes. -->

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
| `:bench` | A `com.android.test` module, package `org.cassini.bench`, for experiments and calibration on a device. It instruments the debug build of `:app` and uses the models that the app installed. `ciTest` and CI do not run it. | All that `:app` needs |

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
| Host persistence | `scripts/device-kill-resume.sh`, `scripts/device-language-check.sh`, `scripts/note-opening-timing.sh` | Process death, the app language and the time to open a note, driven from the host |
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

The Compose accessibility checks (`enableAccessibilityChecks()`) do not operate under Robolectric. Thus the `:ui` tests do not have them. Two `@CiSmoke` device tests of `:app` run them on an emulator:

- `flows/ScreenAccessibilityTest` checks the Library, the Note screen (reading and processing), Recording and Settings in board states.
- `flows/AppAccessibilityTest` checks the Library and Settings of the real app.

Each ERROR or WARNING of the checks fails the test, with three exceptions. The exceptions are the small touch targets of inline word links and of the Library row suffixes, and the low contrast of the old waveform bars (`rec.barIdle`, permitted in `docs/design/ui.md` section 11). The tests write each finding to logcat with the tag `CassiniA11y`.

`app/src/test/.../nav/AppShellTest` runs the whole app under Robolectric on a scratch root. It tests the back order (a sheet, then the search, then the screen), the minimised recording, the predictive back callback, the edge-to-edge insets, the system bar icons of each screen and the TalkBack semantics of the Note screen.

### Device flow tests

The tests in `app/src/androidTest/.../flows/` start the real `MainActivity` on the scratch root of `CassiniTestRunner`. They wait for the state flows of the graph or for the screen, and they do not use fixed delays. `FlowSupport.kt` holds the shared parts.

| Test | Annotation | What it does |
|---|---|---|
| `LibraryFlowTest` | `@CiSmoke` | It shows the empty Library. It opens the FLEURS clip through ACTION_VIEW, from a `file://` copy in the scratch cache. The Note screen opens, with the Library under it. The same file again gives the same note. The row is Audio only or Queued. Search finds a word of another note. A long press opens the actions sheet. Move to trash and Undo restore the note. |
| `PortableDocumentTest` | `@CiSmoke` | It writes the encoder capability of the device to logcat (tag `CassiniCompatibility`). It makes a document with the libopus encoder of the app and reads it again. On API 34 only (`@SdkSuppress`), it encodes the clip with the platform Opus encoder and makes sure that the duration and the speech times stay the same. |
| `RecordingFlowTest` | `@NeedsMicrophone` | It records for 5 s and minimises the recording with back. The Library shows the recording card. It sends the Finish action of the notification, and the note shows in the Library. |
| `NoteFlowTest` | `@NeedsModels` | It transcribes the FLEURS clip from its note and shows the progress card, the words and a seek on a word tap. Transcribe again adds version 2 to the Versions sheet. Save a copy gives the same bytes as `notes/<id>/document.opus`. The TXT, SRT and VTT exports are equal to the goldens. |
| `SpeakerNamingFlowTest` | `@NeedsModels` | It saves a voice from a first transcription. The draft of a second transcription asks "Is this Luca?". "Yes, Luca" names the speaker. The sealed document has the label, and Saved voices shows Luca with 1 note. |
| `NoteOpeningTimingTest` | `@Manual` | The timing check of [note-opening-timing.sh](#note-opening-timingsh). |
| `ModelDownloadFlowTest` | `@Manual` | On an empty model root, Settings shows "Not downloaded". The row opens the consent screen, its sources row opens Licenses, and "Not now" goes back to Settings. Nothing is downloaded. Run it with `scripts/device-smoke.sh --class org.cassini.android.flows.ModelDownloadFlowTest --arg emptyModelRoot=true`. |

An `Instrumentation.ActivityMonitor` gives the result of the system file picker (`ACTION_CREATE_DOCUMENT`) in `NoteFlowTest`. The result is a file in the scratch cache, so no picker opens.

`SpeakerNamingFlowTest` saves the same voiceprint as two persons, Luca and Marco. The two scores are equal, so `VoiceMatcher` makes a suggestion and does not name the speaker automatically. The second transcription is the second half of the clip, twelve times. Thus the draft stays on the screen for some time.

The export goldens are in `app/src/androidTest/assets/goldens/`. We recorded them on emulator-5554 (API 34, x86_64). In the TXT golden, the second line is `<date> · 00:15`, because the date of the note changes at each run. If a golden is not there or is different, `NoteFlowTest` fails. Then it writes the export to `cache/goldens-candidate/` of the app. To record a golden again, do these steps:

1. Run `scripts/device-smoke.sh --serial emulator-5554 --class org.cassini.android.flows.NoteFlowTest`.
2. Copy the candidate with `adb exec-out run-as org.cassini.android cat cache/goldens-candidate/fleurs-it-short.txt`.
3. Examine the candidate, and then put it in `app/src/androidTest/assets/goldens/`.
4. Delete `cache/goldens-candidate/` on the device with `run-as`.

## Device safety

CAUTION: Do not run the Gradle `connected*` tasks or the managed-device tasks. Their cleanup can uninstall the app, and the uninstall erases the app data and the models (about 770 MB).

CAUTION: Do not run `adb uninstall org.cassini.android` or `adb shell pm clear org.cassini.android`. These commands erase the app data and the models.

Install an APK with `adb install -r`. Run instrumentation with `scripts/device-smoke.sh` or `adb shell am instrument`.

Most device tests use a scratch root (see [CassiniTestRunner](#cassinitestrunner)), and the host scripts use a debug root. Before you run a test that has no scratch root, make a backup of the app files without the models:

```sh
adb exec-out run-as org.cassini.android tar c \
    --exclude=files/parakeet-v3 --exclude=files/vad --exclude=files/speaker-models files > files-backup.tar
```

If the test damaged or erased your notes, restore the backup. First stop the app, then extract the backup into the app storage:

```sh
adb shell am force-stop org.cassini.android
adb exec-in run-as org.cassini.android tar xf - < files-backup.tar
```

The restore writes back each file in the backup. It does not delete the files that the test added.

Before you start a device run, make sure that no other run uses the device. `adb shell ps -A -o ARGS | grep '[i]nstrument'` shows a run in progress. Use `-o ARGS`: the process name of a run is `app_process`, and only its arguments contain `instrument`. If a run is in progress, wait for it to end. Do not stop it.

## Device scripts

### CassiniTestRunner

The instrumentation runner `org.cassini.android.CassiniTestRunner` gives each run a scratch root. It sets `AppPaths.root` to `files/androidTest-<run>` and the cache to `cacheDir/androidTest-<run>`. The model root stays `filesDir`, so the tests use the installed models. A test for the download screens can ask for an empty scratch model root with `-e emptyModelRoot true`. The runner deletes its scratch root at the end of the run. At the start of a run, it deletes the scratch roots that an earlier run did not delete. Thus the tests do not touch the notes of the user. The 0.1.0 device tests still use `filesDir`.

The device tests use the committed FLEURS clip as audio.

### device-smoke.sh

`scripts/device-smoke.sh` builds or takes the APKs, installs them with `adb install -r` and runs a selection of tests with `adb shell am instrument`.

| Flag | Meaning |
|---|---|
| `--annotation NAME` | Run the tests with this annotation, for example `NeedsModels` |
| `--class CLASS[#METHOD]` | Run this class or method. You can give the flag more than one time. |
| `--serial SERIAL` | Use this device. If you do not give it, the script uses `ANDROID_SERIAL` or the only device. |
| `--sample NAME` | The audio sample of the 0.1.0 tests: `fleurs` (the default), `youtube`, `youtube-long` or `cutting` |
| `--arg KEY=VALUE` | Give one more instrumentation argument. You can give the flag more than one time. |
| `--bench` | Operate the `:bench` experiments, not the app tests. Select them with `--class`. The script builds and installs the debug build only. |
| `--help` | Show the usage |

The script first does a dry run (`am instrument -e log true`) of the same selection to get the expected count. The run fails if a test fails or skips, or if the count is different. If you set `CASSINI_EXPECTED_TESTS`, the run also fails when the count is not equal to it.

The script gives `downloadModels=true` to the tests. If the device has no Parakeet model or no speech detector, `DeviceSmokeTest` downloads them (several hundred MB) through `ModelRepository`. To prevent this download, select only the tests that need no models, for example `--annotation CiSmoke`. The `:bench` experiments do not download models.

The test APK holds the long clip of the background tests ([`fixtures/README.md`](../fixtures/README.md)). The script runs `scripts/make-long-clip.sh` before it builds the test APK. That script needs ffmpeg on the host. If ffmpeg is not there, the script shows a warning and builds the test APK without the clip. Then the tests that use the clip fail. The test APK from CI does not hold the clip.

| Annotation | Tests | Where they run |
|---|---|---|
| `@CiSmoke` | Tests that need no models and no microphone. `scripts/ci-emulator-smoke.sh` selects them. | The CI emulators, API 26, 29, 34 and 35 |
| `@NeedsModels` | Tests that need the installed models | emulator-5554, and the weekly CI models job on API 34 |
| `@NeedsMicrophone` | Tests that record from the microphone | An emulator or a phone. Emulator audio can be silence, so these tests examine the files and the states. |
| `@Manual` | Tests that CI does not run, and that need their own inputs. `NoteOpeningTimingTest` needs the arguments of its script. `ModelDownloadFlowTest` needs `--arg emptyModelRoot=true`. Three `DeviceSmokeTest` tests need the models and the local YouTube clip. Without their inputs, they skip. | By hand, with the command of the test |

Examples:

```sh
scripts/device-smoke.sh --serial emulator-5554 --annotation NeedsModels
scripts/device-smoke.sh --serial emulator-5554 --annotation NeedsMicrophone
scripts/device-smoke.sh --serial emulator-5554 --annotation CiSmoke
scripts/device-smoke.sh --serial emulator-5554 --bench --class org.cassini.bench.DiarizationPocTest
scripts/device-smoke.sh --serial emulator-5554 --class org.cassini.android.flows.ModelDownloadFlowTest \
    --arg emptyModelRoot=true
```

On emulator-5554 (API 34, with the models), the dry runs select 17 `CiSmoke` tests, 6 `NeedsModels` tests and 2 `NeedsMicrophone` tests. `PortableDocumentTest#platformOpusKeepsTheDurationAndTheSpeechClock` operates on API 34 only. Thus the `CiSmoke` count is 16 on the other API levels.

The YouTube samples are not in the repository. `tools/fetch-youtube-audio.sh` gets them (it needs `uvx yt-dlp` and ffmpeg). `DeviceSmokeTest#opusCheckpointContinuesIdenticalPacketsAfterClosingTheEncoder` always has `@Ignore`, and an ignored test makes the run fail. Thus remove it from a class selection:

```sh
tools/fetch-youtube-audio.sh
scripts/device-smoke.sh --sample youtube --class org.cassini.android.DeviceSmokeTest \
    --arg notClass=org.cassini.android.DeviceSmokeTest#opusCheckpointContinuesIdenticalPacketsAfterClosingTheEncoder
```

Unlock the phone before a run. The script wakes the phone and dismisses a keyguard without security. If the phone asks for authentication, the script stops.

To test the APKs from CI, set these variables:

- `CASSINI_APK=/absolute/path/app.apk`: the APK under test. The script finds out if it is `debug` or `releaseTest` and builds the matching test APK.
- `CASSINI_TEST_APK=/absolute/path/test.apk`: the test APK from the same CI run.

The CI artifact `cassini-android-device-tests` holds both APKs. The script refuses an APK that R8 minified. R8 removes the members that only the tests call, so the minified `release` APK cannot be instrumented. Kotlin internal names are different in each variant, so use a test APK of the same variant. [`release.md`](release.md) describes `releaseTest`.

### device-kill-resume.sh

`scripts/device-kill-resume.sh` makes sure that a transcription continues after the system kills the app process. It does these steps:

1. It makes the long clip with `scripts/make-long-clip.sh` (this needs ffmpeg) and copies it into the app cache with `run-as`.
2. It writes `files/debug-root.txt`. This file names a scratch root `files/killtest-<time>`. Only debug builds read it. The models stay in `files/`.
3. It imports the clip through the debug-only `DebugCommandActivity`. The import starts a transcription job.
4. It reads a debug status file in the scratch root until the job saved a checkpoint.
5. It kills the process with `adb shell run-as org.cassini.android kill -9 <pid>`.
6. It waits for the system to deliver the service intent again. If the system does not do this in time, it opens `DebugCommandActivity` with `am start`.
7. It reads the status file until the job is done.
8. It copies the document to the host and runs `cassini-extract.py --check` on it. If `../gocassini` exists, it also runs `check-portable-document.mjs`.
9. It deletes `files/debug-root.txt` and the scratch root.

CAUTION: If the script stops before step 9, delete the debug root file with `adb shell run-as org.cassini.android rm files/debug-root.txt`. Until you delete it, the debug app uses the scratch root and does not show your notes.

A host script does this test because the two Android tools do not kill the process in the same way as the system:

- An instrumentation test runs in the app process. If the test kills the process, the test also stops. At the end of each run, the system force-stops the app. A force-stop cancels the service intent, so the system does not start the job again.
- `adb shell am kill` kills only a process that is safe to kill. It does not kill a process that has a foreground service, and `ProcessingService` is a foreground service.

`kill -9` from `run-as` operates with the user ID of the app. It stops the process as a low-memory kill does. The system keeps the `START_REDELIVER_INTENT` intent and starts the service again.

### device-language-check.sh

`scripts/device-language-check.sh` makes sure that the app language stays after process death. It selects Italiano in Settings with `uiautomator dump` and `input tap`. Then it kills the process with `run-as org.cassini.android kill -9`, starts the app with `am start -W` and finds Italian text in a new `uiautomator dump`. At the end, it sets the language back to Phone.

```sh
scripts/device-language-check.sh --serial emulator-5554
```

The script uses the installed debug app and its own data. It does not change notes or models. Before you start it, set the device to a language that is not Italian, and set the app language to Phone. An instrumentation test cannot do this check, because the test stops when its process stops.

### note-opening-timing.sh

`scripts/note-opening-timing.sh` measures the time from a tap on a Library row to the first transcript turn on the screen. It uses three documents: about one hour (the FLEURS clip 227 times, 6129 words, 25.6 MB), about ten minutes (the clip 37 times, 999 words) and the 15 s FLEURS clip (one turn). It measures the first open, later opens and the opens after process death. The notes take turns in each round, so that the warm-up of the process does not help one note more than the others.

The script operates `NoteOpeningTimingTest` two times with `-e keepRoot timing`. With this argument, `CassiniTestRunner` keeps the scratch root `files/androidTest-kept-timing` after the run. The first run imports the documents and opens each document four times. Then the script force-stops the app. The second run opens each document three times in a new process. At the end, the script deletes the scratch root. Install the debug app and its test APK before you start the script.

```sh
scripts/note-opening-timing.sh --serial emulator-5554
```

Results on emulator-5554 (API 34, x86_64), 11 Oct 2026, in milliseconds:

| Note | Audio | First open | Later opens | After process death | Later opens after process death |
|---|---|---|---|---|---|
| `timing-one-hour` | 3600 s | 1257 | 740, 641, 575 | 1290 | 776, 690 |
| `timing-ten-minutes` | 586 s | 821 | 840, 765, 673 | 877 | 740, 636 |
| `timing-fleurs` | 15 s | 655 | 503, 442, 405 | 653 | 679, 541 |

The times include the 250 ms screen transition and the time of the test to find the turn. The test finds the turn when the main thread has no more work, so the time includes the drawing of the first screen. The first open of the one-hour document is fast, because the import keeps the result of the check and the words in Room. The app does not read the document again at the first open.

Later opens do not increase with the length of the audio: the median of the later opens is 690 ms for one hour and 740 ms for ten minutes. The 15 s note is faster (503 ms), because its screen has only one turn to draw. The Note screen does these steps to get this result:

- It shows the first page of 400 words first. It loads the other words after the frame that draws the first turns.
- It prepares the player after that frame, because `MediaPlayer.prepare` of a long file takes about 0.5 s.
- `AppGraph.recentWords` keeps the full word lists of the two notes that were opened last. A later open of the same version reads no word rows.

On 10 Oct 2026, before these steps, a later open of the one-hour document took about 0.4 s more than a later open of the 15 s document (median 987 ms against 569 ms).

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
3. Start a recording on an emulator. Send a call with `adb emu gsm call 5551234`, then answer it with `adb emu gsm accept 5551234`. Make sure that the recording pauses and shows "Paused — another app is using audio". Android silences the recording only when the call is active, not while the phone rings.
4. End the call with `adb emu gsm cancel 5551234`. Make sure that the recording stays paused until you push Resume.
5. On an API 35 or 36 AVD, start a transcription. Make sure that `adb shell dumpsys activity services org.cassini.android` shows the type `mediaProcessing`.
6. Make the time limit short with `adb shell device_config put activity_manager media_processing_fgs_timeout_duration 60000`.
7. Wait for the limit. Make sure that the notification "Paused by Android" ("Paused at … of …. Open to resume.") shows and that the note shows Paused, not an error.
8. Reset the limit with `adb shell device_config delete activity_manager media_processing_fgs_timeout_duration`.
9. Copy a document that the new engine sealed. Run `cassini-extract.py --check` and `check-portable-document.mjs` on it (see [Portable file checks](#portable-file-checks)).
10. Write the results in a dated file in [`notes/`](notes/).

The `device_config` key comes from the Android 15 documentation of the foreground service timeouts. If the AVD does not accept it, read the documentation for the current key.
