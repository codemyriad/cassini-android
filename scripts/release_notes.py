"""User-written release fragments; developer titles never supply store text."""

from __future__ import annotations

import argparse
import html
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from play_changelog import utf16_units  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
FALLBACK = 'Fixes and improvements under the hood. Nothing changes in how you use Cassini.'
KINDS = {'new': 'New', 'better': 'Better', 'fixed': 'Fixed'}
SLUG = re.compile(r'[a-z0-9][a-z0-9-]*\.md')
TECHNICAL = re.compile(
    r'#\d+\b|\bD\d+(?:\.\d+)*\b|\b(?:CI|PRs?|pull requests?|commits?|refactors?|tooling|tests?|please)\b|'
    r'\b[\w-]+\.(?:kt|java|py|swift|json|ya?ml|md|sh|cpp)\b', re.I,
)


def validate(text: str) -> str:
    text = re.sub(r"(?<=\w)'(?=\w)", '’', text.strip())
    if not text or TECHNICAL.search(text) or re.search(r'[`<>]|https?://|\]\(', text):
        raise ValueError('Release notes must describe the user-visible change, without developer references')
    return text


def fragments(root: Path, baseline: str | None = None) -> list[tuple[str, str]]:
    directory = root / 'release-notes.d'
    if directory.is_symlink():
        raise ValueError('Fragment directory must not be a symlink')
    previous = set()
    if baseline and not re.fullmatch(r'[0-9a-f]{40}', baseline):
        raise ValueError('Invalid publication commit')
    if baseline:
        result = subprocess.check_output(
            ['git', 'ls-tree', '-r', '--name-only', baseline, '--', 'release-notes.d/'],
            cwd=root, text=True, timeout=60,
        )
        previous = set(result.splitlines())
    result = []
    for file in sorted(directory.glob('*.md')):
        if file.name == 'README.md':
            continue
        if not SLUG.fullmatch(file.name) or file.is_symlink() or not file.is_file():
            raise ValueError(f'Invalid fragment path: {file.name}')
        raw = file.read_bytes()
        relative = file.relative_to(root).as_posix()
        if relative in previous:
            old = subprocess.check_output(['git', 'show', f'{baseline}:{relative}'], cwd=root, timeout=60)
            if old == raw:
                continue
        header, separator, body = raw.decode('utf-8').partition('\n---\n')
        if not separator or header not in {f'kind: {kind}' for kind in KINDS}:
            raise ValueError(f'Invalid fragment header: {file.name}')
        text = validate(' '.join(body.split()))
        if utf16_units(text) > 450:
            raise ValueError(f'Fragment is too long: {file.name}')
        result.append((header.removeprefix('kind: '), text))
    return result


def render(items: list[tuple[str, str]]) -> str:
    def grouped(selected):
        return '\n\n'.join(heading + '\n' + '\n'.join('• ' + text for kind, text in selected if kind == key)
                           for key, heading in KINDS.items() if any(kind == key for kind, _ in selected))

    ordered = [(kind, validate(text)) for kind in KINDS for key, text in items if key == kind]
    if len(ordered) != len(items):
        raise ValueError('Unknown fragment kind')
    if not ordered:
        return FALLBACK
    whole = grouped(ordered)
    if utf16_units(whole) <= 500:
        return whole
    selected = []
    suffix = '\n\nand smaller fixes'
    for item in ordered:
        if utf16_units(grouped([*selected, item]) + suffix) > 500:
            break
        selected.append(item)
    if not selected:
        raise ValueError('A fragment cannot fit the store limit')
    return grouped(selected) + suffix


def store_notes(text: str) -> str:
    text = validate(text)
    if utf16_units(text) > 500:
        raise ValueError('User release notes exceed 500 UTF-16 units')
    return text


def release_body(user: str, developer: str) -> str:
    body = (store_notes(user) + '\n\n<details>\n<summary>Developer changelog</summary>\n\n' +
            html.escape(developer, quote=False).strip() + '\n\n</details>\n')
    if len(body.encode('utf-8')) > 60_000:
        raise ValueError('Complete GitHub notes exceed the release plan limit')
    return body


def curated(root: Path, version: str) -> str:
    version = version.removeprefix('v')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?', version):
        raise ValueError('Invalid release version')
    file = root / 'release-notes' / f'{version}.md'
    if file.parent.is_symlink() or file.is_symlink():
        raise ValueError('Curated notes must not be a symlink')
    if file.exists():
        return store_notes(file.read_text())
    if '-' not in version:
        raise ValueError(f'Stable release requires curated release-notes/{version}.md')
    return render(fragments(root))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--play-code', type=int)
    parser.add_argument('--link-base')
    args = parser.parse_args()
    user = curated(ROOT, args.version)
    if args.play_code is not None:
        directory = ROOT / 'metadata/en-US/changelogs'
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f'{args.play_code}.txt').write_text(user)
    elif not args.check:
        command = [sys.executable, '-I', str(ROOT / 'scripts/changelog-section.py'), args.version]
        if args.link_base:
            command += ['--link-base', args.link_base]
        developer = subprocess.check_output(command, text=True, timeout=60)
        print(release_body(user, developer), end='')


if __name__ == '__main__':
    main()
