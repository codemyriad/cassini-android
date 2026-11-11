"""Evidence distinguishes cgroup OOM counters from a crash, without retaining secrets or process identities."""
import contextlib
import errno
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/ci-memory-evidence.py'
SPEC = importlib.util.spec_from_file_location('memory_evidence', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MemoryEvidenceTest(unittest.TestCase):
    def put(self, root, name, value):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def test_v2_prefers_the_job_limit_and_retains_oom_counters(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc, cgroup = root / 'proc', root / 'cgroup'
            self.put(proc, 'self/cgroup', '0::/job\n')
            self.put(proc, 'meminfo', 'MemTotal: 1234 kB\nMemAvailable: 12 kB\nSecret: 99\n')
            self.put(cgroup, 'memory.events', 'oom_kill 0\n')
            self.put(cgroup, 'job/memory.events', 'oom 2\noom_kill 1\nsecret 1234\n')
            self.put(cgroup, 'job/memory.max', '1024\n')
            self.put(cgroup, 'job/memory.current', '1000\n')
            self.put(cgroup, 'job/memory.swap.max', 'max\n')
            value = MODULE.snapshot(proc, cgroup, 'java 100\n/private-person-name 20\n')
            self.assertEqual(1024, value['cgroup']['memory.max'])
            self.assertEqual({'oom': 2, 'oom_kill': 1}, value['cgroup']['events'])
            self.assertEqual('max', value['cgroup']['memory.swap.max'])
            self.assertEqual({'MemTotal': 1234, 'MemAvailable': 12}, value['hostKiB'])
            self.assertEqual(
                {'javaCount': 1, 'javaRssKiB': 100, 'processCount': 2, 'rssKiB': 120}, value['processTotals'],
            )
            encoded = json.dumps(value)
            self.assertNotIn('private-person-name', encoded)
            self.assertNotIn('secret', encoded.lower())

    def test_namespaced_root_and_legacy_v1_are_both_supported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc, cgroup = root / 'proc', root / 'cgroup'
            self.put(proc, 'self/cgroup', '0::/host-only-path\n')
            self.put(cgroup, 'memory.events', 'oom_kill 0\n')
            self.put(cgroup, 'memory.max', 'max\n')
            self.assertEqual('max', MODULE.snapshot(proc, cgroup, '')['cgroup']['memory.max'])
            self.put(proc, 'self/cgroup', '0::/../../hidden-host-path\n')
            self.assertEqual({'oom_kill': 0}, MODULE.snapshot(proc, cgroup, '')['cgroup']['events'])
            self.put(proc, 'self/cgroup', '4:cpu:/job\n5:memory:/job\n')
            self.put(cgroup, 'memory/job/memory.limit_in_bytes', '2048\n')
            self.put(cgroup, 'memory/job/memory.failcnt', '3\n')
            self.put(cgroup, 'memory/job/memory.oom_control', 'under_oom 0\noom_kill 1\n')
            value = MODULE.snapshot(proc, cgroup, '')['cgroup']
            self.assertEqual(1, value['version'])
            self.assertEqual(2048, value['memory.limit_in_bytes'])
            self.assertEqual(1, value['events']['oom_kill'])

    def test_unavailable_or_malformed_evidence_never_invents_an_oom(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.put(root, 'proc/self/cgroup', '0::/../outside\nnot-a-cgroup\n')
            self.put(root, 'outside/memory.events', 'oom_kill 999\n')
            value = MODULE.snapshot(root / 'proc', root / 'cgroup', 'java invalid\n')
            self.assertEqual({'version': None}, value['cgroup'])
            self.assertEqual(0, value['processTotals']['processCount'])
            self.assertIsNone(MODULE.number(root / 'missing'))
            self.assertEqual({}, MODULE.pairs('oom_kill invalid\n', MODULE.EVENT_KEYS))

    def test_walks_ancestors_and_skips_unreadable_leaf_for_v1_and_v2(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc, cgroup = root / 'proc', root / 'cgroup'
            for membership, prefix, marker, version in [
                ('0::/service/child', '', 'memory.events', 2),
                ('5:memory:/service/child', 'memory', 'memory.limit_in_bytes', 1),
            ]:
                with self.subTest(version=version):
                    self.put(proc, 'self/cgroup', membership)
                    parent = cgroup / prefix / 'service'
                    leaf = parent / 'child' / marker
                    self.put(parent, marker, '0')
                    self.put(parent / 'child', marker, '0')
                    original = Path.open

                    def restricted(path, *args, blocked=leaf, opener=original, **kwargs):
                        if path == blocked:
                            raise PermissionError('restricted cgroup')
                        return opener(path, *args, **kwargs)

                    with patch.object(Path, 'open', restricted):
                        self.assertEqual((version, parent), MODULE.memory_group(proc, cgroup))
                    leaf.unlink()
                    self.assertEqual((version, parent), MODULE.memory_group(proc, cgroup))

    def test_main_replaces_stale_after_and_decodes_non_utf8_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            proc, cgroup, output = root / 'proc', root / 'cgroup', root / 'output'
            self.put(proc, 'self/cgroup', '0::/job')
            self.put(proc, 'meminfo', 'MemTotal: 123 kB')
            (proc / 'meminfo').write_bytes(b'MemTotal: 123 kB\ninvalid \xff\n')
            self.put(cgroup, 'job/memory.events', 'oom_kill 2')
            self.put(output, 'after.json', '{"stale": true}')
            real_run = subprocess.run

            def process_output(command, **kwargs):
                self.assertEqual(['ps', '-eo', 'comm=,rss='], command)
                self.assertEqual(10, kwargs['timeout'])
                self.assertEqual('utf-8', kwargs['encoding'])
                self.assertEqual('replace', kwargs['errors'])
                emitter = 'import os; os.write(1, bytes([255]) + b" 20\\njava 100\\n")'
                return real_run([sys.executable, '-c', emitter], **kwargs)

            with patch.object(MODULE.subprocess, 'run', side_effect=process_output):
                self.assertEqual(0, MODULE.main(['before'], proc=proc, cgroups=cgroup, output=output))
                self.assertFalse((output / 'after.json').exists())
                before = json.loads((output / 'before.json').read_text())
                self.assertEqual({'MemTotal': 123}, before['hostKiB'])
                self.assertEqual(120, before['processTotals']['rssKiB'])
                self.assertEqual(1, before['processTotals']['javaCount'])
                self.assertEqual({'oom_kill': 2}, before['cgroup']['events'])
                self.assertEqual(0, MODULE.main(['after'], proc=proc, cgroups=cgroup, output=output))
                self.assertEqual(before, json.loads((output / 'after.json').read_text()))
                self.assertNotIn('stale', (output / 'after.json').read_text())

    def test_main_ps_failures_are_unknown_not_job_failures(self):
        errors = [PermissionError(), OSError(), UnicodeDecodeError('utf-8', b'\xff', 0, 1, 'invalid'),
                  subprocess.TimeoutExpired('ps', 10), subprocess.SubprocessError()]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for error in errors:
                with self.subTest(error=type(error).__name__):
                    with patch.object(MODULE.subprocess, 'run', side_effect=error):
                        self.assertEqual(0, MODULE.main(['after'], proc=root, cgroups=root, output=root))
                    value = json.loads((root / 'after.json').read_text())
                    self.assertIsNone(value['processTotals']['processCount'])
                    self.assertEqual({'version': None}, value['cgroup'])
                    with patch.object(MODULE.subprocess, 'run', side_effect=error):
                        self.assertEqual(0, MODULE.main(['before'], proc=root, cgroups=root, output=root))
                    self.assertFalse((root / 'after.json').exists())
            with patch.object(MODULE.subprocess, 'run', return_value=subprocess.CompletedProcess('ps', 1, 'java 999')):
                self.assertEqual(0, MODULE.main(['after'], proc=root, cgroups=root, output=root))
                self.assertIsNone(json.loads((root / 'after.json').read_text())['processTotals']['javaCount'])

    def test_main_output_and_cleanup_failures_warn_without_failing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for method, error in [('mkdir', PermissionError()), ('write_text', OSError(errno.ENOSPC, 'full')),
                                  ('unlink', PermissionError())]:
                with self.subTest(method=method):
                    warning = io.StringIO()
                    with patch.object(Path, method, side_effect=error), contextlib.redirect_stderr(warning):
                        with patch.object(MODULE.subprocess, 'run', side_effect=OSError()):
                            self.assertEqual(0, MODULE.main(['before'], proc=root, cgroups=root, output=root))
                    self.assertIn('Warning:', warning.getvalue())

    def test_workflow_snapshots_are_isolated_and_never_fail_the_build(self):
        workflow = (SCRIPT.parents[1] / '.github/workflows/android.yml').read_text()
        for phase, name in [('before', 'limits before'), ('after', 'counters after')]:
            header = f'      - name: Snapshot build memory {name} Gradle\n'
            step = workflow.split(header, 1)[1].split('      - name:', 1)[0]
            self.assertIn('continue-on-error: true', step)
            self.assertIn(f'run: python3 -I scripts/ci-memory-evidence.py {phase}', step)
            if phase == 'after':
                self.assertIn('if: always()', step)


if __name__ == '__main__':
    unittest.main()
