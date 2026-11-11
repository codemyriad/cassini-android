#!/usr/bin/env python3
"""Explicitly cache, install with bounded retries, and cryptographically verify the pinned Android NDK."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
PINS_FILE = ROOT / 'scripts/ci-download-pins.json'
CACHE_SCHEMA = 'v2'
ATTEMPTS = 3
RETRY_DELAYS = (10, 20)
SDKMANAGER_TIMEOUT_SECONDS = 300
IGNORED_TREE_FILES = frozenset({'package.xml', '.cassini-ndk-manifest.json'})
REQUIRED_LLVM_EXECUTABLES = (
    'clang',
    'clang++',
    'clang-19',
    'ld.lld',
    'lld',
    'aarch64-linux-android26-clang',
    'aarch64-linux-android26-clang++',
    'x86_64-linux-android26-clang',
    'x86_64-linux-android26-clang++',
    'llvm-ar',
    'llvm-nm',
    'llvm-objcopy',
    'llvm-readelf',
    'llvm-readobj',
    'llvm-strip',
)
NATIVE_ELF_TOOLS = (
    'clang-19',
    'lld',
    'llvm-ar',
    'llvm-nm',
    'llvm-objcopy',
    'llvm-readobj',
)
REQUIRED_SYSROOT_PATHS = (
    'usr/include/jni.h',
    'usr/include/c++/v1/__config',
    'usr/lib/aarch64-linux-android/26/crtbegin_so.o',
    'usr/lib/aarch64-linux-android/26/crtend_so.o',
    'usr/lib/aarch64-linux-android/26/libc.so',
    'usr/lib/aarch64-linux-android/26/libm.so',
    'usr/lib/aarch64-linux-android/26/libdl.so',
    'usr/lib/aarch64-linux-android/26/liblog.so',
    'usr/lib/aarch64-linux-android/libc++_static.a',
    'usr/lib/aarch64-linux-android/libc++abi.a',
    'usr/lib/x86_64-linux-android/26/crtbegin_so.o',
    'usr/lib/x86_64-linux-android/26/crtend_so.o',
    'usr/lib/x86_64-linux-android/26/libc.so',
    'usr/lib/x86_64-linux-android/26/libm.so',
    'usr/lib/x86_64-linux-android/26/libdl.so',
    'usr/lib/x86_64-linux-android/26/liblog.so',
    'usr/lib/x86_64-linux-android/libc++_static.a',
    'usr/lib/x86_64-linux-android/libc++abi.a',
)
FATAL_SDKMANAGER_PATTERNS = (
    'Failed to find package',
    'JAVA_HOME is not set',
    'UnsupportedClassVersionError',
    'Unknown argument',
)


def sha256_file(path: Path) -> str:
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def load_ndk_pin(pins_path: Path = PINS_FILE) -> dict[str, Any]:
    data = json.loads(pins_path.read_text(encoding='utf-8'))
    ndk_pin = data.get('ndk')
    if not isinstance(ndk_pin, dict) or not isinstance(ndk_pin.get('hosts'), dict):
        raise ValueError(f'Missing committed ndk pin dictionary in {pins_path}')
    return ndk_pin


def read_ndk_version(
    catalog: Path = ROOT / 'gradle/libs.versions.toml',
    *,
    whistle_script: Path | None = ROOT / 'scripts/build-whistle-runtime.py',
    ndk_pin: Mapping[str, Any] | None = None,
) -> str:
    text = catalog.read_text(encoding='utf-8')
    matches = re.findall(r'^ndk\s*=\s*"([^"]+)"\s*$', text, re.MULTILINE)
    if len(matches) != 1 or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)+', matches[0]):
        raise ValueError(f'Expected a single numeric ndk version in {catalog}')
    version = matches[0]
    if whistle_script is not None and whistle_script.is_file():
        whistle_text = whistle_script.read_text(encoding='utf-8')
        whistle_match = re.search(r"^NDK_VERSION\s*=\s*'([^']+)'\s*$", whistle_text, re.MULTILINE)
        if whistle_match and whistle_match.group(1) != version:
            raise ValueError(
                f'NDK pin mismatch between {catalog} ({version}) and {whistle_script} ({whistle_match.group(1)})'
            )
    committed_pin = load_ndk_pin() if ndk_pin is None else ndk_pin
    pin_version = committed_pin.get('version')
    if pin_version != version:
        raise ValueError(f'NDK pin mismatch between {catalog} ({version}) and ci-download-pins.json ({pin_version})')
    return version


def resolve_sdk_root(environ: Mapping[str, str] = os.environ) -> Path:
    for variable in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        raw = environ.get(variable, '').strip()
        if raw:
            return Path(os.path.normpath(raw))
    raise ValueError('Set ANDROID_HOME or ANDROID_SDK_ROOT before installing or caching the Android NDK')


def _normalize_os(runner_os: str | None = None) -> str:
    raw = (runner_os or platform.system()).strip()
    mapping = {'linux': 'Linux', 'darwin': 'macOS', 'macos': 'macOS', 'windows': 'Windows'}
    normalized = mapping.get(raw.lower())
    if not normalized:
        raise ValueError(f'Unsupported host OS for Android NDK cache: {raw!r}')
    return normalized


def _normalize_arch(runner_arch: str | None = None) -> str:
    raw = (runner_arch or platform.machine()).strip()
    mapping = {'x86_64': 'X64', 'amd64': 'X64', 'x64': 'X64', 'aarch64': 'ARM64', 'arm64': 'ARM64'}
    normalized = mapping.get(raw.lower())
    if not normalized:
        raise ValueError(f'Unsupported host architecture for Android NDK cache: {raw!r}')
    return normalized


def host_pin_for(
    ndk_pin: Mapping[str, Any],
    *,
    runner_os: str | None = None,
    runner_arch: str | None = None,
) -> tuple[str, Mapping[str, Any]]:
    platform_key = f'{_normalize_os(runner_os)}-{_normalize_arch(runner_arch)}'
    hosts = ndk_pin.get('hosts', {})
    host_pin = hosts.get(platform_key)
    if not isinstance(host_pin, Mapping):
        raise ValueError(f'No committed Android NDK checksum pin for host {platform_key}')
    return platform_key, host_pin


def compute_ndk_tree_digest(ndk_dir: Path) -> tuple[int, str]:
    """Compute a deterministic SHA-256 digest over every file and symlink in ndk_dir."""
    records: list[str] = []
    for root, dirs, filenames in os.walk(ndk_dir, followlinks=False):
        root_path = Path(root)
        kept_dirs: list[str] = []
        for dirname in sorted(dirs):
            if dirname == '__pycache__':
                continue
            dir_path = root_path / dirname
            if dir_path.is_symlink():
                rel = dir_path.relative_to(ndk_dir).as_posix()
                records.append(f'L\t{rel}\t{os.readlink(dir_path)}\n')
            else:
                kept_dirs.append(dirname)
        dirs[:] = kept_dirs
        for filename in sorted(filenames):
            if filename in IGNORED_TREE_FILES or filename.endswith('.pyc'):
                continue
            file_path = root_path / filename
            rel = file_path.relative_to(ndk_dir).as_posix()
            if file_path.is_symlink():
                records.append(f'L\t{rel}\t{os.readlink(file_path)}\n')
            elif file_path.is_file():
                size = file_path.stat().st_size
                digest = sha256_file(file_path)
                records.append(f'F\t{rel}\t{size}\t{digest}\n')
            else:
                records.append(f'U\t{rel}\n')
    records.sort()
    tree_sha256 = hashlib.sha256(''.join(records).encode('utf-8')).hexdigest()
    return len(records), tree_sha256


def cache_key(
    version: str,
    *,
    sdk_root: Path,
    runner_os: str | None = None,
    runner_arch: str | None = None,
    ndk_pin: Mapping[str, Any] | None = None,
) -> str:
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)+', version):
        raise ValueError(f'Invalid NDK version for cache key: {version!r}')
    committed = load_ndk_pin() if ndk_pin is None else ndk_pin
    platform_key, host_pin = host_pin_for(committed, runner_os=runner_os, runner_arch=runner_arch)
    tree_sha = str(host_pin['treeSha256'])
    if not re.fullmatch(r'[0-9a-f]{64}', tree_sha):
        raise ValueError(f'Invalid committed treeSha256 for {platform_key}: {tree_sha!r}')
    sdk_norm = os.path.normpath(str(sdk_root))
    sdk_digest = hashlib.sha256(sdk_norm.encode('utf-8')).hexdigest()[:12]
    return f'ndk-{CACHE_SCHEMA}-{platform_key}-{version}-{tree_sha[:16]}-{sdk_digest}'


def verify_ndk(
    ndk_dir: Path,
    version: str,
    *,
    runner_os: str | None = None,
    runner_arch: str | None = None,
    ndk_pin: Mapping[str, Any] | None = None,
) -> list[str]:
    problems: list[str] = []
    if not ndk_dir.is_dir():
        return [f'missing NDK directory {ndk_dir}']
    if (ndk_dir / '.installer').exists():
        problems.append('incomplete sdkmanager .installer marker present')

    committed = load_ndk_pin() if ndk_pin is None else ndk_pin
    if committed.get('version') != version:
        problems.append(f'committed NDK pin version {committed.get("version")!r} != expected {version!r}')
        return problems
    try:
        platform_key, host_pin = host_pin_for(committed, runner_os=runner_os, runner_arch=runner_arch)
    except ValueError as exc:
        return [str(exc)]

    source_props = ndk_dir / 'source.properties'
    if not source_props.is_file():
        problems.append('missing source.properties')
    else:
        props_text = source_props.read_text(encoding='utf-8', errors='replace')
        if not re.search(rf'^Pkg\.Revision\s*=\s*{re.escape(version)}\s*$', props_text, re.MULTILINE):
            problems.append(f'source.properties Pkg.Revision does not match {version}')

    for cmake_rel in ('build/cmake/android.toolchain.cmake', 'build/cmake/abis.cmake', 'build/cmake/platforms.cmake'):
        cmake_file = ndk_dir / cmake_rel
        if not cmake_file.is_file() or cmake_file.stat().st_size == 0:
            problems.append(f'missing or empty {cmake_rel}')

    host_tag = str(host_pin['hostTag'])
    toolchain_root = ndk_dir / 'toolchains/llvm/prebuilt' / host_tag
    llvm_bin = toolchain_root / 'bin'
    sysroot = toolchain_root / 'sysroot'
    if not llvm_bin.is_dir():
        problems.append(f'missing LLVM toolchain directory {llvm_bin}')
    else:
        for tool_name in REQUIRED_LLVM_EXECUTABLES:
            tool_path = llvm_bin / tool_name
            if not tool_path.is_file() or not os.access(tool_path, os.X_OK):
                problems.append(f'missing or non-executable LLVM tool {tool_name}')
        if platform_key.startswith('Linux-'):
            for elf_name in NATIVE_ELF_TOOLS:
                elf_path = llvm_bin / elf_name
                if elf_path.is_file() and not elf_path.is_symlink():
                    with elf_path.open('rb') as handle:
                        magic = handle.read(4)
                    if magic != b'\x7fELF':
                        problems.append(f'LLVM tool {elf_name} is not an ELF binary')

    if not sysroot.is_dir():
        problems.append(f'missing NDK sysroot directory {sysroot}')
    else:
        for sysroot_rel in REQUIRED_SYSROOT_PATHS:
            sysroot_file = sysroot / sysroot_rel
            if not sysroot_file.is_file() or sysroot_file.stat().st_size == 0:
                problems.append(f'missing or empty sysroot file {sysroot_rel}')

    for link_rel, expected_target in host_pin.get('symlinks', {}).items():
        link_path = ndk_dir / link_rel
        if not link_path.is_symlink():
            problems.append(f'expected symlink at {link_rel}')
        elif os.readlink(link_path) != expected_target:
            problems.append(f'symlink {link_rel} points to {os.readlink(link_path)!r}, expected {expected_target!r}')

    for file_rel, expected_meta in host_pin.get('files', {}).items():
        file_path = ndk_dir / file_rel
        if file_path.is_symlink() or not file_path.is_file():
            problems.append(f'missing regular pinned file {file_rel}')
            continue
        actual_size = file_path.stat().st_size
        if actual_size != expected_meta['size']:
            problems.append(f'size mismatch for {file_rel}: {actual_size} != {expected_meta["size"]}')
            continue
        actual_sha = sha256_file(file_path)
        if actual_sha != expected_meta['sha256']:
            problems.append(f'SHA-256 mismatch for {file_rel}: {actual_sha} != {expected_meta["sha256"]}')

    if problems:
        return problems

    entry_count, tree_sha = compute_ndk_tree_digest(ndk_dir)
    if entry_count != host_pin['entryCount']:
        problems.append(f'NDK tree entry count mismatch: {entry_count} != {host_pin["entryCount"]}')
    if tree_sha != host_pin['treeSha256']:
        problems.append(f'NDK tree SHA-256 mismatch: {tree_sha} != {host_pin["treeSha256"]}')
    return problems


def find_sdkmanager(
    sdk_root: Path,
    *,
    environ: Mapping[str, str] = os.environ,
    which: Callable[[str, str | None], str | None] = lambda cmd, path: shutil.which(cmd, path=path),
) -> Path:
    candidates: list[Path] = [sdk_root / 'cmdline-tools/latest/bin/sdkmanager']
    cmdline_dir = sdk_root / 'cmdline-tools'
    if cmdline_dir.is_dir():
        versioned = sorted(
            (entry / 'bin/sdkmanager' for entry in cmdline_dir.iterdir() if entry.name != 'latest'),
            reverse=True,
        )
        candidates.extend(versioned)
    on_path = which('sdkmanager', environ.get('PATH'))
    if on_path:
        candidates.append(Path(on_path))
    candidates.append(sdk_root / 'tools/bin/sdkmanager')
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise FileNotFoundError(f'Executable sdkmanager not found under {sdk_root} or PATH')


def clean_sdk_temp(sdk_root: Path) -> None:
    temp_dir = sdk_root / '.temp'
    if not temp_dir.is_dir():
        return
    for entry in temp_dir.iterdir():
        if entry.is_dir() and not entry.is_symlink():
            shutil.rmtree(entry, ignore_errors=True)
        else:
            entry.unlink(missing_ok=True)


def export_verified_ndk_env(ndk_dir: Path, environ: Mapping[str, str] = os.environ) -> None:
    """Override any runner-inherited ANDROID_NDK / ANDROID_NDK_HOME for subsequent workflow steps."""
    github_env = environ.get('GITHUB_ENV', '').strip()
    if not github_env:
        return
    with Path(github_env).open('a', encoding='utf-8') as handle:
        handle.write(f'ANDROID_NDK={ndk_dir}\n')
        handle.write(f'ANDROID_NDK_HOME={ndk_dir}\n')


def ndk_info(
    *,
    catalog: Path = ROOT / 'gradle/libs.versions.toml',
    whistle_script: Path | None = ROOT / 'scripts/build-whistle-runtime.py',
    ndk_pin: Mapping[str, Any] | None = None,
    environ: Mapping[str, str] = os.environ,
) -> dict[str, object]:
    committed = load_ndk_pin() if ndk_pin is None else ndk_pin
    version = read_ndk_version(catalog, whistle_script=whistle_script, ndk_pin=committed)
    sdk_root = resolve_sdk_root(environ)
    ndk_dir = sdk_root / 'ndk' / version
    runner_os = environ.get('RUNNER_OS')
    runner_arch = environ.get('RUNNER_ARCH')
    key = cache_key(
        version,
        sdk_root=sdk_root,
        runner_os=runner_os,
        runner_arch=runner_arch,
        ndk_pin=committed,
    )
    installed = not verify_ndk(
        ndk_dir,
        version,
        runner_os=runner_os,
        runner_arch=runner_arch,
        ndk_pin=committed,
    )
    return {
        'version': version,
        'sdk_root': sdk_root,
        'ndk_dir': ndk_dir,
        'cache_key': key,
        'installed': installed,
    }


def write_key_outputs(
    *,
    catalog: Path = ROOT / 'gradle/libs.versions.toml',
    whistle_script: Path | None = ROOT / 'scripts/build-whistle-runtime.py',
    ndk_pin: Mapping[str, Any] | None = None,
    environ: Mapping[str, str] = os.environ,
) -> dict[str, object]:
    info = ndk_info(catalog=catalog, whistle_script=whistle_script, ndk_pin=ndk_pin, environ=environ)
    lines = [
        f"key={info['cache_key']}",
        f"path={info['ndk_dir']}",
        f"version={info['version']}",
        f"installed={'true' if info['installed'] else 'false'}",
    ]
    github_output = environ.get('GITHUB_OUTPUT', '').strip()
    if github_output:
        with Path(github_output).open('a', encoding='utf-8') as handle:
            for line in lines:
                handle.write(line + '\n')
    else:
        for line in lines:
            print(line)
    return info


def install_ndk(
    *,
    catalog: Path = ROOT / 'gradle/libs.versions.toml',
    whistle_script: Path | None = ROOT / 'scripts/build-whistle-runtime.py',
    ndk_pin: Mapping[str, Any] | None = None,
    environ: Mapping[str, str] = os.environ,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    wait: Callable[[float], None] = time.sleep,
) -> str:
    committed = load_ndk_pin() if ndk_pin is None else ndk_pin
    version = read_ndk_version(catalog, whistle_script=whistle_script, ndk_pin=committed)
    sdk_root = resolve_sdk_root(environ)
    ndk_dir = sdk_root / 'ndk' / version
    runner_os = environ.get('RUNNER_OS')
    runner_arch = environ.get('RUNNER_ARCH')
    initial_problems = verify_ndk(
        ndk_dir,
        version,
        runner_os=runner_os,
        runner_arch=runner_arch,
        ndk_pin=committed,
    )
    if not initial_problems:
        export_verified_ndk_env(ndk_dir, environ)
        print(f'Verified Android NDK {version} at {ndk_dir}', flush=True)
        return 'verified'
    if ndk_dir.exists():
        print(
            f'::warning title=Android NDK verification::Discarding unverified NDK at {ndk_dir} '
            f'({"; ".join(initial_problems[:3])}) and reinstalling from sdkmanager.',
            flush=True,
        )

    sdkmanager = find_sdkmanager(sdk_root, environ=environ)
    last_reason = 'unknown sdkmanager failure'
    last_tail = ''
    for attempt in range(1, ATTEMPTS + 1):
        shutil.rmtree(ndk_dir, ignore_errors=True)
        clean_sdk_temp(sdk_root)
        sdk_root.mkdir(parents=True, exist_ok=True)
        command = [str(sdkmanager), f'--sdk_root={sdk_root}', f'ndk;{version}']
        try:
            completed = runner(
                command,
                input='y\n' * 32,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=SDKMANAGER_TIMEOUT_SECONDS,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            last_reason = f'sdkmanager timed out after {SDKMANAGER_TIMEOUT_SECONDS}s'
            raw_out = (
                exc.stdout.decode('utf-8', errors='replace') if isinstance(exc.stdout, bytes) else (exc.stdout or '')
            )
            last_tail = '\n'.join(raw_out.splitlines()[-20:])
        else:
            output = completed.stdout or ''
            last_tail = '\n'.join(output.splitlines()[-20:])
            for fatal in FATAL_SDKMANAGER_PATTERNS:
                if fatal in output:
                    shutil.rmtree(ndk_dir, ignore_errors=True)
                    clean_sdk_temp(sdk_root)
                    raise RuntimeError(f'Non-retryable sdkmanager error for ndk;{version} ({fatal}):\n{last_tail}')
            if completed.returncode != 0:
                last_reason = f'sdkmanager exited with status {completed.returncode}'
            else:
                problems = verify_ndk(
                    ndk_dir,
                    version,
                    runner_os=runner_os,
                    runner_arch=runner_arch,
                    ndk_pin=committed,
                )
                if not problems:
                    export_verified_ndk_env(ndk_dir, environ)
                    print(
                        f'Installed and verified Android NDK {version} at {ndk_dir} (attempt {attempt}/{ATTEMPTS})',
                        flush=True,
                    )
                    return 'installed'
                last_reason = 'post-install verification failed: ' + '; '.join(problems)

        if attempt < ATTEMPTS:
            delay = RETRY_DELAYS[attempt - 1]
            print(
                f'::warning title=Android NDK retry::Attempt {attempt}/{ATTEMPTS} for ndk;{version} failed '
                f'({last_reason}); cleaning partial SDK state and retrying in {delay}s.',
                flush=True,
            )
            wait(delay)

    shutil.rmtree(ndk_dir, ignore_errors=True)
    clean_sdk_temp(sdk_root)
    detail = f'\n{last_tail}' if last_tail else ''
    raise RuntimeError(f'Failed to install and verify ndk;{version} after {ATTEMPTS} attempts ({last_reason}){detail}')


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['key', 'install'])
    args = parser.parse_args(argv)
    if args.stage == 'key':
        write_key_outputs()
    else:
        install_ndk()
    return 0


if __name__ == '__main__':
    sys.exit(main())
