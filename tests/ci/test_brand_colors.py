"""Mutation checks for the byte pin and the generated Kotlin, with no Gradle dependency."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('brand_colors', ROOT / 'scripts/generate-brand-colors.py')
BRAND = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BRAND)


class BrandColorsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('UPSTREAM.json', 'brand/tokens.json'):
            target = self.root / BRAND.VENDOR / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / BRAND.VENDOR / name).read_bytes())
        for name in ('temporary-old-palette.json', 'temporary-old-palette.sha256'):
            target = self.root / 'scripts/brand' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / 'scripts/brand' / name).read_bytes())
        self.regenerate()

    def regenerate(self):
        for name, content in BRAND.generated_files(self.root):
            output = self.root / name
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(content)

    def check(self):
        with patch.object(BRAND, 'ROOT', self.root), patch('sys.argv', ['generate-brand-colors.py', '--check']):
            BRAND.main()

    def test_exact_generated_source_passes(self):
        self.check()

    def test_changed_vendor_bytes_fail_even_if_json_is_equivalent(self):
        target = self.root / BRAND.VENDOR / 'brand/tokens.json'
        target.write_bytes(target.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            self.check()

    def test_manual_colour_edit_fails(self):
        target = self.root / BRAND.OUTPUT
        target.write_text(target.read_text().replace('0xFF', '0xFE', 1))
        with self.assertRaisesRegex(SystemExit, 'Stale generated'):
            self.check()

    def test_missing_generated_file_fails(self):
        (self.root / BRAND.OUTPUT).unlink()
        with self.assertRaisesRegex(SystemExit, 'Stale generated'):
            self.check()

    def test_both_modes_include_every_accepted_token_and_derived_role(self):
        _, roles = BRAND.tokens(self.root)
        self.assertIn('card-rest', roles)
        source = BRAND.generated(self.root)
        for role in roles:
            self.assertEqual(2, source.count(f'"{role}" to Color('))

    def test_brand_has_its_own_commit_without_repinning_the_strings(self):
        pin = json.loads((self.root / BRAND.VENDOR / 'UPSTREAM.json').read_text())
        commit, _ = BRAND.tokens(self.root)
        self.assertEqual(pin['files']['brand/tokens.json']['commit'], commit)
        self.assertNotEqual(pin['commit'], commit)

    def test_manual_window_xml_edit_fails_in_each_mode(self):
        for qualifier in ('values', 'values-night'):
            with self.subTest(qualifier=qualifier):
                self.regenerate()
                path = self.root / f'ui/src/main/res/{qualifier}/colors.xml'
                path.write_text(path.read_text().replace('#', '#00', 1))
                with self.assertRaisesRegex(SystemExit, 'Stale generated'):
                    self.check()

    def test_temporary_snapshot_is_checksum_frozen(self):
        path = self.root / BRAND.TEMPORARY
        path.write_bytes(path.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'frozen SHA-256'):
            self.check()

    def test_removing_snapshot_and_checksum_switches_to_canonical_only(self):
        path = self.root / BRAND.TEMPORARY
        path.unlink()
        path.with_suffix('.sha256').unlink()
        self.regenerate()
        self.check()
        source = (self.root / BRAND.OUTPUT).read_text()
        self.assertNotIn('LegacyLightColors', source)
        self.assertNotIn('LegacyDarkColors', source)
        self.assertNotIn('TEMPORARY', source)
        self.assertIn('bg = BrandTokenValues.dark.getValue("ground")', source)
        _, roles = BRAND.tokens(self.root)
        for mode, qualifier in [('light', 'values'), ('dark', 'values-night')]:
            xml = (self.root / f'ui/src/main/res/{qualifier}/colors.xml').read_text()
            self.assertIn(roles['ground'][mode], xml)
            self.assertIn(roles['ink'][mode], xml)
            self.assertNotIn('TEMPORARY', xml)
