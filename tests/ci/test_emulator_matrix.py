"""Exercise the actual Actions expressions: PR edges, opt-in full coverage and quiet label events."""

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


def evaluate(expression, event='pull_request', action='synchronize', labels=(), label='', daily=False, run_id=1):
    expression = expression.removeprefix('${{').removesuffix('}}').strip()
    expression = expression.replace('github.event.pull_request.labels.*.name', 'labels')
    expression = expression.replace('&&', ' and ').replace('||', ' or ')
    expression = re.sub(r'!(?!=)', ' not ', expression)
    context = {
        'github': NS(event_name=event, event=NS(action=action, label=NS(name=label)),
                     ref='refs/pull/1/merge' if event == 'pull_request' else 'refs/heads/main',
                     sha='abc', run_id=run_id),
        'inputs': NS(daily_test=daily), 'labels': labels,
        'fromJSON': json.loads,
        'contains': lambda values, name: name.casefold() in [v.casefold() for v in values],
        'format': lambda template, value: template.format(value),
    }
    return eval(expression, {'__builtins__': {}}, context)


class EmulatorMatrixTest(unittest.TestCase):
    def check_matrix(self, expression):
        for event in ['push', 'schedule', 'workflow_dispatch']:
            self.assertEqual([26, 29, 34, 35], evaluate(expression, event=event), event)
        for labels, expected in [((), [26, 35]), (('docs',), [26, 35]),
                                 (('full-ci',), [26, 29, 34, 35]), (('docs', 'FULL-CI'), [26, 29, 34, 35])]:
            self.assertEqual(expected, evaluate(expression, labels=labels), labels)
        self.assertEqual([26, 29, 34, 35], evaluate(expression, event='schedule', daily=True))

    def test_matrix_keeps_edges_and_restores_full_coverage(self):
        self.check_matrix(MATRIX)
        for mutation in [MATRIX.replace('[26,35]', '[35]'), MATRIX.replace("'full-ci'", "'ignored'")]:
            with self.assertRaises(AssertionError):
                self.check_matrix(mutation)

    def test_full_ci_label_runs_now_and_unrelated_labels_start_no_heavy_jobs(self):
        self.assertIn('types: [opened, synchronize, reopened, labeled]', TEXT)
        self.assertIn("cron: '30 2 * * *'", TEXT)
        for job in ['checks', 'core-only', 'build']:
            condition = re.search(r'^    if: (.+)$', JOBS[job], re.M).group(1)
            for action, label, expected in [('synchronize', '', True), ('labeled', 'full-ci', True),
                                            ('labeled', 'docs', False)]:
                with self.subTest(job=job, action=action, label=label):
                    self.assertEqual(expected, bool(evaluate(condition, action=action, label=label)))
            for event in ['push', 'schedule', 'workflow_dispatch']:
                self.assertTrue(evaluate(condition, event=event))

    def test_unrelated_label_cannot_cancel_a_useful_run(self):
        def group(**context):
            return re.sub(r'\$\{\{.*?\}\}', lambda m: str(evaluate(m.group(), **context) or ''), GROUP)

        regular = group()
        self.assertEqual(regular, group(action='labeled', label='full-ci'))
        self.assertNotEqual(regular, group(action='labeled', label='docs'))
        self.assertNotEqual(group(action='labeled', label='docs', run_id=1),
                            group(action='labeled', label='docs', run_id=2))
