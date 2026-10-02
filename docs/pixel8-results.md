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

## Resource preference and realtime speed (2026-10-01)

New installs choose Automatic, favoring FP32 when memory/storage headroom permits and respecting explicit INT8/FP32 overrides. Existing choices migrate as manual choices. This is a resource heuristic; no labeled Italian evaluation establishes that FP32 improves accuracy over INT8 on this recording. The stock frontend still differs from the desktop Cassini fork.

## Notes library and microphone recording

The speech-notes reference now guides a native Notes home screen with date groups, duration, transcript previews, full selected-transcript search and recording/import actions. On this Pixel, first-launch recovery found eight retained meetings across twelve portable files. The current three-minute document, selected variant and 7.063-second playback position were preserved. Retranscription updates one stable library entry while keeping earlier portable revisions.

All **13 focused Android checks** passed on the Pixel, including real microphone pause/resume, AAC decoding/playback after saving, stopping/saving on foreground exit, automatic microphone → INT8 Parakeet → verified portable document creation, search-result navigation, library recovery/corruption protection, language/model switching and the existing portable-document checks. All **20 JVM tests**, debug assembly and lint passed; lint has existing warnings and no errors. Test fixtures are removed from the library and original recordings/model downloads remain installed.

Capture uses mono 48 kHz AAC and automatically stops at 2:59 to leave room for encoder padding within the three-minute decoder limit. It finalizes audio before recognition and retains it when recognition or packaging fails. Leaving the foreground saves audio without starting background inference. This remains the Android whole-utterance, stock-frontend CPU pipeline; it does not adopt the desktop fork or provide long-recording segmentation, background recording/transcription, flags or typed annotations. Cassini saving still produces a complete portable Opus document, rather than a standalone word-body JSON export.

The three-minute FP32 document restores its previously measured 90.159-second recognition time and displays `2.00× realtime` in Italian. New recognition records `x-inferenceMs` in the variant's speech-to-text provenance. Completion separately measures the whole decode/recognize/package operation; higher multipliers are faster.

Verification: 17 JVM tests and assemble/lint pass. Device settings tests select Automatic, then FP32, switch language and return with the manual preference retained; both interface/playback tests pass. A separate actual 30-second INT8 recognition test passes, creates a verified Cassini document with processing time in its provenance, and checks the completion multiplier. The existing three-minute document and both model downloads remain intact.

## Progress during transcription, and cutting at speech pauses (2026-10-02)

Recognition used to be one call for the whole clip, so nothing could be shown until it returned. The app now cuts the recording at speech pauses and decodes the spans one after another (see [feasibility](feasibility.md)). The note shows a progress bar, the pace as a realtime multiplier, the time left and the words decoded so far. The pace during transcription covers decoding only; the figure shown at completion still includes model loading, audio decoding and packaging.

Cutting changes what the recognizer hears, so each way of cutting was compared with one decode of the whole recording, on this Pixel 8, with the clips above plus the FLEURS sample. “Errors” are word-level edit distances after lowercasing, folding the curly apostrophe, removing digit grouping (“4.892”) and splitting on punctuation. FLEURS has a human reference text (27 words by this count). The three-minute excerpt has none: it is scored against YouTube’s automatic captions for the same video (493 words), which are another recognizer’s output and contain at least three errors of their own. These are single runs at uncontrolled temperature. The words were identical across three INT8 sessions; the times were not.

INT8, three-minute excerpt (492 to 498 words in every mode):

| Cutting | Time, three sessions | Differs from whole | vs. YouTube captions | First words, after the model has loaded |
| --- | ---: | ---: | ---: | ---: |
| Whole recording (previous behaviour) | 44.5, 52.6, 40.5 s | 0 | 14 | end |
| Fixed 15 s windows, 0.5 s overlap (desktop without detector) | 30.6, 26.9, 25.7 s | 14 | 12 | 1.6–2.0 s |
| Quiet-point cuts, no detector (the app’s fallback, before cuts had recorded context) | 29.6 s | 11 | 13 | 2.8 s |
| Speech spans in 10 s windows, 0.5 s tail (desktop policy on a stock runtime) | 35.3, 30.2, 31.3 s | 14 | 13 | 1.9–2.7 s |
| Speech spans whole, no tail | 26.8, 28.9, 26.9 s | 7 | 10 | 3.5–3.6 s |
| Speech spans whole, 30 ms recorded context, no tail (desktop policy on its patched runtime) | 28.6, 29.9, 27.2 s | 9 | 11 | 3.4–3.9 s |
| **Speech spans whole, 0.5 s tail (what the app uses)** | 30.2, 32.1 s | 9 | 8 | 3.9–4.2 s |

