#!/usr/bin/env python3
"""Cut the five column references out of the States board (10-states-and-errors.png).

Geometry (ui.md section 10.2), in CSS px of the board, at 2 px per CSS px:
- column i (0-4) starts at x = 48 + i * 442.8 and is 358 wide (the banners' width);
- the top is the first row at or below y = 100 whose pixels in the column differ from the board
  background #ECEEF0, which is the top of the column heading's glyphs (--top-margin moves the cut up,
  for example to the heading's line box);
- the bottom is the last such row plus a margin of 16 (--bottom-margin), clamped to the board.

Writes <out>/states/10-states-col1.png ... col5.png and <out>/states/heights.json. Compare them with
the StatesBoardTest goldens with compare.py --design <out>/states --golden ui/src/test/screenshots/states.

Pillow is the only dependency. Run it with "python3 -I" from any directory. See README.md.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

REPO_ROOT = Path(__file__).resolve().parents[2]
BOARD_NAME = "10-states-and-errors.png"
BOARD_BG = (0xEC, 0xEE, 0xF0)
SCALE = 2  # px per CSS px (dp)
COLUMNS = 5
COLUMN_X0 = 48.0
COLUMN_PITCH = 442.8
COLUMN_WIDTH = 358
SEARCH_TOP = 100
BOTTOM_MARGIN = 16
TOLERANCE = 2  # levels; a pixel within this distance of #ECEEF0 counts as background


class CropError(Exception):
    pass


def default_board() -> Path:
    env = os.environ.get("CASSINI_DESIGN_DIR")
    design = Path(env) if env else REPO_ROOT / "design" / "reference" / "png"
    return design / BOARD_NAME


def content_rows(board: Image.Image, x0: int, x1: int, y0: int) -> tuple[int, int] | None:
    """First and last row at or below y0 with a non-background pixel in [x0, x1)."""
    strip = board.crop((x0, y0, x1, board.height))
    r, g, b = ImageChops.difference(strip, Image.new("RGB", strip.size, BOARD_BG)).split()
    diff = ImageChops.lighter(ImageChops.lighter(r, g), b).point(lambda v: 255 if v > TOLERANCE else 0)
    box = diff.getbbox()
    if box is None:
        return None
    return y0 + box[1], y0 + box[3] - 1


def crop_states(board_path: Path, out: Path, bottom_margin: int = BOTTOM_MARGIN, top_margin: int = 0) -> dict:
    with Image.open(board_path) as im:
        board = im.convert("RGB")
    width_px = COLUMN_WIDTH * SCALE
    states_dir = out / "states"
    states_dir.mkdir(parents=True, exist_ok=True)
    columns = []
    for i in range(COLUMNS):
        x0 = round(SCALE * (COLUMN_X0 + i * COLUMN_PITCH))
        x1 = x0 + width_px
        if x1 > board.width:
            raise CropError(f"column {i + 1} ends at x = {x1} px, past the board width {board.width} px")
        rows = content_rows(board, x0, x1, SEARCH_TOP * SCALE)
        if rows is None:
            raise CropError(f"column {i + 1}: no content below y = {SEARCH_TOP * SCALE} px")
        glyph_top, last = rows
        top = max(0, glyph_top - top_margin * SCALE)
        wanted = last + 1 + bottom_margin * SCALE
        bottom = min(board.height, wanted)
        name = f"10-states-col{i + 1}.png"
        board.crop((x0, top, x1, bottom)).save(states_dir / name)
        columns.append(
            {
                "file": name,
                "x_px": x0,
                "top_px": top,
                "heading_top_px": glyph_top,
                "content_bottom_px": last,
                "bottom_px": bottom,
                "margin_clamped": bottom < wanted,
                "width_px": width_px,
                "height_px": bottom - top,
                "content_height_dp": (last + 1 - top) / SCALE,
                "height_dp": (bottom - top) / SCALE,
            }
        )
    report = {
        "board": str(board_path),
        "board_size_px": list(board.size),
        "scale": SCALE,
        "top_margin_dp": top_margin,
        "bottom_margin_dp": bottom_margin,
        "columns": columns,
    }
    (states_dir / "heights.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def print_report(report: dict, states_dir: Path) -> None:
    print(f"{report['board']} {report['board_size_px'][0]}x{report['board_size_px'][1]} px")
    print(f"{'file':<20} {'x px':>5} {'top px':>6} {'bottom px':>9} {'size px':>9} {'content dp':>10} {'height dp':>9}")
    for c in report["columns"]:
        size = f"{c['width_px']}x{c['height_px']}"
        clamped = "  (margin clamped at the board edge)" if c["margin_clamped"] else ""
        print(
            f"{c['file']:<20} {c['x_px']:>5} {c['top_px']:>6} {c['bottom_px']:>9} {size:>9}"
            f" {c['content_height_dp']:>10g} {c['height_dp']:>9g}{clamped}"
        )
    print(f"Wrote {states_dir}/10-states-col1..{COLUMNS}.png and heights.json")


def self_test() -> int:
    failures: list[str] = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    with tempfile.TemporaryDirectory(prefix="ui-fidelity-states-") as tmp:
        root = Path(tmp)
        board = Image.new("RGB", (4540, 2360), BOARD_BG)
        draw = ImageDraw.Draw(board)
        # A board title above the search line, across every column: it must be ignored.
        draw.rectangle((96, 96, 4400, 170), fill=(20, 23, 26))
        heights = [1500, 1650, 1300, 1450, 2350]
        for i, bottom in enumerate(heights):
            x0 = round(2 * (48 + i * 442.8))
            draw.rectangle((x0 + 4, 245 + i, x0 + 200, 270), fill=(58, 66, 80))  # heading
            draw.rectangle((x0, 300, x0 + 715, bottom), fill=(255, 255, 255))  # banners
            # Something in the gap after the column must not count (it starts at x0 + 716).
            draw.rectangle((x0 + 716, 200, x0 + 760, 2359), fill=(0, 0, 0))
        board_path = root / BOARD_NAME
        board.save(board_path)

        report = crop_states(board_path, root / "out")
        cols = report["columns"]
        check(len(cols) == 5, f"{len(cols)} columns")
        check([c["x_px"] for c in cols] == [96, 982, 1867, 2753, 3638], f"x {[c['x_px'] for c in cols]}")
        check([c["top_px"] for c in cols] == [245, 246, 247, 248, 249], f"tops {[c['top_px'] for c in cols]}")
        check(
            [c["content_bottom_px"] for c in cols] == heights,
            f"bottoms {[c['content_bottom_px'] for c in cols]}",
        )
        for c, last in zip(cols, heights, strict=True):
            expected = min(2360, last + 1 + 32) - c["top_px"]
            check(c["height_px"] == expected, f"{c['file']}: height {c['height_px']}, expected {expected}")
            with Image.open(root / "out" / "states" / c["file"]) as crop:
                check(crop.size == (716, expected), f"{c['file']}: size {crop.size}")
                check(crop.getpixel((715, 100)) == (255, 255, 255), f"{c['file']}: right edge is not the banner")
                if not c["margin_clamped"]:
                    check(crop.getpixel((10, crop.height - 1)) == BOARD_BG, f"{c['file']}: no bottom margin")
        check(cols[4]["margin_clamped"] and not cols[0]["margin_clamped"], "margin clamp flags")
        check(cols[0]["content_height_dp"] == (1500 + 1 - 245) / 2, f"content dp {cols[0]['content_height_dp']}")
        saved = json.loads((root / "out" / "states" / "heights.json").read_text(encoding="utf-8"))
        check(saved["columns"] == cols, "heights.json differs from the report")

        no_margin = crop_states(board_path, root / "out0", bottom_margin=0)
        check(no_margin["columns"][0]["height_px"] == 1500 + 1 - 245, "bottom margin 0")
        raised = crop_states(board_path, root / "out-top", top_margin=3)["columns"][0]
        check((raised["top_px"], raised["heading_top_px"]) == (239, 245), f"top margin 3: {raised}")

        empty = Image.new("RGB", (4540, 2360), BOARD_BG)
        empty.save(root / "empty.png")
        try:
            crop_states(root / "empty.png", root / "out-empty")
            failures.append("an empty board did not fail")
        except CropError:
            pass

        with contextlib.redirect_stdout(io.StringIO()) as log:
            code = main(["--board", str(board_path), "--out", str(root / "out-main")])
        check(code == 0 and "10-states-col1.png" in log.getvalue(), f"main exit {code}")

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    print("crop_states.py self-test: " + ("FAILED" if failures else "passed"))
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--board",
        type=Path,
        default=None,
        help=f"the States board PNG (default: $CASSINI_DESIGN_DIR/{BOARD_NAME}, else design/reference/png/)",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=REPO_ROOT / "build" / "ui-fidelity",
        help="output directory; files go to <out>/states/ (default: build/ui-fidelity)",
    )
    parser.add_argument(
        "--bottom-margin",
        type=int,
        default=BOTTOM_MARGIN,
        help=f"CSS px added below the last content row (default: {BOTTOM_MARGIN})",
    )
    parser.add_argument(
        "--top-margin",
        type=int,
        default=0,
        help="CSS px kept above the heading's first non-background row (default: 0)",
    )
    parser.add_argument("--self-test", action="store_true", help="run the self-test on generated images")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    board_path = args.board or default_board()
    if not board_path.is_file():
        parser.error(f"board not found: {board_path}")
    try:
        report = crop_states(board_path, args.out, args.bottom_margin, args.top_margin)
    except CropError as e:
        print(f"crop_states.py: {e}", file=sys.stderr)
        return 1
    print_report(report, args.out / "states")
    return 0


if __name__ == "__main__":
    sys.exit(main())
