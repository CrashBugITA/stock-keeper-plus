"""Generates assets/stockkeeperplus/textures/gui/buttons.png.

Each button is 15x16, laid out every 16 px, drawn in the same style (frame, wood stripes, colors)
as the search-sync button of Create's Stock Keeper. Requires Pillow.

Order: 0 sort quantity high->low, 1 sort quantity low->high, 2 sort name, 3 sort mod.
Row 2 (v = 16): favorite star, 5x5 at u = 0.
Row 3 (v = 32): side tabs, 24x22: inactive at u = 0, active at u = 24.
"""
import sys
from PIL import Image

LIGHT = (162, 124, 96, 255)     # top/left frame
CORNER = (152, 110, 86, 255)
STRIPE_A = (140, 93, 75, 255)
STRIPE_B = (135, 90, 77, 255)
SHADE = (113, 74, 64, 255)
DARK = (96, 61, 57, 255)        # bottom/right frame
GLYPH = (74, 45, 49, 255)


def blank():
    b = Image.new("RGBA", (15, 16), (0, 0, 0, 0))
    p = b.load()
    for x in range(1, 14):
        p[x, 0] = LIGHT
        p[x, 14] = GLYPH
    for y in range(1, 14):
        p[0, y] = LIGHT
        p[14, y] = DARK
    p[0, 13] = CORNER
    p[14, 0] = (0, 0, 0, 0)
    p[1, 14] = DARK
    p[14, 13] = GLYPH
    for y in range(1, 14):
        for x in range(1, 14):
            edge = x in (1, 13) or y in (1, 13)
            if edge:
                p[x, y] = STRIPE_A
            else:
                p[x, y] = STRIPE_B if (y - 2) % 4 in (0, 1) else STRIPE_A
    p[1, 1] = CORNER
    return b


def draw(img, pixels, ox, oy):
    p = img.load()
    for y, row in enumerate(pixels):
        for x, ch in enumerate(row):
            if ch == "#":
                p[ox + x, oy + y] = GLYPH
            elif ch == "+":
                p[ox + x, oy + y] = SHADE


BARS_DESC = [
    "#########",
    "#########",
    ".........",
    "######...",
    "######...",
    ".........",
    "###......",
    "###......",
]
BARS_ASC = list(reversed(BARS_DESC))
# A, a down arrow, then Z
NAME = [
    ".#...#...",
    "#.#..#...",
    "###..#...",
    "#.#..#...",
    "#.#.###..",
    ".....#...",
    "......###",
    "........#",
    ".......#.",
    "......#..",
    "......###",
]
MOD = [
    ".#####.",
    "#.....#",
    "#.###.#",
    "#.#.#.#",
    "#.####.",
    "#......",
    ".#####.",
]


STAR_OUTLINE = (74, 45, 49, 255)
STAR_FILL = (255, 214, 79, 255)
STAR_SHADE = (222, 151, 47, 255)
STAR = [
    "..y..",
    "yyyys",
    ".yys.",
    ".y.s.",
    "y...s",
]


def star():
    img = Image.new("RGBA", (5, 5), (0, 0, 0, 0))
    p = img.load()
    colors = {"o": STAR_OUTLINE, "y": STAR_FILL, "s": STAR_SHADE}
    for y, row in enumerate(STAR):
        for x, ch in enumerate(row):
            if ch in colors:
                p[x, y] = colors[ch]
    return img


OUTLINE = (0, 0, 0, 255)
TAB_DARK_A = (112, 74, 64, 255)
TAB_DARK_B = (105, 69, 60, 255)
TAB_DARK_LIGHT = (140, 100, 80, 255)


def tab(active):
    """24x22 tab that sticks out of the window's left edge; its right side is open."""
    w, h = 24, 22
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    p = img.load()
    light = LIGHT if active else TAB_DARK_LIGHT
    a, b = (STRIPE_A, STRIPE_B) if active else (TAB_DARK_A, TAB_DARK_B)
    for y in range(h):
        for x in range(w):
            if x == 0 or y == 0 or y == h - 1:
                if not ((x == 0 and y == 0) or (x == 0 and y == h - 1)):
                    p[x, y] = OUTLINE
                continue
            if y == 1 or x == 1:
                p[x, y] = light
            elif y == h - 2:
                p[x, y] = GLYPH if active else DARK
            else:
                p[x, y] = b if (y - 2) % 4 in (0, 1) else a
    return img


def main(out):
    sheet = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    sheet.alpha_composite(tab(False), (0, 32))
    sheet.alpha_composite(tab(True), (24, 32))
    sheet.alpha_composite(star(), (0, 16))
    for i, (glyph, ox, oy) in enumerate([
        (BARS_DESC, 3, 3), (BARS_ASC, 3, 3), (NAME, 3, 2), (MOD, 4, 4)
    ]):
        b = blank()
        draw(b, glyph, ox, oy)
        sheet.alpha_composite(b, (i * 16, 0))
    sheet.save(out)


if __name__ == "__main__":
    main(sys.argv[1])
