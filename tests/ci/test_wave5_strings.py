"""The D-975 hero resources follow the separately pinned Wave 5 catalog (D98 copy) in every app language."""
import importlib.util
import json
import shutil
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name, script):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PIN = load('ux_pin_wave5', 'check-ux-pin.py')
WAVE5 = load('wave5_strings', 'generate-wave5-strings.py')


class Wave5StringsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        shutil.copytree(ROOT / PIN.VENDOR, self.root / PIN.VENDOR)
        self.pin_path = self.root / PIN.VENDOR / 'UPSTREAM.json'
        self.pin = json.loads(self.pin_path.read_text())

    def test_generated_resources_are_committed_and_match_the_catalog(self):
        PIN.check(self.root)
        catalog = {e['id']: e for e in json.loads((self.root / PIN.VENDOR / WAVE5.SOURCE).read_text())['entries']}
        outputs = list(WAVE5.generated(self.root))
        self.assertEqual(len(WAVE5.LOCALES), len(outputs))
        for path, content in outputs:
            self.assertEqual((ROOT / path.relative_to(self.root)).read_text(), content)
            locale = next(k for k, v in WAVE5.LOCALES.items() if v == path.parent.name)
            resources = {e.get('name'): e.text for e in ET.fromstring(content)}
            for name, identifier in WAVE5.LABELS.items():
                entry = catalog[identifier]
                expected = entry['en'] if locale == 'en' else entry['l10n'][locale]['text']
                self.assertEqual(expected.replace("'", "\\'"), resources['ui_' + name], (locale, name))

    def test_wave5_catalog_names_its_own_commit(self):
        entry = self.pin['files'][WAVE5.SOURCE]
        self.assertEqual('31973ff9784fcc66c1c7993b903a336e8f2c1432', entry['commit'])
        self.assertNotEqual(self.pin['commit'], entry['commit'])
        del entry['commit']
        self.pin_path.write_text(json.dumps(self.pin))
        with self.assertRaisesRegex(ValueError, 'own commit'):
            PIN.check(self.root)

    def test_drifted_wave5_bytes_are_rejected(self):
        path = self.root / PIN.VENDOR / WAVE5.SOURCE
        path.write_bytes(path.read_bytes() + b' ')
        with self.assertRaisesRegex(ValueError, 'drifted'):
            PIN.check(self.root)
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            list(WAVE5.generated(self.root))

    def test_the_phase1_catalog_keeps_the_phone_received_badge_until_l10(self):
        phase1 = {e['id']: e for e in json.loads((self.root / PIN.VENDOR / 'strings.json').read_text())['entries']}
        self.assertEqual('Received', phase1['states.badge.received']['en'])


if __name__ == '__main__':
    unittest.main()
