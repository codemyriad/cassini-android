#!/usr/bin/env python3
"""Reject authored colour values in production Android sources (HARMONY 10.1)."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GENERATED = {'ui/src/main/kotlin/io/codemyriad/cassini/ui/theme/BrandTokenValues.kt'}
PATTERN = re.compile(
    r'\bColor\s*\(\s*(?:0[xX][0-9a-fA-F]+|\d+(?:\.\d+)?[fF]?\s*,|(?:red|green|blue)\s*=)'
    r'|\bColor\.(?:Black|White|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\b'
    r'|\b(?:Color|android\.graphics\.Color)\.(?:rgb|argb|parseColor)\s*\('
    r'|#[0-9a-fA-F]{3,8}(?![0-9a-fA-F])'
    r'|@android:color/(?:black|white|holo_\w+|darker_gray)\b'
)
COMMENTS = re.compile(r'"(?:\\.|[^"\\])*"|/\*.*?\*/|<!--.*?-->|//[^\n]*', re.DOTALL)


def violations(root):
    failures = []
    for module in root.iterdir():
        source = module / 'src/main'
        if not source.is_dir():
            continue
        for path in sorted(source.rglob('*')):
            relative = path.relative_to(root).as_posix()
            if path.suffix not in {'.kt', '.java', '.xml', '.css'} or relative in GENERATED:
                continue
            # Keep line numbers when dropping comments so diagnostics point at source lines.
            text = COMMENTS.sub(lambda m: m[0] if m[0].startswith('"') else '\n' * m[0].count('\n'), path.read_text())
            for match in PATTERN.finditer(text):
                line = text.count('\n', 0, match.start()) + 1
                failures.append(f'{relative}:{line}: {match[0]}')
    return failures


def main():
    failures = violations(ROOT)
    if failures:
        raise SystemExit('Authored colours outside generated token files:\n' + '\n'.join(failures))


if __name__ == '__main__':
    main()
