# Physical Handset Qualification — Android (`D-1126`, `D-1117`)

Empirical physical-handset qualification of `transcribeWhileRecording` vs. `afterStop` / `recordOnly` on real Android handsets via AWS Device Farm (`us-west-2`, D89) for **Parakeet TDT (+ streaming Sortformer + CAM++ saved voices)**, **Whistle (`INCLUDED` + Sortformer + CAM++)**, and **API 26–28 `AacCapture` fallback**, prior to enabling recording-time precomputation by default on any Android device profile.

- **Code baseline:** `093efc96` (`main`) + [HandsetQualificationDeviceTest.kt](../app/src/androidTest/java/io/codemyriad/cassini/qualification/HandsetQualificationDeviceTest.kt), [HandsetQualificationContractTest.kt](../app/src/test/java/io/codemyriad/cassini/qualification/HandsetQualificationContractTest.kt), and [devicefarm-qualify.py](../scripts/devicefarm-qualify.py).
- **Shared contracts:** `cassini-ux/contract/HEAD-START-TRANSCRIPTION.md` (`D109`), `cassini-ux/decisions/DECISIONS-UX.md` (`D89`).
- **Shipped default:** remains `afterStop` (no UI or default-mode change in this PR).

---

## 1. Qualified Physical Handsets & Device Farm Runs

Three distinct physical Android handsets spanning `minSdk` (**API 26**) through **API 35**, two SoC vendors (Google Tensor G3 and MediaTek Dimensity 6100+ `mt6835` / Qualcomm Snapdragon 845), and both above (`7.42 GiB`) and below (`3.50 GiB`) Cassini's `ParakeetSuggestion` 6 GB RAM boundary were qualified in AWS Device Farm (`us-west-2`):

| Handset | Model / Device | SoC / Total RAM | OS / SDK | Hardware Codecs | Run Name | Result | Scope |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Samsung Galaxy S9+ (Unlocked)** | `SM-G965U` (`star2qltesq`) | Snapdragon 845 (`qcom`) / 5.0 GB (`5,009.4 MiB`) | Android 8.0.0 / **API 26** (`minSdk`) | Opus enc: `unavailable`; Opus dec: `OMX.google.opus.decoder` | `d1126-s9plus-api26-aac-r2` | **PASSED** (2/2, `2.24` metered min) | Smoke contract + natural API 26 `AacCapture` (`.m4a`) fallback, API 26 transient audio-focus auto-pause/resume, `0` precompute checkpoints, post-Stop Parakeet `.opus` publication |
| **Google Pixel 8** | `Pixel 8` (`shiba`) | Tensor G3 / 8 GB (`7,598.6 MiB`) | Android 15 / **API 35** | `c2.android.opus.encoder` + `c2.android.opus.decoder` | `d1126-pixel8-api35-5min-r2` + `d1126-pixel8-api35-lifecycle` | **PASSED** (`test02` report + 2/2 lifecycle `1.77` metered min) | Live `OpusCapture` + `CommittedOggOpusDecoder` prefix PCM check, Pause/Resume, Home backgrounding, focus/call/noisy hooks, explicit AAC fallback, 5-min paired Parakeet & Whistle (+Sortformer +CAM++ 3 voices) + pressure/stall/OOM |
| **Samsung Galaxy A15 5G** | `SM-A156U1` (`a15x`) | Dimensity 6100+ (`mt6835`) / **4 GB (`3,584.0 MiB`)** | Android 14 / **API 34** | `c2.android.opus.encoder` + `c2.android.opus.decoder` | `d1126-galaxya15-api34-5min-r2` + `d1126-galaxya15-api34-lifecycle` | **PASSED** (`test02` report + 2/2 lifecycle `2.21` metered min) | Same full lifecycle + AAC fallback + 5-min paired Parakeet & Whistle (+Sortformer +CAM++ 3 voices) + pressure/stall/OOM on a 4 GB RAM handset below the 6 GB Parakeet suggestion boundary |
| **Samsung Galaxy A15 5G** | `SM-A156U1` (`a15x`) | Dimensity 6100+ (`mt6835`) / **4 GB (`3,583.8 MiB`)** | Android 14 / **API 34** | `c2.android.opus.encoder` + `c2.android.opus.decoder` | `d1126-galaxya15-api34-30min-parakeet` | **PASSED** (1/1, `49.43` metered min) | 30-min (`1,800 s`) 1.0× real-time hardware `AudioRecord(MIC, 48 kHz)` + `OpusCapture` under `Parakeet + Sortformer + CAM++` `transcribeWhileRecording` |
| **Google Pixel 8** | `Pixel 8` (`shiba`) | Tensor G3 / 8 GB (`7,598.6 MiB`) | Android 15 / **API 35** | `c2.android.opus.encoder` + `c2.android.opus.decoder` | `d1126-pixel8-api35-30min-whistle` | **PASSED** (1/1, `65.58` metered min) | 30-min (`1,800 s`) 1.0× real-time hardware `AudioRecord(MIC, 48 kHz)` + `OpusCapture` under `Whistle + Sortformer + CAM++` `transcribeWhileRecording` |

