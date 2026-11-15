"""The CI build cache entry keeps what the build read or wrote and drops the rest (scripts/ci-build-cache.py)."""

import contextlib
import importlib.util
import io
import os
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('ci_build_cache', ROOT / 'scripts/ci-build-cache.py')
CACHE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CACHE)
WORKFLOW = (ROOT / '.github/workflows/android.yml').read_text(encoding='utf-8')
OLD_NS = 1_600_000_000 * 10**9


def entry(cache: Path, char: str, size: int = 10) -> Path:
    path = cache / (char * 32)
    path.write_bytes(b'x' * size)
    os.utime(path, ns=(OLD_NS, OLD_NS))
    return path


def read(path: Path) -> None:
    """What relatime does when Gradle unpacks an entry whose access time is older than its modification time."""
    os.utime(path, ns=(OLD_NS + 10**9, path.stat().st_mtime_ns))


class BuildCacheTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.cache = self.tmp / 'build-cache-1'
        self.cache.mkdir()
        self.marker = self.tmp / 'marker'

    def run_cli(self, command: str) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = CACHE.main([command, '--dir', str(self.cache), '--marker', str(self.marker)])
        return code, out.getvalue()

    def test_prune_keeps_read_and_written_entries_only(self):
        used, stale = entry(self.cache, 'a'), entry(self.cache, 'b')
        (self.cache / 'gc.properties').write_text('')
        self.assertEqual(0, self.run_cli('prepare')[0])
        self.assertEqual(0, used.stat().st_atime_ns)
        self.assertEqual(OLD_NS, used.stat().st_mtime_ns)
        read(used)
        written = self.cache / ('c' * 32)
        written.write_bytes(b'new')
        code, out = self.run_cli('prune')
        self.assertEqual(0, code)
        self.assertIn('1 of 2 restored entries read, 1 written', out)
        self.assertTrue(used.exists())
        self.assertTrue(written.exists())
        self.assertFalse(stale.exists())
        self.assertTrue((self.cache / 'gc.properties').exists())

    def test_report_deletes_nothing(self):
        used, stale = entry(self.cache, 'a'), entry(self.cache, 'b')
        self.run_cli('prepare')
        read(used)
        code, out = self.run_cli('report')
        self.assertEqual(0, code)
        self.assertIn('1 unused', out)
        self.assertTrue(stale.exists())

    def test_no_read_at_all_keeps_everything(self):
        # A noatime mount never updates the access time: deleting would empty the cache on every main build.
        first, second = entry(self.cache, 'a'), entry(self.cache, 'b')
        self.run_cli('prepare')
        code, out = self.run_cli('prune')
        self.assertEqual(0, code)
        self.assertIn('keeping them all', out)
        self.assertTrue(first.exists() and second.exists())

    def test_cold_cache_and_missing_marker(self):
        self.assertEqual(0, self.run_cli('prepare')[0])
        (self.cache / ('d' * 32)).write_bytes(b'new')
        code, out = self.run_cli('prune')
        self.assertEqual(0, code)
        self.assertIn('0 of 0 restored entries read, 1 written', out)
        self.marker.unlink()
        self.assertEqual(1, self.run_cli('prune')[0])


class BuildCacheWorkflowTest(unittest.TestCase):
    SAVE_GUARD = "if: github.ref == 'refs/heads/main' && runner.environment == 'github-hosted' && !inputs.daily_test"

    def test_only_unsigned_github_hosted_main_saves_and_prunes(self):
        saves = [s for s in re.split(r'\n      - ', WORKFLOW) if 'gradle-build-cache-' in s and 'cache/save@' in s]
        self.assertEqual(1, len(saves))
        self.assertIn(self.SAVE_GUARD, saves[0])
        prunes = [s for s in re.split(r'\n      - ', WORKFLOW) if 'ci-build-cache.py prune' in s]
        self.assertEqual(1, len(prunes))
        self.assertIn(self.SAVE_GUARD, prunes[0])

    def test_every_key_is_new_and_restores_the_latest(self):
        keys = set(re.findall(r'key: (gradle-build-cache-.+)', WORKFLOW))
        self.assertEqual({'gradle-build-cache-v1-${{ github.run_id }}-${{ github.run_attempt }}'}, keys)
        self.assertIn('restore-keys: gradle-build-cache-v1-', WORKFLOW)


if __name__ == '__main__':
    unittest.main()
