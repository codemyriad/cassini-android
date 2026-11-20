# Report a problem in About on Android (D-1132 / D110, L-65)

Alex approved "Report a problem" on 18 November 2026 (L-65, board `P2About-Phones`); Silvio settled its address on
20 November (D-1113), recorded as cassini-ux `D110`, which settles `D107` §2.

## What changed

- Settings › About gains a row right after Help: "Report a problem" with the subtitle "Tell us what went wrong"
  (`about.reportProblem`, `about.reportProblem.subtitle`). It is drawn like the Help row (`AboutLink`, Info glyph).
- Tapping it sends `SettingsEvent.OpenReportProblem`; `SettingsViewModel` emits `SettingsEffect.OpenUrl` with
  `REPORT_PROBLEM_URL` (`https://cassini.talk/report`), which `SettingsRoute` opens in the browser exactly as Help.
- The address sits beside `HELP_URL` in `SettingsViewModel`'s companion, not in the catalog (D110 §1), so changing it
  later is one line. The page is not written yet; the row is built anyway (D110 §1).
- Privacy (HARMONY §12): the browser opens only on a tap. The app attaches nothing (no recordings, no logs) and makes
  no request itself.

## Catalog pin

`third_party/cassini-ux/phase2/strings.json` is re-pinned to cassini-ux `671abcd26e77d012583adc25f971e0d6b3742f7f`
(sha256 `4305e038…`). cassini-ux PR 63 (D110) changes only the two ids' status (`pending` to `drawn`) and notes; the
English and the it, fr, es, pt-BR and pt-PT translations are identical at `671abcd`, so the generated resources do not
change when the pin later moves past the #63 merge. `scripts/generate-phase2-strings.py` generates both ids in all six
languages into `values*/phase2.xml`.

## Tests

- `SettingsMapTest.aboutHasFiveDistinctDestinations`: the About rows send Help, Report a problem, Privacy, Licences,
  Source code in that order.
- `SettingsViewModelTest.helpAndReportAProblemOpenTheirCassiniTalkPagesInTheBrowser`: the two addresses.
- The `phase2-settings-about` goldens (light, dark, Italian, Italian dark) and the About locale goldens are re-recorded.