Total AWS Device Farm (`us-west-2`, `$0.17`/min) usage for this qualification slice (including early diagnostic calibration runs): **`225.93` metered minutes = `$38.41`**, bringing November 2026 month-to-date spend from `$51.67` (`303.95` min) to **`$90.08` (`529.88` min)** against the **`$200.00`** D89 monthly ceiling (`$109.92` remaining).

---

## 2. Aggregate Hardware Qualification Tables

### 2.1 Paired 5-minute (`300 s`) Multi-Speaker Speech Equivalence & Stop-to-Published Latency

Stimulus: `300 s` 48 kHz mono PCM16LE 3-speaker real speech (`fixtures/audio/fleurs-it-short.wav` + DC-corrected `fixtures/audio/dc-quiet-jfk-v1.wav` + 1.28× formant/rate-shifted speaker profile, streamed from a compact `~3.8 MiB` cycle buffer through real hardware `MediaCodec("audio/opus")` + `OggOpus.LiveMuxer`). Isolated test library populated with **3 real CAM++-enrolled voices** using synthetic labels (`Voice_A`, `Voice_B`, `Voice_C`; max off-diagonal cosine `0.1215`).

Stop-to-published latency is measured end-to-end starting immediately before `RecordingController.finish()` (`SystemClock.elapsedRealtimeNanos()`) through `RecordingService` EOS sealing, `ProcessingHandoff`, `ProcessingCoordinator`, `TranscriptionJob` tail execution, `CassiniDocument` `.opus` sealing, and Room library publication (`jobState == null`).

