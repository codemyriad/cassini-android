This beta fixes two playback problems and adds checks on older Android versions.

* Leaving a note before its first playback no longer makes its audio unplayable.
* Opening a note keeps the keyboard closed. On older Android versions, the search field could take focus and pull the page away from the word being played.
* CI now tests the built APK on Android 8, 10 and 14. All three must pass before a release is published.

Install **cassini-android-0.0.3-beta.apk** below over an earlier beta to keep your notes and downloaded models. Installation needs Android 8+ and 64-bit Android (ARM64 phones or x86_64 emulators). Creating Cassini files needs Android 10+ and a working platform Opus encoder; some emulator images lack one. See the [README](https://github.com/codemyriad/cassini-android/blob/v0.0.3-beta/README.md) for memory and storage recommendations.

Validation: 23 JVM tests, all 26 published Cassini format vectors, Android lint, and 16 playback/settings/Opus checks across the three emulators. These checks do not run model inference; recording and transcription results from earlier device runs are linked in the README. This release has not been retested on a physical phone. Native dependency sources and license notices are attached; model weights are downloaded separately.
