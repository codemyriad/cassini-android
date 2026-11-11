# Shared UI catalog

`strings.json` is the complete catalog from [cassini-ux](https://github.com/codemyriad/cassini-ux), pinned by commit and SHA-256 in `UPSTREAM.json`. The catalog is the source of truth for D96 copy. It is a build input, not an app asset.

Run `python3 scripts/generate-d96-strings.py` from the repository root after updating the pin. This generates the D96 and common action resources for all six app locales in `app/src/main/res/values*/d96.xml`. Do not edit those resources by hand. CI runs the generator with `--check`.

The older Markdown vectors keep their own reference pin under `core/src/test/resources/markdown`; its strings selection now records this same catalog commit independently of the vector files.
