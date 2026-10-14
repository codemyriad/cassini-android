export const meta = {
  name: 'preprocessing-experiments',
  description: 'Run the Ultra preprocessing experiment matrix (host + Pixel), adversarially verify, and recommend',
  phases: [
    { title: 'Prepare', detail: 'data sets and incremental-path harness' },
    { title: 'Parity', detail: 'E0: harness sanity, host vs Pixel, frontend on device' },
    { title: 'Experiments', detail: 'E1 padding, E2 unit length, then E3 channel/gain, E4 VAD' },
    { title: 'Pixel', detail: 'E5 finalists, memory, RTF' },
    { title: 'Verify', detail: 'skeptics per claim' },
    { title: 'Report', detail: 'recommendation' },
  ],
}

const S = '/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation'
const PLAN = args.plan

const BASE = `You are part of an investigation into how the Cassini Android app (/home/silvio/dev/cassini-android, now on main with Parakeet Ultra INT8 + sherpa-onnx dbbeef9c with the PR #4024 NeMo-reference frontend) should preprocess audio. Investigation workspace: ${S} (existing: eval/ host harness = eval/run.sh + eval/src/HostEval.kt, build-jni/ = host libsherpa-onnx-jni.so of dbbeef9c, models/ = Ultra INT8 + silero, prior/, data/). Do NOT edit files in the repository (/home/silvio/dev/cassini-android); copy code into ${S} when you need modified versions. Untrusted downloads go in fresh directories; read them with python3 -I. Host: 24 cores, ~37 GB RAM free, /tmp is a 30 GB tmpfs (it uses RAM — keep total data under ~8 GB; put bulk data in ${S}/data). Keep each decode single-stream (batching changes outputs). For anything longer than ~8 minutes run it with run_in_background and poll with Monitor/short checks; never block on one foreground command over 10 minutes. Record every command, pin and sha256 in a README/notes file in your output dir. Report measured numbers only; label anything unmeasured.

THE PLAN (from the understand phase):
${PLAN}`

const SCORE_SPEC = `Scoring spec (shared — implement exactly once in ${S}/eval/score.py, used by everyone): normalization NFKC, lowercase, fold curly apostrophes, strip punctuation, remove '.', ',' or space between digits; WER and CER with S/D/I counts; per-utterance (or per 30 s block for long-form) results in JSONL so paired bootstrap (1000 resamples, 95% CI, fixed seed) can compare any two conditions: 'python3 -I ${S}/eval/score.py compare A.jsonl B.jsonl'. Also boundary-deletion rate (deletions among first 2/last 2 ref words per utterance) and span-length-bucket WER when hypothesis timestamps + VAD spans are logged.`

phase('Prepare')
const [data, harness] = await parallel([
  () => agent(BASE + `\n\nTASK (data): Build the pinned datasets in ${S}/data with a manifest (path, duration, sha256, reference text, license):
- D1: google/fleurs@70bb2e84b976b7e960aa89f1c648e09c59f894dd, it_it test and en_us test (download only those files; HF may need no auth — check). Reference = raw transcription.
- D2: ~24 synthetic long-form files of ~5 min from D1 it_it test, utterances in id order, gaps drawn per file from {0.3,1,3} s filled with -60 dBFS pink noise; log true utterance boundaries; reference = joined texts.
- D3: distil-whisper/tedlium-long-form test split at a pinned revision (look it up; internal eval only, note license).
- D5: channel variants of a 300-utterance D1-it subset: 48 kHz upsample (for testing the app resampler at different cutoffs, the harness will resample), libopus 48 kb/s 20 ms and AAC 32 kb/s round-trips (decoded back to 16 kHz PCM WAV AND 48k for resampler path), attenuation -20/-30/-40 dB.
Also check ${S}/prior and ~/.claude/jobs/1f0c1c89/tmp/asr/ for D4 private meeting windows (do not copy private audio elsewhere; just list them in the manifest by path).
Write ${S}/data/MANIFEST.md and manifest.json. Final answer: summary of what exists, sizes, durations, anything missing.`, { label: 'prepare:data', phase: 'Prepare' }),
  () => agent(BASE + `\n\nTASK (harness): Extend the host harness so experiments run THE PRODUCTION PATH and variants of it, at scale:
1. Read eval/run.sh and eval/src/HostEval.kt, and the app code: core IncrementalTranscript, SeamMerge, DecodeSession, SpeechWindows/DecodePolicy, Resampler, WordGate; engine IncrementalRecognizer, Parakeet, ParakeetDecoder, SpeechFeed, SileroDetector, JobAdapters.kt.
2. Add a CLI mode that, for an input list (manifest JSON) and a condition spec (JSON), produces per-utterance JSONL {id, hyp, words with times, spans, empty-span count, retries, gated-vs-ungated hyp, wall time}. Condition parameters: pipeline=incremental|whole|span-direct; window/overlap seconds; DecodePolicy fields (tailPaddingMs, headPaddingMs, contextMs, syntheticPadding, preserveSpan); span merge (merge adjacent VAD spans keeping gap audio up to max unit seconds, max gap s); VAD threshold/minSilence/minSpeech/maxSpeech; resampler cutoff factor (for 48k input); word gate on/off (record both). Use scratch COPIES of locked classes for the parameterized versions; default parameters must reproduce production bit-for-bit (verify: production condition via your copies == production via the repo classes on 20 utterances).
3. A parallel runner: N worker JVMs (each 2 threads, as the app) over a manifest, with /usr/bin/time -v peak RSS per worker and resumable output.
4. ${SCORE_SPEC}
5. Guard: refuse any single decode > 300 s (5000-frame abort).
Smoke-test on fixtures/audio/fleurs-it-short.wav and 10 items. Document usage in ${S}/eval/USAGE.md. Final answer: usage, verification results, throughput (x realtime per worker) and RSS.`, { label: 'prepare:harness', phase: 'Prepare' }),
])

