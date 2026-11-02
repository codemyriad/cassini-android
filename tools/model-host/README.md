# The model host

Cassini downloads all its models from `dist.gocassini.com`, the model host that the desktop app uses too. The desktop pipeline (`../gocassini`) publishes the host. This repository uploads nothing to it.

The host publishes these files:

| File | Contents |
|---|---|
| [`manifest.json`](https://dist.gocassini.com/manifest.json) | Schema version 2: each model as `{id, source_sha256, files[]}`, and each file as a seekable Zstandard artifact `models/files/<compressed sha256>/<name>.zst` with its compressed and decompressed size and SHA-256. |
| [`NOTICE.txt`](https://dist.gocassini.com/NOTICE.txt) | The origin, the license and the changes of every model on the host, with the license texts that the models ask to keep (OpenMDW-1.1, Apache-2.0, MIT, the NVIDIA Open Model License). |

The app does not follow the manifest to new models. `core/src/main/kotlin/org/cassini/core/models/ModelCatalog.kt` pins the pair (`id`, `source_sha256`) of each model that the app uses, and the sizes and hashes of each file. Before a download, `DistManifest` checks that the manifest still lists these pins, and refuses the download if it does not.

The app keeps copies of the two files:

- `app/src/main/assets/licenses/dist.gocassini.com/NOTICE.txt`: the `NOTICE.txt` of the host, byte for byte. The Licenses screen shows it under "Model notices". `LicenseAssetsTest` pins its SHA-256.
- `core/src/test/resources/model-host/manifest-2026-10-27.json`: the manifest of 2026-10-27, byte for byte. `DistManifestTest` resolves every pinned model in it.

## Changes

When the app moves to another model, or another export of a model:

1. Download the manifest with `curl -sS https://dist.gocassini.com/manifest.json`. Take the pair and the full hashes and sizes of the files from it.
2. Change the pins in `ModelCatalog.kt` and the list in `scripts/ci-models.json` in the same commit (`ModelCatalogTest` compares them). Add the new manifest as a test resource for `DistManifestTest`.
3. Download `NOTICE.txt` again with `curl -sS -o app/src/main/assets/licenses/dist.gocassini.com/NOTICE.txt https://dist.gocassini.com/NOTICE.txt`, and change its SHA-256 in `LicenseAssetsTest`.
4. Update `THIRD_PARTY_NOTICES.md` and its copy in `app/src/main/assets/licenses/`.
