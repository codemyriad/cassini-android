"""Exercise the actual Actions expressions and full job DAG: PR edges, full-ci, perf gate, tags and schedules."""

import importlib.util
import json
import re
import unittest
from pathlib import Path
from types import SimpleNamespace as NS

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('runners', ROOT / 'scripts/check-runner-selection.py')
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)
TEXT = (ROOT / '.github/workflows/android.yml').read_text()
JOBS = dict(POLICY.jobs(ROOT / '.github/workflows/android.yml'))
MATRIX = re.search(r'^        api: (.+)$', JOBS['emulator'], re.M).group(1)
GROUP = re.search(r'^  group: (.+)$', TEXT, re.M).group(1)
CANCEL = re.search(r'^  cancel-in-progress: (.+)$', TEXT, re.M).group(1)
PERF_GATE = re.search(r'^  CASSINI_PERF_GATE: (.+)$', TEXT, re.M).group(1)
STATUS_FUNCTIONS = ('always()', 'cancelled()', 'failure()', 'success()')


def parse_job_dag(jobs_map: dict[str, str]) -> dict[str, tuple[list[str], str | None]]:
    """Extract (needs, job-level if expression) for every job in the workflow."""
    dag: dict[str, tuple[list[str], str | None]] = {}
    for name, block in jobs_map.items():
        header = block.split('\n    steps:\n', 1)[0]
        needs_match = re.search(r'^    needs:\s*\[([^\]]*)\]\s*$', header, re.M)
        if needs_match:
            needs = [item.strip() for item in needs_match.group(1).split(',') if item.strip()]
        elif single_need := re.search(r'^    needs:\s*([A-Za-z0-9_-]+)\s*$', header, re.M):
            needs = [single_need.group(1)]
        else:
            needs = []

        lines = header.splitlines()
        condition: str | None = None
        for idx, line in enumerate(lines):
            if match := re.match(r'^    if:\s*(.*)$', line):
                raw = match.group(1).strip()
                if raw in ('>-', '>', '|', '|-'):
                    folded: list[str] = []
                    for following in lines[idx + 1:]:
                        if not following.startswith('      '):
                            break
                        folded.append(following.strip())
                    condition = ' '.join(folded)
                else:
                    condition = raw
                condition = condition.removeprefix('${{').removesuffix('}}').strip()
                break
        dag[name] = (needs, condition)
    return dag


def evaluate(
    expression: str,
    *,
    event: str = 'pull_request',
    action: str = 'synchronize',
    labels: tuple[str, ...] = (),
    label: str = '',
    ref: str | None = None,
    daily: bool = False,
    run_id: int = 1,
    needs_results: dict[str, str] | None = None,
    ancestors_succeeded: bool = True,
):
    expression = expression.removeprefix('${{').removesuffix('}}').strip()
    expression = expression.replace('github.event.pull_request.labels.*.name', 'labels')
    for job_id in sorted((needs_results or {}), key=len, reverse=True):
        if '-' in job_id:
            expression = expression.replace(f'needs.{job_id}.', f'needs.{job_id.replace("-", "_")}.')
    expression = expression.replace('always()', 'True')
    expression = expression.replace('cancelled()', 'False')
    expression = expression.replace('failure()', 'False')
    expression = expression.replace('success()', str(ancestors_succeeded))
    expression = expression.replace('&&', ' and ').replace('||', ' or ')
    expression = re.sub(r'!(?!=)', ' not ', expression)
    resolved_ref = ref if ref is not None else ('refs/pull/1/merge' if event == 'pull_request' else 'refs/heads/main')
    needs_ns = NS(**{k.replace('-', '_'): NS(result=POLICY._CIString(v)) for k, v in (needs_results or {}).items()})
    event_ns = (
        NS(action=POLICY._CIString(action), label=NS(name=POLICY._CIString(label)))
        if event == 'pull_request'
        else POLICY._NULL
    )
    context = {
        'github': NS(
            event_name=POLICY._CIString(event),
            event=event_ns,
            ref=POLICY._CIString(resolved_ref),
            sha=POLICY._CIString('abc'),
            run_id=run_id,
        ),
        'inputs': NS(daily_test=daily),
        'labels': [POLICY._CIString(item) for item in labels],
        'needs': needs_ns,
        'True': True,
        'False': False,
        'fromJSON': json.loads,
        'contains': lambda values, name: str(name).casefold() in [str(v).casefold() for v in values],
        'format': lambda template, value: template.format(value),
        'startsWith': lambda text, prefix: str(text).casefold().startswith(str(prefix).casefold()),
    }
    return eval(expression, {'__builtins__': {}}, context)


