#!/usr/bin/env python3
"""Plan/reserve internal test builds, patch only their workspace version, and record their outcome."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

# -I omits the script directory; import only this checkout's trusted helper.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_notes  # noqa: E402
from play_changelog import format_play_notes  # noqa: E402

ENVIRONMENT = 'android-play-internal-test'
VERSION = re.compile(r'(\d+\.\d+\.\d+)(?:-beta\.(\d+))?')
SHA = re.compile(r'[0-9a-f]{40}')
CI_WAIT_SECONDS = 45 * 60
CI_POLL_SECONDS = 30


def command(*args: str) -> str:
    return subprocess.check_output(args, text=True, timeout=60).strip()


class GitHub:
    def __init__(self):
        self.repo = os.environ['GITHUB_REPOSITORY']
        if not re.fullmatch(r'[\w.-]+/[\w.-]+', self.repo):
            raise ValueError('Invalid repository')

    def request(self, path: str, data: dict | None = None):
        args = ['gh', 'api', '--method', 'POST' if data is not None else 'GET', f'repos/{self.repo}/{path}']
        if data is not None:
            args += ['--input', '-']
        result = subprocess.run(args, input=json.dumps(data) if data is not None else None,
                                capture_output=True, text=True, timeout=60, check=True)
        return json.loads(result.stdout)

    def pages(self, path: str, key: str | None = None):
        separator = '&' if '?' in path else '?'
        page = 1
        while True:
            response = self.request(f'{path}{separator}per_page=100&page={page}')
            rows = response[key] if key else response
            yield from rows
            if len(rows) < 100:
                return
            page += 1

    def runs(self, branch: str, sha: str, event: str | None = None):
        path = f'actions/workflows/android.yml/runs?branch={branch}&head_sha={sha}'
        if event:
            path += f'&event={event}'
        for run in self.pages(path, 'workflow_runs'):
            repository = (run.get('head_repository') or {}).get('full_name')
            allowed = {event} if event else {'push', 'workflow_dispatch', 'schedule'}
            if (repository == self.repo and run.get('event') in allowed and
                    run.get('head_sha') == sha and run.get('head_branch') == branch):
                yield run

    def ci_state(self, sha: str) -> str:
        run = next(self.runs('main', sha), None)
        if not run:
            return 'red'
        if run['status'] != 'completed':
            return 'pending'
        return 'green' if run['conclusion'] == 'success' else 'red'

    def green(self, sha: str) -> bool:
        return self.ci_state(sha) == 'green'

    def manual_baseline(self) -> tuple[str, int] | None:
        for release in self.pages('releases'):
            tag = release['tag_name']
            if release['draft'] or not VERSION.fullmatch(tag.removeprefix('v')):
                continue
            sha = command('git', 'rev-parse', f'{tag}^{{commit}}')
            run = next(self.runs(tag, sha, 'push'), None)
            if not run or run['conclusion'] != 'success':
                continue
            jobs = self.request(f'actions/runs/{run["id"]}/jobs?per_page=100')['jobs']
            if any(step['name'] == 'Upload to the internal testing track' and step['conclusion'] == 'success'
                   for job in jobs if job['name'] == 'play' for step in job['steps']):
                return sha, source_version(command('git', 'show', f'{tag}:app/build.gradle.kts'))[1]
        return None


def source_version(text: str) -> tuple[str, int]:
    name = re.search(r'^\s*versionName = "([^"]+)"$', text, re.M)
    code = re.search(r'^\s*versionCode = (\d+)$', text, re.M)
    if not name or not code or not VERSION.fullmatch(name[1]):
        raise ValueError('Unsupported source version')
    return name[1], int(code[1])


def beta_base(name: str, released: bool = False) -> str:
    match = VERSION.fullmatch(name)
    base = match[1]
    if match[2] is None or released:
        prefix, patch = base.rsplit('.', 1)
        return f'{prefix}.{int(patch) + 1}'
    return base


def next_version(name: str, code: int, reservations: list[dict], released: bool = False) -> tuple[str, int]:
    match = VERSION.fullmatch(name)
    if match is None:
        raise ValueError('Unsupported source version')
    base = beta_base(name, released)
    beta = int(match[2] or 0) if base == match[1] else 0
    for item in reservations:
        prior_name = item.get('version_name')
        prior = VERSION.fullmatch(prior_name) if isinstance(prior_name, str) else None
        prior_code = item.get('version_code')
        if prior is None or type(prior_code) is not int or not 0 < prior_code <= 2_100_000_000:
            raise ValueError('Invalid release reservation')
        code = max(code, item['version_code'])
        if prior[1] == base:
            beta = max(beta, int(prior[2] or 0))
    if code >= 2_100_000_000:
        raise ValueError('Android versionCode exhausted')
    return f'{base}-beta.{beta + 1}', code + 1


def notes(api: GitHub, baseline: str | None, sha: str) -> str:
    if baseline:
        subprocess.run(['git', 'merge-base', '--is-ancestor', baseline, sha], check=True, timeout=60)
    commits = command('git', 'log', '--first-parent', '--reverse', '--format=%H',
                      f'{baseline}..{sha}' if baseline else sha).splitlines()
    commit_set = set(commits)
    merged = {}
    for commit in commits:
        for pull in api.pages(f'commits/{commit}/pulls'):
            if pull['merged_at'] and pull['base']['ref'] == 'main' and pull['merge_commit_sha'] in commit_set:
                merged[pull['number']] = ' '.join(pull['title'].split())
    result = '\n'.join(f'- {title} (#{number})' for number, title in merged.items())
    result = result or 'Changes on main since the previous internal test build.'
    if len(result.encode('utf-8')) > 60_000:
        raise ValueError('Release notes exceed the plan limit')
    return result


def uploaded(jobs: list[dict]) -> bool:
    return any(step["name"] == "Upload to the internal testing track" and step["conclusion"] == "success"
               for job in jobs if job["name"] == "play" or job["name"].endswith(" / play") for step in job["steps"])


def run_uploaded(api: GitHub, run_id: int) -> bool:
    try:
        return uploaded(api.request(f'actions/runs/{run_id}/jobs?per_page=100')['jobs'])
    except subprocess.CalledProcessError as error:
        if '(HTTP 404)' not in (error.stderr or ''):
            raise
        return False


def user_notes(baseline: str | None) -> str:
    return release_notes.render(release_notes.fragments(Path(__file__).resolve().parents[1], baseline))


def wait_for_green(api: GitHub, sha: str, *, deadline: float | None = None,
                   clock=time.monotonic, sleep=time.sleep) -> bool:
    end = (clock() + CI_WAIT_SECONDS) if deadline is None else deadline
    while True:
        state = api.ci_state(sha) if hasattr(api, 'ci_state') else ('green' if api.green(sha) else 'red')
        if state == 'green':
            return True
        if state != 'pending':
            return False
        remaining = end - clock()
        if remaining <= 0:
            return False
        sleep(min(CI_POLL_SECONDS, remaining))


def plan(api: GitHub, sha: str, text: str, run_id: int, *,
         clock=time.monotonic, sleep=time.sleep) -> dict:
    if not SHA.fullmatch(sha):
        raise ValueError('Invalid main commit')
    if api.request('commits/main')['sha'] != sha:
        return {'build': False, 'reason': 'main moved before planning'}
    deadline = clock() + CI_WAIT_SECONDS
    if not wait_for_green(api, sha, deadline=deadline, clock=clock, sleep=sleep):
        return {'build': False, 'reason': 'latest main CI is not green'}
    source_name, code_floor = source_version(text)
    reservations, baseline = [], None
    for deployment in api.pages(f'deployments?environment={ENVIRONMENT}'):
        payload = deployment['payload']
        if not isinstance(payload, dict) or payload.get('schema') != 1:
            raise ValueError('Invalid test-release ledger')
        reservations.append(payload)
        if baseline is None:
            states = {status['state'] for status in api.pages(f'deployments/{deployment["id"]}/statuses')}
            success = 'success' in states
            if not states.intersection({'success', 'failure'}) and payload.get('run_id'):
                success = run_uploaded(api, payload['run_id'])
            if success:
                baseline = deployment['sha']
    if baseline != sha:
        manual = api.manual_baseline()
        if manual:
            manual_sha, manual_code = manual
            code_floor = max(code_floor, manual_code)
            common = manual_sha if manual_sha == sha else command('git', 'merge-base', manual_sha, sha)
            if baseline is None or common == sha or common == baseline or subprocess.run(
                ['git', 'merge-base', '--is-ancestor', baseline, common], timeout=60, check=False,
            ).returncode == 0:
                baseline = common
    if baseline == sha:
        return {'build': False, 'reason': 'main is unchanged since the last internal test release'}
    base = VERSION.fullmatch(source_name)[1]
    released = bool(command('git', 'tag', '--list', f'v{base}'))
    name, code = next_version(source_name, code_floor, reservations, released)
    developer = notes(api, baseline, sha)
    body = user_notes(baseline)
    release_notes.release_body(body, developer)  # Validate both destinations before reserving a build.
    if not wait_for_green(api, sha, deadline=deadline, clock=clock, sleep=sleep):
        return {'build': False, 'reason': 'main CI changed while planning'}
    payload = {'schema': 1, 'version_name': name, 'version_code': code, 'run_id': run_id}
    deployment = api.request('deployments', {'ref': sha, 'environment': ENVIRONMENT, 'auto_merge': False,
                                           'required_contexts': [], 'transient_environment': True,
                                           'production_environment': False, 'payload': payload})
    return {'build': True, 'sha': sha, 'deployment_id': deployment['id'], **payload,
            'stable_released': released, 'notes': body, 'developer_notes': developer}


def plan_metadata(value: dict) -> dict:
    metadata = {key: item for key, item in value.items() if key not in {'notes', 'developer_notes'}}
    for key in ('notes', 'developer_notes'):
        if key in value:
            metadata[key + '_sha256'] = hashlib.sha256(value[key].encode('utf-8')).hexdigest()
    return metadata


def load_plan(metadata: dict, root: Path) -> dict:
    value = json.loads((root / '.tools/daily-test-release/test-release-plan.json').read_text())
    if plan_metadata(value) != metadata:
        raise ValueError('Release plan artifact does not match its reserved metadata')
    return value


def apply(value: dict, sha: str, root: Path):
    if value.get('build') is not True or value['sha'] != sha or not SHA.fullmatch(sha):
        raise ValueError('Test build is not bound to this main commit')
    file = root / 'app/build.gradle.kts'
    old_name, old_code = source_version(file.read_text())
    if not isinstance(value.get('notes'), str) or len(value['notes'].encode('utf-8')) > 60_000:
        raise ValueError('Invalid release notes')
    if (not isinstance(value.get('version_name'), str) or not VERSION.fullmatch(value['version_name']) or
            type(value['version_code']) is not int or
            not old_code < value['version_code'] <= 2_100_000_000 or
            VERSION.fullmatch(value['version_name'])[1] != beta_base(old_name, value.get('stable_released', False))):
        raise ValueError('Invalid test version')
    body = release_notes.store_notes(value['notes'])
    developer = value.get('developer_notes', '')
    if not isinstance(developer, str) or len(developer.encode('utf-8')) > 60_000:
        raise ValueError('Invalid developer changelog')
    text = re.sub(r'(?m)^(\s*versionCode = )\d+$', lambda m: m[1] + str(value['version_code']), file.read_text())
    text = re.sub(r'(?m)^(\s*versionName = )"[^"]+"$', lambda m: m[1] + json.dumps(value['version_name']), text)
    file.write_text(text)
    directory = root / 'metadata/en-US/changelogs'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f'{value["version_code"]}.txt').write_text(format_play_notes(body))
    (root / 'test-release-notes.md').write_text(release_notes.release_body(body, developer))


def finish(api: GitHub, value: dict, _workflow_success: bool, url: str) -> bool:
    success = run_uploaded(api, value['run_id'])
    api.request(f'deployments/{value["deployment_id"]}/statuses',
                {'state': 'success' if success else 'failure', 'log_url': url, 'auto_inactive': False})
    return success


def report(message: str, failed: bool = False):
    line = f'[Android] daily test release {message}'
    escaped = line.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
    print(('::error::' if failed else '::notice::') + escaped)
    with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as output:
        output.write(line + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'apply', 'body', 'finish'])
    args = parser.parse_args()
    trusted = os.environ['GITHUB_REF'] == 'refs/heads/main' and \
        os.environ['GITHUB_EVENT_NAME'] in {'schedule', 'workflow_dispatch'}
    if not trusted:
        raise ValueError('Test releases run only from main on schedule or dispatch')
    sha = os.environ['GITHUB_SHA']
    if args.mode == 'plan':
        value = plan(GitHub(), sha, Path('app/build.gradle.kts').read_text(), int(os.environ['GITHUB_RUN_ID']))
        metadata = json.dumps(plan_metadata(value), separators=(',', ':'))
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
            output.write(f'build={str(value["build"]).lower()}\nplan={metadata}\n')
        Path('test-release-plan.json').write_text(json.dumps(value, indent=2) + '\n')
        report(f'planned: {value["version_name"]} ({value["version_code"]})' if value['build'] else
               f'skipped: {value["reason"]}')
    else:
        value = json.loads(os.environ['DAILY_TEST_PLAN'])
        if args.mode in {'apply', 'body'}:
            full = load_plan(value, Path('.'))
            if args.mode == 'apply':
                apply(full, sha, Path('.'))
            else:
                if full['sha'] != sha or not SHA.fullmatch(sha):
                    raise ValueError('Release notes are not bound to this main commit')
                delivered = run_uploaded(GitHub(), full['run_id'])
                with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
                    output.write(f'publish={str(delivered).lower()}\n')
                if delivered:
                    Path('test-release-notes.md').write_text(
                        release_notes.release_body(full['notes'], full.get('developer_notes', '')))
        else:
            workflow_success = os.environ['PUBLISH_RESULT'] == 'success'
            success = finish(GitHub(), value, workflow_success, os.environ['RUN_URL'])
            if success and not workflow_success:
                report(f'failed: publishing workflow ended {os.environ["PUBLISH_RESULT"]} after upload succeeded', True)
                raise SystemExit(1)
            report(f'published: {value["version_name"]} ({value["version_code"]})' if success else
                   f'failed: build, device checks or internal upload failed ({os.environ["RUN_URL"]})', not success)
            if not success:
                raise SystemExit(1)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        detail = (error.stderr or str(error)) if isinstance(error, subprocess.CalledProcessError) else str(error)
        report(f'failed: {type(error).__name__}: {detail.strip().splitlines()[0][:300]}', True)
        raise SystemExit(1) from error
