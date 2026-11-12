#!/usr/bin/env python3
"""Generate the existing shared L-26 labels; missing short forms remain explicit canvas preview copy."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
VENDOR = Path('third_party/cassini-ux')
LOCALES = {'en': 'values', 'it': 'values-it', 'fr': 'values-fr', 'es': 'values-es',
           'pt-BR': 'values-pt-rBR', 'pt-PT': 'values-pt-rPT'}
LABELS = {'recording': 'states.badge.recording', 'saving': 'states.badge.saving',
          'transcribing': 'states.badge.transcribing.noEta', 'attention': 'states.badge.needsAttention',
          'received': 'states.badge.received', 'transcribing_spoken_eta': 'states.badge.transcribing'}


def android_text(text, expected, argument, catalog_id, locale, *, literal_count=False):
    actual = set(re.findall(r'\{([^{}]+)\}', text))
    if not actual <= expected or (not literal_count and actual != expected):
        raise ValueError(f'Missing shared placeholder: {catalog_id}/{locale}')
    if argument:
        text = text.replace('{' + argument + '}', '%1$s')
    return escape(text).replace("'", "\\'").replace('"', '\\"')


def generated(root):
    raw = (root / VENDOR / 'strings.json').read_bytes()
    pin = json.loads((root / VENDOR / 'UPSTREAM.json').read_text())
    record = pin['files']['strings.json']
    if hashlib.sha256(raw).hexdigest() != record['sha256']:
        raise ValueError('Shared strings differ from their pinned SHA-256')
    entries = {entry['id']: entry for entry in json.loads(raw)['entries']}
    commit = record.get('commit', pin['commit'])
    for locale, qualifier in LOCALES.items():
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 f'<!-- Generated from cassini-ux {commit}; do not edit. -->', '<resources>']
        for name, catalog_id in LABELS.items():
            entry = entries[catalog_id]
            argument = {'recording': 'liveBadge', 'transcribing_spoken_eta': 'n'}.get(name)
            expected = {argument} if argument else set()
            if set(entry.get('placeholders', {})) != expected:
                raise ValueError(f'Unexpected shared placeholders: {catalog_id}')
            if 'plural' in entry:
                forms = entry['plural'] if locale == 'en' else entry['l10n'][locale]['plural']
                if 'other' not in forms:
                    raise ValueError(f'Missing shared other form: {catalog_id}/{locale}')
                lines.append(f'    <plurals name="ui_badge_{name}">')
                for quantity, text in forms.items():
                    value = android_text(
                        text, expected, argument, catalog_id, locale, literal_count=quantity != 'other')
                    lines.append(f'        <item quantity="{quantity}">{value}</item>')
                lines.append('    </plurals>')
            else:
                text = entry['en'] if locale == 'en' else entry['l10n'][locale]['text']
                value = android_text(text, expected, argument, catalog_id, locale)
                lines.append(f'    <string name="ui_badge_{name}">{value}</string>')
        lines.append('</resources>')
        yield root / 'ui/src/main/res' / qualifier / 'w2_badges.xml', '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, source in generated(ROOT):
        if args.check:
            if not path.exists() or path.read_text() != source:
                raise SystemExit(f'Stale shared badge strings: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)


if __name__ == '__main__':
    main()
