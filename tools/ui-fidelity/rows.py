#!/usr/bin/env python3
"""Report the vertical offsets between two screenshots, for example a design board and a golden.

In the column range [--x0, --x1) px of each image, a row is "dark" when one of its pixels has a luma
below --dark. Runs of dark rows (text lines, icons, rules) taller than --min-height px are listed for
both images, then paired in order, with the offset of each pair's top edge in px and dp.

Pillow is the only dependency. Run it with "python3 -I" from any directory. See README.md.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

SCALE = 2  # px per dp
DARK = 150  # luma below which a pixel counts as dark
MIN_HEIGHT = 4  # px; shorter runs are anti-aliasing or hairlines


def dark_runs(
    path: Path, x0: int, x1: int, dark: int = DARK, min_height: int = MIN_HEIGHT, y0: int = 0, y1: int | None = None
) -> list[tuple[int, int]]:
    """Inclusive (top, bottom) px of each run of rows that contain a dark pixel in [x0, x1)."""
    with Image.open(path) as im:
        luma = im.convert("L")
    bottom = luma.height if y1 is None else min(y1, luma.height)
    x1 = min(x1, luma.width)
    if x0 >= x1 or y0 >= bottom:
        return []
    mask = luma.crop((x0, y0, x1, bottom)).point(lambda v: 255 if v < dark else 0)
    width = mask.width
    runs: list[tuple[int, int]] = []
    start: int | None = None
    for y in range(mask.height):
        hit = mask.crop((0, y, width, y + 1)).getbbox() is not None
        if hit and start is None:
            start = y
        elif not hit and start is not None:
            runs.append((y0 + start, y0 + y - 1))
            start = None
    if start is not None:
        runs.append((y0 + start, y0 + mask.height - 1))
    return [(top, bot) for top, bot in runs if bot - top + 1 >= min_height]


def report(a: Path, b: Path, runs_a: list[tuple[int, int]], runs_b: list[tuple[int, int]]) -> str:
    lines = [f"A: {a.name}: {len(runs_a)} runs", f"B: {b.name}: {len(runs_b)} runs"]
    if len(runs_a) != len(runs_b):
        lines.append("Run counts differ: the pairs below may be shifted; narrow --x0/--x1 or --y0/--y1.")
    lines.append(f"{'#':>3}  {'A top-bottom px':>16}  {'B top-bottom px':>16}  {'top offset':>16}  {'height diff':>11}")
    for i in range(max(len(runs_a), len(runs_b))):
        ra = runs_a[i] if i < len(runs_a) else None
        rb = runs_b[i] if i < len(runs_b) else None
        sa = f"{ra[0]}-{ra[1]}" if ra else "-"
        sb = f"{rb[0]}-{rb[1]}" if rb else "-"
        if ra and rb:
            off = rb[0] - ra[0]
            dh = (rb[1] - rb[0]) - (ra[1] - ra[0])
            lines.append(f"{i + 1:>3}  {sa:>16}  {sb:>16}  {off:+5d} px {off / SCALE:+6.1f} dp  {dh:+8d} px")
        else:
            lines.append(f"{i + 1:>3}  {sa:>16}  {sb:>16}")
    return "\n".join(lines)


def self_test() -> int:
    failures: list[str] = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    with tempfile.TemporaryDirectory(prefix="ui-fidelity-rows-") as tmp:
        root = Path(tmp)
        a = Image.new("RGB", (200, 300), (245, 246, 247))
        b = a.copy()
        da, db = ImageDraw.Draw(a), ImageDraw.Draw(b)
        for top in (20, 80, 150):
            da.rectangle((30, top, 120, top + 15), fill=(20, 23, 26))
            db.rectangle((30, top + 4, 120, top + 19), fill=(20, 23, 26))
        da.rectangle((30, 200, 120, 201), fill=(20, 23, 26))  # a 2 px hairline: filtered out
        da.rectangle((150, 240, 190, 260), fill=(0, 0, 0))  # outside the x range
        db.rectangle((30, 280, 120, 299), fill=(74, 82, 91))  # touches the bottom edge
        da.rectangle((30, 120, 120, 130), fill=(200, 200, 200))  # light: not dark
        pa, pb = root / "a.png", root / "b.png"
        a.save(pa)
        b.save(pb)

        ra = dark_runs(pa, 0, 140)
        rb = dark_runs(pb, 0, 140)
        check(ra == [(20, 35), (80, 95), (150, 165)], f"runs A {ra}")
        check(rb == [(24, 39), (84, 99), (154, 169), (280, 299)], f"runs B {rb}")
        check(dark_runs(pa, 0, 140, min_height=1)[-1] == (200, 201), "hairline with min height 1")
        check(dark_runs(pa, 140, 200) == [(240, 260)], "x range")
        check(dark_runs(pa, 0, 140, y0=70, y1=100) == [(80, 95)], "y range")
        text = report(pa, pb, ra, rb)
        check(text.count("+4 px   +2.0 dp") == 3, f"offsets:\n{text}")
        check("Run counts differ" in text, "count warning")

        with contextlib.redirect_stdout(io.StringIO()) as log:
            code = main([str(pa), str(pb), "--x0", "0", "--x1", "140"])
        check(code == 0 and "+4 px" in log.getvalue(), f"main exit {code}")

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    print("rows.py self-test: " + ("FAILED" if failures else "passed"))
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("a", nargs="?", type=Path, help="first PNG (for example the design board)")
    parser.add_argument("b", nargs="?", type=Path, help="second PNG (for example the golden)")
    parser.add_argument("--x0", type=int, default=0, help="first column, px (default: 0)")
    parser.add_argument("--x1", type=int, default=None, help="end column, px, exclusive (default: image width)")
    parser.add_argument("--y0", type=int, default=0, help="first row, px (default: 0)")
    parser.add_argument("--y1", type=int, default=None, help="end row, px, exclusive (default: image height)")
    parser.add_argument("--dark", type=int, default=DARK, help=f"luma threshold (default: {DARK})")
    parser.add_argument("--min-height", type=int, default=MIN_HEIGHT, help=f"px (default: {MIN_HEIGHT})")
    parser.add_argument("--self-test", action="store_true", help="run the self-test on generated images")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if args.a is None or args.b is None:
        parser.error("two PNG files are required")
    for path in (args.a, args.b):
        if not path.is_file():
            parser.error(f"file not found: {path}")
    x1 = args.x1 if args.x1 is not None else 1 << 30
    runs_a = dark_runs(args.a, args.x0, x1, args.dark, args.min_height, args.y0, args.y1)
    runs_b = dark_runs(args.b, args.x0, x1, args.dark, args.min_height, args.y0, args.y1)
    print(report(args.a, args.b, runs_a, runs_b))
    return 0


if __name__ == "__main__":
    sys.exit(main())
