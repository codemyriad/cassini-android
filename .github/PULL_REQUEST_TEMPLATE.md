## Summary

<!-- What does this change do, and why? Give the issue number if there is one (for example "Closes #12"). -->

## Checks

<!-- Put an x in each box that applies. If a check does not apply, write "not applicable" next to it. -->

- [ ] `./gradlew detekt ciTest` passes. On macOS or Windows: `./gradlew detekt` and the tests without the pixel comparison pass (see `CONTRIBUTING.md`). CI does the pixel comparison.
- [ ] `./gradlew :app:lintDebug :data:lintDebug :ui:lintDebug` passes.
- [ ] The CI checks that are not in Gradle pass (see "Run the tests" in `CONTRIBUTING.md`).
- [ ] Screenshot goldens: I recorded them on Linux x86_64 and examined each changed PNG, or no golden changed.
- [ ] Device tests: if the change touches recording, playback, services, models or native code, I ran `scripts/device-smoke.sh` on this device and Android version: <!-- device, API level -->
- [ ] Verbatim engine code: `scripts/check-verbatim.sh` passes, and I recorded each reviewed change in `scripts/check-verbatim.baseline`.
- [ ] Documentation: I updated the documents that the change affects (`README.md`, `docs/`, `CHANGELOG.md`, `PRIVACY.md`), and `scripts/check-links.sh` passes.
- [ ] Each commit has a `Signed-off-by` line (`git commit -s`, see `CONTRIBUTING.md`).
- [ ] The change contains no model weights, private recordings, transcripts or voiceprints.

## Device safety

CAUTION: Do not run the Gradle `connected*` or managed-device tasks, `adb uninstall org.cassini.android` or `adb shell pm clear org.cassini.android` on a device with data that you want to keep. They erase the app data and the downloaded models.
