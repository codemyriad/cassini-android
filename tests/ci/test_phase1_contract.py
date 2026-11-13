"""Byte pin and generated catalog resources remain reviewable without an Android SDK."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name, script):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / script)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PIN = load('ux_pin', 'check-ux-pin.py')
STRINGS = load('phase1_strings', 'generate-phase1-strings.py')


class Phase1ContractTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.pin = json.loads((ROOT / PIN.VENDOR / 'UPSTREAM.json').read_text())
        for name in ['UPSTREAM.json', *self.pin['files']]:
            target = self.root / PIN.VENDOR / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / PIN.VENDOR / name).read_bytes())

    def test_exact_bundle_and_generated_resources_pass(self):
        PIN.check(self.root)
        outputs = list(STRINGS.generated(self.root))
        self.assertEqual(6, len(outputs))
        for path, content in outputs:
            self.assertEqual((ROOT / path.relative_to(self.root)).read_text(), content)

    def test_every_vendored_input_is_byte_pinned(self):
        for name in self.pin['files']:
            with self.subTest(name=name):
                target = self.root / PIN.VENDOR / name
                original = target.read_bytes()
                target.write_bytes(original + b'\n')
                with self.assertRaisesRegex(ValueError, 'drifted'):
                    PIN.check(self.root)
                target.write_bytes(original)

    def test_missing_required_asset_cannot_be_hidden_by_removing_its_pin(self):
        del self.pin['files']['brand/mark/saturn-nav.svg']
        (self.root / PIN.VENDOR / 'UPSTREAM.json').write_text(json.dumps(self.pin))
        with self.assertRaisesRegex(ValueError, 'Required'):
            PIN.check(self.root)

    def test_inputs_cannot_silently_follow_different_commits(self):
        self.pin['files']['strings.json']['commit'] = '0' * 40
        (self.root / PIN.VENDOR / 'UPSTREAM.json').write_text(json.dumps(self.pin))
        with self.assertRaisesRegex(ValueError, 'one commit'):
            PIN.check(self.root)

    def test_unexpected_or_missing_placeholder_fails(self):
        with self.assertRaises(ValueError):
            STRINGS.android_text('Paused at {invented}', 'position')
        with self.assertRaises(ValueError):
            STRINGS.android_text('Paused', 'position')

    def test_android_singular_category_keeps_the_numeric_argument(self):
        self.assertEqual('%1$s orador', STRINGS.android_text('1 orador', 'n', literal=True))
        self.assertEqual('%1$s intervenant', STRINGS.android_text('{n} intervenant', 'n', literal=True))
        with self.assertRaisesRegex(ValueError, 'carry its count'):
            STRINGS.android_text('orador', 'n', literal=True)

    def test_missing_other_plural_fails_after_an_intentional_pin_update(self):
        path = self.root / PIN.VENDOR / 'strings.json'
        catalog = json.loads(path.read_text())
        entry = next(e for e in catalog['entries'] if e['id'] == 'phone.row.speakerCount')
        entry['plural'].pop('other')
        path.write_text(json.dumps(catalog))
        self.pin['files']['strings.json']['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        (self.root / PIN.VENDOR / 'UPSTREAM.json').write_text(json.dumps(self.pin))
        with self.assertRaisesRegex(ValueError, 'Missing other plural'):
            list(STRINGS.generated(self.root))


if __name__ == '__main__':
    unittest.main()
