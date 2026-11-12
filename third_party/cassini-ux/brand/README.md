# Brand token pin

`tokens.json` is byte-identical to `brand/tokens.json` at cassini-ux commit
`e244ad6a830e4ef77d87fb452143f7100c767139` (PR #4). Its SHA-256 and source commit are
in the `brand/tokens.json` entry of [../UPSTREAM.json](../UPSTREAM.json). The root
commit in that manifest still describes the separately pinned strings catalog;
updating colours does not silently update strings.

Run `python3 -I scripts/generate-brand-colors.py` from the repository root to
regenerate the checked-in `BrandTokenValues.kt`. CI runs it with `--check` and
runs mutation tests for vendor-byte drift and generated-source drift. Only
`decided` and `placeholder` roles are generated, including the `derived` section.
Unknown statuses fail. The JVM sync test checks the source digest and both modes
against the generated values and Android role aliases.

This is a draft PR pin. Once cassini-ux #4 merges, replace its commit with the
merge commit and verify the bytes again.

W1.1 currently preserves the old active palette. Conversion of those remaining
literal values, the window resources and the Recording palette is pending the
coordinator's decision about the no-pixel-change requirement. The strict literal
checker must not exempt those files and claim HARMONY 10.1 compliance.
