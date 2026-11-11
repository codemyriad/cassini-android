"""Offline release planning, source binding and partial-publication failure checks; never upload a build."""

import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('daily', ROOT / 'scripts/daily-test-release.py')
DAILY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DAILY)
SHA, OLD = 'a' * 40, 'b' * 40
SOURCE = 'android {\n    versionCode = 18\n    versionName = "0.4.2-beta.3"\n}\n'


class API:
    def __init__(self, main=SHA, green=True, baseline=OLD, records=(), states=None, jobs=()):
        self.main, self.is_green, self.baseline = main, green, baseline
        self.records, self.states, self.jobs = list(records), states or {}, list(jobs)
        self.posts = []
        self.main_reads = 0

    def request(self, path, data=None):
        if data is not None:
            self.posts.append((path, data))
            return {'id': 99}
        if path == 'commits/main':
            self.main_reads += 1
            return {'sha': self.main}
        if path.startswith('actions/runs/'):
            return {'jobs': self.jobs}
        raise AssertionError(path)

    def pages(self, path):
        if path.startswith('deployments?'):
            return iter(self.records)
        return iter(self.states.get(path, []))

    def green(self, sha):
        return self.is_green

    def manual_baseline(self):
        return self.baseline


def reservation(sha=OLD, code=19, beta=4, run_id=20):
    return {'id': code, 'sha': sha, 'payload': {
        'schema': 1, 'version_name': f'0.4.2-beta.{beta}', 'version_code': code, 'run_id': run_id,
    }}


def upload_job(conclusion):
    return {'name': 'publish / play', 'steps': [
        {'name': 'Upload to the internal testing track', 'conclusion': conclusion},
    ]}


