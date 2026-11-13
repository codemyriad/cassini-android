#!/usr/bin/env python3
"""Verify the approved SVG source and every density export used by Android."""
import hashlib
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def check(root):
    receipt = json.loads((root / 'scripts/nav-mark.json').read_text())
    source = root / 'third_party/cassini-ux/brand/mark/saturn-nav.svg'
    if hashlib.sha256(source.read_bytes()).hexdigest() != receipt['source_sha256']:
        raise ValueError('Navigation SVG changed: regenerate its Android exports')
    wanted = {'mdpi': (37, 24), 'hdpi': (56, 36), 'xhdpi': (74, 48),
              'xxhdpi': (111, 72), 'xxxhdpi': (148, 96)}
    images = receipt['images']
    if len(images) != len(wanted) or {row['density'] for row in images} != wanted.keys():
        raise ValueError('Missing navigation mark density')
    for row in images:
        density = row['density']
        path = f'ui/src/main/res/drawable-{density}/ui_saturn_nav.png'
        if row['path'] != path:
            raise ValueError('Unexpected navigation mark path')
        raw = (root / path).read_bytes()
        if (not raw.startswith(b'\x89PNG\r\n\x1a\n') or len(raw) > 256_000
                or hashlib.sha256(raw).hexdigest() != row['sha256']
                or struct.unpack('>II', raw[16:24]) != wanted[density]):
            raise ValueError(f'Navigation mark export drifted: {density}')


if __name__ == '__main__':
    check(ROOT)
