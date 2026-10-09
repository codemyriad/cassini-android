#!/usr/bin/env python3
"""Collect the pinned native dependency sources, with their upstream build files."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile

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
    entries = json.loads(manifest.read_text())
    paths = list(ThreadPoolExecutor(max_workers=4).map(fetch, entries))
    destination = Path('dist')
    destination.mkdir(exist_ok=True)
    archive = destination / 'cassini-native-sources.tar'
    with tarfile.open(archive, 'w') as output:
        output.add(Path('scripts/streaming-diarization.patch'), arcname='streaming-diarization.patch')
        for path in [Path("scripts/build-opus-jni.sh"), *Path("app/src/main/cpp").glob("*")]:
            output.add(path, arcname=str(path))
        for path in paths:
            output.add(path, arcname='sources/' + path.name)
        for path in [manifest, Path('docs/native-runtime.md'), Path('THIRD_PARTY_NOTICES.md')]:
            output.add(path, arcname=path.name)
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix('.tar.sha256').write_text(f'{checksum}  {archive.name}\n')
