# Physical Handset Qualification — Android (`D-1126`, `D-1117`)

Empirical physical-handset qualification of `transcribeWhileRecording` vs. `afterStop` / `recordOnly` on real Android handsets via AWS Device Farm (`us-west-2`, D89) for **Parakeet TDT (+ streaming Sortformer + CAM++ saved voices)**, **Whistle (`INCLUDED` + Sortformer + CAM++)**, and **API 26–28 `AacCapture` fallback**, prior to enabling recording-time precomputation by default on any Android device profile.

- **Code baseline:** `093efc96` (`main`) + [HandsetQualificationDeviceTest.kt](../app/src/androidTest/java/io/codemyriad/cassini/qualification/HandsetQualificationDeviceTest.kt), [HandsetQualificationContractTest.kt](../app/src/test/java/io/codemyriad/cassini/qualification/HandsetQualificationContractTest.kt), [WhistlePrefixAudit.kt](../engine/src/main/kotlin/io/codemyriad/cassini/engine/whistle/WhistlePrefixAudit.kt), and [devicefarm-qualify.py](../scripts/devicefarm-qualify.py).
- **Shared contracts:** `cassini-ux/contract/HEAD-START-TRANSCRIPTION.md` (`D109`), `cassini-ux/decisions/DECISIONS-UX.md` (`D89`).
- **Shipped default:** remains `afterStop` (no UI or default-mode change in this PR).

---

## 1. Qualified Physical Handsets & Device Farm Runs

Three distinct physical Android handsets spanning `minSdk` (**API 26**) through **API 35**, three SoC vendors (Google Tensor G3, MediaTek Dimensity 6100+ `mt6835`, and Qualcomm Snapdragon 845), and both above (`7.42 GiB`) and below (`3.50 GiB`) Cassini's `ParakeetSuggestion` 6 GB RAM boundary were qualified in AWS Device Farm (`us-west-2`):

| Handset | Model / Device | SoC / Total RAM | OS / SDK | Hardware Codecs | Run Name | Result | Scope |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Samsung Galaxy S9+ (Unlocked)** | `SM-G965U` (`star2qltesq`) | Snapdragon 845 (`qcom`) / 5.0 GB (`5,009.4 MiB`) | Android 8.0.0 / **API 26** (`minSdk`) | Opus enc: `unavailable`; Opus dec: `OMX.google.opus.decoder` | `d1126-s9plus-api26-aac-r2` | **PASSED** (2/2, `2.24` metered min) | Smoke contract + API 26 `AacCapture` (`.m4a`) fallback (historical run used `ExplicitAacCaptureSource`; natural unset-override selection **Pending physical re-run**), API 26 transient audio-focus auto-pause/resume, `0` precompute checkpoints, post-Stop Parakeet `.opus` publication |
| **Google Pixel 8** | `Pixel 8` (`shiba`) | Tensor G3 / 8 GB (`7,598.6 MiB`) | Android 15 / **API 35** | `c2.android.opus.encoder` + `c2.android.opus.decoder` | `d1126-pixel8-api35-5min-v4` | **PASSED** (3/3, `47.77` metered min) | Live `OpusCapture` + `CommittedOggOpusDecoder` prefix PCM check, Pause/Resume, Home backgrounding, focus/call/noisy hooks, explicit AAC fallback, 5-min paired Parakeet & Whistle (+Sortformer +CAM++ 3 voices), separate thermal (`YIELDED`) + stall, dedicated preflight `LOW_MEMORY`, and OOM suppression |
| **Samsung Galaxy A15 5G** | `SM-A156U1` (`a15x`) | Dimensity 6100+ (`mt6835`) / **4 GB (`3,583.8 MiB`)** | Android 14 / **API 34** | `c2.android.opus.encoder` + `c2.android.opus.decoder` | `d1126-galaxya15-api34-5min-v4` | **PASSED** (3/3, `74.15` metered min) | Same full 3/3 lifecycle + AAC fallback + 5-min paired Parakeet & Whistle (+Sortformer +CAM++ 3 voices) + separate thermal (`YIELDED`), dedicated preflight `LOW_MEMORY`, and OOM suppression on a 4 GB RAM handset below the 6 GB Parakeet suggestion boundary |
| **Samsung Galaxy A15 5G** | `SM-A156U1` (`a15x`) | Dimensity 6100+ (`mt6835`) / **4 GB (`3,584.0 MiB`)** | Android 14 / **API 34** | `c2.android.opus.encoder` + `c2.android.opus.decoder` | `d1126-galaxya15-api34-30min-parakeet-v2` | **PASSED** (1/1, `41.92` metered min) | 30-min (`1,800 s`) 1.0× real-time production `OpusCapture` (`1,000 ms` `AudioRecord` ring buffer, `c2.android.opus.encoder`, `OggOpus.LiveMuxer`, 5 s `fsync`) under `Parakeet + Sortformer + CAM++` `transcribeWhileRecording` |
| **Google Pixel 8** | `Pixel 8` (`shiba`) | Tensor G3 / 8 GB (`7,598.6 MiB`) | Android 15 / **API 35** | `c2.android.opus.encoder` + `c2.android.opus.decoder` | `d1126-pixel8-api35-30min-whistle-v2` | **PASSED** (1/1, `64.75` metered min) | 30-min (`1,800 s`) 1.0× real-time production `OpusCapture` (`1,000 ms` `AudioRecord` ring buffer) under `Whistle + Sortformer + CAM++` `transcribeWhileRecording` with span-level `WhistlePrefixAudit` verification |

