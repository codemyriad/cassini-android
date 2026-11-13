"""Android's filtered SVG export remains byte-pinned without a platform renderer in CI."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('nav_mark', ROOT / 'scripts/check-nav-mark.py')
MARK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MARK)


class NavMarkTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.receipt = json.loads((ROOT / 'scripts/nav-mark.json').read_text())
        self.paths = ['scripts/nav-mark.json', 'third_party/cassini-ux/brand/mark/saturn-nav.svg',
                      *[row['path'] for row in self.receipt['images']]]
        for name in self.paths:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())

    def test_exact_exports_pass(self):
        MARK.check(self.root)

    def test_source_and_every_export_reject_drift(self):
        for name in self.paths[1:]:
            with self.subTest(path=name):
                target = self.root / name
                original = target.read_bytes()
                target.write_bytes(original + b'changed')
                with self.assertRaises(ValueError):
                    MARK.check(self.root)
                target.write_bytes(original)

    def test_omitting_a_density_does_not_hide_it(self):
        self.receipt['images'].pop()
        (self.root / 'scripts/nav-mark.json').write_text(json.dumps(self.receipt))
        with self.assertRaisesRegex(ValueError, 'density'):
            MARK.check(self.root)
