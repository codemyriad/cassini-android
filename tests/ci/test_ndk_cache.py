"""Contract and failure-mode tests for explicit Android NDK caching, checksum verification, and static checks."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
NDK_SPEC = importlib.util.spec_from_file_location('ci_ndk', ROOT / 'scripts/ci-ndk.py')
NDK = importlib.util.module_from_spec(NDK_SPEC)
NDK_SPEC.loader.exec_module(NDK)

RUNNER_SPEC = importlib.util.spec_from_file_location('runners', ROOT / 'scripts/check-runner-selection.py')
RUNNERS = importlib.util.module_from_spec(RUNNER_SPEC)
RUNNER_SPEC.loader.exec_module(RUNNER_SPEC.loader and RUNNERS)

NATIVE_PATHS_SPEC = importlib.util.spec_from_file_location('native_paths', ROOT / 'scripts/check-native-paths.py')
NATIVE_PATHS = importlib.util.module_from_spec(NATIVE_PATHS_SPEC)
NATIVE_PATHS_SPEC.loader.exec_module(NATIVE_PATHS)

FAKE_ELF64 = b'\x7fELF\x02\x01\x01' + b'\x00' * 57 + b'cassini-elf-payload'


def populate_valid_ndk(ndk_dir: Path, version: str, *, host_tag: str = 'linux-x86_64') -> dict[str, Any]:
    """Create a realistic NDK tree (ELF binaries, linker, symlinks, CMake, API-26 sysroot) and return its pin."""
    ndk_dir.mkdir(parents=True, exist_ok=True)
    (ndk_dir / 'source.properties').write_text(
        f'Pkg.Desc = Android NDK\nPkg.Revision = {version}\n',
        encoding='utf-8',
    )
    (ndk_dir / 'package.xml').write_text(
        f'<repository><localPackage path="ndk;{version}"/></repository>\n',
        encoding='utf-8',
    )
    for cmake_rel in ('build/cmake/android.toolchain.cmake', 'build/cmake/abis.cmake', 'build/cmake/platforms.cmake'):
        cmake_file = ndk_dir / cmake_rel
        cmake_file.parent.mkdir(parents=True, exist_ok=True)
        cmake_file.write_text(f'# {cmake_rel}\n', encoding='utf-8')

    toolchain = ndk_dir / 'toolchains/llvm/prebuilt' / host_tag
    llvm_bin = toolchain / 'bin'
    llvm_bin.mkdir(parents=True, exist_ok=True)
    for elf_tool in NDK.NATIVE_ELF_TOOLS:
        target = llvm_bin / elf_tool
        target.write_bytes(FAKE_ELF64 + elf_tool.encode('utf-8'))
        target.chmod(0o755)
    for wrapper in (
        'aarch64-linux-android26-clang',
        'aarch64-linux-android26-clang++',
        'x86_64-linux-android26-clang',
        'x86_64-linux-android26-clang++',
    ):
        script = llvm_bin / wrapper
        script.write_text(f'#!/bin/sh\nexec "$(dirname "$0")/clang" "$@"\n# {wrapper}\n', encoding='utf-8')
        script.chmod(0o755)

    symlinks = {
        f'toolchains/llvm/prebuilt/{host_tag}/bin/clang': 'clang-19',
        f'toolchains/llvm/prebuilt/{host_tag}/bin/clang++': 'clang',
        f'toolchains/llvm/prebuilt/{host_tag}/bin/ld.lld': 'lld',
        f'toolchains/llvm/prebuilt/{host_tag}/bin/llvm-readelf': 'llvm-readobj',
        f'toolchains/llvm/prebuilt/{host_tag}/bin/llvm-strip': 'llvm-objcopy',
    }
    for link_rel, target_name in symlinks.items():
        link_path = ndk_dir / link_rel
        if link_path.exists() or link_path.is_symlink():
            link_path.unlink()
        link_path.symlink_to(target_name)

    sysroot = toolchain / 'sysroot'
    for sysroot_rel in NDK.REQUIRED_SYSROOT_PATHS:
        sysroot_file = sysroot / sysroot_rel
        sysroot_file.parent.mkdir(parents=True, exist_ok=True)
        payload = FAKE_ELF64 if sysroot_rel.endswith(('.so', '.o')) else f'/* {sysroot_rel} */\n'.encode()
        sysroot_file.write_bytes(payload + sysroot_rel.encode('utf-8'))

    # Non-critical file outside `files` to verify full-tree `treeSha256` catches arbitrary tree corruption.
    extra = ndk_dir / 'sources/android/support/include/extra_header.h'
    extra.parent.mkdir(parents=True, exist_ok=True)
    extra.write_text('/* intact non-critical header */\n', encoding='utf-8')

    critical_rels = [
        'source.properties',
        'build/cmake/android.toolchain.cmake',
        'build/cmake/abis.cmake',
        'build/cmake/platforms.cmake',
        *[f'toolchains/llvm/prebuilt/{host_tag}/bin/{name}' for name in NDK.NATIVE_ELF_TOOLS],
        f'toolchains/llvm/prebuilt/{host_tag}/bin/aarch64-linux-android26-clang',
        f'toolchains/llvm/prebuilt/{host_tag}/bin/aarch64-linux-android26-clang++',
        f'toolchains/llvm/prebuilt/{host_tag}/bin/x86_64-linux-android26-clang',
        f'toolchains/llvm/prebuilt/{host_tag}/bin/x86_64-linux-android26-clang++',
        *[f'toolchains/llvm/prebuilt/{host_tag}/sysroot/{rel}' for rel in NDK.REQUIRED_SYSROOT_PATHS],
    ]
    files_pin = {}
    for rel in critical_rels:
        file_path = ndk_dir / rel
        data = file_path.read_bytes()
        files_pin[rel] = {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}

    entry_count, tree_sha = NDK.compute_ndk_tree_digest(ndk_dir)
    return {
        'version': version,
        'hosts': {
            'Linux-X64': {
                'hostTag': host_tag,
                'archiveUrl': 'https://dl.google.com/android/repository/android-ndk-r28c-linux.zip',
                'archiveSize': 722261334,
                'archiveSha1': 'a7b54a5de87fecd125a17d54f73c446199e72a64',
                'entryCount': entry_count,
                'treeSha256': tree_sha,
                'symlinks': symlinks,
                'files': files_pin,
            }
        },
    }


def populate_sdkmanager(sdk_root: Path) -> Path:
    binary = sdk_root / 'cmdline-tools/latest/bin/sdkmanager'
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_text('#!/bin/sh\nexit 0\n', encoding='utf-8')
    binary.chmod(0o755)
    return binary


class NdkCacheKeyContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog_text = (ROOT / 'gradle/libs.versions.toml').read_text(encoding='utf-8')
        self.committed_pin = NDK.load_ndk_pin()
        self.version = NDK.read_ndk_version()
        self.sdk_root = Path('/usr/local/lib/android/sdk')
        self.base_key = NDK.cache_key(
            self.version,
            sdk_root=self.sdk_root,
            runner_os='Linux',
            runner_arch='X64',
            ndk_pin=self.committed_pin,
        )

    def test_committed_pin_in_ci_download_pins_is_complete_and_matches_catalog(self) -> None:
        self.assertEqual(self.version, self.committed_pin['version'])
        host_pin = self.committed_pin['hosts']['Linux-X64']
        self.assertRegex(host_pin['treeSha256'], r'^[0-9a-f]{64}$')
        self.assertGreater(host_pin['entryCount'], 8000)
        self.assertEqual(host_pin['symlinks']['toolchains/llvm/prebuilt/linux-x86_64/bin/ld.lld'], 'lld')
        for required_file in (
            'source.properties',
            'build/cmake/android.toolchain.cmake',
            'toolchains/llvm/prebuilt/linux-x86_64/bin/clang-19',
            'toolchains/llvm/prebuilt/linux-x86_64/bin/lld',
            'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/jni.h',
            'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/26/libc.so',
            'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/x86_64-linux-android/26/libc.so',
            'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_static.a',
        ):
            self.assertIn(required_file, host_pin['files'])
            self.assertRegex(host_pin['files'][required_file]['sha256'], r'^[0-9a-f]{64}$')
            self.assertGreater(host_pin['files'][required_file]['size'], 0)

    def test_ndk_version_tree_digest_arch_and_sdk_root_rotate_cache_key(self) -> None:
        self.assertIn(self.version, self.base_key)
        self.assertTrue(self.base_key.startswith(f'ndk-{NDK.CACHE_SCHEMA}-Linux-X64-{self.version}-'))

        other_ver_pin = copy.deepcopy(self.committed_pin)
        other_ver_pin['version'] = '28.2.9999999'
        self.assertNotEqual(
            self.base_key,
            NDK.cache_key(
                '28.2.9999999',
                sdk_root=self.sdk_root,
                runner_os='Linux',
                runner_arch='X64',
                ndk_pin=other_ver_pin,
            ),
        )

        other_tree_pin = copy.deepcopy(self.committed_pin)
        other_tree_pin['hosts']['Linux-X64']['treeSha256'] = 'f' * 64
        self.assertNotEqual(
            self.base_key,
            NDK.cache_key(
                self.version,
                sdk_root=self.sdk_root,
                runner_os='Linux',
                runner_arch='X64',
                ndk_pin=other_tree_pin,
            ),
        )

        arm_pin = copy.deepcopy(self.committed_pin)
        arm_pin['hosts']['Linux-ARM64'] = copy.deepcopy(arm_pin['hosts']['Linux-X64'])
        self.assertNotEqual(
            self.base_key,
            NDK.cache_key(
                self.version,
                sdk_root=self.sdk_root,
                runner_os='Linux',
                runner_arch='ARM64',
                ndk_pin=arm_pin,
            ),
        )
        self.assertNotEqual(
            self.base_key,
            NDK.cache_key(
                self.version,
                sdk_root=Path('/opt/android-sdk'),
                runner_os='Linux',
                runner_arch='X64',
                ndk_pin=self.committed_pin,
            ),
        )

    def test_trailing_slash_or_unrelated_version_catalog_edits_do_not_rotate_cache_key(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            catalog = Path(tmp) / 'libs.versions.toml'
            edited = self.catalog_text.replace('agp = "', 'agp = "99.').replace('robolectric = "', 'robolectric = "99.')
            catalog.write_text(edited, encoding='utf-8')
            env_plain = {'ANDROID_HOME': '/usr/local/lib/android/sdk', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64'}
            env_slash = {'ANDROID_HOME': '/usr/local/lib/android/sdk/', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64'}
            self.assertEqual(self.base_key, NDK.ndk_info(catalog=catalog, environ=env_plain)['cache_key'])
            self.assertEqual(
                NDK.ndk_info(catalog=catalog, environ=env_plain)['cache_key'],
                NDK.ndk_info(catalog=catalog, environ=env_slash)['cache_key'],
            )

    def test_drift_between_catalog_whistle_or_download_pins_fails_fast(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            catalog = Path(tmp) / 'libs.versions.toml'
            whistle = Path(tmp) / 'build-whistle-runtime.py'
            catalog.write_text('ndk = "28.2.13676358"\n', encoding='utf-8')
            whistle.write_text("NDK_VERSION = '29.0.0'\n", encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'NDK pin mismatch'):
                NDK.read_ndk_version(catalog, whistle_script=whistle)

            whistle.write_text("NDK_VERSION = '28.2.13676358'\n", encoding='utf-8')
            bad_pin = copy.deepcopy(self.committed_pin)
            bad_pin['version'] = '29.0.0'
            with self.assertRaisesRegex(ValueError, 'ci-download-pins.json'):
                NDK.read_ndk_version(catalog, whistle_script=whistle, ndk_pin=bad_pin)

    def test_write_key_outputs_reports_installed_state_to_github_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            ndk_dir = sdk_root / 'ndk' / self.version
            pin = populate_valid_ndk(ndk_dir, self.version)
            # Remove ndk_dir first to verify installed=false before installation.
            NDK.shutil.rmtree(ndk_dir)
            output_file = Path(tmp) / 'github_output.txt'
            env = {
                'ANDROID_HOME': str(sdk_root),
                'RUNNER_OS': 'Linux',
                'RUNNER_ARCH': 'X64',
                'GITHUB_OUTPUT': str(output_file),
            }
            NDK.write_key_outputs(ndk_pin=pin, environ=env)
            first = dict(line.split('=', 1) for line in output_file.read_text(encoding='utf-8').splitlines())
            self.assertEqual(first['installed'], 'false')

            output_file.write_text('', encoding='utf-8')
            populate_valid_ndk(ndk_dir, self.version)
            NDK.write_key_outputs(ndk_pin=pin, environ=env)
            second = dict(line.split('=', 1) for line in output_file.read_text(encoding='utf-8').splitlines())
            self.assertEqual(second['installed'], 'true')


class NdkVerificationAndInstallTests(unittest.TestCase):
    def setUp(self) -> None:
        self.version = NDK.read_ndk_version()

    def test_verify_ndk_rejects_truncated_executables_shell_stubs_missing_linker_sysroot_and_tree_tampering(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ndk_dir = Path(tmp) / 'ndk' / self.version
            pin = populate_valid_ndk(ndk_dir, self.version)
            self.assertEqual(
                [],
                NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin),
            )

            # 1. Truncated compiler executable (1 byte shorter than pinned size).
            clang_bin = ndk_dir / 'toolchains/llvm/prebuilt/linux-x86_64/bin/clang-19'
            original_clang = clang_bin.read_bytes()
            clang_bin.write_bytes(original_clang[:-1])
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('size mismatch' in item and 'clang-19' in item for item in problems), problems)

            # 2. Same-size byte corruption in linker (`lld`).
            clang_bin.write_bytes(original_clang)
            lld_bin = ndk_dir / 'toolchains/llvm/prebuilt/linux-x86_64/bin/lld'
            original_lld = lld_bin.read_bytes()
            lld_bin.write_bytes(original_lld[:-1] + (b'X' if original_lld[-1:] != b'X' else b'Y'))
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('SHA-256 mismatch' in item and 'lld' in item for item in problems), problems)

            # 3. Non-ELF shell stub in place of native ELF binary (even if pin were forged without ELF header).
            stub_bytes = b'#!/bin/sh\nexit 0\n'
            lld_bin.write_bytes(stub_bytes)
            lld_bin.chmod(0o755)
            forged_pin = copy.deepcopy(pin)
            forged_pin['hosts']['Linux-X64']['files']['toolchains/llvm/prebuilt/linux-x86_64/bin/lld'] = {
                'size': len(stub_bytes),
                'sha256': hashlib.sha256(stub_bytes).hexdigest(),
            }
            problems = NDK.verify_ndk(
                ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=forged_pin
            )
            self.assertTrue(any('not an ELF binary' in item and 'lld' in item for item in problems), problems)
            lld_bin.write_bytes(original_lld)

            # 4. Missing or mis-pointed linker symlink (`ld.lld`).
            ld_lld = ndk_dir / 'toolchains/llvm/prebuilt/linux-x86_64/bin/ld.lld'
            ld_lld.unlink()
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('ld.lld' in item for item in problems), problems)
            ld_lld.symlink_to('clang-19')
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('symlink' in item and 'ld.lld' in item for item in problems), problems)
            ld_lld.unlink()
            ld_lld.symlink_to('lld')

            # 5. Missing or truncated sysroot library / header (`aarch64 libc.so` and `jni.h`).
            libc_so = ndk_dir / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/26/libc.so'
            original_libc = libc_so.read_bytes()
            libc_so.unlink()
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('libc.so' in item for item in problems), problems)
            libc_so.write_bytes(original_libc[:12])
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('size mismatch' in item and 'libc.so' in item for item in problems), problems)
            libc_so.write_bytes(original_libc)

            # 6. Corruption in a non-critical file outside `files` caught by full-tree `treeSha256`.
            extra = ndk_dir / 'sources/android/support/include/extra_header.h'
            extra.write_text('/* tampered */\n', encoding='utf-8')
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('tree SHA-256 mismatch' in item for item in problems), problems)
            extra.write_text('/* intact non-critical header */\n', encoding='utf-8')

            # 7. Unexpected injected file caught by `entryCount` / `treeSha256`.
            injected = ndk_dir / 'sources/injected.so'
            injected.write_bytes(b'injected')
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('entry count mismatch' in item for item in problems), problems)
            injected.unlink()

            # 8. Leftover `.installer` marker rejected.
            (ndk_dir / '.installer').mkdir()
            problems = NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin)
            self.assertTrue(any('.installer' in item for item in problems), problems)

    def test_verified_cache_hit_skips_sdkmanager_and_corrupt_restored_cache_triggers_clean_reinstall(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            ndk_dir = sdk_root / 'ndk' / self.version
            pin = populate_valid_ndk(ndk_dir, self.version)
            runner = Mock()
            wait = Mock()
            env = {'ANDROID_HOME': str(sdk_root), 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'PATH': ''}
            self.assertEqual('verified', NDK.install_ndk(ndk_pin=pin, environ=env, runner=runner, wait=wait))
            runner.assert_not_called()

            # Corrupt the restored cache (truncate `lld`); `install_ndk` must discard it and reinstall cleanly.
            populate_sdkmanager(sdk_root)
            lld_bin = ndk_dir / 'toolchains/llvm/prebuilt/linux-x86_64/bin/lld'
            lld_bin.write_bytes(lld_bin.read_bytes()[:8])

            def reinstall_runner(command, **_kwargs):
                self.assertFalse(ndk_dir.exists(), 'corrupt restored cache must be wiped before sdkmanager runs')
                populate_valid_ndk(ndk_dir, self.version)
                return subprocess.CompletedProcess(command, 0, stdout='Done\n')

            outcome = NDK.install_ndk(ndk_pin=pin, environ=env, runner=reinstall_runner, wait=wait)
            self.assertEqual(outcome, 'installed')
            wait.assert_not_called()
            self.assertEqual(
                [],
                NDK.verify_ndk(ndk_dir, self.version, runner_os='Linux', runner_arch='X64', ndk_pin=pin),
            )

    def test_corrupt_download_cleans_sdk_temp_and_succeeds_on_bounded_retry(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            ndk_dir = sdk_root / 'ndk' / self.version
            pin = populate_valid_ndk(ndk_dir, self.version)
            NDK.shutil.rmtree(ndk_dir)
            populate_sdkmanager(sdk_root)

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
            outcome = NDK.install_ndk(ndk_pin=pin, environ=env, runner=fake_runner, wait=wait)
            self.assertEqual(outcome, 'installed')
            self.assertEqual(calls, [(False, False), (False, False)])
            wait.assert_called_once_with(10)

    def test_exhausted_retries_clean_up_partial_state_and_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            ndk_dir = sdk_root / 'ndk' / self.version
            pin = populate_valid_ndk(ndk_dir, self.version)
            NDK.shutil.rmtree(ndk_dir)
            populate_sdkmanager(sdk_root)

            def always_corrupt(command, **_kwargs):
                ndk_dir.mkdir(parents=True, exist_ok=True)
                (ndk_dir / 'source.properties').write_text(f'Pkg.Revision = {self.version}\n', encoding='utf-8')
                (sdk_root / '.temp/bad.zip').parent.mkdir(parents=True, exist_ok=True)
                (sdk_root / '.temp/bad.zip').write_bytes(b'corrupt')
                return subprocess.CompletedProcess(command, 0, stdout='Unpacked truncated zip\n')

            wait = Mock()
            env = {'ANDROID_HOME': str(sdk_root), 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'PATH': ''}
            with self.assertRaisesRegex(RuntimeError, 'after 3 attempts'):
                NDK.install_ndk(ndk_pin=pin, environ=env, runner=always_corrupt, wait=wait)
            self.assertEqual([call.args[0] for call in wait.call_args_list], [10, 20])
            self.assertFalse(ndk_dir.exists())
            self.assertEqual([], list((sdk_root / '.temp').iterdir()))

    def test_fatal_sdkmanager_or_missing_binary_errors_fail_fast_without_retry(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            ndk_dir = sdk_root / 'ndk' / self.version
            pin = populate_valid_ndk(ndk_dir, self.version)
            NDK.shutil.rmtree(ndk_dir)
            wait = Mock()
            env = {'ANDROID_HOME': str(sdk_root), 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'PATH': ''}
            with self.assertRaises(FileNotFoundError):
                NDK.install_ndk(ndk_pin=pin, environ=env, wait=wait)
            wait.assert_not_called()

            populate_sdkmanager(sdk_root)
            runner = Mock(
                return_value=subprocess.CompletedProcess(
                    ['sdkmanager'], 1, stdout='Warning: Failed to find package "ndk;28.2.13676358"\n'
                )
            )
            with self.assertRaisesRegex(RuntimeError, 'Non-retryable sdkmanager error'):
                NDK.install_ndk(ndk_pin=pin, environ=env, runner=runner, wait=wait)
            runner.assert_called_once()
            wait.assert_not_called()


class ConflictingInheritedNdkAndWorkflowContractTests(unittest.TestCase):
    def test_verified_ndk_overrides_conflicting_inherited_android_ndk_for_native_source_build(self) -> None:
        version = NDK.read_ndk_version()
        with tempfile.TemporaryDirectory() as tmp:
            sdk_root = Path(tmp) / 'sdk'
            decoy_ndk = sdk_root / 'ndk/27.3.13750724'
            decoy_ndk.mkdir(parents=True)
            verified_ndk = sdk_root / 'ndk' / version
            pin = populate_valid_ndk(verified_ndk, version)

            gh_out = Path(tmp) / 'github_output.txt'
            gh_env = Path(tmp) / 'github_env.txt'
            inherited_env = {
                **os.environ,
                'ANDROID_HOME': str(sdk_root),
                'ANDROID_SDK_ROOT': str(sdk_root),
                'ANDROID_NDK': str(decoy_ndk),
                'ANDROID_NDK_HOME': str(decoy_ndk),
                'RUNNER_OS': 'Linux',
                'RUNNER_ARCH': 'X64',
                'GITHUB_OUTPUT': str(gh_out),
                'GITHUB_ENV': str(gh_env),
            }

            # Without pinned-ndk's override, ndk_directory() in scripts/native-common.sh picks the inherited 27.3 NDK.
            unfixed = subprocess.run(
                ['bash', '-c', '. scripts/native-common.sh && ndk_directory'],
                cwd=ROOT,
                env=inherited_env,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual(unfixed.stdout.strip(), str(decoy_ndk))

            # Run pinned-ndk (`key` + `install`); verify both GITHUB_ENV export and explicit step output override 27.3.
            NDK.write_key_outputs(ndk_pin=pin, environ=inherited_env)
            NDK.install_ndk(ndk_pin=pin, environ=inherited_env)
            outputs = dict(line.split('=', 1) for line in gh_out.read_text(encoding='utf-8').splitlines())
            exported = dict(line.split('=', 1) for line in gh_env.read_text(encoding='utf-8').splitlines())
            self.assertEqual(outputs['path'], str(verified_ndk))
            self.assertEqual(exported['ANDROID_NDK'], str(verified_ndk))
            self.assertEqual(exported['ANDROID_NDK_HOME'], str(verified_ndk))

            for env_after in (
                {**inherited_env, **exported},
                {**inherited_env, 'ANDROID_NDK': outputs['path']},
            ):
                resolved = subprocess.run(
                    ['bash', '-c', '. scripts/native-common.sh && ndk_directory'],
                    cwd=ROOT,
                    env=env_after,
                    capture_output=True,
                    text=True,
                    check=True,
                )
                self.assertEqual(resolved.stdout.strip(), str(verified_ndk))

    def test_pinned_ndk_action_exposes_output_and_saves_from_hosted_main_only(self) -> None:
        action = (ROOT / '.github/actions/pinned-ndk/action.yml').read_text(encoding='utf-8')
        self.assertIn('ndk-path:', action)
        self.assertIn('value: ${{ steps.pin.outputs.path }}', action)
        self.assertIn('uses: actions/cache/restore@', action)
        self.assertIn('uses: actions/cache/save@', action)
        self.assertNotIn('restore-keys:', action)
        self.assertIn("if: runner.environment == 'github-hosted' && steps.pin.outputs.installed != 'true'", action)
        self.assertIn(
            "if: github.ref == 'refs/heads/main' && runner.environment == 'github-hosted' "
            "&& steps.pin.outputs.installed != 'true' && steps.restore.outputs.cache-hit != 'true'",
            action,
        )

    def test_native_workflow_passes_verified_ndk_and_tracks_ci_ndk_script_in_path_filters(self) -> None:
        native_text = (ROOT / '.github/workflows/native.yml').read_text(encoding='utf-8')
        native_jobs = dict(RUNNERS.jobs(ROOT / '.github/workflows/native.yml'))
        native_steps = RUNNERS.steps(native_jobs['native'])
        self.assertTrue(
            any('id: ndk' in step and 'uses: ./.github/actions/pinned-ndk' in step for step in native_steps),
            'native.yml must bind id: ndk on ./.github/actions/pinned-ndk',
        )
        self.assertTrue(
            any(
                'ANDROID_NDK: ${{ steps.ndk.outputs.ndk-path }}' in step
                and './scripts/setup.sh --from-source' in step
                for step in native_steps
            ),
            'native.yml source-build step must explicitly pass ANDROID_NDK from steps.ndk.outputs.ndk-path',
        )

        closure = NATIVE_PATHS.closure(NATIVE_PATHS.workflow_script_seeds(NATIVE_PATHS.WORKFLOW))
        self.assertIn('ci-ndk.py', closure)
        lists = NATIVE_PATHS.triggers(native_text)
        self.assertIn('ci-ndk.py', lists['push'])
        self.assertIn('ci-ndk.py', lists['pull_request'])

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
