"""Checks that native.yml's path triggers list exactly the scripts of the native build.

The native build starts at scripts/setup.sh. Every file of scripts/ that one of its scripts names belongs to it, and
so on. A change to any of them must run native.yml, and a script that no longer belongs to it must not.
Run from the repository root: python3 -I scripts/check-native-paths.py
"""

import re
import sys
from pathlib import Path

WORKFLOW = Path('.github/workflows/native.yml')
NAME = re.compile(r'[A-Za-z0-9_.-]+\.(?:sh|json|patch|py)\b')


def closure(start: Path) -> set[str]:
    found, todo = set(), [start]
    while todo:
        path = todo.pop()
        if path.name in found:
            continue
        found.add(path.name)
        if path.suffix in ('.sh', '.py'):
            for name in NAME.findall(path.read_text()):
                candidate = Path('scripts') / name
                # The comments of the native scripts name this check; it is not part of the build.
                if candidate.is_file() and name not in found and name != Path(__file__).name:
                    todo.append(candidate)
    return found


def triggers(text: str) -> dict[str, set[str]]:
    """The scripts/ entries of each event's paths list (push, pull_request)."""
    lists, event, in_paths = {}, None, False
    for line in text.splitlines():
        if match := re.fullmatch(r'  (push|pull_request):', line):
            event, in_paths = match.group(1), False
            lists[event] = set()
        elif event and line == '    paths:':
            in_paths = True
        elif in_paths and (match := re.fullmatch(r"      - '([^']+)'", line)):
            if match.group(1).startswith('scripts/'):
                lists[event].add(match.group(1).removeprefix('scripts/'))
        elif in_paths:
            event, in_paths = None, False
    return lists


def main() -> int:
    native = closure(Path('scripts/setup.sh'))
    lists = triggers(WORKFLOW.read_text())
    failed = False
    for event in ('push', 'pull_request'):
        listed = lists.get(event, set())
        for name in sorted(native - listed):
            print(f'{WORKFLOW} {event}.paths: add scripts/{name} (part of the native build)', file=sys.stderr)
            failed = True
        for name in sorted(listed - native):
            print(f'{WORKFLOW} {event}.paths: remove scripts/{name} (not part of the native build)', file=sys.stderr)
            failed = True
    if not failed:
        print(f'native.yml triggers on the {len(native)} scripts of the native build')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