Total AWS Device Farm (`us-west-2`, `$0.17`/min) usage for this qualification slice (across initial runs and review regeneration): **`487.87` metered minutes = `$82.94`**, bringing November 2026 month-to-date spend from `$51.67` (`303.95` min) to **`$134.61` (`791.82` min)** against the **`$200.00`** D89 monthly budget and **`$160.00`** D-1126 scheduling denial threshold (**`$25.39`** remaining scheduling headroom; `$65.39` remaining monthly budget).

---

## 2. Aggregate Hardware Qualification Tables

### 2.1 Paired 5-minute (`300 s`) Multi-Speaker Speech Equivalence & Stop-to-Published Latency

Stimulus: `300 s` 48 kHz mono PCM16LE 3-speaker real speech (`fixtures/audio/fleurs-it-short.wav` + DC-corrected `fixtures/audio/dc-quiet-jfk-v1.wav` + 1.28× formant/rate-shifted speaker profile, streamed from a compact `~3.8 MiB` cycle buffer through real hardware `MediaCodec("audio/opus")` + `OggOpus.LiveMuxer`). Isolated test library populated with **3 real CAM++-enrolled voices** using synthetic labels (`Voice_A`, `Voice_B`, `Voice_C`; max off-diagonal cosine `0.1215`).

Stop-to-published latency is measured end-to-end starting immediately before `RecordingController.finish()` (`SystemClock.elapsedRealtimeNanos()`) through `RecordingService` EOS sealing, `ProcessingHandoff`, `ProcessingCoordinator`, `TranscriptionJob` tail execution, `CassiniDocument` `.opus` sealing, and Room library publication (`jobState == null`).

