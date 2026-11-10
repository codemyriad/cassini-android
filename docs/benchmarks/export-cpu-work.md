# Export test CPU work (D88)

The [11-hour export test](../../core/src/test/kotlin/io/codemyriad/cassini/core/export/LongExportTest.kt) keeps its 104,761-word fixture, all three writers, output-shape checks, final-word check and clocks past ten hours. Its former performance condition was less than 30 elapsed seconds. The D84 review of Android #94 recorded a 50.58-second failure with the old interval-pair implementation; #96 fixed that regression.

The test now measures the calling thread's CPU work for the writers. Each measured batch keeps the 30-second CPU ceiling. Three alternating half/full pairs use medians for a 2N/N growth check below 3.5. Warming the writers and alternating input order reduce compilation bias. The five-minute outer timeout only stops a hung test.

The half fixture has 52,380 words and the full fixture has 104,761. On the shared arm64 Mac with JDK 17, the initial three-pair probe measured median CPU of 96.651 ms / 296.060 ms (3.06x). The new guard's positive run measured 90.800 ms / 267.270 ms (2.94x). These are separate warming contexts, not an application speed comparison. Linux full-build CI then measured 3.681x (median 500.845 ms / 1843.405 ms) and correctly failed the 3.5 growth guard. The production overlap scan still visited every pair of runs, even disjoint ones. It now sorts audible envelopes, expires ended runs with a priority queue, and intersects only overlapping envelopes. Original run indices retain the reference collision ordering on ties; the exact merged-interval intersection and all word/row semantics are unchanged. Cost is O(R log R + overlapping-envelope candidates), plus the existing exact intersections, rather than every run pair. Dense overlap can still have quadratic output/candidates; this fixture is sparse. Final sweep measurements and historical-code negative-control validation are pending before review.

All repeated before/after probe outputs have identical SHA-256 values. For the full fixture:

| Export | SHA-256 |
| --- | --- |
| TXT | `2a269d0ca7695dea6bda2e680695124ffdfa2b759c81efac207523019ad06a39` |
| SRT | `386a3e2ed9c608df814821b666ff9fe0f2c3cd7b71aa615dd311c2e70ee637ec` |
| VTT | `7fb104a7dfa5f41a1c258cbf51498e8bacd47054967c0bd5ef06d07d8024467d` |

Local runs use the shared heavy lock, check memory inside it, store outputs externally, and stop Gradle daemons. No emulator, model weights or private audio are needed. The temporary probe is not part of the PR. The broader D88 inventory is in coordination `REPLIES-ANDROID-wall-clock-audit-2026-11-10.md`.
