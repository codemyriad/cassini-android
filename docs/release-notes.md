This beta fixes what a code review of 0.0.1-beta turned up. The most important one is in the audio itself.

* **Recordings lost one sample every 64 ms.** Microphone recordings (48 kHz AAC) were decoded with a silent sample every 3072 samples. That glitch went into the transcription and into the Opus audio saved in new documents. Decoding is now gapless. Documents made with 0.0.1-beta keep the audio they were saved with.
* **Opening the same file twice no longer duplicates it.** A file opened from another app was copied again every time the screen was recreated (a theme or language change was enough), each time with a new note. It now returns to the same note, with its playback position and selected transcript.
* **Long transcripts play without stalling.** The whole transcript was rebuilt for every spoken word. Now only the highlight moves.
* **Cancelling “Import audio” goes back to Notes** instead of leaving you inside the last note you opened.

Install **cassini-android-0.0.2-beta.apk** below over 0.0.1-beta: your notes and downloaded models stay. Android 10+ is recommended; ARM64 phones and x86_64 emulators are packaged. The limits are the same as before: recordings up to 2:59, imports up to 3 minutes, and the app has to stay open while recording and transcribing.

Validation: 23 JVM tests, Android lint, all 26 published Cassini format vectors, and 20 recording/library/interface/portable-document checks, including real Parakeet INT8 transcription. The device checks for this release ran on an Android 14 x86_64 emulator, not on a physical phone. The fixes were written by Claude and reviewed by Codex over several rounds. The native dependency sources and license notices are attached; model weights are downloaded separately.
