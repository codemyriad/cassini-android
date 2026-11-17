# About copyright line names Codemyriad OÜ on Android (D-1090 / D103 §6 q5, D81.1)

The Phase 2 boards disagreed on the copyright holder (question q5). D81.1 settles it: copyright lines name the legal
entity, "Codemyriad OÜ". cassini-ux#51 changed only the text of `about.licenceLine.phone`; no id is renamed or removed.

## Catalog pin

All four vendored catalog copies (`strings.json`, `strings/strings.json`, `wave5/strings.json`, `phase2/strings.json`)
and the Phase 1 bundle commit move from `c28b724` to the #51 merge commit
`dd06a62dc881034fed417b000f47004e577a3fb0` (catalog SHA-256 `37be3943…`). Between the two commits only the catalog
changed bytes, so tokens, marks, vectors and contracts are identical. The independently pinned Markdown fixture in
`core/src/test/resources/markdown/` keeps its own pin: none of its ten extracted ids changed.

The generators (`scripts/generate-{phase1,phase2,wave5,d96}-strings.py`, `scripts/generate-brand-colors.py`) only
rewrite their provenance header, except `ui_about_licence_line` in the six `values*/phase2.xml`:

- en: "Free software under GPLv3. © {year} Codemyriad OÜ and contributors."
- it, fr, es, pt-BR and pt-PT add "OÜ" after "Codemyriad", taken from the catalog.

## Evidence

`Phase2SettingsScreenshotTest` (English, light and dark) and the new `Phase2AboutLocaleScreenshotTest` (Italian,
light and dark) render Settings › About, assert the full line and re-record the goldens
`{light,dark,it,it-dark}/phase2-settings-about.png`. The Italian test also checks that no text is cut.
