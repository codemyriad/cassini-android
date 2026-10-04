You can now opt in to transcription while recording, and see draft words sooner during batch transcription.

* Turn on **Transcribe while recording** in Settings after downloading the selected model. It is off by default. Words appear after each short audio chunk is decoded; the phone may still be catching up when you tap Done.
* Done saves the recording first, then finishes transcription in the same library note. If transcription fails or you leave the recording screen, the audio stays available for another attempt.
* Batch transcription shows draft words after each decoded chunk. The draft is marked while processing and may change as overlaps are joined.

Live transcription uses more battery, and its shorter audio context can reduce accuracy. Use **Transcribe again** for the usual batch pass. Recording and transcription run in the foreground; keep the app open.

Install **cassini-android-0.0.5-beta.apk** below over an earlier beta to keep your notes and downloaded models. Installation needs Android 8+ and 64-bit Android (ARM64 phones or x86_64 emulators). Creating Cassini files needs Android 10+ and a working platform Opus encoder. See the [README](https://github.com/codemyriad/cassini-android/blob/v0.0.5-beta/README.md) for memory and storage recommendations.

Validation: 127 JVM tests, all 26 published Cassini format vectors, Android lint, and 22 device checks across Android 8, 10 and 14 emulators. CI's emulator checks do not run model inference. Live transcription and saving were also checked with the real INT8 decoder on an Android 14 emulator. Native dependency sources and license notices are attached; model weights are downloaded separately.
