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

We publish the APK only on the [GitHub Releases page](https://github.com/codemyriad/cassini-android/releases). Each release has these files:

- `cassini-android-VERSION.apk`: the APK.
- `cassini-android-VERSION.apk.sha256`: the SHA-256 of the APK.

The release APKs have one stable signing key. Thus an update installs over the earlier version and keeps the notes and the models. The SHA-256 digest of the signing certificate is in [`scripts/beta-signing-cert.sha256`](scripts/beta-signing-cert.sha256):

```text
06b2a95b3a6e6e7cdef618999d37631a18216307def007102980db71cf2387ec
```

If the APK has a different certificate or is debuggable, the release job of CI stops.

To make sure that you have a genuine APK, do these steps:

1. Run `sha256sum -c cassini-android-VERSION.apk.sha256` in the directory of the two files.
2. Run `apksigner verify --print-certs cassini-android-VERSION.apk` (`apksigner` is in the Android SDK build tools).
3. Compare the line `Signer #1 certificate SHA-256 digest` with the digest above.

The repository does not contain the keystore or its passwords. The build reads the passwords from the environment variables `CASSINI_RELEASE_STORE_PASSWORD`, `CASSINI_RELEASE_KEY_PASSWORD` and `CASSINI_RELEASE_KEY_ALIAS`. CI gets the keystore from a repository secret. The key is a beta key. A stable release can have a different key.

## Model downloads and pinned hashes

The APK does not contain the speech models. The app downloads them only after the user agrees to the download. The app downloads the models from these hosts:

| Model | Host |
|---|---|
| Parakeet TDT 0.6B v3 INT8 (speech recognition) | `huggingface.co`, at a pinned revision |
| Silero VAD (speech detection) and CAM++ (voiceprints) | `github.com`, from the `k2-fsa/sherpa-onnx` releases |
| Nemotron-3-Diarization INT8 (speaker labels) | `dist.gocassini.com` |

These hosts can send the download to a different download host (a redirect). The app follows a redirect only to a host in the list `ModelCatalog.DOWNLOAD_HOSTS`. `PRIVACY.md` gives this list.

<!-- TODO(P10): the redirect allow-list (ModelCatalog.DOWNLOAD_HOSTS) comes from P3 on the rebuild branch. When the docs branch merges, make sure that the list exists and that PRIVACY.md copies it. -->

[`ModelCatalog.kt`](core/src/main/kotlin/org/cassini/core/models/ModelCatalog.kt) pins the URL, the size and the SHA-256 of each model file. The app compares the size and the SHA-256 of each downloaded file with the pin before it installs the file. If they are not equal, the app deletes the partial file and does not install it. The Nemotron model is a Zstandard file. The app compares the hash of the compressed file before decompression, and the hash of the model after decompression.

If a host gives a file with a different hash, the app stops the download. Report this to us, because it can show that a host is not safe.

## Native runtime

The app contains a sherpa-onnx AAR that we build from a pinned source and [`scripts/streaming-diarization.patch`](scripts/streaming-diarization.patch). [`scripts/native-dependencies.json`](scripts/native-dependencies.json) pins the SHA-256 of the source archives, of the patch and of the prebuilt AAR on the GitHub release `native-sherpa-onnx-1.13.7-nemotron.2`. `scripts/setup.sh` and `scripts/verify-native-runtime.sh` compare the AAR with the pin. Each week, CI builds the AAR from source and compares its SHA-256 with the pin. [`docs/native-runtime.md`](docs/native-runtime.md) gives more detail.

## Scope

These parts are in scope: the app, its build, the scripts in this repository and the model pins. If you find a vulnerability in an upstream project (sherpa-onnx, ONNX Runtime, libopus, zstd-jni), report it to that project. If Cassini is affected, tell us too.
