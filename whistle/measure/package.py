"""Report unsigned APK/AAB bytes and ZIP/native/model contributions without install-size claims."""

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(65536):
            digest.update(block)
    return digest.hexdigest()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--expected-abi", choices=["universal", "arm64-v8a", "x86_64"])
parser.add_argument("artifacts", nargs="+")
args = parser.parse_args()
rows = []
for arg in args.artifacts:
    path = Path(arg)
    with zipfile.ZipFile(path) as archive:
        native = {}
        assets = {}
        for entry in archive.infolist():
            match = re.fullmatch(r"(?:base/)?lib/([^/]+)/(.+)", entry.filename)
            if match:
                native.setdefault(match[1], []).append(
                    dict(name=match[2], uncompressedBytes=entry.file_size, zipBytes=entry.compress_size)
                )
            if "/whistle/" in entry.filename:
                assets[entry.filename] = dict(uncompressedBytes=entry.file_size, zipBytes=entry.compress_size)
        if args.expected_abi:
            expected = {"arm64-v8a", "x86_64"} if args.expected_abi == "universal" else {args.expected_abi}
            if set(native) != expected:
                raise ValueError(f"Measured ABI mismatch in {path.name}: {set(native)} != {expected}")
        rows.append(
            dict(
                file=str(path),
                bytes=path.stat().st_size,
                sha256=sha256(path),
                native=native,
                whistle=assets,
            )
        )
print(json.dumps(rows, indent=2))
