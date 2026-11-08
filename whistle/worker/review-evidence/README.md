# Worker review follow-up evidence — 2026-11-08

These are measured results for the #83 follow-up, not new goldens. Runtime
code is `125e78d`; the release packaging configuration is `63278d2`. Runtime
sources are unchanged between those commits. APK/test/bench hashes and source
commits bind the results in the JSON files. All inputs and Mac-reference words,
PCM hashes and the 80 ms bound are unchanged. The frozen GNOME differences
remain documented in [the worker contract](../../../docs/whistle/worker.md).
This evidence does not assert equality with that different frozen reference.

## Actual arm64 ABI APK

| Guest | DC/control segments | Mac-reference words | Boundary bound | Worker / caller UIDs |
| --- | ---: | ---: | ---: | --- |
| API29 / 4096-byte pages | 12/12 | 82/82 | 80 ms | 99000,99001 / 10116 |
| API35 / 4096-byte pages | 12/12 | 82/82 | 80 ms | 99000,99002 / 10207 |
| API35 / 16384-byte pages | 12/12 | 82/82 | 80 ms | 99000,99001 / 10207 |

All cases retain the exact raw segment PCM and complete-frame HPF hashes,
with 1181 VAD frames per case. Each run first passes six missing/wrong privacy
flag refusals and the new native main-UID refusal with all flags set correctly.
Successful isolated OPEN also requires denied INTERNET permission and actual
IPv4/IPv6 TCP/UDP socket permission refusals before any neural runtime load.
Both models are mapped from the pinned arm64-only read-only data container.
All owned emulators used no audio and stopped after the run.

## Unsigned minified release packages

Decimal MB, rounded here. Exact bytes, SHA-256 digests and native ZIP member
sizes are in [packages.json](packages.json). APKs and the normal two-ABI AAB
passed the ABI-set and source-pin checks. APKs also passed 16 KB ELF/ZIP
alignment checks. No raw models remain in common assets, and x86_64 has no
private Whistle Needle, ORT or weight container.

| Package | Merged #83 MB | Follow-up MB | Change MB |
| --- | ---: | ---: | ---: |
| x86_64 APK | 60.55 | 41.31 | -19.25 |
| arm64-v8a APK | 77.38 | 77.41 | +0.04 |
| Universal APK | 114.21 | 114.25 | +0.04 |
| Normal two-ABI AAB | 77.70 | 77.71 | +0.01 |

The baseline is reused from PR84's measured `b128fa6` candidate. Its Git tree
`3a6f8797` is identical to merged main `6067cff`; it was not remeasured here.
Both measurements use JDK17, NDK28.2.13676358 and the same pinned inputs.
The x86 baseline contained 19,246,931 bytes of unused common model assets;
its measured reduction is 19,247,145 bytes. The previous x86 package already
excluded the private ARM libraries. The new private ARM model/runtime payload
is 40,472,684 bytes, all under the arm64 native directory. Container headers,
padding and native ZIP alignment explain the small ARM/universal increase.

The AAB itself includes both ABIs; its ABI-specific libraries are delivered
through bundle ABI splits. These ZIP sizes do not measure installed disk use
or Play-served downloads. No new peak-PSS measurement is claimed. The earlier
PR84 memory result applies to its explicitly recorded worker build.

Reproduce with the separate APK/bundle commands and
`whistle/worker/review_measure.py` in the worker contract. All build/cache/tmp
and emulator outputs stayed on the external disk, under the shared heavy lock.
Every resource gate passed with unchanged swap-outs (7467076) and internal
free space above 15 GB. Gradle daemons stopped afterward. No signing key was
read and no release or upload was performed.

## Unit checks and retained failures

[unit-tests.json](unit-tests.json) records app319/engine107 tests, zero failures,
and one existing engine skip. All ten new JVM tests ran and passed, including
socket/hash refusal, permit deadline, failed-bind cleanup/error suppression,
interrupt restoration, process-name boundaries and multiple PCM ring wraps.
Python packaging/payload checks pass all12 cases. Local detekt, lint for app,
data and UI, Ruff, ShellCheck, actionlint, links and the GNU-sed verbatim guard
pass. No existing golden or tolerance changed.

The first CI matrix stopped before instrumentation because its preflight
expected one APK. Explicit x86_64 selection fixed that; the full API26/29/34/35
matrix passed at `b2936dd`. The first combined release APK/AAB invocation failed
at AGP buildReleasePreBundle because multiple shrunk-resource files are not
supported (Google issue402800800). The two separate invocations with
`-PcassiniBundle=true` for the bundle both passed; CI and the recipe use them.
These packaging failures were not test assertion failures or parity waivers.
