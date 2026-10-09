#!/usr/bin/env python3
"""Collect the pinned native dependency sources, with their upstream build files."""
import hashlib
import json
import subprocess
import tarfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

manifest = Path('scripts/native-dependencies.json')
cache = Path('.tools/native-sources')
cache.mkdir(parents=True, exist_ok=True)

def fetch(entry):
    suffix = '.zip' if entry['url'].endswith('.zip') else '.tar.gz'
    path = cache / (entry['name'] + suffix)
    expected = entry['sha256']
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        temporary = path.with_suffix(path.suffix + '.part')
        subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--silent', '--show-error',
                        entry['url'], '-o', str(temporary)], check=True)
        actual = hashlib.sha256(temporary.read_bytes()).hexdigest()
        if expected and actual != expected:
            raise ValueError(f"Source hash mismatch for {entry['name']}")
        temporary.replace(path)
    print(f"Verified source: {entry['name']}", flush=True)
    return path

if __name__ == '__main__':
    # The prebuilt AAR entry pins a binary, not a source archive.
    entries = [entry for entry in json.loads(manifest.read_text()) if not entry['url'].endswith('.aar')]
    paths = list(ThreadPoolExecutor(max_workers=4).map(fetch, entries))
    destination = Path('dist')
    destination.mkdir(exist_ok=True)
    archive = destination / 'cassini-native-sources.tar'
    with tarfile.open(archive, 'w') as output:
        # Files keep their repository paths, so the packaged scripts find the manifest and the
        # patch where they look for them.
        build_files = [manifest, Path('scripts/streaming-diarization.patch'),
                       Path('scripts/build-native-aar.sh'), Path('scripts/fetch-opus-source.sh'),
                       Path('scripts/native-common.sh'), Path('app/build.gradle.kts'),
                       *sorted(Path('app/src/main/cpp').glob('*')),
                       Path('docs/native-runtime.md'), Path('THIRD_PARTY_NOTICES.md')]
        for path in build_files:
            output.add(path, arcname=str(path))
        for path in paths:
            output.add(path, arcname='sources/' + path.name)
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix('.tar.sha256').write_text(f'{checksum}  {archive.name}\n')
