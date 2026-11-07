"""Input verification must fail before a corrupt or unpinned binary can reach the linker."""

import hashlib
import importlib.util
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    'whistle_build', Path(__file__).resolve().parent.parent / 'scripts/build-whistle-runtime.py',
)
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class InputTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / 'libneedle.a'
        self.content = b'pinned engine input'
        self.pin = {
            'size': len(self.content), 'sha256': hashlib.sha256(self.content).hexdigest(),
            'url': 'https://example.invalid/pinned/libneedle.a',
        }

    def test_verified_cache_works_offline_without_network(self):
        self.path.write_bytes(self.content)
        with patch.object(BUILD.urllib.request, 'urlopen', side_effect=AssertionError('network forbidden')):
            BUILD.fetch(self.path, self.pin, offline=True)

    def test_same_size_corruption_is_rejected_and_not_replaced_online_or_offline(self):
        damaged = b'X' * len(self.content)
        for offline in (True, False):
            with self.subTest(offline=offline):
                self.path.write_bytes(damaged)
                with patch.object(BUILD.urllib.request, 'urlopen', side_effect=AssertionError('network forbidden')):
                    with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                        BUILD.fetch(self.path, self.pin, offline)
                self.assertEqual(self.path.read_bytes(), damaged)

    def test_missing_offline_input_cannot_download(self):
        with patch.object(BUILD.urllib.request, 'urlopen', side_effect=AssertionError('network forbidden')):
            with self.assertRaisesRegex(ValueError, 'Missing offline'):
                BUILD.fetch(self.path, self.pin, offline=True)

    def test_bad_and_oversized_downloads_never_become_cached_inputs(self):
        for body in (b'truncated', b'X' * len(self.content), self.content + b'oversized'):
            with self.subTest(body=body):
                with patch.object(BUILD.urllib.request, 'urlopen', return_value=BytesIO(body)):
                    with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                        BUILD.fetch(self.path, self.pin, offline=False)
                self.assertFalse(self.path.exists())
                self.assertFalse(list(self.path.parent.glob('*.part')))

    def test_valid_download_is_published_only_after_verification(self):
        with patch.object(BUILD.urllib.request, 'urlopen', return_value=BytesIO(self.content)):
            BUILD.fetch(self.path, self.pin, offline=False)
        self.assertEqual(self.path.read_bytes(), self.content)


if __name__ == '__main__':
    unittest.main()
