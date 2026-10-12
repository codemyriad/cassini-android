# Files for the model host

Cassini downloads its speaker model (the INT8 ONNX export of NVIDIA Nemotron-3-Diarization) from `dist.gocassini.com`. The OpenMDW License 1.1 of this model tells each distributor to keep a copy of the license and the notices of origin with the model. This directory holds these files for the model host.

| File | Contents |
|---|---|
| `LICENSE-OpenMDW-1.1.txt` | The OpenMDW License Agreement 1.1. It is a copy of `app/src/main/assets/licenses/nemotron-3-diarization/OpenMDW-1.1.txt`. |
| `NOTICE.txt` | The origin of the model, its license, and the changes that Cassini made (ONNX export, INT8 quantization, Zstandard compression). |
| `provenance.json` | The source revision, the export toolchain, and the sizes and SHA-256 hashes of the source, the export and the download. |

`core/src/main/kotlin/org/cassini/core/models/ModelCatalog.kt` pins the source revision, the sizes, the hashes and the download URL. The values in `provenance.json` are equal to these pins. The source hash and the export toolchain come from the distribution manifest of the model host.

## Upload

The project owner uploads these files. Contributors and CI do not.

1. Upload the three files to `dist.gocassini.com`, next to `models/files/51249515e4cb49dc00c5d5dc0a41ac59aa91157d689ec9586b3f4bf567c3f602/model.int8.onnx.zst`.
2. Make sure that the URLs of the three files give the same bytes as the files in this directory.

## Changes

If the pins in `ModelCatalog.kt` change, update `provenance.json` and `NOTICE.txt` in the same commit. Then upload them again.
