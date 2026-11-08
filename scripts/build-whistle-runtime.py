#!/usr/bin/env python3
"""Build/verify the private D75.2 arm64 runtime, without Gradle, model downloads or app installation.

Only published hash-verified inputs are accepted. This is not a native-source rebuild or an inference test.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import struct
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NDK_VERSION = '28.2.13676358'
EXPORTS = {'needle_load', 'needle_models', 'needle_last_error', 'needle_transcribe'}
SYSTEM_LIBRARIES = {'libc.so', 'libm.so', 'libdl.so'}


def checked(path: Path, pin: dict) -> None:
    data = path.read_bytes()
    if len(data) != pin['size'] or hashlib.sha256(data).hexdigest() != pin['sha256']:
        raise ValueError(f'Whistle input size/SHA-256 mismatch: {path}')


def fetch(path: Path, pin: dict, offline: bool) -> None:
    if path.exists():
        checked(path, pin)
        return
    if offline:
        raise ValueError(f'Missing offline Whistle input: {path}')
    # Bound the transfer before writing; never turn an unverified partial file into a cached input.
    with urllib.request.urlopen(pin['url'], timeout=60) as response:
        data = response.read(pin['size'] + 1)
    if len(data) != pin['size'] or hashlib.sha256(data).hexdigest() != pin['sha256']:
        raise ValueError(f'Whistle download size/SHA-256 mismatch: {pin["url"]}')
    temporary = path.with_suffix(path.suffix + '.part')
    temporary.write_bytes(data)
    temporary.replace(path)


def run(command: list[str | Path]) -> str:
    result = subprocess.run([str(arg) for arg in command], check=True, capture_output=True, text=True)
    return result.stdout


def verify_elf(path: Path, readelf: Path) -> dict:
    data = path.read_bytes()
    if data[:7] != b'\x7fELF\x02\x01\x01' or struct.unpack_from('<HH', data, 16) != (3, 183):
        raise ValueError('Expected an ELF64 little-endian AArch64 shared library')
    offset = struct.unpack_from('<Q', data, 32)[0]
    entry_size, count = struct.unpack_from('<HH', data, 54)
    loads = []
    for index in range(count):
        kind, _, at, address, _, _, _, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + index * entry_size)
        if kind == 1:  # PT_LOAD, independently inspect actual ELF values rather than trusting compiler flags.
            if alignment < 16384 or at % 16384 != address % 16384:
                raise ValueError('Whistle PT_LOAD is not 16 KB aligned')
            loads.append(alignment)
    if not loads:
        raise ValueError('Whistle has no loadable segments')
    dynamic = run([readelf, '--dynamic', '--wide', path])
    if 'TEXTREL' in dynamic or 'RPATH' in dynamic or 'RUNPATH' in dynamic:
        raise ValueError('Whistle has text relocations or a runtime library search path')
    needed = set(re.findall(r'\(NEEDED\).*\[([^]]+)\]', dynamic))
    if needed != SYSTEM_LIBRARIES:
        raise ValueError(f'Unexpected dependency closure: {sorted(needed)}')
    symbols = run([readelf, '--dyn-syms', '--wide', path])
    exports = set()
    imports = []
    weak_imports = []
    for line in symbols.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].endswith(':') or not fields[0][:-1].isdigit():
            continue
        if fields[6] == 'UND':
            (weak_imports if fields[4] == 'WEAK' else imports).append(fields[7])
        elif fields[4] in ('GLOBAL', 'WEAK') and fields[5] == 'DEFAULT':
            exports.add(fields[7])
    if exports != EXPORTS:
        raise ValueError(f'Unexpected public C ABI: {sorted(exports)}')
    if any('__ndk' in name or name.startswith('_Z') or name.startswith('Ort') for name in imports):
        raise ValueError('Unresolved C++ or ONNX Runtime dependency')
    return {
        'loadAlignments': loads, 'needed': sorted(needed), 'exports': sorted(exports),
        'imports': sorted(imports), 'weakImports': sorted(weak_imports),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--abi', choices=('arm64-v8a', 'x86_64'), default='arm64-v8a')
    parser.add_argument('--output', type=Path, default=ROOT / '.tools/whistle')
    parser.add_argument('--offline', action='store_true', help='Require every pinned input in output/inputs')
    args = parser.parse_args()
    pins = json.loads((ROOT / 'whistle/pins.json').read_text())
    if pins[args.abi] is None:
        raise ValueError('Whistle unavailable for x86_64: no published D5 Android engine; use Parakeet')
    if not args.ndk or not (args.ndk / 'source.properties').is_file():
        raise ValueError('Set --ndk or ANDROID_NDK_HOME to the pinned Android NDK')
    properties = (args.ndk / 'source.properties').read_text()
    if not re.search(rf'^Pkg\.Revision\s*=\s*{re.escape(NDK_VERSION)}\s*$', properties, re.MULTILINE):
        raise ValueError(f'Whistle requires NDK {NDK_VERSION}')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}[platform.system()]
    tools = args.ndk.resolve() / 'toolchains/llvm/prebuilt' / host / 'bin'
    output = args.output.resolve()
    inputs = output / 'inputs'
    inputs.mkdir(parents=True, exist_ok=True)
    for name, pin in pins[args.abi].items():
        fetch(inputs / name, pin, args.offline)
    library = output / 'libcassini_needle.so'
    command = [
        tools / 'aarch64-linux-android26-clang++', '-std=c++17', '-shared', '-fPIC', '-O2',
        ROOT / 'whistle/abi-check.cpp', '-I', inputs,
        '-Wl,--whole-archive', inputs / 'libneedle.a', '-Wl,--no-whole-archive',
        '-static-libstdc++', '-Wl,--no-undefined', '-Wl,-z,text', '-Wl,-z,now', '-Wl,-z,relro',
        '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
        f'-Wl,--version-script={ROOT / "whistle/exports.map"}', '-Wl,-soname,libcassini_needle.so',
        '-o', library,
    ]
    run(command)
    run([tools / 'llvm-strip', '--strip-unneeded', library])
    report = verify_elf(library, tools / 'llvm-readelf')
    probe = output / 'load-smoke'
    run([
        tools / 'aarch64-linux-android26-clang', '-std=c11', '-D_POSIX_C_SOURCE=200809L',
        '-Wall', '-Wextra', '-Werror', '-fPIE', '-pie', ROOT / 'whistle/load-smoke.c', '-ldl',
        '-Wl,--no-undefined', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-o', probe,
    ])
    report.update({
        'engineRevision': pins['engineRevision'], 'engineVersion': pins['engineVersion'],
        'abi': args.abi, 'androidApi': 26, 'ndk': NDK_VERSION,
        'size': library.stat().st_size, 'sha256': hashlib.sha256(library.read_bytes()).hexdigest(),
        'compiler': run([tools / 'aarch64-linux-android26-clang++', '--version']).splitlines()[0],
        'linkCommand': [str(arg) for arg in command],
        'runtimeTest': 'not run; link/symbol/alignment checks do not establish device inference or parity',
    })
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Verified arm64 API 26 link, PIC, closure and 16 KB ELF alignment: {library}')
    print(f'{report["size"]} bytes; SHA-256 {report["sha256"]}; full report: {output / "report.json"}')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f'Whistle build failed: {error}', file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr, file=sys.stderr)
        sys.exit(1)
