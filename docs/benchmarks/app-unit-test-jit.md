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

## Code-cache capacity (2026-11-11)

C1 must be paired with `-XX:ReservedCodeCacheSize=240m`. On the pinned JDK 17,
`-XX:TieredStopAtLevel=1` implicitly selects a 48 MiB code cache, instead of the
240 MiB normally selected with full tiered compilation. Running `java` with
`-XX:TieredStopAtLevel=1 -XX:+PrintFlagsFinal -version` exposes the effective
`ReservedCodeCacheSize`; compare it with the same command without the C1 flag.
The explicit limit restores capacity without changing compilation tiers or the
test roster. It is a reservation limit, not an initial allocation of 240 MiB.
See the [JDK 17 code-cache option](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#advanced-jit-compiler-options-for-java).

[Main run 43039814205](https://github.com/codemyriad/cassini-android/actions/runs/43039814205)
on GitHub-hosted Ubuntu 24.04 exhausted this cache: 49,147 KiB used out of
49,152 KiB, leaving 4 KiB. HotSpot disabled compilation and then threw
`VirtualMachineError: Out of space in CodeCache for method handle intrinsic`
while Robolectric linked `SQLiteConnectionPool.obtainConnectionWaiterLocked`.
That killed the processing coroutine; waiting for its replacement job timed out
in `discardAfterTheRunEndedButBeforeItsOutcomeIsWrittenRemovesTheCheckpoint`.
`finishWithConsentOpensTheNote` subsequently reported the **same** error as
`UncaughtExceptionsBeforeTest`. Both JUnit XML reports retain its complete stack.

The capacity bug predates bulk transfer PR #166: commit `38346a1c` introduced
the C1 setting on November 9. Neither failure is evidence of a new uncaught I/O
exception or a changed dispatcher. Fresh-worker runs on the ARM Mac do not
reproduce the exhaustion, but both versions approach the same capacity ceiling:

| Unmodified source | Tests | Failures | Code-cache peak |
| --- | ---: | ---: | ---: |
| Pre-#166 main, `7022f359` | 403 | 0 | 47,849 KiB / 49,152 KiB |
| Main, `231c4252` | 413 | 0 | 47,846 KiB / 49,152 KiB |
| Main with explicit capacity and regression check | 414 | 0 | 61,735 KiB / 245,760 KiB |

These runs use the complete `:app:testDebugUnitTest` task with its normal class
order and worker configuration. This is the app task selected by `ciTest`;
other modules execute in separate test JVMs. The two reported classes also pass
together in three fresh workers on unmodified main, 41 tests per run. These are
reproduction samples, not retries that discard a failed result.

`JvmCodeCacheTest` queries the running HotSpot worker's effective capacity. With
the old configuration it fails deterministically with 50,331,648 bytes; the
explicit reservation must supply at least 251,658,240 bytes. Worker shutdown
also prints code-cache usage for subsequent CI diagnosis. Existing test
timeouts, coroutine exception reporting, class order and assertions stay intact.
