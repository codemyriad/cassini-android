#!/usr/bin/env python3
"""Generate Phase 1 Android resources from the checksum-pinned shared catalog."""
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
LABELS = {
    'badge_recording': ('states.badge.recording', 'liveBadge'),
    'badge_importing': ('states.badge.importing', None),
    'badge_saving': ('states.badge.saving', None),
    'badge_transcribing': ('states.badge.transcribing.noEta', None),
    'badge_transcribing_eta': ('states.badge.transcribing', 'n'),
    'badge_transcribing_under_minute': ('states.badge.transcribing.underMinute', None),
    'badge_attention': ('states.badge.needsAttention', None),
    'badge_interrupted': ('states.badge.interrupted', None),
    'badge_received': ('states.badge.received', None),
    'badge_queued': ('states.phone.queued', None),
    'badge_paused': ('states.phone.pausedAt', 'position'),
    'badge_failed': ('states.phone.couldntTranscribe', None),
    'badge_audio_only': ('states.phone.audioOnly', None),
    'badge_unplayable': ('states.phone.cantPlay', None),
    'badge_unreadable': ('states.phone.cantReadTranscript', None),
    'badge_newer': ('states.phone.newerFormat', None),
    'all_meetings': ('library.allMeetings', None),
    'row_speaker_count': ('phone.row.speakerCount', 'n'),
    'row_no_speech': ('phone.row.noSpeech', None),
    'row_transcribed_a11y': ('phone.row.transcribed.a11y', None),
    'row_resume': ('phone.row.resume', None),
}


def android_text(text, argument, *, literal=False):
    # Android may select `one` for zero (Portuguese), so keep the count dynamic
    # even when the shared catalog spells its singular example with a literal 1.
    if literal and argument == 'n' and '{n}' not in text:
        text, count = re.subn(r'(?<!\w)1(?!\w)', '{n}', text)
        if count != 1:
            raise ValueError('Shared count plural must carry its count')
    expected = {argument} if argument else set()
    actual = set(re.findall(r'\{([^{}]+)\}', text))
    if (literal and not actual <= expected) or (not literal and actual != expected):
        raise ValueError('Shared string placeholders changed')
    if argument:
        text = text.replace('{' + argument + '}', '%1$s')
    return escape(text).replace("'", "\\'").replace('"', '\\"')


def generated(root):
    raw = (root / VENDOR / 'strings.json').read_bytes()
    pin = json.loads((root / VENDOR / 'UPSTREAM.json').read_text())
    if hashlib.sha256(raw).hexdigest() != pin['files']['strings.json']['sha256']:
        raise ValueError('Shared catalog differs from pinned SHA-256')
    entries = {entry['id']: entry for entry in json.loads(raw)['entries']}
    for locale, qualifier in LOCALES.items():
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 f"<!-- Generated from cassini-ux {pin['commit']}; do not edit. -->", '<resources>']
        for name, (identifier, argument) in LABELS.items():
            entry = entries[identifier]
            if set(entry.get('placeholders', {})) != ({argument} if argument else set()):
                raise ValueError(f'Shared placeholder declaration changed: {identifier}')
            if 'plural' in entry:
                forms = entry['plural'] if locale == 'en' else entry['l10n'][locale]['plural']
                if 'other' not in forms:
                    raise ValueError(f'Missing other plural: {identifier}/{locale}')
                lines.append(f'    <plurals name="ui_{name}">')
                for quantity, text in forms.items():
                    value = android_text(text, argument, literal=quantity != 'other')
                    lines.append(f'        <item quantity="{quantity}">{value}</item>')
                lines.append('    </plurals>')
            else:
                text = entry['en'] if locale == 'en' else entry['l10n'][locale]['text']
                value = android_text(text, argument)
                lines.append(f'    <string name="ui_{name}">{value}</string>')
        lines.append('</resources>')
        yield root / 'ui/src/main/res' / qualifier / 'phase1.xml', '\n'.join(lines) + '\n'


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
