"""Shared badge copy must retain its byte pin, locale coverage and timer placeholder."""
import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('badge_strings', ROOT / 'scripts/generate-w2-badge-strings.py')
BADGES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BADGES)


class BadgeStringsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for name in ('strings.json', 'UPSTREAM.json'):
            target = self.root / BADGES.VENDOR / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / BADGES.VENDOR / name).read_bytes())
        for path, text in BADGES.generated(self.root):
            path.parent.mkdir(parents=True)
            path.write_text(text)

    def check(self):
        with patch.object(BADGES, 'ROOT', self.root), patch('sys.argv', ['badges', '--check']):
            BADGES.main()

    def test_all_six_languages_have_the_same_labels_and_timer_argument(self):
        self.check()
        outputs = list(BADGES.generated(self.root))
        self.assertEqual(6, len(outputs))
        for path, text in outputs:
            with self.subTest(locale=path.parent.name):
                resources = {item.attrib['name']: item for item in ET.fromstring(text)}
                labels = {name: item.text for name, item in resources.items()}
                self.assertEqual({'ui_badge_' + key for key in BADGES.LABELS}, labels.keys())
                self.assertEqual(1, labels['ui_badge_recording'].count('%1$s'))
                forms = {item.attrib['quantity']: item.text for item in resources['ui_badge_transcribing_spoken_eta']}
                self.assertEqual(1, forms['other'].count('%1$s'))
                self.assertIn('one', forms)
                if path.parent.name == 'values':
                    self.assertEqual('Transcribing, about 1 minute left', forms['one'])
                self.assertNotIn('{liveBadge}', text)

    def test_missing_locale_file_fails(self):
        (self.root / 'ui/src/main/res/values-fr/w2_badges.xml').unlink()
        with self.assertRaisesRegex(SystemExit, 'Stale shared badge'):
            self.check()

    def test_edited_translation_fails(self):
        path = self.root / 'ui/src/main/res/values-it/w2_badges.xml'
        path.write_text(path.read_text().replace('%1$s', '%1$d'))
        with self.assertRaisesRegex(SystemExit, 'Stale shared badge'):
            self.check()

    def test_changed_catalog_bytes_fail_before_generation(self):
        path = self.root / BADGES.VENDOR / 'strings.json'
        path.write_bytes(path.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            self.check()
