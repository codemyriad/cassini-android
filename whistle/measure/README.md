# Whistle package and memory measurements

Use clean baseline and candidate worktrees with identical JDK17/SDK/NDK/native
inputs. Keep every build directory, Gradle/Kotlin cache, temporary directory,
Android user directory, model and output on the external volume. Initialize the
format submodule and prepare each worktree's pinned build inputs before measuring.
Do not use the old dirty checkout or a user's installed app/device.

`build-packages.sh BASELINE CANDIDATE EXTERNAL_OUTPUT` takes the shared heavy-job
lock, checks host resources before each stage and builds unsigned release APK/AAB
pairs. Set JAVA_HOME, GRADLE_USER_HOME, TMPDIR and ANDROID_USER_HOME explicitly.
No signing key is read. The script retains each commit, build log, artifact hash,
ZIP bytes and uncompressed native/model member contributions.

The `universal` row is the normal two-ABI package. The other rows vary only the
packaged ABI set through `abi.init.gradle`; their AABs are diagnostic subsets,
not submitted Play bundles or estimates of Play's delivered download size.
APK/AAB byte size is not installed disk use. No binaries belong in this repository.

For memory, add `--profile-worker` to the existing `whistle/spike/run.py` owned
arm64 worker run, with the built app, bench and privacy-test APKs. Run under the
shared lock with external AVD state, `-no-audio`, and no other heavy process.
The runner passes privacy refusals before inference and stops only its own AVD.
The profiler waits for its logcat marker and actual process-start events; test
waits do not depend on time delays. Its timed event wait sets a measurement
cadence, not a test pass condition. It samples only the named private child,
checks its PID against the parity result, and reports every sample's time/PSS,
count, maximum gap, largest observed PSS, and collection errors.

This is debug instrumentation on an emulator. `dumpsys meminfo` adds overhead;
a sampled peak is not an exact kernel high-water mark, a main-process total,
or a physical-device/low-memory acceptance result. An absent sample is a failure,
not zero memory. Retain failed parity output and reference discrepancies.
