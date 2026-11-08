#!/usr/bin/env python3
"""Explicit native-only lab probe. Pushes to a fresh temporary directory; never installs or clears an app."""

import argparse
import hashlib
import json
import subprocess
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit owned arm64 test device/emulator')
    parser.add_argument('--build', type=Path, default=Path('.tools/whistle'))
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()

    def adb(*command, check=True):
        return subprocess.run(
            [args.adb, '-s', args.serial, *command], check=check, capture_output=True, text=True, timeout=60,
        )

    adb('wait-for-device')  # ADB transport event; no fixed boot/settling sleep.
    abi = adb('shell', 'getprop', 'ro.product.cpu.abi').stdout.strip()
    api = int(adb('shell', 'getprop', 'ro.build.version.sdk').stdout.strip())
    if abi != 'arm64-v8a' or api < 26:
        raise ValueError(f'Owned arm64 API >=26 test target required, got {abi} API {api}')
    report = json.loads((args.build / 'report.json').read_text())
    if hashlib.sha256((args.build / 'libcassini_needle.so').read_bytes()).hexdigest() != report['sha256']:
        raise ValueError('Runtime library changed since the verified build')
    # Fixed safe path prefix and UUID; never use a user-supplied directory in a remote shell command.
    remote = '/data/local/tmp/cassini-whistle-' + uuid.uuid4().hex
    adb('shell', 'mkdir', remote)
    try:
        for name in ('load-smoke', 'libcassini_needle.so'):
            adb('push', str(args.build / name), remote + '/' + name)
        adb('shell', 'chmod', '700', remote + '/load-smoke')
        flags = {'NEEDLE_TELEMETRY': '0', 'DO_NOT_TRACK': '1', 'ORT_DISABLE_TELEMETRY': '1'}
        guard_results = []
        for name in flags:
            for bad in (None, 'wrong'):
                environment = {key: value for key, value in flags.items() if key != name}
                if bad is not None:
                    environment[name] = bad
                # A nonexistent library proves the guard ran before dlopen, not after a failed library load.
                result = adb(
                    'shell', 'env', '-i', *(f'{key}={value}' for key, value in environment.items()),
                    remote + '/load-smoke', remote + '/does-not-exist.so', check=False,
                )
                if result.returncode != 2 or 'Privacy guard: ' + name not in result.stderr + result.stdout:
                    raise ValueError(f'Pre-load guard failed for {name}: {result}')
                guard_results.append({'flag': name, 'value': bad, 'exitCode': result.returncode})
        result = adb(
            'shell', 'env', '-i', *(f'{key}={value}' for key, value in flags.items()),
            remote + '/load-smoke', remote + '/libcassini_needle.so',
        )
        if 'Whistle C API loaded; models=0; pageSize=' not in result.stdout:
            raise ValueError(f'Missing native load evidence: {result}')
        report['runtimeTest'] = {
            'serial': args.serial, 'abi': abi, 'api': api, 'output': result.stdout.strip(),
            'preLoadGuards': guard_results,
            'scope': 'RTLD_NOW load/C ABI only; no model, inference, app worker or DC parity claim',
        }
        (args.build / 'device-report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report['runtimeTest'], indent=2))
    finally:
        adb('shell', 'rm', '-rf', remote)


if __name__ == '__main__':
    main()
