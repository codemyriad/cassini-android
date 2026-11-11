"""Play's plain-text and UTF-16 boundaries, including the real APK/AAB staging guard."""

import contextlib
import importlib.util
import io
import json
import os
import runpy
import sys
import tempfile
import types
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('play_notes', ROOT / 'scripts/play_changelog.py')
PLAY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PLAY)
EMOJI = '\U0001f600'


class PlayChangelogTests(unittest.TestCase):
    def test_generic_titles_are_plain_text_and_complete_input_stays_unchanged(self):
        source = 'Fix StateFlow<SettingsUiState> and <cause>'
        self.assertEqual('Fix StateFlow[SettingsUiState] and [cause]', PLAY.format_play_notes(source))
        self.assertEqual('Fix StateFlow<SettingsUiState> and <cause>', source)

    def test_supplementary_characters_at_the_500_unit_boundary_are_not_split(self):
        for source in ('A' * 500, 'A' * 498 + EMOJI, EMOJI * 250):
            self.assertEqual(500, PLAY.utf16_units(source))
            self.assertEqual(source, PLAY.format_play_notes(source))
        for source in ('A' * 499 + EMOJI, EMOJI * 251, 'A' * 495 + EMOJI * 4):
            result = PLAY.format_play_notes(source)
            self.assertLessEqual(len(result.encode('utf-16-le')) // 2, 500)
            self.assertTrue(result.endswith('...'))
            self.assertEqual(result, result.encode('utf-16-le').decode('utf-16-le'))

    def test_the_real_packager_accepts_500_utf16_units_and_refuses_502(self):
        # Native checks are separate existing tests. Exercise the actual staging script without real binaries or keys.
        compatibility = types.ModuleType('apk_compatibility')
        compatibility.check_apk_compatibility = lambda *_: 0
        models = types.ModuleType('apk_models')
        models.check_apk_models = lambda *_: None
        models.whistle_model_pins = lambda: {}
        digest = 'a' * 64
        def tool(args, **_):
            if str(args[0]).endswith('apksigner'):
                return 'Signer #1 certificate SHA-256 digest: ' + digest + '\n'
            if args[0] == 'keytool':
                return 'SHA256: ' + digest + '\n'
            return '<manifest />'
        for count in (250, 251):
            with self.subTest(emoji=count), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                apk_dir = root / 'app/build/outputs/apk/release'
                apk_dir.mkdir(parents=True)
                with zipfile.ZipFile(apk_dir / 'app.apk', 'w') as apk:
                    apk.writestr('assets/licenses/Cassini-GPL-3.0.txt', 'License')
                    apk.writestr('assets/licenses/THIRD_PARTY_NOTICES.md', 'Notices')
                (apk_dir / 'output-metadata.json').write_text(json.dumps({'elements': [
                    {'filters': [], 'versionName': '0.4.2-beta.4', 'versionCode': 19, 'outputFile': 'app.apk'},
                ]}))
                for name, text in {
                    'scripts/release-signing-cert.sha256': digest,
                    'app/build/outputs/mapping/release/mapping.txt': 'Mapping',
                    'app/build/outputs/bundle/release/app-release.aab': 'Synthetic bundle',
                    'THIRD_PARTY_NOTICES.md': 'Notices',
                    'metadata/en-US/changelogs/19.txt': EMOJI * count,
                }.items():
                    file = root / name
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text(text)
                previous = Path.cwd()
                try:
                    os.chdir(root)
                    modules = {'apk_compatibility': compatibility, 'apk_models': models, 'play_changelog': PLAY}
                    with patch.dict(sys.modules, modules), \
                            patch.dict(os.environ, {'IS_TAG': 'true', 'TAG': 'v0.4.2-beta.4',
                                                    'APK_VARIANT': 'release', 'ANDROID_HOME': str(root / 'sdk')}), \
                            patch('subprocess.check_output', side_effect=tool), \
                            contextlib.redirect_stdout(io.StringIO()):
                        if count == 251:
                            with self.assertRaisesRegex(SystemExit, '500 UTF-16 units'):
                                runpy.run_path(str(ROOT / 'scripts/package-apk.py'))
                        else:
                            runpy.run_path(str(ROOT / 'scripts/package-apk.py'))
                            self.assertEqual(EMOJI * count, (root / 'play/whatsnew/whatsnew-en-US').read_text())
                finally:
                    os.chdir(previous)


if __name__ == '__main__':
    unittest.main()
