# Documentation

This index lists every document under `docs/`. Start with the guides. The reference documents describe how the current
app works. The records further down keep the evidence and decisions of earlier work. They describe the code at the date
or commit that they name, and nobody updates them afterwards.

The contributor entrypoints at the root of the repository are [`CONTRIBUTING.md`](../CONTRIBUTING.md) (people) and
[`AGENTS.md`](../AGENTS.md) (coding agents). [`scripts/README.md`](../scripts/README.md) lists the helper scripts.

## Guides

- [`development.md`](development.md): setup, modules, worktrees, test tiers, screenshot tests, device scripts, CI and the pre-release gate.
- [`release.md`](release.md): tags, signing, the Play upload and the F-Droid draft.
- [`release-notes.md`](release-notes.md): user-facing release notes and the fragments in [`release-notes.d/`](../release-notes.d/README.md).
- [`native-runtime.md`](native-runtime.md): the sherpa-onnx AAR, libopus and how to build them from source.
- [`ci-downloads.md`](ci-downloads.md): the pinned CI download cache for Gradle, the SDK and the NDK.

## Reference

- [`architecture.md`](architecture.md): modules, storage, background work and the build.
- [`data-formats.md`](data-formats.md): storage layout, the portable document, exports and the differences from gocassini.
- [`backup.md`](backup.md): device-to-device transfer and backup (D97, D98).
- [`recorded-at.md`](recorded-at.md): the recording time of a note (D95).
- [`transcript-rendering.md`](transcript-rendering.md): how `ReadingTurns` groups the transcript into turns (D84).
- [`whistle-speech-gate.md`](whistle-speech-gate.md): the Whistle speech gate, ported from the desktop `SpeechGate`.
- [`whistle/included.md`](whistle/included.md): the included Whistle model and the default speech model.
- [`whistle/worker.md`](whistle/worker.md): the private Whistle worker process.
- [`whistle/job-adapter.md`](whistle/job-adapter.md): the Whistle recognizer adapter of `TranscriptionJob`.
- [`ui-fidelity.md`](ui-fidelity.md): the UI fidelity report against the design reference in [`design/reference/`](../design/reference/README.md).

## Design specifications

- [`design/features.md`](design/features.md) and [`design/ui.md`](design/ui.md): the features and the UI system of 0.2.
- Phase 1 work packages: [WP2 Library and search](design/phase1-wp2.md), [WP3 meeting view](design/phase1-wp3.md),
  [WP4 recording](design/phase1-wp4.md), [WP5 Transcribing](design/phase1-wp5.md),
  [WP6 Name a speaker](design/phase1-wp6.md) and the [D-990 QA revision](design/qa-d990.md).
- Phase 2 and later: [first-run hero (D-975)](design/phase2-d975.md), [Moving and backup (D-980)](design/phase2-d980.md),
  [import types and cards (D-1030)](design/phase2-d1030.md), [Imported provenance (D-1035)](design/phase2-d1035.md),
  [one export set (D-1039)](design/phase2-d1039.md), [starring and filters (D-1043)](design/phase2-d1043.md),
  [meeting nouns (D-1048)](design/phase2-d1048.md), [voices question and model download sheet (D-1086)](design/phase2-d1086.md),
  [About copyright line (D-1090)](design/phase2-d1090.md) and [Report a problem (D-1132)](design/phase2-d1132.md).

## Records

These files are dated evidence. Read the current guides and reference documents for the app as it is now.

- QA and audits: [D-1057 walkthrough](audits/qa-walkthrough-d1057.md),
  [handset qualification (D-1126)](audits/qa-handset-qualification-d1126.md) and
  [model download completion](audits/model-download-completion.md).
- Reviews of earlier changes: [D82 acceptance follow-up](review/android-acceptance-d82.md),
  [D82 CI profile](review/android-ci-d82.md), [bulk transfer (D96)](review/android-bulk-transfer-d96.md),
  [Files-app access (D97)](review/android-documents-provider-d97.md),
  [API 29 heap failure](review/android-flake-api29-heap.md) and [CI memory evidence](review/ci-memory-evidence.md).
- Test-suite benchmarks: [app unit-test JIT](benchmarks/app-unit-test-jit.md),
  [coordinator factory events](benchmarks/coordinator-factory-events.md), [export CPU work](benchmarks/export-cpu-work.md),
  [held-queue events](benchmarks/held-queue-events.md), [job cancellation](benchmarks/job-cancellation-events.md),
  [note lock worker failures](benchmarks/note-lock-worker-failures.md),
  [screenshot comparison](benchmarks/screenshot-comparison.md), [speaker statistics](benchmarks/speaker-statistics.md)
  and [UI test compiler threads](benchmarks/ui-test-compiler-threads.md).
- Pre-release gates: [0.2.0](notes/2026-10-12-pre-release-0.2.0.md) and [0.4.0](notes/2026-11-04-pre-release-0.4.0.md).
- Notes of the 0.1.0 prototype: [feasibility](notes/feasibility.md), [Pixel 8 results](notes/pixel8-results.md) and
  [acceleration](notes/android-acceleration.md).
