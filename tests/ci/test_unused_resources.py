"""Every app resource is used by shipping code, not only by tests (D-1073).

PR and main CI lint the Debug variant. AGP lints unit-test sources only for Debug, so a string referenced only from a
test passes `lintDebug` but fails `UnusedResources` in the `lintRelease` of the daily internal test release. This check
does what release lint does, without compiling the release variant: it looks for references in main and debug sources
only. A resource kept on purpose carries tools:ignore="UnusedResources", exactly as release lint expects.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VALUE = re.compile(r'<(string|plurals|color|dimen|array|string-array|integer|bool|style)\s+name="([^"]+)"([^>]*)>')
FILE_TYPES = ('drawable', 'mipmap', 'raw', 'xml', 'font', 'layout', 'anim', 'menu')
SOURCE_SETS = ('main', 'debug')


def module_dirs():
    return sorted(p.parents[2] for p in ROOT.glob('*/src/main/AndroidManifest.xml'))


def declared(module):
    res = module / 'src/main/res'
    found = {}
    for path in res.glob('values/*.xml'):
        for kind, name, attributes in VALUE.findall(path.read_text()):
            if 'UnusedResources' not in attributes:
                found[({'string-array': 'array'}.get(kind, kind), name)] = path
    for path in res.glob('*/*'):
        kind = path.parent.name.split('-')[0]
        if kind in FILE_TYPES and path.is_file() and 'UnusedResources' not in path.read_text(errors='ignore'):
            found[(kind, path.name.split('.')[0])] = path
    return found


def shipping_sources():
    text = []
    for module in module_dirs():
        for source_set in SOURCE_SETS:
            for path in (module / 'src' / source_set).rglob('*'):
                if not path.is_file() or path.suffix not in ('.kt', '.java', '.xml'):
                    continue
                content = path.read_text(errors='ignore')
                if path.parent.name.startswith('values'):
                    # A declaration is not a use; references inside values (styles, aliases) are.
                    content = VALUE.sub('', content)
                text.append(content)
    return '\n'.join(text)


def is_used(kind, name, sources):
    field = name.replace('.', '_')
    if re.search(rf'R\.{kind}\.{re.escape(field)}\b', sources):
        return True
    if re.search(rf'@{kind}/{re.escape(name)}\b', sources):
        return True
    if kind == 'style':
        # A style is also used as an explicit parent, or implicitly as the prefix of a dotted child style.
        return bool(re.search(rf'parent="(@style/)?{re.escape(name)}"|name="{re.escape(name)}\.', sources))
    return False


class UnusedResourcesTest(unittest.TestCase):
    def test_every_main_resource_is_referenced_outside_tests(self):
        sources = shipping_sources()
        unused = sorted(
            f'{path.relative_to(ROOT)}: R.{kind}.{name}'
            for module in module_dirs()
            for (kind, name), path in declared(module).items()
            if not is_used(kind, name, sources)
        )
        self.assertEqual([], unused, 'Delete these resources (and their generator entries) or wire them into the UI')

    def test_a_test_only_reference_does_not_count(self):
        sources = 'stringResource(R.string.ui_shown)\n<item name="x">@color/ui_ink</item>'
        self.assertTrue(is_used('string', 'ui_shown', sources))
        self.assertTrue(is_used('color', 'ui_ink', sources))
        self.assertFalse(is_used('string', 'ui_shown_body', sources))


if __name__ == '__main__':
    unittest.main()
