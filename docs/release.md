# Release

This file tells how the owner makes a release of Cassini for Android. Only the owner of the repository makes a release. A contributor does not push tags, does not edit GitHub releases and does not change the secrets of the repository.

## Version and tag

`app/build.gradle.kts` sets `versionName` and `versionCode`. The release tag is `v` and the `versionName`, for example `v0.2.0`. `scripts/package-apk.py` stops the release job if the tag and the `versionName` are different. A tag with a hyphen, for example `v0.3.0-beta`, makes a pre-release on GitHub.

Each release must have a `versionCode` that is more than the `versionCode` of the previous release. Android does not install an update with a lower `versionCode`.

## Release notes

[`CHANGELOG.md`](../CHANGELOG.md) uses the Keep a Changelog format. The changes for the next version collect in the "Unreleased" section.

The release job takes the notes from the section of the tag in `CHANGELOG.md`. For the tag `v0.2.0`, this is the section `## [0.2.0]`. `scripts/changelog-section.py` extracts the section. It changes each relative link into a link to the file at the tag, because a relative link does not work on the release page. If the section is not there or is empty, the `checks` job fails. Then the release job does not run, and the release is not published. The `build` and `emulator` jobs do not wait for `checks`, so they still run.

To see the notes before you make the tag, run `python3 scripts/changelog-section.py v0.2.0`. With no argument, the script shows the "Unreleased" section.

## Make a release

The owner does these steps:

1. Do the pre-release gate in [`development.md`](development.md#pre-release-gate).
2. In `CHANGELOG.md`, rename "Unreleased" to the new version and the date. Add a new empty "Unreleased" section and update the compare links.
3. At the start of the section of the new version, add a short paragraph about the installation. Give the name of the APK file (`cassini-android-VERSION.apk`) and the requirements (Android 8.0 or later, 64-bit Android). Tell the users to install the APK over the earlier version, so that the notes and the downloaded models stay. Tell them that the release has the native sources and the notices, and that the models download separately.
4. Make sure that `python3 scripts/changelog-section.py v0.2.0` shows the notes of the new version.
5. Set `versionName` and increase `versionCode` in `app/build.gradle.kts`.
6. Make the Settings screenshot again, because it shows the version number. Use the steps in [`development.md`](development.md#acceptance-walksh) to make `docs/screenshots/light/05-settings.png`, `docs/screenshots/dark/05-settings.png` and `metadata/en-US/images/phoneScreenshots/5.png`.
7. Commit the changes on `main` and push them.
8. Wait until CI passes on that commit.
9. Make an annotated tag on that commit with `git tag -a v0.2.0 -m "Cassini 0.2.0"`.
10. Push the tag with `git push origin v0.2.0`.
11. Wait for the release job. It publishes the GitHub release after the checks, the build and the emulator tests pass.
12. Download the APK and its checksum from the release. Examine them with the steps in [`SECURITY.md`](../SECURITY.md).

The release job attaches these files from `dist/`:

- The signed APK and the R8 mapping.
- A SHA-256 checksum file for each of the two.
- The notices.
- `cassini-native-sources.tar` and its SHA-256 checksum file.

Before that, `scripts/package-apk.py` makes sure that the APK is not debuggable, has no model weights and has the certificate digest in `scripts/beta-signing-cert.sha256`. It also makes sure that no `assets/licenses/*.md` file in the APK contains the text `TODO(`, because the Licenses screen shows these files.

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

The beta key is the key of the earlier beta installations. Thus an update installs over them without an uninstall, and the downloaded models stay. `scripts/beta-signing-cert.sha256` holds the SHA-256 digest of its certificate.

Version 0.2 is a new app. It does not read the app data of 0.1.x. The Cassini documents (`.opus`) that users saved from 0.1.x open in 0.2 as before.

The owner can select a new signing identity for a stable release. Then Android does not install the stable release over a beta installation. Users must uninstall the beta, and the uninstall erases their notes and models. The release notes must tell this to the users.

Never commit a keystore or a password.

## releaseTest

`releaseTest` is a build type for the device tests of a release. It is the release build without R8, with the same signing, and it is not debuggable. R8 removes the members that only the tests call, so the instrumentation cannot operate on the minified `release` APK.

On a tag, CI does these steps:

1. It builds `release`, `releaseTest` and the test APK of `releaseTest` with `-PdeviceTestBuildType=releaseTest`.
2. It runs the device tests against `releaseTest` on the emulators.
3. It installs the minified `release` APK over `releaseTest` and makes sure that the app starts.

To do step 3 by hand, run `scripts/launch-check.sh APK`. To run the device tests against a `releaseTest` APK from CI, read [`development.md`](development.md#device-smokesh).

## F-Droid (draft)

Cassini has no F-Droid listing at this time. The fastlane texts are in [`metadata/en-US/`](../metadata/en-US/). The app has no proprietary SDKs, no Google Play services, no analytics and no advertising. The open problems that follow stop an F-Droid build at this time.

### Open problems

- The sherpa-onnx Android build scripts download a prebuilt ONNX Runtime 1.27.1 library from `csukuangfj/onnxruntime-libs`. F-Droid builds native code from source. A recipe must first build ONNX Runtime from source and give it to the sherpa-onnx build. Nobody did this yet.
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
