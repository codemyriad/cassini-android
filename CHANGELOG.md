# Changelog

This file records the changes in each release of Cassini for Android. The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/). The version numbers follow [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

We made the entries for 0.0.1-beta to 0.1.0 again from the release notes of these versions and from the git history.

## [Unreleased]

### Changed

- The app is called Cassini talk. The launcher, the About card, the Licenses screen and the store title show the new name. Sentences in the app still say "Cassini". The package name stays `org.cassini.android`, so this version updates the installed app and keeps your notes, voices, models and settings.

## [0.3.0] - 2026-10-17

The APK file of this release is `cassini-android-0.3.0.apk`. It operates on Android 8.0 or later, on a 64-bit ARM phone (`arm64-v8a`). The APK also has `x86_64` for emulators. This release has a new signing key, so it does not install over 0.1.x or an earlier beta. Before you uninstall the earlier version, save your notes as Cassini documents (`.opus`). The uninstall erases the notes and the downloaded models, so Cassini downloads the models again (about 730 MB). Testers can also get Cassini from Google Play, with the same key. The release also has the native sources (`cassini-native-sources.tar`) and the notices. The APK has no models. After you agree, the app downloads them separately.

Version 0.3.0 has all the changes of 0.2.0, which was not published.

### Changed

- The screens are smoother. Transcript words are drawn without one link per word, so long notes scroll and play without stutter. Opening and closing a note no longer stalls for about 100 ms. The Library stays ready under an open note. The recording waveform scrolls at the display rate, and the transcription progress bar moves between decoding windows. The app ships a Baseline Profile, so its code is compiled at install.
- The highlighted word changes at the start of the word that you hear. Before, it was 100 to 200 ms late.
- TalkBack reads each turn as one paragraph with "Play from start of turn". The words are no longer links.
- A new release key signs Cassini. Google Play does not accept the beta key of 0.0.1-beta to 0.1.0, and the same key now signs the APK on GitHub and the app on Google Play. Android does not install this version over 0.1.x. Save your notes as Cassini documents (`.opus`), uninstall 0.1.x and then install this version. The uninstall erases the notes and the downloaded models of 0.1.x. [SECURITY.md](SECURITY.md) gives the digest of the new certificate.
- Cassini recognizes speech with Parakeet Ultra INT8. Moondream made this model with more training of NVIDIA Parakeet TDT 0.6B v3. It makes fewer errors in the 25 languages of v3. Earlier versions used Parakeet TDT 0.6B v3.
- Parakeet Ultra installs in `parakeet-ultra/`. This version does not read or delete the `parakeet-v3/` directory of earlier versions.
- The documents that you made before keep the name of their model. Document info shows "Parakeet TDT 0.6B v3, INT8" for them and "Parakeet Ultra, INT8" for new versions.
- A transcription that you stopped with an earlier version does not continue with Parakeet Ultra. Cassini shows "Saved progress no longer matches", and your recording stays. Start the transcription again.
- The sherpa-onnx runtime has the feature extraction of NVIDIA NeMo for Parakeet, from upstream pull request [k2-fsa/sherpa-onnx#4024](https://github.com/k2-fsa/sherpa-onnx/pull/4024).
- zstd-jni 1.5.7-23 and AndroidX Annotation 1.11.0.

### Fixed

- Quiet audio gives a transcript. Before, the transcript of a very quiet recording could be empty.

## [0.2.0] - 2026-10-12

Not published: the tag build failed in lint, so there is no GitHub release of 0.2.0. Version 0.3.0 has these changes.

Version 0.2.0 is a new build of the app. It keeps the speech engine and the portable Cassini document format of 0.1.0.

### Added

- A transcription queue. You can start transcriptions for many notes, and Cassini does them one after the other.
- A trash. A deleted note stays in the trash for 30 days, and you can restore it.
- Export of a transcript as text (`.txt`) or as subtitles (`.srt`, `.vtt`), in "Save a copy". The copy of the Cassini document stays as before.
- Rename of a note.
- Search in the titles and the words of all notes, with highlighted results. The search ignores upper and lower case and accents.
- "Stop" in the transcription notification pauses the transcription and holds the queue. "Continue" in the list of notes starts the queue again.
- If Cassini cannot open its list of notes, it shows an error and not an empty list.
- [PRIVACY.md](PRIVACY.md), [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

### Changed

- 0.2 is a new app with a new storage layout. It does not read the app data of 0.1.x. Thus the notes, the unfinished transcriptions and the saved voices of 0.1.x do not show in 0.2. The Cassini documents (`.opus`) that you saved from 0.1.x open in 0.2 as before. The downloaded models of 0.1.x stay, and 0.2 uses them.
- If 0.2 installs over 0.1.x, it does not delete the files of 0.1.x. [PRIVACY.md](PRIVACY.md) gives the list of these files and tells how to delete them.
- If 0.2 installs over 0.1.x, Android can reset the app language to the language of the phone. If this occurs, select the app language again in Settings. This reset comes from Android, not from 0.2.
- A new user interface, made with Jetpack Compose, replaces all screens. It uses the Instrument Sans and IBM Plex Mono fonts and has a light theme and a dark theme.
- One "Name a speaker" sheet replaces the chain of dialogs. The "Versions" sheet replaces "Choose transcript".
- If the voice of a speaker is similar to a saved voice, the transcript asks a question at that speaker, for example "Is this Anna?". You can answer yes or no there.
- A recording has no time limit. It continues until you push Finish. Before, a recording stopped at 2 hours. If the free storage gets to 200 MB, Cassini stops the recording and saves it. On Android 8 and 9, and on phones without an Opus encoder, Cassini ends an M4A recording at 1 GB (about 74 hours) and saves it. A warning shows 15 minutes before.
- The Recording screen shows the space for the recording with no upper limit ("Space for about 31 h more.").
- A transcription accepts up to 36 hours of audio. Before, the limit was 2 hours. If a recording is too long, the message "This recording is too long to transcribe" shows when Cassini finds the problem, not at the end. This message has no "Try again", because a new try fails at the same point. The audio stays, and Save a copy exports it.
- The message for a file that is too large gives the limit of 4 GB. Before, the limit was 2 GB.
- Transcription runs in a foreground service, so it continues when you leave the app. If Android or you stop a transcription, it continues later from the last saved point.
- On Android 15 and later, the transcription service has the Android type for media processing (`mediaProcessing`). On Android 10 to 14, it has the type for data sync.
- The notifications use the app language, also when the app language is different from the language of the phone.
- An unfinished transcription writes much less data to the phone storage. In a test of 10 hours, the writes decreased from 8.5 GB to 1.7 GB. The processor time to save the progress decreased from 37 minutes to less than 2 minutes.
- The "People" screen has the new name "Saved voices". It shows the number of notes and the minutes of confirmed speech for each saved voice.
- The model download is a full screen. It shows the size of each model and the free space that the installation needs. An interrupted model download continues from where it stopped.
- The audio of a non-Opus source (an imported MP3, M4A or WAV file, or an AAC recording) goes into the Cassini document as Opus at 24 kb/s. In 0.1.0, some imports used a 48 kb/s platform encoder. After the transcription, Cassini deletes its copy of the original file. [PRIVACY.md](PRIVACY.md) gives the details.
- The time to open a note does not increase with the length of the audio. The Note screen shows the first words first. Then it loads the other words and the player.
- If you turn the phone, or if Android makes the screen again, the open sheets, the dialogs and the player stay.
- At the Android font size 200 %, and in Italian, the screens show all of their text.
- "Transcribe again" is not available for a document with the state "Not verified" or "Newer format". These documents get a message and no "Try again".
- "Document info" shows the name of the speech model.
- The app targets Android 16 (targetSdk 36) and compiles against compileSdk 37. The minimum is still Android 8 (minSdk 26).

### Fixed

- The word times of an imported MP3 file agree with the audio on each Android version that we tested. Before, Android 8 and 10 kept the silence that the MP3 encoder adds, and Android 14 added 72 ms of silence. Cassini reads the delay and the padding from the LAME tag of the file and removes them.
- A new transcript does not give Italian as its language. Parakeet does not identify the language, so the language of a new transcript is empty.

### Security

- Cassini follows a download redirect only to a host in its allow-list. [PRIVACY.md](PRIVACY.md) gives the list.
- Cassini downloads no model before you agree. The transcription service also makes sure of this.

### Known limitations

- One transcript holds about 11 hours of continuous speech (about 110,000 words). If a recording has more speech, the transcription stops with "This recording is too long to transcribe". The audio stays.
- On Android 15 and later, Android permits about 6 hours of background transcription in each 24 hours. Then Android pauses the transcription, and the note and the notification tell you. Resume continues from where it stopped.
- If a transcription of an MP3 file continues from a saved point, the words after that point are about 37 ms early.
- Cassini is not on F-Droid at this time. Two native libraries (ONNX Runtime and zstd-jni) come as prebuilt files, and F-Droid builds native code from source. [docs/release.md](docs/release.md#f-droid-draft) gives the open problems.

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

[Unreleased]: https://github.com/codemyriad/cassini-android/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/codemyriad/cassini-android/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/codemyriad/cassini-android/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/codemyriad/cassini-android/compare/v0.0.6-beta...v0.1.0
[0.0.6-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.5-beta...v0.0.6-beta
[0.0.5-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.4-beta...v0.0.5-beta
[0.0.4-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.3-beta...v0.0.4-beta
[0.0.3-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.2-beta...v0.0.3-beta
[0.0.2-beta]: https://github.com/codemyriad/cassini-android/compare/v0.0.1-beta...v0.0.2-beta
[0.0.1-beta]: https://github.com/codemyriad/cassini-android/releases/tag/v0.0.1-beta
