"""The compact artifact preserves every emulator input and refuses ambiguous or missing APKs."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/stage-emulator-inputs.py'
SPEC = importlib.util.spec_from_file_location('stage_emulator_inputs', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EmulatorInputsTest(unittest.TestCase):
    def fixture(self, root, variant):
        files = {
            'dist/cassini-1-x86_64.apk': b'published',
            f'app/build/outputs/apk/{variant}/app-x86_64-test.apk': b'under-test',
            f'app/build/outputs/apk/androidTest/{variant}/instrumentation.apk': b'tests',
            'data/build/outputs/apk/androidTest/debug/data.apk': b'data',
            'dist/cassini-1-arm64-v8a.apk': b'arm',
            f'app/build/outputs/apk/{variant}/app-universal-test.apk': b'universal',
        }
        for name, content in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)

    def test_debug_and_release_test_keep_the_four_exact_inputs(self):
        for variant in ('debug', 'releaseTest'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as work:
                root = Path(work)
                self.fixture(root, variant)
                output = MODULE.stage(root, variant)
                self.assertEqual(
                    {p.relative_to(output).as_posix(): p.read_bytes() for p in output.rglob('*.apk')},
                    {'ci-apk/cassini-1-x86_64.apk': b'published',
                     f'ci-tests/{variant}/app-x86_64-test.apk': b'under-test',
                     f'ci-tests/androidTest/{variant}/instrumentation.apk': b'tests',
                     'ci-data-tests/data.apk': b'data'},
                )
                (output / 'stale.apk').write_bytes(b'stale')
                MODULE.stage(root, variant)
                self.assertFalse((output / 'stale.apk').exists())

    def test_missing_or_duplicate_inputs_fail(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            self.fixture(root, 'debug')
            apk = root / 'dist/cassini-1-x86_64.apk'
            apk.unlink()
            with self.assertRaises(SystemExit):
                MODULE.stage(root, 'debug')
            apk.write_bytes(b'published')
            (apk.parent / 'another-x86_64.apk').write_bytes(b'duplicate')
            with self.assertRaises(SystemExit):
                MODULE.stage(root, 'debug')
