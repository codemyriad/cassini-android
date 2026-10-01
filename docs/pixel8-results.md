# Pixel 8 prototype results — 2026-10-01

**Both INT8 and FP32 ran successfully on the connected Pixel 8.** The tested recording is the requested [YouTube video](https://www.youtube.com/watch?v=UmZwQf5TV3c), cut from **00:28 to 00:58** with `uvx yt-dlp` and ffmpeg. The resulting input has exactly 480,000 mono PCM16 samples at 16 kHz (30.000 seconds).

The device reports Pixel 8, Android 17 / API 37, with 7,754,764 KiB total system RAM (nominal 8 GB). The debug APK uses stock sherpa-onnx 1.13.7, CPU provider, greedy decoding, 128-dimensional features and two inference threads.

| Precision | Model size | Model creation + recognition | Process peak RSS | Words |
| --- | ---: | ---: | ---: | ---: |
| INT8 | 639.4 MiB | 7.031 s | 1,067,988 KiB / 1.02 GiB | 87 |
| FP32 | 2.37 GiB | 19.399 s | 2,721,900 KiB / 2.60 GiB | 86 |

An earlier INT8 run took 6.754 seconds and produced the same text. The table uses the later instrumented run because it also measured peak RSS. Times exclude downloads and audio import but include recognizer construction, inference, result conversion and native cleanup. Peak RSS is the process's `/proc/self/status` `VmHWM`, measured after recognition; it includes the test process and shared mappings, and is not PSS or a measurement of total device memory pressure. These are short-clip runs, with no controlled cache, thermal or battery conditions.

FP32 and INT8 produced the same word sequence except for a comma after “galleggia” and a trailing “Il” in the INT8 result at the clip boundary. No human reference or word-error-rate evaluation has been completed for this excerpt. This comparison does not establish that FP32 is more accurate. On the separate FLEURS Italian fixture, the INT8 result contained lexical errors, including “Antarti” for “Antartide”; successful execution is distinct from recognition quality.

Validation completed:

- Seven JVM tests covering Italian SentencePiece reconstruction, accented text/apostrophes, delayed punctuation, empty speech, speaker-turn order and invalid timing rejection.
- Three Android device tests passing with each precision: real WAV decoding, actual native recognition, and AAC/MP3/Opus duration/onset comparison against WAV. Compressed duration and onset tolerance is 100 ms, not a precise alignment certification.
- Both actual phone-generated transcript bodies validated against `../cassini-format/spec/cassini-words-v1.schema.json`.
- Manual UI check on the unlocked Pixel: selected the supplied WAV through Android's file picker, transcribed it in the activity (5.674 seconds in this later run), tapped a word to seek/start playback, paused playback, and verified the active word and search match highlights.
- Debug APK build and Android lint completed with no errors. Lint reports prototype warnings about localization, icon/backup configuration and storage-space API choice.

The generated bodies and metrics remain in the phone's private app storage and in local ignored `.tools/` artifacts. Reproduce the checks with the commands in [README.md](../README.md). Both model bundles remain installed; the supplied WAV is in the phone's Downloads directory as `cassini-italian-28-58.wav`.

Storage cleanup removed the two explicitly approved Gemma/Qwen downloads and requested Android cache trimming. About 5.5 GB was recovered. After retaining both Parakeet bundles, the phone reports approximately **4.5 GB free**. Photos, recordings, Maps data and the Linux Terminal environment were retained.
