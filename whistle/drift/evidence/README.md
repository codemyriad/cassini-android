# Controlled dispatch/math results

**Partial matrix: API 26 is complete; API 35 and API 29 are still pending.**
The [table](TABLE.md) records strict comparisons with the unchanged #78 Mac
reference. Full returned words/times/probabilities are content-addressed under
`numerical/`; `runs.json` binds them to APK/engine hashes, actual kernel/image
metadata, feature masks, FPCR and wrapper counters. Raw performance reports stay
on the external disk; their hashes are retained. No product golden was changed.

On the API 26 guest (Linux 3.18.94+, emulator 37.2.13), `getauxval(AT_HWCAP)`
returned **0xff**, with bit 20 absent, while the independent SDOT instruction
probe executed and returned four exact lanes of 24. FPCR was 0.

- The unchanged-engine control reproduces #78 API 26's complete numerical word
  arrays. Transparent wrapping and forced-off/system math produce identical
  output to that control: 11/13 exact-word fixtures, maximum timing difference
  560 ms.
- Forced-on dispatch passes all 13 fixtures and the 80 ms bound, with both system
  and pinned musl math. It runs only after SDOT has executed successfully in the
  owned guest. This is a lab override, not a production capability policy.
- Pinned math alone does not recover the two word substitutions or the 560 ms
  alignment failure. It does change returned numerical values, so the evidence
  does not claim libm has no effect. Counters show the actual FFT/recognition
  math paths were exercised (including expf/logf and double exp/sincos).
- Forced-on/system math has the same word timestamps as #78's API 35 output,
  but probabilities still differ by up to 0.025. Exact word parity is the gate;
  probability equivalence is a diagnostic and is not claimed.

This supports CPU dispatch as the cause of the observed word/timing failures on
API 26. The cross-API forced-off/on controls and intermediate-OS run must finish
before the experiment draws its full conclusion. No minimum API or production
availability rule is established by this partial matrix. Actual hardware ISA
support and an OS-reported feature mask are distinct; these forced-on APKs must
not be shipped as a capability detector.

Every case passed six pre-dlopen privacy refusals and verified the identical
model/PCM pins. Tests run only in owned silent emulators under the shared lock
and memory/swap checks. The Android resampler/VAD and production app are outside
this experiment; no Included/default change follows from this data.
