Transcription now tells speakers apart, remembers the people you name, and survives interruptions.

* **One action.** **Transcribe** recognizes the words and labels each speaker in a single job. The first time, the app asks once to download the models (about 730 MiB: Parakeet v3, the speaker model and the voice model). Earlier installations download only what is missing.
* **Name speakers.** Tap a speaker label, or use **⋯ → Speakers…**, to give it a name. The name is written into the note's Cassini document; words, timing and audio stay unchanged.
* **Remembered voices.** When you name a speaker, the app can keep a voice fingerprint on this phone. Later transcriptions apply the name automatically when the match is strong (marked, easy to change) and suggest it with one tap when it is weaker. **Not this person** undoes a wrong match. **Settings → People** renames or forgets saved voices. Fingerprints never leave the phone and are never written into shared files.
* **Resume processing.** If transcription is interrupted, the note keeps its progress. **Resume processing** continues from where it stopped instead of starting over.
* **Simpler note screen.** Transcribe again, transcript choice, Save Cassini, Info and Settings are now in the **⋯** menu.

Speaker labels and automatic names are estimates. Overlapping voices are not separated, and short turns can be attributed to the wrong person. Check names against the recording.

Install **cassini-android-0.1.0.apk** below over an earlier beta to keep notes and downloaded models. Installation needs Android 8+ and 64-bit Android. See the [README](https://github.com/codemyriad/cassini-android/blob/v0.1.0/README.md) for memory and storage recommendations.

Validation: 204 JVM tests, the published Cassini format vectors and Android lint. CI runs the emulator checks on Android 8, 10 and 14. Transcription with speakers, naming, voice recognition and resumed processing were exercised on a Pixel during development, including private multi-speaker meeting excerpts. The voice-match thresholds were tuned on a small set of real meetings and may need adjustment.

Native dependency sources and license notices are attached; model weights download separately.
