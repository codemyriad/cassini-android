# Release

This file tells how a Cassini for Android release is made. Only the owner of the repository makes a release. A contributor does not push tags, does not edit GitHub releases and does not change the secrets of the repository.

## Version and tag

`app/build.gradle.kts` sets `versionName` and `versionCode`. The release tag is `v` and the `versionName`, for example `v0.2.0`. `scripts/package-apk.py` stops the release job if the tag and the `versionName` are different. A tag with a hyphen, for example `v0.3.0-beta`, makes a pre-release on GitHub.

Each release must have a `versionCode` that is more than the `versionCode` of the previous release. Android does not install an update with a lower `versionCode`.

## Release notes

[`CHANGELOG.md`](../CHANGELOG.md) uses the Keep a Changelog format. The changes for the next version collect in the "Unreleased" section.

Until phase P11 of the rebuild, the release job reads the notes from [`release-notes.md`](release-notes.md). From P11, the release job takes the CHANGELOG section of the tag with `scripts/changelog-section.py`, and `docs/release-notes.md` goes away.

## Make a release

The owner does these steps:

1. Do the pre-release gate in [`development.md`](development.md#pre-release-gate).
2. In `CHANGELOG.md`, rename "Unreleased" to the new version and the date. Add a new empty "Unreleased" section and update the compare links.
3. Until P11, copy the notes of the new version into `docs/release-notes.md`.
4. Set `versionName` and increase `versionCode` in `app/build.gradle.kts`.
5. Commit the change on `main` and push it.
6. Wait until CI passes on that commit.
7. Make an annotated tag on that commit with `git tag -a v0.2.0 -m "Cassini 0.2.0"`.
8. Push the tag with `git push origin v0.2.0`.
9. Wait for the release job. It publishes the GitHub release after the checks, the build and the emulator tests pass.
10. Download the APK and its checksum from the release. Examine them with the steps in [`SECURITY.md`](../SECURITY.md).

The release job attaches these files from `dist/`:

- The signed APK and the R8 mapping.
- A SHA-256 checksum file for each of the two.
- The notices.
- `cassini-native-sources.tar`.

Before that, `scripts/package-apk.py` makes sure that the APK is not debuggable, has no model weights and has the certificate digest in `scripts/beta-signing-cert.sha256`.

CAUTION: Do not move or delete a published tag. Do not replace a file of a published release. People, checksums and F-Droid refer to the tag. A moved tag gives two different builds the same name. If a release has a problem, publish a new version with a higher `versionCode`.

## Signing

The build makes the release signing configuration only when the environment has `CASSINI_BETA_KEYSTORE_FILE`. Gradle then reads the other values from these environment variables:

| Variable | Content |
|---|---|
| `CASSINI_BETA_KEYSTORE_FILE` | The path of the keystore |
| `CASSINI_RELEASE_STORE_PASSWORD` | The password of the keystore |
| `CASSINI_RELEASE_KEY_ALIAS` | The alias of the key |
| `CASSINI_RELEASE_KEY_PASSWORD` | The password of the key |

If the keystore variable is set and one of the other variables is not set, Gradle stops with an error. The Gradle files contain no passwords.

To make a signed release build on your computer, do these steps:

1. Export the four variables.
2. Run `./gradlew :app:assembleRelease`.

CI uses these repository secrets on tags:

- `ANDROID_BETA_KEYSTORE`: the keystore, encoded as base64.
- `CASSINI_RELEASE_STORE_PASSWORD`, `CASSINI_RELEASE_KEY_ALIAS` and `CASSINI_RELEASE_KEY_PASSWORD`.

Until the owner sets the three password secrets, CI uses the values of the old debug keystore (`android`, `androiddebugkey`, `android`). This fallback is temporary. It goes when the owner changes the passwords. If `ANDROID_BETA_KEYSTORE` is not set, the tag build fails.

The beta key is the key of the earlier beta installations, so updates keep the notes and the models. `scripts/beta-signing-cert.sha256` holds the SHA-256 digest of its certificate. The owner can choose a new signing identity for a stable release. Never commit a keystore or a password.

## releaseTest

`releaseTest` is a build type for the device tests of a release. It is the release build without R8, with the same signing, and it is not debuggable. R8 removes the members that only the tests call, so the minified `release` APK cannot be instrumented.

On a tag, CI does these steps:

1. It builds `release`, `releaseTest` and the test APK of `releaseTest` with `-PdeviceTestBuildType=releaseTest`.
2. It runs the device tests against `releaseTest` on the emulators.
3. It installs the minified `release` APK over `releaseTest` and makes sure that the app starts.

To do step 3 by hand, run `scripts/launch-check.sh APK`. To run the device tests against a `releaseTest` APK from CI, read [`development.md`](development.md#device-smokesh).

## F-Droid (draft)

Cassini has no F-Droid listing at this time. The fastlane texts are in [`metadata/en-US/`](../metadata/en-US/). The app has no proprietary SDKs, no Google Play services, no analytics and no advertising. The open problems that follow stop an F-Droid build at this time.

### Open problems

- The sherpa-onnx Android build scripts download a prebuilt ONNX Runtime 1.27.1 library from `csukuangfj/onnxruntime-libs`. F-Droid builds native code from source. A recipe must first build ONNX Runtime from source and give it to the sherpa-onnx build. Nobody has done this yet.
- The `zstd-jni` AAR (1.5.7-22) contains prebuilt `.so` files. F-Droid must build them from source, or its scanner refuses the build.
- A build of the AAR from source is not byte-for-byte equal to the pinned release build ([`native-runtime.md`](native-runtime.md)). Thus the F-Droid build is not reproducible against the GitHub APK at this time.
- F-Droid signs with its own key if the build is not reproducible. An F-Droid APK then cannot update a GitHub APK, and a GitHub APK cannot update an F-Droid APK.
- After consent, the app downloads the models from huggingface.co, github.com and dist.gocassini.com. The licenses are CC-BY-4.0 (Parakeet), OpenMDW-1.1 (Nemotron-3-Diarization) and Apache-2.0 (CAM++). The F-Droid reviewers decide if the listing needs an anti-feature label for these downloads.

### Draft recipe

Nobody tested this recipe. It shows the build from source with `scripts/setup.sh --from-source`. It does not solve the open problems.

```yaml
# fdroiddata: metadata/org.cassini.android.yml (draft, not tested)
Categories:
  - Multimedia
License: GPL-3.0-only  # TODO(owner): GPL-3.0-only or GPL-3.0-or-later
SourceCode: https://github.com/codemyriad/cassini-android
IssueTracker: https://github.com/codemyriad/cassini-android/issues
Changelog: https://github.com/codemyriad/cassini-android/blob/main/CHANGELOG.md

RepoType: git
Repo: https://github.com/codemyriad/cassini-android.git

Builds:
  - versionName: 0.2.0
    versionCode: 11  # TODO(owner): the versionCode of the release
    commit: v0.2.0
    subdir: app
    submodules: true
    ndk: r28c
    prebuild: ../scripts/setup.sh --from-source
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
```
