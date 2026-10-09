#!/usr/bin/env python3
"""Reproduce the final, pixel-refined sakura textures (requires Pillow).

Artwork is authored at native resolution from the ImageGen concepts in
docs/art/concepts. No vanilla texture pixels are copied. Run from any directory.
"""

import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/siasworkshop/textures"
ART = ROOT / "docs/art"


def compass_dial():
    """One immutable dial; pixel centers straddle the (8, 8) pivot."""
    image = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            radius = math.hypot(dx, dy)
            if radius > 7.5:
                continue
            if radius > 6.55:
                color = "#50343f" if dx + dy < 0 else "#382935"
            elif radius > 5.5:
                color = "#e9a8b8" if dx + dy < -2 else "#b76d8b"
            else:
                color = "#fae7df" if dy < 0 else "#efd5d2"
            image.putpixel((x, y), (*bytes.fromhex(color[1:]), 255))

    # Four tiny petal pairs; they frame the pointer without competing with it.
    for x, y in [(4, 4), (10, 4), (4, 10), (10, 10)]:
        image.putpixel((x, y), (221, 150, 171, 255))
        image.putpixel((x + 1, y + 1), (234, 178, 191, 255))
    for x, y in [(7, 2), (13, 7), (8, 13), (2, 8)]:
        image.putpixel((x, y), (144, 90, 116, 255))
    return image


def compass_frame(index, dial):
    """Full static sprite: 00 down, 08 left, 16 up, 24 right.

    Sample the same tapered needle analytically for every 11.25 degree angle.
    This avoids repeatedly rotating/resampling a previously rasterized sprite.
    """
    image = dial.copy()
    angle = math.tau * index / 32
    ux, uy = -math.sin(angle), math.cos(angle)
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            along = dx * ux + dy * uy
            across = dx * uy - dy * ux
            if 0 <= along <= 4.9:
                width = max(0.51, 1.5 * (1 - along / 4.9))
                colors = ((148, 38, 81, 255), (209, 70, 112, 255))
            elif -3.8 <= along < 0:
                width = max(0.51, 1.25 * (1 + along / 3.8))
                colors = ((92, 65, 89, 255), (137, 95, 124, 255))
            else:
                continue
            if abs(across) <= width:
                image.putpixel((x, y), colors[across >= 0])
    # Stationary pearl hub, shared across all orientations.
    for xy, color in [((7, 7), "#fff1db"), ((8, 7), "#fff1db"),
                      ((7, 8), "#d7ad9e"), ((8, 8), "#ba8a8d")]:
        image.putpixel(xy, (*bytes.fromhex(color[1:]), 255))
    return image


def foundation_block():
    """Warm cut-stone footing with cherry boards and a small blossom inlay."""
    image = Image.new("RGB", (16, 16))
    stone = ["#99918b", "#a69c92", "#918981", "#a69c92"]
    wood = ["#d99da5", "#d496a0", "#cc8d9a", "#d99da5"]
    for y in range(16):
        for x in range(16):
            if x < 3 or x > 12 or y < 3 or y > 12:
                color = stone[(x // 2 + y * 3) % 4]
                if x == 0 or y == 0:
                    color = "#b7aca0"
                if x == 15 or y == 15:
                    color = "#706970"
                if (x == 2 and 2 <= y <= 13) or (y == 2 and 2 <= x <= 13):
                    color = "#756c70"
                if (x == 13 and 2 <= y <= 13) or (y == 13 and 2 <= x <= 13):
                    color = "#c5b7aa"
                if (x == 7 and y < 2) or (x == 9 and y > 13):
                    color = "#756c70"
                if (y == 9 and x < 2) or (y == 6 and x > 13):
                    color = "#756c70"
            else:
                color = wood[(x // 3 + y) % 4]
                if y in (3, 7, 10):
                    color = "#e7b2b5"
                if y in (6, 9, 12):
                    color = "#b57988"
            image.putpixel((x, y), tuple(bytes.fromhex(color[1:])))
    blossom = ["..pp..", "..PP..", "pPccPp", "pPPPPp", ".P..P.", ".p..p."]
    colors = {"p": "#e9bcc6", "P": "#ffe0df", "c": "#c76686", "C": "#e495ab"}
    for y, row in enumerate(blossom, 5):
        for x, value in enumerate(row, 5):
            if value != ".":
                image.putpixel((x, y), tuple(bytes.fromhex(colors[value][1:])))
    return image


def review_preview(foundation, frames):
    """Static review sheet, nearest-neighbor enlargement only."""
    preview = Image.new("RGB", (800, 390), "#292630")
    draw = ImageDraw.Draw(preview)
    draw.text((24, 16), "VILLAGE FOUNDATION / 16 x 16", fill="#fae7df")
    draw.text((304, 16), "WILDERNESS COMPASS / 16 x 16", fill="#fae7df")
    preview.paste(foundation.resize((240, 240), Image.Resampling.NEAREST), (24, 44))
    sprite = frames[16].resize((240, 240), Image.Resampling.NEAREST)
    preview.paste(sprite, (304, 44), sprite)
    for i, index in enumerate([16, 24, 0, 8]):
        sprite = frames[index].resize((48, 48), Image.Resampling.NEAREST)
        x = 304 + 64 * i
        preview.paste(sprite, (x, 316), sprite)
    draw.text((590, 46), "3 x 3 material repeat", fill="#fae7df")
    tile = foundation.resize((56, 56), Image.Resampling.NEAREST)
    for y in range(3):
        for x in range(3):
            preview.paste(tile, (590 + x * 56, 68 + y * 56))
    draw.text((24, 310), "Opaque stone + cherry inlay", fill="#fae7df")
    draw.text((24, 332), "Nearest-neighbor enlargement", fill="#bda5b3")
    preview.save(ART / "final-textures-preview.png")


def main():
    foundation = foundation_block()
    foundation.save(ASSETS / "block" / "village_foundation.png")
    dial = compass_dial()
    frames = [compass_frame(i, dial) for i in range(32)]
    for i, frame in enumerate(frames):
        frame.save(ASSETS / "item" / f"wilderness_compass_{i:02d}.png")
    preview = Image.new("RGB", (8 * 112, 4 * 136), "#292630")
    draw = ImageDraw.Draw(preview)
    for i, frame in enumerate(frames):
        x, y = (i % 8) * 112 + 8, (i // 8) * 136 + 8
        sprite = frame.resize((96, 96), Image.Resampling.NEAREST)
        preview.paste(sprite, (x, y), sprite)
        draw.text((x + 36, y + 103), f"{i:02d}", fill="#fae7df")
    preview.save(ART / "compass-frames.png")
    review_preview(foundation, frames)
    print("Wrote foundation, 32 compass textures and two static review sheets.")


if __name__ == "__main__":
    main()