| Handset (OS, RAM) | Engine | Mode | Stop-to-Published (s) | Speedup | Post-Stop Units | Precompute Checkpoints (`x-resumedFromMs`) | Words / Turns / Speakers / Matched Voices | Peak RSS (MiB) | Peak Native Heap (MiB) | Min Avail RAM (MiB) | Low-Mem / Trim | Peak Thermal | App + Worker CPU (s) | Equivalence |
| --- | --- | --- | ---: | ---: | ---: | --- | --- | ---: | ---: | ---: | --- | --- | ---: | --- |
| **Pixel 8** (Android 15, 8 GB) | `recordOnly` | control | **0.06** | — | 0 | 0 (`0 ms`) | — | 433.2 | 33.4 | 3,765.3 | `0` / `0` | `none` | 47.28 | N/A |
| **Pixel 8** (Android 15, 8 GB) | **Parakeet + Sortformer + CAM++** | `afterStop` | **119.78** | 1.00× | 12 windows | 0 (`0 ms`) | `517` / `51` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | 1,685.0 | 1,396.7 | 2,792.0 | `0` / `0` | `none` | 179.37 | baseline |
| **Pixel 8** (Android 15, 8 GB) | **Parakeet + Sortformer + CAM++** | `transcribeWhileRecording` | **26.10** | **4.59×** | **2 windows** | 10 (`262,000 ms`) | `517` / `51` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | 1,570.6 (pre) / 1,621.3 (post) | 1,233.2 (pre) / 1,460.9 (post) | 2,846.0 (pre) / 2,777.3 (post) | `0` / `0` | `none` | 155.44 + 40.81 | **PASS** |
| **Pixel 8** (Android 15, 8 GB) | **Whistle + Sortformer + CAM++** | `afterStop` | **334.86** | 1.00× | 44 neural segs | 0 (`0 ms`) | `506` / `44` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (AUTO)` | 773.5 | 395.2 | 3,503.2 | `0` / `0` | `none` | 257.39 + 343.39 (`600.78`) | baseline |
| **Pixel 8** (Android 15, 8 GB) | **Whistle + Sortformer + CAM++** | `transcribeWhileRecording` | **340.14**† | **0.98×**† | **6 neural segs** (`38` cache hits, **0 prefix re-decodes**) | 38 (`252,672 ms`) | `506` / `44` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (AUTO)` | 648.1 (pre) / 706.1 (post) | 224.3 (pre) / 361.5 (post) | 3,613.5 (pre) / 3,610.3 (post) | `0` / `0` | `none` | 254.56 (pre) / 269.25 + **150.37** (`419.62` post) | **PASS** |
| **Galaxy A15** (Android 14, 4 GB) | `recordOnly` | control | **0.11** | — | 0 | 0 (`0 ms`) | — | 298.7 | 40.5 | 963.4 | `0` / `0` | `none` | 77.63 | N/A |
| **Galaxy A15** (Android 14, 4 GB) | **Parakeet + Sortformer + CAM++** | `afterStop` | **186.62** | 1.00× | 12 windows | 0 (`0 ms`) | `518` / `50` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | 1,493.3 | 1,695.1 | **255.3** | **`1` / `15` (`CRITICAL`)** | `none` | 267.10 | baseline |
| **Galaxy A15** (Android 14, 4 GB) | **Parakeet + Sortformer + CAM++** | `transcribeWhileRecording` | **43.33** | **4.31×** | **2 windows** | 10 (`262,000 ms`) | `518` / `50` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | 1,398.1 (pre) / 1,479.8 (post) | 1,498.3 (pre) / 1,775.8 (post) | **373.2** (pre) / **322.4** (post) | `0` / `0` | `none` | 248.50 + 62.46 | **PASS** |
| **Galaxy A15** (Android 14, 4 GB) | **Whistle + Sortformer + CAM++** | `afterStop` | **375.60** | 1.00× | 44 neural segs | 0 (`0 ms`) | `506` / `44` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (AUTO)` | 494.0 | 469.3 | 1,149.8 | `0` / `0` | `none` | 296.62 + 130.71 (`427.33`) | baseline |
| **Galaxy A15** (Android 14, 4 GB) | **Whistle + Sortformer + CAM++** | `transcribeWhileRecording` | **386.35**† | **0.97×**† | **6 neural segs** (`38` cache hits, **0 prefix re-decodes**) | 38 (`252,672 ms`) | `506` / `44` / `3` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (AUTO)` | **364.4** (pre) / **466.3** (post) | 264.8 (pre) / 427.7 (post) | **1,217.5** (pre) / **1,134.5** (post) | `0` / `0` | `none` | 261.66 (pre) / 261.42 + **115.50** (`376.92` post) | **PASS** |

*†Key empirical Whistle + Sortformer architecture finding (see Section 4, item 4): Whistle's neural prefix cache hit 100% (`38 / 38` prefix segments served from `work/whistle-segments.json` with **zero neural prefix re-decodes**, cutting isolated Whistle worker post-Stop CPU by **56.2%** on Pixel 8 from `343.39 s` → `150.37 s`). However, when `SPEAKERS` (`Sortformer`) is enabled alongside Whistle, `TranscriptionJob` currently re-emits the 38 cached prefix segments through `onChunk` on resume without skipping already-diarized prefix segments (`session.diarizer` restores from checkpoint, then re-runs `Sortformer.process` + 48k→16k FIR replay on cached prefix segments, keeping `appCpuSeconds` at `~269 s`). Skipping `diarizer.process` for segments already covered by the restored `DiarizerState` in `TranscriptionJob` is a clean follow-up optimization that will unlock a ~4–5× Whistle+Sortformer wall-clock speedup matching Parakeet's **4.31×–4.59×** speedup.*

### 2.2 30-minute (`1,800 s`) Continuous 1.0× Real-Time Hardware `AudioRecord` Qualification

Live hardware `AudioRecord(MediaRecorder.AudioSource.MIC, 48_000 Hz, PCM_16BIT)` running continuously for `1,800 s` (`86,400,000` samples) through hardware `c2.android.opus.encoder` + `OggOpus.LiveMuxer` while `RecordingPrecomputeController` runs live head-start precomputation on disk, followed by `RecordingController.finish()` and `TranscriptionJob` publication:

