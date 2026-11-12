"""The literal guard must detect new colours even when the generated token file is unchanged."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('brand_literals', ROOT / 'scripts/check-brand-color-literals.py')
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


class BrandColorLiteralTest(unittest.TestCase):
    def test_every_supported_literal_form_fails_in_production(self):
        forms = ['Color(0xFFFF0000)', 'Color(color = 0xFFFF0000)', 'Color.RED', 'rgb(255, 0, 0)',
                 'Color(1f, 0f, 0f)', 'Color(red = 1f, green = 0f, blue = 0f)', 'Color(4294901760L)',
                 'colorResource(android.R.color.white)',
                 'Color.Red', 'android.graphics.Color.rgb(255, 0, 0)', 'Color.parseColor("red")',
                 '<color name="oops">#F00</color>', '@android:color/white',
                 'val url = "https://example.com"; val ink = Color.Red']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'app/src/main/Oops.kt'
            source.parent.mkdir(parents=True)
            for form in forms:
                with self.subTest(form=form):
                    source.write_text(form)
                    self.assertTrue(GUARD.violations(root))

    def test_generated_values_comments_and_test_fixtures_are_not_app_colours(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, text in [
                (next(iter(GUARD.GENERATED)), 'Color(0xFFFF0000)'),
                ('app/src/test/Fixture.kt', 'Color.Red'),
                ('app/src/main/Roles.kt', '// Old #FF0000\nval ink = colors.ink\n/* Color.White */'),
            ]:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            self.assertEqual([], GUARD.violations(root))

    def test_authored_theme_files_are_not_exempt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'ui/src/main/kotlin/io/codemyriad/cassini/ui/theme/Color.kt'
            source.parent.mkdir(parents=True)
            source.write_text('// A comment\nval ink = Color.White')
            failures = GUARD.violations(root)
            self.assertEqual(1, len(failures))
            self.assertIn('Color.kt:2:', failures[0])

    def test_release_and_debug_sources_cannot_hide_colours(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for source_set in ('release', 'debug'):
                source = root / f'app/src/{source_set}/Oops.kt'
                source.parent.mkdir(parents=True)
                source.write_text('val ink = Color.White')
            self.assertEqual(2, len(GUARD.violations(root)))
