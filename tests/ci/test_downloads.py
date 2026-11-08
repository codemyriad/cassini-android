"""Only transient bootstrap downloads may repeat; failures of analysis/tests remain failures."""

import hashlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
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

    def test_api_504_recovers_but_403_is_not_retried(self):
        error = DOWNLOADS.urllib.error.HTTPError('https://api.github.com', 504, 'gateway', {}, None)
        operation = Mock(side_effect=[error, b'good'])
        wait = Mock()
        self.assertEqual(DOWNLOADS.network(operation, wait=wait), b'good')
        wait.assert_called_once_with(15)
        error = DOWNLOADS.urllib.error.HTTPError('https://api.github.com', 403, 'forbidden', {}, None)
        operation = Mock(side_effect=error)
        with self.assertRaises(DOWNLOADS.urllib.error.HTTPError):
            DOWNLOADS.network(operation, wait=wait)
        operation.assert_called_once()

    def native_archive(self, path, extra=None):
        entries = {
            'headers/onnxruntime_c_api.h': b'header',
            'jni/arm64-v8a/libonnxruntime.so': b'arm',
            'jni/x86_64/libonnxruntime.so': b'x86',
            'jni/armeabi-v7a/libonnxruntime.so': b'excluded',
        }
        with zipfile.ZipFile(path, 'w') as archive:
            for name, body in {**entries, **(extra or {})}.items():
                archive.writestr(name, body)
        return entries

    def test_native_extracts_only_headers_and_supported_abis_idempotently(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            entries = self.native_archive(root / 'ort.zip')
            DOWNLOADS.unpack_native(root / 'ort.zip', root / 'out')
            DOWNLOADS.unpack_native(root / 'ort.zip', root / 'out')
            for name, body in entries.items():
                if 'armeabi-v7a' in name:
                    self.assertFalse((root / 'out' / name).exists())
                else:
                    self.assertEqual((root / 'out' / name).read_bytes(), body)

    def test_native_archive_cannot_escape_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.native_archive(root / 'ort.zip', {'headers/../../escape': b'bad'})
            with self.assertRaises(ValueError):
                DOWNLOADS.unpack_native(root / 'ort.zip', root / 'out')
            self.assertFalse((root / 'escape').exists())

    def test_api_download_uses_binary_endpoint_and_committed_hash(self):
        for valid in [True, False]:
            with tempfile.TemporaryDirectory() as tmp, self.subTest(valid=valid):
                target = Path(tmp) / 'input.zip'
                digest = hashlib.sha256(b'good').hexdigest()
                metadata = {'assets': [{'id': 123, 'name': 'input.zip', 'digest': 'sha256:' + digest}]}
                responses = [io.BytesIO(json.dumps(metadata).encode()), io.BytesIO(b'good' if valid else b'evil')]
                with patch.object(DOWNLOADS.urllib.request, 'urlopen', side_effect=responses) as fetch:
                    if valid:
                        DOWNLOADS.github_asset('official/repo', 'v1', 'input.zip', target, digest)
                        DOWNLOADS.github_asset('official/repo', 'v1', 'input.zip', target, digest)
                        self.assertEqual(target.read_bytes(), b'good')
                    else:
                        with self.assertRaises(ValueError):
                            DOWNLOADS.github_asset('official/repo', 'v1', 'input.zip', target, digest)
                        self.assertFalse(target.exists())
                        self.assertFalse(list(target.parent.glob('*.part')))
                    self.assertEqual(fetch.call_count, 2)
                    request = fetch.call_args_list[1].args[0]
                    self.assertEqual(request.get_header('Accept'), 'application/octet-stream')
                    self.assertEqual(request.full_url, 'https://api.github.com/repos/official/repo/releases/assets/123?download=1')

    def test_corrupt_cached_input_is_refused_without_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'input.zip'
            target.write_bytes(b'corrupt')
            with patch.object(DOWNLOADS.urllib.request, 'urlopen') as fetch, self.assertRaises(ValueError):
                DOWNLOADS.github_asset('official/repo', 'v1', 'input.zip', target, '0' * 64)
            fetch.assert_not_called()

    def test_gradle_uses_existing_wrapper_cache_layout_and_pin(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(DOWNLOADS.os.environ, GRADLE_USER_HOME=tmp):
            with patch.object(DOWNLOADS, 'github_asset') as fetch:
                DOWNLOADS.prepare_gradle()
            target = fetch.call_args.args[3]
            self.assertEqual(target.parent.name, '8wepis63pzuhrlfaiy04bagwu')
            self.assertEqual(target.name, 'gradle-9.8.2-bin.zip')
            self.assertEqual(
                fetch.call_args.args[4], '21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607',
            )


if __name__ == '__main__':
    unittest.main()
