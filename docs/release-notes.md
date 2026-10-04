You can now add anonymous speaker labels to a completed transcript, including notes you already have. This is an optional pass after transcription; it does not repeat speech recognition or run while recording.

* Tap **Identify speakers**, then choose automatic detection or a known count (1–8). The separate speaker models download once, with your consent (about 40 MiB).
* The result adds a transcript variant. Your original transcript, audio and imported speaker names stay available under **Choose transcript**.
* Cancel returns to the library and keeps the original result. The native computation may take time to finish in the background before another transcription can start.

Labels are estimates. Automatic detection can merge or split voices, and mixed audio does not separate people speaking at the same time. Check labels against the recording. Processing runs on CPU; keep the app open until it finishes.

Install **cassini-android-0.0.6-beta.apk** below over an earlier beta to keep notes and downloaded models. Installation needs Android 8+ and 64-bit Android. Creating Cassini files needs Android 10+ and a working platform Opus encoder. See the [README](https://github.com/codemyriad/cassini-android/blob/v0.0.6-beta/README.md) for memory and storage recommendations.

Validation: 143 JVM tests, all 26 published Cassini format vectors and Android lint. CI runs 31 playback, recording, settings and speaker-consent checks across Android 8, 10 and 14 emulators, without model inference. Native speaker identification and cancellation were checked on an Android 14 emulator with a two-voice fixture, including preservation of words, timing, previous variants and the Opus audio digest. This is a clean test fixture, not a conversational accuracy benchmark. The Pixel was unavailable for this feature's device checks.

Native dependency sources and license notices are attached; model weights download separately.