| Handset (OS, RAM) | Engine | Mode | Stop-to-Published (s) | Speedup | Post-Stop Units | Precompute Checkpoints (`x-resumedFromMs`) | Words / Turns / Speakers / Matched Voices | Peak App RSS (MiB)§ | Peak Native Heap (MiB) | Min Avail RAM (MiB) | Low-Mem / Trim | Peak Thermal | App + Worker CPU (s) | Equivalence |
| --- | --- | --- | ---: | ---: | ---: | --- | --- | ---: | ---: | ---: | --- | --- | ---: | --- |
| **Pixel 8** (Android 15, 8 GB) | `recordOnly` | control | **0.06** | — | 0 | 0 (`0 ms`) | — | 430.0 | 33.0 | 3,803.8 | `0` / `0` | `none` | 34.89 | N/A |
| **Pixel 8** (Android 15, 8 GB) | **Parakeet + Sortformer + CAM++** | `afterStop` | **117.20** | 1.00× | 12 windows | 0 (`0 ms`) | `517` / `51` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | 1,646.3 | 1,396.5 | 2,751.4 | `0` / `0` | `none` | 176.17 | baseline |
| **Pixel 8** (Android 15, 8 GB) | **Parakeet + Sortformer + CAM++** | `transcribeWhileRecording` | **25.85** | **4.53×** | **2 windows** | 10 (`262,000 ms`) | `517` / `51` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | 1,576.3 (pre) / 1,626.8 (post) | 1,233.2 (pre) / 1,460.9 (post) | 2,977.2 (pre) / 2,905.7 (post) | `0` / `0` | `none` | 156.54 + 40.71 | **PASS** |
| **Pixel 8** (Android 15, 8 GB) | **Whistle + Sortformer + CAM++** | `afterStop` | **315.13** | 1.00× | 44 neural segs | 0 (`0 ms`) | `506` / `44` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (AUTO)` | 776.6 | 395.2 | 3,507.7 | `0` / `0` | `none` | 240.19 + 343.31 (`583.49`) | baseline |
| **Pixel 8** (Android 15, 8 GB) | **Whistle + Sortformer + CAM++** | `transcribeWhileRecording` | **309.26**† | **1.02×**† | **6 neural segs** (`38/38` prefix cache hits, **0 prefix re-decodes**) | 38 (`252,672 ms`) | `506` / `44` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (AUTO)` | 653.6 (pre) / 717.5 (post) | 224.2 (pre) / 361.7 (post) | 3,641.3 (pre) / 3,625.6 (post) | `0` / `0` | `none` | 236.72 (pre) / 244.51 + **140.41** (`384.92` post) | **PASS** |
| **Galaxy A15** (Android 14, 4 GB) | `recordOnly` | control | **0.08** | — | 0 | 0 (`0 ms`) | — | 215.7 | 39.9 | 1,133.3 | `0` / `0` | `none` | 75.12 | N/A |
| **Galaxy A15** (Android 14, 4 GB) | **Parakeet + Sortformer + CAM++** | `afterStop` | **185.98** | 1.00× | 12 windows | 0 (`0 ms`) | `518` / `50` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | 1,484.8 | 1,691.8 | **331.8** | **`1` / `15` (`CRITICAL`)** | `none` | 264.85 | baseline |
| **Galaxy A15** (Android 14, 4 GB) | **Parakeet + Sortformer + CAM++** | `transcribeWhileRecording` | **37.78** | **4.92×** | **2 windows** | 10 (`262,000 ms`) | `518` / `50` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | 1,382.9 (pre) / 1,499.8 (post) | 1,497.1 (pre) / 1,774.5 (post) | **384.9** (pre) / **270.2** (post) | **`1` / `15`** (pre) / `0` / `0` (post) | `none` | 242.35 + 57.68 | **PASS** |
| **Galaxy A15** (Android 14, 4 GB) | **Whistle + Sortformer + CAM++** | `afterStop` | **375.41** | 1.00× | 44 neural segs | 0 (`0 ms`) | `506` / `44` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (AUTO)` | 485.1 | 468.8 | 1,119.6 | `0` / `0` | `none` | 295.19 + 131.43 (`426.63`) | baseline |
| **Galaxy A15** (Android 14, 4 GB) | **Whistle + Sortformer + CAM++** | `transcribeWhileRecording` | **385.25**† | **0.97×**† | **6 neural segs** (`38/38` prefix cache hits, **0 prefix re-decodes**) | 38 (`252,672 ms`) | `506` / `44` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (AUTO)` | **349.4** (pre) / **466.4** (post) | 263.9 (pre) / 427.0 (post) | **1,222.8** (pre) / **1,090.2** (post) | `0` / `0` | `none` | 261.18 (pre) / 257.92 + **114.32** (`372.23` post) | **PASS** |

*†Whistle post-Stop resume CPU mechanism (see Section 4, item 4): `WhistlePrefixAudit` verified at exact `"$startSample-$endSample:$pcmSha256"` granularity that `38 / 38` recording-time prefix segments hit `work/whistle-segments.json` (`0` neural ORT prefix re-decodes, cutting isolated `WhistleService` worker CPU by **59.1%** on Pixel 8 from `343.31 s` → `140.41 s`), and `WhistleRecognizer` suppresses `onChunk` / `Sortformer.process` while `replaying == true`. However, post-Stop resume currently executes two origin-0 decode passes to reconstruct stateful filter/VAD history: (1) `WhistleJobs.prefix` streams `0..limit` through `AudioDecoder(included=true)` (stateful 48k→16k desktop FIR) + Binder IPC into `WhistleWorker` (`SileroDetector` VAD + `WhistleSpeechGate`), and (2) `TranscriptionJob.decodeAudio` streams from sample `0` a second time through `AudioDecoder(included=true)` before emitting tail samples `>= limit`, keeping `appCpuSeconds` at `~245–258 s` on 5-minute notes.*

