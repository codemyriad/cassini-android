# Security policy

## Report a vulnerability

Report a vulnerability in private, with a GitHub security advisory:

1. Open <https://github.com/codemyriad/cassini-android/security/advisories/new>.
2. Write what the problem is, which version has it and the steps that show it.
3. Send the report.

Do not open a public issue or pull request for a vulnerability. Do not attach private recordings, transcripts or voiceprints. If the report needs audio, use audio that you made for the report.

If the link does not open a form, open a public issue that asks for a private contact. Do not put details of the problem in that issue.

We reply to each report. When a fix is in a release, we publish the advisory. If you agree, the advisory gives your name.

## Supported versions

Cassini for Android is beta software. Only the latest release gets security fixes. We do not put fixes into older releases.

| Version | Security fixes |
|---|---|
| The latest release on the [Releases page](https://github.com/codemyriad/cassini-android/releases/latest) | Yes |
| Older releases | No. Install the latest release. |
| The `main` branch and unreleased versions | Fixes go into the next release. |

## Release signing

We publish the APK on the [GitHub Releases page](https://github.com/codemyriad/cassini-android/releases). Testers also get Cassini from the internal testing track of Google Play. Each GitHub release has these files:

- `cassini-android-VERSION.apk`: the APK.
- `cassini-android-VERSION.apk.sha256`: the SHA-256 of the APK.

One release key signs the APK on GitHub and the app on Google Play (Play App Signing holds the same key). Thus an update from either source installs over the other without an uninstall, and the downloaded models stay. The SHA-256 digest of the signing certificate is in [`scripts/release-signing-cert.sha256`](scripts/release-signing-cert.sha256).

If the APK or the Play bundle has a different certificate, or the APK is debuggable, the release job of CI stops.

To make sure that you have a genuine APK, do these steps:

1. Run `sha256sum -c cassini-android-VERSION.apk.sha256` in the directory of the two files.
2. Run `apksigner verify --print-certs cassini-android-VERSION.apk` (`apksigner` is in the Android SDK build tools).
3. Compare the line `Signer #1 certificate SHA-256 digest` with the digest in `scripts/release-signing-cert.sha256`.

The repository does not contain the keystore. The Gradle files contain no passwords. The build reads the passwords from the environment variables `CASSINI_RELEASE_STORE_PASSWORD`, `CASSINI_RELEASE_KEY_PASSWORD` and `CASSINI_RELEASE_KEY_ALIAS`. CI gets the keystore and the passwords from repository secrets. [docs/release.md](docs/release.md) gives the details.

Versions 0.0.1-beta to 0.1.0 have a different key, the beta key (certificate SHA-256 `06b2a95b3a6e6e7cdef618999d37631a18216307def007102980db71cf2387ec`). Android does not install a version with the release key over them. Version 0.2 does not show the notes of 0.1.x. Before you uninstall 0.1.x, transcribe each note that has no transcript. Then save each note as a Cassini document (`.opus`), and open these files in the new version. Version 0.1.x cannot save a recording without a transcript as a Cassini document, and there is no other way to move such a recording. The uninstall erases the notes, the voiceprints and the downloaded models of 0.1.x.

## Model downloads and pinned hashes

The APK does not contain the speech models. The app downloads them only after the user agrees to the download. The app downloads every model from one host, `dist.gocassini.com`, the model host of Cassini: Parakeet Ultra INT8 (speech recognition), Silero VAD (speech detection), Nemotron-3-Diarization INT8 (speaker labels) and CAM++ (voiceprints). The desktop app of Cassini downloads its models from the same host, with the same rules.

[`ModelCatalog.kt`](core/src/main/kotlin/org/cassini/core/models/ModelCatalog.kt) pins each model by its identifier on the host and the SHA-256 of its source. It also pins the size and the SHA-256 of each model file, compressed and decompressed. Before a download, the app reads the manifest of the host (`https://dist.gocassini.com/manifest.json`) only to find the files. If the manifest does not list the pinned model, or if one of its files does not have the pinned sizes and hashes, the app does not download the model. The app never installs a newer version of a model that the manifest offers.

Each model file is a seekable Zstandard file. The app asks for the file without a transfer encoding (`Accept-Encoding: identity`). It continues an interrupted download with an HTTP range request, only if the file on the host is still the same (`If-Range` with the SHA-256 of the file). The app compares the size and the SHA-256 of the compressed file before decompression, and the size and the SHA-256 of the model file after decompression. If they are not equal, the app deletes the file and does not install it. The app installs a model in `models/<identifier>/<source SHA-256>/`, and writes the marker of the model only after every file is correct.

The host can send the download to a different host (a redirect). The app follows a redirect only to a host in the list `ModelCatalog.DOWNLOAD_HOSTS`, which has only `dist.gocassini.com`. [PRIVACY.md](PRIVACY.md) gives this list.

If a host gives a file with a different hash, the app stops the download. Report this to us, because it can show that a host is not safe.

## Native runtime

The app contains a sherpa-onnx AAR that we build from a pinned source and [`scripts/streaming-diarization.patch`](scripts/streaming-diarization.patch). [`scripts/native-dependencies.json`](scripts/native-dependencies.json) pins the SHA-256 of the source archives, of the patch and of the prebuilt AAR on the GitHub release `native-sherpa-onnx-1.13.7-nemotron.3`. `scripts/setup.sh` and `scripts/verify-native-runtime.sh` compare the AAR with the pin. Each week, and after a change to the native inputs, CI builds the AAR from source and compares its SHA-256 with the pin. [`docs/native-runtime.md`](docs/native-runtime.md) gives more detail.

## Scope

These parts are in scope: the app, its build, the scripts in this repository and the model pins. If you find a vulnerability in an upstream project (sherpa-onnx, ONNX Runtime, libopus, zstd-jni), report it to that project. If the vulnerability also affects Cassini, tell us too.
