#!/usr/bin/env python3
"""
Draws the TaskMind mark and writes the PNG icons the web app serves.

WHY THIS EXISTS RATHER THAN A CHECKED-IN BINARY NOBODY CAN EDIT

The mark is a rounded square in the app's own green with a tick through it -
the same green as the Android theme's primary, so the browser tab and the
phone agree. It is defined once, here, in numbers you can change: adjust
GREEN or the tick geometry, run this, and every size is regenerated
consistently.

Apple's touch icon has to be a PNG - it ignores SVG - which is the only
reason raster files exist at all. Everything else uses app/icon.svg.

No Pillow, no ImageMagick, no rsvg: this environment has none of them, and a
build that depends on a tool the next machine might not have is a build that
breaks later. A PNG is a zlib stream of filtered scanlines, so it is written
directly. Edges are anti-aliased by supersampling each pixel SS x SS and
averaging, which is slow and perfectly adequate for a handful of icons.

    python3 web/tools/make-icons.py
"""

import math
import struct
import zlib
from pathlib import Path

GREEN = (0x1D, 0x5D, 0x4B)   # matches LightColors.primary in the Android theme
INK = (0xFF, 0xFF, 0xFF)

SS = 4  # supersampling factor per axis


def rounded_rect(x, y, w, h, r):
    """Signed-ish inside test for a rounded rectangle at the origin."""
    def inside(px, py):
        if px < x or py < y or px > x + w or py > y + h:
            return False
        # Corner circles; the straight edges are the box minus the corner squares.
        cx = min(max(px, x + r), x + w - r)
        cy = min(max(py, y + r), y + h - r)
        return (px - cx) ** 2 + (py - cy) ** 2 <= r * r
    return inside


def thick_polyline(points, width):
    """Inside test for a polyline stroked with round joins and caps."""
    half = width / 2.0

    def dist_to_segment(px, py, ax, ay, bx, by):
        dx, dy = bx - ax, by - ay
        if dx == 0 and dy == 0:
            return math.hypot(px - ax, py - ay)
        t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        t = max(0.0, min(1.0, t))
        return math.hypot(px - (ax + t * dx), py - (ay + t * dy))

    def inside(px, py):
        for i in range(len(points) - 1):
            ax, ay = points[i]
            bx, by = points[i + 1]
            if dist_to_segment(px, py, ax, ay, bx, by) <= half:
                return True
        return False
    return inside


def render(size):
    """The mark at `size` px, as RGBA rows."""
    s = float(size)
    # Geometry as fractions of the canvas, so every size is the same drawing.
    plate = rounded_rect(0.0, 0.0, s, s, s * 0.22)
    tick = thick_polyline(
        [(s * 0.27, s * 0.52), (s * 0.44, s * 0.69), (s * 0.75, s * 0.34)],
        width=s * 0.115,
    )

    rows = []
    step = 1.0 / SS
    samples = SS * SS
    for py in range(size):
        row = bytearray()
        for px in range(size):
            plate_hits = 0
            tick_hits = 0
            for sy in range(SS):
                fy = py + (sy + 0.5) * step
                for sx in range(SS):
                    fx = px + (sx + 0.5) * step
                    if plate(fx, fy):
                        plate_hits += 1
                        if tick(fx, fy):
                            tick_hits += 1
            if plate_hits == 0:
                row += bytes((0, 0, 0, 0))
                continue
            alpha = plate_hits / samples
            # Coverage of the tick WITHIN the plate, so the stroke can never
            # bleed past the rounded corners.
            t = tick_hits / plate_hits
            colour = tuple(
                round(GREEN[i] * (1 - t) + INK[i] * t) for i in range(3)
            )
            row += bytes((colour[0], colour[1], colour[2], round(alpha * 255)))
        rows.append(bytes(row))
    return rows


def write_png(path, rows, size):
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    Path(path).write_bytes(png)
    print(f"{path}  {size}x{size}  {len(png)} bytes")


if __name__ == "__main__":
    here = Path(__file__).resolve().parent.parent
    # Apple ignores SVG icons, so this one has to be raster. 180 is the size
    # iOS actually asks for.
    write_png(here / "app" / "apple-icon.png", render(180), 180)