*§Memory & CPU telemetry scope: `HandsetTelemetryMonitor` reads `/proc/self/status` and `Process.getElapsedCpuTime()` inside the app process, so **Peak App RSS** for Whistle rows reflects **app-process RSS only (excluding the isolated `WhistleService` worker process RSS)**, whereas `Min Avail RAM` (`/proc/meminfo` `MemAvailable`) reflects device-wide available memory across both processes. Similarly, Whistle **recording-phase (`pre`) CPU** reflects **app-process CPU only (isolated `WhistleService` worker CPU is unavailable during recording because precomputation closes the recognizer without `FINISH`)**, whereas post-Stop Whistle CPU includes both app CPU and isolated worker CPU reported by `FINISH`.*

### 2.2 Separate Thermal Yield (`YIELDED`), Low-Memory Suppression (`LOW_MEMORY`) & OOM (`OUT_OF_MEMORY`) Lanes

Thermal throttling + inference stall and preflight low-memory pressure were executed in **separate dedicated qualification lanes** for both engines on both API 29+ handsets, plus injected `OutOfMemoryError` suppression:

| Handset | Engine | Fault / Pressure Lane | Observed `PrecomputeStopReason` | Checkpoints Saved Before Stop | Capture State During Fault | Post-Stop Units Decoded | Post-Stop Recovery Latency (s) | Peak Post-Stop RSS / Min Avail RAM (MiB) | Output Match vs. `afterStop` |
| --- | --- | --- | --- | ---: | --- | ---: | ---: | --- | --- |
| **Pixel 8** | **Parakeet** | Injected `THERMAL_STATUS_SEVERE` + stall | **`YIELDED`** | `2` (paused `<= 1` unit after trigger `2`) | `Active` (uninterrupted) | `10` windows | `96.96` | `1,722.9` / `2,770.1` | **PASS** (`517`w / `51`t / `3` voices) |
| **Pixel 8** | **Parakeet** | Dedicated preflight `lowMemory = true` | **`LOW_MEMORY`** (suppressed for note) | **`0`** | `Active` (uninterrupted) | `12` windows | `116.83` | `1,649.9` / `2,827.4` | **PASS** (`517`w / `51`t / `3` voices) |
| **Pixel 8** | **Whistle** | Injected `THERMAL_STATUS_SEVERE` + stall | **`YIELDED`** | `3` (paused `<= 1` unit after trigger `3`) | `Active` (uninterrupted) | `41` segs | `312.21` | `790.5` / `3,534.0` | **PASS** (`506`w / `44`t / `3` voices) |
| **Pixel 8** | **Whistle** | Dedicated preflight `lowMemory = true` | **`LOW_MEMORY`** (suppressed for note) | **`0`** | `Active` (uninterrupted) | `44` segs | `313.17` | `750.4` / `3,573.1` | **PASS** (`506`w / `44`t / `3` voices) |
| **Pixel 8** | **Parakeet** | Injected precompute `OutOfMemoryError` | **`OUT_OF_MEMORY`** (suppressed for note) | `0` | `Active` (uninterrupted) | — (`recordOnly` finish) | — | — | **PASS** |
| **Galaxy A15** | **Parakeet** | Injected `THERMAL_STATUS_SEVERE` + stall | **`YIELDED`** | `2` (paused `<= 1` unit after trigger `2`) | `Active` (uninterrupted) | `10` windows | `153.34` | `1,507.3` / `274.0` | **PASS** (`518`w / `50`t / `3` voices) |
| **Galaxy A15** | **Parakeet** | Dedicated preflight `lowMemory = true` | **`LOW_MEMORY`** (suppressed for note) | **`0`** | `Active` (uninterrupted) | `12` windows | `180.01` | `1,455.5` / `287.3` | **PASS** (`518`w / `50`t / `3` voices) |
| **Galaxy A15** | **Whistle** | Injected `THERMAL_STATUS_SEVERE` + stall | **`YIELDED`** | `3` (paused `<= 1` unit after trigger `3`) | `Active` (uninterrupted) | `41` segs | `372.39` | `498.2` / `982.7` | **PASS** (`506`w / `44`t / `3` voices) |
| **Galaxy A15** | **Whistle** | Dedicated preflight `lowMemory = true` | **`LOW_MEMORY`** (suppressed for note) | **`0`** | `Active` (uninterrupted) | `44` segs | `373.58` | `485.9` / `1,022.6` | **PASS** (`506`w / `44`t / `3` voices) |
| **Galaxy A15** | **Parakeet** | Injected precompute `OutOfMemoryError` | **`OUT_OF_MEMORY`** (suppressed for note) | `0` | `Active` (uninterrupted) | — (`recordOnly` finish) | — | — | **PASS** |

