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

    def test_persistent_api_failure_stops_at_cap_and_stays_failed(self):
        error = DOWNLOADS.urllib.error.HTTPError('https://api.github.com', 504, 'gateway', {}, None)
        operation = Mock(side_effect=error)
        wait = Mock()
        with patch('builtins.print') as log, self.assertRaises(DOWNLOADS.urllib.error.HTTPError):
            DOWNLOADS.network(operation, wait=wait)
        self.assertEqual(operation.call_count, 4)
        self.assertEqual([call.args[0] for call in wait.call_args_list], [15, 30, 60])
        self.assertEqual(log.call_count, 3)

    def test_job_token_is_never_forwarded_to_redirected_assets(self):
        with patch.dict(DOWNLOADS.os.environ, CASSINI_GITHUB_TOKEN='test-job-token'):
            request = DOWNLOADS.github_request('https://api.github.com/repos/vendor/repo', 'application/octet-stream')
            self.assertEqual(request.get_header('Authorization'), 'Bearer test-job-token')
            self.assertNotIn('Authorization', request.headers)
            redirected = DOWNLOADS.urllib.request.HTTPRedirectHandler().redirect_request(
                request, None, 302, 'redirect', {}, 'https://release-assets.githubusercontent.com/asset.zip',
            )
            self.assertIsNone(redirected.get_header('Authorization'))
            external = DOWNLOADS.github_request('https://example.invalid/asset.zip', 'application/octet-stream')
            self.assertIsNone(external.get_header('Authorization'))

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

    def test_detekt_prefetch_preserves_published_module_and_metadata(self):
        pin = DOWNLOADS.input_pin('kotlinCompiler')
        with patch.object(DOWNLOADS, 'github_asset') as fetch, patch.object(DOWNLOADS, 'pinned_pom') as pom:
            DOWNLOADS.prepare_detekt()
        target = fetch.call_args.args[3]
        expected = DOWNLOADS.ROOT / '.tools/ci-maven/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21'
        self.assertEqual(target, expected / pin['name'])
        self.assertEqual(fetch.call_args.args[4], pin['sha256'])
        self.assertEqual(fetch.call_args.kwargs['size'], 58272093)
        pom.assert_called_once_with(pin['pom'], target.with_suffix('.pom'))

    def test_changed_pom_is_refused_before_repository_publication(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'compiler.pom'
            pin = dict(url='https://example.invalid/compiler.pom', size=4,
                       sha256=hashlib.sha256(b'good').hexdigest())
            with patch.object(DOWNLOADS.urllib.request, 'urlopen', return_value=io.BytesIO(b'evil')):
                with self.assertRaises(ValueError):
                    DOWNLOADS.pinned_pom(pin, target)
            self.assertFalse(target.exists())

    def test_gradle_info_is_keyed_by_committed_wrapper_checksum(self):
        info = DOWNLOADS.gradle_info()
        self.assertEqual(info['checksum'], '21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607')
        self.assertTrue(info['cache_key'].endswith(info['checksum']))
        self.assertEqual(info['archive'].name, 'gradle-9.8.2-bin.zip')

    def distribution(self, root):
        checksum = hashlib.sha256(b'pinned distribution').hexdigest()
        return dict(name='gradle-9.8.2-bin', version='9.8.2', tag='v9.8.2', checksum=checksum,
                    url='https://services.gradle.org/distributions/gradle-9.8.2-bin.zip',
                    archive=root / 'cache/gradle-9.8.2-bin.zip')

    def test_restored_cache_works_with_distribution_endpoints_offline(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            info = self.distribution(root)
            info['archive'].parent.mkdir()
            info['archive'].write_bytes(b'pinned distribution')
            with patch.dict(DOWNLOADS.os.environ, GRADLE_USER_HOME=str(root / 'gradle-home')):
                with patch.object(DOWNLOADS, 'gradle_info', return_value=info):
                    with patch.object(DOWNLOADS.urllib.request, 'urlopen', side_effect=AssertionError('offline')):
                        DOWNLOADS.prepare_gradle()
                        DOWNLOADS.prepare_gradle()
            target = root / 'gradle-home/wrapper/dists/gradle-9.8.2-bin/8wepis63pzuhrlfaiy04bagwu/gradle-9.8.2-bin.zip'
            self.assertEqual(target.read_bytes(), b'pinned distribution')

    def test_corrupt_distribution_cache_is_refused_even_with_installed_wrapper(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            info = self.distribution(root)
            info['archive'].parent.mkdir()
            info['archive'].write_bytes(b'corrupt distribution')
            home = root / 'gradle-home'
            wrapper = home / 'wrapper/dists/gradle-9.8.2-bin/8wepis63pzuhrlfaiy04bagwu'
            (wrapper / 'gradle-9.8.2/bin').mkdir(parents=True)
            (wrapper / 'gradle-9.8.2/bin/gradle').touch()
            (wrapper / 'gradle-9.8.2-bin.zip.ok').touch()
            with patch.dict(DOWNLOADS.os.environ, GRADLE_USER_HOME=str(home)):
                with patch.object(DOWNLOADS, 'gradle_info', return_value=info):
                    with patch.object(DOWNLOADS.urllib.request, 'urlopen') as fetch, self.assertRaises(ValueError):
                        DOWNLOADS.prepare_gradle()
                    fetch.assert_not_called()
            self.assertFalse((wrapper / 'gradle-9.8.2-bin.zip').exists())

    def native_properties(self, path):
        path.write_text(
            'distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n'
            'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.6-bin.zip\n'
            'zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n',
        )

    def test_native_wrapper_adds_pin_idempotently_without_version_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'gradle-wrapper.properties'
            self.native_properties(path)
            DOWNLOADS.pin_native_wrapper(path)
            DOWNLOADS.pin_native_wrapper(path)
            properties = DOWNLOADS.read_properties(path)
            self.assertEqual(properties['distributionUrl'], DOWNLOADS.input_pin('nativeGradle')['url'])
            self.assertEqual(properties['distributionSha256Sum'], DOWNLOADS.input_pin('nativeGradle')['sha256'])
            self.assertEqual(path.read_text().count('distributionSha256Sum='), 1)

    def test_native_wrapper_refuses_existing_wrong_pin(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'gradle-wrapper.properties'
            self.native_properties(path)
            with path.open('a') as output:
                output.write('distributionSha256Sum=' + '0' * 64 + '\n')
            before = path.read_bytes()
            with self.assertRaises(ValueError):
                DOWNLOADS.pin_native_wrapper(path)
            self.assertEqual(path.read_bytes(), before)

    def test_native_cache_is_separate_and_checksum_addressed(self):
        native = DOWNLOADS.gradle_info('native')
        project = DOWNLOADS.gradle_info()
        self.assertEqual(native['version'], '8.6')
        self.assertEqual(native['tag'], 'v8.6.0')
        self.assertTrue(native['cache_key'].endswith(native['checksum']))
        self.assertNotEqual(native['cache_key'], project['cache_key'])

    def test_wrapper_without_sha256_pin_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'gradle/wrapper').mkdir(parents=True)
            original = (DOWNLOADS.ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
            (root / 'gradle/wrapper/gradle-wrapper.properties').write_text(
                original.replace('distributionSha256Sum=', 'disabledPin='),
            )
            with patch.object(DOWNLOADS, 'ROOT', root), self.assertRaises((KeyError, ValueError)):
                DOWNLOADS.gradle_info()


if __name__ == '__main__':
    unittest.main()
