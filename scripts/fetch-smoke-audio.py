#!/usr/bin/env python3
"""Fetch one CC-BY-4.0 Italian FLEURS dev recording, without downloading the corpus."""
import json
import pathlib
import subprocess
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
BASE = f"https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}/data/it_it"
FILENAME = "10017639081645832204.wav"
ASSETS = ROOT / "app/src/androidTest/assets"
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
(ASSETS / "italian-smoke.json").write_text(json.dumps({
    "source": BASE, "revision": REVISION, "filename": FILENAME,
    "reference": row[2], "license": "CC-BY-4.0", "attribution": "Google FLEURS contributors",
    "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
    "modifications": "Converted to PCM16 WAV, AAC, MP3 and Opus for decoder regression checks",
}, ensure_ascii=False, indent=2))
for suffix, options in {
    "wav": ["-c:a", "pcm_s16le"], "m4a": ["-c:a", "aac", "-b:a", "96k"],
    "mp3": ["-c:a", "libmp3lame", "-b:a", "96k"], "opus": ["-c:a", "libopus", "-b:a", "48k"],
}.items():
    command = ["ffmpeg", "-v", "error", "-y"]
    acceleration = ["-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128", "-hwaccel_output_format", "vaapi"]
    if pathlib.Path("/dev/dri/renderD128").exists():
        command += acceleration
    command += ["-i", str(source)] + options + [str(ASSETS / f"italian-smoke.{suffix}")]
    completed = subprocess.run(command)
    if completed.returncode and acceleration[0] in command:
        command = [part for part in command if part not in acceleration]
        subprocess.run(command, check=True)
    elif completed.returncode:
        raise subprocess.CalledProcessError(completed.returncode, command)
print("Prepared Italian smoke fixtures. Reference:", row[2])
