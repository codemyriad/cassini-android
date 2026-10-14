# Preprocessing investigation: handoff (2026-10-14)

Work in progress, not for merge. The question: how should the app preprocess audio for Parakeet Ultra INT8 on the sherpa-onnx runtime with the NeMo reference frontend (k2-fsa/sherpa-onnx#4024, `codemyriad/sherpa-onnx@dbbeef9c`)? That runtime has been on `main` since `dcb687b`.

## State when the laptop closed
- `plan.md`: the synthesis of the understand phase, with the current pipeline knobs, the evidence that Ultra invalidates, the hypotheses H1–H9 and the experiment matrix E0–E5. The `reader-*.md` files are the four investigator reports behind it.
- `experiments-workflow.js`: the experiment workflow (prepare, E0 parity, E1/E2, E3/E4, E5 on the Pixel, three-skeptic verification, report). It had only started on the laptop (the data and harness preparation). No results exist yet.
- `scratch/`: the host harness as it was then (`run.sh`, `HostEval.kt`, two Python probes). The harness agent was extending it.

## To resume on another machine
1. Build the host JNI library of `codemyriad/sherpa-onnx@dbbeef9c` with `scripts/streaming-diarization.patch` (see `reader-harness.md`). Download Ultra INT8 (`tpmaerk/parakeet-ultra-sherpa-onnx-int8@b86784a3`) and silero_vad.onnx, pinned in `core/.../ModelCatalog.kt`.
2. Set `S` (the workspace path) in `experiments-workflow.js` for the new machine, and run it again from the start. It builds the data (FLEURS `70bb2e84`, synthetic long-form, TED-LIUM long-form, channel variants) and the harness.
3. The Pixel 8 steps (E0, E5) need the phone on adb, with Ultra installed in the app.
