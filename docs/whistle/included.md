# Included and the default speech model

Included Whistle is the default for a new transcription when the executing process
is arm64, Android API 29 or later, the real CPU probe reports ASIMDDP, and the
packaged runtime/models pass their existing integrity checks, until Parakeet is
installed and verified (D78). Settings shows
Unavailable elsewhere and retains optional Parakeet. No dispatch override or
runtime download enables Whistle on an unsupported device.

The first verified Parakeet installation makes it the default for new jobs.
A later explicit Whistle choice survives restarts. Removing or invalidating the
installed Parakeet returns new work to Included where available; reinstalling
and verifying promotes Parakeet again. `recognition.parakeetActivated` tracks
that installation transition, separately from `recognition.model`. A choice changes new jobs;
it does not change queued or paused work. Every Room job stores `speech_identity`,
which includes the full engine revision, model/VAD hashes and decoder/gate policy
versions for Whistle. Unknown job identities never select another recognizer.
Checkpoint validation, worker creation, consent and output provenance use this
recorded identity. A rebuild or Trash restore retains a valid checkpoint's model. Trash also
stores the queued job pin before its first checkpoint; restoring never stamps
the current Settings choice over that pin. Older two-field Trash snapshots name
the legacy Parakeet engine, and unknown pins remain unknown for strict refusal.

Whistle's basic path uses bundled VAD and needs no downloaded model or consent.
Without optional speaker/voice models, it uses one speaker and no saved-voice
matching. Settings can download those parts explicitly. That consent does not
authorize Parakeet: `models.speechConsentAt` is recorded only for a download plan
that includes Parakeet. Conversely, Parakeet-only consent never authorizes the
optional speaker downloads, including a retry or the next Parakeet job. Recording
and note storage checks use the same authorized job plan. The speech-only consent
screen uses existing size/privacy copy and does not promise speaker separation.
Consent and installation retain only the shown roles, including across a
notification-permission recreation. Standalone retries keep their original
part set even if earlier consent would allow more models. Failed or offline
optional standalone downloads produce a retry notice rather than silently
looking successful. Existing
installed Parakeet remains usable. Download
screens for a paused job use that job's model, even after the default changes.

## Parakeet recommendation (D78)

Settings offers a calm, dismissible suggestion. After the first completed Whistle
job, the same suggestion appears in the Library and that note. It never opens a
download itself. The action enters the existing consent screen with only the
missing Parakeet/VAD part, and the displayed bytes come from that exact pinned
plan. Optional speaker downloads are excluded. Not now or close persists across
restarts and both surfaces. Per coordinator clarification, the suggestion is also
suppressed wherever Whistle is unavailable, so its starter copy never makes a
false promise. Failed/paused jobs do not count as a first completion.

The suggestion requires a supported 64-bit runtime, at least 6,000,000,000 bytes
of OS-reported total RAM, neither Android low-RAM nor low-memory warning, and
enough actual free disk for the installation plan plus its normal slack. Unknown
capability and downloads being installed/verified suppress it. Disk/memory are
rechecked on screen resume and model-state changes. The 6 GB floor
uses twice the measured Android process RSS high-water plus a 3 GiB reserve for
Android and other apps, rounded up to the next decimal GB: 2 × 1,123,762,176 +
3,221,225,472 = 5,468,749,824 bytes. This is a conservative suggestion policy;
a short emulator measurement does not establish a safe long-meeting phone budget.
No new hard execution gate or user-model deletion is introduced.

The first completion note ID and dismissal are private settings:
`recognition.firstWhistleNote`, `notices.parakeetSuggestionDismissed`.
`notices.parakeetDefault` holds the one-time default-change announcement until
dismissed. Coordinator D78 copy comes from `ux-draft/strings.json` in all six
Android locales. The explicit memory bench measures cold Parakeet + VAD on the
public English DC input; its PSS samples and process RSS high-water mark are
reported separately, with no long-meeting or whole-device guarantee.

The [initial measured run](../../whistle/memory/parakeet-api35.json) on the owned
4 GiB/two-CPU API 35/16 KiB arm64 emulator observed peak app PSS of 985,818 KiB
(962.7 MiB), from 170 samples with at most 58.7 ms between samples. The process
RSS high-water mark was 1,097,424 KiB (1,071.7 MiB). This used Parakeet + VAD,
warm model files, a cold app process, and the public English DC fixture, without
optional speaker models. Artifact/source/model/input/report hashes are retained.
The measured RSS (1.05 GiB) is the larger Android process metric, so the headroom
calculation uses it rather than sampled PSS. The run excludes optional speaker
models and long recordings; its 4 GiB emulator size is not a recommendation floor.
The suggestion now requires 6 GB total RAM; existing low-memory and disk checks
still apply. Devices below that floor are not prompted to download Parakeet.

The decoder policy is explicit: 48 kHz uses `cassini-fir-48k-16k-v1`, 16 kHz is
unchanged, and other supported rates use the established Hann-windowed sinc
conversion. Those other-rate decodes do not claim desktop FIR bit parity.
Whistle decodes from the origin on resume and drops samples only after
resampling; it never creates a false FIR edge at the checkpoint. The recognizer
then rebuilds the saved prefix as described in [the adapter](job-adapter.md).
Stop is checked before opening the worker and between replay blocks. A stopped
replay releases its worker and leaves the prior checkpoint unchanged. A replay
mismatch offers Start over. Worker/capability/asset failures never
substitute Parakeet or start a download.

Whistle's portable transcript records Needle, its actual engine/model revisions,
undetermined language, this decoder policy, and desktop speech-gate segmentation.
Its speaker provenance describes those segments rather than Parakeet's overlapped
28-second windows. Parakeet output and its decoder defaults remain unchanged.

## Pre-public schema switch

D51 permits a clean switch: the version-1 catalogue schema gains the job identity
column and its generated export is committed. No old development-build migration
is added. Testers can reinstall; the existing database opener also sets aside an
incompatible development catalogue and rebuilds from files. Public portable
transcripts keep their original provenance. Future public-release migrations
still require the normal migration tests.

## Verification

JVM checks cover persistent choice/consent, eligibility defaults, durable job
identity, no-download Included policy, typed replay mismatch and exact WAV FIR
resume output. Linux records only intentional Settings screenshot changes.
The owned arm64 worker bench also runs the real default production job in a
fresh scratch graph with a refusing downloader and no models or consent. It
pauses on a checkpoint event, requests Parakeet, resumes the pinned
Whistle job, and compares portable words/times with the unchanged public English
DC/control desktop references. It writes an `included-job.json` report beside
the existing worker report. Neither benchmark uses a user's device or data.