class DailyReleaseTests(unittest.TestCase):
    def test_red_pending_or_moved_main_never_reserves_or_publishes(self):
        for api in (API(green=False), API(main=OLD)):
            with self.subTest(api=api):
                self.assertFalse(DAILY.plan(api, SHA, SOURCE, 42)['build'])
                self.assertEqual([], api.posts)

    def test_unchanged_main_skips_successful_daily_and_manual_releases(self):
        daily = API(records=[reservation(SHA)], states={'deployments/19/statuses': [{'state': 'success'}]})
        for api in (daily, API(baseline=SHA)):
            with self.subTest(api=api):
                self.assertFalse(DAILY.plan(api, SHA, SOURCE, 42)['build'])
                self.assertEqual([], api.posts)

    def test_a_newer_manual_test_release_also_makes_main_unchanged(self):
        api = API(baseline=SHA, records=[reservation()],
                  states={'deployments/19/statuses': [{'state': 'success'}]})
        with patch.object(DAILY, 'notes', return_value='- Fixture change'):
            self.assertFalse(DAILY.plan(api, SHA, SOURCE, 42)['build'])
        self.assertEqual([], api.posts)

    def test_failed_reservations_consume_numbers_without_marking_main_released(self):
        api = API(records=[reservation()])
        with patch.object(DAILY, 'notes', return_value='- A tested fix (#7)'):
            value = DAILY.plan(api, SHA, SOURCE, 42)
        self.assertTrue(value['build'])
        self.assertEqual(('0.4.2-beta.5', 20), (value['version_name'], value['version_code']))
        self.assertEqual((SHA, 99, 42), (value['sha'], value['deployment_id'], value['run_id']))
        path, body = api.posts[0]
        self.assertEqual('deployments', path)
        self.assertEqual(SHA, body['ref'])
        self.assertFalse(body['production_environment'])
        self.assertFalse(body['auto_merge'])
        self.assertEqual([], body['required_contexts'])

    def test_upload_success_is_reconciled_if_deployment_acknowledgement_was_lost(self):
        api = API(records=[reservation(SHA)], jobs=[upload_job('success')])
        with patch.object(DAILY, 'notes', return_value='- Fixture change'):
            self.assertFalse(DAILY.plan(api, SHA, SOURCE, 42)['build'])
        self.assertEqual([], api.posts)
        self.assertFalse(DAILY.uploaded([upload_job('skipped')]))
        self.assertFalse(DAILY.uploaded([upload_job('failure')]))

    def test_ci_or_main_changing_during_notes_collection_skips_before_reservation(self):
        for move in (True, False):
            api = API()
            def change(*_, move=move, api=api):
                if move:
                    api.main = OLD
                else:
                    api.is_green = False
                return '- New change'
            with self.subTest(move=move), patch.object(DAILY, 'notes', side_effect=change):
                self.assertFalse(DAILY.plan(api, SHA, SOURCE, 42)['build'])
                self.assertEqual([], api.posts)

    def test_version_changes_reset_beta_but_never_reset_android_version_code(self):
        self.assertEqual(('0.5.0-beta.1', 43), DAILY.next_version('0.5.0', 20, [
            {'version_name': '0.4.2-beta.27', 'version_code': 42},
        ]))
        self.assertEqual(('0.4.2-beta.4', 19), DAILY.next_version('0.4.2-beta.3', 18, []))
        with self.assertRaises(ValueError):
            DAILY.next_version('0.4.2', 2_100_000_000, [])

    def test_damaged_ledger_numbers_fail_closed(self):
        for name, code in ((None, 19), ('0.4.2-beta.4', True), ('0.4.2-beta.4', -1)):
            with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                DAILY.next_version('0.4.2-beta.3', 18, [{'version_name': name, 'version_code': code}])

    def test_apply_changes_only_workspace_version_and_keeps_complete_notes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app').mkdir()
            build = root / 'app/build.gradle.kts'
            build.write_text(SOURCE)
            notes = '- ' + 'A' * 600 + ' (#7)'
            value = {'build': True, 'sha': SHA, 'version_name': '0.4.2-beta.4', 'version_code': 19, 'notes': notes}
            DAILY.apply(value, SHA, root)
            self.assertEqual(('0.4.2-beta.4', 19), DAILY.source_version(build.read_text()))
            self.assertEqual(500, len((root / 'metadata/en-US/changelogs/19.txt').read_text()))
            self.assertEqual(notes + '\n', (root / 'test-release-notes.md').read_text())
            for change in ({'sha': OLD}, {'version_code': 18}, {'version_code': True},
                           {'version_name': '0.5.0-beta.1'}, {'notes': None}):
                build.write_text(SOURCE)
                with self.subTest(change=change), self.assertRaises(ValueError):
                    DAILY.apply({**value, **change}, SHA, root)
                self.assertEqual(SOURCE, build.read_text())

    def test_arbitrary_pr_titles_are_not_sent_through_job_outputs(self):
        value = {'build': True, 'sha': SHA, 'notes': 'A title containing a signing-key alias such as cassini'}
        metadata = DAILY.plan_metadata(value)
        self.assertNotIn('notes', metadata)
        self.assertNotIn('cassini', json.dumps(metadata))
        self.assertEqual(SHA, metadata['sha'])

    def test_plan_artifact_is_bound_to_metadata_and_complete_notes(self):
        value = {'build': True, 'sha': SHA, 'version_name': '0.4.2-beta.4', 'version_code': 19, 'notes': '- Title (#7)'}
        metadata = DAILY.plan_metadata(value)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / '.tools/daily-test-release/test-release-plan.json'
            file.parent.mkdir(parents=True)
            file.write_text(json.dumps(value))
            self.assertEqual(value, DAILY.load_plan(metadata, root))
            for change in ({'sha': OLD}, {'version_code': 20}, {'notes': '- Changed title'}):
                file.write_text(json.dumps({**value, **change}))
                with self.subTest(change=change), self.assertRaises(ValueError):
                    DAILY.load_plan(metadata, root)

    def test_notes_only_include_merged_main_prs_in_the_commit_range_and_deduplicate(self):
        def pull(number, title, commit, merged=True, base='main'):
            return {'number': number, 'title': title, 'merge_commit_sha': commit,
                    'merged_at': 'date' if merged else None, 'base': {'ref': base}}
        api = API()
        api.pages = lambda _: iter([
            pull(7, 'Fix `quoted` title\nwith whitespace', SHA),
            pull(8, 'Outside range', OLD), pull(9, 'Open', SHA, False), pull(10, 'Other branch', SHA, base='next'),
        ])
        with patch.object(DAILY, 'command', return_value=SHA + '\n' + 'c' * 40), patch.object(DAILY.subprocess, 'run'):
            self.assertEqual('- Fix `quoted` title with whitespace (#7)', DAILY.notes(api, OLD, SHA))

    def test_only_a_successful_publish_records_success(self):
        for success, expected in ((True, 'success'), (False, 'failure')):
            api = API(jobs=[upload_job('success')])
            DAILY.finish(api, {'deployment_id': 99, 'run_id': 42}, success, 'https://github.com/run')
            self.assertEqual(expected, api.posts[0][1]['state'])

    def test_a_successful_workflow_with_a_skipped_upload_is_not_a_test_release(self):
        api = API(jobs=[upload_job('skipped')])
        self.assertFalse(DAILY.finish(api, {'deployment_id': 99, 'run_id': 42}, True, 'https://github.com/run'))
        self.assertEqual('failure', api.posts[0][1]['state'])

    def test_failure_line_is_escaped_and_written_to_the_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = directory + '/summary'
            with patch.dict(os.environ, {'GITHUB_STEP_SUMMARY': summary}), patch('builtins.print') as output:
                DAILY.report('failed: first\nsecond%', True)
            expected = '::error::[Android] daily test release failed: first%0Asecond%25'
            self.assertEqual(expected, output.call_args.args[0])
            self.assertIn('[Android] daily test release failed:', Path(summary).read_text())

    def test_schedule_keeps_all_three_berlin_slots_through_daylight_saving(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        workflow = (ROOT / '.github/workflows/daily-test-release.yml').read_text()
        self.assertIn("cron: '0 9,14,20 * * *'", workflow)
        self.assertIn('timezone: Europe/Berlin', workflow)
        for month, utc_hours in ((1, [8, 13, 19]), (7, [7, 12, 18])):
            for index, hour in enumerate((9, 14, 20)):
                expected = utc_hours[index]
                instant = datetime(2026, month, 15, hour, tzinfo=ZoneInfo('Europe/Berlin'))
                self.assertEqual(expected, instant.astimezone(ZoneInfo('UTC')).hour)

    def test_dispatch_from_a_feature_branch_cannot_start_a_release(self):
        with patch.dict(os.environ, {'GITHUB_REF': 'refs/heads/feature', 'GITHUB_EVENT_NAME': 'workflow_dispatch'}):
            with patch('sys.argv', ['daily-test-release.py', 'plan']), self.assertRaises(ValueError):
                DAILY.main()

    def test_latest_ci_must_be_finished_green_for_the_exact_main_sha(self):
        api = object.__new__(DAILY.GitHub)
        for status, conclusion, sha in [('completed', 'success', SHA), ('in_progress', None, SHA),
                                         ('completed', 'failure', SHA), ('completed', 'success', OLD)]:
            api.request = lambda _, s=status, c=conclusion, h=sha: {'workflow_runs': [
                {'status': s, 'conclusion': c, 'head_sha': h},
            ]}
            self.assertEqual(status == 'completed' and conclusion == 'success' and sha == SHA, api.green(SHA))


if __name__ == '__main__':
    unittest.main()
