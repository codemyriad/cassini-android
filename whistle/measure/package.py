"""Report unsigned APK/AAB bytes and ZIP/native/model contributions without install-size claims."""

import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

rows = []
for arg in sys.argv[1:]:
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
        rows.append(
            dict(
                file=str(path),
                bytes=path.stat().st_size,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                native=native,
                whistle=assets,
            )
        )
print(json.dumps(rows, indent=2))
