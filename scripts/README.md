# Scripts

Run every script from the root of the repository. The header comment or docstring of each script gives its arguments.
On a machine that other agents share, run heavy commands through `scripts/heavy-lock.sh`
([`docs/development.md`](../docs/development.md#one-heavy-run-per-machine)). `tools/` holds the larger host programs
(`asr-eval`, `ui-fidelity`, `model-host`) and `tools/fetch-youtube-audio.sh`, which makes local test audio that is
never committed.

## Setup and native runtime

| Script | Purpose |
|---|---|
| `setup.sh` | Prepares the native build inputs: the pinned libopus source, the sherpa-onnx AAR in `native/m2` and the Whistle inputs. Run it after a clone. |
| `fetch-native-aar.sh`, `fetch-opus-source.sh` | The download steps of `setup.sh`, each checked against its pin. |
| `build-native-aar.sh` | Builds the sherpa-onnx AAR from source with `streaming-diarization.patch` ([`docs/native-runtime.md`](../docs/native-runtime.md)). |
| `verify-native-runtime.sh` | Checks `native/m2` against `native-dependencies.json`, the patch and the version catalog. |
| `native-common.sh` | Shared shell helpers of the native scripts (sourced, not run). |
| `build-whistle-runtime.py`, `package-whistle.py`, `whistle_payload.py` | Build, verify and package the private Whistle runtime ([`whistle/README.md`](../whistle/README.md)). |
| `package-native-sources.py` | Collects the pinned native sources that accompany a release. |

## Checks that CI runs outside Gradle

| Script | Purpose |
|---|---|
| `check-links.sh` | Relative links of the Markdown files. |
| `check-verbatim.sh`, `check-verbatim.baseline` | Differences of the verbatim engine code from 0.1.0 ([`CONTRIBUTING.md`](../CONTRIBUTING.md#verbatim-engine-code)). |
| `check-ux-pin.py` | The vendored cassini-ux contracts against their byte pins in `third_party/cassini-ux/`. |
| `check-import-types.py` | Manifest Open with and Share filters against the vendored import-types contract. |
| `check-brand-color-literals.py` | No authored colour values in production sources. |
| `check-nav-mark.py` | The Saturn mark sources and their density exports (`nav-mark.json`, `mid-mark.json`, `launcher-mark.json`). |
| `check-resource-collisions.py` | No resource name in both `:app` and `:ui`. |
| `check-native-paths.py` | The path triggers of `native.yml` list exactly the native build scripts. |
| `check-runner-selection.py` | The `CM_RUNNER` variable moves jobs to self-hosted runners only safely. |

## Generated resources

These scripts write Android resources from the checksum-pinned cassini-ux inputs. CI checks that the committed output matches: `--check` (directly or through `tests/ci/`) for the resources, `check-nav-mark.py` for the mark.

| Script | Purpose |
|---|---|
| `generate-brand-colors.py` | Colour values from the pinned brand tokens. |
| `generate-phase1-strings.py`, `generate-phase2-strings.py`, `generate-d96-strings.py`, `generate-wave5-strings.py` | String resources from the pinned shared catalogues of each wave. |
| `render-mark-chrome.py`, `render-nav-mark.swift` | Rasterize the Saturn mark SVGs. |

## Devices and emulators

| Script | Purpose |
|---|---|
| `device-smoke.sh` | Instrumented tests with direct adb instrumentation, which keeps the app data and the models. |
| `acceptance-walk.sh`, `acceptance-audio.sh` | The acceptance walk and its test clips; also the store screenshots. |
| `device-kill-resume.sh`, `device-language-check.sh`, `note-opening-timing.sh`, `launch-check.sh` | Focused device checks ([`docs/development.md`](../docs/development.md#device-scripts)). |
| `baseline-profile.sh` | Generates `app/src/main/baseline-prof.txt`. |
| `make-long-clip.sh`, `fetch-smoke-audio.py` | Test audio from the committed FLEURS clip. |
| `fetch-ci-models.py`, `push-models.sh`, `fetch-diarization-models.py` | Pinned models (`ci-models.json`) for device tests. |
| `diarization-poc.sh`, `check-whistle-device.py`, `check-backup-transport.py` | Lab probes on a device or a new emulator. |
| `devicefarm-qualify.py` | Handset qualification runs on AWS Device Farm ([report](../docs/audits/qa-handset-qualification-d1126.md)). |
| `check-portable-document.mjs` | Reads an Android document with the web viewer of the sibling `../gocassini` checkout. |

## CI plumbing

| Script | Purpose |
|---|---|
| `ci-downloads.py`, `ci-download-pins.json`, `ci-ndk.py`, `ci-pinned-compiler.init.gradle` | Pinned, verified CI downloads ([`docs/ci-downloads.md`](../docs/ci-downloads.md)). |
| `ci-build-cache.py` | Keeps the CI Gradle build cache fresh and small. |
| `ci-emulator-smoke.sh`, `ci-emulator-heap.py`, `stage-emulator-inputs.py`, `ci-models-smoke.sh` | The CI emulator jobs. |
| `ci-memory-evidence.py`, `profile-ci-reports.py` | CI memory and timing diagnostics. |
| `pr-shots.py` | Screenshot comparison boards, vendored from cassini-ux `tools/pr-shots/`. |
| `heavy-lock.sh` | One heavy run per shared machine. |

## Releases

| Script | Purpose |
|---|---|
| `package-apk.py` | Names and checks the APKs (and the Play bundle on tags). |
| `apk_compatibility.py`, `apk_models.py` | Modules of `package-apk.py`: APK architectures, native layout and permitted model files. |
| `daily-test-release.py` | Plans and records the scheduled internal test builds. |
| `changelog-section.py` | Prints the `CHANGELOG.md` section of one version. |
| `release_notes.py`, `play_changelog.py` | User release notes from `release-notes.d/` and the Play "What's new" text. |
| `release-signing-cert.sha256` | The pinned certificate digest of the release signing key. |