phase('Parity')
const parity = await agent(BASE + `\n\nData prep result:\n${data}\n\nHarness result:\n${harness}\n\nTASK (E0 parity gate):
1. Confirm on the Pixel 8 (adb serial 49101FDJH002K4; app org.cassini.android has Ultra installed in files/parakeet-ultra) that the reference frontend is active for the Ultra export: e.g. run a :bench or debug path that transcribes fleurs-it-short with the installed model and compare with host output, or check logcat/feature behaviour (an attenuated -60 dB copy of a clip transcribing non-empty is a strong signal; empty means stock frontend). Use scripts/device-smoke.sh --bench (read its usage; save files/chunking-comparison.INT8.json first into ${S}/prior if not already saved). Do not uninstall the app.
2. Host: production condition vs PARAKEET_V3_REFERENCE vs WHOLE on fleurs-it-short + 30 D1-it utterances; same on the Pixel if a bench path allows it (you may write a scratch bench test in a git worktree / copy, not in the main checkout — or push audio and use an existing bench test).
3. Report host vs Pixel agreement (normalized WER between them, casing diffs), and whether the harness is trustworthy. Output dir ${S}/out/E0.`, { label: 'E0:parity', phase: 'Parity' })

phase('Experiments')
const [e1, e2] = await parallel([
  () => agent(BASE + `\n\nHarness:\n${harness}\nData:\n${data}\nParity:\n${parity}\n\nTASK (E1 padding, H2/H4): Use at most 10 workers (another experiment runs concurrently with 10). Conditions, all SPEECH cutting through the production incremental path: tail 500 (production WHOLE_SPANS), tail 250, tail 100, tail 0 (syntheticPadding true), ctx30 no padding (PARAKEET_V3_REFERENCE), ctx100 no padding, WHOLE cutting (no VAD). Data D1-it and D1-en (all utterances). Score with score.py: WER/CER, S/D/I, boundary deletions, span-length buckets, empty spans and retries, paired bootstrap vs production. Output ${S}/out/E1 with a RESULTS.md table. Final answer: the table and which differences have CIs excluding 0.`, { label: 'E1:padding', phase: 'Experiments' }),
  () => agent(BASE + `\n\nHarness:\n${harness}\nData:\n${data}\nParity:\n${parity}\n\nTASK (E2 unit length, H1/H3): Use at most 10 workers (E1 runs concurrently). Conditions: (a) 28/2 + WHOLE_SPANS (production), (b) 28/2 + REFERENCE, (c) VAD spans merged to <=30 s keeping gaps <=1 s, REFERENCE, (d) 60/4 windows + (c), (e) 120/4 windows + (c), (f) WHOLE <=300 s units as an upper reference. Data: D2 (all), D3 (all, English), D4 if available (relative only — references are v3 output). Metrics: WER (30 s blocks for bootstrap), seam/unit-edge errors within ±2 s, RTF and peak RSS per condition. Output ${S}/out/E2 with RESULTS.md. Final answer: table, CIs, memory.`, { label: 'E2:units', phase: 'Experiments' }),
])

