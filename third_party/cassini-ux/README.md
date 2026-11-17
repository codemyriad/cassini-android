# Shared Phase 1 contract

The catalog, tokens, Saturn marks (nav, mid), contracts and vectors come from
cassini-ux commit `c28b72461c7317ea0a4684183cb9103626aabf17` (bumped from `5e0c34e` to `5e4f0bf` for L-10,
`docs/design/phase2-d1035.md`, then to the reviewed catalog `c28b724` for D-1075; only the catalog and
`contract/HARMONY.md` changed bytes). `UPSTREAM.json`
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
re-pinned it to `c28b724` in its own `UPSTREAM.json` entry. It supplies only the first-run hero and privacy-line resources
(`scripts/generate-wave5-strings.py`, `docs/design/phase2-d975.md`).

`phase2/strings.json` (L-67, D-1048) is the complete catalog pinned independently for phone meeting nouns.
`scripts/generate-phase2-strings.py` generates the affected Settings, Saved voices, transcription and shared-path
resources in all six languages. See `docs/design/phase2-d1048.md` and, for the D103 second pass (re-pinned at
`c28b724`), `docs/design/phase2-d1086.md`. D-1075 moved the Phase 1 and Wave 5 catalog pins to the same reviewed
`c28b724` catalog, so every generated string resource now comes from one catalog revision.
