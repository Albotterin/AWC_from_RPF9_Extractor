"""Draws the logos of stations that the app invents itself, into logos/ (shipped with the project, our own design).

"Clubmixes" is not a station of the game, so it has no logo there. Needs Pillow and the Windows font Bahnschrift
(only the finished picture is shipped, not the font).

    python make_station_logos.py
"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
W = 1024                                   # drawn large, saved at 256
VIOLET, BLUE = (104, 18, 190), (38, 24, 150)
LIME, MAGENTA, WHITE = (200, 255, 46), (255, 38, 160), (255, 255, 255)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def lettering(size):
    f = ImageFont.truetype(os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", "bahnschrift.ttf"), size)
    f.set_variation_by_name("Bold Condensed")
    return f


def word(text, width, color, shadow):
    """One word exactly `width` wide, with a second print shifted behind it."""
    font = lettering(400)
    box = font.getbbox(text)
    off = 22
    im = Image.new("RGBA", (box[2] - box[0] + off, box[3] - box[1] + off), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.text((off - box[0], off - box[1]), text, font=font, fill=shadow)
    d.text((-box[0], -box[1]), text, font=font, fill=color)
    return im.resize((width, int(im.height * width / im.width)), Image.LANCZOS)


def clubmixes():
    im = Image.new("RGB", (W, W))
    d = ImageDraw.Draw(im)
    for y in range(W):
        d.line((0, y, W, y), fill=lerp(VIOLET, BLUE, y / W))
    # sound waves from the middle: rings that get thinner and fainter outwards
    rings = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rings)
    for i in range(7):
        r = 190 + i * 88
        rd.ellipse((W // 2 - r, W // 2 - r, W // 2 + r, W // 2 + r), outline=(255, 255, 255, 62 - i * 7), width=30 - i * 3)
    im.paste(rings, (0, 0), rings)
    top = word("CLUB", 800, WHITE, MAGENTA)
    low = word("MIXES", 800, LIME, MAGENTA)
    gap = 34
    bars_h = 130
    total = top.height + gap + low.height + 56 + bars_h
    y = (W - total) // 2
    im.paste(top, ((W - top.width) // 2, y), top)
    y += top.height + gap
    im.paste(low, ((W - low.width) // 2, y), low)
    y += low.height + 56
    # the equalizer under the words
    d = ImageDraw.Draw(im)
    n, bw, space = 19, 28, 15
    x = (W - (n * bw + (n - 1) * space)) // 2
    for i in range(n):
        level = 0.25 + 0.75 * abs(math.sin(i * 0.83) * math.cos(i * 0.31 + 0.4))
        h = int(bars_h * level)
        d.rounded_rectangle((x, y + bars_h - h, x + bw, y + bars_h), radius=bw // 2, fill=LIME if i % 3 else WHITE)
        x += bw + space
    return im.resize((256, 256), Image.LANCZOS)


def main():
    out = os.path.join(HERE, "logos")
    os.makedirs(out, exist_ok=True)
    clubmixes().save(os.path.join(out, "clubmixes.png"), optimize=True)
    print("OK:", out)


if __name__ == "__main__":
    main()
