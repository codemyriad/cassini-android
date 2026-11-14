# Shared Phase 1 contract

The catalog, tokens, Saturn marks (nav, mid), contracts and vectors come from
cassini-ux commit `5e4f0bff95300f2ef795fe71244b22644cad5c25` (bumped from `5e0c34e` for L-10,
`docs/design/phase2-d1035.md`). `UPSTREAM.json`
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

`wave5/strings.json` (D-975) is the catalog at cassini-ux `31973ff9784fcc66c1c7993b903a336e8f2c1432`, pinned
at that commit in its own `UPSTREAM.json` entry. It supplies only the first-run hero and privacy-line resources
(`scripts/generate-wave5-strings.py`, `docs/design/phase2-d975.md`).
