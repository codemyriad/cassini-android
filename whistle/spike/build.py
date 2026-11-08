#!/usr/bin/env python3
"""Build the standalone lab APK with SDK/NDK tools, without Gradle or the production app."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import platform
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "whistle/spike"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--compile-only", action="store_true", help="Compile JNI/Java without model or Mac fixtures")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    ndk = args.sdk / "ndk/28.2.13676358"
    native = output / "native"
    run(sys.executable, ROOT / "scripts/build-whistle-runtime.py", "--ndk", ndk, "--output", native)
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}[platform.system()]
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    bootstrap = output / "libwhistle_spike.so"
    run(
        tools / "aarch64-linux-android26-clang",
        "-std=c11",
        "-D_POSIX_C_SOURCE=200809L",
        "-shared",
        "-fPIC",
        "-O2",
        "-Wall",
        "-Wextra",
        "-Werror",
        SOURCE / "bootstrap.c",
        "-ldl",
        "-Wl,--no-undefined",
        "-Wl,-z,text",
        "-Wl,-z,now",
        "-Wl,-z,relro",
        "-Wl,-z,max-page-size=16384",
        "-Wl,-z,common-page-size=16384",
        "-o",
        bootstrap,
    )
    spec = importlib.util.spec_from_file_location("whistle_inputs", ROOT / "scripts/build-whistle-runtime.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    inspection = module.verify_elf(
        bootstrap, tools / "llvm-readelf",
        exports_expected={
            "Java_io_codemyriad_cassini_whistlespike_Spike_load",
            "Java_io_codemyriad_cassini_whistlespike_Spike_transcribe",
        },
        libraries_expected={"libc.so", "libdl.so"},
    )
    inspection.update(size=bootstrap.stat().st_size, sha256=hashlib.sha256(bootstrap.read_bytes()).hexdigest())
    (output / "bootstrap-inspection.json").write_text(json.dumps(inspection, indent=2) + "\n")
    build_tools = args.sdk / "build-tools/36.0.0"
    android = args.sdk / "platforms/android-34/android.jar"
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    run(
        args.java_home / "bin/javac",
        "-source",
        "8",
        "-target",
        "8",
        "-bootclasspath",
        android,
        "-d",
        classes,
        SOURCE / "Spike.java",
    )
    if args.compile_only:
        print("Lab JNI and Java compiled; no model or inference claim")
        return
    if args.fixtures is None:
        raise ValueError("--fixtures is required for APK packaging")
    # Reuse the foundation ELF and pinned-input checks.
    pins = json.loads((ROOT / "whistle/pins.json").read_text())
    module.checked(args.fixtures / "whistle.cact", pins["model"])
    assets = output / "assets"
    assets.mkdir(exist_ok=True)
    manifest = json.loads((args.fixtures / "manifest.json").read_text())
    manifest["androidEngineSha256"] = hashlib.sha256((native / "libcassini_needle.so").read_bytes()).hexdigest()
    for name in ["whistle.cact"] + [fixture["file"] for fixture in manifest["fixtures"]]:
        shutil.copyfile(args.fixtures / name, assets / name)
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    os.environ["JAVA_HOME"] = str(args.java_home)
    run(build_tools / "d8", "--min-api", "26", "--lib", android, "--output", output, *sorted(classes.rglob("*.class")))
    apk = output / "unsigned.apk"
    run(
        build_tools / "aapt2",
        "link",
        "-o",
        apk,
        "-I",
        android,
        "--manifest",
        SOURCE / "AndroidManifest.xml",
        "-A",
        assets,
    )
    with zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED) as package:
        package.write(output / "classes.dex", "classes.dex")
        for name, path in [
            ("libwhistle_spike.so", bootstrap),
            ("libcassini_needle.so", native / "libcassini_needle.so"),
        ]:
            package.write(path, "lib/arm64-v8a/" + name)
    aligned = output / "aligned.apk"
    run(build_tools / "zipalign", "-P", "16", "-f", "4", apk, aligned)
    key = output / "lab.keystore"
    if not key.exists():
        run(
            args.java_home / "bin/keytool",
            "-genkeypair",
            "-keystore",
            key,
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "lab",
            "-keyalg",
            "RSA",
            "-validity",
            "30",
            "-dname",
            "CN=Offline Whistle Lab",
        )
    final = output / "whistle-spike.apk"
    run(build_tools / "apksigner", "sign", "--ks", key, "--ks-pass", "pass:android", "--out", final, aligned)
    run(build_tools / "apksigner", "verify", final)
    run(build_tools / "zipalign", "-c", "-P", "16", "4", final)
    print(f"Lab APK: {final}; {final.stat().st_size} bytes; sha256 {hashlib.sha256(final.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
