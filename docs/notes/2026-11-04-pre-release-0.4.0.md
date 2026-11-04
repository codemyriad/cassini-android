# Pre-release gate for 0.4.0 (2026-11-04)

This file records the [pre-release gate](../development.md#pre-release-gate) for Cassini talk for Android 0.4.0, the first release of `io.codemyriad.cassini`. We did steps 3 to 9 on an emulator on 2026-11-04. Steps 1 and 2 need a Pixel 8 and are not done yet.

## Result

Steps 3 and 5 to 9 passed. Step 4 passed with one finding: after a call, the first **Resume** pauses the recording again at once, and only a second **Resume** continues it ([Steps 3 and 4](#steps-3-and-4-a-call-pauses-the-recording)). No audio is lost. Steps 1 and 2 are still to do.

## Release candidate

| Item | Value |
| --- | --- |
| File | `app-release.apk`, built from `release-0.4.0` at 3bd7772 (#69) |
| SHA-256 | `b61b3ea513a8dc48734b7d4eb38b39c35698557b5c2ab4533272ddd773cd24e0` |
| Version | `versionName` 0.4.0, `versionCode` 14, `io.codemyriad.cassini` |
| Build | R8 minified. The package flags do not include `DEBUGGABLE`, and `run-as` refuses the package. |
| Signature | Certificate SHA-256 `06b2a95b3a6e6e7cdef618999d37631a18216307def007102980db71cf2387ec` (the local debug keystore, through the four `CASSINI_RELEASE_*` variables). The tag build signs with the release key instead. |
| Native libraries | `libsherpa-onnx-jni.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`, `libonnxruntime.so`, `libcassini_opus.so`, `libzstd-jni-1.5.7-25.so`, `libdatastore_shared_counter.so` and `libandroidx.graphics.path.so`, for `arm64-v8a` and `x86_64` |

The RC is not debuggable, so we drove the UI with `adb`: `uiautomator dump`, `input tap` and `screencap`. The evidence comes from `dumpsys` and logcat.

## Device

| Device | Android | Before the gate | App language |
| --- | --- | --- | --- |
| Emulator `emulator-5554`, AVD `cassini-api35` (Google APIs, `arm64-v8a`, Pixel 5 profile, 4 GB) on drmeyers | 15, API 35 | 0.4.0 debug build (#69), with the models from `scripts/push-models.sh` | Not set (English UI) |

We installed the RC over the debug build with `adb install -r`. The models stayed.

## Steps

| Step | Device | Result |
| --- | --- | --- |
| 1. Record, transcribe with speakers, name a speaker, export, transcribe again | Pixel 8 | Not done yet |
| 2. Music while you record | Pixel 8 | Not done yet |
| 3. A call pauses the recording | Emulator | Pass |
| 4. The recording stays paused after the call | Emulator | Pass, with a finding (see the text that follows) |
| 5. The type `mediaProcessing` | Emulator (API 35) | Pass |
| 6. A short time limit | Emulator (API 35) | Done |
| 7. "Paused by Android" | Emulator (API 35) | Pass |
| 8. Reset the limit, then resume | Emulator (API 35) | Pass |
| 9. Portable file checks | Host | Pass |
| 10. Results | This file | Done |

### Steps 3 and 4: a call pauses the recording

We started a recording. Then we sent `emu gsm call 5551234` and `emu gsm accept 5551234`.

- On the first try, telecom dropped the incoming call 91 ms after it made it: `SET_DISCONNECTED`, "Found no ringing call, INCOMING_MISSED". The modem still reported `mCallState=2`, but the audio mode stayed `MODE_NORMAL`, and Android did not silence the recording. This is the emulator's telephony, not the app. We sent `emu gsm cancel 5551234` and called again. This time we sent `accept` only after `dumpsys telecom` listed the call under "Ringing calls".
- On the second call, the audio mode changed to `MODE_IN_CALL`. `dumpsys audio` showed `rec update ... silenced` and `rec stop ... silenced`. The recording paused at 2:34 and showed "Paused — another app is using audio" and "Resume when your call ends. Nothing was lost."
- We sent `emu gsm cancel 5551234`. `mCallState` changed to 0, and telecom entered `UNFOCUSED`. For 40 s, until we pushed **Resume**, the recording still showed "Paused — another app is using audio", and the counter stayed at 2:34.
- **Finding:** we pushed **Resume** 40 s after the call ended, with the audio mode back at `MODE_NORMAL`. The recording paused again at once. `dumpsys audio` showed `rec start ... silenced`, then `rec update ... not silenced` 1 ms later, then the app's `rec stop` 47 ms later. A second **Resume** gave `rec start ... not silenced`, and the counter increased (2:35 to 2:42). A new recording, a new call and the same steps gave the same three events and the same pause. The recording callback (`AndroidRecordingPlatform.recordingCallback`) passes each `isClientSilenced` to the controller. The first configuration after `startRecording` still carries the silence of the call, so the controller pauses again. The 0.2.0 gate (API 34) did not show this.
- We pushed **Finish**. The note opened with "Verified" and "No speech", because the emulator has no audio input.

### Steps 5 to 8: the time limit of Android

1. We imported `gate-long-16min.wav` (`fleurs-it-x20.wav` of `scripts/make-long-clip.sh` three times, 15:50) with **Import audio** and the system picker. 5 min of audio is not enough: the emulator transcribes at about 14× real time.
2. We set the limit with `device_config put activity_manager media_processing_fgs_timeout_duration 60000`. The value before the change was `null`.
3. We started the transcription at 14:49:50. `dumpsys activity services io.codemyriad.cassini` showed `ProcessingService` with `isForeground=true` and `types=0x00002000` (`mediaProcessing`). Then we went to the home screen.
4. At 14:50:51, logcat showed `FGS (mediaProcessing) timed out: ServiceRecord{... io.codemyriad.cassini/.processing.ProcessingService ...}`. Cassini posted the notification "Transcription paused by Android", "Paused at 14:46 of 15:50. Android limits background work to about 6 hours a day. Open Cassini to resume." The notification has `io.codemyriad.cassini.pausedByAndroid=true`. After the timeout, the service was not in the list.
5. The Note screen showed the banner "Paused at 14:46 of 15:50" with **Resume** and **Start over**, and the draft transcript up to 14:32. It showed no error.
6. We reset the limit with `device_config delete activity_manager media_processing_fgs_timeout_duration`. The value is `null` again.
7. We pushed **Resume**. The transcription continued as `mediaProcessing` and finished with "Verified". Document info shows "Transcribed on this phone in 38 s · 25× realtime" and "Resumed from 14:46". The manifest has `x-resumedFromMs` 886000.

### Step 9: portable file checks

We saved the note of steps 5 to 8 with **Save a copy** › **Cassini document** in Download and copied `gate-long-16min.opus` to the host. Its SHA-256 is `79ec8f898fa95f393bbb46f804d4f431f439daab10eefd65b40c693ec161d8ec`.

| Tool | Result |
| --- | --- |
| `cassini-extract.py --check` | Exit 0. `manifest ok kind=cassini-portable-meeting version=1 profile=ogg-opus` and `words ok 1609 items`. |
| `cassini-opus-digest.py` | Exit 0. The audio SHA-256 `dea9669a…9ac5ef` is equal to `integrity.opusAudioSha256` in the manifest. 48 kHz, 1 channel, 950,400 ms. |
| `cassini-read-pure.py` | Exit 0. It prints the words with their times. |
| `node scripts/check-portable-document.mjs` (Node 26.11.1, `../gocassini` at d5aba38) | Exit 0. Title "gate-long-16min", 1609 words, language `und`, 950,400 ms. |

The manifest gives Parakeet TDT (`moondream/parakeet-ultra INT8`), sherpa-onnx 1.13.7, language `und`, Sortformer diarization with one speaker, "Speaker 1".

## Not done or changed

- Steps 1 and 2 need a Pixel 8 (API 37), and the owner's phone is not used by the agents. The owner does them on the release candidate before the tag.
- On the emulator the first incoming call did not reach telecom (steps 3 and 4). We called again and waited for telecom to ring before the answer.
- For step 4, we pushed **Resume** twice (the finding above).
- The gate did not operate zstd-jni with a download in the app: the models came from `scripts/push-models.sh`. `libzstd-jni-1.5.7-25.so` is in the APK. CI's model-download device test covers the download.

## Device state after the gate

- The RC (`versionCode` 14) stays installed on the AVD, with four new notes: `fleurs-it-x20` (Audio only), `gate-long-16min` (transcribed) and two recordings without speech.
- `media_processing_fgs_timeout_duration` is `null` again.
- The emulated cutout `com.android.internal.display.cutout.emulation.hole` stays on, for the screenshots of the acceptance walk.
- `gate-long-16min.wav`, `fleurs-it-x20.wav` and `gate-long-16min.opus` stay in Download on the AVD.
- We stopped the emulator. It ran only under `~/.cassini-heavy.lock`, from 14:23 to 14:56.

The `dumpsys` files, the screenshots and the export are in the scratch directory of the agent on drmeyers. They are not in the repository.
