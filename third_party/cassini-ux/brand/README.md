# Brand token pin

`tokens.json` is byte-identical to `brand/tokens.json` at cassini-ux commit
`e244ad6a830e4ef77d87fb452143f7100c767139` (PR #4). Its SHA-256 and source commit are
in the `brand/tokens.json` entry of [../UPSTREAM.json](../UPSTREAM.json). The root
commit still describes the separately pinned strings catalog. A colour pin bump
does not silently update strings. Re-pin to the merge commit once UX #4 lands.

Run `python3 -I scripts/generate-brand-colors.py` from the repository root to
regenerate checked-in `BrandTokenValues.kt` and both `colors.xml` files. CI runs
it with `--check`, then `scripts/check-brand-color-literals.py`. No authored
production source set may introduce a raw colour or fixed platform colour.
Only the three exact generated output paths are excluded from that scan, and
the generator checks all three for edits. Transparent/unspecified sentinels are
not colour roles. Tests and design documentation are outside the production scan.

Only `decided` and `placeholder` roles are generated, including `derived`.
Unknown statuses fail. The JVM sync test reads these actual bytes and checks
the digest, both modes and Android role aliases. Mutation tests cover vendor
bytes, generated Kotlin, both XML files, and new authored colour literals.

The temporary #195 compatibility snapshot and generator path are deleted in this look preview.
All emitted values come from the canonical token file, including Recording and window/launcher resources.
