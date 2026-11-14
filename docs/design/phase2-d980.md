# Wave 6 Moving and backup on Android (D-980)

Android slice of D-957 / D-1012, built to cassini-ux `PARITY.md` (Wave 6 contract §§1–4), `contract/HARMONY.md` §12
and `decisions/DECISIONS-UX.md` D98. Strings come from cassini-ux `31973ff9784fcc66c1c7993b903a336e8f2c1432`, the
catalog already pinned for D-975 (`third_party/cassini-ux/wave5/strings.json`); `scripts/generate-wave5-strings.py`
writes them to `ui/src/main/res/values*/wave6.xml` in the six app languages. The data path is in `docs/backup.md`.

## What changes for a person

- **Encrypted cloud backup is on by default.** Android Backup (Google or Seedvault) now receives the library when the
  transport is encrypted on the phone. Before, only device-to-device transfer was allowed.
- **Settings, Moving and backup** (`backup.settings.heading`) replaces "Moving to a new phone": three independent
  switches, then "Learn more".

  | Switch | On | Off |
  |---|---|---|
  | Encrypted cloud backup | `backup.cloud.body.on` | `backup.cloud.body.off` (cloud only; never "this phone only") |
  | Move to a new phone | `settings.transfer.body` | `settings.transfer.body.offBackupOn` while cloud is on, else `settings.transfer.body.off` |
  | Include saved voices in backups | `backup.includeSavedVoices.subtitle` | `backup.includeSavedVoices.subtitle.off` |

  With cloud and transfer both off, `backup.phoneOnly.body` follows the group; it is the only place besides the
  saved-voices off line that says "this phone only". `settings.transfer.exportHint` always follows. A cloud size
  refusal shows `backup.info.android.overQuota`; a transfer one `settings.transfer.tooLarge`. Before Android 9 every
  switch is off and disabled with `settings.transfer.unavailable`.
- **Learn more** opens layer 2 (`backup.info.*`): what is backed up, Android cloud backup (Google or Seedvault,
  encryption, the 25 MB limit) and a copy you keep yourself.
- **The once-only card** (`backup.phoneOnlyCard.*`) sits at the top of the Library while cloud backup is on and the
  card has not been answered: "Keep on this phone only" turns off cloud and transfer; "Keep backup on" and the close
  button ("Dismiss backup notice") keep both. A choice changed in Settings also answers it. It is not shown during
  search. In the empty Library it sits above the first-run hero.
- **Saved voices** shows the phone privacy line `savedVoices.privacy.phone` instead of the D97
  `android.savedVoices.privacy`.
- The first-run hero (D-975) needed no change: it already followed `BackupReach`, which now reports cloud backup.

## Honesty rules kept in code

- "Encrypted": the agent refuses a cloud transport without `FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED`, and API 31+ rules
  set `disableIfNoEncryptionCapabilities`.
- "Saved voices stay on this phone only": with the switch off the inventory omits all of `voices/` for both channels.
- "This phone only" / "The audio never leaves it": only in the state where both channels are refused by the agent.
- Existing installs: a D97 `choices.json` (version 1) keeps `cloud = false`, because it was written after a choice
  made under copy that promised no cloud account. Installs with no file get the D98 defaults and the card.
- Restore never brings the source phone's choices or card state (`noBackupFilesDir`).

## Not in this PR (follow-ups on D-980)

- One-question voice setup (`voices.setup.*`), guided test-phrase enrollment (`voices.enroll.*`) and saved-voice
  sample playback (`savedVoices.row.play` / `stopSample`) need a recording flow and stored sample audio that the
  Android voice store does not keep today; the existing Saved voices list (rename, forget, forget everyone, stale
  status) and transcript-clip enrollment ("Remember this voice") are unchanged.
- `savedVoices.row.sampleCount` / `savedVoices.row.status.ready` come with the voice setup slice; the stored voiceprint already counts its samples (`Voice.count`).
- A device check on Google / Seedvault transports. The local-transport harness (`scripts/check-backup-transport.py`)
  now expects encrypted cloud to succeed and unencrypted cloud to be refused; it was not re-run for this PR.
