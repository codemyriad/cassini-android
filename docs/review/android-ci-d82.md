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

## Follow-up: task and cache profiles, 2026-11-11

`scripts/profile-ci-reports.py` reads downloaded Actions jobs and Gradle HTML profiles offline. It ranks step medians separately for each runner type, and reports individual task timings separately for each runner/cache-state pair. It excludes overlapping module totals and never sums task times into build wall time. This is diagnostic data, with no automatic performance threshold.

The refreshed sample contains successful runs [43034795891](https://github.com/codemyriad/cassini-android/actions/runs/43034795891), [43030649824](https://github.com/codemyriad/cassini-android/actions/runs/43030649824), [43033470789](https://github.com/codemyriad/cassini-android/actions/runs/43033470789) and [43020103181](https://github.com/codemyriad/cassini-android/actions/runs/43020103181). The last uses attempt 2; the others use attempt 1. All four builds and API matrices used GitHub-hosted runners. One run's static/core jobs used self-hosted runners, so those observations are kept outside the hosted ranking. These runs have different source changes and cache histories; they are not a controlled before/after benchmark.

### Cache write policy

The build's explicit cache-read-only condition checked only the runner. On GitHub-hosted pull requests it became false, overriding the action's default branch restriction. The [pinned action definition](https://github.com/gradle/actions/blob/8da46b539cd8fe6ab27ca75128919a18d757604c/setup-gradle/action.yml) confirms that read-only restores existing entries without saving them; its default restricts writes to the default branch. [Gradle's cache documentation](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md#using-the-cache-read-only) explains that a PR's merge-ref cache is reusable only by that PR.

A cache inventory snapshot contained four PR-scoped Gradle entries totalling 5,778,299,237 bytes. They used the same two dependency keys across PRs 166, 169 and 170. These copies consume quota, while their merge-ref scope prevents reuse by other PRs. The diagnostic candidate restored an exact cache hit and skipped saving; the fix prevents new PR copies on cache misses. It does not delete existing caches or claim an immediate wall-time saving.

The build now writes the Gradle home only on GitHub-hosted main. PRs, tags, other refs and self-hosted jobs remain read-only; other jobs remain readers. The runner-selection guard enforces both the single main writer and these exclusions. Regression cases reject the old runner-only condition, a branch-only condition, unconditional writes, a missing main writer, and a second writer in static/core jobs.

| Hosted job / step | Samples | Median | Range |
| --- | ---: | ---: | ---: |
| build / assembly, unit/conformance tests and lint | 4 | 333 s | 152–456 s |
| API 34 / device checks | 4 | 242.5 s | 193–286 s |
| API 29 / device checks | 4 | 213.5 s | 181–303 s |
| API 26 / device checks | 4 | 208 s | 160–227 s |
| API 35 / device checks | 4 | 207 s | 193–250 s |
| static / detekt | 3 | 73 s | 70–77 s |
| JDK only / core tests | 3 | 41 s | 12–64 s |
| build / Android setup | 4 | 33 s | 29–38 s |
| static / Gradle setup | 3 | 17 s | 14–18 s |
| JDK only / Gradle setup | 3 | 14 s | 13–16 s |

The most expensive executed unit-test tasks were UI (median 122.27 s, three executions), app (69.34 s, three) and data (66.32 s, three). Executed core main lint took a median 104.05 s (three), core test lint 71.47 s (three), and app main lint 60.93 s (three). UI main lint executed once at 88.28 s and came from cache three times at 0.040–0.740 s. The UI test task came from cache once at 4.091 s. Combining these cache hits with executions would hide the cost of the work that ran.

The earlier artifact/invocation changes remain the measured transfer reduction. This follow-up prevents unnecessary PR cache writes and supplies reproducible task attribution for the remaining build time. A useful next experiment would hold the source and runner fixed while inspecting UI test and core lint cache misses; these observations alone do not justify dropping those checks or tuning worker/heap limits. All four samples' retained memory counters have zero OOM-kill increase. Their peak counters already include preparation work before Gradle, so a peak is not a JVM heap measurement.

### Collect and reproduce

Use one successful attempt per run. Save its complete jobs response as `jobs.json` and the run metadata as `run.json`. Select the `android-checks` artifact created between that build job's start and completion; save its metadata as `artifact.json` and extract it under `checks/`. This avoids taking an earlier failed attempt's artifact when names repeat.

Example for one verified sample (artifact retention can expire; use a current successful run when it does):

```sh
mkdir -p evidence/43034795891/checks
gh api repos/codemyriad/cassini-android/actions/runs/43034795891 > evidence/43034795891/run.json
gh api 'repos/codemyriad/cassini-android/actions/runs/43034795891/attempts/1/jobs?per_page=100' > evidence/43034795891/jobs.json
gh api repos/codemyriad/cassini-android/actions/artifacts/13863272952 > evidence/43034795891/artifact.json
gh api repos/codemyriad/cassini-android/actions/artifacts/13863272952/zip > evidence/43034795891/reports.zip
python3 -m zipfile -e evidence/43034795891/reports.zip evidence/43034795891/checks
python3 -I scripts/profile-ci-reports.py evidence/43034795891
```

Pass several run directories to combine observations. Reports label whether artifact metadata was checked; handcrafted inputs without it remain usable but are not marked checked. With metadata supplied, a wrong run, head, attempt, expired artifact or artifact outside the build window fails. Incomplete job pagination, duplicate runs, failed builds/tasks and missing task tables/profiles also fail.

Fixture tests cover parsing, ranking, overlapping module exclusion, host/cache separation and the CLI layout, plus provenance and incomplete-input refusal. Mutations that merge cache states or runner types, include module totals, accept failed builds, duplicate a run, or accept an artifact outside its build window must fail the corresponding regression. Test, lint, ABI, API, model and coverage settings remain unchanged; only cache-write eligibility changes. Full Android CI validates the PR.
