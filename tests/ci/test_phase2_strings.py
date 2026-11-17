"""L-67 wording stays pinned, translated, and free of legacy meeting nouns."""
import importlib.util
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

LEGACY_NOUN = re.compile(r'\b(notes?|not[ae]s?)\b', re.IGNORECASE)
ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('phase2_strings', ROOT / 'scripts/generate-phase2-strings.py')
PHASE2 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PHASE2)


class Phase2StringsTest(unittest.TestCase):
    def test_generated_resources_match_the_catalog_in_all_six_languages(self):
        outputs = list(PHASE2.generated(ROOT))
        self.assertEqual(6, len(outputs))
        for path, content in outputs:
            self.assertEqual(content, path.read_text(), path)
            for entry in ET.fromstring(content):
                for text in entry.itertext():
                    self.assertIsNone(LEGACY_NOUN.search(text), (path, entry.get('name'), text))

    def test_phase2_screen_strings_have_no_legacy_meeting_nouns(self):
        prefixes = ('ui_settings_', 'ui_voices_', 'ui_name_', 'ui_sp_', 'ui_tx_', 'ui_export_',
                    'ui_backup_', 'ui_trash_', 'ui_headline_', 'ui_about_', 'ui_actions_')
        for directory in PHASE2.phase1.LOCALES.values():
            for path in (ROOT / 'ui/src/main/res' / directory).glob('*.xml'):
                for entry in ET.parse(path).getroot():
                    if entry.get('name', '').startswith(prefixes):
                        for text in entry.itertext():
                            self.assertIsNone(LEGACY_NOUN.search(text), (path, entry.get('name'), text))

    def test_recording_titles_agree_and_notifications_use_meeting_nouns(self):
        for directory in PHASE2.phase1.LOCALES.values():
            resources = {}
            for module in ('app', 'ui'):
                path = ROOT / module / 'src/main/res' / directory / 'strings.xml'
                resources[module] = {e.get('name'): e.text for e in ET.parse(path).getroot()}
            self.assertEqual(resources['app']['rec_default_title'], resources['ui']['ui_voice_note_title'], directory)
            for name, text in resources['app'].items():
                if name.startswith('notif_') or name == 'rec_default_title':
                    self.assertIsNone(LEGACY_NOUN.search(text or ''), (directory, name, text))

    def test_backup_notice_keeps_d98_copy_after_catalog_migration(self):
        expected = {
            'backup_card_title': 'backup.phoneOnlyCard.title',
            'backup_card_body': 'backup.phoneOnlyCard.body',
            'backup_card_action': 'backup.phoneOnlyCard.action',
            'backup_card_dismiss': 'backup.phoneOnlyCard.dismiss',
            'backup_card_close': 'backup.phoneOnlyCard.close.a11y',
        }
        for name, identifier in expected.items():
            self.assertEqual(identifier, PHASE2.LABELS[name][0])

    def test_catalog_checksum_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            vendor = root / PHASE2.VENDOR
            (vendor / 'phase2').mkdir(parents=True)
            (vendor / 'UPSTREAM.json').write_bytes((ROOT / PHASE2.VENDOR / 'UPSTREAM.json').read_bytes())
            (vendor / PHASE2.SOURCE).write_bytes(b'{}')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                list(PHASE2.generated(root))

    def test_moved_resources_have_no_legacy_override(self):
        names = {'ui_' + name for name in PHASE2.LABELS}
        for directory in PHASE2.phase1.LOCALES.values():
            resources = ET.parse(ROOT / 'ui/src/main/res' / directory / 'strings.xml').getroot()
            self.assertFalse(names & {entry.get('name') for entry in resources})


if __name__ == '__main__':
    unittest.main()
