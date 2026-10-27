#!/usr/bin/env python3
"""Generate all launcher + PWA icons for Shelf from a single source drawing.

Run from anywhere:  python3 scripts/make-icons.py
Requires Pillow (pip install Pillow). The generated PNGs are committed so the
APK build and the GitHub Actions workflow don't need Pillow.
"""

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SS = 4  # supersampling factor for smooth edges

BLUE = "#2B50FF"
WHITE = "#FFFFFF"
YELLOW = "#FFB400"

DENSITIES = {
    "mipmap-mdpi": 1.0,
    "mipmap-hdpi": 1.5,
    "mipmap-xhdpi": 2.0,
    "mipmap-xxhdpi": 3.0,
    "mipmap-xxxhdpi": 4.0,
}


def glyph(draw, cx, cy, box):
    """White play triangle over a yellow bar, sized to `box` (square)."""
    # Reference design is a 32x32 canvas: triangle (12,8.5)-(12,17.5)-(20,13)
    # and a bar at x=7..25, y=22..25 with 1.5 corner radius.
    x0, y0 = cx - box / 2, cy - box / 2
    k = box / 32.0

    def px(x, y):
        return x0 + x * k, y0 + y * k

    draw.polygon([px(12, 8.5), px(12, 17.5), px(20, 13)], fill=WHITE)
    draw.rounded_rectangle([px(7, 22), px(25, 25)], radius=1.5 * k, fill=YELLOW)


def canvas(size, color):
    img = Image.new("RGBA", (size * SS, size * SS), color)
    return img, ImageDraw.Draw(img)


def finish(img, size, path):
    img.resize((size, size), Image.Resampling.LANCZOS).save(path, "PNG")
    print("  ", path.relative_to(ROOT), f"{size}x{size}")


def legacy(size, path, rounded=True):
    """Full-bleed blue squircle / circle with the glyph (pre-Android-8 icon)."""
    img, d = canvas(size, (0, 0, 0, 0))
    if rounded:
        d.rounded_rectangle([0, 0, size * SS, size * SS], radius=0.28 * size * SS, fill=BLUE)
    else:
        d.ellipse([0, 0, size * SS, size * SS], fill=BLUE)
    glyph(d, size * SS / 2, size * SS / 2, size * SS * 0.78)
    finish(img, size, path)


def adaptive_foreground(size, path):
    """Transparent 108dp canvas; glyph kept inside the ~66dp safe zone."""
    img, d = canvas(size, (0, 0, 0, 0))
    glyph(d, size * SS / 2, size * SS / 2, size * SS * 0.50)
    finish(img, size, path)


def maskable_web(size, path):
    """Full-bleed blue with glyph inside the maskable safe zone."""
    img, d = canvas(size, BLUE)
    glyph(d, size * SS / 2, size * SS / 2, size * SS * 0.46)
    finish(img, size, path)


def main():
    print("Generating Android launcher icons:")
    for folder, scale in DENSITIES.items():
        out = ROOT / "app" / "res" / folder
        out.mkdir(parents=True, exist_ok=True)
        legacy(round(48 * scale), out / "ic_launcher.png", rounded=True)
        legacy(round(48 * scale), out / "ic_launcher_round.png", rounded=False)
        adaptive_foreground(round(108 * scale), out / "ic_launcher_foreground.png")

    print("Generating PWA icons:")
    www = ROOT / "www"
    www.mkdir(parents=True, exist_ok=True)
    legacy(192, www / "icon-192.png")
    legacy(512, www / "icon-512.png")
    maskable_web(512, www / "icon-maskable-512.png")
    print("Done.")


if __name__ == "__main__":
    main()
