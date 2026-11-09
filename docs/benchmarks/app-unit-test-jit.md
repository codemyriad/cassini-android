# App unit-test worker timing

D82 measures the slow suites before changing them. The successful build job in
run 42673103734 reports AppShellTest at 26.708 s (13 cases) and AppGraphTest at
17.507 s (6 cases). StateVariantsTest takes 27.109 s in the separate UI worker.
That run's workflow-level GitHub server error is separate from its passing build
and unit tests.

## App worker

Limit the `:app` unit-test JVM to HotSpot's first compiler tier (C1), with
`-XX:TieredStopAtLevel=1`. This reduces compilation warmup in short-lived workers.
The flag belongs to `:app` unit-test JVM arguments. Kotlin compilation, APKs,
instrumentation tests and the other modules keep their existing configuration.

Measurements use parent `7f4dc9ac`, Temurin 17.0.21+9 on an arm64 Mac mini, a
512 MiB test heap and a 2 GiB Gradle heap. Each run starts a fresh test JVM, after
build inputs are warm. Baseline and candidate alternate three times under the
shared heavy lock. Outputs and dependencies are on the external disk. Memory
admission passes before each invocation and Gradle daemons stop afterwards.

| Measurement | Default tiered JIT | C1 only |
| --- | ---: | ---: |
| All 339 app cases, summed JUnit time, median | 15.339 s | 14.480 s |
| AppShellTest, median | 3.155 s | 2.697 s |
| AppGraphTest, median | 2.530 s | 2.305 s |
| Complete Gradle invocation, median | 24.861 s | 23.976 s |

The whole app worker improves about 5.6% by summed JUnit time; AppShell improves
about 14.5%. These are local measurements, not an end-to-end CI speed claim.
All six runs have the identical 339-case roster, no skips, failures or errors.

Individual summed JUnit samples in seconds:

- Default: 15.312, 15.339, 15.399.
- C1: 14.369, 14.480, 14.533.

Individual Gradle invocation samples in seconds:

- Default: 24.952, 24.861, 24.820.
- C1: 23.976, 23.847, 24.210.

Initial pilot runs included compilation and are excluded from these samples.
No test, assertion, timeout, fixture or workflow is removed or reduced.

## Keep full JIT in the UI worker

The UI worker retains full tiered compilation. Although a C1 trial passes all
459 UI cases and lowers local JUnit time from about 14.0 s to 12.7 s, Mac checks
omit PNG verification. The existing full-phone fallback-comparison harness
exposes a regression: row-order comparison takes 4.722 ms under full JIT and
50.693 ms under C1 (medians). Both paths execute 13 times and produce identical
results. Limiting the screenshot worker would trade away its comparison speed.

The image-processing harness and its input are described in
[screenshot comparison timing](screenshot-comparison.md). Golden verification,
pixel tolerances and the changed-area validator stay intact.

## Reproduce

Use the parent commit in a clean worktree with JDK 17 and the normal development
inputs. Apply this temporary init script to force fresh app test execution:

```groovy
gradle.beforeProject { p ->
    if (p.path == ':app') p.tasks.withType(Test).configureEach { t ->
        t.outputs.upToDateWhen { false }
        t.outputs.doNotCacheIf('D82 fresh execution measurement') { true }
    }
}
```

For a second init script, add `t.jvmArgs('-XX:TieredStopAtLevel=1')` in the same
closure. Run `:app:testDebugUnitTest` alternately with each script three times,
using `--no-daemon --max-workers=2 --configure-on-demand`. Keep Gradle's heap and
the JDK equal between runs. Confirm the test task actually executes each time
and compare the test rosters as well as the times in the JUnit XML. These
measurement-only cache overrides are not part of ordinary CI.

On the shared Mac, use the heavy lock, memory admission and external outputs.
