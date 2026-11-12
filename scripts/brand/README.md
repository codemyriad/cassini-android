# Temporary W1 compatibility snapshot

`temporary-old-palette.json` is **temporary**, not a second brand specification.
The coordinator approved it on 12 November 2026 so #195 can merge the pinned
canonical file, generator and enforcement without changing a pixel. It captures
the active light/dark palette, forced-dark Recording palette, window/launcher
resources and vector-mask value from Android `968dfbc0`. Its SHA-256 is frozen
in the adjacent checksum file. Alpha is stored separately where necessary to
preserve the original Compose float exactly.

`scripts/generate-brand-colors.py` emits these values into the same checked-in
generated Kotlin and XML files as the canonical tokens. No authored app file
contains a colour literal. CI checks the canonical byte pin, temporary snapshot
checksum, every generated output, and all production source sets for literals.

**#196 deletes both snapshot files.** Generation then emits only canonical
values: the normal theme reads its authored role aliases, window/launcher XML
reads ground/ink, and Recording reads the canonical dark recording/text roles.
The deletion regression checks this path. Do not expand the temporary palette
or retain it after the look preview activates the canonical colours.
