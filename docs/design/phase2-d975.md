# Wave 5 first-run hero (D-975)

The empty Library shows the L-27 hero instead of the "No notes yet" banner: "Every word written down." over a
dimmed "None of it uploaded.", a wave of square 4 dp teal bars that fades toward the trailing edge, a how-to line
and the provisional L-09 privacy line. Record and Import stay in the bottom bar. The hero does not show during
search, while the Library loads or once a meeting exists.

## Backup-gated copy (D98)

The copy follows the automatic channels that can copy the library off the phone (cassini-ux `PARITY.md`, Wave 6
contract, Android table). `GraphLibraryPorts.backupReach` derives them from the D97 choices with the same rules
as the backup agent (`BackupChoices.cloudActive` and `transferActive`):

| Cloud backup | Device transfer | Qualifier `(except for encrypted cloud backup)` | Privacy line |
|---|---|---|---|
| on | on or off | shown | `privacy.onDevice.phone.withBackup` |
| off | on | hidden | `android.privacy.processing` |
| off | off | hidden | `privacy.onDevice.phone` ("The audio never leaves it.") |

Since D-980 (`docs/design/phase2-d980.md`) cloud backup is offered and on by default, so a fresh install shows the
first row: the qualifier and the backup line. Device transfer and cloud backup count only from API 28, because
Android 8 cannot tell the transports apart and the API 26–27 rules exclude every backup domain. An unreadable choices
file counts as both off, as it does for the agent. No hero or privacy claim is drawn until the choices have been read.

With the qualifier, the second line is read as one sentence (`firstRun.hero.subtitlePhoneNote`), so TalkBack never
reads two strings joined in code. The privacy line is one TalkBack stop.

## Strings and pin

The hero and privacy-line strings come from cassini-ux `31973ff9784fcc66c1c7993b903a336e8f2c1432`, vendored as
`third_party/cassini-ux/wave5/strings.json` with its own commit in `UPSTREAM.json`. `scripts/check-ux-pin.py`
accepts separately pinned inputs only when they name their commit. The Phase 1 catalog stays at its bundle
commit: the later catalog renames the `Received` badge to `Imported`, and phones wait for L-10 (D-997) before
they do that. `scripts/generate-wave5-strings.py` writes `ui/src/main/res/values*/wave5.xml` in the six app
languages, and CI checks the output. The how-to line reuses the existing `ui_banner_lib_empty_body`, because the
catalog's how-to line (`firstRun.body`) is desktop-only.

## Metrics

The 32 sp bold headline comes from L-27. The gaps, wave height and bar levels are proposed values. They are kept
in `FirstRunHeroMetrics` and `HeroWaveMetrics`, so a designer's change touches one line. No phone hero has been
drawn on the canvas yet.

## Out of scope on Android

- Settings parity for backups (the `Moving and backup` section, the phone-only card, saved-voices backup) came in
  Wave 6, D-980.
- Browser Companion is n/a on Android (PARITY).
