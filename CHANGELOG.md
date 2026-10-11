# Changelog

This file records the changes in each release of Cassini for Android. The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/). The version numbers follow [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

We made the entries for 0.0.1-beta to 0.1.0 again from the release notes of these versions and from the git history.

## [Unreleased]

Version 0.2.0-beta is a new build of the app. It keeps the speech engine and the portable Cassini document format of 0.1.0.

### Changed

- 0.2 is a new app with a new storage layout. It does not read the app data of 0.1.x. Thus the notes, the unfinished transcriptions and the saved voices of 0.1.x do not show in 0.2. The Cassini documents (`.opus`) that you saved from 0.1.x open in 0.2 as before.
- If 0.2 installs over 0.1.x, it does not delete the files of 0.1.x. [PRIVACY.md](PRIVACY.md) gives the list of these files and tells how to delete them.
- If 0.2 installs over 0.1.x, Android can reset the app language to the language of the phone. If this occurs, select the app language again in Settings. This reset comes from Android, not from 0.2. The APK of 0.2 has its list of languages (`locales_config.xml`), and 0.2 does not change the app language when it installs over 0.2.
- A new transcript does not give Italian as its language. Parakeet does not identify the language, so the language of a new transcript is empty.
- A new user interface, made with Jetpack Compose, replaces all screens. It uses the Instrument Sans and IBM Plex Mono fonts and has a light theme and a dark theme.
- Transcription runs in a foreground service, so it continues when you leave the app. If Android or you stop a transcription, it continues later from the last saved point.
- The "People" screen has the new name "Saved voices". It shows the number of notes and the minutes of confirmed speech for each saved voice.
- The model download is a full screen. It shows the size of each model and the free space that the installation needs.
- An interrupted model download continues from where it stopped.
- The audio of a non-Opus source (an imported MP3, M4A or WAV file, or an AAC recording) goes into the Cassini document as Opus at 24 kb/s. In 0.1.0, some imports used a 48 kb/s platform encoder. After the transcription, Cassini deletes its copy of the original file. [PRIVACY.md](PRIVACY.md) gives the details.
- The app targets Android 16 (targetSdk 36) and compiles against compileSdk 37. The minimum is still Android 8 (minSdk 26).
- The time to open a note does not increase with the length of the audio. The Note screen shows the first words first. Then it loads the other words and the player.
- If you turn the phone, or if Android makes the screen again, the open sheets, the dialogs and the player stay.
- At the Android font size 200 %, the Library, Note, Name a speaker and Settings screens show all of their text.
- When the app language is Italian, the screens show all of their text.
- "Transcribe again" is not available for a document with the state "Not verified" or "Newer format". These documents get a message and no "Try again".
- The message for a file that is too large gives the limit of 2 GB.
- "Document info" shows the name of the speech model.

### Added

- A transcription queue. You can start transcriptions for many notes, and Cassini does them one after the other.
- A trash. A deleted note stays in the trash for 30 days, and you can restore it.
- Export of a transcript as text (`.txt`) or as subtitles (`.srt`, `.vtt`).
- Rename of a note.
- Search with highlighted results, also for words without their accents.
- "Stop" in the transcription notification pauses the transcription and holds the queue. "Continue" in the list of notes starts the queue again.
- If Cassini cannot open its list of notes, it shows an error and not an empty list.
- [PRIVACY.md](PRIVACY.md), [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

### Security

- Cassini follows a download redirect only to a host in its allow-list. [PRIVACY.md](PRIVACY.md) gives the list.
- Cassini downloads no model before you agree. The transcription service also makes sure of this.

## [0.1.0] - 2026-10-09

### Added

- One "Transcribe" action recognizes the words and labels each speaker. The first time, the app asks one time to download the models (about 730 MiB: Parakeet v3, the speaker model and the voice model).
- Speaker names. Tap a speaker label, or use "⋯ → Speakers…", to give it a name. Cassini writes the name into the Cassini document. The words, the times and the audio do not change.
- Saved voices. When you name a speaker, Cassini can keep a voiceprint on the phone. Later transcriptions apply the name automatically when the match is strong, and suggest it when the match is weaker. "Not this person" removes a wrong match. "Settings → People" renames or forgets saved voices.
- "Resume processing" continues an interrupted transcription from where it stopped.
- Recording in a foreground service, with streaming Opus and no 2:59 limit.
- Recovery of recordings that stopped before their end, at the next start of the app.
- Imports and recordings of up to 2 hours.

### Changed

- NVIDIA Nemotron-3-Diarization replaces the earlier speaker models.
- Transcribe again, the transcript selection, Save Cassini, Info and Settings are in the "⋯" menu.
- Cassini writes Cassini documents as a stream, and does not keep a full file in memory.

### Removed

- The FP32 speech model. Only the INT8 model remains.
- Transcription during recording.

## [0.0.6-beta] - 2026-10-04

### Added

- Optional anonymous speaker labels for a completed transcript, also for older notes. "Identify speakers" uses automatic detection or a known number of speakers (1–8).
- The speaker models download one time, after you agree (about 40 MiB).
- The result is a new transcript variant. The original transcript, the audio and the imported speaker names stay available under "Choose transcript".

## [0.0.5-beta] - 2026-10-04

### Added

- The "Transcribe while recording" setting (off by default). It shows the words while you record.
- Draft words during a batch transcription, after each decoded chunk.

### Changed

- "Done" saves the recording first and then completes the transcription in the same note.

## [0.0.4-beta] - 2026-10-04

### Added

- Progress, speed and time left during a transcription. Words show when Cassini decodes each part of the speech.
- Silero VAD finds the speech parts, with the settings of the desktop pipeline. The small Silero model (about 630 KB) downloads separately.

### Changed

- Cassini merges the words of windows that overlap. The padding and the filter for quiet audio are the same as in the desktop pipeline.

## [0.0.3-beta] - 2026-10-04

### Fixed

- A note that you left before its first playback can play its audio again.
- When you open a note, the keyboard stays closed. On older Android versions, the search field took the focus.

### Added

- CI tests the built APK on Android 8, 10 and 14 before each release.

## [0.0.2-beta] - 2026-10-02

### Fixed

- Microphone recordings lost one sample every 64 ms. The decoder gives the audio without gaps. Documents from 0.0.1-beta keep the audio that they have.
- A file that you opened from a different app made a new note each time that Android made the screen again. It opens the same note.
- Long transcripts play without stops. Only the highlight moves.
- "Cancel" on "Import audio" goes back to the notes.

## [0.0.1-beta] - 2026-10-01

### Added

- The first beta. You can record a voice note and find it again in a searchable library.
- Transcription on the phone with Parakeet v3. The words link to the audio.
- Save a complete Cassini `.opus` document with the audio and the transcript.
- A one-time model download in Settings: INT8 (about 640 MiB) or FP32 (about 2.37 GiB).
- Recordings of up to 2:59 and imports of up to 3 minutes.

[Unreleased]: https://github.com/codemyriad/cassini-android/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/codemyriad/cassini-android/compare/v0.0.6-beta...v0.1.0
[0.0.6-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.5-beta...v0.0.6-beta
[0.0.5-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.4-beta...v0.0.5-beta
[0.0.4-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.3-beta...v0.0.4-beta
[0.0.3-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.2-beta...v0.0.3-beta
[0.0.2-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.1-beta...v0.0.2-beta
[0.0.1-beta]: https://github.com/codemyriad/cassini-android/releases/tag/v0.0.1-beta
