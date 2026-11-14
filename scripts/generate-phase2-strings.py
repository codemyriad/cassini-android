#!/usr/bin/env python3
"""Generate L-67 phone wording from its separately pinned shared catalog."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'phase2/strings.json'
VENDOR = Path('third_party/cassini-ux')
spec = importlib.util.spec_from_file_location('phase1_strings', ROOT / 'scripts/generate-phase1-strings.py')
phase1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(phase1)
LABELS = {
    'headline_waiting': ('android.transcribing.waitingForPrevious', None),
    'count_ahead_of_note': ('android.transcribing.aheadCount', 'n'),
    'action_open_note': ('android.meeting.open', None),
    'settings_transfer_body': ('settings.transfer.body', None),
    'settings_transfer_body_off': ('settings.transfer.body.off', None),
    'settings_transfer_too_large': ('settings.transfer.tooLarge', None),
    'name_remember_body': ('android.speakers.rememberVoice.description', 'name'),
    'name_remember_body_unnamed': ('android.speakers.rememberVoice.description.unnamed', None),
    'count_notes': ('format.searchCount.meetings', 'n'),
    'voices_stale': ('savedVoices.row.stale', None),
    'voices_footnote': ('android.savedVoices.howToAdd', None),
    'voices_forget_body': ('android.savedVoices.forget.body', None),
    'voices_forget_everyone_body': ('android.savedVoices.forgetEveryone.body', 'n'),
    'trash_intro': ('android.trash.retentionNote', None),
    'trash_delete_body': ('android.trash.delete.body', None),
    'trash_delete_all_title': ('android.trash.deleteAll.title', 'n'),
    'trash_is_empty_body': ('android.trash.empty.body', None),
}


def generated(root):
    raw = (root / VENDOR / SOURCE).read_bytes()
    pin = json.loads((root / VENDOR / 'UPSTREAM.json').read_text())['files'][SOURCE]
    if hashlib.sha256(raw).hexdigest() != pin['sha256']:
        raise ValueError('Phase 2 catalog differs from pinned SHA-256')
    entries = {e['id']: e for e in json.loads(raw)['entries']}
    for locale, directory in phase1.LOCALES.items():
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 f"<!-- Generated from cassini-ux {pin['commit']}; do not edit. -->", '<resources>']
        for name, (identifier, argument) in LABELS.items():
            entry = entries[identifier]
            expected = {argument} if argument else set()
            if set(entry.get('placeholders', {})) != expected:
                raise ValueError(f'Shared placeholder declaration changed: {identifier}')
            if 'android' not in entry['platforms']:
                raise ValueError(f'String is not for Android: {identifier}')
            translation = entry if locale == 'en' else entry['l10n'][locale]
            if 'plural' in entry:
                lines.append(f'    <plurals name="ui_{name}">')
                for quantity, text in translation['plural'].items():
                    value = phase1.android_text(text, argument, literal=quantity != 'other')
                    lines.append(f'        <item quantity="{quantity}">{value}</item>')
                lines.append('    </plurals>')
            else:
                text = entry['en'] if locale == 'en' else translation['text']
                value = phase1.android_text(text, argument)
                lines.append(f'    <string name="ui_{name}">{value}</string>')
        lines.append('</resources>')
        yield root / 'ui/src/main/res' / directory / 'phase2.xml', '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, content in generated(ROOT):
        if args.check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit(f'Stale shared resources: {path.relative_to(ROOT)}')
        else:
            path.write_text(content)


if __name__ == '__main__':
    main()
