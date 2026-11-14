"""L-67 wording stays pinned, translated, and free of legacy meeting nouns."""
import importlib.util
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

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
            legacy = r'\b(notes?|not[ae]s?)\b'
            for entry in ET.fromstring(content):
                for text in entry.itertext():
                    self.assertIsNone(re.search(legacy, text, re.IGNORECASE), (path, entry.get('name'), text))

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
