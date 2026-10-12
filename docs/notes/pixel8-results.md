# Pixel 8 prototype results — 2026-10-01

> Historical note: this file records work on the 0.1.0 prototype, written from 2026-10-01 to 2026-10-09. Nobody updates it. Its statements about the app can be out of date. For the current app, read [the architecture](../architecture.md) and [the development notes](../development.md). Until 0.2, this file was `docs/pixel8-results.md`.

A KDoc comment in `core/src/main/kotlin/org/cassini/core/asr/SpeechWindows.kt` still gives the old path `docs/pixel8-results.md`, and `config/detekt/baseline.xml` repeats that comment. `scripts/check-verbatim.sh` has this file in a "package only" group, so the comment keeps the old path. A change of the comment needs a reviewed verbatim edit of the three files.

The measurements in this file are for earlier versions, with FP32 and with the Automatic model selection. The current app uses only INT8 and transcribes after the recording. The historical FP32 results and the saved documents stay valid.

**INT8 and FP32 both operated correctly on the connected Pixel 8.** The test recording is the requested [YouTube video](https://www.youtube.com/watch?v=UmZwQf5TV3c), cut from **00:28 to 00:58** with `uvx yt-dlp` and ffmpeg. The input has exactly 480,000 mono PCM16 samples at 16 kHz (30.000 seconds).

The device reports Pixel 8, Android 17 / API 37, with 7,754,764 KiB of total system RAM (nominal 8 GB). The debug APK uses stock sherpa-onnx 1.13.7, the CPU provider, greedy decoding, 128-dimensional features and two inference threads.

| Precision | Model size | Model creation + recognition | Process peak RSS | Words |
| --- | ---: | ---: | ---: | ---: |
| INT8 | 639.4 MiB | 7.031 s | 1,067,988 KiB / 1.02 GiB | 87 |
| FP32 | 2.37 GiB | 19.399 s | 2,721,900 KiB / 2.60 GiB | 86 |

An earlier INT8 run took 6.754 seconds and gave the same text. The table uses the later run with instrumentation, because it also measured the peak RSS. The times do not include the downloads and the audio import. They include the construction of the recognizer, the inference, the result conversion and the native cleanup.

The peak RSS is `VmHWM` in `/proc/self/status` of the process, measured after the recognition. It includes the test process and the shared mappings. It is not PSS, and it is not a measurement of the total memory pressure of the device. These runs used short clips, with no control of the cache, the temperature or the battery.

FP32 and INT8 gave the same word sequence, with two differences in the INT8 result: a comma after “galleggia”, and a last “Il” at the end of the clip. Nobody did a human reference or a word-error-rate evaluation for this excerpt. Thus this comparison does not show that FP32 is more accurate. On the separate FLEURS Italian fixture, the INT8 result had lexical errors, for example “Antarti” for “Antartide”. A run without errors is not the same as good recognition quality.

The first validation of the prototype had these results:

- Seven JVM tests: the reconstruction of Italian SentencePiece tokens, accented text and apostrophes, late punctuation, empty speech, the speaker-turn order, and the refusal of invalid times.
- Three Android device tests passed with each precision: the decoding of a real WAV, a real native recognition, and a comparison of the duration and onset of AAC/MP3/Opus with WAV. The tolerance for the duration and the onset of compressed audio is 100 ms. This is not a precise alignment certification.
- The two transcript bodies that the phone made passed the schema `../cassini-format/spec/cassini-words-v1.schema.json`.
- A check of the UI by hand on the unlocked Pixel: we selected the supplied WAV in the file picker of Android and transcribed it in the activity (5.674 seconds in this later run). We tapped a word to seek and start the playback, and we paused the playback. We examined the highlights of the active word and of the search matches.
- The debug APK build and Android lint completed with no errors. Lint gave warnings for the prototype about localization, the icon and backup configuration, and the selection of the storage-space API.

The generated bodies and the metrics stay in the private app storage of the phone and in local `.tools/` files that git ignores. To do the checks again, use the commands in the README of that time (`git show v0.1.0:README.md`). The two model bundles stay installed. The supplied WAV is in the Downloads directory of the phone as `cassini-italian-28-58.wav`.

The storage cleanup removed the two Gemma/Qwen downloads that the owner approved, and it asked Android to trim the caches. This gave back about 5.5 GB. With the two Parakeet bundles, the phone has approximately **4.5 GB free**. The photos, the recordings, the Maps data and the Linux Terminal environment stayed.

## Interface update (0.2)

The English and Italian resources cover the controls, the dialogs, the progress, the failures, the accessibility descriptions and the plurals. The native interface uses dark surfaces in the style of a cassette deck, amber controls, sentence paragraphs and a fixed playback deck. After a change of language or a new start of the activity, the app shows the latest recording, transcript and playback position again.

Validation on the same Pixel:

- Ten JVM tests pass, with the persistence of the transcript and the refusal of invalid saved times.
- Four Android tests pass with INT8 on the supplied excerpt. They include a change English → Italian → English, the restoration of the session and the playback, and search and clear-search.
- Checks by hand in the two languages show the translated labels, the localized decimal format, the playback and highlight after a word tap, and the ±10-second skips. The activity transcribed the 30-second sample in 5.736 seconds. This is one more short-clip measurement without controlled conditions.
- The debug build and lint pass with no errors. We corrected the warnings of the first prototype about localization, the launcher icon and the backup configuration.

The updated APK is installed on the Pixel, and the two model bundles stay.

## Three-minute excerpt and seeking during a pause

The **00:28–03:28** excerpt of the same video has exactly 2,880,000 mono PCM16 samples at 16 kHz (180.000 seconds). It is in the Downloads directory of the phone as `cassini-italian-28-208.wav`. The import limit is three minutes, with the existing size limit of 64 MB for a file. The recognition still operates on the full clip with the stock frontend. There is no segmentation and no reconciliation of overlaps.

| Precision | Model creation + recognition | Process peak RSS | Words | Last word onset |
| --- | ---: | ---: | ---: | ---: |
| INT8 | 40.569 s | 2,556,964 KiB / 2.44 GiB | 492 | 179.520 s |

The body that the phone made passes the published Cassini schema. The definitions of time and memory, and the conditions without control, are the same as in the first section. The recognition got to the last seconds and gave valid word times. This shows the execution and the coverage. Nobody did a human reference or an accuracy evaluation. Until this time, we measured FP32 only on the 30-second sample.

An explicit seek brings the highlighted word into view, also during a pause. The slider and the ±10-second buttons follow the completed seek of the player, also for a seek inside the highlighted word. The restoration of a paused session and the usual reading by hand do not cause a scroll. The regression check on the device does these cases on a transcript that is longer than the viewport.

Six device tests and ten JVM tests pass, with a debug build and no lint errors. The app imported the three-minute file through the picker of Android and transcribed it in 37.679 seconds (one more run without controlled conditions). Jumps by hand between early and late passages during a pause show the highlighted word in view, and the Play button stays paused. This recording and its transcript of 492 words stay on the Pixel with INT8 selected. The two model bundles stay installed.

## Complete Cassini documents (0.3)

The Cassini document is the persisted record. The recognition makes a sealed `.opus` file automatically. The native viewer opens it without a model or a new ASR run. Save copies the full artifact. The restoration of the activity and of the session reads the selected body from that artifact, not from a cached word JSON.

Validation on this Pixel:

- Fifteen JVM tests pass. They include all 26 published conformance vectors, strict payload boundaries, the preservation of variants and extensions, CRC failures, and readable metadata after truncated audio.
- Ten different device checks pass in focused runs: the three existing audio/ASR tests, two interface tests, settings, and four portable-document tests. The portable-document tests cover these items: a real Parakeet → document creation, native open/save/reopen, more than one variant and speaker labels, and the exact preservation of the saved bytes. They also cover the speech onset and tail of the native encode, and the Opus EOS trim of three minutes at the ASR limit.
- The new 30-second INT8 document from the activity has 87 words and exactly 1,440,000 playable 48 kHz samples. The independent extractor and the public manifest schema pass. The independent audio digest and the shape agree with the embedded integrity record.
- The existing three-minute FP32 session of the phone (495 words) moved to the new format without a new recognition run. Its saved document has 8,640,000 playable samples / 180,000 ms and is 1,355,601 bytes. The manifest and word schemas, the payload checksums, the exact audio digest and shape, and the real portable reader and word adapter of the sibling web viewer all pass.
- The three-minute sample is in Downloads as `cassini-italian-28-208.opus`, and its original WAV stays. The two model downloads stay installed. The encode uses the platform Opus codec of Android. This Pixel needs no native codec library and no external conversion service.

The supplied audio and the generated artifacts stay local, in files that git ignores. The updated APK opens the Italian three-minute document again with FP32 selected. Display transcripts, annotations and other metadata that the app does not support stay unchanged after a save, but the app does not show them yet. Microphone capture, new diarization, background jobs and a browser for the document history are future work.

## Resource preference and realtime speed (2026-10-01)

New installations select Automatic. Automatic selects FP32 when there is sufficient memory and storage, and it obeys an explicit INT8 or FP32 selection. Existing selections become manual selections. This is a heuristic for resources. No labeled Italian evaluation shows that FP32 is more accurate than INT8 on this recording. The stock frontend is still different from the desktop Cassini fork.

## Notes library and microphone recording

The speech-notes reference is the guide for a native Notes home screen with date groups, duration, transcript previews, a full search of the selected transcript, and the actions for recording and import. On this Pixel, the recovery at the first launch found eight meetings in twelve portable files. The current three-minute document, the selected variant and the playback position of 7.063 seconds stayed. A new transcription updates one stable library entry and keeps the earlier portable revisions.

All **13 focused Android checks** passed on the Pixel. They include these checks:

- A real microphone pause and resume.
- AAC decoding and playback after a save.
- A stop and save when the app leaves the foreground.
- Automatic microphone → INT8 Parakeet → verified portable document creation.
- Navigation from a search result.
- Library recovery and protection against damage.
- Language and model changes.
- The existing portable-document checks.
 All **20 JVM tests**, the debug assembly and lint passed. Lint has existing warnings and no errors. We removed the test fixtures from the library, and the original recordings and model downloads stay installed.

The capture uses mono 48 kHz AAC. It stops automatically at 2:59, so that there is space for the encoder padding inside the three-minute limit of the decoder. It finalizes the audio before the recognition, and it keeps the audio when the recognition or the packaging fails. When the app leaves the foreground, it saves the audio and does not start an inference in the background.

This is still the Android pipeline on the CPU, with the full utterance and the stock frontend. It does not use the desktop fork. It does not give segmentation of long recordings, background recording or transcription, flags or typed annotations. A Cassini save still makes a complete portable Opus document, not a standalone word-body JSON export.

The three-minute FP32 document shows its earlier recognition time of 90.159 seconds again and shows `2.00× realtime` in Italian. A new recognition records `x-inferenceMs` in the speech-to-text provenance of the variant. The completion figure measures the full decode, recognition and packaging separately. A higher multiplier is faster.

Verification: 17 JVM tests, assemble and lint pass. The device settings tests select Automatic, then FP32, change the language and come back with the manual selection. The two interface and playback tests pass. A separate real 30-second INT8 recognition test passes. It makes a verified Cassini document with the processing time in its provenance, and it examines the completion multiplier. The existing three-minute document and the two model downloads did not change.

## Progress during transcription, and cuts at speech pauses (2026-10-02)

The recognition was one call for the full clip, so the app showed nothing until the call returned. At this time, the app cuts the recording at speech pauses and decodes the spans one after the other (see [feasibility](feasibility.md)). The note shows a progress bar, the pace as a realtime multiplier, the time left and the words that are decoded. During the transcription, the pace covers only the decode. The figure at the end still includes the model load, the audio decode and the packaging.

The cuts change what the recognizer hears. Thus we compared each method of cuts with one decode of the full recording, on this Pixel 8. We used the clips of the earlier sections and the FLEURS sample. “Errors” are word-level edit distances after these steps: lowercase, change of the curly apostrophe, removal of the digit groups (“4.892”), and a split at punctuation.

FLEURS has a human reference text (27 words by this count). The three-minute excerpt has none. We compared it with the automatic captions of YouTube for the same video (493 words). These captions are the output of a different recognizer, and they have at least three errors. These are single runs at a temperature without control. The words were the same in three INT8 sessions, but the times were not.

INT8, three-minute excerpt (492 to 498 words in each mode):

| Cutting | Time, three sessions | Differs from whole | vs. YouTube captions | First words, after the model has loaded |
| --- | ---: | ---: | ---: | ---: |
| Whole recording (previous behavior) | 44.5, 52.6, 40.5 s | 0 | 14 | end |
| Fixed 15 s windows, 0.5 s overlap (desktop without detector) | 30.6, 26.9, 25.7 s | 14 | 12 | 1.6–2.0 s |
| Quiet-point cuts, no detector (the fallback of the app, before cuts had recorded context) | 29.6 s | 11 | 13 | 2.8 s |
| Speech spans in 10 s windows, 0.5 s tail (desktop policy on a stock runtime) | 35.3, 30.2, 31.3 s | 14 | 13 | 1.9–2.7 s |
| Speech spans whole, no tail | 26.8, 28.9, 26.9 s | 7 | 10 | 3.5–3.6 s |
| Speech spans whole, 30 ms recorded context, no tail (desktop policy on its patched runtime) | 28.6, 29.9, 27.2 s | 9 | 11 | 3.4–3.9 s |
| **Speech spans whole, 0.5 s tail (what the app uses)** | 30.2, 32.1 s | 9 | 8 | 3.9–4.2 s |

FLEURS sample (15.8 s), errors against the human reference, INT8, for the `.wav` and the `.m4a` of the same recording:

- Whole recording: 5 and 5.
- Fixed windows: 3 and 3.
- Quiet-point cuts: 3 and 4.
- 10 s windows with tail: 1 and 1.
- Whole spans with tail: 2 and 2.
- Whole spans without tail: 4 and 1.
- Whole spans with context and no tail: 5 and 3.

Three of the four runs without the tail read the number “4892” as words with a Spanish sound (“quattro ocho noventadu”, “quattro noventa”).

These were the differences on the three-minute clip:

* The decode of the full recording made errors that the policy of the app did not make: “cronomet” for “cronometro”, “al'esterno”, “idrorrepellente”, and two short words that it did not give. Most other cut versions did not make these errors either.
* 10 s windows cut words in two. Four seams kept the two parts (“cronometro. Cronometro”, “sofisticale. Sofisticato”, “l'albume Bume è. è”, “la. La”). The ported seam alignment removes a repeated word in two conditions: the two copies read the same and start within 200 ms of each other, or they start within 400 ms as part of a matching run. These copies were 220 to 300 ms apart, or they read differently because one was cut.
* Whole spans with recorded context and no tail, and 10 s windows, cut final words (“succed” for “succedere”, “l'u” for “l'uovo”). Whole spans without tail and without context cut no words on this clip.
* Whole spans with the tail had no duplicates and no cut words. Its remaining differences from the captions were single-word readings (“fin” for “film”, “aliento” for “alimento”).

FP32, three-minute excerpt, one earlier session without the quiet-point modes:

- Whole recording: 115.7 s and 10 differences from the captions.
- Fixed windows: 85.2 s and 11.
- 10 s windows: 92.8 s and 11.
- Whole spans with tail: 93.4 s and 12.

On FLEURS, the FP32 errors were 1 for the full recording and for fixed windows, 2 to 3 for 10 s windows, and 2 for whole spans with tail. On the 30 s clip, whole spans with tail had 3 words different from the full decode, and fixed windows had none. Automatic selects FP32 on this phone. With FP32, the cuts did not make the words better, and whole spans were one or two words worse in these runs. With FP32, the first words came about 15 s after the model load on the 30 s clip and on the three-minute clip. With INT8, they came after 3 to 4 s. The model load comes before this time and is not included.

Speed: on the three-minute clip, the decode with cuts took 26 to 32 s, and the full recording took 40.5 to 52.6 s, in these INT8 sessions (37.7 and 40.6 s in the earlier sessions). For FP32, the decode with cuts took 93.4 s and the full recording 115.7 s. An earlier full FP32 run took 90.2 s, so these runs show no FP32 speed-up. On the 30 s clip, the policy of the app was slower than the full decode: 5.5 to 7.7 s against 5.2 to 6.5 s for INT8, and 25.2 s against 18.6 s for FP32. We did not measure the peak memory for each mode.

The reasons for whole spans with the tail: this mode did not have the failures of the other modes. These failures were duplicated words at 10 s seams, and the misread number and the cut words of some runs without the tail. Its word counts are within the noise of the other modes. With INT8, it had the best score against the captions on the three-minute clip, and it was one error after the best on FLEURS. These are four clips of clean, read or scripted speech from single speakers. They do not show the accuracy on conversations or on noisy recordings. The detector threshold comes from the meeting tracks of the desktop, not from phone microphones.

The maximum span of the detector is not a limit in sherpa-onnx 1.13.7. With the same version and settings on a desktop, the three-minute clip gave seven spans of 19 to 27.7 s, and noisy input gave spans of minutes. Thus the app cuts a span longer than 28 s at its quietest point between 20 and 25 s after the previous cut. None of these clips has such a span, so this rule changed nothing in the detector rows of the table. Only the fallback row uses it, because in that row the full recording is one span. In that row, a cut at 133.58 s was inside “batterica”, and the word became “batteria”.

At this time, the app decodes each side of a cut with 1 s of the recording after the cut. Then it joins the two decodes at a word that they agree on. We made that change after the phone was locked. Thus we examined it on an Android 14 x86_64 emulator with INT8. The fallback kept “batterica” and had 10 words different from the captions. Whole spans with tail had the same count there, the full recording had 18, and fixed windows had 15. The emulator and the phone decode a little differently, so these counts are not comparable with the table.

Verification of the final code: 100 JVM tests pass. They include the window, seam and gate tests of the desktop, ported with the code, and new tests for the detector loop, the quiet-point cuts and the join at a cut. On the Pixel, 22 device checks passed with INT8 before the last changes: the detector on 16 kHz and 48 kHz input, the measured progress and the interim words during a real transcription, and record → transcribe → document. `ChunkingComparisonTest` operates only on request, and it made the tables of this section. After those changes, the phone was locked. Thus we ran the same 22 checks and the comparison with the INT8 model on an Android 14 x86_64 emulator.

## Speaker identification (2026-10-05, 0.0.6-beta)

The published, signed **0.0.6-beta APK** passed six UI checks for speaker identification on the same Pixel 8 (Android 17, 8 GB RAM). The checks were:

- Explicit opt-in.
- No action for empty transcripts.
- Download consent.
- A playable new variant with unchanged text, times and audio.
- Automatic separation of the two fixture voices.
- A cancel during the native embedding work.


The variant check measures 35.707 s for 46.840 s of audio. The time starts at the confirmation, and it includes the save, the decode of the output and the check of the labels on the screen. It does not include the creation of the fixture or the model downloads. A cancel keeps the original variant. The native model lease goes back after the current computation finishes.

Three more native diagnostics completed without skips:

| Recording | Speaker count setting | Diarization time | Result |
| --- | --- | ---: | --- |
| [Public four-speaker clip](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/0-four-speakers-zh.wav), 56.860 s | Automatic | 29.822 s | 7 speakers, 11 turns |
| Same clip | Known: 4 | 32.434 s | 4 speakers, 10 turns |
| Joined two-voice fixture, 46.840 s | Automatic | 41.996 s | 2 speakers, all 113 recognized words on the correct side of the join |
| Two-person conversation, 26.261 s | Automatic | 18.001 s | 3 speakers, 4 turns |
| Same conversation | Known: 2 | 20.898 s | 2 speakers, 3 turns |

The two-voice fixture first ran INT8 Parakeet in 15.142 s. The diarization times include the construction of the speaker model, the inference and the cleanup. They do not include the audio decode, the ASR and the save. The diagnostic of the public clip ran in a new instrumentation process. Its peak RSS was **284 MiB** after the two passes. The process that also ran the ASR had a peak of **1,088 MiB**. These are high-water marks of `/proc/self/status`. They are not PSS, and they are not separate measurements of the memory of each model.

The automatic clustering gave too many speakers for the four-speaker clip and for the conversation. Use the known count when it is available. A requested count does not show that the identities or the boundaries are correct. There is no human-labeled diarization reference for these runs, and the join check covers voices that speak one after the other, not overlapping speech. These are single runs on a phone with USB power, without control of the cache, the temperature or the background load. The private conversation and the transcript stay local.

The speaker pipeline uses stock sherpa-onnx 1.13.7 on the CPU with two threads, INT8 pyannote segmentation and ERes2Net embeddings. It labels one mixed recording. It does not do the desktop attribution from separate participant tracks. The output is still a complete portable Opus document with an added word variant, and the original audio and transcript stay.

Android 17 showed a failure in the reflective input API of the old Espresso test harness. An update of the test-only dependencies to runner 1.7.0, JUnit extension 1.3.0 and Espresso 3.7.0 corrected it ([AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)). The application APK of these checks is the published release, without changes to the app code.

Four existing playback and settings checks also passed. Thus this phone run has **13 passing checks**. The debug assembly, all 143 JVM tests and lint passed after the update of the harness. A SHA-256 comparison of each original private file showed that all 62 files did not change. These files include the catalogue of 16 notes, the recordings, the documents, the session, the preferences and the two Parakeet bundles. Only the three optional speaker-model files were new. After the checks, we installed the signed, non-debuggable 0.0.6-beta release again, and it kept that data.

[CI for the updated harness](https://github.com/codemyriad/cassini-android/actions/runs/37291277773) also passed the build, the unit and conformance tests, lint and 31 device checks without models on Android 8, 10 and 14. These emulator checks cover consent, playback, recording and settings. The native diarization measurements of this section come from the Pixel.

## INT8 and recording cleanup (2026-10-07, development build)

The app uses only Parakeet INT8, and it transcribes after the AAC recording finishes. We removed the FP32 downloads, the Automatic/manual model selection and the five-second live transcription path. At the start, the app removed the old FP32 model directory of the Pixel and kept the verified INT8 installation.

The debug APK, 118 JVM tests (with all published format vectors) and lint pass. The revised CI selection passes all 12 checks on an Android 14 x86_64 emulator. The Pixel 8 / Android 17 run completed 27 selected checks. 26 passed, and the speaker-download consent check skipped, because those optional models were already installed. The emulator without models passed that consent check.

The Pixel checks included pause and resume, the recovery after an interruption, and the automatic transcription after Done. They also included the batch progress, a native INT8 recognition, the load of a legacy session, playback, portable export and the Opus duration of three minutes. All 36 existing recording and document files kept their SHA-256 hashes. The 30-second portable ASR document that the phone made passes the reference manifest and word payload checks and the real portable reader of the sibling web viewer.

A separate native check of the speaker identification passed. It kept the words, the times, the original variant and the audio. We stopped more native checks of the clustering and the cancel when the phone was necessary for other work. They did not complete in this cleanup session. We made no release tag.

## Incremental processing and speaker merges (2026-10-08, development build)

Parakeet INT8 is still the speech model. The audio decode, the 24 kbit/s Opus encode, the recognition and the stateful Sortformer attribution read the recording in one pass. The recognition uses 28-second windows with two seconds of overlap. The pipeline keeps one window in RAM and at most 45 seconds of selected voice clips for each speaker on the disk. It does not keep the full decoded recording.

The native model allocations still use most of the memory. The process that ran the six selected streaming and speaker checks had an RSS high-water mark of 1,368,632 KiB. This is a measurement of the full process, not of one model or one meeting.

That streaming milestone passed 193 JVM tests, lint and nine selected Pixel checks. The Pixel checks were:

- Early incremental words.
- An Opus encode in the same pass, with the exact duration.
- Stable native speaker slots.
- Names for draft speakers.
- Merges of draft speakers through later windows.
- The confirmation and the previous variants for saved merges.
- A cancel during the diarization.
- A cancel during the voice matching.
- The automatic creation of the portable file.
 The saved 30-second fixture has 86 timed words. The reference format tools validate its manifest and word schemas, the payload byte counts and the hashes. Its Opus digest, calculated independently, agrees with the manifest.

The progress and Cancel stay above the transcript. The total percentage follows the completed chunks of words and speakers. A change of the native stage cannot reset it or start the animation again. Four fixed lines show the processed audio, the current stage, the words and the elapsed time. After three chunks give a pace, they also show an approximate remaining time. The app does not show short reading transitions, and it smooths the estimated pace.

The earlier pipeline for the full recording processed a 64-minute recording in 34 minutes 35 seconds. The streaming Parakeet run of the same recording completed in 35 minutes 46 seconds (2,145,802 ms total, 844,503 ms in ASR). This single trial was about 3.4% slower in total than the earlier run. It shows no speed improvement. These times are from before the durable checkpoints and the libopus encoder. At that milestone, Cancel discarded the draft. The next section gives the stop and resume and the estimates that the list of queued work of 2026-10-08 asked for (0.2 removed this list).

## Durable resume, estimates and remembered draft voices (2026-10-08)

You can stop a 90-second fixture after a completed chunk, close it, open it again and resume it. The result has the same prefix of words, speaker IDs, assigned name and prepared audio, and the exact 90-second Opus duration. Native tests compare these items independently:

- The restored Sortformer turns and slots.
- The Opus packet digests and the decoded audio of a continuous run and of a resumed run.
- The clocks of WAV, AAC and Opus after a seek.


The seek test found that Android applied the Opus pre-skip again in the middle of a recording, and we corrected this. A seek in WAV is exact to the sample. For compressed audio, the test examines the sample count and the alignment of the waveform, with a tolerance for the rounding of the codec.

The status has five stable lines, an approximate range for the time to the end (from the observed variability), and the battery status. The battery extrapolation needs two minutes and a measured discharge of at least 0.5 percentage points. Charging resets it. It uses the upper end of the audio estimate and the observed finalization time as a conservative budget for the completion. The Pixel is connected to power at 100%, so we did not measure the real battery accuracy without power. Unit tests cover synthetic discharge and reset cases.

When the user names a live draft, the app gives the same checkbox for a remembered voice as for a finished transcript. It also gives the same saved persons. This selection stays through the checkpoints. The app enrols the voice only after it adopts the completed artifact. A second recognition shows the remembered name in the live results, and automatic guesses do not teach the profile again. A new processing of a named meeting can also match its own existing voiceprints. A repair that the user asked for recovered the profiles Silvio and Conor from the named 14:17 note, without a change to its recording.

The latest host build passes 198 unit tests and lint. Seven selected Pixel checks cover these items:

- Resume with names, and the reuse in later live results.
- The encoder state.
- The diarizer state.
- The seek alignment.
- Draft merges.
- The five-line status and the creation of the sealed portable file.
- The recovery of named profiles.
 The fixture tests keep the restored real profiles. The full meeting gave only an uncertain Conor suggestion (cosine similarity 0.629), so its anonymous labels stayed for a confirmation by the user. The final installation is still a development build.
