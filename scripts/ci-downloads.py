#!/usr/bin/env python3
"""D66: bounded network-only bootstrap; compilation, analysis and tests are never retried."""

import argparse
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ATTEMPTS = 4
TRANSIENT = re.compile(
    r'(?:response code:|status code|HTTP[/\d.]*\s+)\s*(?:429|500|502|503|504)\b'
    r'|SocketTimeoutException|ConnectException|UnknownHostException|Connection reset|Read timed out',
    re.IGNORECASE,
)
PERMANENT = re.compile(
    r'checksum|SHA-256|Compilation failed|AssertionError'
    r'|(?:response code:|status code|HTTP[/\d.]*\s+)\s*(?:400|401|403|404)\b',
    re.IGNORECASE,
)
WGET = 'wget -c -q https://github.com/csukuangfj/onnxruntime-libs/'
BOUNDED_WGET = ('wget -c --tries=4 --timeout=30 --waitretry=2 '
                '--retry-on-http-error=429,500,502,503,504 https://github.com/csukuangfj/onnxruntime-libs/')


def run_download(command, *, run=subprocess.run, wait=time.sleep):
    for attempt in range(1, ATTEMPTS + 1):
        result = run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
        print(result.stdout, end='', flush=True)
        if result.returncode == 0:
            return
        wrapper_exhausted = 'org.gradle.wrapper.' in result.stdout and 'Attempt 4/4 failed' in result.stdout
        if (attempt == ATTEMPTS or wrapper_exhausted
                or PERMANENT.search(result.stdout) or not TRANSIENT.search(result.stdout)):
            raise SystemExit(result.returncode)
        delay = 15 * 2 ** (attempt - 1)
        print(f'D66 network download failed; retry {attempt}/{ATTEMPTS - 1} after {delay}s', flush=True)
        wait(delay)


def prepare_native(source):
    # Change only upstream's transport, not its source, compiler flags, archive pin or build/test commands.
    for name in ['build-android-arm64-v8a.sh', 'build-android-x86-64.sh']:
        path = source / name
        text = path.read_text()
        if BOUNDED_WGET in text and WGET not in text:
            continue
        if text.count(WGET) not in {1, 2}:
            raise ValueError(f'Unexpected upstream ORT download commands in {name}')
        path.write_text(text.replace(WGET, BOUNDED_WGET))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['detekt', 'native'])
    parser.add_argument('--source', type=Path)
    args = parser.parse_args()
    if args.stage == 'native':
        if args.source is None:
            parser.error('native requires --source')
        prepare_native(args.source)
    else:
        # Dedicated resolve-only task. The detekt analysis itself runs once in the next CI step.
        run_download(['./gradlew', '--no-daemon', '--configure-on-demand', 'downloadDetekt'])


if __name__ == '__main__':
    main()
