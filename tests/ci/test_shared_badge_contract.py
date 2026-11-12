"""Pin and cross-check the complete shared badge fixture family at one merged revision."""
import contextlib
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'third_party/cassini-ux'
MERGED = '793209cb3425bece7143178458fddd94b691e12d'


class SharedBadgeContractTest(unittest.TestCase):
    def setUp(self):
        self.pin = json.loads((ROOT / 'UPSTREAM.json').read_text())
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for name, record in self.pin['files'].items():
            if record.get('commit', self.pin['commit']) != MERGED:
                continue
            target = self.root / record['source']
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())
        spec = importlib.util.spec_from_file_location('shared_badge_verifier', self.root / 'vectors/states/verify.py')
        self.verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.verifier)

    def verify(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.verifier.verify()

    def test_every_fixture_has_its_exact_digest_and_one_merged_contract_pin(self):
        self.assertEqual(MERGED, self.pin['commit'])
        for name, record in self.pin['files'].items():
            self.assertEqual(record['sha256'], hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), name)
            if name not in ('brand/tokens.json', 'retained-detail-strings.json'):
                self.assertEqual(MERGED, record.get('commit', self.pin['commit']), name)
        self.assertEqual(3, self.verifier.read('vectors/states/states-vectors.json')['version'])
        self.assertEqual(5, self.verifier.read('vectors/format/format-vectors.json')['version'])
        self.assertEqual(5, len(list((self.root / 'vectors/library-vectors/expected').glob('*.json'))))
        self.verify()

    def test_semantic_corruptions_fail_the_real_canonical_verifier(self):
        # Exercise the shared verifier, beyond digest checks: no altered runtime policy is introduced into Android.
        states = 'vectors/states/states-vectors.json'
        cases = (
            (states, lambda d: d['vectors'][0]['badge'].update(tint='neutral')),
            (states, lambda d: d['vectors'][0]['badge'].pop('glyph')),
            (states, lambda d: d.update(entryVectors=[])),
            ('vectors/format/format-vectors.json',
             lambda d: d['eta'][2].update(out='Transcribing, about 4 minutes left')),
            ('vectors/library-vectors/expected/main.json',
             lambda d: d['groups'][0]['meetings'][0].update(badge='Wrong badge')),
        )
        for name, corrupt in cases:
            with self.subTest(fixture=name, corruption=corrupt):
                path = self.root / name
                original = path.read_bytes()
                try:
                    value = json.loads(original)
                    corrupt(value)
                    path.write_text(json.dumps(value))
                    with self.assertRaises(AssertionError):
                        self.verify()
                finally:
                    path.write_bytes(original)
                self.verify()
