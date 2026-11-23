"""Draws the launcher icon of the Android app (an original sunset / palm / art-deco design, inspired by the look of
the GTA VI typeface preview, nothing copied from Rockstar's artwork or logos).

The finished PNGs are committed (res/), so this script is only needed to change the design. The typeface "GTA Art Deco"
is NOT part of this repository: pass the path of its .ttf file (it is a fan-made font, get it from its author).

    python make_logo.py "path\\to\\GTA-Art-Deco-Medium.ttf"
"""
import math
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
if len(sys.argv) < 2:
    raise SystemExit('Usage: python make_logo.py "path\\to\\GTA-Art-Deco-Medium.ttf"')
FONT = sys.argv[1]
SS = 3                                   # supersampling factor
S = 432                                  # adaptive icon layers are 108 dp = 432 px at xxxhdpi
W = S * SS


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(stops, size):
    """Vertical gradient; stops = [(position 0..1, (r,g,b)), ...]."""
    im = Image.new("RGB", (1, size))
    px = im.load()
    for y in range(size):
        t = y / (size - 1)
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if p0 <= t <= p1:
                px[0, y] = lerp(c0, c1, (t - p0) / (p1 - p0) if p1 > p0 else 0)
                break
    return im.resize((size, size))


def bezier(p0, p1, p2, n=24):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in (i / n for i in range(n + 1))]


def tapered(points, w0, w1):
    """Polygon around a polyline whose width goes from w0 to w1."""
    left, right = [], []
    n = len(points)
    for i, (x, y) in enumerate(points):
        x2, y2 = points[min(i + 1, n - 1)]
        x1, y1 = points[max(i - 1, 0)]
        dx, dy = x2 - x1, y2 - y1
        ln = math.hypot(dx, dy) or 1
        nx, ny = -dy / ln, dx / ln
        w = (w0 + (w1 - w0) * i / (n - 1)) / 2
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
    return left + right[::-1]


def palm(d, x, y, h, lean, color):
    """Palm tree silhouette: base at (x, y), height h, trunk leaning by `lean` pixels."""
    top = (x + lean, y - h)
    trunk = bezier((x, y), (x + lean * 0.15, y - h * 0.55), top)
    d.polygon(tapered(trunk, h * 0.075, h * 0.04), fill=color)
    cx, cy = top
    # fronds: (angle in degrees from the right, length factor)
    for ang, ln in ((-170, 0.46), (-145, 0.52), (-118, 0.46), (-90, 0.40), (-62, 0.46), (-35, 0.52), (-10, 0.46), (-155, 0.34), (-25, 0.34)):
        a = math.radians(ang)
        L = h * ln
        end = (cx + math.cos(a) * L, cy + math.sin(a) * L * 0.55 + L * 0.55)          # droops down at the tip
        ctrl = (cx + math.cos(a) * L * 0.55, cy + math.sin(a) * L * 0.9 - L * 0.12)
        frond = bezier((cx, cy), ctrl, end)
        d.polygon(tapered(frond, h * 0.10, h * 0.008), fill=color)
    d.ellipse((cx - h * 0.035, cy - h * 0.035, cx + h * 0.035, cy + h * 0.035), fill=color)


