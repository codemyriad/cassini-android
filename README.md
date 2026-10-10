# Cassini for Android

Cassini records audio and makes the transcript on your phone. It labels the speakers and can remember the voices of the people that you name. It keeps the audio and the timed words together in one `.opus` file. Any audio player plays this file, and other Cassini software reads the transcript in it. Speech recognition operates without a network connection after a one-time model download.

**[Download the latest release](https://github.com/codemyriad/cassini-android/releases/latest)** · [Changelog](CHANGELOG.md)

## Privacy

Cassini does all speech recognition and speaker analysis on the phone. It does not upload audio, transcripts or voiceprints. It has no accounts, no analytics and no advertising. The app uses the network only to download the models, and only after you agree. A voiceprint stays in the private storage of the app. Cassini never writes a voiceprint into a document or an export. [`PRIVACY.md`](PRIVACY.md) gives the full privacy policy.

## Requirements

- Android 8.0 (API 26) or later, on a 64-bit ARM phone (`arm64-v8a`). The APK also has `x86_64` for emulators.
- About 1 GB of free storage for the models. The download is about 730 MB.
- 4 GB of RAM is a good start. This is not a tested minimum.

## Screenshots

<!-- TODO(P12): add the light and dark device screenshots from docs/screenshots/light/ and docs/screenshots/dark/ in a small table. -->

The screenshots of the 0.2 interface come with the release.

## Build

```sh
git clone --recurse-submodules https://github.com/codemyriad/cassini-android
cd cassini-android
./scripts/setup.sh
./gradlew :app:assembleDebug
```

The build needs JDK 17 and the Android SDK. [`CONTRIBUTING.md`](CONTRIBUTING.md) gives the tools, the tests and the rules for a change.

## Modules

```
:core    Pure Kotlin/JVM. The verbatim engine plus pure app rules (statuses, search,
         trash arithmetic, exports, speaker stats, model plan).
:data    Android library. Room catalogue, DataStore settings, file repositories.
:engine  Android library. Native wrappers (sherpa-onnx, libcassini_opus), MediaCodec
         decoder, microphone capture, TranscriptionJob, ModelInstaller, ProgressTracker.
:ui      Android library. Compose design system and stateless screens.
:app     Android application. AppGraph, ViewModels, navigation, services, notifications.
:bench   Experiments and calibration on a device (com.android.test).
         Never in CI.

app -> ui
app -> data -> core
app -> engine -> core
bench -(instruments)-> app
```

## Documentation

- [`CONTRIBUTING.md`](CONTRIBUTING.md): build, tests, style and pull requests.
- [`docs/development.md`](docs/development.md): worktrees, test tiers, device scripts and the pre-release gate.
- [`docs/architecture.md`](docs/architecture.md): modules, storage, background work and the build.
- [`docs/data-formats.md`](docs/data-formats.md): storage layout, the portable document, exports and the differences from gocassini.
- [`docs/native-runtime.md`](docs/native-runtime.md): the sherpa-onnx AAR and libopus.
- [`docs/release.md`](docs/release.md): tags, signing and the F-Droid draft.
- [`docs/design/`](docs/design/): the features and the visual design.
- [`docs/notes/`](docs/notes/): historical notes of the 0.1.0 prototype.
- [`SECURITY.md`](SECURITY.md), [`PRIVACY.md`](PRIVACY.md), [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) and [`CHANGELOG.md`](CHANGELOG.md).

The [cassini-format](https://github.com/codemyriad/cassini-format) repository gives the specification of the portable format. The desktop pipeline and the web viewer are in [gocassini](https://github.com/codemyriad/gocassini).

## License

Cassini for Android is free software under the [GPLv3](LICENSE). [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) gives the licenses and sources of the dependencies and the models. We developed Cassini with AI coding assistance.
