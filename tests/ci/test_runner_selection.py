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


if __name__ == '__main__':
    unittest.main()
