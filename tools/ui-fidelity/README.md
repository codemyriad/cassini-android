# UI fidelity scripts

These scripts compare the screenshot goldens of `:ui` with the design boards in `design/reference/png`
(ui.md sections 10.2 and 10.3). Their results are advisory. The only CI gate is `:ui:verifyRoborazziDebug`,
which compares the screenshots with the committed goldens. A reviewer approves each change to a golden.

- The scripts need Python 3.10 or later. Pillow is the only dependency (`contrast.py` needs it only to read a
  pixel).
- Run each script with `python3 -I` from any directory. The default paths start at the repository root. The
  outputs go to `build/ui-fidelity/`, which git ignores.
- The images are xhdpi: 2 px for each dp (and for each CSS px of the boards).
- Each script has `--self-test`. This option operates the script on generated images. If a test fails, the exit
  code is 1.
- The scripts pass ruff with `config/ruff.toml`. They do not import each other, because `python3 -I` does not
  put the script directory on the import path.

## compare.py

```sh
python3 -I tools/ui-fidelity/compare.py                       # design/reference/png vs ui/src/test/screenshots/light
python3 -I tools/ui-fidelity/compare.py --slug 00-library     # one board
python3 -I tools/ui-fidelity/compare.py --design build/ui-fidelity/states --golden ui/src/test/screenshots/states
```

The default `--golden` directory holds the committed board goldens of the P8 screen tests
(`ui/src/test/screenshots/light/`). Their names are the board slugs. The goldens of the States columns are in
`ui/src/test/screenshots/states/`.

- `--design`: the design PNGs. The default is `$CASSINI_DESIGN_DIR`, else `design/reference/png`.
- `--golden`: the golden PNGs. The default is `ui/src/test/screenshots/light`.
- `--out`: the output root. The default is `build/ui-fidelity`. The files go to
  `<out>/compare/<golden dir name>/`.

The script pairs the files by slug: a design and a golden with the same file name. It lists the files that have
no pair. For each pair, it writes these files:

- `<slug>-blend.png`: a 50/50 blend of the design and the golden.
- `<slug>-heat.png`: the design in light grey, with the differences in red. A difference of more than 32 levels
  is strong red.
- `summary.json`: the figures of all pairs.

For each pair, it prints these figures:

- The two sizes. If the sizes are different, the script adds the board background `#ECEEF0` to the right and the
  bottom of the smaller image. It gives the difference in px and dp. A difference in width means that the content
  does not align, so compare the images by eye.
- The mean difference and the percentage of pixels with a difference of more than 32 levels. The difference of a
  pixel is the largest difference of its three channels (0-255).
- A table of 52 dp bands with the same two figures. The table marks the bands above 25 %.

The exit code is 0 for all results. It is 2 only for a usage error, for example a missing directory.

## crop_states.py

```sh
python3 -I tools/ui-fidelity/crop_states.py
```

This script cuts the five columns of `10-states-and-errors.png` (4540 x 2360 px) into
`build/ui-fidelity/states/10-states-col1.png` ... `col5.png`. It writes their geometry to `heights.json`. Column i
(0-4) starts at x = round(2 x (48 + i x 442.8)) px and is 716 px wide (358 CSS px, the width of the banners).

The top of a crop is the first row at y = 200 px or more that has a pixel different from `#ECEEF0` in the column.
This row is the top of the heading glyphs. The bottom is the last such row. Thus the crop has the content height of
the `StatesBoardTest` golden (ui.md section 10.2). `--bottom-margin` adds CSS px below the bottom, but not past the
edge of the board.

The golden column starts at the top of the line box of the heading, a few dp above the glyphs. To include that
space in the crop, use `--top-margin` (CSS px). Then compare the crops with the `StatesBoardTest` goldens through
`compare.py` (see "compare.py").

## rows.py

```sh
python3 -I tools/ui-fidelity/rows.py design/reference/png/03-read-and-listen.png \
    ui/src/test/screenshots/light/03-read-and-listen.png --x0 32 --x1 700
```

This script finds the runs of rows that contain a dark pixel (luma less than `--dark`, the default is 150) in the
column range `[--x0, --x1)`. These runs are text lines, icons and rules. The script lists the runs of the two
images and pairs them in sequence. It prints the offset of each top edge in px and dp. If the two images have a
different number of runs, the pairs can move out of alignment. To align them, use `--y0`/`--y1` or a narrower x
range. `--min-height` (the default is 4 px) ignores hairlines and anti-aliasing.

## contrast.py

```sh
python3 -I tools/ui-fidelity/contrast.py '#687180' '#F5F6F7'
python3 -I tools/ui-fidelity/contrast.py ui/src/test/screenshots/light/00-library.png@60,620 '#FFFFFF' --require 4.5
```

This script prints the WCAG 2.x contrast ratio of each FOREGROUND BACKGROUND pair. The result is AA (4.5 or more),
AA-large/UI (3.0 or more) or FAIL. A colour is `#RRGGBB` or a pixel `file.png@x,y`. With `--require N`, the exit
code is 1 if a pair is less than N. `ContrastTest` in `:ui` enforces the token pairs of ui.md section 11. Use this
script for a one-time check of a board or a golden.

## Reviewer protocol (P8, P12)

Do these steps for each board, and record the results in `docs/ui-fidelity.md`:

1. Use the committed goldens. Do not record them again for the review. Record them only after an intended
   change to a screen, and only on Linux x86_64 (see "Screenshot tests" in `docs/development.md`). For the States
   board, run `crop_states.py --top-margin 3`.
2. Run `compare.py`.
3. Read three images with an image viewer (agents: the Read tool): the design PNG, the golden and the blend. Read
   the heat map to find the areas with differences. For a tall image, read crops.
4. Examine these points:
   - Layout and spacing: the difference is 2 dp or less, except for the accepted deviations in ui.md section 11.
     Use `rows.py` for vertical offsets.
   - Colours: they are equal to the tokens. To read a pixel, use `contrast.py` with `file.png@x,y`.
   - Typography: the family, the size and the weight are correct.
   - Icons: they are correct.
   - Copy: it is the same, word for word.
5. Write down the figures that `compare.py` printed. These are the advisory limits for a phone board:
   - At most 8 % of pixels above 32 levels.
   - No 52 dp band above 25 %.

   If a board is above a limit, write the cause. Examples are text anti-aliasing, or an accepted deviation with
   its number in ui.md section 11.
6. A board with no design (the dark theme, `it/`, `fontscale/`, `variants/`) gets no figures. Read it, and record
   the items that the owner must review (ui.md section 11, item 14).

## Ruff

The scripts pass `ruff check`. CI does this check in the ruff step of `.github/workflows/android.yml`. To do the
check yourself, run this command from the repository root:

```sh
ruff check --config config/ruff.toml scripts tools
```
