# Held-queue test events

ProcessingCoordinatorTest takes 4.887 s for 25 cases in green CI run
42698854427. The USER-hold case takes 0.423 s and SYSTEM-hold case 0.558 s.
Each includes a fixed 300 ms settling delay before asserting no job started.

Replace those delays with a bounded join of a drain attempt. This exercises
the service entry point even when a held enqueue did not request the service;
the assertion follows a completed queue pass instead of elapsed time. Existing
hold, ordering, paused checkpoint and explicit-continue assertions remain.
The separate start-over race needs its own synchronization point and does not
change in this patch.

## Measurement

Parent `416159c1`, Temurin 17.0.21+9, arm64 Mac mini. Three baseline runs
precede three candidate runs, each in a fresh test JVM with warm build inputs.
All six execute the identical 25-case roster with zero skips, failures or
errors. The test task is forced to execute without cached results.

| Median JUnit time | Before | After |
| --- | ---: | ---: |
| Complete 25-case suite | 4.798 s | 4.094 s |
| USER hold case | 0.363 s | 0.043 s |
| SYSTEM hold case, including cold setup | 2.475 s | 2.121 s |

The suite improves about 14.7% locally. This removes two explicit test delays;
it does not make application processing faster or establish an end-to-end CI
gain. The SYSTEM case runs first and includes cold Robolectric setup in both
versions. The raw samples retain that cost.

Individual samples, seconds:

- Baseline suite: 4.844, 4.798, 4.797; candidate: 4.094, 4.099, 4.032.
- Baseline USER: 0.371, 0.362, 0.363; candidate: 0.043, 0.042, 0.045.
- Baseline SYSTEM: 2.494, 2.457, 2.475; candidate: 2.129, 2.121, 2.095.
- Baseline complete Gradle invocation: 17.814, 11.628, 11.925.
- Candidate complete Gradle invocation: 22.743, 11.331, 11.195.

The first candidate invocation includes recompilation; process durations do
not isolate a build-speed gain. No test, workload, assertion or timeout is
removed or shortened. Each new join has the existing 20-second upper bound.

## Reproduce

Use JDK 17 in clean parent/candidate worktrees with normal development inputs.
Run :app:testDebugUnitTest --tests '*ProcessingCoordinatorTest' three times
per version, forcing fresh task execution with an external init script and
keeping the normal 512 MiB app test heap/C1 worker configuration, 2 GiB Gradle
heap and --no-daemon --max-workers=2 --configure-on-demand unchanged. Compare
all 25 case names, counts, errors, skips and JUnit durations. Use the shared lock,
memory admission and external outputs; stop daemons after each invocation.


Use this external init script to force execution, keeping it out of commits:

```groovy
gradle.beforeProject { p ->
    if (p.path == ':app') p.tasks.withType(Test).configureEach { t ->
        t.outputs.upToDateWhen { false }
        t.outputs.doNotCacheIf('D82 fresh execution measurement') { true }
    }
}
```
