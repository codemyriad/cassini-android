#!/usr/bin/env python3
"""Package an unchanged-engine control plus six lab variants with one disposable signing key."""

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--runtimes", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    cases = []
    for name in ["published", "natural-system", "off-system", "on-system", "natural-musl", "off-musl", "on-musl"]:
        native = args.baseline if name == "published" else args.runtimes / name
        out = args.output / name
        subprocess.run(
            [
                sys.executable,
                str(ROOT / "whistle/spike/build.py"),
                "--sdk",
                str(args.sdk),
                "--java-home",
                str(args.java_home),
                "--fixtures",
                str(args.reference),
                "--runtime",
                str(native / "libcassini_needle.so"),
                "--runtime-report",
                str(native / "report.json"),
                "--keystore",
                str(args.keystore),
                "--output",
                str(out),
            ],
            check=True,
        )
        cases.append(dict(name=name, apk=str(out / "whistle-spike.apk"), forceOn=name.startswith("on-")))
    (args.output / "batch.json").write_text(
        json.dumps(
            {
                "cases": cases,
                "dotprodProbe": str(args.runtimes / "dotprod-smoke"),
            },
            indent=2,
        )
        + "\n"
    )


if __name__ == "__main__":
    main()
