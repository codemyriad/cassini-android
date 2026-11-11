# Android CI profile — D82, 2026-11-11

Four successful Android runs were sampled: [42976146491](https://github.com/codemyriad/cassini-android/actions/runs/42976146491), [42975733659](https://github.com/codemyriad/cassini-android/actions/runs/42975733659), [42975435299](https://github.com/codemyriad/cassini-android/actions/runs/42975435299) and [42972239340](https://github.com/codemyriad/cassini-android/actions/runs/42972239340). Durations come from GitHub job step timestamps. They exclude runner queue time. Each row has four samples; medians reduce the effect of host/cache variation. These are diagnostics, not speed assertions.

## Ten slowest steps

| Rank | Job / step | Median | Range |
| --- | --- | ---: | ---: |
| 1 | build / Build APK, run unit and conformance tests, and lint | 351.5 s | 233–738 s |
| 2 | Device checks (API 34) / Run the device checks | 226.5 s | 197–243 s |
| 3 | Device checks (API 26) / Run the device checks | 215 s | 153–247 s |
| 4 | Device checks (API 35) / Run the device checks | 197.5 s | 166–229 s |
| 5 | Device checks (API 29) / Run the device checks | 191 s | 170–213 s |
| 6 | Wrapper, detekt, actionlint, shellcheck, ruff, links, fonts and the verbatim diff / detekt | 73 s | 59–117 s |
| 7 | Engine tests with a JDK alone / Run the :core tests | 42.5 s | 10–118 s |
| 8 | build / Run android-actions/setup-android@be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd | 36.5 s | 32–42 s |
| 9 | Engine tests with a JDK alone / Run gradle/actions/setup-gradle@8da46b539cd8fe6ab27ca75128919a18d757604c | 15.5 s | 3–17 s |
| 10 | build / Run gradle/actions/setup-gradle@8da46b539cd8fe6ab27ca75128919a18d757604c | 15 s | 2–21 s |

## Unit tests and lint

Existing runs put assembly, unit/conformance tests and lint in one step. The step timestamps cannot attribute concurrent Gradle work to individual tasks. Test XML from run 42976146491 gives per-suite diagnostic durations: StateVariantsTest 22.181 s (116 cases), BoardTestFontScaleSetupTest 17.868 s (one case), AppShellTest 17.410 s (13 cases), NoteLocksTest 11.942 s (four cases), and AppGraphTest 10.363 s (six cases). These suite times are not additive wall time for the build.

The workflow now keeps Gradle HTML profiles with the check reports. These expose actual task/configuration times, including lint and each unit-test task. No test is omitted or shortened.

## Waste removed

- Debug instrumentation assembly joins the existing Gradle invocation. The old second invocation ran assembleDebug again, then built the test APK with an explicitly supplied debug property. In run 42976146491 that whole invocation took 31 s, including necessary instrumentation work. The saving is its extra JVM/configuration overhead, not the full 31 s. Tags retain the separate releaseTest invocation required by AGP.
- Each emulator downloads one compact artifact with the exact x86_64 published APK, x86_64 APK under test, instrumentation APK and data APK. Previously each downloaded every ABI and the universal APK from three artifacts. On main run 42975733659 those ZIPs totalled 531,747,357 bytes per emulator (about 507 MiB). Four APIs downloaded those inputs independently. Full published/device artifacts remain available; only the matrix transfer is narrowed.
- All ABI APKs are still assembled and validated by package-apk.py. The four API levels, every ciTest module, the JDK-only core job, all three lint targets, per-API FTS/codec checks, published-APK launch checks and fail-before-tests retry policy remain.

## Validation

The staging tests cover debug and releaseTest layouts, byte-identical copies, exclusion of ARM/universal extras, stale output removal, and missing/ambiguous input refusal. Actionlint and ruff pass locally. Exact-head CI validates the real staged APKs on all four API levels. Run [42979640070](https://github.com/codemyriad/cassini-android/actions/runs/42979640070), head f9998dd4, passed all seven Android jobs, including APIs 26/29/34/35 with the staged APKs. All 31 CI Python tests, actionlint, ruff and links passed locally.

## Candidate measurements

The compact artifact is 129,029,595 bytes versus the old three-input total of 531,747,357: **402,717,762 fewer bytes per API (75.7%)**, or 1,610,871,048 fewer bytes across four downloads. Its upload took 3 s; each matrix download took 1–4 s. The existing full artifact uploads remain, so this adds one compact upload in exchange for four smaller downloads. All four device runs passed without the retry (API 26: 220 s, API 29: 206 s, API 34: 200 s, API 35: 198 s).

The build/unit/lint step took 431 s, within the historical 233–738 s range and above its 351.5 s median. One sample does not establish an end-to-end speedup. The profile's total Gradle build time is 429.76 s, including 4.988 s startup and 4.195 s project configuration. Combining debug assembly avoids a second invocation; it does not remove the instrumentation compilation itself.

Actual task durations from that run's archived Gradle profile:

| Module | Unit test task | Main lint analysis | Test lint analysis |
| --- | ---: | ---: | ---: |
| ui | 168.890 s | 115.780 s | unit 27.417 s; Android 1.524 s |
| app | 89.420 s | 66.290 s | unit 44.052 s; Android 27.556 s |
| data | 66.880 s | 43.999 s | unit 21.072 s; Android 3.403 s |
| core | 36.460 s | 156.140 s (JVM main) | JVM test 29.433 s |
| engine | 13.149 s | 24.514 s | unit 12.965 s; Android 0.622 s |

These tasks overlap, so their durations must not be summed as wall time. UI unit tests include screenshot verification. Main and test lint analysis remain enabled, including transitive core/engine analysis. The HTML profile is in the android-checks artifact under build/reports/profile/; future runs retain the same breakdown. No slow test or lint rule was skipped to obtain the transfer reduction.
