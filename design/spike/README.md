# UI spike (throwaway)

These four Kotlin files come from a one-day spike that rendered two boards (Library and Read & listen)
with Compose and Roborazzi, to prove the screenshot toolchain and to try the tokens and the active-word
drawing. They are here only to port from in P7 and P8.

- `Theme.kt`: first draft of the colour and type tokens. ui.md section 4 supersedes it (for example
  `text4` is #687180 there, and the token names differ).
- `Icons.kt`: SVG path data turned into `ImageVector`s.
- `Library.kt`, `Read.kt`: the two spike screens.

This directory is not a source set. Gradle, detekt and lint do not read it, and the files do not
compile on their own (they refer to the spike's `R` class and package `org.cassini.spike`).

Delete this directory at the end of P8, once `:ui` has ported what it needs.

The spike's measuring scripts are in `tools/ui-fidelity/`; ruff skips them (see `config/ruff.toml`)
until P7 rewrites them.