def background():
    sky = gradient([(0.0, (74, 58, 160)), (0.30, (150, 84, 190)), (0.52, (240, 98, 160)), (0.70, (255, 140, 96)), (0.86, (255, 188, 100)),
                    (1.0, (255, 214, 130))], W)
    im = sky.copy()
    d = ImageDraw.Draw(im)
    horizon = int(W * 0.74)
    # sun
    sun_r, sun_c = int(W * 0.21), (W // 2, horizon - int(W * 0.005))
    sun = Image.new("RGB", (W, W))
    sun.paste(gradient([(0.0, (255, 236, 170)), (1.0, (255, 150, 110))], W))
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).ellipse((sun_c[0] - sun_r, sun_c[1] - sun_r, sun_c[0] + sun_r, sun_c[1] + sun_r), fill=255)
    # thin dark stripes through the lower part of the sun (retro look)
    md = ImageDraw.Draw(mask)
    for i in range(5):
        y = sun_c[1] - int(sun_r * 0.05) + i * int(W * 0.022)
        md.rectangle((0, y, W, y + int(W * (0.004 + i * 0.0035))), fill=0)
    im.paste(sun, (0, 0), mask)
    # water / ground
    ground = gradient([(0.0, (92, 52, 140)), (1.0, (40, 26, 84))], W).crop((0, 0, W, W - horizon)).resize((W, W - horizon))
    im.paste(ground, (0, horizon))
    d = ImageDraw.Draw(im)
    # light reflection stripes of the sun on the water
    for i in range(6):
        y = horizon + int(W * 0.02) + i * int(W * 0.03)
        half = int(sun_r * (0.8 - i * 0.1))
        d.rectangle((W // 2 - half, y, W // 2 + half, y + int(W * 0.008)), fill=(255, 170, 120))
    # palms
    col = (46, 28, 92)
    palm(d, int(W * 0.17), horizon + int(W * 0.04), int(W * 0.27), -int(W * 0.035), col)
    palm(d, int(W * 0.285), horizon + int(W * 0.03), int(W * 0.15), int(W * 0.02), col)
    palm(d, int(W * 0.84), horizon + int(W * 0.04), int(W * 0.26), int(W * 0.035), col)
    palm(d, int(W * 0.725), horizon + int(W * 0.03), int(W * 0.14), -int(W * 0.02), col)
    return im.resize((S, S), Image.LANCZOS)


def spaced_text(d, center, text, font, fill, tracking):
    widths = [d.textlength(ch, font=font) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)
    x = center[0] - total / 2
    for ch, w in zip(text, widths):
        d.text((x, center[1]), ch, font=font, fill=fill, anchor="lm")
        x += w + tracking


def foreground():
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    big = ImageFont.truetype(FONT, int(W * 0.235))
    small = ImageFont.truetype(FONT, int(W * 0.062))
    # soft shadow for contrast on the bright sky
    shadow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    spaced_text(sd, (W // 2 + 3 * SS, int(W * 0.35) + 4 * SS), "AWC", big, (60, 20, 100, 150), int(W * 0.018))
    shadow = shadow.filter(ImageFilter.GaussianBlur(5 * SS))
    im = Image.alpha_composite(im, shadow)
    d = ImageDraw.Draw(im)
    shadow2 = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    sd2 = ImageDraw.Draw(shadow2)
    spaced_text(d, (W // 2, int(W * 0.35)), "AWC", big, (255, 255, 255, 255), int(W * 0.018))
    d.line((int(W * 0.33), int(W * 0.462), int(W * 0.67), int(W * 0.462)), fill=(255, 255, 255, 230), width=int(W * 0.006))
    spaced_text(sd2, (W // 2, int(W * 0.522) + 2 * SS), "RADIO", small, (80, 20, 110, 170), int(W * 0.024))
    im = Image.alpha_composite(im, shadow2.filter(ImageFilter.GaussianBlur(3 * SS)))
    d = ImageDraw.Draw(im)
    spaced_text(d, (W // 2, int(W * 0.522)), "RADIO", small, (255, 255, 255, 255), int(W * 0.024))
    return im.resize((S, S), Image.LANCZOS)


def main():
    res = os.path.join(HERE, "res")
    os.makedirs(os.path.join(res, "drawable-xxxhdpi"), exist_ok=True)
    os.makedirs(os.path.join(res, "mipmap-xxxhdpi"), exist_ok=True)
    os.makedirs(os.path.join(res, "mipmap-anydpi-v26"), exist_ok=True)
    bg, fg = background(), foreground()
    bg.save(os.path.join(res, "drawable-xxxhdpi", "ic_launcher_background.png"), optimize=True)
    fg.save(os.path.join(res, "drawable-xxxhdpi", "ic_launcher_foreground.png"), optimize=True)
    with open(os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n')
    # legacy icon (Android 7): rounded square with the visible 72 dp of the layers
    full = bg.convert("RGBA")
    full.alpha_composite(fg)
    crop = full.crop((int(S * 0.1667), int(S * 0.1667), int(S * 0.8333), int(S * 0.8333))).resize((192, 192), Image.LANCZOS)
    mask = Image.new("L", (192 * 4, 192 * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 192 * 4 - 1, 192 * 4 - 1), radius=40 * 4, fill=255)
    mask = mask.resize((192, 192), Image.LANCZOS)
    legacy = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
    legacy.paste(crop, (0, 0), mask)
    legacy.save(os.path.join(res, "mipmap-xxxhdpi", "ic_launcher.png"), optimize=True)
    # preview for a quick look (not part of the app)
    prev = Image.new("RGB", (S * 2 + 60, S + 40), (30, 30, 36))
    prev.paste(full.convert("RGB"), (20, 20))
    prev.paste(legacy, (S + 40, 20 + (S - 192) // 2), legacy)
    prev.save(os.path.join(HERE, "build", "logo_preview.png") if os.path.isdir(os.path.join(HERE, "build")) else "logo_preview.png")
    print("OK")


if __name__ == "__main__":
    main()
