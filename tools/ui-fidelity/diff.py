import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert('RGB'); b = Image.open(sys.argv[2]).convert('RGB')
print('sizes', a.size, b.size)
d = ImageChops.difference(a, b).convert('L')
px = list(d.getdata()); n = len(px)
print('mean abs diff %.2f' % (sum(px)/n), 'pixels >32: %.2f%%' % (100*sum(1 for p in px if p > 32)/n))
# row-band report
w, h = a.size
for y0 in range(0, h, 104):
    band = [d.getpixel((x, y)) for y in range(y0, min(h, y0+104), 2) for x in range(0, w, 2)]
    print(y0//2, 'dp: %.1f' % (sum(1 for p in band if p > 32) * 100 / len(band)))
out = Image.blend(a, b, 0.5); out.save(sys.argv[3])
