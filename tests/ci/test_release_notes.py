"""Store copy is authored, bounded and consumed only by a successful publication baseline."""

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('release_notes', ROOT / 'scripts/release_notes.py')
NOTES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NOTES)
DAILY_SPEC = importlib.util.spec_from_file_location('daily_notes', ROOT / 'scripts/daily-test-release.py')
DAILY = importlib.util.module_from_spec(DAILY_SPEC)
DAILY_SPEC.loader.exec_module(DAILY)


class ReleaseNotesTests(unittest.TestCase):
    def write(self, root, name, kind, text):
        directory = root / 'release-notes.d'
        directory.mkdir(exist_ok=True)
        (directory / f'{name}.md').write_text(f'kind: {kind}\n---\n{text}\n')

    def test_repository_fragments_follow_the_store_contract(self):
        text = NOTES.render(NOTES.fragments(ROOT))
        self.assertEqual(text, NOTES.store_notes(text))
        with self.assertRaises(ValueError):
            NOTES.fragments(ROOT, '--help')

    def test_fixture_renders_groups_in_user_order(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, 'a-repair', 'fixed', 'Your notes keep their names when you import a ZIP.')
            self.write(root, 'b-export', 'new', 'You can export several notes together.')
            self.write(root, 'c-voices', 'better', "Saved voices are easier to find, even if you can't recall a name.")
            self.assertEqual('New\n• You can export several notes together.\n\n'
                             'Better\n• Saved voices are easier to find, even if you can’t recall a name.\n\n'
                             'Fixed\n• Your notes keep their names when you import a ZIP.',
                             NOTES.render(NOTES.fragments(root)))

    def test_developer_titles_cannot_enter_actual_play_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app').mkdir()
            (root / 'app/build.gradle.kts').write_text('versionCode = 19\nversionName = "0.4.2-beta.3"\n')
            self.write(root, 'export', 'new', 'You can export several notes together.')
            user = NOTES.render(NOTES.fragments(root))
            title = 'Refactor CI tests for D95 in worker.kt (#123) </details>'
            value = {'build': True, 'sha': 'a' * 40, 'version_name': '0.4.2-beta.5', 'version_code': 20,
                     'notes': user, 'developer_notes': '- ' + title}
            DAILY.apply(value, 'a' * 40, root)
            store = (root / 'metadata/en-US/changelogs/20.txt').read_text()
            for forbidden in (title, '#123', 'D95', 'CI', 'worker.kt'):
                self.assertNotIn(forbidden, store)
            body = (root / 'test-release-notes.md').read_text()
            self.assertTrue(body.startswith(user + '\n\n<details>'))
            self.assertIn('#123', body)
            self.assertIn('&lt;/details&gt;', body)
            self.assertEqual(1, body.count('</details>'))
            self.assertLessEqual(NOTES.utf16_units(store), 500)
            metadata = DAILY.plan_metadata(value)
            self.assertNotIn(title, json.dumps(metadata))
            self.assertNotIn('developer_notes', metadata)
            self.assertIn('developer_notes_sha256', metadata)

    def test_developer_fragments_are_refused_instead_of_laundered_into_store(self):
        for text in ('Fix CI', 'Add #123', 'Improve D95 handling', 'Update worker.kt',
                     'Refactor the engine', 'Unit tests pass', 'See https://example.com', 'Please update'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                NOTES.render([('fixed', text)])

    def test_overflow_keeps_whole_new_changes_before_smaller_fixes(self):
        new = 'You can export notes. ' * 10
        extra = 'Saved voices are easier to find. ' * 8
        fixed = 'ZIP import keeps your note names. ' * 8
        rendered = NOTES.render([('fixed', fixed), ('better', extra), ('new', new)])
        self.assertTrue(rendered.startswith('New\n• ' + new.strip()))
        self.assertNotIn('Fixed', rendered)
        self.assertTrue(rendered.endswith('and smaller fixes'))
        self.assertLessEqual(NOTES.utf16_units(rendered), 500)
        self.assertEqual(NOTES.FALLBACK, NOTES.render([]))
        emoji = NOTES.render([('new', 'You can see ' + '😀' * 210), ('fixed', fixed)])
        self.assertLessEqual(NOTES.utf16_units(emoji), 500)
        self.assertNotIn('\ufffd', emoji)

    def test_stable_requires_curated_nonempty_safe_notes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, 'Stable release requires'):
                NOTES.curated(root, 'v1.0.0')
            (root / 'release-notes').mkdir()
            file = root / 'release-notes/1.0.0.md'
            for bad in ('', 'Fix CI (#123)', '😀' * 251):
                file.write_text(bad)
                with self.subTest(bad=bad[:20]), self.assertRaises(ValueError):
                    NOTES.curated(root, 'v1.0.0')
            file.write_text('Before you update\nDownload the new model in Settings.')
            self.assertEqual(file.read_text(), NOTES.curated(root, 'v1.0.0'))
            self.assertEqual(NOTES.FALLBACK, NOTES.curated(root, 'v1.0.1-beta.1'))
            with self.assertRaises(ValueError):
                NOTES.curated(root, '../1.0.0')

    def test_publication_commit_consumes_unchanged_not_new_or_edited_fragments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
            git('init', '-q')
            git('config', 'user.name', 'English Sample')
            git('config', 'user.email', 'sample@example.invalid')
            self.write(root, 'first', 'new', 'You can export notes together.')
            git('add', '.')
            git('commit', '-qm', 'Published build')
            baseline = git('rev-parse', 'HEAD')
            self.assertEqual([], NOTES.fragments(root, baseline))
            self.write(root, 'second', 'fixed', 'Your note names stay intact.')
            self.assertEqual([('fixed', 'Your note names stay intact.')], NOTES.fragments(root, baseline))
            # Failed releases keep the last successful baseline: the same pending change remains visible.
            git('add', '.')
            git('commit', '-qm', 'Failed build does not publish')
            self.assertEqual([('fixed', 'Your note names stay intact.')], NOTES.fragments(root, baseline))
            self.write(root, 'first', 'new', 'You can export more notes together.')
            self.assertEqual(2, len(NOTES.fragments(root, baseline)))
            git('add', '.')
            git('commit', '-qm', 'Next successful publication')
            self.assertEqual([], NOTES.fragments(root, git('rev-parse', 'HEAD')))

    def test_malformed_or_linked_fragments_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, 'entry', 'other', 'A new control appears.')
            with self.assertRaises(ValueError):
                NOTES.fragments(root)
            self.write(root, 'entry', 'new', '😀' * 226)
            with self.assertRaises(ValueError):
                NOTES.fragments(root)
            file = root / 'release-notes.d/entry.md'
            file.unlink()
            file.symlink_to(root / 'missing')
            with self.assertRaises(ValueError):
                NOTES.fragments(root)

    def test_github_notes_follow_actual_upload_even_after_a_workflow_failure(self):
        for delivered in (False, True):
            with self.subTest(delivered=delivered), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                value = {'sha': 'a' * 40, 'run_id': 42, 'notes': 'Your note names stay intact.',
                         'developer_notes': 'Fix CI (#123)'}
                with patch.object(DAILY, 'load_plan', return_value=value), \
                        patch.object(DAILY, 'GitHub', return_value=Mock()), \
                        patch.object(DAILY, 'run_uploaded', return_value=delivered), \
                        patch('sys.argv', ['daily-test-release.py', 'body']), \
                        patch.dict(os.environ, {'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': 'a' * 40,
                                                'GITHUB_EVENT_NAME': 'workflow_dispatch', 'DAILY_TEST_PLAN': '{}',
                                                'GITHUB_OUTPUT': str(root / 'output')}):
                    before = Path.cwd()
                    try:
                        os.chdir(root)
                        DAILY.main()
                    finally:
                        os.chdir(before)
                self.assertEqual(f'publish={str(delivered).lower()}\n', (root / 'output').read_text())
                self.assertEqual(delivered, (root / 'test-release-notes.md').exists())

    def test_workflows_enforce_curated_tag_notes_and_publish_only_after_play(self):
        android = (ROOT / '.github/workflows/android.yml').read_text()
        daily = (ROOT / '.github/workflows/daily-test-release.yml').read_text()
        self.assertIn('scripts/release_notes.py "$GITHUB_REF_NAME" --check', android)
        self.assertIn('scripts/release_notes.py "$TAG" --play-code "$code"', android)
        self.assertIn('scripts/release_notes.py "$TAG" \\', android)
        self.assertIn("if: steps.notes.outputs.publish == 'true'", daily)
        self.assertIn('gh release create "android-internal-$BUILD_CODE"', daily)
        self.assertIn('--target "$SOURCE_SHA" --prerelease', daily)
        self.assertIn('needs: [plan, publish, github-notes]', daily)


if __name__ == '__main__':
    unittest.main()