def simulate_workflow(dag: dict[str, tuple[list[str], str | None]], **kwargs) -> dict[str, str]:
    """Simulate GitHub Actions job scheduling, including transitive skipped-ancestor propagation."""
    results: dict[str, str] = {}
    remaining = list(dag)

    def transitive_ancestors(job: str) -> set[str]:
        seen: set[str] = set()
        stack = list(dag[job][0])
        while stack:
            anc = stack.pop()
            if anc not in seen:
                seen.add(anc)
                stack.extend(dag[anc][0])
        return seen

    while remaining:
        progressed = False
        for job in list(remaining):
            needs, condition = dag[job]
            if any(dep not in results for dep in needs):
                continue
            ancestors = transitive_ancestors(job)
            # In GitHub Actions, default `success()` requires every ancestor in the dependency chain to succeed;
            # a skipped ancestor causes downstream jobs without an explicit status function to skip too.
            ancestors_ok = all(results[anc] == 'success' for anc in ancestors)
            if condition is None:
                should_run = ancestors_ok
            else:
                has_status_fn = any(fn in condition for fn in STATUS_FUNCTIONS)
                effective_if = condition if has_status_fn else f'success() && ({condition})'
                should_run = bool(
                    evaluate(
                        effective_if,
                        needs_results={dep: results[dep] for dep in needs},
                        ancestors_succeeded=ancestors_ok,
                        **kwargs,
                    )
                )
            results[job] = 'success' if should_run else 'skipped'
            remaining.remove(job)
            progressed = True
        if not progressed:
            raise AssertionError(f'Cycle or unresolved needs in workflow DAG: {remaining}')
    return results


