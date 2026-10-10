# Contributing to Cassini for Android

This file tells you how to get the code, build the app, run the tests and send a change. Cassini for Android is free software under the [GPLv3](LICENSE). By sending a change, you agree that we publish it under this license.

More detail is in these documents:

- [`docs/development.md`](docs/development.md): the modules, the test tiers and the device checks.
- [`docs/architecture.md`](docs/architecture.md): the modules, the storage, the background work and the build.
- [`docs/native-runtime.md`](docs/native-runtime.md): the sherpa-onnx AAR and how to build it from source.
- [`docs/data-formats.md`](docs/data-formats.md): the storage layout, the portable document and the exports.
- [`docs/release.md`](docs/release.md): the release steps, the signing and the F-Droid draft.

All people in this project obey the [Code of Conduct](CODE_OF_CONDUCT.md). To report a security problem, read [`SECURITY.md`](SECURITY.md). Do not open a public issue for it.

## Get the code

1. Clone the repository with its submodules:

   ```sh
   git clone --recurse-submodules https://github.com/codemyriad/cassini-android
   cd cassini-android
   ```

2. If you have a clone without the submodules, run `git submodule update --init` one time.

The `third_party/cassini-format` submodule holds the conformance vectors of the portable format. The `:core` tests read these vectors. If the submodule is not there, the tests fail. They do not skip.

## Install the tools

You need these tools:

- JDK 17. Gradle runs only with JDK 17. Set `JAVA_HOME` to a JDK 17 before you run `./gradlew`.
- For the Android modules, the Android SDK with platform `android-37.0`, build tools 36.0.0, CMake 3.22.1 and NDK 28.2.13676358. Set `ANDROID_HOME`, or set `sdk.dir` in `local.properties`. Do not commit `local.properties`.
- For `scripts/setup.sh` and the other scripts: `bash`, `python3`, `curl`, `tar` and the GNU coreutils `sha256sum`. On macOS, install coreutils from Homebrew.
- For the device tests: `adb` from the Android SDK platform tools.

We test the setup and device scripts on Linux. On Windows, run them in WSL.

## Run the engine tests without the native tools

The `:core` module is plain Kotlin on the JVM. It holds the portable format, the audio arithmetic, the speaker logic and the model pins. Its tests need only JDK 17 and the submodule:

```sh
JAVA_HOME=/path/to/jdk-17 ./gradlew --configure-on-demand :core:test
```

This command needs no Android SDK, no NDK, no `scripts/setup.sh` and no device. The `--configure-on-demand` flag stops Gradle from configuring `:engine` and `:app`. If Gradle configures these modules, it needs the Android SDK, and it can download the NDK.

## Build the app

1. Get the native inputs:

   ```sh
   ./scripts/setup.sh
   ```

2. Build the debug APK:

   ```sh
   ./gradlew :app:assembleDebug
   ```

3. Install the APK on a device or an emulator:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

`scripts/setup.sh` gets two inputs. Both are pinned by SHA-256:

- The libopus source, in `.tools/opus/source`. Gradle compiles `libcassini_opus.so` from it.
- The sherpa-onnx AAR, in `native/m2`. The script downloads the prebuilt AAR from the GitHub release. If the download is not possible, the script builds the AAR from source.

`scripts/setup.sh --prebuilt-only` stops with an error if it cannot get the prebuilt AAR. `scripts/setup.sh --from-source` always builds the AAR from source. This build takes about 25 minutes.

## When you need the NDK

You need NDK 28.2.13676358 in these cases:

- Gradle configures `:engine` or `:app`. The `:engine` module compiles `libcassini_opus.so` with CMake and the NDK. If the SDK does not have this NDK, the Android Gradle plugin downloads it (more than 1 GB) before the first task starts.
- You run `scripts/setup.sh --from-source`.
- `scripts/setup.sh` cannot download the prebuilt AAR, and it builds the AAR from source. To stop with an error instead, run `scripts/setup.sh --prebuilt-only`.

You do not need the NDK for `./gradlew --configure-on-demand :core:test`.

## Run the tests

Before you send a change, run the checks that CI runs:

```sh
./gradlew detekt ciTest :app:lintDebug :data:lintDebug :ui:lintDebug
```

The `ciTest` task runs the tests of these tiers:

| Tier | Gradle task | What it needs |
|---|---|---|
| JVM | `:core:test` | JDK 17 and the submodule |
| Android unit and Robolectric (SDK 35) | `:engine:testDebugUnitTest`, `:data:testDebugUnitTest`, `:app:testDebugUnitTest` | The Android SDK and `scripts/setup.sh` |
| Screenshots (Roborazzi) | `:ui:verifyRoborazziDebug` | Linux x86_64 |