### 2.3 30-minute (`1,800 s`) Continuous 1.0× Real-Time Production `OpusCapture` Qualification

Production `OpusCapture` (`CaptureFactory.create` → `OpusCapture`, `AudioRecord(MIC, 48_000 Hz, PCM_16BIT)` with its exact **1-second (`1,000 ms`)** minimum buffer, `cassini-recording` `MAX_PRIORITY` thread, D26.1 AC meter, `c2.android.opus.encoder`, `OggOpus.LiveMuxer`, 1 s flush, and 5 s `output.fd.sync()`) running continuously for `1,800 s` (`86,400,000` samples) while `RecordingPrecomputeController` executes live head-start precomputation on disk, followed by `RecordingController.finish()` and `TranscriptionJob` publication:

| Handset (OS, RAM) | Engine | Recorder (`ringBufferMs`) & Dropped Buffers | Max `OpusCapture` Frame Interval vs. Ring | Checkpoints Saved (`x-resumedFromMs`) | Precompute / Post-Stop Units | Stop-to-Published (s) | Words / Turns / Matched Voices | Peak App RSS (rec / post, MiB)§ | Min Avail RAM (rec / post, MiB) | Low-Mem / Trim (rec / post) | Thermal Trace | Battery (`BatteryManager`, USB rack) |
| --- | --- | --- | --- | --- | --- | ---: | --- | --- | --- | --- | --- | --- |
| **Galaxy A15** (Android 14, 4 GB) | **Parakeet + Sortformer + CAM++** | `OpusCapture` (`1,000 ms`), **`0` dropped** (`86.4M` samples, `1,800.22 s`) | **`726 ms`** / `1,000 ms` | **68** (`1,770,000 ms` = `29m 30s`) | `69` pre / **`2` post** | **56.82** | `3,115` / `297` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | `1,371.1` / `1,521.9` (JVM `51.5`, native `1,830.6`) | **`155.9` / `186.2`** | **`4`/`15` (`CRITICAL`)** / `0`/`0` | `62/62` `none` (rec), `3/3` `none` (post) | `100% → 100%` (`USB`, `0 µAh` delta clamped) |
| **Pixel 8** (Android 15, 8 GB) | **Whistle + Sortformer + CAM++** | `OpusCapture` (`1,000 ms`), **`0` dropped** (`86.4M` samples, `1,800.08 s`) | **`48 ms`** / `1,000 ms` | **256** (`1,746,816 ms` = `29m 06.8s`) | `256` pre (`256/256` cache hits, **0 re-decodes**) / **`7` neural post** | **1,669.57**‡ | `3,061` / `265` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | **`641.3` / `777.0`** (JVM `109.6`, native `227.9` / `495.8`) | **`3,518.4` / `3,406.5`** | `0`/`0` / `0`/`0` | `62/62` `none` (rec), `58/58` `none` (post) | `100% → 100%` (`USB`, `-82 mAh` rec / `-6 mAh` post, avg `-162.5 mA`) |