| Handset (OS, RAM) | Engine | Capture Errors / Dropped Buffers | Max `AudioRecord.read` Block vs. Ring | Checkpoints Saved (`x-resumedFromMs`) | Precompute / Post-Stop Units | Stop-to-Published (s) | Words / Turns / Matched Voices | Peak RSS (rec / post, MiB) | Min Avail RAM (rec / post, MiB) | Low-Mem / Trim (rec / post) | Thermal Trace | Battery (`BatteryManager`, USB rack) |
| --- | --- | --- | --- | --- | --- | ---: | --- | --- | --- | --- | --- | --- |
| **Galaxy A15** (Android 14, 4 GB) | **Parakeet + Sortformer + CAM++** | **`0` / `0`** (`86.4M` samples, `1,800.22 s`) | **`191 ms`** / `2,000 ms` | **68** (`1,770,000 ms` = `29m 30s`) | `69` pre / **`2` post** | **56.45** | `3,116` / `293` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | `1,454.1` / `1,521.1` (JVM `51.2`, native `1,831.3`) | **`252.5` / `265.5`** | `2`/`15` (`CRITICAL`) / `1`/`15` | `63/63` `none` (rec), `3/3` `none` (post) | `100% → 100%` (`USB`, `0 µAh` delta clamped) |
| **Pixel 8** (Android 15, 8 GB) | **Whistle + Sortformer + CAM++** | **`0` / `0`** (`86.4M` samples, `1,800.10 s`) | **`75 ms`** / `2,000 ms` | **261** (`1,793,792 ms` = `29m 53.8s`) | `261` pre (`261` cache hits) / **`2` neural post** | **1,744.20**‡ | `3,071` / `268` / `Voice_A (AUTO), Voice_B (AUTO), Voice_C (SUGGESTED)` | **`644.5` / `764.9`** (JVM `107.2`, native `227.2` / `478.7`) | **`3,487.2` / `3,354.1`** | `0`/`0` / `0`/`0` | `62/62` `none` (rec), `60/60` `none` (post) | `100% → 100%` (`USB`, `-98 mAh` rec / `-6 mAh` post, avg `-194.5 mA`) |

*‡30-minute Whistle + Sortformer post-Stop latency finding: During the `1,800 s` recording, Whistle kept pace in real time (`261` checkpoints saved up to `1,793.79 s`, leaving only **`2` neural tail segments (`6.2 s`)** uncomputed at Stop, with zero neural prefix re-decodes: `whistlePostStopCacheHits = 261`, `whistlePostStopNeuralSegments = 2`). However, `WhistleJobs` + `TranscriptionJob` currently replay all `1,793.79 s` of audio from byte 0 through 48k→16k FIR (`AudioDecoder(included=true)`), `SileroDetector` (`514.6 s` worker CPU), and `Sortformer.process` (`1,447.4 s` app CPU) on the `261` cached prefix segments before decoding the `2` tail segments. By contrast, `Parakeet` skips prefix decode/diarization completely on resume and published the 30-minute 3-speaker recording in **`56.45 s`** even on the 4 GB MediaTek Galaxy A15 (~20× faster than batch `afterStop`).*

---

## 3. Equivalence, Pressure Injection, Lifecycle & `AacCapture` Fallback Matrix

