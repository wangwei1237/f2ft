#!/usr/bin/env python3
"""Generates Android launcher icons from requirements/0002_01_1.png."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "requirements/0002_01_1.png"
RES = ROOT / "android/app/src/main/res"
BG = (0x08, 0x5E, 0x4E)
# Fraction of the 108dp adaptive canvas the artwork occupies, so the bubbles stay inside the 66dp safe zone.
FG_SCALE = 0.74
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

src = Image.open(SRC).convert("RGB")

def foreground(px):
    canvas = Image.new("RGB", (px, px), BG)
    inner = round(px * FG_SCALE)
    art = src.resize((inner, inner), Image.LANCZOS)
    canvas.paste(art, ((px - inner) // 2, (px - inner) // 2))
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
