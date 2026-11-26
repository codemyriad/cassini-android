"""D112 Background voices and speaker row resources (D-1168) come from their own pinned cassini-ux catalog."""
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


PIN = load('ux_pin_d112', 'check-ux-pin.py')
D112 = load('d112_strings', 'generate-d112-strings.py')
PHASE1 = load('phase1_strings_d112', 'generate-phase1-strings.py')


class D112StringsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        shutil.copytree(ROOT / PIN.VENDOR, self.root / PIN.VENDOR)
        self.pin_path = self.root / PIN.VENDOR / 'UPSTREAM.json'
        self.pin = json.loads(self.pin_path.read_text())

    def test_generated_resources_are_committed(self):
        PIN.check(self.root)
        outputs = list(D112.generated(self.root))
        self.assertEqual(len(D112.LOCALES), len(outputs))
        for path, content in outputs:
            self.assertEqual((ROOT / path.relative_to(self.root)).read_text(), content)

    def test_english_wording_is_the_ticket_wording(self):
        content = dict(D112.generated(self.root))[self.root / 'ui/src/main/res/values/d112.xml']
        root = ET.fromstring(content)
        strings = {e.get('name'): e.text for e in root.iter('string')}
        plurals = {p.get('name'): {i.get('quantity'): i.text for i in p} for p in root.iter('plurals')}
        self.assertEqual('Save', strings['ui_sp_save'])
        self.assertEqual('Background voice', strings['ui_bg_switch'])
        self.assertEqual('Not needed for a background voice', strings['ui_bg_name_placeholder'])
        self.assertEqual('%1$s is now a background voice', strings['ui_bg_toast_single'])
        self.assertEqual('%1$s background voices hidden', plurals['ui_bg_hidden']['other'])
        self.assertEqual('%1$s background voice hidden', plurals['ui_bg_hidden']['one'])
        self.assertEqual('Named %1$s · %2$s background voices', plurals['ui_bg_toast_named']['other'])
        self.assertEqual('%1$s background voices', plurals['ui_bg_toast_count']['other'])

    def test_save_is_generated_once(self):
        self.assertNotIn('sp_save', PHASE1.LABELS)
        for path, content in PHASE1.generated(self.root):
            self.assertNotIn('name="ui_sp_save"', content, path)

    def test_catalog_names_its_own_commit(self):
        entry = self.pin['files'][D112.SOURCE]
        self.assertEqual('60ac93da94ce433412c93d8e96a76c68503dcec3', entry['commit'])
        del entry['commit']
        self.pin_path.write_text(json.dumps(self.pin))
        with self.assertRaisesRegex(ValueError, 'own commit'):
            PIN.check(self.root)

    def test_drifted_bytes_are_rejected(self):
        path = self.root / PIN.VENDOR / D112.SOURCE
        path.write_bytes(path.read_bytes() + b' ')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            list(D112.generated(self.root))


if __name__ == '__main__':
    unittest.main()
