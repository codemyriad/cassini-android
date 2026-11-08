# Controlled dispatch/math results

All 21 cases completed on owned arm64 API 26, 29 and 35 emulators. The
[table](TABLE.md) records strict comparisons with the unchanged #78 pinned Mac
reference: 13 inputs and 104 words. No product golden, timing tolerance, engine
archive or model pin changed. Full returned words/times/probabilities are
content-addressed under `numerical/`; `runs.json` binds them to APK/engine hashes,
actual kernel/image metadata, feature masks, FPCR and wrapper counters. Raw
reports stay on the external disk; their hashes are retained.

## Dispatch is causal for these word/timing failures

The API 26 guest (Linux 3.18.94+) reports HWCAP **0xff**, without bit 20
(`HWCAP_ASIMDDP`). The API 29 guest (Linux 4.14.175) reports **0xfb3ffff**;
API 35 (Linux 6.6.30) reports **0xefb3ffff**. Both newer guests include bit 20.
All use emulator 37.2.13. A separate process successfully executes SDOT and
returns four exact lanes of 24 on every guest. All wrapped cases report FPCR 0.

- API 26 unchanged-engine, transparent-system and forced-off/system outputs are
  identical, reproducing #78's complete returned numerical arrays: 11/13 exact
  word fixtures, maximum boundary difference 560 ms.
- API 26 forced-on dispatch passes all 13 word fixtures and the 80 ms boundary
  bound with either math implementation.
- API 29 and API 35 unchanged engines pass all 13 fixtures and the 80 ms bound.
  Their complete returned numerical arrays are identical. Transparent and
  forced-on/system controls reproduce those arrays.
- Forcing dispatch off on API 29 and API 35 produces identical failures on both:
  10/13 word fixtures with system math, maximum boundary difference 560 ms.
  The older system math gives 11/13 on API 26; the failures are not all identical.

Changing only HWCAP bit 20 changes the outcome in both directions. This supports
the Needle dot-product dispatch path as the cause of the observed API 26 parity
failure, rather than an inability of that guest CPU to execute the instructions.
The override leaves every other capability real. It is a lab intervention, not
a production feature detector; forced-on variants must not be shipped as one.

## System math explains the remaining cross-API numerical drift

With dispatch held fixed, private pinned musl 1.2.5 math produces identical
returned text/language/word/time/probability arrays across API 26, 29 and 35:

- Dot-product on: `5e068186b323` (13/13 exact word fixtures, within 80 ms).
- Dot-product off: `06efba6460cf` (11/13 fixtures, maximum 560 ms; still failing).

These are hashes of the recorded JSON payloads, not a claim of identical internal
floating-point state or equality to desktop probabilities. System math with
dot-product on passes the word/timing contract on every API, but API 26 word
probabilities differ from API 29/35 by up to 0.025. The unchanged system-math
API 29 and 35 payloads are identical (`81ccaea9c3cf`).

Pinned math alone does not repair the legacy path. On API 26 it fixes `Your`
versus `our`, retains `American.` versus `Americans.`, and introduces `our`
versus `Our` in control segment 4. Math affects decoding as well as confidence.
Counters confirm actual recognition/FFT math execution, including expf/logf,
powf, sincosf, double exp/log10/sin/cos/sincos. Unused wrapped roots remain zero.
The build verifies private static symbol closure for all twelve direct imported
math roots; it does not substitute a different Needle archive.

## Acceptance boundary

Exact desktop word parity is demonstrated for these frozen raw-PCM inputs on
API 29/35 with the unchanged runtime, and on API 26 with proven SDOT support and
the lab dispatch override. API 29 is the lowest tested passing unmodified guest;
API 27/28 and arm64 CPUs without dot-product support were not tested. This does
not establish API 29 as a universal minimum or API 26 as production-eligible.
An API floor alone cannot guarantee this CPU feature. A production capability
policy remains a separate decision; Included/default stays unchanged.

Every case passed six pre-dlopen privacy refusals and verified identical
model/PCM pins. Negative comparisons remain failures, including nonzero runner
exits; completing a controlled experiment does not turn them into acceptance.
All guests ran silently under the shared lock and memory/swap checks, and were
stopped afterward. The Android resampler/VAD, full worker pipeline, production
app integration, hardware coverage and memory/size acceptance remain outside
this experiment.
