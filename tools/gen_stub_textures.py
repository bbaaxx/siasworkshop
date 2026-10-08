#!/usr/bin/env python3
"""Generates the stub textures for Sia's Workshop so resource loading works before
final art lands. Stubs are simple flat-color 16x16 PNGs; replace them with real art
per docs/art/texture-specs.md (keep the file names)."""

import math
import struct
import zlib
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "assets" / "siasworkshop" / "textures"


def write_png(path: Path, pixels):
    """pixels: 16 rows of 16 RGBA tuples."""
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in pixels)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw))
           + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def needle_frame(index: int):
    """One compass needle pointing at index/32 of a full turn; frame 16 points up."""
    angle = 2.0 * math.pi * index / 32.0
    cos_a, sin_a = math.cos(angle), math.sin(angle)
    transparent = (0, 0, 0, 0)
    body = (72, 52, 44, 255)
    tip = (224, 122, 158, 255)  # cherry blossom accent
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            # rotate pixel into the needle's frame: +y is "pointing" direction
            dx, dy = x - 7.5, 7.5 - y
            xr = dx * cos_a - dy * sin_a
            yr = dx * sin_a + dy * cos_a
            if abs(xr) <= 1.2 and -6.0 <= yr <= 6.0:
                row.append(tip if yr < 0 else body)
            elif abs(xr) <= 1.6 and -1.6 <= yr <= 1.6:
                row.append(body)  # center pivot
            else:
                row.append(transparent)
        pixels.append(row)
    return pixels


def foundation_block():
    """Flat warm-gray border (stone footing) around a cherry plank center."""
    border = (122, 112, 100, 255)
    plank = (216, 141, 160, 255)
    seam = (188, 113, 134, 255)
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            if x < 2 or x > 13 or y < 2 or y > 13:
                row.append(border)
            elif x in (7, 8) or y in (4, 9):
                row.append(seam)
            else:
                row.append(plank)
        pixels.append(row)
    return pixels


def main():
    item_dir = ASSETS / "item"
    for i in range(32):
        write_png(item_dir / f"wilderness_compass_{i:02d}.png", needle_frame(i))
    write_png(ASSETS / "block" / "village_foundation.png", foundation_block())
    print(f"wrote 32 compass frames to {item_dir} and village_foundation.png to {ASSETS / 'block'}")


if __name__ == "__main__":
    main()
