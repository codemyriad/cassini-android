# Included and the default speech model

Included Whistle is the default for a new transcription when the executing process
is arm64, Android API 29 or later, the real CPU probe reports ASIMDDP, and the
packaged runtime/models pass their existing integrity checks. Settings shows
Unavailable elsewhere and retains optional Parakeet. No dispatch override or
runtime download enables Whistle on an unsupported device.

An explicit Parakeet choice is saved in `recognition.model` and survives restarts.
An absent choice resolves from Included availability. A choice changes new jobs;
it does not change queued or paused work. Every Room job stores `speech_identity`,
which includes the full engine revision, model/VAD hashes and decoder/gate policy
versions for Whistle. Unknown job identities never select another recognizer.
Checkpoint validation, worker creation, consent and output provenance use this
recorded identity. A rebuild or Trash restore retains a valid checkpoint's model.

Whistle's basic path uses bundled VAD and needs no downloaded model or consent.
Without optional speaker/voice models, it uses one speaker and no saved-voice
matching. Settings can download those parts explicitly. That consent does not
authorize Parakeet: `models.speechConsentAt` is recorded only for a download plan
that includes Parakeet. Existing installed Parakeet remains usable. Download
screens for a paused job use that job's model, even after the default changes.

The decoder policy is explicit: 48 kHz uses `cassini-fir-48k-16k-v1`, 16 kHz is
unchanged, and other supported rates use the established Hann-windowed sinc
conversion. Those other-rate decodes do not claim desktop FIR bit parity.
Whistle decodes from the origin on resume and drops samples only after
resampling; it never creates a false FIR edge at the checkpoint. The recognizer
then rebuilds the saved prefix as described in [the adapter](job-adapter.md).
A replay mismatch offers Start over. Worker/capability/asset failures never
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
pauses on a checkpoint event, changes the default to Parakeet, resumes the pinned
Whistle job, and compares portable words/times with the unchanged public English
DC/control desktop references. It writes an `included-job.json` report beside
the existing worker report. Neither benchmark uses a user's device or data.
