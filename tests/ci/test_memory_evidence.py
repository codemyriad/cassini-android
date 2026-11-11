"""Evidence distinguishes cgroup OOM counters from a crash, without retaining secrets or process identities."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

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


if __name__ == '__main__':
    unittest.main()