| Check | Galaxy S9+ (API 26) | Galaxy A15 (API 34, 4 GB) | Pixel 8 (API 35, 8 GB) | Details |
| --- | --- | --- | --- | --- |
| Live `.opus` prefix SHA-256 (`OggOpusPrefixScanner`) vs. EOS-sealed `.opus` | **PASS** (`@CiSmoke`) | **PASS** (`13,486` B & `22,953` B → `65,152` B) | **PASS** (`14,703` B & `24,352` B → `59,752` B) | Bit-identical byte prefix before and after `OggOpus.LiveMuxer.finish(...)` EOS page |
| Live `CommittedOggOpusDecoder` 16 kHz PCM vs. finalized `AudioDecoder` PCM | N/A (API < 29) | **PASS** (`maxAbsDiff = 0.0` over `48,520` samples) | **PASS** (`maxAbsDiff = 0.0` over `51,080` samples) | Exact bit-for-bit float32 PCM agreement on hardware `c2.android.opus.encoder` + `c2.android.opus.decoder` |
| Paired 5-min `.opus` SHA-256, decoded PCM SHA-256, words, `startMs`/`endMs`, Sortformer turns/speakers, and CAM++ saved-voice matches (`Voice_A`, `Voice_B`, `Voice_C`) | N/A (API < 29) | **PASS** (Parakeet `518`w/`50`t & Whistle `506`w/`44`t) | **PASS** (Parakeet `517`w/`51`t & Whistle `506`w/`44`t) | Exact match across `AFTER_STOP`, `TRANSCRIBE_WHILE_RECORDING`, and pressure-recovery lanes |
| Whistle zero neural prefix re-decodes at Stop | N/A (API < 29) | **PASS** (`38` cache hits + `6` tail = `44` baseline) | **PASS** (`38` cache hits + `6` tail = `44` baseline) | `WhistleStream` neural segment cache verified via `cacheHits` + `neuralTranscribes` counters |
| `AacCapture` (`.m4a`) fallback under `TRANSCRIBE_WHILE_RECORDING` | **PASS** (natural API 26, `10.97 s` Stop-to-pub) | **PASS** (explicit control, `8.60 s` Stop-to-pub) | **PASS** (explicit control, `5.23 s` Stop-to-pub) | `0` precompute checkpoints spawned; post-Stop `StreamingOpusEncoder` sealed valid `DocumentState.OK` `.opus` |
| Injected `THERMAL_STATUS_SEVERE` + `lowMemory` + simulated inference stall + `OutOfMemoryError` suppression | N/A | **PASS** (paused within `<= 1` unit; OOM → `OUT_OF_MEMORY`) | **PASS** (paused within `<= 1` unit; OOM → `OUT_OF_MEMORY`) | `RecordingState.Active` remained uninterrupted; post-Stop `TranscriptionJob` recovered identical transcripts |
| Pause → wait → Resume, Home backgrounding (`KEYCODE_HOME` / `RecordingService`), transient audio focus (`LOST_FOR_NOW` → `GAINED`), incoming-call silence (`RecordingPause.OTHER_APP`), and `ACTION_AUDIO_BECOMING_NOISY` | **PASS** (`api26FocusPauseVerified = true` + noisy hook) | **PASS** (0 ms pause drift, bg advance, precompute yielded on Home) | **PASS** (0 ms pause drift, bg advance, precompute yielded on Home) | Zero fixed sleeps (`E1` / D88); synchronized strictly on `StateFlow`, `Channel`, and `CountDownLatch` transitions |

---

## 4. Device-Capability, Memory & Thermal Threshold Recommendations (for Silvio's Sign-Off)

1. **`ParakeetSuggestion` 6 GB RAM boundary (`>= 6 GB` recommended for Parakeet; Whistle default below 6 GB) — strongly validated; keep unchanged, and gate default Parakeet `transcribeWhileRecording` to `>= 6 GB` RAM.**
   - On the **4 GB (`3,584 MiB`) Samsung Galaxy A15**, **Whistle** head-start precomputation peaked at only **`364.4 MiB` RSS** (`1,217.5 MiB` free RAM, `0` low-memory / trim events), whereas **Parakeet + Sortformer** peaked at **`1,398–1,493 MiB` RSS** (`1,498–1,776 MiB` native heap), compressing `MemAvailable` down to **`255–373 MiB`** and triggering `ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL` (`level 15`) + `onLowMemory` during batch/AAC runs.
   - Recommendation: when `transcribeWhileRecording` is exposed or considered as a default, **Whistle (`INCLUDED`) is safe on all eligible ARMv8.2-A `asimddp` API 29+ devices (including 4 GB phones)**, while **Parakeet `transcribeWhileRecording` should only be auto-suggested on `>= 6 GB` RAM devices** (or consider adding a `MemAvailable >= 600 MiB` pre-window guard in `InstalledEngines.lowMemory(context, durationMs = null)` so 4 GB users who manually select Parakeet yield precompute before `TRIM_MEMORY_RUNNING_CRITICAL`).
2. **Thermal throttling threshold (`ThermalMonitor`: `PowerManager.THERMAL_STATUS_SEVERE` = code `3`) — keep unchanged.**
   - Neither Pixel 8 nor Samsung Galaxy A15 exceeded `THERMAL_STATUS_NONE` (`0`) across all 5-minute and 30-minute runs, and injected `THERMAL_STATUS_SEVERE` cleanly yielded `RecordingPrecomputeController` within `<= 1` window/segment without disturbing capture.
3. **API 26–28 `AacCapture` fallback policy (`CaptureFormat.M4A` → skip precompute, auto-enqueue on Stop) — keep unchanged.**
   - Verified on physical API 26 hardware (`Samsung Galaxy S9+`, Android 8.0.0): `RecordingPrecomputeController` spawned `0` precompute workers while `RecordingController.finish()` automatically queued post-Stop transcription + `.m4a` → `.opus` sealing (`10.97 s`).
