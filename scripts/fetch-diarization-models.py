#!/usr/bin/env python3
"""Fetch the optional speaker model used by the app and native device checks."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / ".tools/diar"
# Same pinned download as DiarizationModels.kt. Model files stay out of the APK and git.
NAME = "nemotron-3-diarization.int8.onnx"
SIZE, DIGEST = 103941758, "01d1f893f394cc0418ae78425d216ed62c334c0125e650135332a7a8e595f168"
ZST_SIZE, ZST_DIGEST = 64982914, "51249515e4cb49dc00c5d5dc0a41ac59aa91157d689ec9586b3f4bf567c3f602"
URL = f"https://dist.gocassini.com/models/files/{ZST_DIGEST}/model.int8.onnx.zst"


def matches(path, size, digest):
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest() == digest


def decompress(source, target):
    try:
        from compression import zstd  # Python 3.14+
        with zstd.open(source, "rb") as input, target.open("wb") as output:
            shutil.copyfileobj(input, output, 1024 * 1024)
    except ImportError:
        subprocess.run(["zstd", "-q", "-d", "-f", str(source), "-o", str(target)], check=True)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    target = DEST / NAME
    if not matches(target, SIZE, DIGEST):
        compressed = DEST / (NAME + ".zst")
        partial = target.with_suffix(".part")
        try:
            # The model host rejects the default Python-urllib agent.
            request = urllib.request.Request(URL, headers={"User-Agent": "cassini-android-tools"})
            with urllib.request.urlopen(request, timeout=30) as source, compressed.open("wb") as output:
                total = 0
                while block := source.read(128 * 1024):
                    total += len(block)
                    if total > ZST_SIZE:
                        raise ValueError(f"Oversized download: {NAME}")
                    output.write(block)
            if not matches(compressed, ZST_SIZE, ZST_DIGEST):
                raise ValueError(f"Download verification failed: {NAME}")
            decompress(compressed, partial)
            if not matches(partial, SIZE, DIGEST):
                raise ValueError(f"Model verification failed: {NAME}")
            partial.replace(target)
        finally:
            compressed.unlink(missing_ok=True)
            partial.unlink(missing_ok=True)
    print(f"Verified {NAME}: {DIGEST}")


if __name__ == "__main__":
    main()