FLEURS sample (15.8 s), errors against the human reference, INT8, for the `.wav` and the `.m4a` of the same recording: whole recording 5 and 5; fixed windows 3 and 3; quiet-point cuts 3 and 4; 10 s windows with tail 1 and 1; whole spans with tail 2 and 2; whole spans without tail 4 and 1; whole spans with context and no tail 5 and 3. Three of the four runs without the tail read the number “4892” as Spanish-sounding words (“quattro ocho noventadu”, “quattro noventa”).

What the differences were on the three-minute clip:

* The whole-recording decode made errors that the app’s policy avoided: “cronomet” for “cronometro”, “al'esterno”, “idrorrepellente”, and two dropped short words. Most other cut versions avoided them too.
* 10 s windows cut words in half. Four seams kept both halves (“cronometro. Cronometro”, “sofisticale. Sofisticato”, “l'albume Bume è. è”, “la. La”). The ported seam alignment removes a repeated word when both copies read the same and start within 200 ms of each other, or within 400 ms as part of a matching run; these copies were 220 to 300 ms apart, or read differently because one was truncated.
* Final words were truncated (“succed” for “succedere”, “l'u” for “l'uovo”) with whole spans plus recorded context and no tail, and with 10 s windows. Whole spans without tail and without context truncated none on this clip.
* Whole spans with the tail had neither duplicates nor truncations. Its remaining differences from the captions were single-word readings (“fin” for “film”, “aliento” for “alimento”).

FP32, three-minute excerpt, one earlier session without the quiet-point modes: whole recording 115.7 s and 10 differences from the captions; fixed windows 85.2 s and 11; 10 s windows 92.8 s and 11; whole spans with tail 93.4 s and 12. On FLEURS the errors were 1 for the whole recording and for fixed windows, 2 to 3 for 10 s windows, and 2 for whole spans with tail. On the 30 s clip whole spans with tail differed from the whole decode by 3 words, fixed windows by none. So with FP32, which Automatic selects on this phone, cutting did not improve the words and whole spans were one or two words worse in these runs. With FP32 the first words appeared about 15 s after the model had loaded on both the 30 s and the three-minute clip, against 3 to 4 s with INT8. Loading the model comes before that and is not included.

Speed: on the three-minute clip, cut decoding took 26 to 32 s against 40.5 to 52.6 s for the whole recording in these INT8 sessions (37.7 and 40.6 s in the earlier sessions above), and 93.4 s against 115.7 s for FP32 here, where an earlier whole run took 90.2 s, so no FP32 speed-up is shown. On the 30 s clip the app’s policy was slower than the whole decode: 5.5 to 7.7 s against 5.2 to 6.5 s for INT8, and 25.2 s against 18.6 s for FP32. Peak memory was not measured per mode.

Why whole spans with the tail: it avoided the failure modes seen with the alternatives, duplicated words at 10 s seams and the misread number and truncations seen in some runs without the tail, and its word counts are within the noise of the other modes. With INT8 it scored best against the captions on the three-minute clip and one error behind the best on FLEURS. This is four clips of clean, read or scripted speech from single speakers. It does not establish accuracy on conversational or noisy recordings, and the detector threshold comes from the desktop’s meeting tracks, not from phone microphones.

The detector’s maximum span is not a cap in sherpa-onnx 1.13.7: with the same version and settings on a desktop, the three-minute clip gave seven spans of 19 to 27.7 s, and noisy input gave spans of minutes. The app therefore cuts a span longer than 28 s at its quietest point between 20 and 25 s after the previous cut. None of these clips has such a span, so that rule changed nothing in the detector rows above; it is exercised only by the fallback row, where the whole recording is one span. In that row a cut at 133.58 s fell inside “batterica” and the word came out as “batteria”. Each side of a cut is now decoded with 1 s of the recording past it, and the two decodes are joined at a word they agree on. That change was made after the phone had locked, so it was checked on an Android 14 x86_64 emulator with INT8: the fallback kept “batterica” and differed from the captions by 10 words, the same as whole spans with tail there, against 18 for the whole recording and 15 for fixed windows. Emulator and phone decode slightly differently, so those counts are not comparable with the table.

Verification of the final code: 100 JVM tests pass, including the desktop’s window, seam and gate tests ported with the code, and new tests for the detector loop, the quiet-point cuts and the join at a cut. On the Pixel, 22 device checks passed with INT8 before the last round of changes (the detector on 16 kHz and 48 kHz input, measured progress and interim words during a real transcription, record → transcribe → document); `ChunkingComparisonTest` runs only when asked for and produced the tables above. After those changes the phone was locked, so the same 22 checks, and the comparison, were run with the INT8 model on an Android 14 x86_64 emulator instead.
