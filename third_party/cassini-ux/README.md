# Shared Phase 1 contract

The catalog, tokens, Saturn marks (nav, mid), contracts and vectors come from
cassini-ux commit `e709094d283434fa59428544e0065e8deb49967b` (bumped from `5e0c34e` to `5e4f0bf` for L-10,
`docs/design/phase2-d1035.md`, then to the reviewed catalog `c28b724` for D-1075, where only the catalog and
`contract/HARMONY.md` changed bytes, then to `dd06a62` for D-1090, where only the catalog changed:
`about.licenceLine.phone` names "Codemyriad OÜ", `docs/design/phase2-d1090.md`, then to `277c654` for D-1100 / D105:
new first run and "transcribe" for the process, then to `160fb92` for D-1106 / D106: Settings, Backup, backup notice and model download, then to `e709094` for
D-1186: cassini-ux main with D113; the catalog bytes now equal the `60ac93d` copies of `phase2/` and `d112/`, and the
generated resources changed only their source-commit header). `UPSTREAM.json`
records source paths and SHA-256 values. `scripts/check-ux-pin.py` verifies every
input, requires the Phase 1 inputs and rejects per-file commit drift.

`strings/strings.json` preserves the upstream directory layout for its vector
verifier. The identical `strings.json` is the existing Android generator input;
both copies are checked against the same source and digest.

Run `python3 -I scripts/generate-phase1-strings.py` for the badge and phone
catalog resources, and `scripts/generate-d96-strings.py` for import/export copy.
Run `scripts/generate-brand-colors.py` for Kotlin and XML colour values. CI checks
all generated output and the state vectors. These are build/test inputs, not
runtime catalog assets. The mark is the canonical source for native rendering.

`contract/import-types.json` (L-25, D-1030) is a Phase 2 contract pinned at its own
cassini-ux commit, recorded in its `UPSTREAM.json` entry; `ImportTypesTest` and
`scripts/check-import-types.py` keep the app and the manifest in step with it
(`docs/design/phase2-d1030.md`).

The older Markdown, search and interjection conformance fixtures retain their
independent reference pins; this UI catalog update does not change those formats.

`wave5/strings.json` (D-975) was first the catalog at cassini-ux `31973ff9784fcc66c1c7993b903a336e8f2c1432`; D-1075
re-pinned it to `c28b724` and D-1090 to `dd06a62` in its own `UPSTREAM.json` entry. It supplies only the first-run hero and privacy-line resources
(`scripts/generate-wave5-strings.py`, `docs/design/phase2-d975.md`).

`phase2/strings.json` (L-67, D-1048) is the complete catalog pinned independently for phone meeting nouns.
`scripts/generate-phase2-strings.py` generates the affected Settings, Saved voices, transcription and shared-path
resources in all six languages. See `docs/design/phase2-d1048.md` and, for the D103 second pass (re-pinned at
`c28b724`), `docs/design/phase2-d1086.md`. D-1075 moved the Phase 1 and Wave 5 catalog pins to the same reviewed
`c28b724` catalog, and D-1090 moved all four catalog copies to `dd06a62` together, so every generated string resource
still comes from one catalog revision.

`d112/strings.json` (D-1168) is the catalog at cassini-ux `60ac93da94ce433412c93d8e96a76c68503dcec3` (D-1171, D112 /
L-86), pinned in its own `UPSTREAM.json` entry. `scripts/generate-d112-strings.py` generates `d112.xml` in all six
languages: the Background voice switch, its folded transcript row and snackbars, and the speaker dialog's "Save"
(was "Save name", so `ui_sp_save` left `phase1.xml`).
