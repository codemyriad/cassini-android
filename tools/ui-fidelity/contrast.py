#!/usr/bin/env python3
"""WCAG 2.x contrast ratios of colour pairs.

Each argument pair is FOREGROUND BACKGROUND. A colour is "#RRGGBB" (or "RRGGBB") or a pixel of a PNG,
written as "file.png@x,y" (px), to measure a colour on a design board or a golden. The verdict is
"AA" (>= 4.5, text), "AA-large/UI" (>= 3.0, large text and non-text) or "FAIL". With --require N the
exit code is 1 when any pair is below N.

The token pairs of ui.md section 11 are enforced by ContrastTest in :ui; this script is for one-off
checks. Pillow is needed only for "file.png@x,y". Run it with "python3 -I". See README.md.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import re
import sys
import tempfile
from pathlib import Path

HEX = re.compile(r"#?([0-9a-fA-F]{6})")
PICK = re.compile(r"(.+)@(\d+),(\d+)")


def parse_colour(spec: str) -> tuple[int, int, int]:
    match = HEX.fullmatch(spec)
    if match:
        value = int(match.group(1), 16)
        return (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF
    match = PICK.fullmatch(spec)
    if match:
        from PIL import Image

        with Image.open(match.group(1)) as im:
            r, g, b = im.convert("RGB").getpixel((int(match.group(2)), int(match.group(3))))
        return r, g, b
    raise ValueError(f"not a colour: {spec!r} (use #RRGGBB or file.png@x,y)")


def luminance(rgb: tuple[int, int, int]) -> float:
    def channel(c: int) -> float:
        s = c / 255
        return s / 12.92 if s <= 0.04045 else ((s + 0.055) / 1.055) ** 2.4

    r, g, b = (channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(fg: tuple[int, int, int], bg: tuple[int, int, int]) -> float:
    hi, lo = sorted((luminance(fg), luminance(bg)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def verdict(value: float) -> str:
    if value >= 4.5:
        return "AA"
    if value >= 3.0:
        return "AA-large/UI"
    return "FAIL"


def hex_of(rgb: tuple[int, int, int]) -> str:
    return "#{:02X}{:02X}{:02X}".format(*rgb)


def self_test() -> int:
    failures: list[str] = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    def r2(fg: str, bg: str) -> float:
        return round(ratio(parse_colour(fg), parse_colour(bg)), 2)

    # Values from the ui.md section 11 table.
    check(r2("#000000", "#FFFFFF") == 21.0, "black on white")
    check(r2("#14171A", "#F5F6F7") == 16.63, f"ink on bg {r2('#14171A', '#F5F6F7')}")
    check(r2("#687180", "#F5F6F7") == 4.55, f"text4 on bg {r2('#687180', '#F5F6F7')}")
    check(r2("#6B7480", "#F5F6F7") == 4.38, f"board text4 on bg {r2('#6B7480', '#F5F6F7')}")
    check(r2("FFFFFF", "#C2410C") == 5.18, f"white on red {r2('FFFFFF', '#C2410C')}")
    check(r2("#F5F6F7", "#14171A") == 16.63, "order does not matter")
    check([verdict(v) for v in (4.5, 4.49, 3.0, 2.99)] == ["AA", "AA-large/UI", "AA-large/UI", "FAIL"], "verdicts")
    try:
        parse_colour("#12345")
        failures.append("a short hex was accepted")
    except ValueError:
        pass

    try:
        from PIL import Image
    except ImportError:
        failures.append("Pillow is not installed")
    else:
        with tempfile.TemporaryDirectory(prefix="ui-fidelity-contrast-") as tmp:
            png = Path(tmp) / "board.png"
            im = Image.new("RGB", (4, 4), (0xF5, 0xF6, 0xF7))
            im.putpixel((2, 3), (0x14, 0x17, 0x1A))
            im.save(png)
            check(parse_colour(f"{png}@2,3") == (0x14, 0x17, 0x1A), "pixel pick")
            with contextlib.redirect_stdout(io.StringIO()) as log:
                code = main([f"{png}@2,3", f"{png}@0,0", "--require", "4.5"])
            check(code == 0 and "16.63" in log.getvalue() and "#14171A" in log.getvalue(), "main output")
            with contextlib.redirect_stdout(io.StringIO()):
                code = main(["#6B7480", "#F5F6F7", "--require", "4.5"])
            check(code == 1, f"--require exit {code}")

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    print("contrast.py self-test: " + ("FAILED" if failures else "passed"))
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("colours", nargs="*", help="FOREGROUND BACKGROUND [FOREGROUND BACKGROUND ...]")
    parser.add_argument("--require", type=float, default=None, help="exit 1 when a ratio is below this value")
    parser.add_argument("--self-test", action="store_true", help="run the self-test")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.colours or len(args.colours) % 2:
        parser.error("give the colours in pairs: FOREGROUND BACKGROUND")
    below = False
    for fg_spec, bg_spec in zip(args.colours[::2], args.colours[1::2], strict=True):
        try:
            fg, bg = parse_colour(fg_spec), parse_colour(bg_spec)
        except (ValueError, OSError, IndexError) as e:
            parser.error(str(e))
        value = ratio(fg, bg)
        below = below or (args.require is not None and value < args.require)
        print(f"{hex_of(fg)} on {hex_of(bg)}: {value:5.2f}  {verdict(value)}   ({fg_spec} on {bg_spec})")
    return 1 if below else 0


if __name__ == "__main__":
    sys.exit(main())
