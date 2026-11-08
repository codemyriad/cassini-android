"""Measure the normal ABI APK outputs and AAB; no signing, installation or publishing."""

import argparse
import hashlib
import importlib.util
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def helper(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def describe(path):
    with zipfile.ZipFile(path) as archive:
        native = {item.filename: dict(bytes=item.file_size, zipBytes=item.compress_size)
                  for item in archive.infolist() if item.filename.endswith('.so')}
        models = [name for name in archive.namelist() if name.endswith(('.onnx', '.cact', '.tflite'))]
    return dict(file=path.name, bytes=path.stat().st_size,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest(), native=native, modelAssets=models)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-dir', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    compatibility = helper('apk_compatibility')
    models = helper('apk_models')
    metadata = json.loads((args.apk_dir / 'output-metadata.json').read_text())
    rows = {}
    for element in metadata['elements']:
        filters = element['filters']
        abi = filters[0]['value'] if filters else 'universal'
        if abi in rows or abi not in {'arm64-v8a', 'x86_64', 'universal'}:
            raise ValueError('Unexpected or duplicate ABI output')
        path = args.apk_dir / element['outputFile']
        expected = {'arm64-v8a', 'x86_64'} if abi == 'universal' else {abi}
        compatibility.check_apk_compatibility(path, expected)
        with zipfile.ZipFile(path) as archive:
            models.check_apk_models(archive, models.whistle_model_pins())
        rows[abi] = describe(path)
    if set(rows) != {'arm64-v8a', 'x86_64', 'universal'}:
        raise ValueError('Missing ABI APKs')
    with zipfile.ZipFile(args.bundle) as bundle:
        libraries = [name for name in bundle.namelist() if name.startswith('base/lib/') and name.endswith('.so')]
        if {name.split('/')[2] for name in libraries} != {'arm64-v8a', 'x86_64'}:
            raise ValueError('Unexpected bundle ABI set')
        pin, = models.whistle_model_pins().values()
        weights = 'base/' + models.WEIGHTS
        data = bundle.read(weights)
        if len(data) != pin['size'] or hashlib.sha256(data).hexdigest() != pin['sha256']:
            raise ValueError('Bundle weight pin mismatch')
        for name in bundle.namelist():
            if name.endswith(('.onnx', '.cact', '.tflite', '.keystore')):
                raise ValueError(f'Unexpected bundle model/key: {name}')
            if 'libcassini_whistle_weights.so' in name and name != weights:
                raise ValueError('Bundle weights outside arm64')
    rows['bundle'] = describe(args.bundle)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rows, indent=2) + '\n')
    print(json.dumps({name: row['bytes'] for name, row in rows.items()}))


if __name__ == '__main__':
    main()
