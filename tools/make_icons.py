"""Draws the TV home-screen banner and launcher icon: white contour rings on black, like the moon scenes.
Usage: python tools/make_icons.py  (writes into app/src/main/res)."""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
SS = 4  # supersampling factor


def contours(draw, cx, cy, r0, rings, step, width, seed=0.0):
    """Wobbly concentric rings, brighter toward the outside."""
    for k in range(rings):
        r = r0 + k * step
        pts = []
        for i in range(181):
            a = i / 180 * 2 * math.pi
            wob = 1 + 0.06 * math.sin(3 * a + seed + k * 0.7) + 0.04 * math.sin(5 * a - seed * 2 + k * 0.4)
            pts.append((cx + r * wob * math.cos(a), cy + r * wob * math.sin(a)))
        shade = int(110 + 145 * (k + 1) / rings)
        draw.line(pts, fill=(shade, shade, shade), width=width, joint="curve")


def font(size, bold=False):
    for name in (["segoeuib.ttf", "arialbd.ttf"] if bold else ["segoeui.ttf", "arial.ttf"]):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def banner():
    w, h = 320 * SS, 180 * SS
    img = Image.new("RGB", (w, h), "black")
    d = ImageDraw.Draw(img)
    contours(d, 250 * SS, 90 * SS, 10 * SS, 6, 11 * SS, 2 * SS, seed=1.3)
    contours(d, 205 * SS, 140 * SS, 6 * SS, 3, 9 * SS, 2 * SS, seed=4.0)
    d.text((20 * SS, 58 * SS), "Fleece", font=font(40 * SS, bold=True), fill="white")
    d.text((22 * SS, 104 * SS), "Screensavers", font=font(20 * SS), fill=(190, 190, 190))
    img.resize((320, 180), Image.LANCZOS).save(RES / "drawable-xhdpi" / "banner.png", optimize=True)


def icon():
    s = 192 * SS
    img = Image.new("RGB", (s, s), "black")
    d = ImageDraw.Draw(img)
    contours(d, s / 2, s / 2, 12 * SS, 6, 13 * SS, 3 * SS, seed=1.3)
    img.resize((192, 192), Image.LANCZOS).save(RES / "mipmap-xxxhdpi" / "ic_launcher.png", optimize=True)


if __name__ == "__main__":
    banner()
    icon()
