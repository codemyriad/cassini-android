"""Checks that the CM_RUNNER repository variable can only move jobs to the cm-hetzner runners safely.

A job moves only when CM_RUNNER is exactly "hetzner", and never on a tag: tag builds restore the release signing
key, which must stay on GitHub-hosted runners. The runner labels are a constant; the variable is never parsed, so
any other value (unset, empty, a typo, JSON) keeps ubuntu-24.04 instead of failing the expression or queueing jobs
for labels no runner has. Each runs-on that reads a variable is evaluated here for those values.
Run from the repository root: python3 -I scripts/check-runner-selection.py
"""

import json
import re
import sys
from pathlib import Path

WORKFLOWS = Path('.github/workflows')
CM_HETZNER = ['self-hosted', 'cm-hetzner']
SELECTOR = re.compile(
    r"^\$\{\{ vars\.CM_RUNNER == 'hetzner' && !startsWith\(github\.ref, 'refs/tags/'\) && "
    r"fromJSON\('(?P<labels>\[\"self-hosted\",\"cm-hetzner\"\])'\) \|\| '(?P<default>ubuntu-24\.04)' \}\}$"
)
# Jobs that may move; every other job keeps a literal GitHub-hosted image.
MOVABLE = {('android.yml', 'checks'), ('android.yml', 'core-only'), ('android.yml', 'build'), ('native.yml', 'native')}
FALLBACK = [None, '', ' ', 'garbage{', '["self-hosted","cm-hetzner"]', '["self-hosted","cm-hetzner-typo"]',
            'hetzner-typo', 'true']


def runs_on_by_job(workflow: Path):
    job = None
    for line in workflow.read_text().splitlines():
        if match := re.match(r'^  ([A-Za-z0-9_-]+):$', line):
            job = match.group(1)
        elif match := re.match(r'^    runs-on: (.+)$', line):
            yield job, match.group(1)


def select(runs_on: str, variable: str | None, ref: str) -> object:
    """What GitHub picks: a model of SELECTOR (`==` ignores case; an unset variable reads as '')."""
    match = SELECTOR.match(runs_on)
    if match is None:
        return runs_on
    on = (variable or '').casefold() == 'hetzner' and not ref.startswith('refs/tags/')
    return json.loads(match['labels']) if on else match['default']


def problems() -> list[str]:
    found, moved = [], set()
    for workflow in sorted(WORKFLOWS.glob('*.yml')):
        for job, runs_on in runs_on_by_job(workflow):
            where = f'{workflow.name}:{job}'
            if 'vars.' not in runs_on:
                continue
            moved.add((workflow.name, job))
            if not SELECTOR.match(runs_on):
                found.append(f'{where}: runs-on reads a variable but is not the allowlisted selector: {runs_on}')
                continue
            for ref in ('refs/heads/main', 'refs/pull/1/merge'):
                for value in FALLBACK:
                    if select(runs_on, value, ref) != 'ubuntu-24.04':
                        found.append(f'{where}: CM_RUNNER={value!r} on {ref} does not fall back to ubuntu-24.04')
                for value in ('hetzner', 'Hetzner'):
                    if select(runs_on, value, ref) != CM_HETZNER:
                        found.append(f'{where}: CM_RUNNER={value!r} on {ref} does not select cm-hetzner')
            for value in [*FALLBACK, 'hetzner']:
                if select(runs_on, value, 'refs/tags/v1.2.3') != 'ubuntu-24.04':
                    found.append(f'{where}: a tag build with CM_RUNNER={value!r} leaves GitHub-hosted runners')
    if moved != MOVABLE:
        found.append(f'jobs reading CM_RUNNER are {sorted(moved)}, expected {sorted(MOVABLE)}')
    # Every secret in a movable job is only read on tags, where the job always runs GitHub-hosted.
    for workflow_name, job in sorted(MOVABLE):
        text = (WORKFLOWS / workflow_name).read_text()
        block = re.search(rf'^  {re.escape(job)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)', text, re.M | re.S)
        for step in re.split(r'\n(?=      - )', block.group(1) if block else ''):
            if 'secrets.' in step and "if: startsWith(github.ref, 'refs/tags/')" not in step:
                found.append(f'{workflow_name}:{job}: a step reads secrets outside tag builds')
    return found


if __name__ == '__main__':
    issues = problems()
    for issue in issues:
        print(issue, file=sys.stderr)
    sys.exit(1 if issues else 0)
