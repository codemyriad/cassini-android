"""The Included exception must not disable CI's accidental-weight/key packaging guard."""

import hashlib
import importlib.util
import io
import unittest
import warnings
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'apk_models', Path(__file__).resolve().parents[1] / 'scripts/apk_models.py',
)
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


class ApkModelsTests(unittest.TestCase):
    def setUp(self):
        self.data = {GUARD.WEIGHTS: b'fake weights'}
        self.pins = {name: dict(size=len(data), sha256=hashlib.sha256(data).hexdigest())
                     for name, data in self.data.items()}

    def check(self, entries, compression=zipfile.ZIP_STORED):
        buffer = io.BytesIO()
        with warnings.catch_warnings(), zipfile.ZipFile(buffer, 'w', compression=compression) as archive:
            warnings.simplefilter('ignore', UserWarning)  # The duplicate-entry refusal case is deliberate.
            for name, data in entries:
                archive.writestr(name, data)
        with zipfile.ZipFile(buffer) as archive:
            GUARD.check_apk_models(archive, self.pins)

    def test_only_pinned_uncompressed_included_models_are_accepted(self):
        self.check(list(self.data.items()) + [('assets/licenses/license.txt', b'license')])

    def test_x86_has_no_weights(self):
        self.check([('lib/x86_64/libtest.so', b'fake code')])
        with self.assertRaises(ValueError):
            self.check([('lib/x86_64/libcassini_whistle_weights.so', b'fake weights')])

    def test_other_weights_and_keystores_are_still_refused(self):
        for name in ['assets/parakeet.onnx', 'assets/extra.cact', 'assets/extra.tflite', 'assets/release.keystore']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.check(list(self.data.items()) + [(name, b'unexpected')])

    def test_x86_cannot_carry_private_arm64_runtimes(self):
        for name in ['libcassini_needle.so', 'libcassini_whistle_ort.so']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.check([(f'lib/x86_64/{name}', b'unexpected')])

    def test_missing_duplicate_and_compressed_models_are_refused(self):
        entries = list(self.data.items())
        for bad in [[('lib/arm64-v8a/libtest.so', b'fake code')], entries + [entries[0]]]:
            with self.assertRaises(ValueError):
                self.check(bad)
        with self.assertRaises(ValueError):
            self.check(entries, zipfile.ZIP_DEFLATED)

    def test_size_and_same_size_corruption_are_refused(self):
        entries = list(self.data.items())
        for bad in [b'truncated', b'FAKE weights']:
            with self.assertRaises(ValueError):
                self.check([(entries[0][0], bad)])


if __name__ == '__main__':
    unittest.main()
