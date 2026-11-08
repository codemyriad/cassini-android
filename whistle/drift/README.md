# Lab-only API drift experiment

This experiment tests two hypotheses from the review of Android #78: runtime
CPU dispatch from `getauxval(AT_HWCAP)` and Bionic math-function differences. It
makes no production model, Settings, minimum-API, golden or timing-tolerance
change. The approved Needle archive/model pins stay unchanged. Every linked
variant has its own verification report and hash.

Binary inspection of the pinned archive shows a `needle::g_dotprod` flag and a
constructor that calls `getauxval(16)` and extracts bit 20 (`HWCAP_ASIMDDP`). The
experiment wraps only that bit. It leaves LSE and all other capabilities real.
Natural, forced-off and forced-on dispatch are crossed with system math and
private musl math. An unchanged-engine APK is the control for link interposition.
Before any forced-on case, a separate owned guest process must execute SDOT and
produce four exact lanes of 24. Unsupported instruction execution skips forced-on
cases; a feature is never enabled just because a hostname/API/model says so.

`pins.json` identifies the immutable [musl 1.2.5 release](https://musl.libc.org/releases/).
The selected C sources remain unmodified; a declaration-only header adapts them
to the NDK headers. Their dependency closure is compiled with FP contraction,
fast-math and builtins disabled. Object-level symbol renaming keeps every defined
math symbol and its internal references private. All twelve direct math imports
of the Needle archive are routed through counted wrappers: exp/expf, log/logf,
log10, sin/sinf, cos/cosf, powf and sincos/sincosf. The ELF verifier rejects a
pinned-math variant that still imports one of those roots. Per-source hashes,
private symbol mappings, the upstream COPYRIGHT and source notices stay in the
external build output. A Bionic libm DT_NEEDED entry alone does not mean a replaced
function was called; the import inventory and counters establish that boundary.

The same public raw-PCM/Mac-reference files from #78 are used without regeneration.
Each guest records actual kernel/image metadata, HWCAP/HWCAP2, FPCR, override mode,
wrapper counts, APK/engine hashes and all thirteen transcription results. Word,
count and 80 ms checks remain strict; negative cases retain results and exit
nonzero. A batch may complete successfully as an experiment while recording
several failed parity comparisons. That is never called product acceptance.

## Run on the shared Mac

Use a fresh feature worktree and external output directories. Put the entire
build or emulator batch under **one** `lockf -k -t 3600 ~/.cassini-heavy.lock`
command. These Python helpers do not acquire that lock themselves. Do not wrap
scripts that already acquire it. Check the holder with `lsof`, and run `host.py`
inside the lock before each stage: it records `memory_pressure`, `vm_stat`, swap
usage and internal free space, and refuses low memory, growing swap or less than
15 GB internal free. If refused, do light work and retry only after conditions
recover. Do not stop another owner's job.

Build runtimes with `build.py --ndk ... --inputs ... --output ...`; the pinned
musl source is fetched only at build time, or required locally with `--offline`.
`package.py` packages all variants with one disposable lab signing key; it needs
the frozen #78 reference directory and unchanged-engine baseline/report. Invoke
`whistle/spike/run.py` with `--batch <apks/batch.json>`, the baseline `--apk`, and
an owned external image via `--api`, `--tag`, `--image`, `--output` and `--sdk`.
The runner boots one owned AVD, launches one app process/model at a time, waits on
actual boot/instrumentation/disconnect events, and stops its emulator in `finally`.
It never installs on a physical/user device, touches the main app, records audio,
opens accounts or plays sound. Every emulator has `-no-audio`.

CI compiles/inspects all variants; it does not execute Android, prove a dispatch
hypothesis or establish a minimum OS. A tested emulator API is not a device-wide
CPU feature guarantee. Results, not the hypotheses, determine the conclusion.
