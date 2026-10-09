# UI fidelity scripts

These scripts compare the screenshot goldens of `:ui` with the design boards in `design/reference/png`
(ui.md sections 10.2 and 10.3). Their results are advisory. CI gates only on
`:ui:verifyRoborazziDebug` against the committed goldens, which a reviewer approves when they change.

- Python 3.10 or later. Pillow is the only dependency (`contrast.py` needs it only to pick a pixel).
- Run each script with `python3 -I` from any directory. Default paths are relative to the repository
  root. Outputs go to `build/ui-fidelity/`, which git ignores.
- Images are xhdpi: 2 px per dp (and per CSS px of the boards).
- Each script has `--self-test`, which runs it on generated images and exits 1 on a failure.
- The scripts are ruff-clean with `config/ruff.toml`. They do not import each other, because
  `python3 -I` does not put the script directory on the import path.

## compare.py

```sh
python3 -I tools/ui-fidelity/compare.py                       # design/reference/png vs ui/src/test/screenshots/light
python3 -I tools/ui-fidelity/compare.py --slug 00-library     # one board
python3 -I tools/ui-fidelity/compare.py --design build/ui-fidelity/states --golden ui/src/test/screenshots/states
```

The board goldens in `light/` and `states/` come with the P8 screen tests. Until they exist, the
default `--golden` directory is missing and the script stops with a usage error: give `--golden`
explicitly. The examples below that read `ui/src/test/screenshots/light/` need these goldens too.

- `--design`: the design PNGs. The default is `$CASSINI_DESIGN_DIR`, else `design/reference/png`.
- `--golden`: the golden PNGs. The default is `ui/src/test/screenshots/light`.
- `--out`: the output root. The default is `build/ui-fidelity`. Files go to
  `<out>/compare/<golden dir name>/`.

The script pairs files by slug: a design and a golden with the same file name. It lists the files
that have no pair. For each pair it writes:

- `<slug>-blend.png`: a 50/50 blend of the design and the golden.
- `<slug>-heat.png`: the design in light grey, with the differences in red. A difference above 32
  levels is strong red.
- `summary.json`: the figures of every pair.

It prints, for each pair:

- the two sizes. When they differ, it pads the smaller image at the right and bottom with the board
  background `#ECEEF0` and reports the difference in px and dp. A width difference means the content
  does not line up, so compare by eye.
- the mean difference and the percentage of pixels that differ by more than 32 levels. The difference
  of a pixel is the largest difference of its three channels (0-255).
- a table of 52 dp bands with the same two figures. It marks the bands above 25 %.

The exit code is 0 for every result; it is 2 only for a usage error, such as a missing directory.

## crop_states.py

```sh
python3 -I tools/ui-fidelity/crop_states.py
```

This script cuts the five columns of `10-states-and-errors.png` (4540 x 2360 px) into
`build/ui-fidelity/states/10-states-col1.png` ... `col5.png`, and writes their geometry to
`heights.json`. Column i (0-4) starts at x = round(2 x (48 + i x 442.8)) px and is 716 px wide (358
CSS px, the banners' width). The top is the first row at or below 200 px that has a pixel different
from `#ECEEF0` in the column: the top of the heading glyphs. The bottom is the last such row, so the crop has
the content height of the `StatesBoardTest` golden (ui.md section 10.2). `--bottom-margin` adds CSS px
below it, but not past the board's edge.

The golden column starts at the top of the heading's line box, which is a few dp above the glyphs.
To include that space in the crop, use `--top-margin` (CSS px). Then compare the crops with the
`StatesBoardTest` goldens through `compare.py` (see above).

## rows.py

```sh
python3 -I tools/ui-fidelity/rows.py design/reference/png/03-read-and-listen.png \
    ui/src/test/screenshots/light/03-read-and-listen.png --x0 32 --x1 700
```

This script finds the runs of rows that contain a dark pixel (luma below `--dark`, default 150) in
the column range `[--x0, --x1)`. These runs are text lines, icons and rules. It lists them for both
images, pairs them in order, and prints the offset of each top edge in px and dp. If the run counts
differ, the pairs can be shifted. Use `--y0`/`--y1` or a narrower x range to align them.
`--min-height` (default 4 px) skips hairlines and anti-aliasing.

## contrast.py

```sh
python3 -I tools/ui-fidelity/contrast.py '#687180' '#F5F6F7'
python3 -I tools/ui-fidelity/contrast.py ui/src/test/screenshots/light/00-library.png@60,620 '#FFFFFF' --require 4.5
```

This script prints the WCAG 2.x contrast ratio of each FOREGROUND BACKGROUND pair. The verdict is AA
(4.5 or more), AA-large/UI (3.0 or more) or FAIL. A colour is `#RRGGBB` or a pixel `file.png@x,y`.
With `--require N`, the exit code is 1 when a pair is below N. `ContrastTest` in `:ui` enforces the
token pairs of ui.md section 11. Use this script for one-off checks on a board or a golden.

## Reviewer protocol (P8, P12)

Do these steps for each board, and record the results in `docs/ui-fidelity.md`:

1. Record the goldens: `./gradlew :ui:recordRoborazziDebug`. For the States board, also run
   `crop_states.py`.
2. Run `compare.py`.
3. Read three images with an image viewer (agents: the Read tool): the design PNG, the golden and the
   blend. Read the heat map to find the areas that differ. For a tall image, read crops.
4. Check these points:
   - Layout and spacing are within 2 dp, except the accepted deviations in ui.md section 11. Use
     `rows.py` for vertical offsets.
   - The colours are equal to the tokens. Use `contrast.py` with `file.png@x,y` to check a pixel.
   - The typography is correct: family, size and weight.
   - The icons are correct.
   - The copy is word for word.
5. Write down the numbers that `compare.py` printed. The advisory bar for a phone board is:
   - at most 8 % of pixels above 32 levels;
   - no 52 dp band above 25 %.

   If a board is above the bar, write an explanation, for example: text anti-aliasing, or an accepted
   deviation with its number from ui.md section 11.
6. A board with no design (the dark theme, `it/`, `fontscale/`, `variants/`) gets no figures. Read it
   and record what needs the owner's review (ui.md section 11, item 14).

## Ruff

The P2 spike scripts were excluded from ruff in `config/ruff.toml` (`extend-exclude`). These scripts
replace them and pass `ruff check`. P7 does not own `config/`, so the exclusion is still there and P10
must remove it. Until then, check them explicitly:

```sh
ruff check --config config/ruff.toml --no-cache --no-force-exclude tools/ui-fidelity/*.py
```
