# Pre-release gate for 0.2.0 (2026-10-12)

This file records the [pre-release gate](../development.md#pre-release-gate) for Cassini for Android 0.2.0. We did the gate on the release candidate (RC) on 2026-10-12.

## Result

The RC passed all the gate steps that we did. We found no release blocker. One step changed (step 1), and one part of the native code did not operate (zstd-jni). The section [Not done or changed](#not-done-or-changed) gives the details.

## Release candidate

| Item | Value |
| --- | --- |
| File | `cassini-android-0.2.0-rc.apk` |
| SHA-256 | `e8ec0c5373b2ecc28ea2beab03e5fe4f5fad2a4df4389401bcf425ee0a268854` |
| Version | `versionName` 0.2.0, `versionCode` 12 |
| Build | R8 minified. The package flags do not include `DEBUGGABLE`. |
| Signature | Certificate SHA-256 `06b2a95b3a6e6e7cdef618999d37631a18216307def007102980db71cf2387ec` (the beta key, the same as `~/.android/debug.keystore`) |
| Native libraries | `libsherpa-onnx-jni.so`, `libonnxruntime.so`, `libcassini_opus.so` and `libzstd-jni-1.5.7-22.so`, for arm64-v8a and x86_64 |

The RC is not debuggable. Thus instrumentation and `run-as` do not operate on it. We used `adb` to operate the UI: `uiautomator dump`, `input tap`, `input text` and `screencap`. We used logcat and `dumpsys` to get the evidence.

## Devices

| Device | Android | Before the gate | App language |
| --- | --- | --- | --- |
| Emulator `emulator-5554`, AVD `test_phone` (`sdk_gphone64_x86_64`) | 14, API 34 | 0.2.0 debug build, with the models | Not set (English UI) |
| Pixel 8 `49101FDJH002K4` | 17, API 37 | 0.2.0-beta (`versionCode` 11, debug build), with the models | `[it]` |

We installed the RC on the two devices with `adb install -r`. The app data stayed: the earlier notes, the saved voices and the models. No API 35 or 36 AVD exists. Thus we did steps 5 to 8 on the Pixel 8.

## Steps

| Step | Device | Result |
| --- | --- | --- |
| 1. Record, transcribe with speakers, name a speaker, export, transcribe again | Pixel 8 | Pass, with a change (see the text that follows) |
| 2. Music while you record | Pixel 8 | Pass |
| 3. A call pauses the recording | Emulator | Pass |
| 4. The recording stays paused after the call | Emulator | Pass |
| 5. The type `mediaProcessing` | Pixel 8 | Pass |
| 6. A short time limit | Pixel 8 | Done |
| 7. "Paused by Android" | Pixel 8 | Pass |
| 8. Reset the limit, then resume | Pixel 8 | Pass |
| 9. Portable file checks | Host | Pass |
| 10. Results | This file | Done |

### Step 1: record, transcribe, name, export, transcribe again

The recording and the transcription of the recording operated. But the recording held no speech, so we also imported a speech clip.

1. We started a speech clip (52 s, two speakers) in VLC. Then we recorded a note of 56 s in Cassini. Android sent the VLC audio to a Bluetooth headset, so the microphone got only the sound of the room. Cassini transcribed the note: "Verificata", "Nessun parlato". This result is correct for a recording without speech.
2. We imported the same clip as `gate-speech-conversation.opus` with **Importa audio** and the system picker. Cassini transcribed it: "Verificata", "2 voci", 73 words. Cassini gave the name "Silvio" automatically to the first speaker, from a saved voice.
3. We gave the name "Ospite" to "Voce 2" and pushed **Salva il nome**. The transcript showed "Ospite" at each turn of this speaker.
4. With **Salva una copia** and the system picker, we saved three files in Download: the Cassini document `gate-rc-export.opus` (277,609 bytes), `gate-speech-conversation.txt` and `gate-speech-conversation.srt`. The TXT and the SRT files have the names "Silvio" and "Ospite".
5. We pushed **Trascrivi di nuovo**. Cassini made "Versione 2 di 2 · trascritta di nuovo", with "Verificata" and "2 voci".

Logcat of the Cassini process (PID 9763, from the install to the end of the gate) shows no crash. It shows no `UnsatisfiedLinkError`, no `ClassNotFoundException`, no `NoSuchMethodError` and no `NoSuchFieldError`. The process stayed alive for all the steps on the Pixel. Logcat shows that the RC loaded these sherpa-onnx parts through JNI: `Vad`, `OfflineSpeakerDiarization` and `SpeakerEmbeddingExtractor`. The sealed document shows that Parakeet recognized the words and that `libcassini_opus` encoded the audio (step 9).

On the emulator, the RC also transcribed the FLEURS clip `fleurs-it-short.mp3` correctly ("1 speaker", the full Italian sentence). Thus the native code of x86_64 also operates in the minified build.

Observation, not a blocker: version 2 shows "Voce 2", not "Ospite". The name "Silvio" stayed, because it comes from a saved voice. The naming sheet for "Voce 2" showed "Troppo poco parlato per ricordare questa voce". The manifest of the document has `"speakersWithPrints": 1`. Thus "Voce 2" had no voiceprint, and Transcribe again cannot carry its name. But the actions sheet said "I nomi delle voci vengono mantenuti." This text shows when one speaker has a name, also if another named speaker has no voiceprint. The text can give a wrong expectation. A follow-up issue can examine this text.

### Step 2: music while you record

We started `gate-music-4min.mp3` in VLC with `ACTION_VIEW` and a content URI. `dumpsys media_session` showed `state=PLAYING(3)` for VLC. Then we started a recording in Cassini and recorded for 54 s.

- The music continued. VLC stayed at `state=PLAYING(3)` for all the recording. `dumpsys audio` showed the VLC `AudioTrack` with `state:started`.
- Cassini held no audio focus. The focus stack had only VLC, with `loss: none`. The focus log has no request from Cassini after the RC install.
- `dumpsys audio` showed the capture `src:MIC not silenced pack:org.cassini.android` and `silenced:false`. The counter increased from 3 s to 36 s in 33 s.

The recording of step 1 gave the same evidence with the speech clip.

### Steps 3 and 4: a call pauses the recording

We started a recording on the emulator. Then we sent `emu gsm call 5551234` and `emu gsm accept 5551234`. `mCallState` changed to 2.

- The recording paused and showed "Paused — another app is using audio" and "Resume when your call ends. Nothing was lost." `dumpsys audio` showed `rec update ... silenced` and then `rec stop ... silenced`.
- We sent `emu gsm cancel 5551234`. `mCallState` changed to 0. After 25 s, the recording still showed "Paused — another app is using audio", and the counter stayed at 17 s.
- We pushed **Resume**. `dumpsys audio` showed `rec start ... not silenced`, and the counter increased to 25 s. Then we pushed **Finish**. The note opened with "Verified" and "No speech", because the emulator has no audio input.

### Steps 5 to 8: the time limit of Android

1. During the transcription of step 1, `dumpsys activity services org.cassini.android` showed `ProcessingService` with `isForeground=true` and `types=0x00002000` (`mediaProcessing`).
2. We set the limit with `device_config put activity_manager media_processing_fgs_timeout_duration 60000`. The value before the change was `null`.
3. We imported `gate-long-15min.opus` (15 min of Italian speech) and started the transcription at 15:57:23. The service showed `types=0x00002000` again. Then we went to the home screen.
4. At 15:58:42, logcat showed `FGS (mediaProcessing) timed out: ServiceRecord{... org.cassini.android/.processing.ProcessingService ...}`. Cassini posted the notification "Trascrizione sospesa da Android", "In pausa a 03:04 di 15:00. Android limita il lavoro in background a circa 6 ore al giorno. Apri Cassini per riprendere." The notification has `org.cassini.android.pausedByAndroid=true`. After the timeout, the service was not in the list.
5. The Note screen showed the banner "In pausa a 03:04 di 15:00" with **Riprendi** and **Ricomincia**, and the draft transcript. It showed no error.
6. We reset the limit with `device_config delete activity_manager media_processing_fgs_timeout_duration`. The value is `null` again.
7. We pushed **Riprendi** on the Note screen. The transcription continued from 20 %, as `mediaProcessing` again. It finished with "Verificata". The document information shows "Trascritta su questo telefono in 2 min 42 s · 5,5× il tempo reale" and "Ripresa da 03:04".

### Step 9: portable file checks

We copied `gate-rc-export.opus` from Download on the Pixel 8 to the host. Its SHA-256 is `3038a1a37de4f030a8761548b26e0791a1542d074e3739f6146d24267e0c5d10`.

| Tool | Result |
| --- | --- |
| `cassini-extract.py --check` | Exit 0. `manifest ok kind=cassini-portable-meeting version=1 profile=ogg-opus` and `words ok 73 items`. |
| `cassini-opus-digest.py` | Exit 0. The audio SHA-256 `fec43451…080dfc` is equal to `opusAudioSha256` in the manifest. 48 kHz, 1 channel, 52,522 ms. |
| `cassini-read-pure.py` | Exit 0. 73 words. |
| `node scripts/check-portable-document.mjs` (Node 24.16.0, `../gocassini`) | Exit 0. Title "gate-speech-conversation", 73 words, 52,522 ms. |

The manifest gives Parakeet TDT, sherpa-onnx 1.13.7, Sortformer with 2 speakers, CAM++ with 1 automatic name, and the labels "Silvio" and "Ospite".

## Not done or changed

- Step 1 changed. The microphone of the Pixel 8 did not get speech, because a Bluetooth headset got the audio. We did not disconnect the headset of the owner, and we did not use the loudspeaker of the host. Thus the speaker names and the export come from an imported clip, not from a recording. The import and the recording use the same transcription engine.
- The gate did not operate zstd-jni. Cassini uses zstd only when it downloads a model. The two devices already had the models, and the gate must not delete them. `libzstd-jni-1.5.7-22.so` is in the APK, and the keep rules come from `engine/proguard-consumer-rules.pro`. A model download on a clean install of the RC will close this gap.
- We did not do steps 5 to 8 on an API 35 or 36 AVD, because none exists. The Pixel 8 (API 37) has the same time limit.

## Device state after the gate

- The RC (`versionCode` 12) stays installed on the two devices. The app language of the Pixel 8 stays `[it]`.
- `media_processing_fgs_timeout_duration` is `null` again.
- We did `svc power stayon false`. Before the gate, `stay_on_while_plugged_in` was 2 (USB). It is 0 after the gate.
- VLC is paused. The gate files stay in Download on the Pixel 8: `gate-music-4min.mp3`, `gate-speech-conversation.mp3`, `gate-speech-conversation.opus`, `gate-long-15min.opus`, `gate-rc-export.opus`, `gate-speech-conversation.txt` and `gate-speech-conversation.srt`.
- Cassini has four new notes on the Pixel 8 and two new notes on the emulator.
- We stopped the emulator.

The logcat files, the `dumpsys` files, the screenshots and the exports are in `cassini-release-candidate/gate/` on the host. They are not in the repository.
