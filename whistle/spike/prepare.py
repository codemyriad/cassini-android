#!/usr/bin/env python3
"""Generate public raw DC segments and candidate pinned-Mac results under the shared heavy lock."""

from __future__ import annotations

import argparse
import array
import ctypes
import hashlib
import json
import os
import shutil
import struct
import subprocess
import wave
from pathlib import Path

MODEL_SHA = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"
MAC_ARM_SHA = "99b9bd363d2e247626118e7ade04a3dc70c60ebe6bbb0e1bca50bc0bce24b0d3"
SEGMENTS = [(105984, 189440), (347648, 367616), (371200, 413184), (416256, 458240), (504320, 544256), (547328, 589312)]
DC = {
    "dc-quiet-jfk-v1": (
        "071f051241d394587c85463272e9414f133ee18c4bb835d1382b662e4154efda",
        "eeaeee543bc328acf6f0c4557e8aba86652e0144d9763264e1e5d62a3d13f968",
    ),
    "dc-quiet-jfk-v1-control": (
        "e31ed958621a39baf1d33dab6d10798695b8319e78fcaa1cb672cdb43c5f7830",
        "6ece4c5cacfc2456cb79d0aa1b16f4aa3912fef6697912e633886d7b0da545ab",
    ),
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def wav(path, rate):
    with wave.open(str(path)) as source:
        require(
            (source.getnchannels(), source.getsampwidth(), source.getframerate()) == (1, 2, rate),
            "Expected mono PCM16 at fixture sample rate",
        )
        data = array.array("h", source.readframes(source.getnframes()))
    require(struct.pack("=I", 1) == struct.pack("<I", 1), "Fixture generator requires little endian")
    return array.array("f", (x / 32768 for x in data))


def filtered_hash(samples):
    x1 = x2 = y1 = y2 = 0.0
    data = bytearray()
    for x in samples:
        y = 0.9726138984998437 * x
        y += -1.9452277969996874 * x1
        y += 0.9726138984998437 * x2
        y -= -1.9444776577670935 * y1
        y -= 0.9459779362322813 * y2
        if -1e-25 < y < 1e-25:
            y = 0.0
        x2, x1, y2, y1 = x1, x, y1, y
        data.extend(struct.pack("<f", y))
    return sha(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ["coord", "mac", "assets", "output"]:
        parser.add_argument("--" + option, type=Path, required=True)
    args = parser.parse_args()
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    model = (args.assets / "whistle.cact").read_bytes()
    require(sha(model) == MODEL_SHA, "Model pin mismatch")
    shutil.copyfile(args.assets / "whistle.cact", out / "whistle.cact")
    library = out / "mac-needle-arm64.dylib"
    subprocess.run(
        ["xcrun", "lipo", str(args.assets / "libneedle3.dylib"), "-thin", "arm64", "-output", str(library)], check=True
    )
    require(sha(library.read_bytes()) == MAC_ARM_SHA, "Mac baseline must be the exact D5 engine slice")
    for key, value in [("NEEDLE_TELEMETRY", "0"), ("DO_NOT_TRACK", "1"), ("ORT_DISABLE_TELEMETRY", "1")]:
        os.environ[key] = value
    engine = ctypes.CDLL(str(library), mode=os.RTLD_NOW | os.RTLD_LOCAL)
    engine.needle_load.argtypes = [ctypes.c_void_p, ctypes.c_ulonglong]
    engine.needle_load.restype = ctypes.c_int
    engine.needle_transcribe.argtypes = [
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_void_p,
        ctypes.c_int,
    ]
    engine.needle_transcribe.restype = ctypes.c_int
    engine.needle_last_error.restype = ctypes.c_char_p
    weights = ctypes.create_string_buffer(model)
    require(engine.needle_load(weights, len(model)) >= 0, engine.needle_last_error())
    # Mac #167's ordered binary64 inner loop; hash source and verify the public output before using it.
    source = args.mac / "Sources/CassiniVAD/fir.c"
    firlib = out / "fir.dylib"
    subprocess.run(
        [
            "xcrun",
            "clang",
            "-dynamiclib",
            "-O2",
            "-ffp-contract=off",
            "-I",
            str(args.mac / "Sources/CassiniVAD/include"),
            str(source),
            "-o",
            str(firlib),
        ],
        check=True,
    )
    fir = ctypes.CDLL(str(firlib))
    fir.cassini_fir_48k_16k.argtypes = [
        ctypes.POINTER(ctypes.c_float),
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_int64,
        ctypes.c_int64,
        ctypes.c_int64,
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_size_t,
    ]
    fir.cassini_fir_48k_16k.restype = None
    coefficients = json.loads((args.coord / "ux-draft/whistle/fir-48k-16k.json").read_text())["coefficients"]
    taps = (ctypes.c_float * len(coefficients))(*coefficients)
    fixtures = []

    def resample(pcm):
        inputs = (ctypes.c_float * len(pcm)).from_buffer(pcm)
        count = (len(pcm) + 2) // 3
        result = (ctypes.c_float * count)()
        fir.cassini_fir_48k_16k(taps, inputs, 0, len(pcm), 0, result, count)
        return array.array("f", result)

    clean = resample(wav(args.coord / "ux-draft/whistle/pipeline-input-48k.wav", 48000))
    require(
        sha(clean.tobytes()) == "1fb2707f7556fd1d2431b29cfe15e2118cea334fe55278be8c47298833ff5263",
        "Mac #167 clean FIR hash differs from the published reference",
    )

    def add(name, pcm, meta):
        data = pcm.tobytes()
        filename = name + ".f32"
        (out / filename).write_bytes(data)
        samples = (ctypes.c_float * len(pcm)).from_buffer(pcm)
        result = ctypes.create_string_buffer(1048576)
        count = engine.needle_transcribe(samples, len(pcm), None, None, 1, result, len(result))
        require(count >= 0, engine.needle_last_error())
        decoded = json.loads(result.value)
        fixtures.append(dict(file=filename, inputSha256=sha(data), samples=len(pcm), macResult=decoded, **meta))
        print(filename, "words=", len(decoded.get("words", [])), flush=True)

    add("jfk", wav(args.coord / "ux-draft/whistle/jfk.wav", 16000), {"source": "D5 public JFK 16 kHz smoke fixture"})
    for name, (wavsha, vadsha) in DC.items():
        path = args.coord / "ux-draft/dc" / f"{name}.wav"
        require(sha(path.read_bytes()) == wavsha, "DC WAV pin mismatch")
        raw = resample(wav(path, 48000))
        require(filtered_hash(raw[: len(raw) // 512 * 512]) == vadsha, "DC filtered VAD-input hash mismatch")
        for index, (start, end) in enumerate(SEGMENTS):
            add(
                f"{name}-{index}",
                raw[start:end],
                {
                    "sourceWavSha256": wavsha,
                    "startSample": start,
                    "endSample": end,
                    "segmentOrigin": "reviewed desktop D26.1 boundaries; no Android VAD execution",
                },
            )
    manifest = {
        "modelSha256": MODEL_SHA,
        "macEngineSha256": MAC_ARM_SHA,
        "firSourceSha256": sha(source.read_bytes()),
        "fixtures": fixtures,
        "scope": "Identical raw PCM engine comparison; candidate Mac results need review; no Android FIR/VAD proof",
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Generated", len(fixtures), "fixtures; clean FIR and both DC VAD-branch hashes verified.")


if __name__ == "__main__":
    main()
