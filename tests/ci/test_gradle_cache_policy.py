"""Keep the shared Gradle cache warm without creating PR copies or self-hosted cache writers."""

import importlib.util
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('runners', ROOT / 'scripts/check-runner-selection.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
JOBS = dict(POLICY.jobs(ROOT / '.github/workflows/android.yml'))


class GradleCachePolicyTest(unittest.TestCase):
    def test_main_build_is_the_only_gradle_cache_writer(self):
        for job in ['build', 'checks', 'core-only']:
            text = JOBS[job]
            with self.subTest(job=job):
                self.assertEqual([], POLICY.movable_job_problems('android.yml:' + job, text))

    def test_signed_daily_build_never_saves_the_shared_gradle_home(self):
        expression = POLICY.MAIN_GRADLE_CACHE.split('${{ ', 1)[1].removesuffix(' }}').replace('||', ' or ')
        for daily in (False, True):
            for ref in ('refs/heads/main', 'refs/pull/1/merge', 'refs/tags/v0.4.2-beta.3'):
                for environment in ('github-hosted', 'self-hosted'):
                    context = {
                        'inputs': SimpleNamespace(daily_test=daily),
                        'github': SimpleNamespace(ref=ref),
                        'runner': SimpleNamespace(environment=environment),
                    }
                    actual = eval(expression, {'__builtins__': {}}, context)
                    self.assertEqual(daily or ref != 'refs/heads/main' or environment != 'github-hosted', actual)

    def test_regressed_build_policy_and_extra_writers_are_refused(self):
        for unsafe in [
            "cache-read-only: ${{ runner.environment != 'github-hosted' }}",
            "cache-read-only: ${{ github.ref != 'refs/heads/main' }}",
            'cache-read-only: false',
            'cache-read-only: true',  # Would remove the main writer and leave the cache cold.
            '',
        ]:
            with self.subTest(policy=unsafe):
                changed = JOBS['build'].replace(POLICY.MAIN_GRADLE_CACHE, unsafe)
                self.assertTrue(POLICY.movable_job_problems('android.yml:build', changed))
        for job in ['checks', 'core-only']:
            with self.subTest(job=job):
                changed = JOBS[job].replace('cache-read-only: true', POLICY.MAIN_GRADLE_CACHE)
                self.assertTrue(POLICY.movable_job_problems('android.yml:' + job, changed))
