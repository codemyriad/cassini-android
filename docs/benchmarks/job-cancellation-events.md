# Event-driven job cancellation test

In green CI run 42694004278, TranscriptionJobTest takes 7.551 s across 42
cases. Its native-cancellation case takes 0.334 s. The test waits for a cancel
request by spinning, then spins for a fixed further 300 ms before checking
resource ownership. This consumes CPU and makes elapsed time stand in for
ordering evidence.

## Change

Replace both spin loops with events. A semaphore holds the fake native call.
The actual cancellation interrupt wakes its first acquire; the fake reports
that interrupt through a latch, then waits uninterruptibly for the test's
release. It restores the interrupt flag before returning to the job, matching
the previous fake's behavior at the native boundary.

After observing the interrupt, the test asserts that the call has not finished,
the recognizer and diarizer remain open, and the native budget remains held.
Only then does it release the fake in `finally`. That release also runs if an
assertion fails, so the test does not strand its inference worker. Existing
10-second event/join bounds remain upper bounds, not delays. The release wait
is controlled by that `finally`, including on event timeout.

All original end-of-native-call, checkpoint, cancellation outcome, handle
cleanup, budget, marker and adoption assertions remain. No production code,
fixture duration, audio sample, checkpoint or case is removed or changed.

## Measurements

Parent `416159c1`, Temurin 17.0.21+9, arm64 Mac mini, external outputs and
caches, shared heavy lock and memory admission before each Gradle invocation.
Each run starts a fresh test JVM, with a 512 MiB test heap and 2 GiB Gradle
heap. Gradle daemons stop after each invocation. An initial JFR profile passes
all 42 cases in 1.370 s; the cancellation case is 0.306 s.

Three baseline invocations precede three candidate invocations. Every run
executes all 42 TranscriptionJobTest cases with identical names and zero skips,
failures or errors. The test task is forced to execute, without a cached result.

| Measurement, median | Before | After |
| --- | ---: | ---: |
| Native-cancellation case | 0.305 s | 0.005 s |
| Complete 42-case suite, summed JUnit | 1.095 s | 0.790 s |

The suite improves about 27.9% locally. The changed case removes the fixed
300 ms wait; it does not shorten native work in the application. These are
local test measurements, not an end-to-end CI improvement claim.

All individual samples, in seconds:

- Baseline case: 0.305, 0.305, 0.306; suite: 1.101, 1.095, 1.095.
- Candidate case: 0.005, 0.005, 0.005; suite: 0.784, 0.790, 0.793.
- Baseline complete Gradle invocation: 6.902, 4.730, 4.627.
- Candidate complete Gradle invocation: 11.547, 4.007, 4.020.

The first candidate invocation includes test recompilation, so invocation
samples do not establish an overall build-speed improvement. The suite's
JUnit samples measure the test execution itself.

A temporary fault probe drops the fake's interrupt restoration. The targeted
test fails its original interrupt-status assertion in 0.091 s. The probe is
restored before final validation and is not part of the patch.

## Reproduce

In clean parent and candidate worktrees with JDK 17 and normal development
inputs, run the following task three times per version with
`--no-daemon --max-workers=2 --configure-on-demand`:

```sh
./gradlew :engine:testDebugUnitTest --tests '*TranscriptionJobTest' -I fresh.gradle
```

Use this temporary init script to require actual execution:

```groovy
gradle.beforeProject { p ->
    if (p.path == ':engine') p.tasks.withType(Test).configureEach { t ->
        t.outputs.upToDateWhen { false }
        t.outputs.doNotCacheIf('D82 fresh execution measurement') { true }
    }
}
```

Compare test names, counts and individual case times in the JUnit XML. Keep
these cache controls and measurement outputs outside the repository. Take the
shared heavy lock, check memory and use external outputs on the shared Mac.
