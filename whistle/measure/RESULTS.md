# Whistle package and PSS measurements — 2026-11-08

Baseline is main `8765f6d`; candidate is worker PR83 `b128fa6`.
Both use the same JDK17, NDK28.2.13676358, Gradle9.8.2 and pinned native inputs.
These are unsigned, minified/resource-shrunk release packages. No release key
was read and no package was published. All six builds passed and every artifact
has the requested ABI set. Exact bytes, hashes and member contributions are in
[evidence/packages.json](evidence/packages.json).

MB below is decimal, not MiB.

| Package | Baseline MB | Candidate MB | Increase MB |
| --- | ---: | ---: | ---: |
| Normal two-ABI AAB | 51.98 | 77.70 | 25.72 |
| Normal two-ABI APK | 73.68 | 114.21 | 40.54 |
| arm64-v8a APK | 36.86 | 77.38 | 40.52 |
| x86_64 APK | 41.25 | 60.55 | 19.30 |
| arm64-v8a-only AAB (diagnostic) | 29.42 | 55.14 | 25.72 |
| x86_64-only AAB (diagnostic) | 31.88 | 49.83 | 17.95 |

The single-ABI AABs are diagnostic subsets. The normal AAB is the two-ABI row.
AAB ZIP size is not a Play delivered-download or installed-disk measurement.
No installed app/data-size claim follows from this table.

The arm64 addition includes the exact 16,919,407-byte Whistle weights,
2,327,524-byte Silero model, 1,856,488-byte host-linked Needle library and
19,347,616-byte private ORT member, plus bootstrap/code/notices/alignment.
The models total 19,246,931 bytes and live in base assets. Consequently x86_64
still carries these unused models even though Whistle is honestly unavailable
and Parakeet is retained. The 19.30 MB x86 APK increase is real; it is not hidden
by the capability policy. No large secondary Needle model or Python runtime is
included, and there is no installed extracted copy of these mapped models.

## Sampled child memory

[evidence/pss-api35.json](evidence/pss-api35.json) binds the app/bench hashes,
image/kernel and successful DC/control instrumentation to every memory sample.
The owned arm64 API35/4KB AVD has 2 GiB RAM and two CPUs. It reboots owned warm
userdata; each case creates a fresh isolated worker/model process. Socket denial,
privacy refusals and all existing parity assertions pass during this run.

| Case | Samples | Largest observed PSS KiB | MiB | Largest sample gap ms |
| --- | ---: | ---: | ---: | ---: |
| DC | 7 | 154058 | 150.45 | 116.56 |
| Control | 18 | 153789 | 150.18 | 279.74 |

The requested cadence is 50 ms; actual collection time makes the gaps larger.
These 25 samples have no collection errors, and observed PID/UID match the
worker report in both cases. The largest observed child PSS is 150.45 MiB.
This is debug instrumentation on DC/control inputs. It is not an exact kernel
high-water mark, a main-process/whole-app total, a maximum-duration/long-meeting
stress measurement, or a representative phone/minimum-RAM acceptance result.
Cold-load/first-word latency and installed disk use are not claimed here.
Those acceptance checks remain before the final Included/default decision.

## Retained collector failures

The first collector missed its readiness marker before inference and produced
no peak. A time-cursor revision collected 20 valid DC samples (partial peak
149.36MiB), then missed control readiness; that overall batch failed. Partial
output remains separate and is not used as a completed measurement. The final
collector reads retained logs but ignores process events until its own unique
marker arrives, then waits for actual process-start events. It validates PID
and UID against the parity report. No fixed settling sleep, parity waiver or
zero-memory fallback was added. Both final cases and all checks passed.

The sampler adds overhead. Every emulator was muted/stopped, builds used the
shared heavy lock and external outputs/caches, Gradle daemons were stopped,
swapouts stayed 7467076, and internal disk free stayed above 15 GB. No user device,
recording, account, browser or installed extension was touched.
