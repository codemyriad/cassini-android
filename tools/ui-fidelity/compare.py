#!/usr/bin/env python3
"""Compare screenshot goldens with the design boards. The result is advisory.

Pairs each golden with the design PNG of the same slug (file name without ".png"). For each pair it
writes a 50/50 blend and a heat map of the absolute difference, and prints the mean difference, the
percentage of pixels that differ by more than 32 levels, and a table per 52 dp band.

The difference of a pixel is the largest absolute difference of its three channels (0-255).
When the two images differ in size, the smaller one is padded at the right and bottom with the board
background #ECEEF0, and the size difference is reported. The exit code is 0 for every comparison
result; only a usage error (for example a missing directory) gives a non-zero exit code.

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
from dataclasses import asdict, dataclass, field
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

REPO_ROOT = Path(__file__).resolve().parents[2]
BOARD_BG = (0xEC, 0xEE, 0xF0)
SCALE = 2  # px per dp: the boards and the goldens are xhdpi
THRESHOLD = 32  # levels
BAND_DP = 52
ADVISORY_PIXELS_PCT = 8.0  # ui.md 10.3: at most 8 % of pixels above 32 per phone board
ADVISORY_BAND_PCT = 25.0  # ui.md 10.3: no 52 dp band above 25 %
HEAT_GAIN = 4
HEAT_COLOUR = (220, 0, 0)


@dataclass
class Band:
    top_dp: int
    bottom_dp: int
    mean: float
    over_pct: float


@dataclass
class PairResult:
    slug: str
    design: str
    golden: str
    design_size: tuple[int, int]
    golden_size: tuple[int, int]
    compared_size: tuple[int, int]
    mean: float
    over_pct: float
    bands: list[Band] = field(default_factory=list)
    blend: str = ""
    heat: str = ""

    @property
    def size_delta_px(self) -> tuple[int, int]:
        return (self.golden_size[0] - self.design_size[0], self.golden_size[1] - self.design_size[1])

    @property
    def above_bar(self) -> bool:
        return self.over_pct > ADVISORY_PIXELS_PCT or any(b.over_pct > ADVISORY_BAND_PCT for b in self.bands)


def default_design_dir() -> Path:
    env = os.environ.get("CASSINI_DESIGN_DIR")
    return Path(env) if env else REPO_ROOT / "design" / "reference" / "png"


def load_rgb(path: Path) -> Image.Image:
    """Opens a PNG as RGB. A transparent pixel is shown over the board background."""
    with Image.open(path) as im:
        if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
            rgba = im.convert("RGBA")
            base = Image.new("RGBA", rgba.size, (*BOARD_BG, 255))
            return Image.alpha_composite(base, rgba).convert("RGB")
        return im.convert("RGB")


def pad_to(im: Image.Image, size: tuple[int, int]) -> Image.Image:
    if im.size == size:
        return im
    canvas = Image.new("RGB", size, BOARD_BG)
    canvas.paste(im, (0, 0))
    return canvas


def difference_map(a: Image.Image, b: Image.Image) -> Image.Image:
    """Greyscale image: per pixel, the largest absolute channel difference."""
    r, g, bl = ImageChops.difference(a, b).split()
    return ImageChops.lighter(ImageChops.lighter(r, g), bl)


def mean_level(hist: list[int]) -> float:
    total = sum(hist)
    return sum(level * count for level, count in enumerate(hist)) / total if total else 0.0


def over_pct(hist: list[int], threshold: int) -> float:
    total = sum(hist)
    return 100.0 * sum(hist[threshold + 1 :]) / total if total else 0.0


def heat_map(design: Image.Image, diff: Image.Image) -> Image.Image:
    """The design in light grey, with differences in red. A difference above 32 is strong red."""
    base = design.convert("L").point(lambda v: 176 + v * 80 // 255).convert("RGB")
    mask = diff.point(lambda v: min(255, v * HEAT_GAIN))
    return Image.composite(Image.new("RGB", design.size, HEAT_COLOUR), base, mask)


def compare_pair(slug: str, design_path: Path, golden_path: Path, out_dir: Path) -> PairResult:
    design = load_rgb(design_path)
    golden = load_rgb(golden_path)
    size = (max(design.width, golden.width), max(design.height, golden.height))
    a = pad_to(design, size)
    b = pad_to(golden, size)
    diff = difference_map(a, b)
    hist = diff.histogram()

    band_px = BAND_DP * SCALE
    bands = []
    for top in range(0, size[1], band_px):
        bottom = min(size[1], top + band_px)
        band_hist = diff.crop((0, top, size[0], bottom)).histogram()
        bands.append(
            Band(
                top_dp=top // SCALE,
                bottom_dp=-(-bottom // SCALE),
                mean=round(mean_level(band_hist), 2),
                over_pct=round(over_pct(band_hist, THRESHOLD), 2),
            )
        )

    out_dir.mkdir(parents=True, exist_ok=True)
    blend_path = out_dir / f"{slug}-blend.png"
    heat_path = out_dir / f"{slug}-heat.png"
    Image.blend(a, b, 0.5).save(blend_path)
    heat_map(a, diff).save(heat_path)

    return PairResult(
        slug=slug,
        design=str(design_path),
        golden=str(golden_path),
        design_size=design.size,
        golden_size=golden.size,
        compared_size=size,
        mean=round(mean_level(hist), 2),
        over_pct=round(over_pct(hist, THRESHOLD), 2),
        bands=bands,
        blend=str(blend_path),
        heat=str(heat_path),
    )


def pngs_by_slug(directory: Path) -> dict[str, Path]:
    return {p.stem: p for p in sorted(directory.glob("*.png")) if p.is_file()}


def pair_files(design_dir: Path, golden_dir: Path) -> tuple[list[tuple[str, Path, Path]], list[str], list[str]]:
    design = pngs_by_slug(design_dir)
    golden = pngs_by_slug(golden_dir)
    pairs = [(slug, design[slug], golden[slug]) for slug in sorted(design.keys() & golden.keys())]
    return pairs, sorted(design.keys() - golden.keys()), sorted(golden.keys() - design.keys())


def format_result(r: PairResult) -> str:
    lines = [f"{r.slug}"]
    dw, dh = r.design_size
    gw, gh = r.golden_size
    lines.append(f"  design {dw}x{dh} px, golden {gw}x{gh} px")
    if r.design_size != r.golden_size:
        xw, xh = r.size_delta_px
        lines.append(
            f"  SIZE DIFFERS: golden {xw:+d} x {xh:+d} px ({xw / SCALE:+g} x {xh / SCALE:+g} dp) against the design;"
            f" both padded to {r.compared_size[0]}x{r.compared_size[1]} with #ECEEF0"
        )
        if xw != 0:
            lines.append("  WIDTHS DIFFER: the columns do not line up; compare the content by eye")
    flag = "  (above the advisory bar of 8 %)" if r.over_pct > ADVISORY_PIXELS_PCT else ""
    lines.append(f"  mean difference {r.mean:.2f}, pixels > {THRESHOLD} levels: {r.over_pct:.2f} %{flag}")
    lines.append(f"  {'band (dp)':>13}  {'mean':>6}  {'> ' + str(THRESHOLD) + ' %':>7}")
    for b in r.bands:
        mark = "  <- above 25 %" if b.over_pct > ADVISORY_BAND_PCT else ""
        lines.append(f"  {b.top_dp:>5} - {b.bottom_dp:<5}  {b.mean:>6.2f}  {b.over_pct:>7.2f}{mark}")
    lines.append(f"  blend {r.blend}")
    lines.append(f"  heat  {r.heat}")
    return "\n".join(lines)


def run(design_dir: Path, golden_dir: Path, out: Path, slugs: list[str] | None = None) -> list[PairResult]:
    pairs, design_only, golden_only = pair_files(design_dir, golden_dir)
    if slugs:
        pairs = [p for p in pairs if p[0] in slugs]
        missing = sorted(set(slugs) - {p[0] for p in pairs})
        if missing:
            print(f"No pair for: {', '.join(missing)}")
    out_dir = out / "compare" / golden_dir.name
    results = []
    for slug, design_path, golden_path in pairs:
        result = compare_pair(slug, design_path, golden_path, out_dir)
        results.append(result)
        print(format_result(result))
        print()
    if not slugs:
        if design_only:
            print(f"Design without a golden: {', '.join(design_only)}")
        if golden_only:
            print(f"Golden without a design: {', '.join(golden_only)}")
    if results:
        out_dir.mkdir(parents=True, exist_ok=True)
        summary = out_dir / "summary.json"
        payload = {
            "design_dir": str(design_dir),
            "golden_dir": str(golden_dir),
            "threshold": THRESHOLD,
            "band_dp": BAND_DP,
            "scale": SCALE,
            "pairs": [asdict(r) | {"size_delta_px": r.size_delta_px, "above_bar": r.above_bar} for r in results],
        }
        summary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        above = [r.slug for r in results if r.above_bar]
        print(f"{len(results)} pairs compared; summary in {summary}")
        print(f"Above the advisory bar (needs a written explanation): {', '.join(above) if above else 'none'}")
    else:
        print("No pairs to compare.")
    return results


def self_test() -> int:
    failures: list[str] = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    with tempfile.TemporaryDirectory(prefix="ui-fidelity-compare-") as tmp:
        root = Path(tmp)
        design_dir = root / "design"
        golden_dir = root / "light"
        design_dir.mkdir()
        golden_dir.mkdir()

        # Identical boards.
        same = Image.new("RGB", (780, 1688), (245, 246, 247))
        ImageDraw.Draw(same).rectangle((40, 100, 300, 160), fill=(20, 23, 26))
        same.save(design_dir / "00-same.png")
        same.save(golden_dir / "00-same.png")

        # A 780 x 52 px block differs by 255 in the blue channel only: half of band 0 (0-52 dp).
        base = Image.new("RGB", (780, 1688), (255, 255, 0))
        base.save(design_dir / "01-block.png")
        changed = base.copy()
        ImageDraw.Draw(changed).rectangle((0, 0, 779, 51), fill=(255, 255, 255))
        changed.save(golden_dir / "01-block.png")

        # The golden is 20 px shorter and dark; the padding rows must equal the design (#ECEEF0).
        Image.new("RGB", (716, 400), BOARD_BG).save(design_dir / "02-pad.png")
        Image.new("RGB", (716, 380), (20, 23, 26)).save(golden_dir / "02-pad.png")

        # A transparent golden pixel shows the board background.
        Image.new("RGB", (10, 10), BOARD_BG).save(design_dir / "03-alpha.png")
        Image.new("RGBA", (10, 10), (0, 0, 0, 0)).save(golden_dir / "03-alpha.png")

        Image.new("RGB", (10, 10)).save(design_dir / "98-design-only.png")
        Image.new("RGB", (10, 10)).save(golden_dir / "99-golden-only.png")

        pairs, design_only, golden_only = pair_files(design_dir, golden_dir)
        check([p[0] for p in pairs] == ["00-same", "01-block", "02-pad", "03-alpha"], f"pairs {pairs}")
        check(design_only == ["98-design-only"], f"design only {design_only}")
        check(golden_only == ["99-golden-only"], f"golden only {golden_only}")

        out = root / "out"
        with contextlib.redirect_stdout(io.StringIO()) as log:
            results = {r.slug: r for r in run(design_dir, golden_dir, out)}
        text = log.getvalue()
        check("Design without a golden: 98-design-only" in text, "design-only file not reported")
        check("Golden without a design: 99-golden-only" in text, "golden-only file not reported")
        check("needs a written explanation): 01-block, 02-pad" in text, "advisory flags not reported")

        r = results["00-same"]
        check(r.mean == 0 and r.over_pct == 0, f"identical: mean {r.mean}, over {r.over_pct}")
        check(not r.above_bar, "identical boards are above the bar")
        check(len(r.bands) == 17, f"1688 px in 104 px bands gives 17 bands, got {len(r.bands)}")
        check((r.bands[-1].top_dp, r.bands[-1].bottom_dp) == (832, 844), f"last band {r.bands[-1]}")

        r = results["01-block"]
        expected = round(100.0 * 780 * 52 / (780 * 1688), 2)
        check(r.over_pct == expected, f"block: over {r.over_pct}, expected {expected}")
        band0 = 50.0
        check(r.bands[0].over_pct == band0, f"block band 0: {r.bands[0].over_pct}, expected {band0}")
        check(all(b.over_pct == 0 for b in r.bands[1:]), "block: other bands differ")
        check(r.bands[0].over_pct > ADVISORY_BAND_PCT and r.above_bar, "block band is not flagged")
        with Image.open(r.heat) as heat:
            check(heat.getpixel((10, 10)) == HEAT_COLOUR, f"heat in the block {heat.getpixel((10, 10))}")
            check(heat.getpixel((10, 60)) != HEAT_COLOUR, "heat outside the block")
        with Image.open(r.blend) as blend:
            check(blend.getpixel((10, 10)) == (255, 255, 127), f"blend {blend.getpixel((10, 10))}")

        r = results["02-pad"]
        check(r.compared_size == (716, 400), f"pad size {r.compared_size}")
        check(r.size_delta_px == (0, -20), f"pad delta {r.size_delta_px}")
        expected = round(100.0 * 716 * 380 / (716 * 400), 2)
        check(r.over_pct == expected, f"pad: over {r.over_pct}, expected {expected} (padding must be #ECEEF0)")
        with Image.open(r.heat) as heat:
            check(heat.getpixel((300, 390)) != HEAT_COLOUR, "pad: the padded rows differ from #ECEEF0")
            check(heat.getpixel((300, 370)) == HEAT_COLOUR, "pad: the golden rows do not differ")
        check("SIZE DIFFERS: golden +0 x -20 px (+0 x -10 dp)" in format_result(r), "pad: size delta not reported")

        r = results["03-alpha"]
        check(r.mean == 0, f"alpha: mean {r.mean}")

        summary = json.loads((out / "compare" / "light" / "summary.json").read_text(encoding="utf-8"))
        check(len(summary["pairs"]) == 4, "summary pairs")
        check([p["slug"] for p in summary["pairs"] if p["above_bar"]] == ["01-block", "02-pad"], "summary flags")

        with contextlib.redirect_stdout(io.StringIO()):
            only = run(design_dir, golden_dir, root / "out2", slugs=["01-block"])
            code = main(["--design", str(design_dir), "--golden", str(golden_dir), "--out", str(root / "o3")])
        check([x.slug for x in only] == ["01-block"], "slug filter")
        check(code == 0, f"exit code {code}")

    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    print("compare.py self-test: " + ("FAILED" if failures else "passed"))
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--design",
        type=Path,
        default=None,
        help="design PNGs (default: $CASSINI_DESIGN_DIR, else design/reference/png)",
    )
    parser.add_argument(
        "--golden",
        type=Path,
        default=REPO_ROOT / "ui" / "src" / "test" / "screenshots" / "light",
        help="golden PNGs (default: ui/src/test/screenshots/light)",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=REPO_ROOT / "build" / "ui-fidelity",
        help="output directory; files go to <out>/compare/<golden dir name>/ (default: build/ui-fidelity)",
    )
    parser.add_argument("--slug", action="append", help="compare only this slug (repeat for more)")
    parser.add_argument("--self-test", action="store_true", help="run the self-test on generated images")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    design_dir = args.design or default_design_dir()
    for label, directory in (("design", design_dir), ("golden", args.golden)):
        if not directory.is_dir():
            parser.error(f"{label} directory not found: {directory}")
    run(design_dir, args.golden, args.out, args.slug)
    return 0


if __name__ == "__main__":
    sys.exit(main())
