# Development notes

The build commands are in the [README](../README.md). Runtime downloads, weights, audio fixtures and build outputs stay out of commits.

## Device checks

Use direct adb instrumentation to retain app data and downloaded models. Gradle’s managed connected-test cleanup can uninstall the app and erase them.

```sh
python3 scripts/fetch-smoke-audio.py  # requires ffmpeg
./scripts/fetch-youtube-audio.sh     # uses uvx yt-dlp + ffmpeg
./scripts/device-smoke.sh int8 youtube
./scripts/device-smoke.sh fp32 youtube
# Optional third argument selects test classes.
./scripts/device-smoke.sh int8 fleurs org.cassini.android.LibraryTest
# Three-minute viewer/seek check.
./scripts/fetch-youtube-audio.sh 180
./scripts/device-smoke.sh int8 youtube-long
```

Unlock the phone first. The script wakes it and dismisses an unsecured keyguard, but fails early if authentication is still required. Set `ANDROID_SERIAL` when several devices are connected. To verify CI’s actual APK, set `CASSINI_APK=/absolute/path/to/downloaded.apk`; the script detects debug/release and builds the matching instrumentation APK. To use both binaries from CI, also set `CASSINI_TEST_APK=/absolute/path/to/test.apk`. The `cassini-android-device-tests` artifact contains that test APK. Kotlin’s internal method names differ across variants, so a debug test APK cannot reliably exercise a release app.

The supplied sample is [il Pericolo Invisibile](https://www.youtube.com/watch?v=UmZwQf5TV3c), 00:28–00:58 (30 seconds), or 00:28–03:28 (three minutes). Local FLEURS fixtures record the pinned revision, reference text and CC-BY-4.0 attribution. These audio files are not distributed here.

Device tests check JNI inference, timestamps, codec decoding, complete-document integrity, microphone pause/resume, catalogue recovery and interface-language restoration. They do not establish ASR accuracy. Test rules restore the previous catalogue/session and remove only their own generated documents.

```sh
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.metrics.json
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.words.json > transcript.words.json
```

## Portable files

JVM tests run all 26 published vectors when `../cassini-format` is checked out. CI fetches a pinned revision and explicitly requires the fixtures, so these checks cannot silently skip. Synthetic integrity and round-trip tests always run. Use `-PcassiniConformanceDir=/path/to/spec/conformance -PrequireCassiniConformance=true` to require an explicit fixture checkout locally.

```sh
python3 ../cassini-format/tools/cassini-extract.py DOCUMENT.opus --check
python3 ../cassini-format/tools/cassini-opus-digest.py DOCUMENT.opus
# Node 24+, with ../gocassini checked out: use the web viewer’s reader.
node scripts/check-portable-document.mjs DOCUMENT.opus
```

A complete Cassini `.opus` contains the Opus audio stream plus timed words, speakers, processing provenance and integrity metadata in OpusTags. The diagnostic `*.words.json` files are only word bodies. Save Cassini copies the entire document unchanged, including unsupported variants, extensions and attachments. Retranscription adds a variant and preserves previous ones.

Android uses stock sherpa-onnx 1.13.7, greedy CPU decoding and two threads. It does not yet use the desktop runtime’s modified Parakeet frontend/decoder; see [feasibility](feasibility.md).

## Local storage

Owned recordings/documents live in private `files/documents/`. Atomic `session.json` stores the current document reference, preferences, selected variant and playback position. Atomic `library.json` stores stable note IDs, dates, per-note state and a disposable text cache for search. The portable document is authoritative when opened.

`library-migrated` marks recovery of older retained documents, grouped by meeting ID. Previous owned revisions remain on disk. Legacy `latest.words.json` and `latest.processing.json` are diagnostic caches.

## Beta releases

Update `versionName`, increase `versionCode`, and refresh [release notes](release-notes.md). A matching `vVERSION` tag triggers the same build/test/lint checks, then publishes the APK, checksum, notices and native source archive. Packaging checks both supported 64-bit ABIs and each native library’s 16 KB ELF/ZIP alignment. These checks catch build regressions; runtime behavior still needs testing on actual devices. Tag builds require the repository’s `ANDROID_BETA_KEYSTORE` secret; missing signing material fails the build.

The signing key matches the existing development installation. Keep it stable for these beta updates. The beta release build disables debugging; the key comes from the earlier debug installation to allow upgrades. A production signing identity can be chosen for a future stable release. Never commit it.
