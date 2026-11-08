#!/usr/bin/env python3
"""Prepare hash-verified offline Whistle worker assets at BUILD time. No runtime download path."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / ".tools/whistle-package")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location("whistle_inputs", ROOT / "scripts/build-whistle-runtime.py")
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    pins = json.loads((ROOT / "whistle/worker-pins.json").read_text())
    needle = json.loads((ROOT / "whistle/pins.json").read_text())
    native = args.output / "native"
    cmd = [sys.executable, ROOT / "scripts/build-whistle-runtime.py", "--ndk", args.ndk, "--output", native]
    if args.offline:
        cmd.append("--offline")
    subprocess.run([str(x) for x in cmd], check=True)
    cache = args.output / "inputs"
    cache.mkdir(exist_ok=True)
    for name, pin in [("ort.aar", pins["ort"]), ("silero_vad.onnx", pins["vad"]), ("whistle.cact", needle["model"])]:
        helper.fetch(cache / name, pin, args.offline)
    includes = args.output / "include"
    includes.mkdir(exist_ok=True)
    for name, pin in pins["headers"].items():
        helper.fetch(includes / name, pin, args.offline)
    jni = args.output / "jni/arm64-v8a"
    assets = args.output / "assets/whistle"
    jni.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(cache / "ort.aar") as archive:
        ort = archive.read(pins["ortMember"]["name"])
    if len(ort) != pins["ortMember"]["size"] or hashlib.sha256(ort).hexdigest() != pins["ortMember"]["sha256"]:
        raise ValueError("Private ORT member pin mismatch")
    (jni / "libcassini_whistle_ort.so").write_bytes(ort)
    shutil.copyfile(native / "libcassini_needle.so", jni / "libcassini_needle.so")
    for name in ["whistle.cact", "silero_vad.onnx"]:
        shutil.copyfile(cache / name, assets / name)
    libraries = {
        p.name: dict(size=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest())
        for p in jni.glob("*.so")
    }
    manifest = dict(
        schemaVersion=1,
        engineRevision=needle["engineRevision"],
        modelRevision=needle["modelRevision"],
        libraries=libraries,
        models={"whistle.cact": needle["model"], "silero_vad.onnx": pins["vad"]},
    )
    # Published last; preBuild and the private worker require this manifest. No partially prepared readiness.
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Whistle worker assets verified: arm64 only; x86_64 remains unavailable")


if __name__ == "__main__":
    main()
