#!/usr/bin/env python3
"""Generates Android launcher icons from requirements/icon.png."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "requirements/icon.png"
RES = ROOT / "android/app/src/main/res"
BG = (0x01, 0x5C, 0x4F)
# Fraction of the 108dp adaptive canvas the artwork occupies, so the bubbles stay inside the 66dp safe zone.
FG_SCALE = 0.70
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

src = Image.open(SRC).convert("RGB")

def backdrop(px):
    """Extends the source's gradient background past its edges by interpolating its four corner colors."""
    w, h = src.size
    corners = [src.getpixel(p) for p in [(5, 5), (w - 6, 5), (5, h - 6), (w - 6, h - 6)]]
    tiny = Image.new("RGB", (2, 2))
    tiny.putdata(corners)
    return tiny.resize((px, px), Image.BILINEAR)

def foreground(px):
    canvas = backdrop(px)
    inner = round(px * FG_SCALE)
    art = src.resize((inner, inner), Image.LANCZOS)
    # Feather the pasted square so its border blends into the extended background.
    edge = max(2, inner // 14)
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).rectangle((edge, edge, inner - 1 - edge, inner - 1 - edge), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(edge / 2))
    canvas.paste(art, ((px - inner) // 2, (px - inner) // 2), mask)
    return canvas

for name, scale in DENSITIES.items():
    out = RES / f"mipmap-{name}"
    out.mkdir(parents=True, exist_ok=True)
    foreground(round(108 * scale)).save(out / "ic_launcher_foreground.png", optimize=True)
    legacy = round(48 * scale)
    square = src.resize((legacy, legacy), Image.LANCZOS)
    square.save(out / "ic_launcher.png", optimize=True)
    mask = Image.new("L", (legacy * 4, legacy * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, legacy * 4 - 1, legacy * 4 - 1), fill=255)
    rnd = square.convert("RGBA")
    rnd.putalpha(mask.resize((legacy, legacy), Image.LANCZOS))
    rnd.save(out / "ic_launcher_round.png", optimize=True)

src.resize((512, 512), Image.LANCZOS).save(ROOT / "android/app/ic_launcher-playstore.png", optimize=True)
print("done")
