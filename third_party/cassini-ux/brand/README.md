# Brand token pin

`tokens.json`, `mark/saturn-nav.svg` and `mark/saturn-mid.svg` are byte-identical to their sources at
cassini-ux commit `5e0c34e0f1bb390298dabdf4eedc494e933fd329`. The catalog,
contracts and vectors use the same pin. [../UPSTREAM.json](../UPSTREAM.json)
records each source path and SHA-256; `scripts/check-ux-pin.py` checks the bundle.

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

The temporary #195 compatibility snapshot and generator path are deleted in WP1.
All emitted values come from the canonical token file, including Recording and window/launcher resources.
