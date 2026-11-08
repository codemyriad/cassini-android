#!/usr/bin/env python3
"""D66: bounded network-only bootstrap; compilation, analysis and tests are never retried."""

import argparse
import hashlib
import json
import os
import re
import shutil
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ATTEMPTS = 4
def network(operation, *, wait=time.sleep):
    for attempt in range(1, ATTEMPTS + 1):
        try:
            return operation()
        except urllib.error.HTTPError as error:
            if error.code not in {429, 500, 502, 503, 504} or attempt == ATTEMPTS:
                raise
        except (TimeoutError, ConnectionError):
            if attempt == ATTEMPTS:
                raise
        delay = 15 * 2 ** (attempt - 1)
        print(f'D66 API download retry {attempt}/{ATTEMPTS - 1} after {delay}s', flush=True)
        wait(delay)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while block := source.read(65536):
            digest.update(block)
    return digest.hexdigest()


def github_asset(repo, tag, name, target, checksum, *, size=None):
    if target.exists():
        if sha(target) != checksum:
            raise ValueError('Cached API download SHA-256 mismatch')
        return
    metadata_url = f'https://api.github.com/repos/{repo}/releases/tags/{tag}'

    def metadata():
        with urllib.request.urlopen(metadata_url, timeout=60) as response:
            return json.loads(response.read(2 * 1024 * 1024))

    release = network(metadata)
    asset, = [item for item in release['assets'] if item['name'] == name]
    if asset.get('digest') not in {None, 'sha256:' + checksum}:
        raise ValueError('Release metadata differs from the committed SHA-256 pin')
    api_url = f'https://api.github.com/repos/{repo}/releases/assets/{int(asset["id"])}?download=1'
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + f'.{os.getpid()}.part')

    def download():
        request = urllib.request.Request(api_url, headers={'Accept': 'application/octet-stream'})
        count = 0
        with urllib.request.urlopen(request, timeout=60) as response, partial.open('wb') as output:
            while block := response.read(65536):
                count += len(block)
                if count > (size if size is not None else 512 * 1024 * 1024):
                    raise ValueError('API download exceeds pinned size or distribution bound')
                output.write(block)
        if (size is not None and count != size) or sha(partial) != checksum:
            raise ValueError('API download size/SHA-256 mismatch')
        partial.replace(target)

    print(f'D66 fetching pinned {repo}/{tag}/{name} through the official release API', flush=True)
    try:
        network(download)
    finally:
        partial.unlink(missing_ok=True)


def base36(number):
    result = ''
    while number:
        number, digit = divmod(number, 36)
        result = '0123456789abcdefghijklmnopqrstuvwxyz'[digit] + result
    return result or '0'


def gradle_info():
    properties = {}
    for line in (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator:
            properties[key] = value.replace('\\:', ':')
    url = properties['distributionUrl']
    match = re.fullmatch(r'https://(?:services|downloads)\.gradle\.org/distributions/(gradle-([0-9.]+)-bin)\.zip', url)
    if (not match or properties['distributionBase'] != 'GRADLE_USER_HOME'
            or properties['distributionPath'] != 'wrapper/dists'):
        raise ValueError('Unsupported wrapper distribution/cache layout')
    name, version = match.groups()
    checksum = properties.get('distributionSha256Sum', '')
    if not re.fullmatch('[0-9a-f]{64}', checksum):
        raise ValueError('A committed Gradle distribution SHA-256 pin is required')
    # One exact key; no restore-prefix fallback to a different distribution or checksum.
    cache = Path(os.environ.get('CASSINI_CI_DOWNLOAD_CACHE', ROOT / '.tools/ci-download-cache'))
    return dict(name=name, version=version, checksum=checksum, url=url,
                cache_key=f'cassini-gradle-zip-v1-{version}-{checksum}', archive=cache / (name + '.zip'))


def gradle_output():
    info = gradle_info()
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
        for key, value in {'key': info['cache_key'], 'path': info['archive']}.items():
            output.write(f'{key}={value}\n')


def prepare_gradle():
    info = gradle_info()
    archive = info['archive']
    # Verifies restored ZIPs even when setup-gradle also restored an installed wrapper distribution.
    github_asset('gradle/gradle-distributions', 'v' + info['version'], archive.name, archive, info['checksum'])
    home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    # MD5/base36 is Gradle PathAssembler's URL directory key, not an integrity check.
    key = base36(int(hashlib.md5(info['url'].encode(), usedforsecurity=False).hexdigest(), 16))
    target = home / 'wrapper/dists' / info['name'] / key / archive.name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if sha(target) != info['checksum']:
            raise ValueError('Existing wrapper ZIP SHA-256 mismatch')
    else:
        partial = target.with_name(target.name + f'.{os.getpid()}.part')
        try:
            shutil.copyfile(archive, partial)
            if sha(partial) != info['checksum']:
                raise ValueError('Staged wrapper ZIP SHA-256 mismatch')
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    print(f'Verified Gradle ZIP staged in wrapper cache: {info["checksum"]}', flush=True)


def unpack_native(archive, output):
    with zipfile.ZipFile(archive) as source:
        required = {'jni/arm64-v8a/libonnxruntime.so', 'jni/x86_64/libonnxruntime.so', 'headers/onnxruntime_c_api.h'}
        if not required.issubset(source.namelist()):
            raise ValueError('Pinned ORT archive lacks required headers or ABIs')
        for member in source.infolist():
            path = Path(member.filename)
            selected = member.filename.startswith(('headers/', 'jni/arm64-v8a/', 'jni/x86_64/'))
            if not selected or member.is_dir():
                continue
            if path.is_absolute() or '..' in path.parts:
                raise ValueError('Unsafe ORT archive path')
            target = output / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(member))


def input_pin(name):
    return json.loads((ROOT / 'scripts/ci-download-pins.json').read_text())[name]


def prepare_detekt():
    pin = input_pin('kotlinCompiler')
    home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    # Gradle's content-addressed artifact cache. SHA-1 is only its directory key; SHA-256 verifies bytes.
    target = (home / 'caches/modules-2/files-2.1' / pin['group'] / pin['artifact']
              / pin['version'] / pin['cacheSha1'] / pin['name'])
    github_asset(pin['repo'], pin['tag'], pin['name'], target, pin['sha256'], size=pin['size'])


def prepare_native(output):
    pin = input_pin('ort')
    archive = output.parent / pin['name']
    github_asset(pin['repo'], pin['tag'], pin['name'], archive, pin['sha256'], size=pin['size'])
    unpack_native(archive, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['gradle-key', 'gradle', 'detekt', 'native'])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.stage == 'native':
        if args.output is None:
            parser.error('native requires --output')
        prepare_native(args.output)
    elif args.stage == 'gradle-key':
        gradle_output()
    elif args.stage == 'gradle':
        prepare_gradle()
    else:
        prepare_detekt()


if __name__ == '__main__':
    main()