class EmulatorMatrixTest(unittest.TestCase):
    def check_matrix(self, expression: str) -> None:
        for event in ['push', 'schedule', 'workflow_dispatch']:
            self.assertEqual([26, 29, 34, 35], evaluate(expression, event=event), event)
        for labels, expected in [
            ((), [26, 35]),
            (('docs',), [26, 35]),
            (('full-ci',), [26, 29, 34, 35]),
            (('docs', 'FULL-CI'), [26, 29, 34, 35]),
        ]:
            self.assertEqual(expected, evaluate(expression, labels=labels), labels)
        self.assertEqual([26, 29, 34, 35], evaluate(expression, event='schedule', daily=True))

    def test_matrix_keeps_edges_and_restores_full_coverage(self):
        self.check_matrix(MATRIX)
        for mutation in [MATRIX.replace('[26,35]', '[35]'), MATRIX.replace("'full-ci'", "'ignored'")]:
            with self.assertRaises(AssertionError):
                self.check_matrix(mutation)

    def test_every_event_runs_its_complete_job_graph_without_skipped_ancestor_holes(self):
        self.assertIn('types: [opened, synchronize, reopened, labeled]', TEXT)
        self.assertIn("cron: '30 2 * * *'", TEXT)
        dag = parse_job_dag(JOBS)
        self.assertEqual({'checks', 'core-only', 'build', 'emulator', 'release', 'play'}, set(dag))

        cases = [
            (
                'unlabeled PR opened',
                {'event': 'pull_request', 'action': 'opened'},
                {'checks', 'core-only', 'build', 'emulator'},
            ),
            (
                'unlabeled PR synchronize',
                {'event': 'pull_request', 'action': 'synchronize'},
                {'checks', 'core-only', 'build', 'emulator'},
            ),
            (
                'full-ci labeled PR',
                {'event': 'pull_request', 'action': 'labeled', 'label': 'full-ci', 'labels': ('full-ci',)},
                {'checks', 'core-only', 'build', 'emulator'},
            ),
            (
                'FULL-CI labeled PR (case-insensitive)',
                {'event': 'pull_request', 'action': 'labeled', 'label': 'FULL-CI', 'labels': ('FULL-CI',)},
                {'checks', 'core-only', 'build', 'emulator'},
            ),
            (
                'unrelated labeled PR',
                {'event': 'pull_request', 'action': 'labeled', 'label': 'docs', 'labels': ('docs',)},
                set(),
            ),
            (
                'push to main',
                {'event': 'push', 'action': '', 'ref': 'refs/heads/main'},
                {'checks', 'core-only', 'build', 'emulator'},
            ),
            (
                'nightly schedule',
                {'event': 'schedule', 'action': '', 'ref': 'refs/heads/main'},
                {'checks', 'core-only', 'build', 'emulator'},
            ),
            (
                'manual workflow_dispatch',
                {'event': 'workflow_dispatch', 'action': '', 'ref': 'refs/heads/main'},
                {'checks', 'core-only', 'build', 'emulator'},
            ),
            (
                'tag push release',
                {'event': 'push', 'action': '', 'ref': 'refs/tags/v1.2.3'},
                {'checks', 'core-only', 'build', 'emulator', 'release', 'play'},
            ),
            (
                'daily internal Play release',
                {'event': 'schedule', 'action': '', 'ref': 'refs/heads/main', 'daily': True},
                {'checks', 'core-only', 'build', 'emulator', 'play'},
            ),
        ]
        for description, ctx, expected_active in cases:
            with self.subTest(case=description):
                outcomes = simulate_workflow(dag, **ctx)
                active = {job for job, state in outcomes.items() if state == 'success'}
                self.assertEqual(expected_active, active, f'{description}: {outcomes}')

        # Prove the DAG simulator catches the skipped-ancestor bug class (run 43163003089).
        regressed = dict(dag)
        regressed['label-gate'] = ([], "github.event_name == 'pull_request' && github.event.action == 'labeled'")
        for root_job in ('checks', 'core-only', 'build'):
            _, cond = regressed[root_job]
            regressed[root_job] = (['label-gate'], f'always() && ({cond})')
        broken_pr = simulate_workflow(regressed, event='pull_request', action='synchronize')
        self.assertEqual('skipped', broken_pr['emulator'])
        broken_tag = simulate_workflow(regressed, event='push', action='', ref='refs/tags/v1.2.3')
        self.assertEqual('skipped', broken_tag['emulator'])
        self.assertEqual('skipped', broken_tag['release'])
        self.assertEqual('skipped', broken_tag['play'])

    def test_labeled_events_never_cancel_or_collide_with_code_runs(self):
        def group(**context):
            return re.sub(r'\$\{\{.*?\}\}', lambda m: str(evaluate(m.group(), **context) or ''), GROUP)

        regular = group()
        self.assertNotEqual(regular, group(action='labeled', label='full-ci'))
        self.assertNotEqual(regular, group(action='labeled', label='docs'))
        self.assertNotEqual(
            group(action='labeled', label='full-ci', run_id=1),
            group(action='labeled', label='full-ci', run_id=2),
        )
        self.assertTrue(bool(evaluate(CANCEL, event='pull_request', action='synchronize')))
        self.assertFalse(bool(evaluate(CANCEL, event='pull_request', action='labeled')))
        self.assertFalse(bool(evaluate(CANCEL, event='push', action='')))
        self.assertFalse(bool(evaluate(CANCEL, event='schedule', action='')))

    def test_perf_gate_is_task_input_and_enabled_only_for_nightly_dispatch_and_full_ci(self):
        self.assertEqual('', evaluate(PERF_GATE, event='pull_request', labels=()))
        self.assertEqual('1', evaluate(PERF_GATE, event='pull_request', labels=('full-ci',)))
        self.assertEqual('', evaluate(PERF_GATE, event='push', action=''))
        self.assertEqual('1', evaluate(PERF_GATE, event='schedule', action='', daily=False))
        self.assertEqual('1', evaluate(PERF_GATE, event='workflow_dispatch', action='', daily=False))
        self.assertEqual('', evaluate(PERF_GATE, event='schedule', action='', daily=True))
        gradle = (ROOT / 'build.gradle.kts').read_text()
        self.assertIn('providers.environmentVariable("CASSINI_PERF_GATE")', gradle)
        self.assertIn('inputs.property("CASSINI_PERF_GATE", perfGateValue)', gradle)
        self.assertIn('environment("CASSINI_PERF_GATE", perfGateValue.get())', gradle)
        self.assertIn('python3 tests/ci/perf-gate-cache-smoke.py', JOBS['core-only'])


if __name__ == '__main__':
    unittest.main()
