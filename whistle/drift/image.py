#!/usr/bin/env python3
"""Install only the pinned API 29 test image without the SDK client's embedded-JRE permission dependency."""

import argparse
import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

SOURCE = Path(__file__).resolve().parent


def digest(path, algorithm):
    value = hashlib.new(algorithm)
    with path.open("rb") as source:
        for data in iter(lambda: source.read(1024 * 1024), b""):
            value.update(data)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    pin = json.loads((SOURCE / "pins.json").read_text())["image29"]
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / "api29.zip"
    if not archive.exists():
        for candidate in args.cache.rglob("*"):
            if candidate.is_file() and candidate.stat().st_size == pin["size"]:
                if digest(candidate, "sha1") == pin["sha1"]:
                    # A cache pointer avoids another 498 MB download/copy; never edit that cache.
                    archive.symlink_to(candidate.resolve())
                    break
    if not archive.exists():
        temporary = args.output / "api29.zip.part"
        total = 0
        with urllib.request.urlopen(pin["url"], timeout=60) as response, temporary.open("wb") as out:
            while data := response.read(1024 * 1024):
                total += len(data)
                if total > pin["size"]:
                    raise ValueError("Oversized test image download")
                out.write(data)
        if total != pin["size"] or digest(temporary, "sha1") != pin["sha1"]:
            raise ValueError("Test image download does not match official SDK metadata")
        temporary.replace(archive)
    if archive.stat().st_size != pin["size"] or digest(archive, "sha1") != pin["sha1"]:
        raise ValueError("Corrupt cached test image")
    image = args.output / "arm64-v8a"
    if image.exists():
        raise ValueError("Refusing to overwrite an existing extracted image")
    image.mkdir()
    files = {}
    with zipfile.ZipFile(archive) as zipped:
        for member in zipped.infolist():
            path = Path(member.filename)
            if ".." in path.parts or path.is_absolute() or path.parts[0] != "arm64-v8a":
                raise ValueError("Unexpected SDK archive path")
            target = args.output / path
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipped.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)  # ZipFile verifies each member CRC.
            files[str(path)] = member.file_size
    report = dict(pin=pin, archiveSha256=digest(archive, "sha256"), extractedFiles=files)
    (args.output / "image-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Installed pinned API 29 image:", image, flush=True)


if __name__ == "__main__":
    main()
