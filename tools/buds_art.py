"""Renders the Buds illustration (port of BudsArt.kt) to PNGs for visual iteration.

Usage: python tools/buds_art.py [outdir]   (needs Pillow). Keep the geometry in sync with BudsArt.kt.
"""
import math
import os
import sys
from PIL import Image, ImageDraw

BODY = (0x3A, 0x3A, 0x3E)
EDGE = (0x5A, 0x5A, 0x60)
GRILLE = (0x77, 0x77, 0x7D)
FILL = (0xEC, 0xEC, 0xEE)
ACCENT = (0xD7, 0x19, 0x21)
PLATE = (0x10, 0x10, 0x12)
DIM_BODY = (0x26, 0x26, 0x2A)
DIM_EDGE = (0x32, 0x32, 0x36)

# Bud outline = smooth union (fillet) of a head ellipse and a tapered stem, sampled as a polygon of half-widths.
# style: head centre y, head rx, head ry | stem half-width at head / at tip | stem start y, bottom y | fillet
SHAPES = {
    "STEM": dict(hx=50, hcy=30, rx=28, ry=29, s0=15, s1=12, sy=24, sb=114, fil=10),    "ROUND": dict(hx=50, hcy=33, rx=33, ry=33, s0=17, s1=14, sy=36, sb=98, fil=8),}
TILT = 5.0          # degrees, stem tips lean inwards
PAIR = {"STEM": (150.0, 120.0, 62.0), "ROUND": (160.0, 110.0, 72.0)}


def half_width(s, y):
    ell = 0.0
    dy = (y - s["hcy"]) / s["ry"]
    if abs(dy) < 1:
        ell = s["rx"] * math.sqrt(1 - dy * dy)
    st = 0.0
    if y >= s["sy"]:
        t = min(1.0, (y - s["sy"]) / (s["sb"] - s["sy"]))
        r_end = s["s1"]
        st = s["s0"] + (s["s1"] - s["s0"]) * t
        yc = s["sb"] - r_end
        if y > yc:
            d = y - yc
            st = math.sqrt(max(0.0, r_end * r_end - d * d)) if d < r_end else 0.0
    if ell <= 0 or st <= 0:
        return max(ell, st)
    k = s["fil"]
    h = min(1.0, max(0.0, 0.5 + 0.5 * (ell - st) / k))
    return st * (1 - h) + ell * h + k * h * (1 - h)


def outline(style, inset):
    s = SHAPES[style]
    ys = [i * 0.5 for i in range(0, int((s["sb"] + 1) * 2))]
    left, right = [], []
    for y in ys:
        w = half_width(s, y) - inset
        if w > 0.05:
            left.append((s["hx"] - w, y))
            right.append((s["hx"] + w, y))
    return left + right[::-1]


def xform(pts, mirror, k, ox, oy, pivot):
    out = []
    a = math.radians(-TILT if not mirror else TILT)
    ca, sa = math.cos(a), math.sin(a)
    for x, y in pts:
        dx, dy = x - pivot[0], y - pivot[1]
        x, y = pivot[0] + dx * ca - dy * sa, pivot[1] + dx * sa + dy * ca
        if mirror:
            x = 2 * pivot[0] - x
        out.append((x * k + ox, y * k + oy))
    return out


def fill_shape(d, style, inset, color, mirror, k, ox, oy):
    s = SHAPES[style]
    d.polygon(xform(outline(style, inset), mirror, k, ox, oy, (s["hx"], s["hcy"])), fill=color)


def draw_bud(img, style, mirror, level, connected, k, ox, oy):
    s = SHAPES[style]
    d = ImageDraw.Draw(img)
    low = connected and 0 <= level <= 15
    fill_shape(d, style, 0, EDGE if connected else DIM_EDGE, mirror, k, ox, oy)
    fill_shape(d, style, 2.0, BODY if connected else DIM_BODY, mirror, k, ox, oy)
    if connected and 0 <= level <= 100:
        top = s["hcy"] - s["ry"] + 2.0
        bottom = s["sb"] - 2.0
        y_fill = bottom - (bottom - top) * level / 100.0
        m = Image.new("L", img.size, 0)
        fill_shape(ImageDraw.Draw(m), style, 2.0, 255, mirror, k, ox, oy)
        from PIL import ImageChops
        yy = y_fill * k + oy
        band = Image.new("L", img.size, 0)
        ImageDraw.Draw(band).rectangle([0, yy, img.size[0], img.size[1]], fill=255)
        m = ImageChops.multiply(m, band)
        img.paste(Image.new("RGB", img.size, ACCENT if low else FILL), (0, 0), m)
    pivot = (s["hx"], s["hcy"])
    for i in (-1, 0, 1):
        for j in (-1, 0, 1):
            p = xform([(s["hx"] + i * 8.5, s["hcy"] + j * 8.5)], mirror, k, ox, oy, pivot)[0]
            r = 2.0 * k
            d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=GRILLE if connected else (0x32, 0x32, 0x36))

def render(w, h, style, left, right, connected, plate=True, radius=10, ss=4):
    W, H = w * ss, h * ss
    img = Image.new("RGB", (W, H), PLATE)
    pw, ph, rdx = PAIR[style]
    k = min(W / pw, H / ph) * 0.90
    ox = (W - pw * k) / 2
    oy = (H - ph * k) / 2
    draw_bud(img, style, False, left, connected, k, ox, oy)
    draw_bud(img, style, True, right, connected, k, ox + rdx * k, oy)
    return img.resize((w, h), Image.LANCZOS)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "out")
    os.makedirs(out, exist_ok=True)
    cases = [
        ("stem_full", "STEM", 82, 75, True), ("stem_low", "STEM", 12, 60, True), ("stem_off", "STEM", -1, -1, False),
        ("round_mid", "ROUND", 55, 40, True), ("stem_unknown", "STEM", -1, -1, True),
    ]
    sheet = Image.new("RGB", (len(cases) * 260, 260), PLATE)
    for i, (n, st, l, r, c) in enumerate(cases):
        im = render(256, 256, st, l, r, c)
        im.save(os.path.join(out, n + ".png"))
        sheet.paste(im, (i * 260, 0))
    sheet.save(os.path.join(out, "sheet.png"))
    wide = render(140, 70, "STEM", 80, 75, True, ss=6)
    wide.save(os.path.join(out, "wide.png"))
    print("ok", out)


if __name__ == "__main__":
    main()





