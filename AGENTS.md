# Working in this repository

- Commit and push completed, reviewable milestones to `origin` when appropriate, as requested by the user. The remote is `git@github.com:codemyriad/cassini-android`.
- Use `./scripts/setup.sh` to fetch the pinned Android runtime. Build with `./gradlew :app:assembleDebug`; verify relevant changes with `:app:testDebugUnitTest` and `:app:lintDebug`.
- Device checks use `scripts/device-smoke.sh`. Its direct adb instrumentation retains app data; Gradle's managed connected-test cleanup can uninstall the app and erase downloaded models.
- Keep model weights, AAR downloads, recordings, fetched test audio, build outputs and device-specific SDK paths out of commits. Their local paths are ignored; fetch scripts reproduce test inputs.
- Public format references live in `../cassini-format`; the desktop transcription pipeline and web viewer live in `../gocassini`. Document any differences from the desktop runtime and distinguish a word-body JSON export from a complete portable Opus artifact.
