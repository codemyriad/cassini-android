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

The staging tests cover debug and releaseTest layouts, byte-identical copies, exclusion of ARM/universal extras, stale output removal, and missing/ambiguous input refusal. Actionlint and ruff pass locally. Exact-head CI validates the real staged APKs on all four API levels. Candidate artifact bytes and Gradle task timings are recorded after the run.
