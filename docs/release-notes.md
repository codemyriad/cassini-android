Record a voice note, then find it again in a searchable library. Transcription runs on the phone with Parakeet v3; words link back to the audio. Save a complete Cassini `.opus` file to keep both together.

* Install **cassini-android-0.0.1-beta.apk** below. Android 10+ is recommended; ARM64 phones and x86_64 emulators are packaged.
* Download a transcription model in Settings once: INT8 is about 640 MiB, FP32 about 2.37 GiB. Audio processing is offline afterward.
* This beta handles recordings up to 2:59 and imports up to 3 minutes. Keep the app open while recording and transcribing.
* GitHub Actions builds the release APK with a stable beta signing key. Install future beta updates over it to keep your notes and downloaded models.

Validation: 20 JVM tests, Android lint, all 26 published Cassini format vectors, and 13 recording/library/interface/portable-document checks on a Pixel 8. The native dependency sources and license notices are attached; model weights are downloaded separately.
