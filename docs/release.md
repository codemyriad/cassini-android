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

Before that, `scripts/package-apk.py` makes sure that the APK is not debuggable, has no model weights and has the certificate digest in `scripts/release-signing-cert.sha256`. The bundle for Google Play must have the same certificate. It also makes sure that no `assets/licenses/*.md` file in the APK contains the text `TODO(`, because the Licenses screen shows these files.

CAUTION: Do not move or delete a published tag. Do not replace a file of a published release. People, checksums and F-Droid refer to the tag. A moved tag gives two different builds the same name. If a release has a problem, publish a new version with a higher `versionCode`.

## Signing

One release key signs the APK of the GitHub release and the bundle (`.aab`) for Google Play. Play App Signing holds the same key, so Google Play signs its installs with it too. Thus an APK from GitHub installs over an installation from Google Play, and the reverse.

The build makes the release signing configuration only when the environment has `CASSINI_RELEASE_KEYSTORE_FILE`. Gradle then reads the other values from these environment variables:

| Variable | Content |
|---|---|
| `CASSINI_RELEASE_KEYSTORE_FILE` | The path of the keystore |
| `CASSINI_RELEASE_STORE_PASSWORD` | The password of the keystore |
| `CASSINI_RELEASE_KEY_ALIAS` | The alias of the key |
| `CASSINI_RELEASE_KEY_PASSWORD` | The password of the key |

If the keystore variable is set and one of the other variables is not set, Gradle stops with an error. The Gradle files contain no passwords.

To make a signed release build on your computer, do these steps:

1. Export the four variables.
2. Run `./gradlew :app:assembleRelease :app:bundleRelease`.

CI uses these repository secrets on tags. If one of them is not set, the tag build fails.

- `CASSINI_RELEASE_KEYSTORE`: the keystore, encoded as base64.
- `CASSINI_RELEASE_STORE_PASSWORD`, `CASSINI_RELEASE_KEY_ALIAS` and `CASSINI_RELEASE_KEY_PASSWORD`.

`scripts/release-signing-cert.sha256` holds the SHA-256 digest of the certificate of the release key.

### Make the release key

The owner makes the key one time. The key cannot change later without an uninstall on each phone, so keep two copies of the keystore and its passwords, for example in a password manager that the founders share.

1. Make the keystore. Use a long random password:
   `keytool -genkeypair -v -keystore cassini-release.jks -alias cassini -keyalg RSA -keysize 4096 -validity 10000`
2. Write the digest into `scripts/release-signing-cert.sha256`:
   `keytool -list -v -keystore cassini-release.jks -alias cassini | sed -n 's/^\s*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'`
3. Set the four repository secrets. For the keystore, use `base64 -w0 cassini-release.jks`.
4. In Play Console, when you make the app, select Play App Signing with "Use a key from Java keystore". Play Console gives the PEPK tool and its command. The tool encrypts the key for Google.

### The old beta key

Versions 0.0.1-beta to 0.1.0 have the beta key: the Android debug keystore of the owner, with the certificate digest `06b2a95b3a6e6e7cdef618999d37631a18216307def007102980db71cf2387ec`. Google Play does not accept a key with the name "Android Debug". Thus the first release with the release key does not install over these versions. Users must save their notes as Cassini documents (`.opus`), uninstall the old version and then install the new version. The uninstall erases the notes and the downloaded models. The release notes must tell this to the users.

`scripts/device-smoke.sh` still signs a local `releaseTest` build with the debug keystore when `CASSINI_RELEASE_KEYSTORE_FILE` is not set, so that it installs over a debug build.

Never commit a keystore or a password.

## Google Play

The testers get Cassini from the internal testing track of Google Play. Each tag build makes a bundle in the CI artifact `cassini-android-play`, with the R8 mapping and the release notes. Play takes the release notes from `metadata/en-US/changelogs/VERSIONCODE.txt`. The tag build fails if this file is not there or has more than 500 characters.

The `play` job uploads the bundle to the internal track when the repository secret `PLAY_SERVICE_ACCOUNT_JSON` is set. This secret is the JSON key of a Google Cloud service account that has the permission "Release to testing tracks" in Play Console ([Google Play Developer API](https://developers.google.com/android-publisher/getting_started)). Without the secret, the job only shows a notice.

The first bundle of a new app must be uploaded by hand: download `cassini-android-play` from the run of the tag and upload the `.aab` in Play Console (Testing, Internal testing, Create new release). Until the app has a release that is rolled out, Play accepts only draft releases from the API. In this time, set the repository variable `PLAY_RELEASE_STATUS` to `draft`. Then remove it.

Each upload needs a `versionCode` that is more than the `versionCode` of each earlier upload. A tag that failed before the upload does not use a `versionCode`.

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
- The `zstd-jni` AAR (1.5.7-23) contains prebuilt `.so` files. F-Droid must build them from source, or its scanner refuses the build.
- A build of the AAR from source is not byte-for-byte equal to the pinned release build ([`native-runtime.md`](native-runtime.md)). Thus the F-Droid build is not reproducible against the GitHub APK at this time.
- F-Droid signs with its own key if the build is not reproducible. An F-Droid APK then cannot update a GitHub APK, and a GitHub APK cannot update an F-Droid APK.
- After consent, the app downloads the models from huggingface.co, github.com and dist.gocassini.com. The licenses are CC-BY-4.0 (Parakeet Ultra), OpenMDW-1.1 (Nemotron-3-Diarization) and Apache-2.0 (CAM++). The F-Droid reviewers decide if the listing needs an anti-feature label for these downloads.

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
    versionCode: 12
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
