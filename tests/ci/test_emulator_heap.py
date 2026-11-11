"""The API 29 system server must not inherit the image's fatal 16 MiB heap ceiling."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/ci-emulator-heap.py'
SPEC = importlib.util.spec_from_file_location('ci_emulator_heap', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EmulatorHeapTest(unittest.TestCase):
    def test_configure_replaces_duplicate_vm_keys_and_keeps_other_settings(self):
        with tempfile.TemporaryDirectory() as work:
            config = Path(work) / 'config.ini'
            config.write_text('hw.ramSize=2048\nvm.heapSize=16\nhw.heapSize=16\n vm.heapSize = 32\n')
            MODULE.configure(29, config)
            self.assertEqual('hw.ramSize=2048\nhw.heapSize=16\nvm.heapSize=512\n', config.read_text())
            first = config.read_bytes()
            MODULE.configure(29, config)
            self.assertEqual(first, config.read_bytes())

    def test_configure_adds_missing_vm_key(self):
        with tempfile.TemporaryDirectory() as work:
            config = Path(work) / 'config.ini'
            config.write_text('hw.ramSize=2048')
            MODULE.configure(29, config)
            self.assertEqual('hw.ramSize=2048\nvm.heapSize=512\n', config.read_text())

    def test_other_api_profiles_are_unchanged(self):
        for api in (26, 34, 35):
            with self.subTest(api=api), tempfile.TemporaryDirectory() as work:
                config = Path(work) / 'config.ini'
                config.write_bytes(b'vm.heapSize=228M\r\nhw.ramSize=2048\r\n')
                first = config.read_bytes()
                MODULE.configure(api, config)
                self.assertEqual(first, config.read_bytes())
                MODULE.verify(api, '16m')

    def test_guest_capacity_accepts_android_units(self):
        for value in ('512m', '512M', '1g', '524288k', '536870912', ' 512m '):
            with self.subTest(value=value):
                MODULE.verify(29, value)

    def test_guest_capacity_rejects_the_observed_oom_ceiling(self):
        for value in ('16m', '256m', '0', '512', '', 'unknown', '512MB'):
            with self.subTest(value=value), self.assertRaises(SystemExit):
                MODULE.verify(29, value)

    def test_missing_avd_config_is_an_error(self):
        with tempfile.TemporaryDirectory() as work, self.assertRaises(FileNotFoundError):
            MODULE.configure(29, Path(work) / 'missing.ini')
