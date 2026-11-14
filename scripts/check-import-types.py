#!/usr/bin/env python3
"""Check the manifest's Open with and Share filters against the vendored import-types contract (L-25, D-1030)."""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = Path('third_party/cassini-ux/contract/import-types.json')
MANIFEST = Path('app/src/main/AndroidManifest.xml')
ANDROID = '{http://schemas.android.com/apk/res/android}'
ACTIONS = ('android.intent.action.VIEW', 'android.intent.action.SEND')


def decided(root):
    contract = json.loads((root / CONTRACT).read_text())
    if contract.get('schema') != 'cassini-ux/import-types@1':
        raise ValueError('Unexpected import-types schema')
    types = []
    for entry in contract['types']:
        if entry['status'] == 'decided':
            types += [t for t in entry['androidMimeTypes'] if t not in types]
    return types


def filters(root):
    manifest = ET.parse(root / MANIFEST).getroot()
    found = {}
    for intent in manifest.iter('intent-filter'):
        actions = {a.get(ANDROID + 'name') for a in intent.findall('action')}
        for action in ACTIONS:
            if action in actions:
                if action in found:
                    raise ValueError(f'More than one filter for {action}')
                data = intent.findall('data')
                found[action] = [d.get(ANDROID + 'mimeType') for d in data if d.get(ANDROID + 'mimeType')]
    return found


def check(root):
    expected = decided(root)
    found = filters(root)
    for action in ACTIONS:
        if action not in found:
            raise ValueError(f'No filter for {action}')
        if found[action] != expected:
            raise ValueError(f'{action} types {found[action]} differ from the decided androidMimeTypes {expected}')


if __name__ == '__main__':
    try:
        check(ROOT)
    except ValueError as error:
        sys.exit(str(error))
