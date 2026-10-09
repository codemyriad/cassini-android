# UI fidelity scripts

Throwaway measuring scripts from the UI spike. They need Pillow (`diff.py`, `rows.py`); run them with
`python3 -I`.

- `diff.py <design.png> <golden.png> <blend.png>`: mean difference, share of pixels that differ by
  more than 32 levels, a per-band report, and a 50/50 blend.
- `rows.py <a.png> <b.png> <x0> <x1>`: vertical runs of dark pixels in a column range, to compare offsets.
- `contrast.py`: WCAG contrast ratios of the spike's colour pairs.

P7 rewrites them as `compare.py` and `crop_states.py` (ui.md section 10.3). Until then
`config/ruff.toml` excludes this directory from ruff.
