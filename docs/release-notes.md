You can now read the first words while the rest of the recording is still being transcribed.

* Transcription shows progress, pace and an estimate of the time left. Words appear as each stretch of speech is decoded; they can still change when overlapping chunks are joined.
* Silero detects speech spans using the desktop pipeline’s settings. Long spans are cut at quiet points, with recorded context on both sides of each cut.
* Words from overlapping windows are merged, and padding and quiet-audio filtering follow the desktop word gate. A boundary fix keeps the copy with duration when another copy was clipped to zero length.

The small Silero model (about 630 KB) downloads separately. Existing Parakeet downloads are reused; if the detector is unavailable, the app cuts at quiet points instead. Android still uses stock sherpa-onnx on CPU, rather than the desktop’s modified runtime.

Install **cassini-android-0.0.4-beta.apk** below over an earlier beta to keep your notes and downloaded models. Installation needs Android 8+ and 64-bit Android (ARM64 phones or x86_64 emulators). Creating Cassini files needs Android 10+ and a working platform Opus encoder. See the [README](https://github.com/codemyriad/cassini-android/blob/v0.0.4-beta/README.md) for memory and storage recommendations.

Validation: 101 JVM tests, all 26 published Cassini format vectors, Android lint, and 16 playback/settings/Opus checks on Android 8, 10 and 14 emulators. CI’s emulator checks do not run model inference. Earlier speech-cutting and progress checks on a Pixel 8 and an Android 14 emulator are documented in the [device results](https://github.com/codemyriad/cassini-android/blob/v0.0.4-beta/docs/pixel8-results.md). Native dependency sources and license notices are attached; model weights are downloaded separately.
