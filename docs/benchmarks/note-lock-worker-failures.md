# Note lock worker failure latency

D82 measures the slow suites before changing them. In green CI run 42681353693,
NoteLocksTest takes 11.075 s for four cases. Most is cold Robolectric startup.
The complete data worker has 228 cases and takes 43.559 s by summed JUnit time.

## Avoid a timeout after a worker has failed

The contention test starts two blocking workers and four coroutine workers. Each
makes 2,000 lock calls. Its final assertion requires all 12,000 increments.
Previously, each blocking worker counted down a latch after its loop. An
exception ended that worker before the countdown. The test then waited for the
30-second timeout and reported a generic assertion failure.

ExecutorCompletionService reports both successful and failed completion. The
test consumes both results and calls get(), so an exception reaches JUnit with
its original cause. Results arrive in completion order. A shared monotonic
deadline retains the existing 30-second aggregate wait bound. The test closes
its own executor in finally.

The original two blocking workers, four Default-to-IO coroutine workers, loop
counts, counter assertion and main-thread/IO checks remain. No production code,
fixture, test case, timeout or workflow changes. Passing-run speed is not the
claimed benefit.

## Injected-failure measurement

Parent: `ab65ca52`. JDK: Temurin 17.0.21+9, arm64 Mac mini, full tiered JIT,
512 MiB test heap and 2 GiB Gradle heap. Heavy jobs use the shared lock and
memory admission; outputs and dependencies are external.

Temporarily make a blocking call for lock id `n` throw
`IllegalStateException("D82 injected worker failure")`. Run only
`NoteLocksTest.suspendAndBlockingCallersExcludeEachOther` on the parent and
candidate, with fresh test execution. Record its JUnit case time:

| Version | Case time | Reported failure |
| --- | ---: | --- |
| Parent, completion latch | 31.968 s | Generic AssertionError after the timeout |
| Candidate, completion queue | 1.894 s | ExecutionException with the injected IllegalStateException as its cause |

Both figures include cold test setup. The change avoids the 30-second wait;
it is not an end-to-end CI speed claim. The injected fault is removed before
validation of all 228 data cases and detekt. It is not committed or run in CI.

## Rejected experiments

A data-wide C1 cap provides little overall benefit: three alternating fresh-JVM
runs give a summed JUnit median of 9.160 s with full JIT and 9.028 s with C1.
The concurrent-write case in NamesStore regresses from a median of 0.759 s to
1.430 s. The data worker therefore retains full JIT.

A barrier-based lock-test scheduling prototype also failed to improve passing
runs. It is discarded. The committed change preserves the original contention
workload and addresses failed-worker completion only.
