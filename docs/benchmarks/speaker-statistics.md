# Whole-run speaker statistics (D82)

The statistics caller needs whole runs, including inline speakers, but was computing the
complete reading rows, interjection layout and competing-speech labels before discarding them.
`ReadingTurns.wholeRuns` now shares preparation with the full reader and returns those runs
directly. The six viewer vectors compare both run projections with legacy and measured ends;
all existing statistics assertions and reader vectors stay in place.

## Measurement

Same Mac mini, JDK 17.0.21+9 arm64, 1 GiB Gradle JVM, one job under the shared heavy lock.
The existing `LongRun` fixture produces 104,761 synthetic words across eleven hours and four
speakers. Three untimed warmups precede five timed samples; the median covers only
`SpeakerStats.of`, with word-count assertions outside the timed interval.

| Statistics projection | Median |
| --- | ---: |
| Parent main `cc41dcb` | 194.4 ms |
| Shared whole runs | 38.3 ms |

The table covers the local statistics operation. Separate core validation passed 570 tests
with two existing platform/fixture skips and detekt; Gradle took 18 s, with 3.641 s of
cumulative JUnit case time.

## Repeating the measurement

The temporary JUnit class below uses the existing core test helper. Place it in the core test
source set for the measurement, then remove that temporary file so normal CI does not acquire
a benchmark workload. Run just this class with `:core:test --tests
io.codemyriad.cassini.core.speakers.SpeakerStatsBenchmarkTest` using the shared-lock and
external-output setup described in [development.md](../development.md). Check memory admission before the run.

```kotlin
package io.codemyriad.cassini.core.speakers

import io.codemyriad.cassini.core.checkpoint.LongRun
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerStatsBenchmarkTest {
    @Test fun measureElevenHourStatistics() {
        val words = LongRun().words(11L * LongRun.HOUR_MS)
        repeat(3) { SpeakerStats.of(words) }
        val nanos = List(5) {
            val start = System.nanoTime()
            val stats = SpeakerStats.of(words)
            val elapsed = System.nanoTime() - start
            assertEquals(words.size, stats.values.sumOf { it.wordCount })
            elapsed
        }
        println("D82_STATS_BENCHMARK words=${words.size} samplesMs=${nanos.map { it / 1e6 }} medianMs=${nanos.sorted()[2] / 1e6}")
    }
}
```
