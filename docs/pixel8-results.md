# Pixel 8 prototype results — 2026-10-01

**Both INT8 and FP32 ran successfully on the connected Pixel 8.** The tested recording is the requested [YouTube video](https://www.youtube.com/watch?v=UmZwQf5TV3c), cut from **00:28 to 00:58** with `uvx yt-dlp` and ffmpeg. The resulting input has exactly 480,000 mono PCM16 samples at 16 kHz (30.000 seconds).

The device reports Pixel 8, Android 17 / API 37, with 7,754,764 KiB total system RAM (nominal 8 GB). The debug APK uses stock sherpa-onnx 1.13.7, CPU provider, greedy decoding, 128-dimensional features and two inference threads.

| Precision | Model size | Model creation + recognition | Process peak RSS | Words |
| --- | ---: | ---: | ---: | ---: |
| INT8 | 639.4 MiB | 7.031 s | 1,067,988 KiB / 1.02 GiB | 87 |
| FP32 | 2.37 GiB | 19.399 s | 2,721,900 KiB / 2.60 GiB | 86 |

An earlier INT8 run took 6.754 seconds and produced the same text. The table uses the later instrumented run because it also measured peak RSS. Times exclude downloads and audio import but include recognizer construction, inference, result conversion and native cleanup. Peak RSS is the process's `/proc/self/status` `VmHWM`, measured after recognition; it includes the test process and shared mappings, and is not PSS or a measurement of total device memory pressure. These are short-clip runs, with no controlled cache, thermal or battery conditions.

FP32 and INT8 produced the same word sequence except for a comma after “galleggia” and a trailing “Il” in the INT8 result at the clip boundary. No human reference or word-error-rate evaluation has been completed for this excerpt. This comparison does not establish that FP32 is more accurate. On the separate FLEURS Italian fixture, the INT8 result contained lexical errors, including “Antarti” for “Antartide”; successful execution is distinct from recognition quality.

Initial prototype validation completed:

- Seven JVM tests covering Italian SentencePiece reconstruction, accented text/apostrophes, delayed punctuation, empty speech, speaker-turn order and invalid timing rejection.
- Three Android device tests passing with each precision: real WAV decoding, actual native recognition, and AAC/MP3/Opus duration/onset comparison against WAV. Compressed duration and onset tolerance is 100 ms, not a precise alignment certification.
- Both actual phone-generated transcript bodies validated against `../cassini-format/spec/cassini-words-v1.schema.json`.
- Manual UI check on the unlocked Pixel: selected the supplied WAV through Android's file picker, transcribed it in the activity (5.674 seconds in this later run), tapped a word to seek/start playback, paused playback, and verified the active word and search match highlights.
- Debug APK build and Android lint completed with no errors. Lint reports prototype warnings about localization, icon/backup configuration and storage-space API choice.

The generated bodies and metrics remain in the phone's private app storage and in local ignored `.tools/` artifacts. Reproduce the checks with the commands in [README.md](../README.md). Both model bundles remain installed; the supplied WAV is in the phone's Downloads directory as `cassini-italian-28-58.wav`.

Storage cleanup removed the two explicitly approved Gemma/Qwen downloads and requested Android cache trimming. About 5.5 GB was recovered. After retaining both Parakeet bundles, the phone reports approximately **4.5 GB free**. Photos, recordings, Maps data and the Linux Terminal environment were retained.

## Interface update (0.2)

English and Italian resources now cover controls, dialogs, progress, failures, accessibility descriptions and plurals. The native interface uses dark cassette-deck surfaces, amber controls, sentence paragraphs and a fixed playback deck. Switching language or reopening the activity restores the latest recording, transcript and playback position.

Validation on the same Pixel:

- Ten JVM tests pass, including transcript persistence and invalid saved timing rejection.
- Four Android tests pass with INT8 on the supplied excerpt, including English → Italian → English switching, session/playback restoration and search/clear-search.
- Manual checks in both languages confirm translated labels, localized decimal formatting, word-tap playback/highlighting and ±10-second skips. The actual activity transcribed the 30-second sample in 5.736 seconds; this is another uncontrolled short-clip measurement.
- Debug build and lint pass with no errors. Localization, launcher icon and backup configuration warnings from the initial prototype are addressed.

The updated APK is installed on the Pixel with both existing model bundles retained.

## Three-minute excerpt and paused seeking

The same video's **00:28–03:28** excerpt contains exactly 2,880,000 mono PCM16 samples at 16 kHz (180.000 seconds). It is available in the phone's Downloads directory as `cassini-italian-28-208.wav`. The import limit is now three minutes, with the existing 64 MB file-size bound. Recognition still runs on the whole clip with the stock frontend; no segmentation or overlap reconciliation has been introduced.

| Precision | Model creation + recognition | Process peak RSS | Words | Last word onset |
| --- | ---: | ---: | ---: | ---: |
| INT8 | 40.569 s | 2,556,964 KiB / 2.44 GiB | 492 | 179.520 s |

The phone-generated body validates against the published Cassini schema. Timing/memory definitions and uncontrolled conditions are the same as above. Reaching the final seconds and producing valid word timings verifies execution and coverage; no human reference or accuracy evaluation has been completed. FP32 has only been measured on the 30-second sample so far.

Explicit seeking now brings the highlighted word into view even while paused. The slider and ±10-second buttons follow the player’s completed seek, including a seek within the currently highlighted word. Restoring a paused session and ordinary manual reading do not trigger scrolling. The device regression check exercises these cases on a transcript longer than the viewport.

Six device tests and ten JVM tests pass, with a successful debug build and no lint errors. The actual app imported the three-minute file through Android's picker and transcribed it in 37.679 seconds (another uncontrolled run). Manual paused jumps between early and late passages show the highlighted word in view while the Play button remains paused. This recording and its 492-word transcript are left loaded on the Pixel with INT8 selected; both model bundles remain installed.

## Complete Cassini documents (0.3)

Cassini is now the persisted document. Recognition creates a sealed `.opus` file automatically; the native viewer opens it without a model or another ASR run. Save copies the full artifact, and activity/session restoration reads the selected body from that artifact rather than from a cached word JSON.

Validation on this Pixel:

- Fifteen JVM tests pass, including all 26 published conformance vectors, strict payload boundaries, variant/extension preservation, CRC failures and readable metadata after truncated audio.
- Ten distinct device checks pass across focused runs: the existing three audio/ASR tests, two interface tests, settings, and four portable-document tests. The latter cover actual Parakeet → document creation, native open/save/reopen, multiple variants/speaker labels, exact saved-byte preservation, native encoding speech onset/tail, and three-minute Opus EOS trimming at the ASR limit.
- The new 30-second activity-generated INT8 document contains 87 words and exactly 1,440,000 playable 48 kHz samples. The independent extractor and public manifest schema pass; the independent audio digest and shape match the embedded integrity record.
- The phone's existing three-minute FP32 session (495 words) was migrated without another recognition run. Its saved document has 8,640,000 playable samples / 180,000 ms and is 1,355,601 bytes. Manifest and word schemas, payload checksums, exact audio digest/shape and the sibling web viewer's actual portable reader/word adapter all pass.
- The three-minute sample is saved in Downloads as `cassini-italian-28-208.opus`, with its original WAV retained. Both model downloads remain installed. Encoding uses Android's platform Opus codec; no native codec library or external conversion service is needed on this Pixel.

The supplied audio and generated artifacts remain local ignored files. The updated APK restores the Italian three-minute document with FP32 selected. Display transcripts, annotations and other unsupported metadata survive unchanged saves but are not rendered yet; microphone capture, new diarization, background jobs and a document history browser remain future work.
