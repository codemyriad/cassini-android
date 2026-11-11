"""Contract and failure-mode tests for explicit Android NDK caching, installation, and static-checks isolation."""

from __future__ import annotations

import importlib.util
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
NDK_SPEC = importlib.util.spec_from_file_location('ci_ndk', ROOT / 'scripts/ci-ndk.py')
NDK = importlib.util.module_from_spec(NDK_SPEC)
NDK_SPEC.loader.exec_module(NDK)

RUNNER_SPEC = importlib.util.spec_from_file_location('runners', ROOT / 'scripts/check-runner-selection.py')
RUNNERS = importlib.util.module_from_spec(RUNNER_SPEC)
RUNNER_SPEC.loader.exec_module(RUNNERS)


def populate_valid_ndk(ndk_dir: Path, version: str, *, host_tag: str = 'linux-x86_64') -> None:
    ndk_dir.mkdir(parents=True, exist_ok=True)
    (ndk_dir / 'source.properties').write_text(
        f'Pkg.Desc = Android NDK\nPkg.Revision = {version}\n',
        encoding='utf-8',
    )
    cmake_file = ndk_dir / 'build/cmake/android.toolchain.cmake'
    cmake_file.parent.mkdir(parents=True, exist_ok=True)
    cmake_file.write_text('# toolchain\n', encoding='utf-8')
    llvm_bin = ndk_dir / 'toolchains/llvm/prebuilt' / host_tag / 'bin'
    llvm_bin.mkdir(parents=True, exist_ok=True)
    for tool_name in NDK.REQUIRED_LLVM_TOOLS:
        tool = llvm_bin / tool_name
        tool.write_text('#!/bin/sh\nexit 0\n', encoding='utf-8')
        tool.chmod(0o755)


def populate_sdkmanager(sdk_root: Path) -> Path:
    binary = sdk_root / 'cmdline-tools/latest/bin/sdkmanager'
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_text('#!/bin/sh\nexit 0\n', encoding='utf-8')
    binary.chmod(0o755)
    return binary


class NdkCacheKeyContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog_text = (ROOT / 'gradle/libs.versions.toml').read_text(encoding='utf-8')
        self.version = NDK.read_ndk_version()
        self.sdk_root = Path('/usr/local/lib/android/sdk')
        self.base_key = NDK.cache_key(
            self.version,
            sdk_root=self.sdk_root,
            runner_os='Linux',
            runner_arch='X64',
        )

    def test_committed_pin_matches_whistle_and_appears_in_cache_key(self) -> None:
        self.assertIn(self.version, self.base_key)
        self.assertTrue(self.base_key.startswith(f'ndk-{NDK.CACHE_SCHEMA}-Linux-X64-{self.version}-'))

    def test_ndk_version_os_arch_and_sdk_root_rotate_cache_key(self) -> None:
        self.assertNotEqual(
            self.base_key,
            NDK.cache_key('28.2.9999999', sdk_root=self.sdk_root, runner_os='Linux', runner_arch='X64'),
        )
        self.assertNotEqual(
            self.base_key,
            NDK.cache_key(self.version, sdk_root=self.sdk_root, runner_os='macOS', runner_arch='X64'),
        )
        self.assertNotEqual(
            self.base_key,
            NDK.cache_key(self.version, sdk_root=self.sdk_root, runner_os='Linux', runner_arch='ARM64'),
        )
        self.assertNotEqual(
            self.base_key,
            NDK.cache_key(self.version, sdk_root=Path('/opt/android-sdk'), runner_os='Linux', runner_arch='X64'),
        )

    def test_trailing_slash_in_sdk_root_does_not_rotate_cache_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            catalog = Path(tmp) / 'libs.versions.toml'
            catalog.write_text(self.catalog_text, encoding='utf-8')
            env_plain = {'ANDROID_HOME': '/usr/local/lib/android/sdk', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64'}
            env_slash = {'ANDROID_HOME': '/usr/local/lib/android/sdk/', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64'}
            self.assertEqual(
                NDK.ndk_info(catalog=catalog, environ=env_plain)['cache_key'],
                NDK.ndk_info(catalog=catalog, environ=env_slash)['cache_key'],
            )

    def test_unrelated_version_catalog_edits_do_not_rotate_ndk_cache_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            catalog = Path(tmp) / 'libs.versions.toml'
            edited = self.catalog_text.replace('agp = "', 'agp = "99.')
            edited = edited.replace('robolectric = "', 'robolectric = "99.')
            catalog.write_text(edited, encoding='utf-8')
            env = {'ANDROID_HOME': str(self.sdk_root), 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64'}
            self.assertEqual(self.base_key, NDK.ndk_info(catalog=catalog, environ=env)['cache_key'])

    def test_drift_between_catalog_and_whistle_pin_fails_fast(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            catalog = Path(tmp) / 'libs.versions.toml'
            whistle = Path(tmp) / 'build-whistle-runtime.py'
            catalog.write_text('ndk = "28.2.13676358"\n', encoding='utf-8')
            whistle.write_text("NDK_VERSION = '29.0.0'\n", encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'NDK pin mismatch'):
                NDK.read_ndk_version(catalog, whistle_script=whistle)

    def test_write_key_outputs_reports_installed_state_to_github_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            output_file = Path(tmp) / 'github_output.txt'
            env = {
                'ANDROID_HOME': str(sdk_root),
                'RUNNER_OS': 'Linux',
                'RUNNER_ARCH': 'X64',
                'GITHUB_OUTPUT': str(output_file),
            }
            NDK.write_key_outputs(environ=env)
            first = dict(line.split('=', 1) for line in output_file.read_text(encoding='utf-8').splitlines())
            self.assertEqual(first['installed'], 'false')
            self.assertEqual(first['version'], self.version)
            self.assertEqual(first['path'], str(sdk_root / 'ndk' / self.version))

            output_file.write_text('', encoding='utf-8')
            populate_valid_ndk(sdk_root / 'ndk' / self.version, self.version)
            NDK.write_key_outputs(environ=env)
            second = dict(line.split('=', 1) for line in output_file.read_text(encoding='utf-8').splitlines())
            self.assertEqual(second['installed'], 'true')


class NdkVerificationAndInstallTests(unittest.TestCase):
    def setUp(self) -> None:
        self.version = NDK.read_ndk_version()

    def test_verify_ndk_rejects_corrupt_or_incomplete_layouts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ndk_dir = Path(tmp) / 'ndk' / self.version
            populate_valid_ndk(ndk_dir, self.version)
            self.assertEqual([], NDK.verify_ndk(ndk_dir, self.version, system='Linux'))

            (ndk_dir / '.installer').mkdir()
            self.assertTrue(any('.installer' in item for item in NDK.verify_ndk(ndk_dir, self.version, system='Linux')))
            (ndk_dir / '.installer').rmdir()

            (ndk_dir / 'source.properties').write_text('Pkg.Revision = 27.0.1\n', encoding='utf-8')
            self.assertTrue(
                any('Pkg.Revision' in item for item in NDK.verify_ndk(ndk_dir, self.version, system='Linux'))
            )
            populate_valid_ndk(ndk_dir, self.version)

            (ndk_dir / 'build/cmake/android.toolchain.cmake').write_text('', encoding='utf-8')
            self.assertTrue(
                any('android.toolchain.cmake' in item for item in NDK.verify_ndk(ndk_dir, self.version, system='Linux'))
            )
            populate_valid_ndk(ndk_dir, self.version)

            clang = ndk_dir / 'toolchains/llvm/prebuilt/linux-x86_64/bin/clang++'
            clang.unlink()
            self.assertTrue(any('clang++' in item for item in NDK.verify_ndk(ndk_dir, self.version, system='Linux')))

    def test_verified_cache_hit_skips_sdkmanager_completely(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            populate_valid_ndk(sdk_root / 'ndk' / self.version, self.version)
            runner = Mock()
            wait = Mock()
            env = {'ANDROID_HOME': str(sdk_root), 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64'}
            outcome = NDK.install_ndk(environ=env, runner=runner, wait=wait)
            self.assertEqual(outcome, 'verified')
            runner.assert_not_called()
            wait.assert_not_called()

    def test_corrupt_download_cleans_sdk_temp_and_succeeds_on_bounded_retry(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            populate_sdkmanager(sdk_root)
            ndk_dir = sdk_root / 'ndk' / self.version
            corrupt_temp = sdk_root / '.temp/PackageOperation01/android-ndk.zip'
            corrupt_temp.parent.mkdir(parents=True, exist_ok=True)
            corrupt_temp.write_bytes(b'truncated-zip')
            (ndk_dir / '.installer').mkdir(parents=True, exist_ok=True)

            calls: list[tuple[bool, bool]] = []

            def fake_runner(command, **_kwargs):
                calls.append((ndk_dir.exists(), (sdk_root / '.temp/PackageOperation01').exists()))
                if len(calls) == 1:
                    (sdk_root / '.temp/PackageOperation01').mkdir(parents=True, exist_ok=True)
                    (ndk_dir / '.installer').mkdir(parents=True, exist_ok=True)
                    return subprocess.CompletedProcess(command, 1, stdout='Error reading Zip content\n')
                populate_valid_ndk(ndk_dir, self.version)
                return subprocess.CompletedProcess(command, 0, stdout='Done\n')

            wait = Mock()
            env = {'ANDROID_HOME': str(sdk_root), 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'PATH': ''}
            outcome = NDK.install_ndk(environ=env, runner=fake_runner, wait=wait)
            self.assertEqual(outcome, 'installed')
            self.assertEqual(calls, [(False, False), (False, False)])
            wait.assert_called_once_with(10)
            self.assertEqual([], NDK.verify_ndk(ndk_dir, self.version, system='Linux'))

    def test_exhausted_retries_clean_up_partial_state_and_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            populate_sdkmanager(sdk_root)
            ndk_dir = sdk_root / 'ndk' / self.version

            def always_corrupt(command, **_kwargs):
                ndk_dir.mkdir(parents=True, exist_ok=True)
                (ndk_dir / 'source.properties').write_text(f'Pkg.Revision = {self.version}\n', encoding='utf-8')
                (sdk_root / '.temp/bad.zip').parent.mkdir(parents=True, exist_ok=True)
                (sdk_root / '.temp/bad.zip').write_bytes(b'corrupt')
                # Exit 0 without toolchain files: verification must catch it!
                return subprocess.CompletedProcess(command, 0, stdout='Unpacked truncated zip\n')

            wait = Mock()
            env = {'ANDROID_HOME': str(sdk_root), 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'PATH': ''}
            with self.assertRaisesRegex(RuntimeError, 'after 3 attempts'):
                NDK.install_ndk(environ=env, runner=always_corrupt, wait=wait)
            self.assertEqual([call.args[0] for call in wait.call_args_list], [10, 20])
            self.assertFalse(ndk_dir.exists())
            self.assertEqual([], list((sdk_root / '.temp').iterdir()))

    def test_fatal_sdkmanager_or_missing_binary_errors_fail_fast_without_retry(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            sdk_root.mkdir()
            wait = Mock()
            env = {'ANDROID_HOME': str(sdk_root), 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'PATH': ''}
            with self.assertRaises(FileNotFoundError):
                NDK.install_ndk(environ=env, wait=wait)
            wait.assert_not_called()

            populate_sdkmanager(sdk_root)
            runner = Mock(
                return_value=subprocess.CompletedProcess(
                    ['sdkmanager'], 1, stdout='Warning: Failed to find package "ndk;28.2.13676358"\n'
                )
            )
            with self.assertRaisesRegex(RuntimeError, 'Non-retryable sdkmanager error'):
                NDK.install_ndk(environ=env, runner=runner, wait=wait)
            runner.assert_called_once()
            wait.assert_not_called()


class WorkflowNdkAndStaticChecksContractTests(unittest.TestCase):
    def test_pinned_ndk_action_uses_exact_key_and_hosted_main_only_cache_save(self) -> None:
        action = (ROOT / '.github/actions/pinned-ndk/action.yml').read_text(encoding='utf-8')
        self.assertIn('uses: actions/cache/restore@', action)
        self.assertIn('uses: actions/cache/save@', action)
        self.assertNotIn('restore-keys:', action)
        self.assertIn("if: runner.environment == 'github-hosted' && steps.pin.outputs.installed != 'true'", action)
        self.assertIn(
            "if: github.ref == 'refs/heads/main' && runner.environment == 'github-hosted' "
            "&& steps.pin.outputs.installed != 'true' && steps.restore.outputs.cache-hit != 'true'",
            action,
        )

    def test_workflows_never_install_ndk_via_setup_android_and_use_pinned_ndk_action(self) -> None:
        expected_ndk_jobs = {
            ('android.yml', 'build'),
            ('native.yml', 'native'),
            ('whistle.yml', 'native-input'),
            ('models.yml', 'models'),
            ('record-goldens.yml', 'record'),
        }
        actual_ndk_jobs: set[tuple[str, str]] = set()
        for workflow in sorted((ROOT / '.github/workflows').glob('*.yml')):
            text = workflow.read_text(encoding='utf-8')
            self.assertIsNone(
                re.search(r'packages:.*\bndk;', text),
                f'{workflow.name} must not install uncached/unverified NDK packages via setup-android',
            )
            for job_id, job_text in RUNNERS.jobs(workflow):
                if 'uses: ./.github/actions/pinned-ndk' in job_text:
                    actual_ndk_jobs.add((workflow.name, job_id))
                for step in RUNNERS.steps(job_text):
                    gradles = re.findall(r'\./gradlew\b', step)
                    if gradles and (workflow.name, job_id) != ('android.yml', 'core-only'):
                        self.assertEqual(
                            len(gradles),
                            step.count('-Pandroid.builder.sdkDownload=false'),
                            f'{workflow.name}:{job_id} Gradle commands must disable silent AGP SDK/NDK downloads',
                        )
        self.assertEqual(expected_ndk_jobs, actual_ndk_jobs)

    def test_static_checks_job_runs_detekt_without_android_sdk_or_subproject_configuration(self) -> None:
        android_jobs = dict(RUNNERS.jobs(ROOT / '.github/workflows/android.yml'))
        checks = RUNNERS.without_comments(android_jobs['checks'])
        self.assertNotIn('android-actions/setup-android', checks)
        self.assertIn('env -u ANDROID_HOME -u ANDROID_SDK_ROOT', checks)
        self.assertIn('-PcassiniStaticChecksOnly=true', checks)
        self.assertIn('-Pandroid.builder.sdkDownload=false', checks)
        self.assertIn(':detekt', checks)

        settings_kts = (ROOT / 'settings.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('providers.gradleProperty("cassiniStaticChecksOnly")', settings_kts)
        self.assertIn('if (!staticChecksOnly)', settings_kts)

        build_kts = (ROOT / 'build.gradle.kts').read_text(encoding='utf-8')
        for expected_source in ('"engine/src/main/kotlin"', '"engine/build.gradle.kts"', '"settings.gradle.kts"'):
            self.assertIn(expected_source, build_kts)


if __name__ == '__main__':
    unittest.main()
