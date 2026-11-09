# Coordinator factory notifications

After the [held-queue delay change](held-queue-events.md), the coordinator
fixture still polls every 20 ms for each fake job to appear in its list.
It then awaits the fake's existing `started` event. The polling adds wakeup
latency whenever the coordinator creates the job after the first check.

The factory now publishes a retained `MutableStateFlow` count after inserting
the job into the same `CopyOnWriteArrayList`. The helper awaits the count,
then the original `started` event. The count update is monotonic even with
concurrent publishers; late waiters see the retained value. All order and
result assertions continue to read the original job list. The existing
20-second creation timeout has the same scope, and the start-event wait is
unchanged. No production code, assertion, workload or fixture changes.

## Measurements

Parent `cc28603e`, Temurin 17.0.21+9 on an arm64 Mac mini, normal app test
worker C1 configuration and 512 MiB heap, 2 GiB Gradle heap. Three baseline
runs precede three candidate runs, each starting a fresh test JVM. All six
execute the identical 25-case ProcessingCoordinatorTest roster with zero
skips, failures or errors. Task execution is forced without cached results.
The shared heavy lock, memory admission, external outputs/cache/tmp and
Gradle daemon shutdown apply to every invocation.

| Median JUnit time | Before | After |
| --- | ---: | ---: |
| Complete 25-case suite | 3.938 s | 3.286 s |
| Three jobs in queue order | 0.073 s | 0.019 s |
| Stop keeps progress and starts next note | 0.047 s | 0.023 s |
| Start over discards and queues again | 0.133 s | 0.051 s |

The suite improves about 16.6% locally. This measures fixture wakeup latency,
not application processing speed or an end-to-end CI improvement.

Raw suite samples (seconds), retaining all runs:

- Baseline: 7.356, 3.938, 3.915.
- Candidate: 3.299, 3.262, 3.286.

The first baseline's first case includes particularly slow cold Robolectric
setup (5.306 s versus 2.035 and 2.011 s in later baselines). No sample is
removed. The later two baselines alone have a 3.927 s median, still above the
candidate median. Candidate first-case times are 1.993, 1.996 and 1.998 s.

Complete Gradle invocation samples, seconds:

- Baseline: 28.940, 10.630, 10.502.
- Candidate: 21.617, 10.015, 10.034.

Initial invocations include compilation/setup work. These process durations
do not isolate a build-speed gain.

## Reproduce

With JDK 17 and normal development inputs in clean parent/candidate worktrees,
run `:app:testDebugUnitTest --tests '*ProcessingCoordinatorTest'` three times
per version using `--no-daemon --max-workers=2 --configure-on-demand` and the
same heaps. Supply an external init script with `-I fresh.gradle`:

```groovy
gradle.beforeProject { p ->
    if (p.path == ':app') p.tasks.withType(Test).configureEach { t ->
        t.outputs.upToDateWhen { false }
        t.outputs.doNotCacheIf('D82 fresh execution measurement') { true }
    }
}
```

Compare complete test rosters and JUnit times, not just process duration.
Keep measurement controls and outputs outside commits. On the shared Mac,
take the heavy lock and check memory before starting.
