# Evidence for a disappearing CI Gradle daemon

Android runs [42985097860](https://github.com/codemyriad/cassini-android/actions/runs/42985097860)
and [42993001096](https://github.com/codemyriad/cassini-android/actions/runs/42993001096)
failed with `Gradle build daemon disappeared unexpectedly`. Both failures occurred on the
self-hosted Linux runner path. The retained logs do not prove OOM or a JVM crash. No failed
run was retried to obtain a green result.

The build job now saves numeric snapshots immediately before and after its Gradle step,
including when Gradle fails. The existing `android-checks` artifact retains both JSON files.
Compare the two cgroup event maps: an increased `oom_kill` count is evidence of a memory
kill within that cgroup; an already nonzero unchanged count is not evidence for this build.
The memory limit/peak/current and host availability provide context. Unavailable values
stay unknown, not zero.

The implementation handles the current or namespaced cgroup v2 root and the standard v1
memory-controller layout, selecting the nearest readable ancestor when a leaf hides its memory controller. It collects only allowlisted numeric values and aggregate RSS
counts. It never reads process arguments, environment, JVM crash logs or heap dumps, and
it does not retain executable paths or process identities. There is no background sampler,
clock assertion, retry or resource-limit change. Both workflow steps use isolated Python
and continue on diagnostic errors; they cannot fail a successful build or hide a Gradle failure.
Unavailable or undecodable input, restricted cgroups and a timed-out ps command produce
partial/unknown evidence. Output or cleanup failures warn without failing the job. A before
snapshot removes stale after.json first; if that cleanup fails, the warning means retained
snapshots cannot be trusted as a pair for this run.

These snapshots establish evidence for a separate scheduling or heap fix. They cannot
identify a particular killed JVM, prove a crash from an unchanged OOM counter, or survive
termination of the entire runner/container before artifacts can upload.

Primary reference: [Linux cgroup v2 memory interface](https://docs.kernel.org/admin-guide/cgroup-v2.html).
