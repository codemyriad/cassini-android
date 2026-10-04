#!/usr/bin/env python3
"""Fetch the optional speaker models used by the app and native device checks."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / ".tools/diar"
# Same pinned downloads as DiarizationModels.kt. Model files stay out of the APK and git.
MODELS = [
    ("segmentation.int8.onnx", 1540506, "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
     "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx"),
    ("embedding.onnx", 39593761, "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b",
     "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"),
]


def matches(path, size, digest):
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest() == digest


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    for name, size, digest, url in MODELS:
        target = DEST / name
        if not matches(target, size, digest):
            partial = target.with_suffix(".part")
            try:
                with urllib.request.urlopen(url, timeout=30) as source, partial.open("wb") as output:
                    total = 0
                    while block := source.read(128 * 1024):
                        total += len(block)
                        if total > size:
                            raise ValueError(f"Oversized download: {name}")
                        output.write(block)
                if not matches(partial, size, digest):
                    raise ValueError(f"Model verification failed: {name}")
                partial.replace(target)
            finally:
                partial.unlink(missing_ok=True)
        print(f"Verified {name}: {digest}")


if __name__ == "__main__":
    main()
