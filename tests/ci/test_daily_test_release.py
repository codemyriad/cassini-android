"""Offline release planning, source binding and partial-publication failure checks; never upload a build."""

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('daily', ROOT / 'scripts/daily-test-release.py')
DAILY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DAILY)
SHA, OLD = 'a' * 40, 'b' * 40
SOURCE = 'android {\n    versionCode = 18\n    versionName = "0.4.2-beta.3"\n}\n'


class API:
    def __init__(self, main=SHA, green=True, baseline=OLD, baseline_code=18, records=(), states=None, jobs=()):
        self.main, self.is_green, self.baseline = main, green, baseline
        self.baseline_code = baseline_code
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
        return (self.baseline, self.baseline_code) if self.baseline else None


def reservation(sha=OLD, code=19, beta=4, run_id=20):
    return {'id': code, 'sha': sha, 'payload': {
        'schema': 1, 'version_name': f'0.4.2-beta.{beta}', 'version_code': code, 'run_id': run_id,
    }}


def upload_job(conclusion):
    return {'name': 'publish / play', 'steps': [
        {'name': 'Upload to the internal testing track', 'conclusion': conclusion},
    ]}


def workflow_run(status='completed', conclusion='success', sha=SHA, repo='codemyriad/cassini-android',
                 event='push', branch='main', identifier=1):
    return {'id': identifier, 'status': status, 'conclusion': conclusion, 'head_sha': sha,
            'head_branch': branch, 'head_repository': {'full_name': repo}, 'event': event}


