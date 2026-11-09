# UI test compiler threads

The slowest suites in green CI run 42689172283 are StateVariantsTest
(26.747 s, 116 cases) and BoardTestFontScaleSetupTest (20.835 s, one case).
Both run in the UI test worker.

Limit that worker to two HotSpot compiler threads with `-XX:CICompilerCount=2`.
The local JDK otherwise chooses four. Full tiered compilation, including C2,
remains enabled: image comparison needs its optimized loops. This changes
unit-test JVM arguments only, leaving application builds and the app and data
test workers alone. No assertions, fixtures, captures, timeouts or PNG tolerances
change.

## Measurement

Measurements use parent `70c7f6c4`, Temurin 17.0.21+9 on an arm64 Mac mini,
a 512 MiB test heap and a 2 GiB Gradle heap. Each invocation starts a fresh
test JVM, with build inputs warm. Baseline and candidate alternate under the
shared heavy lock, with memory admission and external outputs. Gradle daemons
stop after each invocation. The test task is forced to execute without using
its cache; all runs check the identical 459-case roster and zero skips,
failures or errors.

Twelve runs retain all measured samples: baseline/candidate three times, then
one further baseline/candidate pair after another agent's locked run, then two
candidate/baseline pairs to check order bias. No sample below is discarded.
Initial exploratory pilot invocations are separate and excluded.

| Measurement, median | Default (four threads locally) | Two threads |
| --- | ---: | ---: |
| All 459 cases, summed JUnit | 14.219 s | 13.367 s |
| Font-scale setup suite | 2.827 s | 2.690 s |
| StateVariantsTest | 1.675 s | 1.601 s |
| Complete Gradle invocation | 21.632 s | 20.615 s |

The local worker improves about 6.0% by summed JUnit time and 4.7% by complete
invocation time. All twelve runs pass the same 459 cases. Baselines at the
start of the first two blocks have particularly slow initial font-scale setup
(6.639 s and 6.517 s); no causal explanation is established. The later four
baselines alone have a 14.092 s JUnit median, still above the candidate median.

Samples in execution order within each configuration, seconds:

- Default JUnit: 18.688, 14.139, 14.045, 18.499, 13.954, 14.298.
- Two-thread JUnit: 13.467, 13.378, 13.355, 13.319, 13.261, 13.759.
- Default Gradle: 34.217, 21.654, 21.250, 35.368, 21.194, 21.611.
- Two-thread Gradle: 20.842, 20.496, 20.450, 20.734, 18.652, 20.847.

These are local results, not an end-to-end CI improvement claim. Mac runs omit
Linux PNG verification, and other CPUs may choose a different default compiler
pool. The separate image-processing check below exercises
the PNG comparison operation with C2 still enabled.

## Image comparison

Use the unchanged harness and committed full-phone image described in
[screenshot comparison timing](screenshot-comparison.md), with and without
`-XX:CICompilerCount=2`. Both comparison paths execute 13 times each, including
four warmups and nine measured rounds, and retain the same pixel-count and
zero-difference assertions.

Row-major fallback medians are 4.743 ms with default compilation and 4.804 ms
with two compiler threads (about 1.3% difference in this single pair). The
library column-major path takes 5.552 ms and 5.570 ms respectively. This check
shows the operation remains near its previous speed; it is not evidence of a
pixel-comparison improvement. The earlier C1-only trial took about 50.7 ms and
was rejected. All result assertions pass in both runs.

## Reproduce

Use JDK 17 in a clean parent worktree with the normal development inputs.
Force a fresh UI test execution with this temporary Gradle init script:

```groovy
gradle.beforeProject { p ->
    if (p.path == ':ui') p.tasks.withType(Test).configureEach { t ->
        t.outputs.upToDateWhen { false }
        t.outputs.doNotCacheIf('D82 fresh execution measurement') { true }
    }
}
```

Add `t.jvmArgs('-XX:CICompilerCount=2')` inside the closure in a second script.
Alternate `:ui:testDebugUnitTest` with these scripts, keeping JDK, heap,
`--no-daemon --max-workers=2 --configure-on-demand` and all other settings equal.
Use the execution order above and retain all samples; compare full test
rosters alongside the JUnit XML and process durations. These cache overrides are measurement
controls, not ordinary CI configuration. On a shared machine take the heavy
lock and check memory before each invocation.
