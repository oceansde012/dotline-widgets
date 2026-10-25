"""Builds the README banner and the GitHub social preview from the real widget crops in docs/media/widgets/.

  python tools/make_media.py        (needs Pillow)   -> docs/media/hero.png (1600x800), docs/media/social-preview.png (1280x640)

Dot glyphs come from DotText.kt through tools/preview_dots.py, the label font is the app's IBM Plex Mono.
Upload social-preview.png by hand: repository Settings > General > Social preview.
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from preview_dots import layout  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MEDIA = ROOT / "docs/media"
FONTS = ROOT / "app/src/main/res/font"
W, H, SS = 1600, 800, 2
BG, OFF, FG, DIM, RED = (0, 0, 0), (44, 44, 47), (242, 242, 242), (142, 142, 147), (215, 25, 33)


def grid(img, rings, holes=()):
    """the dot field of the promo: unlit dots everywhere, brighter along rings around a point"""
    d = ImageDraw.Draw(img, "RGBA")
    pitch = 44 * SS
    for j in range(H * SS // pitch + 1):
        for i in range(W * SS // pitch + 1):
            x, y = (i + 0.5) * pitch, (j + 0.5) * pitch
            if any(l * SS <= x <= r * SS and t * SS <= y <= b * SS for l, t, r, b in holes):
                continue
            k, red = 0.0, 0.0
            for cx, cy, r, amp, is_red in rings:
                v = math.exp(-(((math.hypot(x - cx * SS, y - cy * SS) - r * SS) / (70 * SS)) ** 2)) * amp
                if is_red:
                    red = max(red, v)
                else:
                    k = max(k, v)
            rad = 3.2 * SS * (1 + 1.1 * max(k, red))
            if red > k:
                col = (int(44 + (RED[0] - 44) * red), int(44 + (RED[1] - 44) * red), int(47 + (RED[2] - 47) * red), int(255 * (0.55 + 0.45 * red)))
            else:
                col = (int(44 + 198 * k), int(44 + 198 * k), int(47 + 195 * k), int(255 * (0.55 + 0.35 * k)))
            d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=col)


def dots(img, text, x, y, pitch):
    """round dots, diameter 0.62 of the pitch, unlit matrix at 60% (as in the app); returns the right edge"""
    d = ImageDraw.Draw(img, "RGBA")
    lit, cols = layout(text)
    r = 0.31 * pitch * SS
    lit = set(lit)
    for c in range(cols):
        for rr in range(7):
            cx, cy = (x + (c + 0.5) * pitch) * SS, (y + (rr + 0.5) * pitch) * SS
            on = (c, rr) in lit
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=FG + (255,) if on else OFF + (70,))
    return x + cols * pitch


def widget(img, name, width, x, y, angle=0.0):
    w = Image.open(MEDIA / "widgets" / f"{name}.png").convert("RGBA")
    h = round(w.height * width / w.width)
    w = w.resize((width * SS, h * SS), Image.LANCZOS)
    if angle:
        w = w.rotate(angle, resample=Image.BICUBIC, expand=True)
    img.alpha_composite(w, (int(x * SS - (w.width - width * SS) / 2), int(y * SS - (w.height - h * SS) / 2)))
    return h


def text(img, s, x, y, font, color):
    ImageDraw.Draw(img).text((x * SS, y * SS), s, font=font, fill=color)


def main():
    img = Image.new("RGBA", (W * SS, H * SS), BG + (255,))
    rings = [(792, 258, 150, 0.9, True), (1160, 420, 470, 0.22, False)]
    grid(img, rings, holes=[(60, 230, 780, 380), (60, 395, 720, 640)])   # keep the type clear of the field

    pitch = 16
    right = dots(img, "DOTLINE", 96, 250, pitch)
    rd = 0.31 * pitch * 1.25 * SS
    cx, cy = (right + 2 * pitch + pitch * 0.5) * SS, (250 + 0.5 * pitch) * SS
    ImageDraw.Draw(img).ellipse([cx - rd, cy - rd, cx + rd, cy + rd], fill=RED)

    f1 = ImageFont.truetype(str(FONTS / "label_medium.ttf"), 38 * SS)
    f2 = ImageFont.truetype(str(FONTS / "label_regular.ttf"), 26 * SS)
    text(img, "Nothing-style dot-matrix", 96, 410, f1, FG)
    text(img, "widgets for any Android.", 96, 462, f1, FG)
    text(img, "Free. Open source. No ads, no account.", 96, 540, f2, DIM)
    text(img, "English  ·  Українська  ·  Русский", 96, 584, f2, DIM)

    widget(img, "head", 700, 820, 90, -1.2)
    widget(img, "time", 330, 820, 262, 2.0)
    widget(img, "weather", 206, 1190, 258, -2.5)
    widget(img, "power", 560, 840, 560, 1.2)

    out = img.convert("RGB").resize((W, H), Image.LANCZOS)
    out.save(MEDIA / "hero.png", optimize=True)
    out.resize((1280, 640), Image.LANCZOS).save(MEDIA / "social-preview.png", optimize=True)
    print("hero.png, social-preview.png")


if __name__ == "__main__":
    main()
