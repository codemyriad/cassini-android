#!/usr/bin/env python3
"""Summarize downloaded Android job timestamps and Gradle profiles without mixing cache states or runner types."""

import argparse
import json
import re
from collections import defaultdict
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path
from statistics import median


class Tables(HTMLParser):
    def __init__(self):
        super().__init__()
        self.tables = []
        self.table = None
        self.row = None
        self.cell = None

    def handle_starttag(self, tag, attrs):
        if tag == 'table':
            self.table = []
        elif tag == 'tr':
            self.row = []
        elif tag in ('td', 'th'):
            self.cell = ''

    def handle_data(self, data):
        if self.cell is not None:
            self.cell += data

    def handle_endtag(self, tag):
        if tag in ('td', 'th') and self.cell is not None:
            self.row.append(self.cell.strip())
            self.cell = None
        elif tag == 'tr' and self.row is not None:
            self.table.append(self.row)
            self.row = None
        elif tag == 'table' and self.table is not None:
            self.tables.append(self.table)
            self.table = None


def seconds(value):
    parts = re.fullmatch(r'(?:(\d+)h)?(?:(\d+)m)?(\d+(?:\.\d+)?)s', value)
    if not parts:
        raise ValueError(f'Unknown Gradle duration: {value!r}')
    hours, minutes, secs = parts.groups()
    return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(secs)


def profile(source):
    parser = Tables()
    parser.feed(source)
    tasks = [table for table in parser.tables if table and table[0] == ['Task', 'Duration', 'Result']]
    if len(tasks) != 1:
        raise ValueError('Expected one Gradle Task Execution table')
    if any(row[-1] == 'FAILED' for row in tasks[0][1:]):
        raise ValueError('Failed task in a successful-build profile')
    # Module totals overlap. Preserve only individual tasks and never sum task times into wall time.
    return [{'task': name, 'seconds': seconds(duration), 'state': state or 'EXECUTED'}
            for name, duration, state in tasks[0][1:] if state != '(total)']


def runner(job):
    labels = job.get('labels', [])
    if 'self-hosted' in labels:
        return 'self-hosted'
    if job.get('runner_group_name') == 'GitHub Actions':
        return 'github-hosted'
    return 'unknown'


def stats(values):
    return {'samples': len(values), 'median_seconds': round(median(values), 3),
            'min_seconds': round(min(values), 3), 'max_seconds': round(max(values), 3)}


def build_inputs(directory):
    payload = json.loads((directory / 'jobs.json').read_text(encoding='utf-8'))
    jobs = payload['jobs']
    if payload.get('total_count', len(jobs)) != len(jobs):
        raise ValueError('Incomplete jobs response: collect every page')
    builds = [job for job in jobs if job['name'] == 'build']
    if len(builds) != 1 or builds[0]['conclusion'] != 'success':
        raise ValueError('Expected exactly one successful build job from one attempt')
    build = builds[0]
    metadata = directory / 'run.json'
    if metadata.exists():
        run = json.loads(metadata.read_text(encoding='utf-8'))
        if (run['id'], run['run_attempt'], run['conclusion']) != (build['run_id'], build['run_attempt'], 'success'):
            raise ValueError('Run metadata does not match the successful build attempt')
    artifact = directory / 'artifact.json'
    if artifact.exists():
        value = json.loads(artifact.read_text(encoding='utf-8'))
        if (value['name'] != 'android-checks' or value['expired'] or value['workflow_run']['id'] != build['run_id'] or
                not build['started_at'] <= value['created_at'] <= build['completed_at']):
            raise ValueError('Artifact metadata does not match the build attempt')
        if metadata.exists() and value['workflow_run']['head_sha'] != run['head_sha']:
            raise ValueError('Artifact head does not match the run')
    return jobs, build, artifact.exists()


def summarize(directories):
    steps = defaultdict(list)
    tasks = defaultdict(list)
    runs = []
    seen = set()
    for directory in directories:
        jobs, build, checked = build_inputs(directory)
        identity = build.get('run_id', directory.resolve())
        if identity in seen:
            raise ValueError('Duplicate run: use one successful attempt per run')
        seen.add(identity)
        host = runner(build)
        profiles = sorted(directory.glob('checks/build/reports/profile/*.html'))
        if not profiles:
            raise ValueError(f'No Gradle profiles in {directory}')
        runs.append({'run': str(build.get('run_id', directory.name)), 'attempt': build.get('run_attempt'),
                     'build_runner': host, 'profiles': len(profiles), 'artifact_metadata_checked': checked})
        for job in jobs:
            if job['conclusion'] != 'success':
                continue
            for step in job['steps']:
                if step['conclusion'] != 'success':
                    continue
                start = datetime.fromisoformat(step['started_at'].replace('Z', '+00:00'))
                end = datetime.fromisoformat(step['completed_at'].replace('Z', '+00:00'))
                elapsed = (end - start).total_seconds()
                if elapsed < 0:
                    raise ValueError('Step ends before it starts')
                steps[runner(job), job['name'], step['name']].append(elapsed)
        for path in profiles:
            for task in profile(path.read_text(encoding='utf-8')):
                tasks[host, task['task'], task['state']].append(task['seconds'])
    ranked = [{'runner': host, 'job': job, 'step': step, **stats(values)}
              for (host, job, step), values in steps.items()]
    hosts = sorted({row['runner'] for row in ranked})
    top = {host: sorted((row for row in ranked if row['runner'] == host),
                        key=lambda row: row['median_seconds'], reverse=True)[:10] for host in hosts}
    detail = [{'runner': host, 'task': task, 'state': state, **stats(values)}
              for (host, task, state), values in sorted(tasks.items())]
    return {'runs': runs, 'ten_slowest_steps_by_runner': top, 'tasks_by_runner_and_cache_state': detail,
            'note': 'Queue time excluded; task times overlap and must not be summed as build wall time.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', nargs='+', type=Path,
                        help='Run directories containing jobs.json and checks/build/reports/profile')
    args = parser.parse_args()
    print(json.dumps(summarize(args.runs), indent=2))


if __name__ == '__main__':
    main()
