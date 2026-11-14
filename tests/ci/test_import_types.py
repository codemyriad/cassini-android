"""The manifest's Open with and Share filters follow the vendored import-types contract (L-25, D-1030)."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('import_types', ROOT / 'scripts' / 'check-import-types.py')
CHECK = importlib.util.module_from_spec(spec)
spec.loader.exec_module(CHECK)


class ImportTypesTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for path in (CHECK.CONTRACT, CHECK.MANIFEST):
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            (self.root / path).write_bytes((ROOT / path).read_bytes())

    def test_repository_passes(self):
        CHECK.check(ROOT)
        self.assertNotIn('audio/flac', CHECK.decided(ROOT))
        self.assertIn('audio/mpeg', CHECK.decided(ROOT))

    def test_a_broad_or_open_type_in_the_manifest_fails(self):
        manifest = self.root / CHECK.MANIFEST
        for extra in ('audio/*', 'audio/flac', 'application/ogg'):
            with self.subTest(extra=extra):
                original = manifest.read_text()
                manifest.write_text(original.replace(
                    '<data android:mimeType="audio/x-wav" />',
                    f'<data android:mimeType="audio/x-wav" />\n<data android:mimeType="{extra}" />', 1))
                with self.assertRaisesRegex(ValueError, 'differ'):
                    CHECK.check(self.root)
                manifest.write_text(original)

    def test_a_newly_decided_type_must_reach_the_manifest(self):
        contract = self.root / CHECK.CONTRACT
        data = json.loads(contract.read_text())
        next(t for t in data['types'] if t['id'] == 'flac')['status'] = 'decided'
        contract.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'differ'):
            CHECK.check(self.root)


if __name__ == '__main__':
    unittest.main()
