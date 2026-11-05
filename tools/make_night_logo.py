"""Generate the Night Launcher logo files (crescent moon + star on a nebula tile).

Run:  python tools/make_night_logo.py
Writes the launcher icon/mark and the in-game menu logo. Deterministic: the
same script always produces the same images. Needs Pillow.
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAVENDER = (0xC6, 0xA4, 0xFF)
PURPLE2 = (0xA1, 0x6B, 0xEF)
PURPLE = (0x86, 0x52, 0xD9)
MAGENTA = (0xC0, 0x5A, 0xE8)
BG = (0x0B, 0x07, 0x14)
BG2 = (0x1A, 0x10, 0x2B)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(size, stops):
    """Diagonal gradient (top-left to bottom-right) through `stops`."""
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.45 + y / h * 0.55)
            t = min(max(t, 0.0), 1.0) * (len(stops) - 1)
            i = min(int(t), len(stops) - 2)
            px[x, y] = lerp(stops[i], stops[i + 1], t - i)
    return img


def mark(size):
    """Transparent crescent moon with a four-point star, purple gradient."""
    s = size * 4  # supersample
    shape = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(shape)
    cx, cy, r = s * 0.46, s * 0.52, s * 0.36
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    # Bite out the crescent.
    ox, oy, orr = cx + r * 0.42, cy - r * 0.30, r * 0.86
    d.ellipse([ox - orr, oy - orr, ox + orr, oy + orr], fill=0)
    # Four-point star in the crescent's opening.
    sx, sy, sr = s * 0.73, s * 0.30, s * 0.13
    k = sr * 0.22
    d.polygon([(sx, sy - sr), (sx + k, sy - k), (sx + sr, sy), (sx + k, sy + k),
               (sx, sy + sr), (sx - k, sy + k), (sx - sr, sy), (sx - k, sy - k)], fill=255)
    tiny = s * 0.045
    tx, ty = s * 0.84, s * 0.52
    d.polygon([(tx, ty - tiny), (tx + tiny * 0.25, ty - tiny * 0.25), (tx + tiny, ty),
               (tx + tiny * 0.25, ty + tiny * 0.25), (tx, ty + tiny),
               (tx - tiny * 0.25, ty + tiny * 0.25), (tx - tiny, ty),
               (tx - tiny * 0.25, ty - tiny * 0.25)], fill=255)
    fill = gradient((s, s), [LAVENDER, PURPLE2, MAGENTA])
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(fill, (0, 0), shape)
    # Soft glow under the shape.
    glow = Image.new("RGBA", (s, s), PURPLE + (0,))
    glow.putalpha(shape.filter(ImageFilter.GaussianBlur(s * 0.035)).point(lambda v: int(v * 0.55)))
    comp = Image.alpha_composite(glow, out)
    return comp.resize((size, size), Image.LANCZOS)


def nebula_tile(size, rounded=True):
    """Dark rounded tile with soft purple clouds and stars (app icon background)."""
    s = size * 2
    rnd = random.Random(7)
    base = gradient((s, s), [BG2, BG, BG])
    clouds = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    cd = ImageDraw.Draw(clouds)
    for col, cx, cy, r, a in [(PURPLE, 0.30, 0.30, 0.42, 110), (MAGENTA, 0.75, 0.70, 0.36, 80),
                              (PURPLE2, 0.62, 0.22, 0.25, 60)]:
        cd.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=col + (a,))
    clouds = clouds.filter(ImageFilter.GaussianBlur(s * 0.12))
    img = Image.alpha_composite(base.convert("RGBA"), clouds)
    sd = ImageDraw.Draw(img)
    for _ in range(int(s * 0.12)):
        x, y = rnd.random() * s, rnd.random() * s
        b = rnd.randint(120, 230)
        rr = rnd.choice([1, 1, 1, 2]) * s / 512
        sd.ellipse([x - rr, y - rr, x + rr, y + rr], fill=(b, b, 255, b))
    m = mark(int(s * 0.78))
    img.alpha_composite(m, (int(s * 0.11), int(s * 0.11)))
    if rounded:
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=255)
        img.putalpha(mask)
        # Thin lavender rim.
        rim = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        ImageDraw.Draw(rim).rounded_rectangle([1, 1, s - 2, s - 2], radius=int(s * 0.22),
                                              outline=LAVENDER + (70,), width=max(2, s // 128))
        img = Image.alpha_composite(img, rim)
    return img.resize((size, size), Image.LANCZOS)


def save(img, rel):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", rel, img.size)


def main():
    launcher = "launcher/crates/kestrel-launcher/assets"
    tile = nebula_tile(256)
    save(tile, f"{launcher}/night-icon.png")
    mk = mark(128)
    save(mk, f"{launcher}/night-mark.png")
    # The in-game main menu draws the same mark (resource path kept as is).
    save(mk, "client/src/main/resources/assets/kestrel/textures/menu/mark.png")
    tile.save(os.path.join(ROOT, f"{launcher}/night.ico"),
              sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("wrote", f"{launcher}/night.ico")


if __name__ == "__main__":
    main()