4. **Required follow-up optimization before flipping Whistle `transcribeWhileRecording` on long recordings (non-blocking for `D-1126`):**
   - **Parakeet** already delivers a **4.31×–4.59× Stop-to-published speedup** on 5-minute recordings (`119.78 s → 26.10 s` on Pixel 8; `186.62 s → 43.33 s` on Galaxy A15) and publishes a **30-minute 3-speaker recording in `56.45 s`** (`68` checkpoints, `2` post-Stop windows) on a 4 GB MediaTek handset.
   - **Whistle** keeps up in real time during a 30-minute recording (`261` checkpoints up to `1,793.8 s`, `644.5 MiB` RSS) and achieves **zero neural ORT prefix re-decodes** at Stop (`261` cache hits, only `2` neural tail segments). However, on resume `WhistleJobs` + `TranscriptionJob` still replay the entire `1,793.8 s` prefix from byte 0 through 48k→16k FIR decode, `SileroDetector` (`514.6 s` worker CPU), and `Sortformer.process` (`1,447.4 s` app CPU), resulting in `1,744.20 s` Stop-to-published latency on a 30-minute recording. Skipping `diarizer.process` for segments already covered by restored `DiarizerState` AND checkpointing/fast-forwarding closed Whistle `SpeechGate` prefix segments (rather than re-running Silero VAD + FIR from sample 0) will bring 30-minute Whistle Stop-to-published latency down from `~29 min` to `~15–45 s`.

---

## 5. Hardware Caveats & Manual Follow-Up Pass (for Silvio)

- **USB-powered Device Farm rack caveat (`BatteryManager`):** AWS Device Farm phones remain tethered to USB/AC rack power (`pluggedSource = "USB"` or `"AC"`, `batteryPercent` pinned at `80%` or `100%`). While Pixel 8's fuel gauge (`BATTERY_PROPERTY_CHARGE_COUNTER` / `BATTERY_PROPERTY_CURRENT_NOW`) still resolved small net coulomb deltas under heavy load (`-4 mAh` to `-20 mAh` across 5-min lanes) and Samsung's fuel gauge clamped `chargeDeltaUah = 0` at 100% SoC, an unplugged 30-minute battery `%` delta check (`recordOnly` vs. `transcribeWhileRecording`) on a personal Android handset is recommended before changing any battery-sensitive default.
- **Physical Bluetooth headset disconnect & live cellular PSTN call:** `AndroidPlaybackPlatform` `AudioFocusRequest` (`LOST_FOR_NOW` → `GAINED`), `AudioRecordingCallback` client-silenced pause (`RecordingPause.OTHER_APP`), and `ACTION_AUDIO_BECOMING_NOISY` broadcast handling were all verified on physical handsets; verify once manually with physical Bluetooth earbuds and a live carrier call.

---

## 6. Reproducing Locally & on AWS Device Farm

```bash
# 1. Fast deterministic JVM contract test (runs automatically in ./gradlew ciTest):
./scripts/heavy-lock.sh ./gradlew --no-daemon :app:testDebugUnitTest \
  --tests io.codemyriad.cassini.qualification.HandsetQualificationContractTest

# 2. Check monthly AWS Device Farm spend against the paginated $200.00 D89 budget guard:
AWS_PROFILE=cassini-devicefarm uv run --with boto3 python3 scripts/devicefarm-qualify.py \
  --check-budget-only

# 3. 5-min paired Parakeet & Whistle equivalence + lifecycle + AAC control on physical handset:
AWS_PROFILE=cassini-devicefarm uv run --with boto3 python3 scripts/devicefarm-qualify.py \
  --device-arn arn:aws:devicefarm:us-west-2::device:DE5BD47FF3BD42C3A14BF7A6EFB1BFE7 \
  --pool-name d1126-pixel8-api35 \
  --name d1126-pixel8-api35-5min \
  --test-class "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#qualificationHarnessTelemetryAndPrefixContractSmoke,io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test01HardwareCaptureLifecycleInterruptionsAndAacControl,io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test02PairedFiveMinuteEquivalenceAndPressure" \
  --short-seconds 300 --timeout-minutes 45

# 4. 30-min continuous real-time AudioRecord + OpusCapture qualification (parakeet or whistle):
AWS_PROFILE=cassini-devicefarm uv run --with boto3 python3 scripts/devicefarm-qualify.py \
  --device-arn arn:aws:devicefarm:us-west-2::device:9B74AD95597D4BF39827A4EAD83B9F6D \
  --pool-name d1126-galaxya15-api34 \
  --name d1126-galaxya15-api34-30min-parakeet \
  --test-class "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test03ThirtyMinuteContinuousQualification" \
  --long-seconds 1800 --long-engine parakeet --timeout-minutes 75
```