class DailyReleaseTests(unittest.TestCase):
    def setUp(self):
        def git(*args):
            if args[:3] == ('git', 'tag', '--list'):
                return ''
            if args[:2] == ('git', 'merge-base'):
                return args[2]
            raise AssertionError(args)
        git_patch = patch.object(DAILY, 'command', side_effect=git)
        self.git = git_patch.start()
        self.addCleanup(git_patch.stop)
        user_patch = patch.object(DAILY, 'user_notes', return_value='Notes are easier to export.')
        user_patch.start()
        self.addCleanup(user_patch.stop)

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

    def test_expanded_github_body_is_checked_before_reserving_a_build(self):
        api = API()
        with patch.object(DAILY, 'notes', return_value='<' * 20_000), self.assertRaises(ValueError):
            DAILY.plan(api, SHA, SOURCE, 42)
        self.assertEqual([], api.posts)

    def test_plan_separates_user_copy_from_developer_titles(self):
        with patch.object(DAILY, 'notes', return_value='- Refactor CI for D95 (#123)'):
            value = DAILY.plan(API(), SHA, SOURCE, 42)
        self.assertEqual('Notes are easier to export.', value['notes'])
        self.assertEqual('- Refactor CI for D95 (#123)', value['developer_notes'])

    def test_fragment_baseline_advances_only_after_successful_play_publication(self):
        manual = 'c' * 40
        for state, expected in (('failure', manual), ('success', OLD)):
            api = API(baseline=manual, records=[reservation()],
                      states={'deployments/19/statuses': [{'state': state}]})
            with self.subTest(state=state), patch.object(DAILY, 'notes', return_value='Developer text'), \
                    patch.object(DAILY, 'user_notes', return_value='Your notes keep their names.') as user, \
                    patch.object(DAILY.subprocess, 'run', return_value=Mock(returncode=1)):
                self.assertTrue(DAILY.plan(api, SHA, SOURCE, 42)['build'])
                user.assert_called_once_with(expected)

    def test_version_changes_reset_beta_but_never_reset_android_version_code(self):
        self.assertEqual(('0.5.1-beta.1', 43), DAILY.next_version('0.5.0', 20, [
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
            notes = 'Notes are easier to export.'
            developer = '- ' + 'A' * 600 + ' (#7)'
            value = {'build': True, 'sha': SHA, 'version_name': '0.4.2-beta.4', 'version_code': 19,
                     'notes': notes, 'developer_notes': developer}
            DAILY.apply(value, SHA, root)
            self.assertEqual(('0.4.2-beta.4', 19), DAILY.source_version(build.read_text()))
            self.assertEqual(notes, (root / 'metadata/en-US/changelogs/19.txt').read_text())
            self.assertEqual(DAILY.release_notes.release_body(notes, developer),
                             (root / 'test-release-notes.md').read_text())
            for change in ({'sha': OLD}, {'version_code': 18}, {'version_code': True},
                           {'version_name': '0.5.0-beta.1'}, {'notes': None},
                           {'notes': 'Fix CI (#123)'}, {'notes': '😀' * 251}):
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

    def test_an_uploaded_bundle_records_success_even_if_a_post_step_failed(self):
        for workflow_success in (True, False):
            api = API(jobs=[upload_job('success')])
            self.assertTrue(DAILY.finish(api, {'deployment_id': 99, 'run_id': 42}, workflow_success, 'https://github.com/run'))
            self.assertEqual('success', api.posts[0][1]['state'])

    def test_post_upload_job_failure_is_visible_without_forgetting_delivery(self):
        value = {'deployment_id': 99, 'run_id': 42, 'version_name': '0.4.2-beta.4', 'version_code': 19}
        api = API(jobs=[upload_job('success')])
        with tempfile.TemporaryDirectory() as directory, patch.object(DAILY, 'GitHub', return_value=api), \
                patch('sys.argv', ['daily-test-release.py', 'finish']), patch('builtins.print'), \
                patch.dict(os.environ, {
                    'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_SHA': SHA,
                    'DAILY_TEST_PLAN': json.dumps(value), 'PUBLISH_RESULT': 'failure', 'RUN_URL': 'https://github.com/run',
                    'GITHUB_STEP_SUMMARY': directory + '/summary',
                }):
            with self.assertRaises(SystemExit) as raised:
                DAILY.main()
            self.assertEqual(1, raised.exception.code)
            self.assertEqual('success', api.posts[0][1]['state'])
            self.assertIn('daily test release failed:', Path(directory + '/summary').read_text())

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

    def test_daily_full_history_and_outcome_checkouts_pin_the_reserved_sha(self):
        workflow = (ROOT / '.github/workflows/daily-test-release.yml').read_text()
        self.assertEqual(3, workflow.count('ref: ${{ github.sha }}'))
        android = (ROOT / '.github/workflows/android.yml').read_text().split('  core-only:', 1)[0]
        self.assertIn('ref: ${{ github.sha }}', android)

    def test_dispatch_from_a_feature_branch_cannot_start_a_release(self):
        with patch.dict(os.environ, {'GITHUB_REF': 'refs/heads/feature', 'GITHUB_EVENT_NAME': 'workflow_dispatch'}):
            with patch('sys.argv', ['daily-test-release.py', 'plan']), self.assertRaises(ValueError):
                DAILY.main()

    def test_latest_ci_must_be_finished_green_for_the_exact_main_sha(self):
        api = object.__new__(DAILY.GitHub)
        api.repo = 'codemyriad/cassini-android'
        for status, conclusion, sha in [('completed', 'success', SHA), ('in_progress', None, SHA),
                                         ('completed', 'failure', SHA), ('completed', 'success', OLD)]:
            api.request = lambda _, s=status, c=conclusion, h=sha: {'workflow_runs': [
                workflow_run(status=s, conclusion=c, sha=h),
            ]}
            self.assertEqual(status == 'completed' and conclusion == 'success' and sha == SHA, api.green(SHA))

    def test_pr_runs_at_the_same_sha_cannot_hide_red_main_or_block_green_main(self):
        api = object.__new__(DAILY.GitHub)
        api.repo = 'codemyriad/cassini-android'
        for trusted_green in (True, False):
            for repository in (api.repo, 'attacker/cassini-android'):
                api.request = Mock(return_value={'workflow_runs': [
                    workflow_run(repo=repository, event='pull_request',
                                 conclusion='failure' if trusted_green else 'success'),
                    workflow_run(conclusion='success' if trusted_green else 'failure'),
                ]})
                self.assertEqual(trusted_green, api.green(SHA))
        api.request = Mock(return_value={'workflow_runs': [
            workflow_run(event='workflow_dispatch', status='in_progress', conclusion=None), workflow_run(),
        ]})
        self.assertFalse(api.green(SHA))  # Never fall back to an older green trusted run.

    def test_latest_nightly_blocks_or_clears_an_older_push_for_the_same_main_sha(self):
        api = object.__new__(DAILY.GitHub)
        api.repo = 'codemyriad/cassini-android'
        for status, conclusion, expected in [('completed', 'success', True), ('completed', 'failure', False),
                                             ('in_progress', None, False), ('completed', 'cancelled', False)]:
            with self.subTest(status=status, conclusion=conclusion):
                api.request = Mock(return_value={'workflow_runs': [
                    workflow_run(event='schedule', status=status, conclusion=conclusion),
                    workflow_run(conclusion='failure' if expected else 'success'),
                ]})
                self.assertEqual(expected, api.green(SHA))
                self.assertIn('branch=main&head_sha=' + SHA, api.request.call_args.args[0])

    def test_scheduled_runs_require_same_repository_sha_and_main_branch(self):
        api = object.__new__(DAILY.GitHub)
        api.repo = 'codemyriad/cassini-android'
        for trusted_green in (True, False):
            for changed in ({'repo': 'attacker/cassini-android'}, {'sha': OLD}, {'branch': 'feature'}):
                with self.subTest(trusted_green=trusted_green, changed=changed):
                    api.request = Mock(return_value={'workflow_runs': [
                        workflow_run(event='schedule', conclusion='failure' if trusted_green else 'success', **changed),
                        workflow_run(conclusion='success' if trusted_green else 'failure'),
                    ]})
                    self.assertEqual(trusted_green, api.green(SHA))

    def test_foreign_push_at_the_same_sha_cannot_hide_red_main(self):
        api = object.__new__(DAILY.GitHub)
        api.repo = 'codemyriad/cassini-android'
        api.request = Mock(return_value={'workflow_runs': [
            workflow_run(repo='attacker/cassini-android'), workflow_run(conclusion='failure'),
        ]})
        self.assertFalse(api.green(SHA))

    def test_ci_lookup_paginates_past_fork_and_pr_runs(self):
        api = object.__new__(DAILY.GitHub)
        api.repo = 'codemyriad/cassini-android'
        for conclusion in ('success', 'failure'):
            api.request = Mock(side_effect=[
                {'workflow_runs': [workflow_run(repo='attacker/cassini-android', event='pull_request')] * 100},
                {'workflow_runs': [workflow_run(conclusion=conclusion)]},
            ])
            self.assertEqual(conclusion == 'success', api.green(SHA))
            self.assertEqual(2, api.request.call_count)
            self.assertIn('page=2', api.request.call_args.args[0])

    def test_manual_baseline_only_accepts_this_repositories_push_run(self):
        api = object.__new__(DAILY.GitHub)
        api.repo = 'codemyriad/cassini-android'
        tag = 'v0.4.2-beta.3'
        def request(path, data=None):
            if path.startswith('releases?'):
                return [{'tag_name': tag, 'draft': False}]
            if path.startswith('actions/workflows/'):
                self.assertIn('event=push', path)
                return {'workflow_runs': [
                    workflow_run(branch=tag, event='pull_request', identifier=1),
                    workflow_run(branch=tag, event='schedule', identifier=4),
                    workflow_run(branch=tag, repo='attacker/cassini-android', identifier=2),
                    workflow_run(branch=tag, identifier=3),
                ]}
            self.assertEqual('actions/runs/3/jobs?per_page=100', path)
            return {'jobs': [{'name': 'play', 'steps': upload_job('success')['steps']}]}
        api.request = request
        with patch.object(DAILY, 'command', side_effect=lambda *args: SOURCE if args[1] == 'show' else SHA):
            self.assertEqual((SHA, 18), api.manual_baseline())

    def test_failed_acknowledgements_do_not_read_expired_workflow_runs(self):
        api = API(records=[reservation()], states={'deployments/19/statuses': [{'state': 'failure'}]})
        original = api.request
        def request(path, data=None):
            self.assertFalse(path.startswith('actions/runs/'))
            return original(path, data)
        api.request = request
        with patch.object(DAILY, 'notes', return_value='- Fixture'):
            self.assertTrue(DAILY.plan(api, SHA, SOURCE, 42)['build'])

    def test_missing_orphaned_run_is_not_success_and_other_api_errors_propagate(self):
        api = API(records=[reservation()])
        original = api.request
        def request(path, data=None):
            if path.startswith('actions/runs/'):
                raise subprocess.CalledProcessError(1, ['gh'], stderr='gh: Not Found (HTTP 404)')
            return original(path, data)
        api.request = request
        with patch.object(DAILY, 'notes', return_value='- Fixture'):
            self.assertTrue(DAILY.plan(api, SHA, SOURCE, 42)['build'])
        api.request = Mock(side_effect=subprocess.CalledProcessError(1, ['gh'], stderr='gh: Forbidden (HTTP 403)'))
        with self.assertRaises(subprocess.CalledProcessError):
            DAILY.run_uploaded(api, 20)

    def test_stable_release_advances_to_the_next_patch_beta(self):
        records = [{'version_name': '0.4.2-beta.8', 'version_code': 24}]
        self.assertEqual(('0.4.3-beta.1', 25), DAILY.next_version('0.4.2', 24, records))
        self.assertEqual(('0.4.3-beta.1', 25), DAILY.next_version('0.4.2-beta.3', 18, records, released=True))
        self.assertEqual(('0.4.3-beta.5', 27), DAILY.next_version('0.4.2', 24, [
            *records, {'version_name': '0.4.3-beta.4', 'version_code': 26},
        ]))

    def test_reserved_next_patch_beta_can_be_applied_to_a_stable_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app').mkdir()
            file = root / 'app/build.gradle.kts'
            file.write_text(SOURCE.replace('0.4.2-beta.3', '0.4.2'))
            value = {'build': True, 'sha': SHA, 'version_name': '0.4.3-beta.1',
                     'version_code': 19, 'notes': 'Your notes are easier to find.'}
            DAILY.apply(value, SHA, root)
            self.assertEqual(('0.4.3-beta.1', 19), DAILY.source_version(file.read_text()))
            self.assertEqual(value['notes'], (root / 'metadata/en-US/changelogs/19.txt').read_text())
            self.assertEqual(DAILY.release_notes.release_body(value['notes'], ''),
                             (root / 'test-release-notes.md').read_text())

    def test_a_stable_tag_on_a_beta_source_reserves_the_next_patch(self):
        api = API()
        with patch.object(DAILY, 'command', side_effect=lambda *args: 'v0.4.2' if args[1] == 'tag' else OLD), \
                patch.object(DAILY, 'notes', return_value='- Main changes'):
            value = DAILY.plan(api, SHA, SOURCE, 42)
        self.assertTrue(value['stable_released'])
        self.assertEqual('0.4.3-beta.1', value['version_name'])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app').mkdir()
            file = root / 'app/build.gradle.kts'
            file.write_text(SOURCE)
            DAILY.apply(value, SHA, root)
            self.assertEqual(('0.4.3-beta.1', 19), DAILY.source_version(file.read_text()))
            file.write_text(SOURCE)
            with self.assertRaises(ValueError):
                DAILY.apply({**value, 'stable_released': False}, SHA, root)

    def test_divergent_manual_hotfix_uses_the_merge_base_and_its_code_floor(self):
        api = API(baseline=OLD, baseline_code=40)
        common = 'c' * 40
        with patch.object(DAILY, 'command', side_effect=lambda *args: common if args[1] == 'merge-base' else ''), \
                patch.object(DAILY, 'notes', return_value='- Main changes') as notes:
            value = DAILY.plan(api, SHA, SOURCE, 42)
        self.assertEqual(common, notes.call_args.args[1])
        self.assertEqual(41, value['version_code'])


if __name__ == '__main__':
    unittest.main()
