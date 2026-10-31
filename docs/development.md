# Development notes

This file is for contributors who build, test and examine Cassini for Android 0.2. [`CONTRIBUTING.md`](../CONTRIBUTING.md) gives the first steps and the rules for a change. [`architecture.md`](architecture.md) gives the design. [`data-formats.md`](data-formats.md) gives the files, the tables and the export formats.

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
| `:baselineprofile` | A `com.android.test` module, package `org.cassini.baselineprofile`, that generates the Baseline Profile of `:app` ([Baseline Profile](#baseline-profile)). `ciTest` and CI do not run it. | All that `:app` needs |

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
| Screenshots | `:ui:verifyRoborazziDebug` | Each board compared with its golden, in light, dark, Italian, French and 1.5x font scale |
| Device | `scripts/device-smoke.sh` | Instrumentation tests on an emulator or a phone (see [Device scripts](#device-scripts)) |
| Host persistence | `scripts/device-kill-resume.sh`, `scripts/device-language-check.sh`, `scripts/note-opening-timing.sh` | Process death, the app language and the time to open a note, driven from the host |
| Acceptance | `scripts/acceptance-walk.sh` | An instrumented walk through each board on emulator-5554, with device screenshots that a reviewer compares with the design (see [acceptance-walk.sh](#acceptance-walksh)) |

Robolectric runs at SDK 35. Each module that uses it has `src/test/resources/robolectric.properties` with `sdk=35`. SDK 36 and later need a JDK 21 runtime, and the build uses JDK 17. One test in each module makes sure that `Build.VERSION.SDK_INT` is 35.

All the JVM tests of a module run in one JVM, and the Robolectric tests share static state. Thus work that a test starts and does not stop can fail during a later test. A test that makes an `AppGraph` or its own coroutine scope must stop that work and wait until it ends. `AppGraph.close()` cancels the application scope and waits for its jobs. The `:app` rule `NoUncaughtCoroutines` (`app/src/test/.../testing/UncaughtCoroutines.kt`) makes such a test fail when one of its coroutines fails with an exception that nothing caught. Without the rule, `runTest` shows the exception only at the start of the next `runTest`, which can be in a different class, as `UncaughtExceptionsBeforeTest`.

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

Each ERROR or WARNING of the checks fails the test, with two exceptions. The exceptions are the small touch targets of the Library row suffixes, and the low contrast of the old waveform bars (`rec.barIdle`, permitted in `docs/design/ui.md` section 11). The tests write each finding to logcat with the tag `CassiniA11y`.

One more `@CiSmoke` device test, `flows/SheetAndDraftDeviceTest`, does the checks of `docs/ui-fidelity.md` (device follow-ups 3 and 4) on an edge-to-edge window. With the keyboard open, the Name a speaker sheet must stay below the status bar, and the name field must stay above the keyboard. A second sheet check sets the system font scale to 1.3. Then the Cancel and Save name buttons must be fully above the keyboard. On Transcribing, new draft text must not move the progress card out of view. The test writes a screenshot of each check to `files/device-checks/` in the app data. During the sheet checks, the test sets the secure setting `show_ime_with_hard_keyboard` to 1, so that the soft keyboard opens also on an emulator with a hardware keyboard. The font scale check also sets the system setting `font_scale`. After each check, the test sets the old values again. The sheet checks wait for events, never for a fixed time: the sheet window gets the input focus, the window gets the IME inset, and the sheet stands on the keyboard when the keyboard animation has ended. If one of them does not happen within 5 s, the test fails with the focused window and any system error dialog in the message.

The `@CiSmoke` device test `background/ProcessingServiceTypeTest` makes sure that `ProcessingService` has the correct type during inference. The type is `mediaProcessing` (0x2000) on API 35 or more, and `dataSync` (0x1) on API 29-34. The test gets the type from Android in the app process (`ProcessingService.currentType()`, which calls `Service.getForegroundServiceType()`), because `dumpsys activity services` on API 29 does not show the type. When the dump shows a type (API 30 or more), the test also makes sure that it is the same type. A scripted job takes the place of the transcription, thus the test needs no models. The CI emulators of API 26, 29, 34 and 35 run it. On API 26 the test does not examine the type, because services have no type before API 29.

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
| `AcceptanceWalkTest` | `@Manual` | The acceptance walk of [acceptance-walk.sh](#acceptance-walksh). It saves a screenshot of each board. |
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
    --exclude=files/parakeet-ultra --exclude=files/vad --exclude=files/speaker-models files > files-backup.tar
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

The runner also gives the graph the test seams of `TestHooks`. Each seam does the same as the app until a test sets it. `TestHooks.validatedNetwork` replaces the answer of the network check. `TestHooks.recordingFreeBytes` replaces the free space that the recording sees. On an empty model root, the model fetcher is `ScriptedModels`. This fetcher does the steps of a script from the test, and it does not open a connection. If the script has no more steps, the fetch fails. Thus a run with an empty model root never downloads a model.

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

The script gives `downloadModels=true` to the tests. If the device has no Parakeet Ultra model or no speech detector, `DeviceSmokeTest` downloads them (several hundred MB) through `ModelRepository`. To prevent this download, select only the tests that need no models, for example `--annotation CiSmoke`. The `:bench` experiments do not download models.

The test APK holds the long clip of the background tests ([`fixtures/README.md`](../fixtures/README.md)). The script runs `scripts/make-long-clip.sh` before it builds the test APK. That script needs ffmpeg on the host. If ffmpeg is not there, the script shows a warning and builds the test APK without the clip. Then the tests that use the clip fail. The test APK from CI does not hold the clip.

| Annotation | Tests | Where they run |
|---|---|---|
| `@CiSmoke` | Tests that need no models and no microphone. `scripts/ci-emulator-smoke.sh` selects them. | The CI emulators (API 26, 29, 34 and 35), and a phone on API 35 or more |
| `@NeedsModels` | Tests that need the installed models | emulator-5554, and the weekly CI models job on API 34 |
| `@NeedsMicrophone` | Tests that record from the microphone | An emulator or a phone. Emulator audio can be silence, so these tests examine the files and the states. |
| `@Manual` | Tests that CI does not run, and that need their own inputs. `NoteOpeningTimingTest` needs the arguments of its script. `ModelDownloadFlowTest` needs `--arg emptyModelRoot=true`. `AcceptanceWalkTest` needs the arguments of `scripts/acceptance-walk.sh`. Three `DeviceSmokeTest` tests need the models and the local YouTube clip. Without their inputs, they skip. | By hand, with the command of the test |

Examples:

```sh
scripts/device-smoke.sh --serial emulator-5554 --annotation NeedsModels
scripts/device-smoke.sh --serial emulator-5554 --annotation NeedsMicrophone
scripts/device-smoke.sh --serial emulator-5554 --annotation CiSmoke
scripts/device-smoke.sh --serial emulator-5554 --bench --class org.cassini.bench.DiarizationPocTest
scripts/device-smoke.sh --serial emulator-5554 --class org.cassini.android.flows.ModelDownloadFlowTest \
    --arg emptyModelRoot=true
```

On emulator-5554 (API 34, with the models), the dry runs select 21 `CiSmoke` tests, 6 `NeedsModels` tests and 2 `NeedsMicrophone` tests. `PortableDocumentTest#platformOpusKeepsTheDurationAndTheSpeechClock` operates on API 34 only. Thus the `CiSmoke` count is 20 on the other API levels, for example on the Pixel 8 (API 37).

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

### acceptance-walk.sh

`scripts/acceptance-walk.sh` operates `AcceptanceWalkTest` on emulator-5554 and collects a screenshot of each board.

Before you start the script, install the app on the device, open it and download the models. The walk uses these models and does not download them. If the models are not on the device, the script stops with a message.

The script does these steps:

1. It makes the test clips with `scripts/acceptance-audio.sh` (this needs ffmpeg).
2. It writes a backup of `files/` without the models. Use `--backup FILE` to name the backup file.
3. It builds the debug app and its test APK one time.
4. It operates the walk in three variants: light, dark (`cmd uimode night yes`) and Italian.
5. It copies the screenshots to `build/acceptance/<run>/<variant>/` and deletes them on the device.
6. It makes sure that the model files did not change. It compares a recursive list of the model files and the checksums of their `verified` files before and after the walk.
7. It sets the night mode and the app language back to their values before the run.

```sh
scripts/acceptance-walk.sh --serial emulator-5554
```

Each variant has two parts. Each part is one test method and one instrumentation run with `scripts/device-smoke.sh`:

- `realModels` uses the installed models. It makes the notes through the repositories and the coordinator. Then it opens Library, Recording, Transcribing, Read & listen, Name a speaker, Note actions, Saved voices, Settings and Trash. It also shows the Paused, Not verified, Unreadable and Storage almost full banners.
- `downloadScreens` uses an empty model root and `ScriptedModels`. It shows Model download, Downloading 46%, Checking 80%, No connection, Not enough storage and File check failed.

The test saves each screenshot in its scratch root. At the end of the test, it copies the screenshots to `files/acceptance-out/<variant>/`, because the runner deletes the scratch root when the run ends. In the Italian variant, the test selects Italiano in Settings first and selects Phone at the end.

The test makes these notes for `realModels`:

- A copy of a document with its last audio byte changed. The page check of Ogg fails, so the app cannot verify the audio (Not verified).
- A copy of a document with a changed checksum of the manifest (Can't read transcript).
- One note in the trash, one Audio only note and one paused note.
- One transcription to Done. The test gives its speaker the name Anna Ricci and saves the voice as Anna Ricci and as Paolo Ferri. The two saved voices have the same voiceprint. Thus the draft of a later note asks "Is this Anna Ricci?" and does not give the name automatically.
- One long transcription that runs during the walk, and one note in the queue after it.

To find Storage almost full, the test sets the free space to the floor of the recording plus about 12 minutes at the measured rate of the capture file.

The screenshots in the README come from this walk. To make them again, use these steps:

1. Do the walk for the light and dark variants with `--part models`.
2. Select these screenshots of each variant: `00-library`, `03-read-and-listen`, `02-transcribing`, `04-name-a-speaker` and `08-settings`.
3. Make sure that they show only the FLEURS clip and the test names.
4. Make them 540 pixels wide with Pillow, and make the files smaller with `optipng -o5 -strip all`.
5. Save them as `docs/screenshots/<variant>/01-library.png` to `05-settings.png`, in the order of step 2.
6. Save the light screenshots also as `metadata/en-US/images/phoneScreenshots/1.png` to `5.png`, in the same order.

The Settings screenshot (`05-settings.png` and the store image `5.png`) shows the version number of the app in the About card. The About card shows the product name and the version, for example "Cassini talk 0.3.0". After the owner changes `versionName` for a release, make this screenshot again ([`release.md`](release.md#make-a-release)).

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

`AppGraph.recentWords` keeps the full word lists of the two notes that the user opened last. If the user opens one of these two notes again, the Note screen reads no word rows. This cache did not change the times in the table. The test opens three notes in rotation, and the cache holds only two lists. Thus each open removes from the cache the note that the next round opens first, and each timed open reads the words from Room.

On 10 Oct 2026, before these steps, a later open of the one-hour document took about 0.4 s more than a later open of the 15 s document (median 987 ms against 569 ms).

### Baseline Profile

`app/src/main/baseline-prof.txt` lists the classes and methods of the app that Android compiles ahead of time at install. Without it, the app code runs in the interpreter the first times, and a screen that is composed again from the start is slow. On a Pixel 8 (16 Oct 2026), closing a note took 69 to 98 ms with the code interpreted and about 43 ms with the code compiled.

`BaselineProfileGenerator` in `:baselineprofile` collects the profile. It starts the app, scrolls the Library, opens a note with speech three times (it scrolls the transcript and plays two seconds each time), and opens Settings and Trash. It drives the `releaseTest` build, the release build without R8, so the profile has the real names. R8 rewrites the names when it builds the minified release.

`scripts/baseline-profile.sh` builds the two APKs, installs them with direct adb instrumentation (the app data stays), runs the generator and writes `app/src/main/baseline-prof.txt`. The device needs API 33 or later, English, and at least one transcribed note with speech. The generator does not record, transcribe or change notes. Make the profile again after large changes to the screens, and commit the file.

```sh
scripts/baseline-profile.sh --serial emulator-5554
```

## Acceptance 0.2 (11 Oct 2026)

These are the results of the P12 behavior checks on 11 Oct 2026. The device is emulator-5554 (API 34, x86_64, 2 GB RAM). The app is the debug build of the branch `rebuild` with the P12 corrections. The 1-minute load average of the host was between 5 and 8.

The checks 2 to 6, 8 and 9 use a debug root (`files/debug-root.txt`, see [device-kill-resume.sh](#device-kill-resumesh)). Thus they did not touch the notes of the device. At the end of the checks, the debug roots are deleted. These device values are the same as before the checks: `font_scale` and `animator_duration_scale` are not set, the night mode is `no` and the app language is Phone (`[]`). The files of the models did not change: the `ls -laR` listings before and after are the same, except the time of the parent directory.

| Check | Method | Result |
|---|---|---|
| 1. Process death and resume | `scripts/device-kill-resume.sh --serial emulator-5554` | Passed. See [Process death](#process-death). |
| 2. Stop and Continue | Stop in the processing notification, then Continue in the Library | Passed. See [Stop and Continue](#stop-and-continue). |
| 3. Back on Recording | `adb shell input keyevent KEYCODE_BACK`, then Finish in the recording notification | Passed. See [Recording in the background](#recording-in-the-background). |
| 4. Recording during a transcription | Record while two notes transcribe, then `ffprobe` | Passed: 0.45 % less than the wall clock. See [Recording in the background](#recording-in-the-background). |
| 5. Font scale 2.0 | `adb shell settings put system font_scale 2.0`, then screenshots of Library, Note, Name a speaker and Settings | Passed after two corrections. See [Font scale 2.0](#font-scale-20). |
| 6. Reduced motion | `adb shell settings put global animator_duration_scale 0`, then four Recording screenshots 0.3 s apart | Passed with a limit. See [Reduced motion](#reduced-motion). |
| 7. App language after process death | `scripts/device-language-check.sh --serial emulator-5554` | Passed |
| 8. Foreground service types | `adb shell dumpsys activity services org.cassini.android` during check 4 | Passed: `ProcessingService` has `types=00000001` (`dataSync`) and `RecordingService` has `types=00000080` (`microphone`) |
| 9. Cold start | `adb shell am start -W -n org.cassini.android/.MainActivity` after `am force-stop`, three times | `TotalTime` 3246, 2886 and 2923 ms |
| 10. Note opening time | `scripts/note-opening-timing.sh --serial emulator-5554` | Passed. See [Note opening time](#note-opening-time). |
| Acceptance walk | `scripts/acceptance-walk.sh` | Passed in light, dark and Italian (P12 step 1). See [Acceptance walk runs](#acceptance-walk-runs). |

### Acceptance walk runs

The first walk ran in light, dark and Italian (`build/acceptance/20261011-031058/`). After the corrections of the device review, the `realModels` part ran again in light (`20261011-034439`) and in dark (`20261011-034904`). The first README and store screenshots came from these two runs.

After the last change to the walk (commit `57cc96d`), the full walk passed again on a debug build of that commit: light (`20261011-045343`), dark (`20261011-045602`) and Italian (`20261011-045814`). Each run has the `realModels` part and the `downloadScreens` part. The `build/` directory is not in the repository.

After the fixes of phase F1, the full walk ran again on a debug build. The light and dark variants passed. Two of three runs of the Italian variant failed at Transcribing, because the walk did not find the "Draft transcript" header. The Italian progress card is taller, so the draft list is shorter. Auto-follow then scrolls the end of the first long turn into view, and the header goes above the list. Thus the walk scrolls the list back to the header before it takes the screenshot, as a reader does. With this change, the full walk passed in light, dark and Italian (`20261011-065635`), with the `realModels` part and the `downloadScreens` part. The README and store screenshots come from this run.

### Process death

The script imported `fleurs-it-x20.wav` (316.8 s). The job saved its first checkpoint at 28000 ms. Then the script stopped the process with `kill -9`. Android delivered the service intent again 47 s later, and the job continued in a new process. The job sealed the document with 538 words. The document state is OK.

```text
manifest	ok	kind=cassini-portable-meeting version=1 profile=ogg-opus
words	ok	538 items
checked: the manifest and 1 transcript payload(s), sha256 and byte counts.
```

`check-portable-document.mjs` read the document with the web viewer of `../gocassini` (538 words, 316800 ms). `cassini-opus-digest.py` gives the audio digest `55a12554…f383`. This value is equal to `integrity.opusAudioSha256` in the manifest.

### Stop and Continue

1. Two notes are in the queue: `fleurs-it-x20` transcribes and `acceptance-x8` waits.
2. Push Stop in the processing notification. The notification shows "Stopping and keeping progress…".
3. The job stops at its next checkpoint. `fleurs-it-x20` is Paused (USER) at 01:20. The queue hold is `USER`, and `acceptance-x8` stays Queued. The service stops and the notification goes away.
4. The Library shows "Transcription paused · 1 waiting" with Continue. The paused note shows its draft and the same banner.
5. Push Continue. `acceptance-x8` starts. `fleurs-it-x20` stays Paused until you push Resume.

### Recording in the background

1. While `acceptance-x8` transcribes, push Record.
2. On the Recording screen, push the system back key. The Library shows the card "Recording · Voice note 11 Oct, 03:28" and the Record button "Recording · 00:41". 9 s later, both show 00:50. The transcription continues.
3. Push Finish in the recording notification. The note goes to the queue and the recording service stops.

The time from the push on Record to the push on Finish was 214.16 s. During this time, two notes transcribed. `ffprobe` gives 213.1865 s for `capture.opus` (Opus, 48000 Hz, 1 channel). The difference is 0.97 s (0.45 %), and it includes the start and the stop of the capture. `ffmpeg -f null` decodes the file without an error. The emulator microphone gives silence, so `silencedetect` cannot find a gap.

### Font scale 2.0

The review found two problems. Both are corrected, and a second look on the device found no more cut text:

- In the Library, the suffix "· No speech" was cut to "· No". The line with the time, the chip and the suffix is now a flow row.
- In the player, the "10" of the skip buttons covered the arrow of the icon. The "10" now does not follow the font scale.

In Settings, Name a speaker and the Note screen, all text fits. The rows become taller and the screens scroll.

### Reduced motion

With `animator_duration_scale` at 0, only the timer changes between the four screenshots. The waveform, the status and the buttons do not change. The waveform has no animation of its own. The emulator microphone gives silence, so the waveform is a row of dots at both scales. Thus this check does not show the waveform with sound (follow-up 7 in [`ui-fidelity.md`](ui-fidelity.md)).

### Note opening time

The second run of `scripts/note-opening-timing.sh`, in milliseconds:

| Note | Audio | First open | Later opens | After process death | Later opens after process death |
|---|---|---|---|---|---|
| `timing-one-hour` | 3600 s | 1356 | 757, 625, 539 | 1391 | 807, 675 |
| `timing-ten-minutes` | 586 s | 824 | 760, 692, 640 | 875 | 713, 759 |
| `timing-fleurs` | 15 s | 731 | 436, 506, 478 | 777 | 618, 536 |

The median of the five later opens is 675 ms for one hour, 713 ms for ten minutes and 506 ms for 15 s. Thus the later opens do not increase with the length of the audio, as in the first run of [note-opening-timing.sh](#note-opening-timingsh).

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

- Android uses sherpa-onnx 1.13.7 with the Sortformer patch ([`native-runtime.md`](native-runtime.md)), on the CPU, with two threads and greedy decoding. It uses only Parakeet Ultra INT8. The desktop uses Parakeet TDT 0.6B v3.
- The Android runtime has the NeMo-reference Parakeet feature frontend of upstream pull request [k2-fsa/sherpa-onnx#4024](https://github.com/k2-fsa/sherpa-onnx/pull/4024). The frontend operates automatically for Parakeet v3 and Ultra. The desktop runtime has its own fork with a corrected frontend. Android still decodes with `DecodePolicy.WHOLE_SPANS` (0.5 s of synthetic silence after each span), which we measured with the stock frontend. The reference frontend normalises over the entire input, and the synthetic silence is part of that input. Before a release, compare `WHOLE_SPANS` with `PARAKEET_V3_REFERENCE` on Parakeet Ultra (`ChunkingComparisonTest`), and choose the policy from the result. The KDoc of `DecodePolicy` in `SpeechWindows.kt` still names a stock runtime, because `scripts/check-verbatim.sh` keeps that file as in 0.1.0. [`notes/feasibility.md`](notes/feasibility.md) gives the history.
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

The script shows the restructured groups (the models and the checkpoint) for reference, but it does not compare them.

### Names of the removed 0.1.0 code

The UI and the stores of 0.1.0 are not in the tree. To make sure of this, run this command:

```sh
git grep -n -E 'LibraryActivity|SessionStore|LibraryStore|DocumentStore|ProcessingCheckpoint|AppLanguage' -- ':!CHANGELOG.md' ':!docs/notes' ':!docs/development.md'
```

The command does not search `CHANGELOG.md`, `docs/notes/` and this file, because they name the old code on purpose. The command must find one line only: the `ProcessingCheckpoint` row of the `groups` table in `scripts/check-verbatim.sh`. This row is a permitted exception. It names the 0.1.0 file `app/src/main/java/org/cassini/android/ProcessingCheckpoint.kt` at the base commit, which is not in the tree. The script reads that file from git to show the restructured checkpoint group.

## Continuous integration

Three workflows in `.github/workflows/` do the CI checks. All actions have a pin to a full commit SHA. Dependabot (`.github/dependabot.yml`) updates the Gradle dependencies, the actions and the submodule each week.

| Workflow | Triggers |
|---|---|
| `android.yml` | Each push to `main`, each pull request, each tag `v*` and a manual start (`workflow_dispatch`) |
| `models.yml` | Each Monday and a manual start |
| `native.yml` | Each Monday, a manual start, and a push to `main` or a pull request that changes `scripts/`, `engine/src/main/cpp/`, `engine/build.gradle.kts`, `gradle/libs.versions.toml` or the workflow |

If you push a branch and do not open a pull request, CI does not run. A new run of the same workflow on the same pull request or branch stops the earlier run. A run on a tag always continues to its end.

### android.yml

| Job | What it does |
|---|---|
| `checks` | The Gradle wrapper validation, detekt, actionlint, shellcheck on `scripts/*.sh` and `tools/*.sh`, ruff on `scripts` and `tools`, `scripts/check-links.sh`, `scripts/fetch-fonts.sh --check`, `scripts/check-resource-collisions.py` and `scripts/check-verbatim.sh`. On a tag, it also makes sure that `CHANGELOG.md` has a section for the tag. |
| `core-only` | `./gradlew --configure-on-demand :core:test` with only a JDK and the submodules. It does not run `setup.sh` and it hides the Android SDK of the runner. |
| `build` | `setup.sh --prebuilt-only`, then `ciTest` (with `:ui:verifyRoborazziDebug`), the lint of `:app`, `:data` and `:ui`, the APK, the device test APKs of `:app` and `:data`, and `scripts/package-apk.py`. On a tag, it builds `release` (the APK and the bundle) and `releaseTest` and collects the native sources. |
| `emulator` | The device checks on API 26, 29, 34 and 35 (x86_64 Google APIs images), with `scripts/ci-emulator-smoke.sh`. If the emulator or adb fails before the tests start, the job does all the checks one more time on a new emulator. If a test fails, the job fails and does not try again. |
| `release` | On a tag only, after `checks`, `core-only`, `build` and `emulator` pass. It publishes the GitHub release with the files of `dist/` and the notes of the tag from `CHANGELOG.md` ([`release.md`](release.md)). |
| `play` | On a tag only, after `release`. If the secret `PLAY_SERVICE_ACCOUNT_JSON` is set, it uploads the bundle to the internal testing track of Google Play ([`release.md`](release.md#google-play)). Else it only shows a notice. |

The `build` job uploads these artifacts:

- `cassini-android`: the content of `dist/`, that is the APK, its checksum and the notices. On a tag, also the R8 mapping and the native sources.
- `cassini-android-play`: on a tag only, the content of `play/`, that is the signed bundle (`.aab`), the R8 mapping and the Play release notes.
- `cassini-android-device-tests`: the APK under test and its instrumentation APK.
- `cassini-data-device-tests`: the instrumentation APK of `:data`.
- `android-checks`: the test reports and the lint reports.
- `roborazzi-diffs`: if the job fails, the actual image and the diff of each changed screenshot (`**/build/outputs/roborazzi`).

`scripts/ci-emulator-smoke.sh` takes the API level from `TEST_API` and does these checks:

0. It sets `hide_error_dialogs`, so Android shows no new "Application Not Responding" or "Application Error" dialog and stops the app instead, and it closes such a dialog that is already open by stopping its process. First it waits until every app that is in an ANR has its dialog or has stopped (`mNotResponding` in `dumpsys activity processes`), because the dialog of an ANR that started before the setting can open some time after the first look. Such a dialog stays over every test, so no activity gets window focus and no soft keyboard opens. On API 34 and 35 the launcher sometimes gets one at boot, because the emulator runner injects a key before the launcher has a window. If a dialog stays, the script stops before the tests, so the job tries again on a new emulator. The setting gets its old value at the end.
1. It runs the `@CiSmoke` tests with `scripts/device-smoke.sh`. The dry run of the same selection gives the expected count.
2. It tells `PortableDocumentTest` if the system image has a platform Opus encoder (`expectPlatformOpus`). The images of API 26 and 29 have no encoder, so recordings there are AAC (`capture.m4a`). The image of API 34 has one. If the device does not agree, the test fails. On API 35, the test accepts both results, because no API 35 run has shown the value yet. A device on API 36 or more gets the rules of API 35, because no rule is different there. Thus you can run the script on a phone, for example `TEST_API=37` for a Pixel 8. The test writes the result to logcat (tag `CassiniCompatibility`).
3. On API 26, it runs `org.cassini.data.NoteFtsDeviceTest` from the `:data` test APK with `adb shell am instrument`.
4. It installs the published APK over the tested build and makes sure that the app starts (`scripts/launch-check.sh`).

`PortableDocumentTest#platformOpusKeepsTheDurationAndTheSpeechClock` operates on API 34 only. A selected test that skips makes the job fail. Each emulator job uploads the instrumentation output and logcat as `emulator-api-<level>`.

We did not run `scripts/ci-emulator-smoke.sh` on API 26, 29 or 35 on a local emulator, because the resource limits of the development machine permit only one emulator. We ran it only on emulator-5554 (API 34). The first CI run shows the counts and the encoder result of the other API levels.

### models.yml

The `models` job runs the `@NeedsModels` tests on an API 34 emulator with the real models. It does these steps:

1. It runs `setup.sh --prebuilt-only` and `scripts/make-long-clip.sh`, and builds the debug APK and its test APK.
2. `scripts/fetch-ci-models.py` downloads the models of `scripts/ci-models.json` into `.tools/ci-models` and makes sure that each file has its pinned size and SHA-256. The cache key of this directory comes from the SHA-256 values in the list. The job saves the cache after this check and before the device tests, so a failed test does not cause a new download in the next run. `ModelCatalogTest` makes sure that the list and `ModelCatalog` agree.
3. `scripts/ci-models-smoke.sh` installs the debug APK. Then `scripts/push-models.sh` copies the models into the `files/` directory of the app with `run-as`, and `scripts/device-smoke.sh --annotation NeedsModels` runs the tests.

`scripts/push-models.sh` copies a file only when the device does not have the same SHA-256. It writes the marker files last. To do a check of an emulator that has the models, run `scripts/push-models.sh` with the output of `scripts/fetch-ci-models.py`. If the models are the same, the script copies nothing. `scripts/fetch-ci-models.py` needs Python 3.10 or later. Before Python 3.14, it also needs the `zstd` tool.

### native.yml

The `native` job builds the sherpa-onnx AAR from the pinned source with `setup.sh --from-source` (about 40 minutes). It writes the SHA-256 of the built AAR and of the pinned AAR in the job summary. If they are different, the job shows a warning and does not fail ([`native-runtime.md`](native-runtime.md)). Then it builds the app and runs the JVM tests with the built AAR. If the tests fail, the job uploads the test reports as the artifact `native-aar-test-reports`.

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
