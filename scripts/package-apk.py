#!/usr/bin/env python3
"""Name CI's APK from Android's generated metadata and reject mismatched tags."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

variant = os.environ.get('APK_VARIANT', 'debug')
if variant not in {'debug', 'release'}:
    raise SystemExit('APK_VARIANT must be debug or release')
output = Path('app/build/outputs/apk') / variant
metadata = json.loads((output / 'output-metadata.json').read_text())
element, = metadata['elements']
version = element['versionName']
if os.environ.get('IS_TAG') == 'true' and os.environ['TAG'] != f'v{version}':
    raise SystemExit(f'Tag does not match APK version {version}')
source = output / element['outputFile']
with zipfile.ZipFile(source) as apk:
    names = apk.namelist()
    for required in ['assets/licenses/Cassini-GPL-3.0.txt', 'assets/licenses/THIRD_PARTY_NOTICES.md']:
        if required not in names:
            raise SystemExit(f'APK is missing {required}')
    abis = {name.split('/')[1] for name in names if name.startswith('lib/')}
    if abis != {'arm64-v8a', 'x86_64'}:
        raise SystemExit(f'Unexpected APK architectures: {abis}')
    if any(name.endswith(('.onnx', '.tflite', '.keystore')) for name in names):
        raise SystemExit('APK contains model weights or signing material')
if os.environ.get('IS_TAG') == 'true':
    if variant != 'release':
        raise SystemExit('Tagged APK must use the release build type')
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '34.0.0'
    certificate = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--print-certs',
                                           str(source)], text=True)
    expected = Path('scripts/beta-signing-cert.sha256').read_text().strip()
    if f'Signer #1 certificate SHA-256 digest: {expected}' not in certificate.splitlines():
        raise SystemExit('APK signing certificate does not match the beta update key')
    manifest = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'xmltree', str(source),
                                       'AndroidManifest.xml'], text=True)
    if any('android:debuggable' in line and '0xffffffff' in line for line in manifest.splitlines()):
        raise SystemExit('Release APK must not be debuggable')
destination = Path('dist')
destination.mkdir(exist_ok=True)
name = f'cassini-android-{version}.apk'
shutil.copyfile(output / element['outputFile'], destination / name)
checksum = hashlib.sha256((destination / name).read_bytes()).hexdigest()
(destination / f'{name}.sha256').write_text(f'{checksum}  {name}\n')
shutil.copyfile('THIRD_PARTY_NOTICES.md', destination / 'THIRD_PARTY_NOTICES.md')
