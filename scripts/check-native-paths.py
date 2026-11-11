"""Checks that native.yml's path triggers list exactly the scripts of the native build.

The native build starts at scripts/setup.sh plus any scripts/ helpers invoked directly by native.yml or
its local composite actions (.github/actions/*). Every file of scripts/ that one of those scripts names
belongs to it, and so on. A change to any of them must run native.yml, and a script that no longer
belongs to it must not.
Run from the repository root: python3 -I scripts/check-native-paths.py
"""

import re
import sys
from pathlib import Path

WORKFLOW = Path('.github/workflows/native.yml')
NAME = re.compile(r'[A-Za-z0-9_.-]+\.(?:sh|json|patch|py)\b')
LOCAL_ACTION = re.compile(r'^\s*uses:\s+\./(\.github/actions/[A-Za-z0-9_.-]+)\s*(?:#.*)?$', re.MULTILINE)


def workflow_script_seeds(workflow: Path) -> list[Path]:
    text = workflow.read_text(encoding='utf-8')
    non_comment = '\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('#'))
    sources = [non_comment]
    for action_dir in LOCAL_ACTION.findall(non_comment):
        action_file = Path(action_dir) / 'action.yml'
        if action_file.is_file():
            sources.append(action_file.read_text(encoding='utf-8'))
    seeds = [Path('scripts/setup.sh')]
    for body in sources:
        for name in NAME.findall(body):
            candidate = Path('scripts') / name
            if candidate.is_file() and name != Path(__file__).name and candidate not in seeds:
                seeds.append(candidate)
    return seeds


def closure(starts: list[Path]) -> set[str]:
    found, todo = set(), list(starts)
    while todo:
        path = todo.pop()
        if path.name in found:
            continue
        found.add(path.name)
        if path.suffix in ('.sh', '.py'):
            for name in NAME.findall(path.read_text(encoding='utf-8')):
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
    native = closure(workflow_script_seeds(WORKFLOW))
    lists = triggers(WORKFLOW.read_text(encoding='utf-8'))
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
