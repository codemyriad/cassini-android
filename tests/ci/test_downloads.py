"""Only transient bootstrap downloads may repeat; failures of analysis/tests remain failures."""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location(
    'downloads', Path(__file__).resolve().parents[2] / 'scripts/ci-downloads.py',
)
DOWNLOADS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DOWNLOADS)


class DownloadTests(unittest.TestCase):
    def result(self, code, output):
        return subprocess.CompletedProcess([], code, output)

    def test_504_retries_download_with_visible_cap(self):
        run = Mock(side_effect=[self.result(1, 'HTTP response code: 504'), self.result(0, 'downloaded')])
        wait = Mock()
        with patch('builtins.print') as log:
            DOWNLOADS.run_download(['download'], run=run, wait=wait)
        self.assertEqual(run.call_count, 2)
        wait.assert_called_once_with(15)
        self.assertTrue(any('retry 1/3' in str(call) for call in log.call_args_list))

    def test_persistent_504_stops_after_four_attempts(self):
        run = Mock(return_value=self.result(7, 'Received status code 504'))
        with patch('builtins.print'), self.assertRaises(SystemExit) as error:
            DOWNLOADS.run_download(['download'], run=run, wait=Mock())
        self.assertEqual(error.exception.code, 7)
        self.assertEqual(run.call_count, 4)

    def test_assertions_checksum_failures_and_404_never_retry(self):
        for output in [
            'AssertionError', 'Compilation failed', 'SHA-256 mismatch',
            'HTTP 504 then SHA-256 mismatch', 'Received status code 404', 'HTTP 504 then HTTP 404',
            'Attempt 4/4 failed: HTTP 504 at org.gradle.wrapper.Download',
        ]:
            run = Mock(return_value=self.result(1, output))
            wait = Mock()
            with self.subTest(output=output), patch('builtins.print'), self.assertRaises(SystemExit):
                DOWNLOADS.run_download(['download'], run=run, wait=wait)
            run.assert_called_once()
            wait.assert_not_called()

    def test_cli_rejects_builds_tests_and_analysis_commands(self):
        for stage in ['build', 'test', 'ciTest', ':core:test']:
            result = subprocess.run(
                [sys.executable, str(Path(DOWNLOADS.__file__)), stage],
                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False,
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn('invalid choice', result.stdout)

    def test_native_transport_is_bounded_visible_and_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ['build-android-arm64-v8a.sh', 'build-android-x86-64.sh']:
                (root / name).write_text(DOWNLOADS.WGET + 'release.zip\ncmake --build build\n')
            DOWNLOADS.prepare_native(root)
            DOWNLOADS.prepare_native(root)
            for path in root.iterdir():
                self.assertEqual(path.read_text(), DOWNLOADS.BOUNDED_WGET + 'release.zip\ncmake --build build\n')

    def test_unexpected_upstream_commands_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'build-android-arm64-v8a.sh').write_text('unexpected source')
            with self.assertRaises(ValueError):
                DOWNLOADS.prepare_native(root)


if __name__ == '__main__':
    unittest.main()
