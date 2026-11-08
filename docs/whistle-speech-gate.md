# Whistle speech gate

`WhistleSpeechGate` ports the desktop `SpeechGate` decisions from Mac
`Sources/CassiniTranscription/SpeechPipeline.swift`. It is a separate core stage;
the existing Parakeet/sherpa wrapper, job worker and Settings are unchanged.
Nothing in this increment makes Whistle available or the default.

The gate consumes complete 512-sample frames at 16 kHz. The caller supplies one
probability per frame from the track's high-pass and pinned stateful Silero model.
Two voiced votes in the last three frames activate speech, with up to ten frames
of pre-roll. Activation uses 0.5; active speech uses 0.35. It closes on 9600 quiet
samples, a pause of 2048 samples after 224000 samples, or 240000 samples, in that
priority order. Decisions occur at frame boundaries. The remaining partial frame
is not evaluated or zero-padded, but its real samples extend an active segment at
flush. An inactive gate with only one voiced frame produces no segment, as on the
desktop; this increment does not change that behavior or implement fallbacks.

Only one raw frame and three votes are retained. Pre-roll is represented by a
range in the caller's raw PCM scratch. The probability callback must not mutate
or retain its borrowed frame. Raw recognition audio must remain separate from
the filtered model input. Invalid/nonfinite probabilities or callback failures
abort the gate rather than becoming silent audio; a retry starts a new gate and
resets the matching model and high-pass state.

Unit tests exercise vote/threshold boundaries, pre-roll, all ending reasons,
reason priority, frame rounding, partial tails, chunk invariance, exact frame
order and failure handling. These are deterministic probability-input tests,
not native VAD execution or full-pipeline parity. The next gate remains actual
pinned Silero inference on the clean/DC/control fixtures, with exact desktop
segments and raw recognizer input hashes. No existing golden changes.
