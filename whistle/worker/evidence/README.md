# Packaged isolated-worker evidence

These are measured outputs, not new word goldens. The inputs and Mac reference
remain the unchanged Android #78 files. The frozen GNOME differences remain in
`docs/whistle/worker.md`; a Mac-reference pass does not establish GNOME equality.

| Guest | Pipeline cases | Exact segment/PCM matches | Exact Mac-reference words | Largest boundary difference | Worker / caller UIDs |
| --- | --- | --- | --- | --- | --- |
| API29 / 4096-byte pages | DC and control | 12/12 | 82/82 | 80ms | 99000,99001 / 10116 |
| API35 / 4096-byte pages | DC and control | 12/12 | 82/82 | 80ms | 99000,99002 / 10207 |
| API35 / 16384-byte pages | DC and control | 12/12 | 82/82 | 80ms | 99001,99002 / 10207 |

Each case has 1181 VAD frames and the exact complete-frame desktop HPF hash.
The successful isolated OPEN requires denied INTERNET permission and actual
EACCES/EPERM creating IPv4/IPv6 TCP/UDP sockets before either neural load. All
six missing/wrong privacy-flag refusals pass before the pipeline cases. The JSON
retains APK/bench hashes, build identity, source commit, PIDs/UIDs, source/raw/VAD
hashes, boundaries and raw engine outputs. Every owned emulator uses no audio
and is stopped afterward. No model, runtime, WAV, key or AVD bytes are committed.

## Retained failed attempts

- The initial bench crashed before inference: its older transitive Lifecycle
  class shadowed the app ABI. The bench now uses the app's Lifecycle version.
- The first full API35 run passed DC. Control wrote its report and emitted a
  passing test status, then instrumentation aborted with System has crashed.
  The crash buffer identified system_server ART's SuspendThreadByThreadId timeout
  on NetworkPolicy/Connectivity, not a worker crash. The overall run failed.
  An unchanged-APK warm-reboot repeat passed both cases. No assertion, word
  reference or timing bound was changed. The crash is not silently retried away.
- That earlier private-process pass shared the app UID and is not proof of
  sandbox isolation. The isolated-UID draft then failed before neural loading:
  Android appends the service component to its declared process name, bypassing
  the exact-name startup check and entering notification initialization. The
  failed owned run was stopped after preserving the crash. The process-namespace
  check now handles the appended component in both startup and cleanup.

The table contains the subsequent real isolated-UID runs after that fix,
including the production minimum API29 and actual16KB pages. Release package
deltas and sampled peak PSS remain separate measurements; no unmeasured
result is implied by this table.
