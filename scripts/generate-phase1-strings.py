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
    'library_import': ('import.button.mobile', None),
    'all_meetings': ('library.allMeetings', None),
    'back_to_library': ('library.backToAllMeetings', None),
    'recording_library': ('library.allMeetings', None),
    'recording_minimise': ('library.backToAllMeetings', None),
    'note_search_placeholder': ('common.search', None),
    'recording_finish': ('common.stop', None),
    'recording_pause_label': ('player.pause', None),
    'recording_resume_label': ('phone.row.resume', None),
    'library_search_placeholder': ('common.search', None),
    'library_search_description': ('search.field.a11y', None),
    'row_speaker_count': ('phone.row.speakerCount', 'n'),
    'row_no_speech': ('phone.row.noSpeech', None),
    'row_resume': ('phone.row.resume', None),
    'row_try_again': ('common.tryAgain', None),
    'search_meetings': ('format.searchCount.meetings', 'n'),
    'tx_heading': ('transcribing.heading', None),
    'sp_dialog_a11y': ('speakers.dialog.a11y', None),
    'sp_name': ('speakers.nameLabel', None),
    'sp_play_sample': ('speakers.playSample', None),
    'sp_quote': ('common.quote', 'quote'),
    'sp_talk_time': ('speakers.talkTime', 'talkTime'),
    'sp_duplicate_notice': ('speakers.duplicate.notice', 'name'),
    'sp_same_person': ('speakers.duplicate.samePerson', None),
    'sp_someone_else': ('speakers.duplicate.someoneElse', None),
    'sp_voices_ranked': ('speakers.savedVoices.heading', None),
    'sp_voices': ('common.savedVoices', None),
    'sp_likely': ('speakers.savedVoices.likely', None),
    'sp_remember_title': ('speakers.rememberVoice.title', None),
    'sp_remember_body': ('speakers.rememberVoice.cardBody.phone', 'name'),
    'sp_remember_unnamed': ('speakers.rememberVoice.cardBody.phone.unnamed', None),
    'sp_remember_too_short': ('speakers.rememberVoice.tooShort', None),
    'sp_remember_models': ('speakers.rememberVoice.modelsNeeded', None),
    'sp_merge': ('speakers.mergeInto', None),
    'sp_save': ('speakers.saveName', None),
    'tx_eta_minutes': ('transcribing.eta.minutes', 'n'),
    'tx_details': ('transcribing.details.a11y', None),
    'tx_stop': ('common.stop', None),
    'tx_stop_keep': ('transcribing.stopAndKeepProgress', None),
    'tx_transcript': ('transcribing.transcriptSoFar', None),
    'tx_reading': ('transcribing.step.reading', None),
    'tx_preparing': ('transcribing.step.preparing', None),
    'tx_words': ('transcribing.step.words', None),
    'tx_matching': ('transcribing.step.speakers', None),
    'tx_saving': ('transcribing.step.saving', None),
    'tx_audio_done': ('transcribing.stat.audioDone', None),
    'tx_audio_done_value': ('transcribing.stat.audioDoneValue', ('done', 'total')),
    'tx_time_left': ('transcribing.stat.timeLeft', None),
    'tx_time_left_value': ('transcribing.stat.timeLeftValue', 'n'),
    'tx_words_so_far': ('transcribing.stat.wordsSoFar', None),
    'tx_battery_finish': ('transcribing.stat.batteryAtFinish', None),
    'tx_suggestion_question': ('transcribing.speakerSuggestion', 'name'),
    'tx_suggestion_yes': ('transcribing.speakerSuggestion.yes', None),
    'tx_suggestion_no': ('transcribing.speakerSuggestion.no', None),
}
# L-10 (D99): the Meeting header subtitle of an imported meeting is the catalog's `format.previewSubtitle` followed by
# the words after `{duration}` of these entries. Android keeps building the day, time and duration itself (the "Date
# unknown" label included), so only that suffix is generated; `provenance_suffix` checks the rest is unchanged.
SUFFIXES = {
    'subtitle_imported': 'format.previewSubtitle.received',
    'subtitle_imported_other_device': 'format.previewSubtitle.importedOtherDevice',
}
SUBTITLE = 'format.previewSubtitle'
SUBTITLE_PLACEHOLDERS = {'day', 'time', 'duration'}
SEPARATOR = ' · '


def provenance_suffix(base, text):
    """The words after `{duration} · ` of [text], which must start with the plain subtitle [base]."""
    prefix = base + SEPARATOR
    if not base.endswith('{duration}') or not text.startswith(prefix) or '{' in text[len(prefix):]:
        raise ValueError('Imported subtitle no longer extends the plain subtitle')
    return text[len(prefix):]


def android_text(text, argument, *, literal=False):
    # Android may select `one` for zero (Portuguese), so keep the count dynamic
    # even when the shared catalog spells its singular example with a literal 1.
    if literal and argument == 'n' and '{n}' not in text:
        text, count = re.subn(r'(?<!\w)1(?!\w)', '{n}', text)
        if count != 1:
            raise ValueError('Shared count plural must carry its count')
    arguments = (argument,) if isinstance(argument, str) else (argument or ())
    expected = set(arguments)
    actual = set(re.findall(r'\{([^{}]+)\}', text))
    if (literal and not actual <= expected) or (not literal and actual != expected):
        raise ValueError('Shared string placeholders changed')
    for index, name in enumerate(arguments, start=1):
        text = text.replace('{' + name + '}', f'%{index}$s')
    return escape(text).replace('\n', r'\n').replace("'", "\\'").replace('"', '\\"')


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
            arguments = (argument,) if isinstance(argument, str) else (argument or ())
            if set(entry.get('placeholders', {})) != set(arguments):
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
        base = entries[SUBTITLE]['en'] if locale == 'en' else entries[SUBTITLE]['l10n'][locale]['text']
        for name, identifier in SUFFIXES.items():
            entry = entries[identifier]
            if set(entry.get('placeholders', {})) != SUBTITLE_PLACEHOLDERS:
                raise ValueError(f'Shared placeholder declaration changed: {identifier}')
            text = entry['en'] if locale == 'en' else entry['l10n'][locale]['text']
            value = android_text(provenance_suffix(base, text), None)
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
