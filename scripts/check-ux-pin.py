#!/usr/bin/env python3
"""Check every vendored Phase 1 contract input against its recorded byte pin."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENDOR = Path('third_party/cassini-ux')
# Phase 2 inputs pinned at their own cassini-ux commit. Each must name that commit explicitly; every other input
# shares the bundle commit. phase2/strings.json carries L-67 phone nouns (D-1048).
# wave5/strings.json (D-975) carries the D98 first-run hero and privacy-line copy;
# contract/import-types.json (D-1030) has its own contract pin. The bundle itself moved for L-10 (D-1035).
OWN_COMMIT = {'wave5/strings.json', 'contract/import-types.json', 'phase2/strings.json'}


def check(root):
    pin = json.loads((root / VENDOR / 'UPSTREAM.json').read_text())
    if not re.fullmatch(r'[0-9a-f]{40}', pin['commit']):
        raise ValueError('Invalid UX commit')
    required = {"brand/tokens.json", "brand/mark/saturn-nav.svg", "brand/mark/saturn-mid.svg",
                "strings.json",
                "vectors/states/states-vectors.json", "contract/STATUS-BADGES.md", "contract/HARMONY.md"}
    if not required <= pin["files"].keys():
        raise ValueError("Required Phase 1 pin missing")
    for name, entry in pin['files'].items():
        if Path(name).is_absolute() or '..' in Path(name).parts:
            raise ValueError('Unsafe vendor path')
        if name in OWN_COMMIT:
            if not re.fullmatch(r'[0-9a-f]{40}', entry.get('commit', '')):
                raise ValueError(f'Separately pinned input needs its own commit: {name}')
        elif entry.get('commit', pin['commit']) != pin['commit']:
            raise ValueError('Phase 1 inputs must share one commit')
        raw = (root / VENDOR / name).read_bytes()
        if hashlib.sha256(raw).hexdigest() != entry['sha256']:
            raise ValueError(f'Vendored bytes drifted: {name}')


if __name__ == '__main__':
    check(ROOT)
