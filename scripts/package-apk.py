#!/usr/bin/env python3
"""Name CI's APK (and, on tags, the Play bundle) from Android's generated metadata and reject mismatched tags."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

from apk_compatibility import check_apk_compatibility

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
    if any(name.endswith(('.onnx', '.tflite', '.keystore')) for name in names):
        raise SystemExit('APK contains model weights or signing material')
    if os.environ.get('IS_TAG') == 'true':
        # The Licenses screen shows these files. Merge notes ("TODO(") belong in files that do not ship.
        for name in names:
            if name.startswith('assets/licenses/') and name.endswith('.md') and b'TODO(' in apk.read(name):
                raise SystemExit(f'{name} in the APK contains a TODO( note')
libraries = check_apk_compatibility(source)
print(f'Checked {libraries} native libraries: 64-bit ABI and 16 KB ELF/ZIP alignment')
if os.environ.get('IS_TAG') == 'true':
    if variant != 'release':
        raise SystemExit('Tagged APK must use the release build type')
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '36.0.0'
    certificate = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--print-certs',
                                           str(source)], text=True)
    expected = Path('scripts/release-signing-cert.sha256').read_text().strip()
    if not re.fullmatch('[0-9a-f]{64}', expected):
        raise SystemExit('scripts/release-signing-cert.sha256 has no certificate digest of the release key')
    if f'Signer #1 certificate SHA-256 digest: {expected}' not in certificate.splitlines():
        raise SystemExit('APK signing certificate does not match the release key')
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
# R8 renames classes in the release APK; keep its mapping so crash traces can be decoded.
mapping = Path('app/build/outputs/mapping') / variant / 'mapping.txt'
if variant == 'release':
    if not mapping.is_file():
        raise SystemExit(f'Release build has no R8 mapping at {mapping}')
    mapping_name = f'cassini-android-{version}-mapping.txt'
    shutil.copyfile(mapping, destination / mapping_name)
    mapping_checksum = hashlib.sha256((destination / mapping_name).read_bytes()).hexdigest()
    (destination / f'{mapping_name}.sha256').write_text(f'{mapping_checksum}  {mapping_name}\n')

# The bundle for Google Play goes to play/, not dist/: the GitHub release attaches everything in dist/.
if os.environ.get('IS_TAG') == 'true':
    bundle = Path('app/build/outputs/bundle/release/app-release.aab')
    if not bundle.is_file():
        raise SystemExit(f'Tag build has no bundle at {bundle}')
    # keytool prints the certificate of the signed bundle; the digest is in upper case with colons.
    printed = subprocess.check_output(['keytool', '-printcert', '-jarfile', str(bundle)], text=True)
    digests = {line.split(':', 1)[1].strip().replace(':', '').lower()
               for line in printed.splitlines() if line.strip().startswith('SHA256:')}
    if digests != {expected}:
        raise SystemExit('Bundle signing certificate does not match the release key')
    play = Path('play')
    play.mkdir(exist_ok=True)
    shutil.copyfile(bundle, play / f'cassini-android-{version}.aab')
    shutil.copyfile(mapping, play / 'mapping.txt')
    # Play shows at most 500 characters of release notes per language.
    notes = Path('metadata/en-US/changelogs') / f'{element["versionCode"]}.txt'
    if not notes.is_file():
        raise SystemExit(f'Tag build has no Play release notes at {notes}')
    if len(notes.read_text().strip()) > 500:
        raise SystemExit(f'{notes} is longer than the 500 characters that Play accepts')
    (play / 'whatsnew').mkdir(exist_ok=True)
    shutil.copyfile(notes, play / 'whatsnew' / 'whatsnew-en-US')
