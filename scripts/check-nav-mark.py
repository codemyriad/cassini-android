#!/usr/bin/env python3
"""Verify the approved Saturn SVG sources and every density export Android draws (saturn-nav, saturn-mid, launcher)."""
import hashlib
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Mark -> (receipt, source SVG, resource path template, pixel size per density). saturn-nav is 24 dp tall in the
# search field; saturn-mid is 56 dp tall on the model download screen; both keep the 184x120 viewBox. The launcher
# foreground (D-1175) is saturn-nav placed on the 108 dp adaptive-icon canvas, so it is 108 dp square.
MARKS = {
    'saturn-nav': ('scripts/nav-mark.json', 'saturn-nav', 'ui/src/main/res/drawable-{density}/ui_saturn_nav.png',
                   {'mdpi': (37, 24), 'hdpi': (56, 36), 'xhdpi': (74, 48), 'xxhdpi': (111, 72), 'xxxhdpi': (148, 96)}),
    'saturn-mid': ('scripts/mid-mark.json', 'saturn-mid', 'ui/src/main/res/drawable-{density}/ui_saturn_mid.png',
                   {'mdpi': (86, 56), 'hdpi': (129, 84), 'xhdpi': (172, 112), 'xxhdpi': (258, 168),
                    'xxxhdpi': (344, 224)}),
    'launcher': ('scripts/launcher-mark.json', 'saturn-nav',
                 'ui/src/main/res/mipmap-{density}/ui_ic_launcher_foreground.png',
                 {'mdpi': (108, 108), 'hdpi': (162, 162), 'xhdpi': (216, 216), 'xxhdpi': (324, 324),
                  'xxxhdpi': (432, 432)}),
}


def check_mark(root, mark, receipt_path, source_name, template, wanted):
    receipt = json.loads((root / receipt_path).read_text())
    source = root / f'third_party/cassini-ux/brand/mark/{source_name}.svg'
    if hashlib.sha256(source.read_bytes()).hexdigest() != receipt['source_sha256']:
        raise ValueError(f'{source_name} SVG changed: regenerate the {mark} Android exports')
    images = receipt['images']
    if len(images) != len(wanted) or {row['density'] for row in images} != wanted.keys():
        raise ValueError(f'Missing {mark} mark density')
    for row in images:
        density = row['density']
        path = template.format(density=density)
        if row['path'] != path:
            raise ValueError(f'Unexpected {mark} mark path')
        raw = (root / path).read_bytes()
        if (not raw.startswith(b'\x89PNG\r\n\x1a\n') or len(raw) > 256_000
                or hashlib.sha256(raw).hexdigest() != row['sha256']
                or struct.unpack('>II', raw[16:24]) != wanted[density]):
            raise ValueError(f'{mark} mark export drifted: {density}')


def check(root):
    for mark, (receipt_path, source_name, template, wanted) in MARKS.items():
        check_mark(root, mark, receipt_path, source_name, template, wanted)


if __name__ == '__main__':
    check(ROOT)
