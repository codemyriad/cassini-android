#!/usr/bin/env python3
"""Regenerate fixtures/audio/fleurs-it-short.* from the pinned FLEURS revision.

The committed files are the reference copies; device tests read them as androidTest assets. Run this
only to rebuild them (ffmpeg output can differ between versions, so review the diff before committing).
"""
import hashlib
import json
import pathlib
import subprocess
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
BASE = f"https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}/data/it_it"
FILENAME = "10017639081645832204.wav"
ASSETS = ROOT / "fixtures/audio"
NAME = "fleurs-it-short"
SCRATCH = ROOT / ".tools"
ASSETS.mkdir(parents=True, exist_ok=True)
SCRATCH.mkdir(exist_ok=True)
source = SCRATCH / "italian-source.wav"
with urllib.request.urlopen(f"{BASE}/audio/dev.tar.gz") as response:
    with tarfile.open(fileobj=response, mode="r|gz") as archive:
        for member in archive:
            if member.isfile() and pathlib.Path(member.name).name == FILENAME:
                source.write_bytes(archive.extractfile(member).read())
                break
        else:
            raise RuntimeError("Pinned FLEURS sample not found")
tsv = urllib.request.urlopen(f"{BASE}/dev.tsv").read().decode()
row = next(line.split("\t") for line in tsv.splitlines() if FILENAME in line)
metadata = {
    "source": BASE, "revision": REVISION, "filename": FILENAME,
    "reference": row[2], "license": "CC-BY-4.0", "attribution": "Google FLEURS contributors (https://huggingface.co/datasets/google/fleurs)",
    "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
    "modifications": "Extracted from dev.tar.gz and converted with ffmpeg to 16 kHz mono PCM16 WAV, AAC (M4A), MP3 and "
    "Opus for decoder checks. Nothing was cut or edited.",
}
for suffix, options in {
    "wav": ["-c:a", "pcm_s16le"], "m4a": ["-c:a", "aac", "-b:a", "96k"],
    "mp3": ["-c:a", "libmp3lame", "-b:a", "96k"], "opus": ["-c:a", "libopus", "-b:a", "48k"],
}.items():
    command = ["ffmpeg", "-v", "error", "-y"]
    acceleration = ["-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128", "-hwaccel_output_format", "vaapi"]
    if pathlib.Path("/dev/dri/renderD128").exists():
        command += acceleration
    command += ["-i", str(source)] + options + [str(ASSETS / f"{NAME}.{suffix}")]
    completed = subprocess.run(command)
    if completed.returncode and acceleration[0] in command:
        command = [part for part in command if part not in acceleration]
        subprocess.run(command, check=True)
    elif completed.returncode:
        raise subprocess.CalledProcessError(completed.returncode, command)
metadata["files"] = {
    path.name: hashlib.sha256(path.read_bytes()).hexdigest()
    for path in sorted(ASSETS.glob(f"{NAME}.*"))
    if path.suffix != ".json"
}
(ASSETS / f"{NAME}.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
print("Prepared the FLEURS fixtures. Reference:", row[2])
