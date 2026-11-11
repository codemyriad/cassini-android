#!/usr/bin/env python3
"""Copy the exact APK inputs used by every x86_64 emulator, preserving the smoke script's directory layout."""
import os
import shutil
from pathlib import Path


def one(directory, pattern):
    matches = list(directory.glob(pattern))
    if len(matches) != 1:
        raise SystemExit(f'Expected one {directory}/{pattern}, found {len(matches)}')
    return matches[0]


def stage(root, variant):
    if variant not in {'debug', 'releaseTest'}:
        raise SystemExit(f'Unsupported device test build type: {variant}')
    destination = root / 'build/emulator-inputs'
    if destination.exists():
        shutil.rmtree(destination)
    inputs = [
        (one(root / 'dist', '*-x86_64.apk'), 'ci-apk'),
        (one(root / f'app/build/outputs/apk/{variant}', 'app-x86_64-*.apk'), f'ci-tests/{variant}'),
        (one(root / f'app/build/outputs/apk/androidTest/{variant}', '*.apk'), f'ci-tests/androidTest/{variant}'),
        (one(root / 'data/build/outputs/apk/androidTest/debug', '*.apk'), 'ci-data-tests'),
    ]
    for source, relative in inputs:
        target = destination / relative / source.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    return destination


if __name__ == '__main__':
    print(stage(Path.cwd(), os.environ.get('DEVICE_TEST_TYPE', 'debug')))