const [e3, e4] = await parallel([
  () => agent(BASE + `\n\nE1:\n${e1}\n\nE2:\n${e2}\n\nTASK (E3 channel/gain, H5/H6/H8): pick the best overall condition from E1/E2 (state why) plus production. On D5 variants: 48k input through the app resampler at cutoff 0.4375 (production) vs 0.475 vs 0.49; Opus 48k and AAC 32k round-trips vs clean; attenuation -20/-30/-40 dB. Score with the word gate ON and OFF to separate ASR from gate effects. 10 workers max. Output ${S}/out/E3/RESULTS.md. Final answer: table + CIs + which effects are real.`, { label: 'E3:channel-gain', phase: 'Experiments' }),
  () => agent(BASE + `\n\nE1:\n${e1}\n\nE2:\n${e2}\n\nTASK (E4 VAD sweep, H7): with the best unit/padding condition from E1/E2, sweep Silero threshold {0.18,0.3,0.5} x min silence {0.25,0.5,1.0} on D2 (and D3 if time allows). 10 workers max. Metrics: WER, deletions, inserted words in gaps (D2 has pink-noise gaps: hallucinations there), span counts. Output ${S}/out/E4/RESULTS.md. Final answer: table + CIs.`, { label: 'E4:vad', phase: 'Experiments' }),
])

phase('Pixel')
const e5 = await agent(BASE + `\n\nResults so far:\nE0:${parity}\nE1:${e1}\nE2:${e2}\nE3:${e3}\nE4:${e4}\n\nTASK (E5 on the Pixel 8, serial 49101FDJH002K4): confirm the finalists (production vs the best 1-2 candidate conditions) on device: transcribe a 60-utterance D1-it subset and two D2 files through the app's code on device (scratch bench test in a separate git worktree of the repo, e.g. git worktree add ${S}/wt; build and run with scripts/device-smoke.sh --bench from that worktree; never uninstall the app; keep its data). Measure per-decode peak memory (VmHWM / PSS) and RTF for decode units of 28, 60, 120 s, and thermal behavior over a sustained ~20-30 min run. Compare device WER deltas with host deltas. Output ${S}/out/E5/RESULTS.md. Clean up the worktree when done (git worktree remove).`, { label: 'E5:pixel', phase: 'Pixel' })

phase('Verify')
const CLAIMS = { type: 'object', properties: { claims: { type: 'array', items: { type: 'object', properties: { claim: { type: 'string' }, evidence: { type: 'string' } }, required: ['claim', 'evidence'] } } }, required: ['claims'] }
const extracted = await agent(BASE + `\n\nResults:\nE0:${parity}\nE1:${e1}\nE2:${e2}\nE3:${e3}\nE4:${e4}\nE5:${e5}\n\nTASK: Extract every decision-relevant claim (e.g. "REFERENCE padding beats WHOLE_SPANS by X WER on D1-it", "30 s merged units cut seam errors", "resampler cutoff 0.49 helps", "120 s units fit in memory on the Pixel"), each with the evidence files/numbers. At most 12 claims, most decision-relevant first.`, { label: 'verify:extract', phase: 'Verify', schema: CLAIMS })

const VERDICT = { type: 'object', properties: { refuted: { type: 'boolean' }, reason: { type: 'string' }, corrected: { type: 'string' } }, required: ['refuted', 'reason'] }
const verified = await parallel((extracted?.claims ?? []).map((c, i) => () =>
  parallel(['statistics', 'methodology', 'reproduce'].map(lens => () => agent(BASE + `\n\nCLAIM: ${c.claim}\nEVIDENCE: ${c.evidence}\n\nYou are a skeptic using the ${lens} lens. statistics: recompute from the JSONL outputs with score.py — CI, sample size, multiple comparisons, pseudo-reference bias. methodology: was the production path really used, are conditions confounded (e.g. arm64 vs x86, normalization artefacts, batch), does the data represent real recordings. reproduce: re-run a small slice (e.g. 30 items) of both conditions and check the direction holds. Default to refuted=true if the evidence does not support the claim as stated; give a corrected statement if partially true.`, { label: `verify:${i}:${lens}`, phase: 'Verify', schema: VERDICT })))
    .then(vs => ({ ...c, verdicts: vs.filter(Boolean), survives: vs.filter(Boolean).filter(v => !v.refuted).length >= 2 }))))

phase('Report')
const report = await agent(BASE + `\n\nAll results:\nE0:${parity}\nE1:${e1}\nE2:${e2}\nE3:${e3}\nE4:${e4}\nE5:${e5}\n\nVerified claims (3 skeptics each; survives = >=2 not refuted):\n${JSON.stringify(verified, null, 2)}\n\nTASK: Write ${S}/REPORT.md: (1) recommendation for the app's preprocessing (unit length/windowing, span merging, padding policy, VAD settings, resampler, gain/word gate, decode cap) — only from surviving claims; (2) the evidence table with CIs; (3) what was refuted or inconclusive; (4) concrete code change list for the repo (files, which verbatim-check groups need moving to described-edit rows, docs to update); (5) open questions. Final answer: the full report text.`, { label: 'report', phase: 'Report' })

return { report, verified, parity, e1, e2, e3, e4, e5 }
