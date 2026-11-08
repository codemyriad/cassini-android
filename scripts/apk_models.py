"""Permit only the pinned ABI-specific Included weight container; reject other weights and keys."""

import hashlib
import json
from pathlib import Path

WEIGHTS = 'lib/arm64-v8a/libcassini_whistle_weights.so'


def whistle_model_pins():
    root = Path(__file__).resolve().parent.parent
    worker = json.loads((root / 'whistle/worker-pins.json').read_text())
    return {WEIGHTS: worker['payload']}


def check_apk_models(apk, pins):
    seen = set()
    arm64 = any(name.startswith('lib/arm64-v8a/') for name in apk.namelist())
    for entry in apk.infolist():
        name = entry.filename
        if name.endswith(('.onnx', '.cact', '.tflite', '.keystore')):
            raise ValueError(f'APK contains unexpected model/signing entry: {name}')
        if (name.endswith(('/libcassini_needle.so', '/libcassini_whistle_ort.so'))
                and not name.startswith('lib/arm64-v8a/')):
            raise ValueError(f'Private Whistle runtime outside arm64: {name}')
        if name.endswith('/libcassini_whistle_weights.so'):
            if name not in pins or name in seen:
                raise ValueError(f'Unexpected or duplicate ABI weights: {name}')
            pin = pins[name]
            if entry.file_size != pin['size'] or entry.compress_type != 0:
                raise ValueError(f'Included weights must have pinned size and be stored uncompressed: {name}')
            digest = hashlib.sha256()
            with apk.open(entry) as source:
                while block := source.read(65536):
                    digest.update(block)
            if digest.hexdigest() != pin['sha256']:
                raise ValueError(f'Included model integrity mismatch: {name}')
            seen.add(name)
    if seen != (set(pins) if arm64 else set()):
        raise ValueError('APK is missing its ABI-specific pinned Included Whistle weights')
