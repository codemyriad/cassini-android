#!/usr/bin/env python3
"""Numeric memory evidence around Gradle; never read command lines, environment or JVM dumps."""
import argparse
import json
import subprocess
import sys
from pathlib import Path

HOST_KEYS = {'MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'}
EVENT_KEYS = {'low', 'high', 'max', 'oom', 'oom_kill', 'oom_group_kill', 'under_oom', 'oom_kill_disable'}


def text(path):
    try:
        with path.open(encoding='utf-8', errors='replace') as stream:
            return stream.read(65536)
    except (OSError, UnicodeError):
        return ''


def number(path):
    value = text(path).strip()
    if value == 'max':
        return value
    try:
        return int(value)
    except ValueError:
        return None


def pairs(value, allowed):
    result = {}
    for line in value.splitlines():
        parts = line.replace(':', ' ').split()
        if len(parts) >= 2 and parts[0] in allowed:
            try:
                result[parts[0]] = int(parts[1])
            except ValueError:
                continue
    return result


def memory_group(proc, base):
    for line in text(proc / 'self/cgroup').splitlines():
        parts = line.split(':', 2)
        if len(parts) != 3:
            continue
        version = 2 if parts[0] == '0' and not parts[1] else 1
        if version == 1 and 'memory' not in parts[1].split(','):
            continue
        root = base if version == 2 else base / 'memory'
        # A namespaced mount may hide the host-relative path. Never walk outside its mount.
        candidate = root if '..' in Path(parts[2]).parts else root / parts[2].lstrip('/')
        marker = 'memory.events' if version == 2 else 'memory.limit_in_bytes'
        while True:
            try:
                with (candidate / marker).open(encoding='utf-8', errors='replace') as stream:
                    stream.read(1)
                return version, candidate
            except (OSError, UnicodeError):
                pass
            if candidate == root:
                break
            candidate = candidate.parent
    return None, None


def process_totals(value):
    totals = {'javaCount': 0, 'javaRssKiB': 0, 'processCount': 0, 'rssKiB': 0}
    if value is None:
        return dict.fromkeys(totals)
    for line in value.splitlines():
        parts = line.rsplit(None, 1)
        if len(parts) != 2 or not parts[1].isdigit():
            continue
        rss = int(parts[1])
        totals['processCount'] += 1
        totals['rssKiB'] += rss
        if Path(parts[0]).name == 'java':
            totals['javaCount'] += 1
            totals['javaRssKiB'] += rss
    return totals


def snapshot(proc, cgroups, processes):
    version, group = memory_group(proc, cgroups)
    memory = {'version': version}
    if group is not None:
        fields = ['memory.current', 'memory.max', 'memory.peak', 'memory.swap.current', 'memory.swap.max']
        if version == 1:
            fields = ['memory.usage_in_bytes', 'memory.limit_in_bytes', 'memory.max_usage_in_bytes', 'memory.failcnt']
        memory.update({name: number(group / name) for name in fields})
        events = 'memory.events' if version == 2 else 'memory.oom_control'
        memory['events'] = pairs(text(group / events), EVENT_KEYS)
    return {
        'hostKiB': pairs(text(proc / 'meminfo'), HOST_KEYS),
        'cgroup': memory,
        'processTotals': process_totals(processes),
    }


def main(argv=None, *, proc=Path('/proc'), cgroups=Path('/sys/fs/cgroup'), output=Path('build/reports/resources')):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['before', 'after'])
    args = parser.parse_args(argv)
    try:
        if args.phase == 'before':
            (output / 'after.json').unlink(missing_ok=True)
    except (OSError, UnicodeError):
        print('Warning: stale memory evidence could not be removed; build result is unchanged.', file=sys.stderr)
        return 0
    try:
        result = subprocess.run(
            ['ps', '-eo', 'comm=,rss='], check=False, capture_output=True, text=True,
            encoding='utf-8', errors='replace', timeout=10,
        )
        processes = result.stdout if result.returncode == 0 else None
    except (OSError, subprocess.SubprocessError, UnicodeError):
        processes = None
    value = snapshot(proc, cgroups, processes)
    try:
        output.mkdir(parents=True, exist_ok=True)
        (output / f'{args.phase}.json').write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    except (OSError, UnicodeError):
        print('Warning: memory evidence could not be written; build result is unchanged.', file=sys.stderr)
    return 0


if __name__ == '__main__':
    main()
