#!/usr/bin/env python3
"""Fetch the optional speaker model used by the app and native device checks."""
import hashlib
import shutil
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / ".tools/diar"
# Same pinned download as ModelCatalog.DIARIZATION in :core. Model files stay out of the APK and git.
NAME = "nemotron-3-diarization.int8.onnx"
SIZE, DIGEST = 103967426, "47c221ea9b4d4e7f6c108bd098e769bc706cdc986c29f6133c93cae335fe6779"
ZST_SIZE, ZST_DIGEST = 64915668, "794720a59892189484d38a186d2b350018b4194cae55292f501b2849d6313290"
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
