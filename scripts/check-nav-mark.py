#!/usr/bin/env python3
"""Verify the approved Saturn SVG sources and every density export Android draws (saturn-nav, saturn-mid)."""
import hashlib
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Mark -> (receipt, pixel size per density). saturn-nav is 24 dp tall in the search field; saturn-mid is 56 dp
# tall on the model download screen. Both keep the 184x120 viewBox.
MARKS = {
    'nav': ('scripts/nav-mark.json', {'mdpi': (37, 24), 'hdpi': (56, 36), 'xhdpi': (74, 48),
                                      'xxhdpi': (111, 72), 'xxxhdpi': (148, 96)}),
    'mid': ('scripts/mid-mark.json', {'mdpi': (86, 56), 'hdpi': (129, 84), 'xhdpi': (172, 112),
                                      'xxhdpi': (258, 168), 'xxxhdpi': (344, 224)}),
}


def check_mark(root, mark, receipt_path, wanted):
    receipt = json.loads((root / receipt_path).read_text())
    source = root / f'third_party/cassini-ux/brand/mark/saturn-{mark}.svg'
    if hashlib.sha256(source.read_bytes()).hexdigest() != receipt['source_sha256']:
        raise ValueError(f'saturn-{mark} SVG changed: regenerate its Android exports')
    images = receipt['images']
    if len(images) != len(wanted) or {row['density'] for row in images} != wanted.keys():
        raise ValueError(f'Missing saturn-{mark} mark density')
    for row in images:
        density = row['density']
        path = f'ui/src/main/res/drawable-{density}/ui_saturn_{mark}.png'
        if row['path'] != path:
            raise ValueError(f'Unexpected saturn-{mark} mark path')
        raw = (root / path).read_bytes()
        if (not raw.startswith(b'\x89PNG\r\n\x1a\n') or len(raw) > 256_000
                or hashlib.sha256(raw).hexdigest() != row['sha256']
                or struct.unpack('>II', raw[16:24]) != wanted[density]):
            raise ValueError(f'saturn-{mark} mark export drifted: {density}')


def check(root):
    for mark, (receipt_path, wanted) in MARKS.items():
        check_mark(root, mark, receipt_path, wanted)


if __name__ == '__main__':
    check(ROOT)
