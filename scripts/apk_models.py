"""Permit only the two pinned, uncompressed Included Whistle model entries in the APK."""

import hashlib
import json
from pathlib import Path


def whistle_model_pins():
    root = Path(__file__).resolve().parent.parent
    needle = json.loads((root / 'whistle/pins.json').read_text())
    worker = json.loads((root / 'whistle/worker-pins.json').read_text())
    return {'assets/whistle/whistle.cact': needle['model'], 'assets/whistle/silero_vad.onnx': worker['vad']}


def check_apk_models(apk, pins):
    seen = set()
    for entry in apk.infolist():
        name = entry.filename
        if not name.endswith(('.onnx', '.cact', '.tflite', '.keystore')):
            continue
        if name not in pins or name in seen:
            raise ValueError(f'APK contains unexpected or duplicate model/signing entry: {name}')
        pin = pins[name]
        if entry.file_size != pin['size'] or entry.compress_type != 0:
            raise ValueError(f'Included model must have its pinned size and be stored uncompressed: {name}')
        digest = hashlib.sha256()
        with apk.open(entry) as source:
            while block := source.read(65536):
                digest.update(block)
        if digest.hexdigest() != pin['sha256']:
            raise ValueError(f'Included model integrity mismatch: {name}')
        seen.add(name)
    if seen != set(pins):
        raise ValueError('APK is missing a required pinned Included Whistle model')
