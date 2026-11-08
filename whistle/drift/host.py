#!/usr/bin/env python3
"""Read-only Mac resource gate. Run inside the shared lock, before a heavy lab stage."""

import argparse
import json
import re
import shutil
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    args = parser.parse_args()
    pressure = subprocess.check_output(["memory_pressure"], text=True)
    vm = subprocess.check_output(["vm_stat"], text=True)
    page_size = int(re.search(r"page size of (\d+) bytes", vm)[1])
    free = int(re.search(r"^Pages free:\s+(\d+)", vm, re.MULTILINE)[1]) * page_size
    swaps = int(re.search(r"^Swapouts:\s+(\d+)", vm, re.MULTILINE)[1])
    percent = int(re.search(r"System-wide memory free percentage:\s+(\d+)", pressure)[1])
    current = dict(
        time=time.time(),
        freeBytes=free,
        pressureFreePercent=percent,
        swapouts=swaps,
        swapUsage=subprocess.check_output(["sysctl", "vm.swapusage"], text=True).strip(),
        internalFreeBytes=shutil.disk_usage("/").free,
    )
    previous = json.loads(args.baseline.read_text()) if args.baseline.exists() else current
    args.baseline.write_text(json.dumps(current, indent=2) + "\n")
    print(json.dumps(current), flush=True)
    if (
        swaps > previous["swapouts"]
        or free < 2 * 1024**3
        or percent < 30
        or current["internalFreeBytes"] < 15_000_000_000
    ):
        raise SystemExit("Resource gate: swap grew, free memory is low, or internal disk is below 15 GB; wait")


if __name__ == "__main__":
    main()
