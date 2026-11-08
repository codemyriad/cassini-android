# Whistle job recognizer adapter

`WhistleRecognizers` binds the isolated worker to the existing `RecognizerFactory`
seam. It accepts raw 16 kHz PCM after the pinned desktop FIR. It does not use the
VAD-only high-pass samples for recognition, speaker assignment or stored audio.
The speaker callback receives raw segment audio and segment-relative words; the
adapter then places the assigned words once on the recording clock. It trims
surrounding word whitespace, preserves word order and reports language `und`
because this worker API does not report a detected language.

The adapter retains a fixed 262144-sample raw ring. Commands remain bounded by
the existing worker protocol. Worker/process errors abort this instance; they
never open Parakeet or fetch a model.

## Resume

Needle and the Silero worker do not expose a native-state checkpoint API. A
Whistle checkpoint therefore stores a typed pipeline marker, the accepted
sample position, a SHA-256 of that exact PCM prefix, settled words and whether
the true EOF was flushed. The `chunks.window` is empty. A Parakeet adapter rejects
this marker, and the Whistle adapter rejects untagged or incompatible state
before opening a worker.

The durable job must check its source/model/pipeline identity first. It then
supplies a `WhistleReplay` that reproduces exactly the saved number of raw
16 kHz samples from the track origin, using the same decoder/FIR policy. The
adapter rebuilds the VAD, filter, gate and recognizer state in a fresh isolated
worker. It suppresses speaker and progress callbacks during replay and compares
every word/time plus the complete PCM-prefix hash. A changed source, changed
result or short/overlong replay fails and closes the worker before new input.
For a checkpoint made after finish, replay also flushes the true EOF once.

Replay keeps memory bounded, but its time grows with the saved prefix and it
repeats native inference. This is a deliberate correctness tradeoff, not a
native-state serialization claim. Inference timing excludes time in the caller's
speaker/progress callbacks. Cleanup retains the original failure.

## Integration boundary

This prerequisite does not select Whistle for production jobs or change Settings.
The Included/default change must also persist engine/pipeline pins per job,
select the decoder's FIR policy, bind checkpoint identity and provenance to that
choice, and separate basic transcription from optional model downloads. It must
retain capability and verified-asset refusal. Current Parakeet output and its
untagged checkpoint JSON remain unchanged.
