#!/usr/bin/env python3
"""Draws the launcher icon and TV banner without any image libraries."""
import math, struct, sys, zlib, os

FONT = {
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def blend(bg, fg, a):
    return tuple(int(bg[i] + (fg[i] - bg[i]) * a) for i in range(3))


def clamp8(value):
    return max(0, min(255, int(value)))


def inside_round_rect(x, y, w, h, r):
    if x < 0 or y < 0 or x >= w or y >= h:
        return False
    cx = min(max(x, r), w - r)
    cy = min(max(y, r), h - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def inside_poly(x, y, pts):
    inside = False
    j = len(pts) - 1
    for i in range(len(pts)):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > y) != (yj > y):
            if x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi:
                inside = not inside
        j = i
    return inside


def cubic(p0, p1, p2, p3, n=10):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        x = u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0]
        y = u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]
        out.append((x, y))
    return out


def shield(cx, cy, s):
    top = (cx, cy - 0.47 * s)
    right = (cx + 0.40 * s, cy - 0.34 * s)
    lower = (cx + 0.40 * s, cy + 0.02 * s)
    curve = cubic(lower, (cx + 0.40 * s, cy + 0.30 * s),
                  (cx + 0.21 * s, cy + 0.43 * s), (cx, cy + 0.49 * s))
    pts = [top, right, lower] + curve[1:]
    mirror = [(2 * cx - x, y) for (x, y) in reversed(curve[:-1])]
    pts += mirror
    pts += [(cx - 0.40 * s, cy - 0.34 * s)]
    return pts


def dist_seg(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    if l2 <= 1e-9:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / l2))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def text_rects(text, x, y, scale):
    rects = []
    pen = x
    for ch in text:
        glyph = FONT.get(ch.upper(), FONT[" "])
        for row, line in enumerate(glyph):
            for col, bit in enumerate(line):
                if bit == "1":
                    rects.append((pen + col * scale, y + row * scale, scale, scale))
        pen += 6 * scale
    return rects


def in_rects(x, y, rects):
    for (rx, ry, rw, rh) in rects:
        if rx <= x < rx + rw and ry <= y < ry + rh:
            return True
    return False


def render(w, h, color_at, ss=3):
    pixels = bytearray()
    for y in range(h):
        for x in range(w):
            r = g = b = a = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    px = x + (sx + 0.5) / ss
                    py = y + (sy + 0.5) / ss
                    c = color_at(px, py)
                    if len(c) == 3:
                        cr, cg, cb = c
                        ca = 255
                    else:
                        cr, cg, cb, ca = c
                    r += cr * ca
                    g += cg * ca
                    b += cb * ca
                    a += ca
            n = ss * ss
            if a > 0:
                pixels += bytes((clamp8(r / a), clamp8(g / a), clamp8(b / a),
                                 clamp8(255 * a / n)))
            else:
                pixels += b"\x00\x00\x00\x00"
    return pixels


def write_png(path, w, h, pixels):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    print("wrote", path, len(png), "bytes")


def icon_color(x, y):
    w = h = 192
    if not inside_round_rect(x, y, w, h, 40):
        return (0, 0, 0, 0)
    t = y / h
    col = lerp((17, 34, 46), (4, 7, 10), t)
    d = math.hypot(x - 96, y - 68)
    col = blend(col, (52, 211, 153), 0.09 * max(0.0, 1 - d / 155))

    s = 138
    cx, cy = 96, 98
    pts = shield(cx, cy, s)
    if (dist_seg(x, y, cx - 0.20 * s, cy + 0.03 * s, cx - 0.05 * s, cy + 0.18 * s) < 0.040 * s
            or dist_seg(x, y, cx - 0.05 * s, cy + 0.18 * s, cx + 0.24 * s, cy - 0.12 * s) < 0.040 * s):
        return (248, 250, 252)
    if inside_poly(x, y, pts):
        return lerp((110, 231, 183), (4, 120, 87), (y - (cy - 0.47 * s)) / s)
    return col


def banner_color(x, y):
    col = lerp((8, 17, 28), (4, 8, 13), y / 180)
    d1 = math.hypot(x - 36, y - 22)
    col = blend(col, (52, 211, 153), 0.16 * max(0.0, 1 - d1 / 150))
    d2 = math.hypot(x - 292, y - 168)
    col = blend(col, (96, 165, 250), 0.15 * max(0.0, 1 - d2 / 160))

    pts = shield(160, 58, 82)
    s = 82
    cx, cy = 160, 58
    if (dist_seg(x, y, cx - 0.20 * s, cy + 0.03 * s, cx - 0.05 * s, cy + 0.18 * s) < 0.040 * s
            or dist_seg(x, y, cx - 0.05 * s, cy + 0.18 * s, cx + 0.24 * s, cy - 0.12 * s) < 0.040 * s):
        return (248, 250, 252)
    if inside_poly(x, y, pts):
        return lerp((110, 231, 183), (4, 120, 87), (y - 20) / 82)
    return col


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "res/drawable"
    os.makedirs(out, exist_ok=True)
    ic = render(192, 192, icon_color, ss=4)
    write_png(os.path.join(out, "ic_launcher.png"), 192, 192, ic)

    rects = text_rects("PI-HOLE TV", 71, 130, 3)
    def banner(x, y):
        if in_rects(x, y, rects):
            return (241, 245, 249)
        return banner_color(x, y)
    banner_px = render(320, 180, banner, ss=2)
    write_png(os.path.join(out, "banner.png"), 320, 180, banner_px)


if __name__ == "__main__":
    main()
