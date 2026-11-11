#!/usr/bin/env python3
"""Explicitly cache, install with bounded retries, and verify the pinned Android NDK before Gradle runs."""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from collections.abc import Callable, Mapping
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE_SCHEMA = 'v1'
ATTEMPTS = 3
RETRY_DELAYS = (10, 20)
SDKMANAGER_TIMEOUT_SECONDS = 300
REQUIRED_LLVM_TOOLS = (
    'clang',
    'clang++',
    'aarch64-linux-android26-clang',
    'aarch64-linux-android26-clang++',
    'x86_64-linux-android26-clang',
    'x86_64-linux-android26-clang++',
    'llvm-ar',
    'llvm-nm',
    'llvm-objcopy',
    'llvm-readelf',
    'llvm-strip',
)
FATAL_SDKMANAGER_PATTERNS = (
    'Failed to find package',
    'JAVA_HOME is not set',
    'UnsupportedClassVersionError',
    'Unknown argument',
)


def read_ndk_version(
    catalog: Path = ROOT / 'gradle/libs.versions.toml',
    *,
    whistle_script: Path | None = ROOT / 'scripts/build-whistle-runtime.py',
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


def cache_key(
    version: str,
    *,
    sdk_root: Path,
    runner_os: str | None = None,
    runner_arch: str | None = None,
) -> str:
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)+', version):
        raise ValueError(f'Invalid NDK version for cache key: {version!r}')
    os_tag = _normalize_os(runner_os)
    arch_tag = _normalize_arch(runner_arch)
    sdk_norm = os.path.normpath(str(sdk_root))
    sdk_digest = hashlib.sha256(sdk_norm.encode('utf-8')).hexdigest()[:12]
    return f'ndk-{CACHE_SCHEMA}-{os_tag}-{arch_tag}-{version}-{sdk_digest}'


def verify_ndk(ndk_dir: Path, version: str, *, system: str | None = None) -> list[str]:
    problems: list[str] = []
    if not ndk_dir.is_dir():
        return [f'missing NDK directory {ndk_dir}']
    if (ndk_dir / '.installer').exists():
        problems.append('incomplete sdkmanager .installer marker present')
    source_props = ndk_dir / 'source.properties'
    if not source_props.is_file():
        problems.append('missing source.properties')
    else:
        props_text = source_props.read_text(encoding='utf-8', errors='replace')
        if not re.search(rf'^Pkg\.Revision\s*=\s*{re.escape(version)}\s*$', props_text, re.MULTILINE):
            problems.append(f'source.properties Pkg.Revision does not match {version}')
    toolchain_cmake = ndk_dir / 'build/cmake/android.toolchain.cmake'
    if not toolchain_cmake.is_file() or toolchain_cmake.stat().st_size == 0:
        problems.append('missing or empty build/cmake/android.toolchain.cmake')
    host_name = _normalize_os(system)
    host_tag = {'Linux': 'linux-x86_64', 'macOS': 'darwin-x86_64'}.get(host_name)
    if host_tag is None:
        problems.append(f'unsupported NDK host {host_name}')
        return problems
    llvm_bin = ndk_dir / 'toolchains/llvm/prebuilt' / host_tag / 'bin'
    if not llvm_bin.is_dir():
        problems.append(f'missing LLVM toolchain directory {llvm_bin}')
        return problems
    for tool_name in REQUIRED_LLVM_TOOLS:
        tool_path = llvm_bin / tool_name
        if not tool_path.is_file() or not os.access(tool_path, os.X_OK):
            problems.append(f'missing or non-executable LLVM tool {tool_name}')
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


def ndk_info(
    *,
    catalog: Path = ROOT / 'gradle/libs.versions.toml',
    whistle_script: Path | None = ROOT / 'scripts/build-whistle-runtime.py',
    environ: Mapping[str, str] = os.environ,
) -> dict[str, object]:
    version = read_ndk_version(catalog, whistle_script=whistle_script)
    sdk_root = resolve_sdk_root(environ)
    ndk_dir = sdk_root / 'ndk' / version
    key = cache_key(
        version,
        sdk_root=sdk_root,
        runner_os=environ.get('RUNNER_OS'),
        runner_arch=environ.get('RUNNER_ARCH'),
    )
    installed = not verify_ndk(ndk_dir, version, system=environ.get('RUNNER_OS'))
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
    environ: Mapping[str, str] = os.environ,
) -> dict[str, object]:
    info = ndk_info(catalog=catalog, whistle_script=whistle_script, environ=environ)
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
    environ: Mapping[str, str] = os.environ,
    runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
    wait: Callable[[float], None] = time.sleep,
) -> str:
    version = read_ndk_version(catalog, whistle_script=whistle_script)
    sdk_root = resolve_sdk_root(environ)
    ndk_dir = sdk_root / 'ndk' / version
    system = environ.get('RUNNER_OS')
    if not verify_ndk(ndk_dir, version, system=system):
        print(f'Verified Android NDK {version} at {ndk_dir}', flush=True)
        return 'verified'

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
                problems = verify_ndk(ndk_dir, version, system=system)
                if not problems:
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
