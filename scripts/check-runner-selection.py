"""Checks that the CM_RUNNER repository variable can only move jobs to the cm-hetzner runners safely.

A job moves only when CM_RUNNER is exactly "hetzner", and never on a tag: tag builds restore the release signing
key, which must stay on GitHub-hosted runners. The runner labels are a constant; the variable is never parsed, so
any other value (unset, empty, a typo, JSON) keeps ubuntu-24.04 instead of failing the expression or queueing jobs
for labels no runner has. Each selecting runs-on is evaluated here for those values on branch, PR and tag refs.
Every other job runs on a literal GitHub-hosted image. In the jobs that can move, secrets are read only by steps
that run on tags alone, and caches are saved only by GitHub-hosted runs: a tag build restores them while it holds
the signing key, so nothing written on a self-hosted machine may reach it.
Only the Android build on GitHub-hosted main writes the Gradle home; other jobs and refs only restore it.
Run from the repository root: python3 -I scripts/check-runner-selection.py
"""

from __future__ import annotations

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
HOSTED_IMAGE = re.compile(r'^(?:ubuntu|macos|windows)-[0-9a-z.-]+$')
TAG_ONLY = "if: startsWith(github.ref, 'refs/tags/')"
GITHUB_HOSTED = "runner.environment == 'github-hosted'"
MAIN_GRADLE_CACHE = (
    "cache-read-only: ${{ github.ref != 'refs/heads/main' || runner.environment != 'github-hosted' }}"
)
# The jobs that may move. Every other job (emulator, release, play, models, record-goldens) stays GitHub-hosted.
MOVABLE = {('android.yml', 'checks'), ('android.yml', 'core-only'), ('android.yml', 'build'), ('native.yml', 'native')}
FALLBACK = [None, '', ' ', 'garbage{', '["self-hosted","cm-hetzner"]', '["self-hosted","cm-hetzner-typo"]',
            'hetzner-typo', 'true']


def jobs(workflow: Path):
    """(job id, job text) for each job of a workflow."""
    body = workflow.read_text().split('\njobs:\n', 1)[1]
    for block in re.split(r'\n(?=  [A-Za-z0-9_-]+:\n)', '\n' + body):
        if match := re.match(r'\s*([A-Za-z0-9_-]+):\n', block):
            yield match.group(1), block


def runs_on(job_text: str) -> str:
    """The runs-on value, inline or as a block (a YAML list or mapping on the following, deeper lines)."""
    lines = job_text.splitlines()
    for i, line in enumerate(lines):
        if match := re.match(r'^    runs-on:(.*)$', line):
            value = [match.group(1).strip()]
            for following in lines[i + 1:]:
                if not following.startswith('      '):
                    break
                value.append(following.strip())
            return '\n'.join(v for v in value if v)
    return ''


def without_comments(text: str) -> str:
    return '\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('#'))


def steps(job_text: str) -> list[str]:
    return re.split(r'\n(?=      - )', without_comments(job_text))[1:]


def select(value: str, variable: str | None, ref: str) -> object:
    """What GitHub picks: a model of SELECTOR (`==` ignores case; an unset variable reads as '')."""
    match = SELECTOR.match(value)
    if match is None:
        return value
    on = (variable or '').casefold() == 'hetzner' and not ref.startswith('refs/tags/')
    return json.loads(match['labels']) if on else match['default']


def selection_problems(where: str, value: str) -> list[str]:
    found = []
    for ref in ('refs/heads/main', 'refs/pull/1/merge'):
        for setting in FALLBACK:
            if select(value, setting, ref) != 'ubuntu-24.04':
                found.append(f'{where}: CM_RUNNER={setting!r} on {ref} does not fall back to ubuntu-24.04')
        for setting in ('hetzner', 'Hetzner'):
            if select(value, setting, ref) != CM_HETZNER:
                found.append(f'{where}: CM_RUNNER={setting!r} on {ref} does not select cm-hetzner')
    for setting in [*FALLBACK, 'hetzner']:
        if select(value, setting, 'refs/tags/v1.2.3') != 'ubuntu-24.04':
            found.append(f'{where}: a tag build with CM_RUNNER={setting!r} leaves GitHub-hosted runners')
    return found


def movable_job_problems(where: str, job_text: str) -> list[str]:
    found = []
    head = without_comments(job_text).split('\n    steps:\n', 1)[0]
    if re.search(r'\bsecrets\b', head):
        found.append(f'{where}: the job reads secrets outside its steps')
    for step in steps(job_text):
        name = re.search(r'name: (.+)|uses: (\S+)', step)
        label = f"{where} step {(name.group(1) or name.group(2)) if name else '?'}"
        conditions = re.findall(r'^        if: .+$', step, re.M)
        if re.search(r'\bsecrets\b', step):
            if [c.strip() for c in conditions] != [TAG_ONLY] or re.search(r'toJSON\(\s*secrets|secrets\[', step):
                found.append(f'{label}: reads secrets but is not limited to tag builds ({TAG_ONLY})')
        if re.search(r'uses: actions/cache(?:/save)?@', step) and not any(GITHUB_HOSTED in c for c in conditions):
            found.append(f"{label}: saves a cache without `{GITHUB_HOSTED}` in its if")
        if 'uses: gradle/actions/setup-gradle@' in step:
            read_only = {line.strip() for line in step.splitlines() if 'cache-read-only:' in line}
            expected = {MAIN_GRADLE_CACHE} if where == 'android.yml:build' else {'cache-read-only: true'}
            if read_only != expected:
                found.append(f'{label}: only the GitHub-hosted main build may write the Gradle home')
    return found


def problems() -> list[str]:
    found, moved = [], set()
    for workflow in sorted(WORKFLOWS.glob('*.yml')):
        for job, text in jobs(workflow):
            where, value = f'{workflow.name}:{job}', runs_on(text)
            if not value:
                continue          # a job that calls a reusable workflow
            if (workflow.name, job) not in MOVABLE:
                if not HOSTED_IMAGE.match(value):
                    found.append(f'{where}: must run on a literal GitHub-hosted image, not {value!r}')
                continue
            moved.add((workflow.name, job))
            if not SELECTOR.match(value):
                found.append(f'{where}: runs-on is not the allowlisted CM_RUNNER selector: {value!r}')
                continue
            found += selection_problems(where, value)
            found += movable_job_problems(where, text)
    if moved != MOVABLE:
        found.append(f'jobs that can move are {sorted(moved)}, expected {sorted(MOVABLE)}')
    return found


if __name__ == '__main__':
    issues = problems()
    for issue in issues:
        print(issue, file=sys.stderr)
    sys.exit(1 if issues else 0)
