#!/usr/bin/env python3
"""Configure and verify the API 29 guest ART heap, independently of host/Gradle memory."""
import argparse
import re
from pathlib import Path

API29_HEAP_MIB = 512


def configure(api, config):
    if api != 29:
        print(f'API {api}: retaining the image VM heap')
        return
    lines = config.read_text(encoding='utf-8').splitlines()
    lines = [line for line in lines if line.partition('=')[0].strip() != 'vm.heapSize']
    # The emulator hardware schema defines vm.heapSize as an integer in MiB. hw.heapSize is not that property.
    config.write_text('\n'.join([*lines, f'vm.heapSize={API29_HEAP_MIB}']) + '\n', encoding='utf-8')
    print(f'API 29: configured vm.heapSize={API29_HEAP_MIB} MiB in {config}')


def verify(api, value):
    print(f'API {api}: guest dalvik.vm.heapsize={value!r}')
    if api != 29:
        return
    match = re.fullmatch(r'(\d+)([kmg]?)', value.strip(), re.IGNORECASE)
    if match is None:
        raise SystemExit('Cannot verify the API 29 guest ART heap')
    multiplier = {'': 1, 'k': 1024, 'm': 1024 ** 2, 'g': 1024 ** 3}[match[2].lower()]
    capacity = int(match[1]) * multiplier
    if capacity < API29_HEAP_MIB * 1024 * 1024:
        raise SystemExit(f'API 29 guest ART heap is {value!r}; requires at least {API29_HEAP_MIB} MiB')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['configure', 'verify'])
    parser.add_argument('api', type=int)
    parser.add_argument('value', help='AVD config.ini path for configure; booted dalvik.vm.heapsize for verify')
    args = parser.parse_args()
    if args.mode == 'configure':
        configure(args.api, Path(args.value))
    else:
        verify(args.api, args.value)


if __name__ == '__main__':
    main()