The device tests are not in `ciTest`. Read [Device tests](#device-tests).

### Screenshot goldens

The `:ui` tests draw the screens with Robolectric and compare each picture with a committed PNG file (a golden) in `ui/src/test/screenshots/`. We record and compare the goldens on Linux x86_64 only. On other systems, font rendering can change the pixels.

On Linux x86_64, use these tasks:

```sh
./gradlew :ui:verifyRoborazziDebug    # compare with the goldens (ciTest runs it)
./gradlew :ui:compareRoborazziDebug   # write the pictures and the differences to ui/build/outputs/roborazzi/
./gradlew :ui:recordRoborazziDebug    # write new goldens
```

If you change a screen on purpose, do these steps:

1. Run `./gradlew :ui:recordRoborazziDebug`.
2. Examine each changed PNG with `git diff --stat ui/src/test/screenshots` and an image viewer.
3. Commit the goldens together with the change.

On macOS or Windows, do not run `ciTest`, and do not record or commit goldens. Run the same tests without the pixel comparison. CI does the pixel comparison.

```sh
./gradlew :core:test :engine:testDebugUnitTest :data:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest
```

## Device tests

CAUTION: Do not run the Gradle `connected*` tasks or the managed-device tasks. These tasks can uninstall the app. The uninstall erases the app data and the downloaded models (about 770 MB).

CAUTION: Do not run `adb uninstall org.cassini.android` or `adb shell pm clear org.cassini.android`. These commands erase the app data and the downloaded models.

Use `scripts/device-smoke.sh` for the device tests. The script installs the app and the test APK with `adb install -r`. Then it runs the instrumentation with `adb shell am instrument`. This keeps the app data and the models.

The script tells the tests to download the models (`downloadModels=true`). If the device has no models, `DeviceSmokeTest` downloads Parakeet and Silero VAD (about 640 MB). To prevent this download, give a class selection that needs no models. [`docs/development.md`](docs/development.md) gives the arguments of the script.

If you install an APK by hand, use `adb install -r`.

## Code style

- Kotlin: [detekt](https://detekt.dev) 1.23.8 with the `detekt-formatting` rules. Run `./gradlew detekt`. Autocorrect is off, because it changes the verbatim engine code. Correct each finding by hand.
- Kotlin syntax: detekt reads the code with a Kotlin 2.0 parser. Do not use guard conditions in `when`, non-local `break` or `continue`, context parameters or explicit backing fields.
- Format: `.editorconfig` sets 4 spaces and 140 columns.
- Python: [ruff](https://docs.astral.sh/ruff/). From the repository root, run `ruff check --config config/ruff.toml scripts`.
- Shell: [ShellCheck](https://www.shellcheck.net). Run `shellcheck -x scripts/*.sh tools/*.sh`.
- Markdown: run `scripts/check-links.sh`. It finds broken relative links.
- Code comments and KDoc are in plain English. The documentation follows ASD-STE100 Simplified Technical English: short sentences, active voice and simple tenses.

## Verbatim engine code

The engine code came from Cassini 0.1.0 into `:core` and `:engine`. We changed the package names and a short list of other lines. The code is tested and the portable format depends on it. Thus each change to this code gets a review.

`scripts/check-verbatim.sh` compares the code with the base commit `0ebea43` (0.1.0 and two later fixes). CI runs it. The script fails in these conditions:

- A "package only" group has a difference.
- The changed lines of a verbatim group are not equal to the reviewed lines in `scripts/check-verbatim.baseline`.

If you change verbatim engine code on purpose, do these steps:

1. Read the difference of the group: `scripts/check-verbatim.sh Parakeet` (use the name of your group).
2. Make sure that the `groups` table in `scripts/check-verbatim.sh` permits each edit. If an edit is new, add it to that table.
3. Record the difference as reviewed: `scripts/check-verbatim.sh --update Parakeet`.
4. Commit `scripts/check-verbatim.baseline` together with the change.

`scripts/check-verbatim.sh --summary` shows the number of changed lines for each group. The script needs the base commit. In a shallow clone, run `git fetch --unshallow` first.

## Files that stay out of the repository

Do not commit these files:

- Model weights and AAR downloads.
- Recordings, transcripts and voiceprints of real people, and private or YouTube-derived audio.
- Build outputs, `local.properties` and other paths of your computer.

The test inputs that we commit are in [`fixtures/`](fixtures/README.md). The FLEURS clip there has a CC-BY-4.0 license.

## The portable format

The `.opus` document format is an external contract. It must stay compatible with [cassini-format](https://github.com/codemyriad/cassini-format) and the desktop app [gocassini](https://github.com/codemyriad/gocassini). If Android does a thing in a different way from the desktop app, write the difference in the documentation. A word-body JSON export (words and speaker labels only) is not the same as a complete portable `.opus` document (audio, timed words, provenance and integrity data). Keep the two names separate in code and documentation.

## Sign off your commits

We use the [Developer Certificate of Origin 1.1](https://developercertificate.org) (DCO). With the sign-off, you state that you wrote the change, or that you have the right to send it under the GPLv3.

To add the sign-off, commit with `-s`:

```sh
git commit -s -m "Add the export menu"
```

Git adds a line `Signed-off-by: Your Name <you@example.com>` with your name and email from the git configuration. Each commit in a pull request must have this line. If you forgot it, update your local `main` from the main repository, run `git rebase --signoff main`, and push the branch again with `git push --force-with-lease`. The rebase changes the commits, so a plain push fails.

## Send a pull request

1. Make one branch for each topic.
2. Run the checks of [Run the tests](#run-the-tests).
3. Open a pull request and fill in the template.

Do not attach private recordings, transcripts or voiceprints to issues or pull requests. If a problem needs audio, use audio that you can share in public, for example the FLEURS clip in `fixtures/audio/`.
