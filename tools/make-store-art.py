#!/usr/bin/env python3
"""Generates Play Store artwork (icon, feature graphic, TV banner copy).

Uses only the standard library, like make_art.py.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import make_art as art


EXTRA = {
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
}
art.FONT.update(EXTRA)


def shield_mark(x, y, cx, cy, s, light=(150, 220, 184), dark=(0, 76, 51)):
    pts = art.shield(cx, cy, s)
    if (art.dist_seg(x, y, cx - 0.20 * s, cy + 0.03 * s,
                     cx - 0.05 * s, cy + 0.18 * s) < 0.040 * s
            or art.dist_seg(x, y, cx - 0.05 * s, cy + 0.18 * s,
                            cx + 0.24 * s, cy - 0.12 * s) < 0.040 * s):
        return (0, 56, 37)
    if art.inside_poly(x, y, pts):
        return art.lerp(light, dark, (y - (cy - 0.47 * s)) / s)
    return None


def icon_base(x, y, size=512):
    t = y / size
    col = art.lerp((30, 42, 35), (9, 14, 11), t)
    d = math.hypot(x - size * 0.50, y - size * 0.34)
    col = art.blend(col, (142, 213, 178), 0.13 * max(0.0, 1 - d / (size * 0.85)))
    d2 = math.hypot(x - size * 0.16, y - size * 0.06)
    col = art.blend(col, (165, 204, 222), 0.10 * max(0.0, 1 - d2 / (size * 0.7)))
    return col


def icon_512(x, y):
    base = icon_base(x, y)
    s = 512 * 0.72
    cx, cy = 256, 264
    mark = shield_mark(x, y, cx, cy, s)
    return mark if mark is not None else base


def feature_bg(x, y, w=1024, h=500):
    t = y / h
    col = art.lerp((17, 25, 20), (8, 12, 10), t)
    d = math.hypot(x - 170, y - 90)
    col = art.blend(col, (142, 213, 178), 0.20 * max(0.0, 1 - d / 560))
    d2 = math.hypot(x - 930, y - 470)
    col = art.blend(col, (165, 204, 222), 0.12 * max(0.0, 1 - d2 / 620))
    # Concentric rings behind the mark on the right.
    r = math.hypot(x - 840, y - 250)
    for ring, alpha in ((150, 0.055), (210, 0.040), (270, 0.028)):
        if abs(r - ring) < 1.5:
            col = art.blend(col, (142, 213, 178), alpha * 6)
    return col


def feature_graphic(x, y):
    col = feature_bg(x, y)
    mark = shield_mark(x, y, 196, 232, 290)
    if mark is not None:
        return mark
    return col


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "store/assets"
    os.makedirs(out, exist_ok=True)

    icon = art.render(512, 512, icon_512, ss=3)
    art.write_png(os.path.join(out, "icon-512.png"), 512, 512, icon)

    title = art.text_rects("HOLE", 330, 118, 11)
    title += art.text_rects("SHIELD", 330, 220, 11)
    tagline = art.text_rects("DNS FILTERING ON YOUR DEVICE", 336, 344, 4)
    small = art.text_rects("ANDROID TV AND PHONE", 336, 402, 3)

    def graphic(x, y):
        if art.in_rects(x, y, title):
            return (232, 238, 232)
        if art.in_rects(x, y, tagline):
            return (142, 213, 178)
        if art.in_rects(x, y, small):
            return (150, 162, 152)
        return feature_graphic(x, y)

    px = art.render(1024, 500, graphic, ss=2)
    art.write_png(os.path.join(out, "feature-graphic-1024x500.png"), 1024, 500, px)

    # Copy of the TV banner in the store asset folder for convenience.
    import shutil
    banner = os.path.join(os.path.dirname(out), "..", "res", "drawable", "banner.png")
    banner = os.path.normpath(banner)
    if os.path.exists(banner):
        shutil.copyfile(banner, os.path.join(out, "tv-banner-320x180.png"))
        print("copied", os.path.join(out, "tv-banner-320x180.png"))


if __name__ == "__main__":
    main()
