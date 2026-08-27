"""
Generate every texture the mod ships, from scratch.

Run it and the whole asset set is rebuilt. Nothing here is traced, sampled or
copied from anywhere - each image is drawn from geometry and a named palette, so
the provenance of every pixel is this file.

    python tools/gen_textures.py

Sixteen pixels is not much room. The rule throughout is that shapes must survive
being drawn at 16x16 and then displayed at 32 in an inventory slot: silhouette
first, two tones of shading at most, and no detail smaller than a pixel.
"""

import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "src", "main", "resources", "assets", "bowling", "textures")

# ---------------------------------------------------------------- palette

CREAM      = (245, 242, 236)
CREAM_DARK = (208, 203, 194)
CREAM_EDGE = (176, 170, 160)
STRIPE     = (176, 58, 46)
STRIPE_DK  = (140, 44, 35)

NAVY       = (31, 42, 68)
NAVY_LIT   = (58, 76, 116)
NAVY_DARK  = (12, 16, 26)
HOLE       = (8, 10, 16)

WOOD       = (186, 146, 94)
WOOD_DARK  = (156, 118, 72)
WOOD_LINE  = (128, 94, 56)

# Lighter than the ball on purpose. A gutter that reads as near-black looks
# like a hole in the world rather than a channel beside the lane.
GUTTER     = (72, 82, 108)
GUTTER_LIT = (96, 106, 134)
GUTTER_DK  = (52, 60, 82)

CLEAR = None


def write_png(path, rows):
    """Write RGBA rows (list of list of (r,g,b,a) or None for transparent)."""
    h = len(rows)
    w = len(rows[0])
    raw = b""
    for row in rows:
        raw += b"\x00"
        for px in row:
            raw += bytes(px if px else (0, 0, 0, 0))

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c))

    blob = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(blob)
    print(f"  {w}x{h}  {os.path.relpath(path, ROOT)}")


def blank(w=16, h=16):
    return [[CLEAR for _ in range(w)] for _ in range(h)]


def opaque(colour, w=16, h=16):
    return [[colour + (255,) for _ in range(w)] for _ in range(h)]


# ---------------------------------------------------------------- the pin
#
# Half-widths per row, mirrored about the centre. A real pin is a neck that
# swells to a belly and tucks to a base; these numbers are that profile
# flattened onto sixteen rows.
PIN_PROFILE = [2, 2, 3, 3, 3, 2, 2, 5, 5, 5, 5, 5, 4, 4, 4, 4]
PIN_STRIPES = (8, 10)          # rows carrying the red bands


def pin_item():
    img = blank()
    for y, half in enumerate(PIN_PROFILE):
        for x in range(8 - half, 8 + half):
            edge = x == 8 - half or x == 8 + half - 1
            if y in PIN_STRIPES:
                c = STRIPE_DK if edge else STRIPE
            else:
                # light from the upper left: the left third stays bright,
                # the right edge falls away
                c = CREAM_EDGE if edge and x > 8 else (CREAM_DARK if x > 8 + half - 3 else CREAM)
            img[y][x] = c + (255,)
    return img


def pin_entity():
    """Same pin, no transparency - used on the 3D model, so it must tile."""
    img = opaque(CREAM)
    for y in range(16):
        for x in range(16):
            if y in PIN_STRIPES:
                img[y][x] = STRIPE + (255,)
            elif x > 11:
                img[y][x] = CREAM_DARK + (255,)
            elif x > 13:
                img[y][x] = CREAM_EDGE + (255,)
    return img


# ---------------------------------------------------------------- the ball

BALL_PROFILE = [0, 4, 5, 6, 7, 7, 7, 7, 7, 7, 7, 7, 6, 5, 4, 0]
HOLES = [(5, 5), (7, 4), (6, 7)]


def ball_item():
    img = blank()
    for y, half in enumerate(BALL_PROFILE):
        if half == 0:
            continue
        for x in range(8 - half, 8 + half):
            # A tight specular up and left of centre, not a broad wash - that
            # small bright patch is the only thing telling the eye this is a
            # sphere rather than a dark disc.
            dx, dy = x - 5.4, y - 4.8
            spec = (dx * dx + dy * dy) ** 0.5
            rx, ry = x - 9.5, y - 10.5
            rim = (rx * rx + ry * ry) ** 0.5
            if spec < 1.9:
                c = NAVY_LIT
            elif spec < 2.9:
                c = tuple((a + b) // 2 for a, b in zip(NAVY_LIT, NAVY))
            elif rim < 3.4 or x >= 8 + half - 1 or y >= 13:
                c = NAVY_DARK
            else:
                c = NAVY
            img[y][x] = c + (255,)
    for hx, hy in HOLES:
        img[hy][hx] = HOLE + (255,)
    return img


# ---------------------------------------------------------------- blocks

def lane_board():
    """Planks running along the lane, with the seams a bowler sights down."""
    img = opaque(WOOD)
    for y in range(16):
        for x in range(16):
            if x in (0, 5, 10):
                img[y][x] = WOOD_LINE + (255,)
            elif x in (1, 6, 11):
                img[y][x] = WOOD_DARK + (255,)
            elif (x * 7 + y * 3) % 11 == 0:
                img[y][x] = WOOD_DARK + (255,)   # grain, sparse and irregular
    return img


def lane_top():
    img = lane_board()
    for x in range(16):
        img[0][x] = WOOD_LINE + (255,)           # board joint at the near edge
    return img


def lane():
    """The foul line block: boards with a painted marker across them."""
    img = lane_board()
    for x in range(16):
        img[7][x] = STRIPE + (255,)
        img[8][x] = STRIPE_DK + (255,)
    return img


def gutter():
    """A channel: dark in the middle, lit lip either side."""
    img = opaque(GUTTER)
    for y in range(16):
        for x in range(16):
            if x in (0, 15):
                img[y][x] = GUTTER_LIT + (255,)
            elif 5 <= x <= 10:
                img[y][x] = GUTTER_DK + (255,)
    return img


def main():
    print("regenerating bowling textures from geometry:")
    write_png(os.path.join(ROOT, "item", "pin.png"), pin_item())
    write_png(os.path.join(ROOT, "item", "ball.png"), ball_item())
    write_png(os.path.join(ROOT, "item", "pin3d.png"), pin_entity())
    write_png(os.path.join(ROOT, "entity", "pin.png"), pin_entity())
    write_png(os.path.join(ROOT, "block", "lane_board.png"), lane_board())
    write_png(os.path.join(ROOT, "block", "lane_top.png"), lane_top())
    write_png(os.path.join(ROOT, "block", "lane.png"), lane())
    write_png(os.path.join(ROOT, "block", "gutter.png"), gutter())
    print("done - every pixel above is defined in this file")


if __name__ == "__main__":
    main()
