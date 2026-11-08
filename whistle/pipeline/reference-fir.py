#!/usr/bin/env python3
"""Make NEW synthetic FIR test data with the pinned desktop C implementation, never Android outputs.

Run under the shared heavy lock. Existing desktop vectors/goldens are never rewritten.
"""

import argparse
import ctypes
import hashlib
import json
import subprocess
from pathlib import Path

FIR_SHA = "cefc18cff31fa136e9fe9359f4e7444804adcc205a9b34bcea9fa435387e30c9"
COEFFICIENT_SHA = "52423b160d7d18e1c535a0750cc615e7df27c33f9e56f0f2b6bd7f27fd204e74"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mac", type=Path, required=True)
    parser.add_argument("--coefficients", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = args.mac / "Sources/CassiniVAD/fir.c"
    if sha(source.read_bytes()) != FIR_SHA or sha(args.coefficients.read_bytes()) != COEFFICIENT_SHA:
        raise ValueError("Desktop source/coefficient pin mismatch")
    args.output.mkdir(parents=True, exist_ok=True)
    library = args.output / "reference-fir.dylib"
    subprocess.run(
        [
            "xcrun",
            "clang",
            "-dynamiclib",
            "-O2",
            "-ffp-contract=off",
            "-fno-fast-math",
            "-I",
            str(args.mac / "Sources/CassiniVAD/include"),
            str(source),
            "-o",
            str(library),
        ],
        check=True,
    )
    module = ctypes.CDLL(str(library))
    fir = module.cassini_fir_48k_16k
    fir.argtypes = [
        ctypes.POINTER(ctypes.c_float),
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_int64,
        ctypes.c_int64,
        ctypes.c_int64,
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_size_t,
    ]
    fir.restype = None
    coefficients = json.loads(args.coefficients.read_text())["coefficients"]
    taps = (ctypes.c_float * len(coefficients))(*coefficients)
    cases = []
    for count in [0, 1, 2, 3, 48, 49, 50, 97, 4097, 16001]:
        inputs = (ctypes.c_float * count)(*((i * 7919 + 12345) % 65536 - 32768 for i in range(count)))
        for i in range(count):
            inputs[i] /= 32768
        result = (ctypes.c_float * ((count + 2) // 3))()
        fir(taps, inputs, 0, count, 0, result, len(result))
        cases.append(dict(inputSamples=count, outputSha256=sha(bytes(result))))
    report = dict(
        firSourceSha256=FIR_SHA,
        coefficientJsonSha256=COEFFICIENT_SHA,
        inputRule="float32(((i * 7919 + 12345) % 65536 - 32768) / 32768)",
        cases=cases,
    )
    (args.output / "fir-synthetic.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
