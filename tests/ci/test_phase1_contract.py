"""Byte pin and generated catalog resources remain reviewable without an Android SDK."""
import hashlib
import importlib.util
import json
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

    def test_mobile_import_label_is_generated_from_the_catalog_in_every_locale(self):
        catalog = json.loads((self.root / PIN.VENDOR / 'strings.json').read_text())
        entry = next(e for e in catalog['entries'] if e['id'] == 'import.button.mobile')
        outputs = dict(STRINGS.generated(self.root))
        for locale, directory in STRINGS.LOCALES.items():
            with self.subTest(locale=locale):
                output = next(value for path, value in outputs.items() if path.parent.name == directory)
                actual = ET.fromstring(output).find("string[@name='ui_library_import']")
                self.assertIsNotNone(actual, 'mobile Import must be a generated catalog resource')
                expected = entry['en'] if locale == 'en' else entry['l10n'][locale]['text']
                self.assertEqual(expected, actual.text)
                handwritten = (ROOT / 'ui/src/main/res' / directory / 'strings.xml').read_text()
                self.assertNotIn('name="ui_library_import"', handwritten)

    def test_remember_privacy_newlines_survive_android_resource_parsing(self):
        for path, output in STRINGS.generated(self.root):
            with self.subTest(locale=path.parent.name):
                resources = ET.fromstring(output)
                for alias in ('ui_sp_remember_body', 'ui_sp_remember_unnamed'):
                    text = resources.find(f"string[@name='{alias}']").text
                    self.assertIn(r'\n', text)
                    self.assertNotIn('\n', text)

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

    def test_recording_stop_uses_the_shared_stop_in_every_locale(self):
        catalog = json.loads((self.root / PIN.VENDOR / 'strings.json').read_text())
        stop = next(e for e in catalog['entries'] if e['id'] == 'common.stop')
        outputs = dict(STRINGS.generated(self.root))
        for locale, directory in STRINGS.LOCALES.items():
            with self.subTest(locale=locale):
                output = next(value for path, value in outputs.items() if path.parent.name == directory)
                actual = ET.fromstring(output).find("string[@name='ui_recording_finish']")
                text = stop['en'] if locale == 'en' else stop['l10n'][locale]['text']
                self.assertIsNotNone(actual)
                self.assertEqual(text, actual.text)

    def test_recording_controls_and_notification_actions_use_the_catalog_in_every_locale(self):
        entries = {
            entry['id']: entry
            for entry in json.loads((self.root / PIN.VENDOR / 'strings.json').read_text())['entries']
        }
        expected_resources = {
            'ui_recording_finish': 'common.stop',
            'ui_recording_pause_label': 'player.pause',
            'ui_recording_resume_label': 'phone.row.resume',
            'ui_recording_library': 'library.allMeetings',
            'ui_recording_minimise': 'library.backToAllMeetings',
        }
        outputs = dict(STRINGS.generated(self.root))
        for locale, directory in STRINGS.LOCALES.items():
            with self.subTest(locale=locale):
                root = ET.fromstring(next(v for path, v in outputs.items() if path.parent.name == directory))
                ui_handwritten = (ROOT / 'ui/src/main/res' / directory / 'strings.xml').read_text()
                app_handwritten = (ROOT / 'app/src/main/res' / directory / 'strings.xml').read_text()
                for resource_name, catalog_id in expected_resources.items():
                    actual = root.find(f"string[@name='{resource_name}']")
                    entry = entries[catalog_id]
                    text = entry['en'] if locale == 'en' else entry['l10n'][locale]['text']
                    self.assertIsNotNone(actual, f'{resource_name} must be generated in {locale}')
                    self.assertEqual(text, actual.text)
                    self.assertNotIn(f'name="{resource_name}"', ui_handwritten)
                for retired in ('notif_action_finish', 'notif_action_pause'):
                    self.assertNotIn(f'name="{retired}"', app_handwritten)
        notifications = (
            ROOT / 'app/src/main/java/io/codemyriad/cassini/recording/RecordingNotifications.kt'
        ).read_text()
        for shared_action in ('ui_recording_pause_label', 'ui_recording_resume_label', 'ui_recording_finish'):
            self.assertIn(f'UiR.string.{shared_action}', notifications)

    def test_compact_eta_is_a_catalog_plural_in_every_locale(self):
        catalog = json.loads((self.root / PIN.VENDOR / 'strings.json').read_text())
        entry = next(e for e in catalog['entries'] if e['id'] == 'transcribing.eta.minutes')
        for locale, directory in STRINGS.LOCALES.items():
            output = next(text for path, text in STRINGS.generated(self.root) if path.parent.name == directory)
            plural = ET.fromstring(output).find("plurals[@name='ui_tx_eta_minutes']")
            self.assertIsNotNone(plural)
            actual = {item.attrib['quantity']: item.text for item in plural}
            forms = entry['plural'] if locale == 'en' else entry['l10n'][locale]['plural']
            self.assertEqual(set(forms), set(actual))
            for quantity, text in forms.items():
                expected = text.replace('{n}', '%1$s').replace('1 min', '%1$s min')
                self.assertEqual(expected, actual[quantity])

    def test_unexpected_or_missing_placeholder_fails(self):
        with self.assertRaises(ValueError):
            STRINGS.android_text('Paused at {invented}', 'position')
        with self.assertRaises(ValueError):
            STRINGS.android_text('Paused', 'position')

    def test_audio_progress_arguments_keep_their_identity_when_translation_reorders_them(self):
        self.assertEqual('Total %2$s, done %1$s',
                         STRINGS.android_text('Total {total}, done {done}', ('done', 'total')))
        for text in ['{done}', '{done} of {unknown}', '{done} of {total} {extra}']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                STRINGS.android_text(text, ('done', 'total'))

    def test_android_singular_category_keeps_the_numeric_argument(self):
        self.assertEqual('%1$s orador', STRINGS.android_text('1 orador', 'n', literal=True))
        self.assertEqual('%1$s intervenant', STRINGS.android_text('{n} intervenant', 'n', literal=True))
        with self.assertRaisesRegex(ValueError, 'carry its count'):
            STRINGS.android_text('orador', 'n', literal=True)

    def test_missing_other_plural_fails_after_an_intentional_pin_update(self):
        path = self.root / PIN.VENDOR / 'strings.json'
        catalog = json.loads(path.read_text())
        entry = next(e for e in catalog['entries'] if e['id'] == 'states.badge.transcribing')
        entry['plural'].pop('other')
        path.write_text(json.dumps(catalog))
        self.pin['files']['strings.json']['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        (self.root / PIN.VENDOR / 'UPSTREAM.json').write_text(json.dumps(self.pin))
        with self.assertRaisesRegex(ValueError, 'Missing other plural'):
            list(STRINGS.generated(self.root))


if __name__ == '__main__':
    unittest.main()
