"""Measure the collapsed week-pill position from an app screenshot.

Usage: python measure_pill.py <png> [density]

Known app geometry: the pill is exactly WeekPillHeight (40dp) tall, the weekday row is
five equal columns spanning the header's full width, and the header is full screen width.
So: px-per-dp comes from the pill's height, and the layout width from the weekday columns.
"""
import sys
import numpy as np
from PIL import Image

path = sys.argv[1]
density = float(sys.argv[2]) if len(sys.argv) > 2 else 3.0
img = np.asarray(Image.open(path).convert("RGB")).astype(int)
H, W, _ = img.shape
lum = img.mean(axis=2)
print(f"image {W}x{H}  density {density}  => screen {W / density:.1f}dp wide")


def runs(mask):
    out, s = [], None
    for i, v in enumerate(mask):
        if v and s is None:
            s = i
        elif not v and s is not None:
            out.append((s, i - 1))
            s = None
    if s is not None:
        out.append((s, len(mask) - 1))
    return out


# --- weekday row: 9th..16th percentile rows hold the darkest text in the header ---
dark_rows = (lum[:, :] < 120).sum(axis=1)
band_top = int(H * 0.06)
cand = np.argsort(dark_rows[band_top:int(H * 0.20)])[-40:] + band_top
label_lo, label_hi = cand.min(), cand.max()
cols = (lum[label_lo:label_hi + 1] < 150).any(axis=0)
groups = [g for g in runs(cols) if g[1] - g[0] >= 2]
# merge groups closer than 40px (glyphs of one label)
merged = []
for g in groups:
    if merged and g[0] - merged[-1][1] <= 40:
        merged[-1] = (merged[-1][0], g[1])
    else:
        merged.append(list(g))
centers = [(a + b) / 2 for a, b in merged]
print("weekday label centers px:", [round(c, 1) for c in centers])
if len(centers) == 5:
    step = (centers[-1] - centers[0]) / 4
    layout_w = 5 * step
    layout_l = centers[0] - step / 2
    layout_r = layout_l + layout_w
    print(f"layout: left {layout_l:.1f} right {layout_r:.1f} width {layout_w:.1f}px "
          f"({layout_w / density:.1f}dp)")

# --- pill: brightest wide blob in the header band (above the weekday row) ---
pill_band = lum[band_top:label_lo - 5]
best = None
for y in range(pill_band.shape[0]):
    row = pill_band[y]
    for a, b in runs(row > 252):
        if b - a >= 60 and (best is None or b - a > best[3] - best[2]):
            best = (y + band_top, y + band_top, a, b)
# widen to the pill's full vertical extent at its own horizontal middle
_, _, x0, x1 = best
xm = (x0 + x1) // 2
col = lum[band_top:label_lo - 5, xm]
rows = np.where(col > 252)[0] + band_top
py0, py1 = rows.min(), rows.max()
px_per_dp = (py1 - py0 + 1) / 40.0
print(f"pill: x {x0}..{x1} (w {x1 - x0 + 1}px = {(x1 - x0 + 1) / px_per_dp:.1f}dp), "
      f"y {py0}..{py1} (h {py1 - py0 + 1}px = 40dp => {px_per_dp:.3f}px/dp)")
print(f"pill right edge -> layout right edge: {layout_r - x1:.1f}px "
      f"= {(layout_r - x1) / px_per_dp:.1f}dp")
print(f"pill centre {((x0 + x1) / 2):.1f}px vs layout centre {((layout_l + layout_r) / 2):.1f}px "
      f"(offset {((x0 + x1) / 2 - (layout_l + layout_r) / 2) / px_per_dp:+.1f}dp)")
