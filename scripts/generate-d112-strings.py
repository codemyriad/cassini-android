#!/usr/bin/env python3
"""Generate the D112 Background voices and speaker row resources (D-1168) from the separately pinned catalog."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
VENDOR = Path('third_party/cassini-ux')
SOURCE = 'd112/strings.json'
LOCALES = {'en': 'values', 'it': 'values-it', 'fr': 'values-fr', 'es': 'values-es',
           'pt-BR': 'values-pt-rBR', 'pt-PT': 'values-pt-rPT'}
# Android resource name -> (catalog id, placeholders in argument order). cassini-ux D112 (L-86), D-1171.
LABELS = {
    # "Save name" became "Save" (D112): the footer button applies the changes of every speaker.
    'sp_save': ('speakers.saveName', ()),
    'bg_switch': ('speakers.backgroundVoice', ()),
    'bg_description': ('speakers.backgroundVoice.description', ()),
    'bg_name_placeholder': ('speakers.backgroundVoice.namePlaceholder', ()),
    'bg_hidden': ('speakers.backgroundVoice.hidden', ('n',)),
    'bg_toast_single': ('speakers.backgroundVoice.toast.single', ('name',)),
    'bg_toast_named': ('speakers.backgroundVoice.toast.namedAndBackground', ('name', 'n')),
    'bg_toast_count': ('speakers.backgroundVoice.toast.count', ('n',)),
}
# CLDR `many` (large round numbers) takes the `other` wording in these languages; Android lint asks for it.
MANY = {'it', 'fr', 'es', 'pt-BR', 'pt-PT'}


def android_text(text, arguments, *, literal=False):
    # Android may select `one` for zero (Portuguese), so a singular spelled with a literal 1 keeps its count dynamic.
    if literal and 'n' in arguments and '{n}' not in text:
        text, count = re.subn(r'(?<!\w)1(?!\w)', '{n}', text)
        if count != 1:
            raise ValueError('Shared count plural must carry its count')
    if set(re.findall(r'\{([^{}]+)\}', text)) != set(arguments):
        raise ValueError('Shared string placeholders changed')
    for index, name in enumerate(arguments, start=1):
        text = text.replace('{' + name + '}', f'%{index}$s')
    return escape(text).replace('\n', r'\n').replace("'", "\\'").replace('"', '\\"')


def generated(root):
    raw = (root / VENDOR / SOURCE).read_bytes()
    pin = json.loads((root / VENDOR / 'UPSTREAM.json').read_text())
    entry_pin = pin['files'][SOURCE]
    if hashlib.sha256(raw).hexdigest() != entry_pin['sha256']:
        raise ValueError('D112 catalog differs from pinned SHA-256')
    entries = {entry['id']: entry for entry in json.loads(raw)['entries']}
    for locale, qualifier in LOCALES.items():
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 f"<!-- Generated from cassini-ux {entry_pin['commit']}; do not edit. -->", '<resources>']
        for name, (identifier, arguments) in LABELS.items():
            entry = entries[identifier]
            if 'android' not in entry.get('platforms', []):
                raise ValueError(f'Catalog entry is not for Android: {identifier}')
            if set(entry.get('placeholders', {})) != set(arguments):
                raise ValueError(f'Shared placeholder declaration changed: {identifier}')
            if 'plural' in entry:
                forms = dict(entry['plural'] if locale == 'en' else entry['l10n'][locale]['plural'])
                if 'other' not in forms:
                    raise ValueError(f'Missing other plural: {identifier}/{locale}')
                if locale in MANY:
                    forms.setdefault('many', forms['other'])
                lines.append(f'    <plurals name="ui_{name}">')
                for quantity in ('zero', 'one', 'two', 'few', 'many', 'other'):
                    if quantity in forms:
                        value = android_text(forms[quantity], arguments, literal=quantity == 'one')
                        lines.append(f'        <item quantity="{quantity}">{value}</item>')
                lines.append('    </plurals>')
            else:
                text = entry['en'] if locale == 'en' else entry['l10n'][locale]['text']
                lines.append(f'    <string name="ui_{name}">{android_text(text, arguments)}</string>')
        lines.append('</resources>')
        yield root / 'ui/src/main/res' / qualifier / 'd112.xml', '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, source in generated(ROOT):
        if args.check:
            if not path.exists() or path.read_text() != source:
                raise SystemExit(f'Stale shared resources: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)


if __name__ == '__main__':
    main()
