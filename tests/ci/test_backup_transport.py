"""A successful adb/bmgr process is not evidence of a completed application backup."""

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'backup_transport', Path(__file__).resolve().parents[2] / 'scripts/check-backup-transport.py',
)
BACKUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BACKUP)


class BackupTransportTests(unittest.TestCase):
    def test_success_requires_the_exact_package_and_completed_operation(self):
        output = f'Package {BACKUP.PACKAGE} with result: Success\nBackup finished with result: Success\n'
        self.assertEqual('Success', BACKUP.backup_result(output))

    def test_quota_refusal_preserves_the_framework_status(self):
        status = 'Size quota exceeded'
        output = f'Package {BACKUP.PACKAGE} with result: {status}\nBackup finished with result: {status}\n'
        self.assertEqual(status, BACKUP.backup_result(output))

    def test_agent_refusal_is_kept_as_a_refusal(self):
        output = f'Package {BACKUP.PACKAGE} with result: Agent error\nBackup finished with result: Agent error\n'
        self.assertEqual('Agent error', BACKUP.backup_result(output))

    def test_overall_success_or_another_packages_result_is_insufficient(self):
        for output in ('Backup finished with result: Success\n',
                       'Package example.other with result: Success\nBackup finished with result: Success\n'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                BACKUP.backup_result(output)

    def test_partial_success_cannot_hide_a_failed_or_unfinished_transport(self):
        prefix = f'Package {BACKUP.PACKAGE} with result: Success\n'
        for output in (prefix, prefix + 'Backup finished with result: Transport error\n'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                BACKUP.backup_result(output)

    def test_cli_requires_the_shared_heavy_wrapper_before_writing_anything(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'must-not-exist'
            environment = dict(os.environ)
            environment.pop('CASSINI_HEAVY_LOCK_HELD', None)
            result = subprocess.run(
                [sys.executable, BACKUP.__file__, '--sdk', directory, '--app', 'unused.apk',
                 '--test-apk', 'unused-test.apk', '--output', str(output)],
                env=environment, capture_output=True, text=True, timeout=10, check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Run through the shared heavy-run wrapper', result.stderr)
            self.assertFalse(output.exists())
