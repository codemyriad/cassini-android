import sys
from PIL import Image
# print y-ranges (in px) of rows containing "ink" pixels (dark) within x range, for both images
def runs(path, x0, x1, test):
    im = Image.open(path).convert('RGB'); w, h = im.size
    rows = [y for y in range(h) if any(test(im.getpixel((x, y))) for x in range(x0, x1))]
    out = []; s = None; p = None
    for y in rows:
        if s is None: s = p = y
        elif y == p + 1: p = y
        else: out.append((s, p)); s = p = y
    if s is not None: out.append((s, p))
    return out
x0, x1 = int(sys.argv[3]), int(sys.argv[4])
dark = lambda c: sum(c) < 3 * 150
for p in sys.argv[1:3]:
    print(p.split('/')[-1], [(a, b) for a, b in runs(p, x0, x1, dark) if b - a > 3])