*‡30-minute production `OpusCapture` + Whistle findings: (1) Driving production `OpusCapture` directly exposed real 5-second `output.fd.sync()` + ORT scheduling stalls on the 4 GB Galaxy A15 (`maxFrameIntervalMs = 726 ms` against `OpusCapture`'s `1,000 ms` ring buffer, still with `0` dropped buffers reported in the historical run, while `MemAvailable` dipped to `155.9 MiB` with `4` `TRIM_MEMORY_RUNNING_CRITICAL` events — reinforcing recommendation #1 below). Note: the historical 30-min runs predated the delta-based `HalTimestampDropTracker` fix (saving both initial HAL frame and app sample count, and rejecting unavailable HAL timestamps) and the strict Parakeet late-precompute/tail validator; re-verifying with those stricter harness checks is **Pending physical re-run** (historical reports already recorded `68` checkpoints / `1,770,000 ms` resume for Parakeet and `256` checkpoints / `1,746,816 ms` resume for Whistle). (2) On Pixel 8, Whistle kept pace in real time (`256` checkpoints saved up to `1,746.82 s`) and `WhistlePrefixAudit` proved `256 / 256` cached prefix hits with **zero neural prefix re-decodes** (`7` neural tail segments). Post-Stop wall time (`1,669.57 s`) comes from the two full-prefix origin-0 `AudioDecoder(included=true)` 48k→16k FIR passes (`1,391.9 s` app CPU) plus `SileroDetector` VAD replay (`509.2 s` worker CPU), whereas `Parakeet` skips prefix decode/diarization completely on resume and published the 30-minute 3-speaker recording in **`56.82 s`**.*

---

## 3. Equivalence, Pressure Injection, Lifecycle & `AacCapture` Fallback Matrix

| Check | Galaxy S9+ (API 26) | Galaxy A15 (API 34, 4 GB) | Pixel 8 (API 35, 8 GB) | Details |
| --- | --- | --- | --- | --- |
| Live `.opus` prefix SHA-256 (`OggOpusPrefixScanner`) vs. EOS-sealed `.opus` | **PASS** (`@CiSmoke`) | **PASS** (`13,082` B & `22,681` B → `56,481` B) | **PASS** (`14,537` B & `24,073` B → `58,851` B) | Bit-identical byte prefix before and after `OggOpus.LiveMuxer.finish(...)` EOS page |
| Live `CommittedOggOpusDecoder` 16 kHz PCM vs. finalized `AudioDecoder` PCM | N/A (API < 29) | **PASS** (`maxAbsDiff = 0.0` over `48,520` samples) | **PASS** (`maxAbsDiff = 0.0` over `50,440` samples) | Exact bit-for-bit float32 PCM agreement on hardware `c2.android.opus.encoder` + `c2.android.opus.decoder` |
| Paired 5-min `.opus` SHA-256, decoded PCM SHA-256, words, `startMs`/`endMs`, Sortformer turns/speakers, and CAM++ saved-voice matches (`Voice_A`, `Voice_B`, `Voice_C`) | N/A (API < 29) | **PASS** (Parakeet `518`w/`50`t & Whistle `506`w/`44`t) | **PASS** (Parakeet `517`w/`51`t & Whistle `506`w/`44`t) | Exact packet/PCM hash + transcript/speaker/voice match verified across `AFTER_STOP` vs. `TRANSCRIBE_WHILE_RECORDING`; historical thermal/low-memory recovery runs verified words, turn counts, and CAM++ voice matches (recovery packet/PCM hash + speaker-label assertions now enforced in code: **Pending physical re-run**) |
| `WhistlePrefixAudit` span-level zero neural prefix re-decodes at Stop + negative single-entry loss regression | N/A (API < 29) | **PASS** (`38/38` prefix spans cached, `0` re-decoded, `6` tail = `44`) | **PASS** (`38/38` on 5-min; `256/256` on 30-min; `0` re-decoded) | Verified per `"$startSample-$endSample:$pcmSha256"` span; negative JVM regression proves dropping `1` cached prefix entry fails qualification |
| `AacCapture` (`.m4a`) fallback under `TRANSCRIBE_WHILE_RECORDING` | **PASS** (historical run used `ExplicitAacCaptureSource`, `10.97 s` Stop-to-pub; natural API 26 selection **Pending physical re-run**) | **PASS** (explicit control, `8.42 s` Stop-to-pub) | **PASS** (explicit control, `4.55 s` Stop-to-pub) | `0` precompute checkpoints spawned; post-Stop `StreamingOpusEncoder` sealed valid `DocumentState.OK` `.opus`; harness now leaves `captureSource` unset on API 26–28 and asserts `CaptureFormat.M4A` + `AacCapture` |
| Separate thermal + stall (`YIELDED`), preflight low-memory (`LOW_MEMORY`), and `OutOfMemoryError` (`OUT_OF_MEMORY`) lanes | N/A | **PASS** (`YIELDED` `<= 1` unit; `LOW_MEMORY` `0` cp; `OUT_OF_MEMORY`) | **PASS** (`YIELDED` `<= 1` unit; `LOW_MEMORY` `0` cp; `OUT_OF_MEMORY`) | `RecordingState.Active` remained uninterrupted; post-Stop `TranscriptionJob` recovered identical transcripts |
| Pause → wait → Resume, Home backgrounding (`KEYCODE_HOME` / `RecordingService`), transient audio focus (`LOST_FOR_NOW` → `GAINED`), incoming-call silence (`RecordingPause.OTHER_APP`), and `ACTION_AUDIO_BECOMING_NOISY` | **PASS** (`api26FocusPauseVerified = true` + noisy hook) | **PASS** (historical: 0 ms pause drift, 2-tick bg advance, precompute yielded on Home; full 5-min bg + strict focus-grant assertions **Pending physical re-run**) | **PASS** (historical: 0 ms pause drift, 2-tick bg advance, precompute yielded on Home; full 5-min bg + strict focus-grant assertions **Pending physical re-run**) | Zero fixed sleeps (`E1` / D88); synchronized strictly on `StateFlow`, `Channel`, and `CountDownLatch` transitions. Harness now holds `KEYCODE_HOME` for `300,000 ms`, verifies sealed `.opus` duration covers it, and requires both focus grants + ordered `LOST_FOR_NOW` → `GAINED` (**Pending physical re-run**) |

---

## 4. Device-Capability, Memory & Thermal Threshold Recommendations (for Silvio's Sign-Off)

1. **`ParakeetSuggestion` 6 GB RAM boundary (`>= 6 GB` recommended for Parakeet; Whistle default below 6 GB) — strongly validated for Parakeet; keep unchanged, and gate default Parakeet `transcribeWhileRecording` to `>= 6 GB` RAM (4 GB Whistle full-process RSS pending isolated-worker measurement).**
   - On the **4 GB (`3,584 MiB`) Samsung Galaxy A15**, **Whistle** head-start precomputation recorded **`349.4 MiB` app-process RSS** (excluding isolated `WhistleService` worker RSS; `/proc/self/status` in the app process), with device-wide `MemAvailable` staying at **`1,222.8 MiB`** (pre) / **`1,090.2 MiB`** (post) and `0` low-memory / trim events, whereas **Parakeet + Sortformer** peaked at **`1,371–1,522 MiB` app-process RSS** (`1,497–1,831 MiB` native heap), compressing `MemAvailable` down to **`155.9–384.9 MiB`**, triggering `ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL` (`level 15`) + `onLowMemory`, and pushing production `OpusCapture`'s frame drain interval up to **`726 ms / 1,000 ms`** during 5 s `fsync` boundaries.
   - Recommendation: when `transcribeWhileRecording` is exposed or considered as a default, **Parakeet `transcribeWhileRecording` should only be auto-suggested on `>= 6 GB` RAM devices** (and consider raising `InstalledEngines.lowMemory(context, durationMs = null)`'s free-memory guard on `< 6 GB` handsets so manual Parakeet selection yields precompute with `LOW_MEMORY` before `TRIM_MEMORY_RUNNING_CRITICAL`). For **Whistle (`INCLUDED`)** on 4 GB ARMv8.2-A `asimddp` API 29+ devices, device-wide `MemAvailable` headroom (`>= 1,090 MiB`, `0` low-memory/trim events) is encouraging, while final 4 GB memory sign-off remains **pending isolated `WhistleService` worker RSS measurement**.
2. **Thermal throttling threshold (`ThermalMonitor`: `PowerManager.THERMAL_STATUS_SEVERE` = code `3`) — keep unchanged.**
   - Neither Pixel 8 nor Samsung Galaxy A15 exceeded `THERMAL_STATUS_NONE` (`0`) across all 5-minute and 30-minute runs, and injected `THERMAL_STATUS_SEVERE` cleanly yielded `RecordingPrecomputeController` (`PrecomputeStopReason.YIELDED`) within `<= 1` window/segment without disturbing capture.
3. **API 26–28 `AacCapture` fallback policy (`CaptureFormat.M4A` → skip precompute, auto-enqueue on Stop) — keep unchanged.**
   - Verified on physical API 26 hardware (`Samsung Galaxy S9+`, Android 8.0.0): `RecordingPrecomputeController` spawned `0` precompute workers while `RecordingController.finish()` automatically queued post-Stop transcription + `.m4a` → `.opus` sealing (`10.97 s`).
4. **Follow-up optimization before flipping Whistle `transcribeWhileRecording` on long recordings (non-blocking for `D-1126`):**
   - **Parakeet** already delivers a **4.53×–4.92× Stop-to-published speedup** on 5-minute recordings (`117.20 s → 25.85 s` on Pixel 8; `185.98 s → 37.78 s` on Galaxy A15) and publishes a **30-minute 3-speaker recording in `56.82 s`** (`68` checkpoints, `2` post-Stop windows) on a 4 GB MediaTek handset.
   - **Whistle** keeps up in real time during a 30-minute recording (`256` checkpoints up to `1,746.8 s`, `641.3 MiB` app-process RSS excluding isolated worker, `48 ms / 1,000 ms` max `OpusCapture` frame interval) and achieves **zero neural ORT prefix re-decodes** at Stop (`256 / 256` cached prefix spans, only `7` neural tail segments, while `WhistleRecognizer` already skips `Sortformer` `onChunk` callbacks during `replaying`). However, post-Stop resume still executes two origin-0 `AudioDecoder(included=true)` 48k→16k FIR passes (`WhistleJobs.prefix` + `TranscriptionJob.decodeAudio`, `1,391.9 s` app CPU) plus `SileroDetector` VAD replay (`509.2 s` worker CPU), resulting in `1,669.57 s` Stop-to-published latency on a 30-minute recording. Checkpointing FIR + `SileroDetector` / `WhistleSpeechGate` state at `positionSamples` so both `WhistleRecognizer` and `AudioDecoder(included=true)` seek past the committed prefix will reduce 30-minute Whistle Stop-to-published latency from `~28 min` to `~15–45 s`.

---

## 5. Hardware Caveats & Manual Follow-Up Pass (for Silvio)

- **USB-powered Device Farm rack caveat (`BatteryManager`):** AWS Device Farm phones remain tethered to USB/AC rack power (`pluggedSource = "USB"` or `"AC"`, `batteryPercent` pinned at `80%` or `100%`). While Pixel 8's fuel gauge (`BATTERY_PROPERTY_CHARGE_COUNTER` / `BATTERY_PROPERTY_CURRENT_NOW`) still resolved net coulomb draw under load (`-82 mAh` during 30-min Whistle recording + `-6 mAh` post-Stop) and Samsung's fuel gauge clamped `chargeDeltaUah = 0` at 100% SoC, an unplugged 30-minute battery `%` delta check (`recordOnly` vs. `transcribeWhileRecording`) on a personal Android handset is recommended before changing any battery-sensitive default.
- **Physical Bluetooth headset disconnect & live cellular PSTN call:** `AndroidPlaybackPlatform` `AudioFocusRequest` (`LOST_FOR_NOW` → `GAINED`), `AudioRecordingCallback` client-silenced pause (`RecordingPause.OTHER_APP`), and `ACTION_AUDIO_BECOMING_NOISY` broadcast handling were all verified on physical handsets; verify once manually with physical Bluetooth earbuds and a live carrier call.

---

## 6. Reproducing Locally & on AWS Device Farm

```bash
# 1. Fast deterministic JVM contract + negative Whistle prefix-cache regression tests (run in ./gradlew ciTest):
./scripts/heavy-lock.sh ./gradlew --no-daemon :app:testDebugUnitTest \
  --tests io.codemyriad.cassini.qualification.HandsetQualificationContractTest

# 2. Check monthly AWS Device Farm spend against the paginated $160.00 D-1126 scheduling denial threshold ($200.00 D89 monthly budget):
AWS_PROFILE=cassini-devicefarm uv run --with boto3 python3 scripts/devicefarm-qualify.py \
  --check-budget-only

# 3. 5-min paired Parakeet & Whistle equivalence + separate thermal & low-memory lanes + lifecycle + AAC control:
AWS_PROFILE=cassini-devicefarm uv run --with boto3 python3 scripts/devicefarm-qualify.py \
  --device-arn arn:aws:devicefarm:us-west-2::device:DE5BD47FF3BD42C3A14BF7A6EFB1BFE7 \
  --pool-name d1126-pixel8-api35 \
  --name d1126-pixel8-api35-5min \
  --test-class "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#qualificationHarnessTelemetryAndPrefixContractSmoke,io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test01HardwareCaptureLifecycleInterruptionsAndAacControl,io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test02PairedFiveMinuteEquivalenceAndPressure" \
  --short-seconds 300 --timeout-minutes 75

# 4. 30-min continuous real-time production OpusCapture qualification (parakeet or whistle):
AWS_PROFILE=cassini-devicefarm uv run --with boto3 python3 scripts/devicefarm-qualify.py \
  --device-arn arn:aws:devicefarm:us-west-2::device:9B74AD95597D4BF39827A4EAD83B9F6D \
  --pool-name d1126-galaxya15-api34 \
  --name d1126-galaxya15-api34-30min-parakeet \
  --test-class "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test03ThirtyMinuteContinuousQualification" \
  --long-seconds 1800 --long-engine parakeet --timeout-minutes 85
```
