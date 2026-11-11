"""Verify self-hosted runner selection enforces the 4-event allow-list and tag exclusion."""

import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('runners', ROOT / 'scripts/check-runner-selection.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


class RunnerSelectionPolicyTest(unittest.TestCase):
    def test_repository_workflows_pass_runner_selection_policy(self):
        old_workflows = POLICY.WORKFLOWS
        try:
            POLICY.WORKFLOWS = ROOT / '.github/workflows'
            self.assertEqual([], POLICY.problems())
        finally:
            POLICY.WORKFLOWS = old_workflows

    def test_unallowlisted_or_tag_allowing_selectors_are_rejected(self):
        legacy_unguarded = (
            "${{ vars.CM_RUNNER == 'hetzner' && !startsWith(github.ref, 'refs/tags/') && "
            "fromJSON('[\"self-hosted\",\"cm-hetzner\"]') || 'ubuntu-24.04' }}"
        )
        self.assertIsNone(POLICY.SELECTOR.match(legacy_unguarded))
        issues = POLICY.selection_problems('android.yml:checks', legacy_unguarded)
        self.assertTrue(any('fork pull_request' in item for item in issues), issues)
        self.assertTrue(any('pull_request_target' in item for item in issues), issues)

        missing_tag_guard = (
            "${{ (github.event_name == 'push' || github.event_name == 'schedule' || "
            "github.event_name == 'workflow_dispatch' || "
            "(github.event_name == 'pull_request' && "
            "github.event.pull_request.head.repo.full_name == github.repository)) && "
            "vars.CM_RUNNER == 'hetzner' && fromJSON('[\"self-hosted\",\"cm-hetzner\"]') || 'ubuntu-24.04' }}"
        )
        self.assertIsNone(POLICY.SELECTOR.match(missing_tag_guard))
        tag_issues = POLICY.selection_problems('android.yml:build', missing_tag_guard)
        self.assertTrue(any('tag push' in item for item in tag_issues), tag_issues)

    def test_signing_is_limited_to_tags_or_explicit_main_schedule_dispatch(self):
        guard = '${{ ' + POLICY.SIGNED_TEST.removeprefix('if: ') + ' }}'
        cases = [
            ('schedule', 'refs/heads/main', True, True),
            ('workflow_dispatch', 'refs/heads/main', True, True),
            ('push', 'refs/heads/main', True, False),
            ('schedule', 'refs/heads/main', False, False),
            ('workflow_dispatch', 'refs/heads/feature', True, False),
            ('pull_request', 'refs/pull/1/merge', True, False),
            ('push', 'refs/tags/v0.4.2-beta.3', False, True),
        ]
        for event, ref, daily, expected in cases:
            with self.subTest(event=event, ref=ref, daily=daily):
                self.assertEqual(expected, POLICY.select(guard, 'hetzner', ref, event_name=event, daily_test=daily))

    def test_daily_pipeline_preserves_the_existing_public_release_boundary(self):
        jobs = dict(POLICY.jobs(ROOT / '.github/workflows/android.yml'))
        self.assertIn("    if: startsWith(github.ref, 'refs/tags/')", jobs['release'])
        self.assertIn("inputs.daily_test != true &&", POLICY.runs_on(jobs['build']))
        for name in ('checks', 'core-only', 'build', 'emulator'):
            self.assertIn(f"needs.{name}.result == 'success'", jobs['play'])
        self.assertIn('          path: play/\n', jobs['build'])


if __name__ == '__main__':
    unittest.main()
