"""CI diagnostics keep host/cache groups distinct and reject incomplete profile data."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/profile-ci-reports.py'
SPEC = importlib.util.spec_from_file_location('ci_profile', SCRIPT)
PROFILE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROFILE)


def html(state='', duration='2m3.25s'):
    return f'''<table><tr><th>Dependency</th><th>Duration</th></tr>
<tr><td>:ui:testDebugUnitTest</td><td>999s</td></tr></table>
<table><tr><th>Task</th><th>Duration</th><th>Result</th></tr>
<tr><td>:ui</td><td>10m0s</td><td>(total)</td></tr>
<tr><td>:ui:testDebugUnitTest</td><td>{duration}</td><td>{state}</td></tr>
<tr><td>:ui:verifyRoborazziDebug</td><td>0s</td><td>Did No Work</td></tr></table>'''


def fixture(root, name, *, cached=False, hosted=True):
    directory = root / name
    reports = directory / 'checks/build/reports/profile'
    reports.mkdir(parents=True)
    (reports / 'profile.html').write_text(html('FROM-CACHE' if cached else '', '0.1s' if cached else '2m3.25s'))
    job = {'name': 'build', 'conclusion': 'success', 'runner_name': 'private-runner-name',
           'labels': ['ubuntu-24.04'] if hosted else ['self-hosted'],
           'runner_group_name': 'GitHub Actions' if hosted else 'Default',
           'steps': [{'name': f'Step {i}', 'conclusion': 'success',
                      'started_at': '2026-11-11T01:00:00Z',
                      'completed_at': f'2026-11-11T01:00:{i + 1:02d}Z'} for i in range(12)]}
    job['steps'].append({'name': 'Skipped', 'conclusion': 'skipped'})
    (directory / 'jobs.json').write_text(json.dumps({'jobs': [job]}))
    return directory


class ProfileReportsTest(unittest.TestCase):
    def test_duration_units_and_invalid_input(self):
        self.assertEqual(3723.25, PROFILE.seconds('1h2m3.25s'))
        self.assertEqual(0, PROFILE.seconds('0s'))
        self.assertEqual('unknown', PROFILE.runner({'labels': []}))
        for value in ['NaN', '-1s', '3', '2m', '1s extra']:
            with self.assertRaises(ValueError):
                PROFILE.seconds(value)

    def test_only_task_table_is_parsed_without_module_totals_or_summed_wall_time(self):
        tasks = PROFILE.profile(html())
        self.assertEqual(2, len(tasks))
        self.assertEqual(123.25, tasks[0]['seconds'])
        self.assertEqual('EXECUTED', tasks[0]['state'])
        self.assertEqual('Did No Work', tasks[1]['state'])
        with self.assertRaises(ValueError):
            PROFILE.profile('<table><tr><th>Missing task table</th></tr></table>')

    def test_cached_and_executed_samples_and_runner_types_stay_separate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [fixture(root, '1'), fixture(root, '2', cached=True), fixture(root, '3', hosted=False)]
            result = PROFILE.summarize(runs)
            task = [row for row in result['tasks_by_runner_and_cache_state']
                    if row['task'].endswith('testDebugUnitTest')]
            self.assertEqual(3, len(task))
            self.assertEqual([1, 1, 1], [row['samples'] for row in task])
            self.assertEqual([123.25, 0.1, 123.25], [row['median_seconds'] for row in task])
            top = result['ten_slowest_steps_by_runner']['github-hosted']
            self.assertEqual(10, len(top))
            self.assertEqual('Step 11', top[0]['step'])
            self.assertEqual(2, top[0]['samples'])
            self.assertNotIn('private-runner-name', json.dumps(result))
            self.assertNotIn('Skipped', json.dumps(result))

    def test_failed_or_partial_build_inputs_and_duplicate_runs_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = fixture(Path(temporary), '42')
            jobs_file = directory / 'jobs.json'
            payload = json.loads(jobs_file.read_text())
            with self.assertRaisesRegex(ValueError, 'Duplicate run'):
                PROFILE.summarize([directory, directory])
            for conclusion in ['failure', 'cancelled', 'in_progress']:
                payload['jobs'][0]['conclusion'] = conclusion
                jobs_file.write_text(json.dumps(payload))
                with self.assertRaisesRegex(ValueError, 'successful build'):
                    PROFILE.summarize([directory])
            payload['jobs'][0]['conclusion'] = 'success'
            payload['total_count'] = 2
            jobs_file.write_text(json.dumps(payload))
            with self.assertRaisesRegex(ValueError, 'every page'):
                PROFILE.summarize([directory])
            payload['jobs'].append(payload['jobs'][0])
            jobs_file.write_text(json.dumps(payload))
            with self.assertRaisesRegex(ValueError, 'exactly one'):
                PROFILE.summarize([directory])
        with self.assertRaisesRegex(ValueError, 'Failed task'):
            PROFILE.profile(html('FAILED'))

    def test_artifact_and_attempt_metadata_must_belong_to_the_selected_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = fixture(Path(temporary), '42')
            jobs_file = directory / 'jobs.json'
            payload = json.loads(jobs_file.read_text())
            payload['jobs'][0].update(run_id=42, run_attempt=2, started_at='2026-11-11T01:00:00Z',
                                      completed_at='2026-11-11T01:10:00Z')
            jobs_file.write_text(json.dumps(payload))
            run = {'id': 42, 'run_attempt': 2, 'conclusion': 'success', 'head_sha': 'test-head'}
            artifact = {'name': 'android-checks', 'expired': False, 'created_at': '2026-11-11T01:09:00Z',
                        'workflow_run': {'id': 42, 'head_sha': 'test-head'}}
            run_file = directory / 'run.json'
            artifact_file = directory / 'artifact.json'
            run_file.write_text(json.dumps(run))
            artifact_file.write_text(json.dumps(artifact))
            result = PROFILE.summarize([directory])
            self.assertTrue(result['runs'][0]['artifact_metadata_checked'])
            self.assertEqual(2, result['runs'][0]['attempt'])
            for key, value in [('run_attempt', 1), ('conclusion', 'failure')]:
                run_file.write_text(json.dumps({**run, key: value}))
                with self.assertRaisesRegex(ValueError, 'Run metadata'):
                    PROFILE.summarize([directory])
            run_file.write_text(json.dumps(run))
            for changed in [{**artifact, 'expired': True}, {**artifact, 'name': 'unrelated'},
                            {**artifact, 'created_at': '2026-11-11T00:59:59Z'},
                            {**artifact, 'workflow_run': {'id': 99, 'head_sha': 'test-head'}},
                            {**artifact, 'workflow_run': {'id': 42, 'head_sha': 'other-head'}}]:
                artifact_file.write_text(json.dumps(changed))
                with self.assertRaisesRegex(ValueError, 'Artifact'):
                    PROFILE.summarize([directory])

    def test_main_reads_downloaded_layout_and_missing_profiles_fail_loudly(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = fixture(Path(temporary), '42')
            result = subprocess.run([sys.executable, '-I', str(SCRIPT), str(directory)],
                                    capture_output=True, text=True, check=True)
            self.assertEqual('42', json.loads(result.stdout)['runs'][0]['run'])
            next(directory.glob('checks/build/reports/profile/*.html')).unlink()
            with self.assertRaises(ValueError):
                PROFILE.summarize([directory])
