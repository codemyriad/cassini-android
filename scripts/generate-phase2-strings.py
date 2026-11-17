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
    'settings_voices': ('common.savedVoices', None),
    'settings_trash': ('settings.phone.trash', None),
    'settings_language_phone': ('settings.phone.language.system', None),
    'headline_waiting': ('android.transcribing.waitingForPrevious', None),
    'count_ahead_of_note': ('android.transcribing.aheadCount', 'n'),
    'action_open_note': ('android.meeting.open', None),
    'settings_transfer_body': ('settings.transfer.body', None),
    'settings_transfer_body_off': ('settings.transfer.body.off', None),
    'settings_transfer_too_large': ('settings.transfer.tooLarge', None),
    'count_notes': ('format.searchCount.meetings', 'n'),
    'voices_stale': ('savedVoices.row.stale', None),
    'voices_footnote': ('android.savedVoices.howToAdd', None),
    'voices_forget_body': ('android.savedVoices.forget.body', None),
    'voices_forget_everyone_body': ('android.savedVoices.forgetEveryone.body', 'n'),
    'trash_intro': ('android.trash.retentionNote', None),
    'trash_delete_body': ('android.trash.delete.body', None),
    'trash_delete_all_title': ('android.trash.deleteAll.title', 'n'),
    'trash_is_empty_body': ('android.trash.empty.body', None),
    'settings_transcripts': ('settings.phone.group.transcripts', None),
    'settings_meetings': ('settings.phone.group.meetings', None),
    'settings_app': ('settings.phone.group.app', None),
    'transcription_title': ('settings.tab.transcription', None),
    'speakers_title': ('common.speakers', None),
    'speaker_on': ('models.status.on', None),
    'setting_off': ('settings.answer.off', None),
    'settings_backup': ('settings.phone.backup', None),
    'backup_answer_transfer': ('settings.phone.backup.answer.transfer', None),
    'settings_language_label': ('settings.phone.language', None),
    'settings_about_subtitle': ('settings.phone.aboutRow.subtitle', None),
    'transcription_for_new': ('settings.phone.transcription.forNew', None),
    'model_whistle': ('models.whistle.name', None),
    'model_whistle_short': ('models.whistle.description.short', None),
    'model_parakeet': ('models.parakeetUltra.name', None),
    'model_parakeet_short': ('models.parakeetUltra.description.short', None),
    'model_built_in': ('models.builtIn', None),
    'model_download': ('models.download', None),
    'models_keep_transcript': ('models.keepTranscript', None),
    'transcription_online_heading': ('settings.phone.transcription.goingOnline', None),
    'models_where_from': ('models.whereFrom', None),
    'transcription_online': ('settings.phone.transcription.online', None),
    'speakers_who': ('settings.phone.speakers.whoIsSpeaking', None),
    'speakers_tell_apart': ('models.tellSpeakersApart.name', None),
    'speakers_tell_apart_short': ('models.tellSpeakersApart.description.short', None),
    'speakers_recognise': ('models.recogniseVoices.name', None),
    'speakers_recognise_short': ('models.recogniseVoices.description.short', None),
    'speakers_on_device': ('settings.phone.speakers.runsOnDevice.phone', None),
    'backup_cloud_title': ('backup.cloud.title', None),
    'backup_cloud_body_on': ('backup.cloud.body.on', None),
    'backup_cloud_body_off': ('backup.cloud.body.off', None),
    'backup_phone_only_body': ('backup.phoneOnly.body', None),
    'backup_voices_title': ('backup.savedVoices.switch', None),
    'backup_voices_subtitle': ('backup.includeSavedVoices.subtitle', None),
    'backup_voices_subtitle_off': ('backup.includeSavedVoices.subtitle.off', None),
    'backup_info_android_heading': ('backup.info.android.heading', None),
    'backup_info_android_body': ('backup.info.android.body', None),
    'backup_info_over_quota': ('backup.info.android.overQuota', None),
    'backup_card_title': ('backup.phoneOnlyCard.title', None),
    'backup_card_body': ('backup.phoneOnlyCard.body', None),
    'backup_card_action': ('backup.phoneOnlyCard.action', None),
    'backup_card_dismiss': ('backup.phoneOnlyCard.dismiss', None),
    'backup_card_close': ('backup.phoneOnlyCard.close.a11y', None),
    'settings_transfer_body_off_backup_on': ('settings.transfer.body.offBackupOn', None),
    'backup_page_transfer': ('backup.page.answer.transfer', None),
    'backup_meetings_subtitle': ('backup.meetings.switch.subtitle', None),
    'backup_voices_why': ('backup.savedVoices.switch.why', None),
    'backup_more_detail': ('settings.moreDetail', None),
    'backup_moves': ('backup.info.moves.heading', None),
    'backup_who': ('backup.info.who.heading', None),
    'backup_meetings': ('backup.info.row.meetings', None),
    'backup_meetings_detail': ('backup.info.row.meetings.detail', None),
    'backup_settings': ('backup.info.row.settings', None),
    'backup_settings_detail': ('backup.info.row.settings.detail', None),
    'backup_voices_with_meetings': ('backup.info.row.savedVoices.withMeetings', None),
    'backup_models': ('backup.info.row.models', None),
    'backup_models_detail': ('backup.info.row.models.detail.phone', None),
    'backup_copied': ('backup.info.status.copied', None),
    'backup_not_copied': ('backup.info.status.notCopied', None),
    'backup_new_phone': ('backup.info.who.newPhone', None),
    'backup_new_phone_detail': ('backup.info.who.newPhone.detail', None),
    'backup_zip': ('backup.info.who.zip', None),
    'backup_zip_detail': ('backup.info.who.zip.detail', None),
    'backup_never': ('backup.info.cassiniNever', None),
    'backup_own_copy': ('backup.ownCopy.heading', None),
    'backup_zip_subtitle': ('backup.ownCopy.zip.subtitle', None),
    'backup_export_all': ('export.zip.all', None),
    'settings_transfer_title': ('settings.transfer.title', None),
    'about_title': ('about.title', None),
    'about_tagline': ('about.tagline.phone', None),
    'about_help': ('about.help', None),
    'about_help_subtitle': ('about.help.subtitle', None),
    'about_privacy': ('about.privacy', None),
    'about_privacy_subtitle': ('about.privacy.subtitle.phone', None),
    'about_licences': ('about.licencesNotices', None),
    'about_licences_subtitle': ('about.licencesNotices.subtitle', None),
    'about_source': ('about.sourceCode', None),
    'settings_people': ('savedVoices.peopleCount', 'n'),
    'settings_meeting_count': ('library.meetingCount', 'n'),
    'settings_audio_hours': ('settings.phone.audioHours', 'n'),
    'settings_library_stats': ('settings.phone.libraryStats', ('meetings', 'hours')),
    'about_name_version': ('about.nameVersion', 'version'),
    'about_version': ('about.version', 'version'),
    'about_licence_line': ('about.licenceLine.phone', 'year'),
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
            expected = {argument} if isinstance(argument, str) else set(argument or ())
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
