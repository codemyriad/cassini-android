#!/usr/bin/env python3
"""Run the lab APK on an owned disposable arm64 AVD; wait for boot events, never fixed sleeps."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import selectors
import shutil
import subprocess
import time
import uuid
from pathlib import Path

PACKAGE = "io.codemyriad.cassini.whistlespike"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument(
        "--batch", type=Path, help="Owned lab variant APKs; all must use the same disposable signing key"
    )
    parser.add_argument("--api", type=int, default=35)
    parser.add_argument("--tag", default="google_apis")
    parser.add_argument("--image", type=Path, help="Already installed external-disk system image")
    args = parser.parse_args()
    if args.image is None and (args.api != 35 or args.tag != "google_apis"):
        parser.error("--image is required for a non-default API/tag")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if args.batch:
        (output / "batch.json").unlink(missing_ok=True)
    adb = args.sdk / "platform-tools/adb"
    serial = "emulator-5586"

    def command(*parts, timeout=1200, **kwargs):
        return subprocess.run(
            [str(adb), "-s", serial, *parts], check=True, capture_output=True, timeout=timeout, **kwargs
        )

    devices = subprocess.check_output([str(adb), "devices"], text=True)
    if serial in devices:
        raise ValueError("Refusing to reuse an existing emulator transport")
    home = output / "avd"
    avd = home / "cassini-whistle-lab.avd"
    avd.mkdir(parents=True, exist_ok=True)
    # Use an owned image copy: writable emulator state and all image bytes stay on the external disk.
    image = args.image.resolve() if args.image else output / "image-api35"
    if args.image and not (image / "system.img").is_file():
        raise ValueError("Missing installed system image")
    if not image.exists():
        shutil.copytree(args.sdk / "system-images/android-35/google_apis/arm64-v8a", image)
    (home / "cassini-whistle-lab.ini").write_text(f"avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-{args.api}\n")
    (avd / "config.ini").write_text(
        f"avd.ini.encoding=UTF-8\nabi.type=arm64-v8a\nhw.cpu.arch=arm64\nhw.cpu.ncore=2\n"
        f"hw.ramSize=2048\nhw.lcd.width=720\nhw.lcd.height=1280\nhw.lcd.density=320\n"
        f"hw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\nhw.audioInput=no\nhw.audioOutput=no\n"
        f"hw.keyboard=yes\ndisk.dataPartition.size=2G\nimage.sysdir.1={image}/\n"
        f"tag.id={args.tag}\ntag.display={args.tag}\nPlayStore.enabled=no\n"
    )
    env = dict(
        os.environ,
        ANDROID_AVD_HOME=str(home),
        ANDROID_USER_HOME=str(output / "android-user"),
        ANDROID_EMULATOR_HOME=str(output / "emulator-home"),
    )
    for name in ["android-user", "emulator-home"]:
        (output / name).mkdir(exist_ok=True)
    with (output / "emulator.log").open("w") as log:
        emulator = subprocess.Popen(
            [
                str(args.sdk / "emulator/emulator"),
                "-avd",
                "cassini-whistle-lab",
                "-port",
                "5586",
                "-no-window",
                "-no-audio",
                "-no-snapshot",
                "-no-boot-anim",
                "-memory",
                "2048",
                "-cores",
                "2",
                "-gpu",
                "swiftshader_indirect",
            ],
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        watcher = None
        try:
            command("wait-for-device", timeout=240)
            watcher = subprocess.Popen(
                [str(adb), "-s", serial, "logcat", "-b", "events", "-v", "brief"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            selector = selectors.DefaultSelector()
            selector.register(watcher.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 240
            boot = bytearray()
            # Query once initially, then on real Android event-log output. No timer/sleep determines readiness.
            while command("shell", "getprop", "sys.boot_completed", timeout=10).stdout.strip() != b"1":
                ready = selector.select(max(0, deadline - time.monotonic()))
                if not ready or emulator.poll() is not None:
                    raise RuntimeError("Owned emulator did not complete boot before the event deadline")
                block = os.read(watcher.stdout.fileno(), 65536)
                if not block:
                    raise RuntimeError("Boot event stream closed")
                boot.extend(block)
                if len(boot) > 4000000:
                    raise RuntimeError("Unexpected boot event volume")
            (output / "boot-events.log").write_bytes(boot)
            watcher.terminate()
            watcher.wait(timeout=10)
            watcher = None
            cases = [{"name": "single", "apk": str(args.apk)}]
            hardware = {}
            dotprod_ok = False
            if args.batch:
                batch = json.loads(args.batch.read_text())
                cases = batch["cases"]
                if not cases or len(cases) > 8:
                    raise ValueError("Expected one to eight bounded variant cases")
                hardware["kernel"] = command("shell", "uname", "-a").stdout.decode().strip()
                hardware["imageProperties"] = (image / "source.properties").read_text().splitlines()
                hardware["emulatorProperties"] = (args.sdk / "emulator/source.properties").read_text().splitlines()
                try:
                    hardware["bionicHashes"] = (
                        command("shell", "sha256sum", "/system/lib64/libm.so", "/system/lib64/libc.so")
                        .stdout.decode()
                        .splitlines()
                    )
                except subprocess.CalledProcessError:
                    hardware["bionicHashes"] = "Unavailable on this image"
                hardware["fingerprint"] = command("shell", "getprop", "ro.build.fingerprint").stdout.decode().strip()
                remote = "/data/local/tmp/cassini-drift-" + uuid.uuid4().hex
                command("shell", "mkdir", remote)
                try:
                    command("push", batch["dotprodProbe"], remote + "/dotprod-smoke")
                    command("shell", "chmod", "700", remote + "/dotprod-smoke")
                    try:
                        hardware["dotprodSmoke"] = command("shell", remote + "/dotprod-smoke").stdout.decode().strip()
                        dotprod_ok = True
                    except subprocess.CalledProcessError as error:
                        hardware["dotprodSmoke"] = {
                            "exit": error.returncode,
                            "stdout": error.stdout.decode(errors="replace"),
                        }
                finally:
                    command("shell", "rm", "-f", remote + "/dotprod-smoke")
                    command("shell", "rmdir", remote)
            summaries = []
            parity_failures = []
            for case in cases:
                name = case["name"]
                if not re.fullmatch(r"[a-z0-9-]{1,60}", name):
                    raise ValueError("Invalid owned case name")
                directory = output / name if args.batch else output
                directory.mkdir(exist_ok=True)
                if case.get("forceOn") and not dotprod_ok:
                    summaries.append({"name": name, "skipped": "Actual SDOT instruction was not verified"})
                    continue
                print("Running", args.api, name, flush=True)
                command("shell", "am", "force-stop", PACKAGE)
                command("install", "-r", str(case["apk"]))
                result = command("shell", "am", "instrument", "-w", "-r", PACKAGE + "/.Spike")
                (directory / "instrumentation.txt").write_bytes(result.stdout)
                if b"INSTRUMENTATION_CODE: -1" not in result.stdout and b"INSTRUMENTATION_CODE: 1" not in result.stdout:
                    raise RuntimeError("Lab failed before producing results; see instrumentation.txt")
                report = command("shell", "run-as", PACKAGE, "cat", "files/result.json").stdout
                (directory / "result.json").write_bytes(report)
                decoded = json.loads(report)
                expected_page_size = 16384 if args.tag == "google_apis_ps16k" else 4096
                if decoded["pageSize"] != expected_page_size or decoded["sdk"] != args.api:
                    raise RuntimeError("Guest API/page size does not match the requested image")
                if not decoded["allWordsEqual"] or not decoded["allTimesWithin80ms"]:
                    parity_failures.append(name)
                summary = {
                    key: decoded[key]
                    for key in ["sdk", "pageSize", "privacyRefusals", "allWordsEqual", "allTimesWithin80ms", "loadMs"]
                }
                summary.update(
                    name=name,
                    apkSha256=hashlib.sha256(Path(case["apk"]).read_bytes()).hexdigest(),
                    environment=decoded.get("environment"),
                )
                summaries.append(summary)
                print(json.dumps(summary), flush=True)
            if args.batch:
                (output / "batch.json").write_text(
                    json.dumps(
                        {
                            "status": "completed",
                            "hardware": hardware,
                            "cases": summaries,
                            "parityFailures": parity_failures,
                        },
                        indent=2,
                    )
                    + "\n"
                )
            if parity_failures:
                raise RuntimeError("Parity differences recorded; no golden changed")
        finally:
            try:
                crash = command("logcat", "-d", "-b", "crash", timeout=10)
                (output / "crash.log").write_bytes(crash.stdout)
            except (OSError, subprocess.SubprocessError):
                pass  # Guest failure must not prevent cleanup or hide the original exception.
            if watcher is not None:
                watcher.terminate()
                watcher.wait(timeout=10)
            # Stop only the emulator process launched by this invocation, on success or failure.
            emulator.terminate()
            try:
                emulator.wait(timeout=30)
            except subprocess.TimeoutExpired:
                emulator.kill()
                emulator.wait(timeout=10)
            command("wait-for-disconnect", timeout=30)


if __name__ == "__main__":
    main()
