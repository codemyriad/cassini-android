#!/usr/bin/env python3
"""Generate the Wave 5 first-run hero (D-975) and Wave 6 backup (D-980) resources from the separately pinned catalog."""
import argparse
import hashlib
import json
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
VENDOR = Path('third_party/cassini-ux')
SOURCE = 'wave5/strings.json'
LOCALES = {'en': 'values', 'it': 'values-it', 'fr': 'values-fr', 'es': 'values-es',
           'pt-BR': 'values-pt-rBR', 'pt-PT': 'values-pt-rPT'}
# Android resource name -> catalog id. None of these carry placeholders (PARITY Wave 6 contract sections 1-2, D98).
LABELS = {
    'hero_title': 'firstRun.hero.title',
    'hero_subtitle': 'firstRun.hero.subtitle',
    'hero_backup_qualifier': 'firstRun.hero.phoneBackupQualifier',
    'hero_subtitle_backup': 'firstRun.hero.subtitlePhoneNote',
    'privacy_phone_only': 'privacy.onDevice.phone',
    'privacy_with_backup': 'privacy.onDevice.phone.withBackup',
    'privacy_processing': 'android.privacy.processing',
}
# Wave 6 Moving and backup, the phone-only card and the phone Saved voices privacy line (PARITY Wave 6 contract
# sections 3-5, D98). None of these carry placeholders either.
WAVE6_LABELS = {
    'voices_privacy_phone': 'savedVoices.privacy.phone',
}
OUTPUTS = {'wave5.xml': LABELS, 'wave6.xml': WAVE6_LABELS}


def android_text(text):
    if '{' in text or '%' in text:
        raise ValueError('Wave 5 and 6 strings must stay placeholder-free')
    return escape(text).replace('\n', r'\n').replace("'", "\\'").replace('"', '\\"')


def generated(root):
    raw = (root / VENDOR / SOURCE).read_bytes()
    pin = json.loads((root / VENDOR / 'UPSTREAM.json').read_text())
    entry_pin = pin['files'][SOURCE]
    if hashlib.sha256(raw).hexdigest() != entry_pin['sha256']:
        raise ValueError('Wave 5 catalog differs from pinned SHA-256')
    entries = {entry['id']: entry for entry in json.loads(raw)['entries']}
    for filename, labels in OUTPUTS.items():
        for locale, qualifier in LOCALES.items():
            lines = ['<?xml version="1.0" encoding="utf-8"?>',
                     f"<!-- Generated from cassini-ux {entry_pin['commit']}; do not edit. -->", '<resources>']
            for name, identifier in labels.items():
                entry = entries[identifier]
                if 'android' not in entry.get('platforms', []):
                    raise ValueError(f'Catalog entry is not for Android: {identifier}')
                if entry.get('placeholders'):
                    raise ValueError(f'Shared placeholder declaration changed: {identifier}')
                text = entry['en'] if locale == 'en' else entry['l10n'][locale]['text']
                lines.append(f'    <string name="ui_{name}">{android_text(text)}</string>')
            lines.append('</resources>')
            yield root / 'ui/src/main/res' / qualifier / filename, '\n'.join(lines) + '\n'

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
