"""Checks that the CM_RUNNER repository variable can only move jobs to the cm-hetzner runners safely.

A job moves only when CM_RUNNER is exactly "hetzner", the triggering event is in the trusted allow-list (`push`,
`schedule`, `workflow_dispatch`, or a same-repo `pull_request` where `head.repo.full_name == github.repository`), and
never on a tag: tag builds restore the release signing key, which must stay on GitHub-hosted runners, and fork pull
requests must never execute untrusted code on self-hosted runners. The runner labels are a constant; the variable is
never parsed, so any other value (unset, empty, a typo, JSON) keeps ubuntu-24.04 instead of failing the expression or
queueing jobs for labels no runner has. Each selecting runs-on is evaluated here for those values across trusted,
fork, prohibited-event, and tag contexts.
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
from types import SimpleNamespace

WORKFLOWS = Path('.github/workflows')
CM_HETZNER = ['self-hosted', 'cm-hetzner']
EVENT_ALLOWLIST = (
    r"\(github\.event_name == 'push' \|\| github\.event_name == 'schedule' \|\| "
    r"github\.event_name == 'workflow_dispatch' \|\| "
    r"\(github\.event_name == 'pull_request' && "
    r"github\.event\.pull_request\.head\.repo\.full_name == github\.repository\)\)"
)
SELECTOR = re.compile(
    rf"^\$\{{\{{ {EVENT_ALLOWLIST} && !startsWith\(github\.ref, 'refs/tags/'\) && "
    r"vars\.CM_RUNNER == 'hetzner' && "
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
            'hetzner-typo', 'true', 'github']
TRUSTED_CONTEXTS = (
    ('push', 'refs/heads/main', None),
    ('schedule', 'refs/heads/main', None),
    ('workflow_dispatch', 'refs/heads/main', None),
    ('pull_request', 'refs/pull/1/merge', 'codemyriad/cassini-android'),
)
PROHIBITED_EVENTS = (
    'pull_request_target',
    'workflow_run',
    'issue_comment',
    'pull_request_review',
    'pull_request_review_comment',
    'discussion_comment',
    'release',
    'merge_group',
)


class _CIString(str):
    """GitHub Actions string comparison (`==` / `!=`) is case-insensitive."""

    def __eq__(self, other: object) -> bool:
        return isinstance(other, str) and self.casefold() == other.casefold()

    def __ne__(self, other: object) -> bool:
        return not self.__eq__(other)


class _NullContext:
    """GitHub Actions dereferences missing/null context properties to null instead of raising."""

    def __getattr__(self, _name: str) -> _NullContext:
        return self

    def __bool__(self) -> bool:
        return False


_NULL = _NullContext()


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


def select(
    value: str,
    variable: str | None,
    ref: str,
    *,
    event_name: str = 'push',
    head_repo: str | None = 'codemyriad/cassini-android',
    repository: str = 'codemyriad/cassini-android',
) -> object:
    """Evaluate a job's runs-on expression under GitHub Actions event, ref, repo, and vars contexts."""
    if not (value.startswith('${{') and value.endswith('}}')):
        return value
    expr = value.removeprefix('${{').removesuffix('}}').strip()
    expr = expr.replace('&&', ' and ').replace('||', ' or ').replace('!startsWith(', 'not startsWith(')
    repo_obj = _NULL if head_repo is None else SimpleNamespace(full_name=_CIString(head_repo))
    event = SimpleNamespace(pull_request=SimpleNamespace(head=SimpleNamespace(repo=repo_obj)))
    github = SimpleNamespace(
        event_name=_CIString(event_name),
        ref=_CIString(ref),
        repository=_CIString(repository),
        event=event if event_name in ('pull_request', 'pull_request_target') else _NULL,
    )
    vars_ns = SimpleNamespace(CM_RUNNER=_CIString(variable or ''))
    env = {
        'github': github,
        'vars': vars_ns,
        'fromJSON': json.loads,
        'startsWith': lambda text, prefix: str(text).casefold().startswith(str(prefix).casefold()),
    }
    return eval(expr, {'__builtins__': {}}, env)


def selection_problems(where: str, value: str) -> list[str]:
    found = []
    for event_name, ref, head_repo in TRUSTED_CONTEXTS:
        for setting in FALLBACK:
            if select(value, setting, ref, event_name=event_name, head_repo=head_repo) != 'ubuntu-24.04':
                found.append(f'{where}: CM_RUNNER={setting!r} on {event_name} ({ref}) does not fall back')
        for setting in ('hetzner', 'Hetzner'):
            if select(value, setting, ref, event_name=event_name, head_repo=head_repo) != CM_HETZNER:
                found.append(f'{where}: CM_RUNNER={setting!r} on {event_name} ({ref}) does not select cm-hetzner')
    for event_name in ('push', 'workflow_dispatch'):
        for setting in [*FALLBACK, 'hetzner']:
            if select(value, setting, 'refs/tags/v1.2.3', event_name=event_name) != 'ubuntu-24.04':
                found.append(f'{where}: tag {event_name} with CM_RUNNER={setting!r} leaves GitHub-hosted runners')
    for untrusted_head in ('fork/cassini-android', 'codemyriad/other-repo', '', None):
        picked = select(
            value, 'hetzner', 'refs/pull/1/merge', event_name='pull_request', head_repo=untrusted_head,
        )
        if picked != 'ubuntu-24.04':
            found.append(f'{where}: fork pull_request ({untrusted_head!r}) leaves GitHub-hosted runners')
    for prohibited_event in PROHIBITED_EVENTS:
        picked = select(value, 'hetzner', 'refs/heads/main', event_name=prohibited_event)
        if picked != 'ubuntu-24.04':
            found.append(f'{where}: unallowlisted event {prohibited_event!r} leaves GitHub-hosted runners')
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
