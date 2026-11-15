"""Keeps the CI copy of Gradle's local build cache fresh and small (D-1063).

The Gradle User Home that setup-gradle saves has a key that follows only the build scripts, so main saves it once and
then keeps restoring that copy: its build-cache-1 grows stale, and most tasks ran again on every push. The build job
now saves build-cache-1 on its own, under a new key on each main run. To keep that entry at one build's worth of task
outputs (the repository has 10 GB of caches), main deletes the entries that its build neither read nor wrote.

Gradle does not change the modification time of an entry it reads, but the read changes its access time:
`prepare` sets the access time of every restored entry to the epoch (relatime then updates it on the first read) and
writes a marker file. `prune` deletes the entries that are older than the marker and still have the epoch access
time. When no restored entry was read at all (a noatime mount, or a build that hit nothing), it deletes nothing.
`report` prints the same counts and deletes nothing, for runs that do not save the cache.

Usage: python3 -I scripts/ci-build-cache.py prepare|prune|report [--dir DIR] [--marker FILE]
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

ENTRY = re.compile(r'^[0-9a-f]{32}$')


def default_dir() -> Path:
    home = os.environ.get('GRADLE_USER_HOME') or str(Path.home() / '.gradle')
    return Path(home) / 'caches' / 'build-cache-1'


def default_marker() -> Path:
    return Path(os.environ.get('RUNNER_TEMP') or '/tmp') / 'cassini-build-cache.marker'


def entries(cache: Path) -> list[Path]:
    if not cache.is_dir():
        return []
    return sorted(p for p in cache.iterdir() if ENTRY.match(p.name) and p.is_file())


def prepare(cache: Path, marker: Path) -> int:
    cache.mkdir(parents=True, exist_ok=True)
    restored = entries(cache)
    for path in restored:
        os.utime(path, ns=(0, path.stat().st_mtime_ns))
    marker.parent.mkdir(parents=True, exist_ok=True)
    # The file system stamps the marker with the same clock as the entries the build writes: those are not older.
    marker.unlink(missing_ok=True)
    marker.write_text('', encoding='utf-8')
    print(f'build cache: {len(restored)} restored entries, {mib(restored)} MiB')
    return 0


class Census:
    def __init__(self, restored: list[Path], read: list[Path], written: list[Path]) -> None:
        self.restored, self.read, self.written = restored, read, written

    @property
    def unused(self) -> list[Path]:
        read = set(self.read)
        return [p for p in self.restored if p not in read]


def census(cache: Path, marker: Path) -> Census:
    since = marker.stat().st_mtime_ns
    restored, read, written = [], [], []
    for path in entries(cache):
        stat = path.stat()
        if stat.st_mtime_ns >= since:
            written.append(path)
        else:
            restored.append(path)
            if stat.st_atime_ns > 0:
                read.append(path)
    return Census(restored, read, written)


def mib(paths: list[Path]) -> int:
    return round(sum(p.stat().st_size for p in paths) / 2**20)


def summarize(found: Census) -> str:
    return (
        f'build cache: {len(found.read)} of {len(found.restored)} restored entries read, '
        f'{len(found.written)} written ({mib(found.written)} MiB), {len(found.unused)} unused ({mib(found.unused)} MiB)'
    )


def prune(cache: Path, marker: Path, *, delete: bool) -> int:
    if not marker.is_file():
        print(f'::error::{marker} is missing: run "prepare" before the build', file=sys.stderr)
        return 1
    found = census(cache, marker)
    print(summarize(found))
    if not delete:
        return 0
    if found.restored and not found.read:
        print('::warning::No restored build cache entry was read; keeping them all (noatime mount or no cache hit).')
        return 0
    for path in found.unused:
        path.unlink()
    print(f'build cache: kept {len(found.read) + len(found.written)} entries, {mib(entries(cache))} MiB')
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    parser.add_argument('command', choices=['prepare', 'prune', 'report'])
    parser.add_argument('--dir', type=Path, default=None)
    parser.add_argument('--marker', type=Path, default=None)
    args = parser.parse_args(argv)
    cache = args.dir or default_dir()
    marker = args.marker or default_marker()
    if args.command == 'prepare':
        return prepare(cache, marker)
    return prune(cache, marker, delete=args.command == 'prune')


if __name__ == '__main__':
    sys.exit(main())
