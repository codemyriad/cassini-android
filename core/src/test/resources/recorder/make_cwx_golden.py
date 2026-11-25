"""Writes the CWX golden files of CwxEncoderTest with the recorder's reference encoder.

Run from the repository root, with the recorder repository's tools/cassini_to_device.py:

    python3 -I core/src/test/resources/recorder/make_cwx_golden.py PATH/TO/tools/cassini_to_device.py

It reads cwx-cases.json next to this file and writes <name>.cwx for each case. The mapping to the encoder's input
(manifest speakers with ids "Speaker N", words with those ids) mirrors CwxTranscript, so that CwxEncoder must give the
same bytes.
"""

import importlib.util
import json
import sys
from pathlib import Path


def main() -> int:
    spec = importlib.util.spec_from_file_location("cassini_to_device", sys.argv[1])
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    here = Path(__file__).resolve().parent
    cases = json.loads((here / "cwx-cases.json").read_text(encoding="utf-8"))["cases"]
    for case in cases:
        ids = [f"Speaker {i + 1}" for i in range(len(case["speakers"]))]
        manifest = {
            "title": case["title"],
            "durationMs": case["durationMs"],
            "speakers": [
                {"id": ids[i], "label": sp["label"], "color": sp["rgb"]} for i, sp in enumerate(case["speakers"])
            ],
        }
        items = []
        for word in case["words"]:
            index = word["speakerIndex"]
            items.append({
                "speaker": ids[index] if 0 <= index < len(ids) else None,
                "startMs": word["startMs"],
                "endMs": word["endMs"],
                "text": word["text"],
                "turnStart": word["turnStart"],
                "lowConfidence": word["lowConfidence"],
            })
        data = module.build_cwx_bytes(manifest, {"items": items})
        (here / f"{case['name']}.cwx").write_bytes(data)
        print(case["name"], len(data))
    return 0


if __name__ == "__main__":
    sys.exit(main())
