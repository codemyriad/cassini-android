#!/usr/bin/env python3
"""Rasterize a vendored Saturn SVG with headless Chrome, keeping its gradients, filters and transparency.

Usage: render-mark-chrome.py <svg> <png> <pixel-width> <pixel-height>. Chrome draws the unchanged SVG at the
output size in the top-left corner of a larger transparent page (headless Chrome enforces a minimum window
size), then ImageMagick crops that box out. No vector importer approximates the mark.
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def render(svg, png, width, height):
    chrome = next(filter(None, map(shutil.which, ('google-chrome', 'chromium', 'chromium-browser'))), None)
    if chrome is None:
        raise SystemExit('Chrome not found')
    page = (f'<!doctype html><html><head><style>html,body{{margin:0;padding:0;background:transparent;'
            f'overflow:hidden}}svg{{display:block;width:{width}px;'
            f'height:{height}px}}</style></head><body>{Path(svg).read_text()}</body></html>')
    with tempfile.TemporaryDirectory() as temp:
        html = Path(temp, 'mark.html')
        html.write_text(page)
        out = Path(temp, 'mark.png')
        subprocess.run([chrome, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
                        f'--user-data-dir={temp}/profile', '--force-device-scale-factor=1',
                        '--default-background-color=00000000', f'--window-size={max(width, 800)},{max(height, 600)}',
                        f'--screenshot={out}', html.as_uri()],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['convert', out, '-crop', f'{width}x{height}+0+0', '+repage', '-strip', f'PNG32:{png}'],
                       check=True)
    os.chmod(png, 0o644)


if __name__ == '__main__':
    render(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
