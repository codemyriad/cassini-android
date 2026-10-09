#!/usr/bin/env bash
# Checks the relative links in the Markdown files of the repository, without network access.
#
# With lychee installed, it runs `lychee --offline`. Otherwise a small Python checker reads each file,
# ignores fenced code blocks and inline code, and makes sure that every relative link target
# ([text](path), [label]: path, <img src="path">, <a href="path">) exists. It does not check web links
# or #fragments.
#
# Usage: scripts/check-links.sh [file.md...]
#   With no arguments it checks every tracked or new (not ignored) Markdown file, except the APK copies
#   under app/src/main/assets/. Those copies are equal to the files at the repository root (LicenseAssetsTest),
#   and their links resolve from the root, so the script checks the root files.
# Exits 0 when every link resolves, 1 when a link is broken.
set -euo pipefail
files=()
for file in "$@"; do
    files+=("$(realpath -- "$file")")
done
cd "$(dirname "$0")/.."

if ((${#files[@]} == 0)); then
    while IFS= read -r -d '' file; do
        files+=("$file")
    done < <(git ls-files -z --cached --others --exclude-standard -- '*.md' ':!:app/src/main/assets/**')
fi
if ((${#files[@]} == 0)); then
    printf 'No Markdown files to check.\n'
    exit 0
fi

if command -v lychee >/dev/null 2>&1; then
    exec lychee --offline --no-progress --root-dir "$PWD" -- "${files[@]}"
fi

python3 -I - "${files[@]}" <<'PY'
import os
import re
import sys
from urllib.parse import unquote

root = os.getcwd()
inline = re.compile(r'!?\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)')
reference = re.compile(r'^\s{0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+.*)?$')
html = re.compile(r'<(?:img|a|source)\b[^>]*?\b(?:src|href)\s*=\s*"([^"]+)"', re.IGNORECASE)
code_span = re.compile(r'(`+)(?:(?!\1).)+?\1')
fence = re.compile(r'^\s{0,3}(```|~~~)')


def external(target):
    return re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', target) is not None or target.startswith('//')


broken = 0
checked = 0
for name in sys.argv[1:]:
    base = os.path.dirname(os.path.abspath(name))
    in_fence = None
    with open(name, encoding='utf-8') as handle:
        for number, line in enumerate(handle, 1):
            marker = fence.match(line)
            if marker:
                if in_fence is None:
                    in_fence = marker.group(1)
                elif marker.group(1) == in_fence:
                    in_fence = None
                continue
            if in_fence is not None:
                continue
            text = code_span.sub('', line)
            targets = inline.findall(text) + html.findall(text)
            ref = reference.match(text)
            if ref:
                targets.append(ref.group(1))
            for target in targets:
                if external(target) or target.startswith('#'):
                    continue
                path = unquote(target.split('#', 1)[0].split('?', 1)[0])
                if not path:
                    continue
                if path.startswith('/'):
                    resolved = os.path.join(root, path.lstrip('/'))
                else:
                    resolved = os.path.join(base, path)
                checked += 1
                if not os.path.exists(resolved):
                    broken += 1
                    print(f'{name}:{number}: broken link to {target}')

print(f'Checked {checked} relative links in {len(sys.argv) - 1} files: {broken} broken.')
sys.exit(1 if broken else 0)
PY
