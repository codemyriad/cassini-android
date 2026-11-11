#!/usr/bin/env python3
"""Plan/reserve internal test builds, patch only their workspace version, and record their outcome."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from pathlib import Path

ENVIRONMENT = 'android-play-internal-test'
VERSION = re.compile(r'(\d+\.\d+\.\d+)(?:-beta\.(\d+))?')
SHA = re.compile(r'[0-9a-f]{40}')


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

    def pages(self, path: str):
        separator = '&' if '?' in path else '?'
        page = 1
        while True:
            rows = self.request(f'{path}{separator}per_page=100&page={page}')
            yield from rows
            if len(rows) < 100:
                return
            page += 1

    def green(self, sha: str) -> bool:
        path = f'actions/workflows/android.yml/runs?branch=main&head_sha={sha}&per_page=1'
        runs = self.request(path)['workflow_runs']
        return bool(runs and runs[0]['head_sha'] == sha and runs[0]['status'] == 'completed' and
                    runs[0]['conclusion'] == 'success')

    def manual_baseline(self) -> str | None:
        for release in self.pages('releases'):
            tag = release['tag_name']
            if release['draft'] or not VERSION.fullmatch(tag.removeprefix('v')):
                continue
            sha = command('git', 'rev-parse', f'{tag}^{{commit}}')
            path = f'actions/workflows/android.yml/runs?branch={tag}&head_sha={sha}&per_page=1'
            runs = self.request(path)['workflow_runs']
            if not runs or runs[0]['conclusion'] != 'success':
                continue
            jobs = self.request(f'actions/runs/{runs[0]["id"]}/jobs?per_page=100')['jobs']
            if any(step['name'] == 'Upload to the internal testing track' and step['conclusion'] == 'success'
                   for job in jobs if job['name'] == 'play' for step in job['steps']):
                return sha
        return None


def source_version(text: str) -> tuple[str, int]:
    name = re.search(r'^\s*versionName = "([^"]+)"$', text, re.M)
    code = re.search(r'^\s*versionCode = (\d+)$', text, re.M)
    if not name or not code or not VERSION.fullmatch(name[1]):
        raise ValueError('Unsupported source version')
    return name[1], int(code[1])


def next_version(name: str, code: int, reservations: list[dict]) -> tuple[str, int]:
    match = VERSION.fullmatch(name)
    if match is None:
        raise ValueError('Unsupported source version')
    base, beta = match[1], int(match[2] or 0)
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


def plan(api: GitHub, sha: str, text: str, run_id: int) -> dict:
    if not SHA.fullmatch(sha):
        raise ValueError('Invalid main commit')
    if api.request('commits/main')['sha'] != sha:
        return {'build': False, 'reason': 'main moved before planning'}
    if not api.green(sha):
        return {'build': False, 'reason': 'latest main CI is not green'}
    reservations, baseline = [], None
    for deployment in api.pages(f'deployments?environment={ENVIRONMENT}'):
        payload = deployment['payload']
        if not isinstance(payload, dict) or payload.get('schema') != 1:
            raise ValueError('Invalid test-release ledger')
        reservations.append(payload)
        if baseline is None:
            success = any(status['state'] == 'success' for status in
                          api.pages(f'deployments/{deployment["id"]}/statuses'))
            if not success and payload.get('run_id'):
                # An upload can succeed just before recording its deployment status fails.
                # Reconcile the actual upload step; do not publish the same main again for a lost acknowledgement.
                jobs = api.request(f'actions/runs/{payload["run_id"]}/jobs?per_page=100')['jobs']
                success = uploaded(jobs)
            if success:
                baseline = deployment['sha']
    if baseline != sha:
        manual = api.manual_baseline()
        if manual and (baseline is None or manual == sha or subprocess.run(
            ['git', 'merge-base', '--is-ancestor', baseline, manual], timeout=60, check=False,
        ).returncode == 0):
            baseline = manual
    if baseline == sha:
        return {'build': False, 'reason': 'main is unchanged since the last internal test release'}
    name, code = next_version(*source_version(text), reservations)
    body = notes(api, baseline, sha)
    if api.request('commits/main')['sha'] != sha or not api.green(sha):
        return {'build': False, 'reason': 'main or its CI changed while planning'}
    payload = {'schema': 1, 'version_name': name, 'version_code': code, 'run_id': run_id}
    deployment = api.request('deployments', {'ref': sha, 'environment': ENVIRONMENT, 'auto_merge': False,
                                           'required_contexts': [], 'transient_environment': True,
                                           'production_environment': False, 'payload': payload})
    return {'build': True, 'sha': sha, 'deployment_id': deployment['id'], **payload, 'notes': body}


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
            VERSION.fullmatch(old_name)[1] != VERSION.fullmatch(value['version_name'])[1]):
        raise ValueError('Invalid test version')
    text = re.sub(r'(?m)^(\s*versionCode = )\d+$', lambda m: m[1] + str(value['version_code']), file.read_text())
    text = re.sub(r'(?m)^(\s*versionName = )"[^"]+"$', lambda m: m[1] + json.dumps(value['version_name']), text)
    file.write_text(text)
    directory = root / 'metadata/en-US/changelogs'
    directory.mkdir(parents=True, exist_ok=True)
    body = value['notes']
    # Preserve the full PR titles in the plan artifact; Play permits at most 500 characters.
    (directory / f'{value["version_code"]}.txt').write_text(body if len(body) <= 500 else body[:497] + '...')
    (root / 'test-release-notes.md').write_text(body + '\n')


def finish(api: GitHub, value: dict, success: bool, url: str) -> bool:
    if success:
        jobs = api.request(f'actions/runs/{value["run_id"]}/jobs?per_page=100')['jobs']
        success = uploaded(jobs)
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
    parser.add_argument('mode', choices=['plan', 'apply', 'finish'])
    args = parser.parse_args()
    trusted = os.environ['GITHUB_REF'] == 'refs/heads/main' and \
        os.environ['GITHUB_EVENT_NAME'] in {'schedule', 'workflow_dispatch'}
    if not trusted:
        raise ValueError('Test releases run only from main on schedule or dispatch')
    sha = os.environ['GITHUB_SHA']
    if args.mode == 'plan':
        value = plan(GitHub(), sha, Path('app/build.gradle.kts').read_text(), int(os.environ['GITHUB_RUN_ID']))
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
            output.write(f'build={str(value["build"]).lower()}\nplan={json.dumps(value, separators=(",", ":"))}\n')
        Path('test-release-plan.json').write_text(json.dumps(value, indent=2) + '\n')
        report(f'planned: {value["version_name"]} ({value["version_code"]})' if value['build'] else
               f'skipped: {value["reason"]}')
    else:
        value = json.loads(os.environ['DAILY_TEST_PLAN'])
        if args.mode == 'apply':
            apply(value, sha, Path('.'))
        else:
            success = os.environ['PUBLISH_RESULT'] == 'success'
            success = finish(GitHub(), value, success, os.environ['RUN_URL'])
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
