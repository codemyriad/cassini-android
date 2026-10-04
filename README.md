# Cassini for Android

Record a thought, find the words later, and keep the audio with the transcript. I’m building Cassini around that simple flow, with transcription running on your phone.

**[Download 0.0.2-beta](https://github.com/codemyriad/cassini-android/releases/download/v0.0.2-beta/cassini-android-0.0.2-beta.apk)** · [Release notes](https://github.com/codemyriad/cassini-android/releases/tag/v0.0.2-beta)

<p>
  <img src="docs/screenshots/library.png" width="240" alt="Notes library with dates, transcript previews and search">
  <img src="docs/screenshots/recording.png" width="240" alt="Microphone recording with timer, pause and Done controls">
  <img src="docs/screenshots/transcript.png" width="240" alt="Timed transcript with word seeking and fixed playback controls">
</p>

## Requirements

* **Android:** 8.0+ to install; **10+ for the full recording/transcription flow**. Android 8/9 can read existing documents but lack the [platform Opus encoder](https://developer.android.com/media/platform/supported-formats) needed to package new recordings.
* **Processor:** a 64-bit ARM phone (`arm64-v8a`) running 64-bit Android. The APK also includes `x86_64` for emulators. It does not support 32-bit ARM or x86 systems. Transcription runs on CPU; no GPU/NPU is required.
* **RAM (recommended):** **4 GB+ for INT8**, **8 GB+ for FP32**. These are starting recommendations, not tested minimums. Short-clip process memory peaked at about 1.02 GiB / 2.60 GiB on the Pixel; longer clips and other apps need additional memory.
* **Free storage:** allow **1 GiB for INT8** or **3 GiB for FP32**, plus space for recordings. Model downloads are about 640 MiB / 2.37 GiB. Reading existing documents needs no model.

I’ve tested recording and transcription on a **Pixel 8 (8 GB RAM, Android 17)**; see [device results](docs/pixel8-results.md). Other phones and older Android versions haven’t been verified end to end. Installation alone doesn’t guarantee usable transcription speed or enough memory.

## Try it

* Install the APK, then download a model in **Settings** once. Automatic chooses according to available resources. After download, audio processing works offline.
* Tap **Record**, allow microphone access, then **Done**. You can pause and resume. Transcription starts when the model is ready. Or import WAV, MP3, M4A/AAC or Ogg Opus and tap **Transcribe**.
* Browse **Notes** or search titles and past transcriptions. Tap a word to hear that part of the recording. Each note remembers its playback position.
* Use **Save Cassini** to keep a complete `.opus` document containing audio, timed words and metadata. You can open it again without downloading a model. This is a portable recording, not just a word-body JSON export.

The interface supports English and Italian. Parakeet recognizes speech languages automatically; the current transcript metadata labels the language as Italian.

## What’s still limited

This is an early beta: microphone recordings stop at **2:59**, imports for transcription are limited to **3 minutes / 64 MiB**, and recording and transcription need the app in the foreground. Leaving the recording screen stops and saves the audio. Failed transcription retains the recording for retry.

There’s no transcription queue, new speaker diarization or storage cleanup UI yet. The APK is a release build signed with a stable beta key.

## Build

Requires JDK 17 and Android SDK platform/build tools 34. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`.

```sh
./scripts/setup.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

[GitHub Actions](https://github.com/codemyriad/cassini-android/actions/workflows/android.yml) builds and checks each push and pull request, including playback/settings checks on Android 8 and 10 emulators. Version tags publish the APK with a stable beta signing key, so updates preserve notes and downloaded models. Models and the pinned Android runtime are fetched separately.

For device checks and format interoperability, see [development notes](docs/development.md), [Pixel 8 results](docs/pixel8-results.md) and the [Android acceleration investigation](docs/android-acceleration.md). Android currently uses stock sherpa-onnx on CPU; desktop Cassini uses a modified runtime.

Developed with AI coding assistance; recording, transcription and portable-file checks ran on a Pixel 8. [GPLv3](LICENSE). See [dependency licenses and sources](THIRD_PARTY_NOTICES.md).
