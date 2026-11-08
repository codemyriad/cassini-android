# JVM FIR conformance result

The [record](jvm-fir.json) binds the result to source/test-data hashes and the
original external JUnit reports. The six synthetic test methods pass against
independent pinned Mac C outputs. The full core suite ran 542 tests, with two
existing skips and no failures/errors. The explicit speech conformance task ran
one test without a skip and passed all assertions:

- Exact published clean FIR hash.
- Exact raw input hashes for all twelve frozen DC/control segments from #78.
- Both published complete-frame VAD high-pass hashes (604672 samples per track).
- Byte equality across 511/8192-sample decoder chunks and 512-sample filter frames.

Detekt, Ruff, relative links and diff checks pass. All local heavy work used the
shared host lock and external build/cache/tmp directories. The initial SDK setup
failure and Kotlin wrapping failure remain in the external logs; neither changed
numerical expectations. No old golden changed.

This result is **Mac arm64/JDK 17**, not an Android VAD/ASR/worker run. The added
CiSmoke test checks independent synthetic FIR hashes under ART; its CI results
are separate. Actual Silero probabilities/segments, worker lifecycle/checkpoints,
size/memory and Included/default remain unproved by this result. The frozen
GNOME/Mac word discrepancy is retained, as described in the parent README.
