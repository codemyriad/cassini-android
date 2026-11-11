#!/usr/bin/env python3
"""Generate D96 Android resources from the complete, checksum-pinned shared catalog."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / 'third_party/cassini-ux'
LOCALES = {'en': 'values', 'it': 'values-it', 'fr': 'values-fr', 'es': 'values-es',
           'pt-BR': 'values-pt-rBR', 'pt-PT': 'values-pt-rPT'}


def resource_id(catalog_id):
    return re.sub(r'([a-z])([A-Z])', r'\1_\2', catalog_id).replace('.', '_').lower()


def android_text(text, placeholders):
    for position, key in enumerate(placeholders, 1):
        text = text.replace('{' + key + '}', f'%{position}$d')
    if '{' in text or '}' in text:
        raise ValueError(f'Unmapped placeholder in {text}')
    return escape(text).replace("'", "\\'").replace('"', '\\"')


def generated():
    raw = (VENDOR / 'strings.json').read_bytes()
    pin = json.loads((VENDOR / 'UPSTREAM.json').read_text())
    assert hashlib.sha256(raw).hexdigest() == pin['files']['strings.json']['sha256']
    entries = [e for e in json.loads(raw)['entries']
               if e['milestone'] == 'D96' or e['id'] in {'common.cancel', 'common.done'}]
    for language, directory in LOCALES.items():
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 '<!-- Generated from cassini-ux ' + pin['commit'] + '; do not edit. -->', '<resources>']
        for entry in entries:
            name = resource_id(entry['id'])
            translation = entry if language == 'en' else entry['l10n'][language]
            placeholders = list(entry.get('placeholders', {}))
            if 'plural' in entry:
                lines.append(f'    <plurals name="{name}">')
                for quantity, text in translation['plural'].items():
                    lines.append(f'        <item quantity="{quantity}">{android_text(text, placeholders)}</item>')
                lines.append('    </plurals>')
            else:
                text = entry['en'] if language == 'en' else translation['text']
                lines.append(f'    <string name="{name}">{android_text(text, placeholders)}</string>')
        lines.append('</resources>')
        yield ROOT / 'app/src/main/res' / directory / 'd96.xml', '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    check = parser.parse_args().check
    for path, content in generated():
        if check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit(f'Stale generated shared strings: {path.relative_to(ROOT)}')
        else:
            path.write_text(content)


if __name__ == '__main__':
    main()
