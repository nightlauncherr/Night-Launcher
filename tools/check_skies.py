#!/usr/bin/env python3
"""Check the bundled sky atlases: size, catalog/thumbnail consistency and seams.

For every border texel of every face, the texel just across the cube edge is
looked up on the neighbouring face (through the same corner table the renderer
uses) and the colours are compared. A seamless atlas differs across an edge no
more than neighbouring texels differ inside a face.

Usage: python tools/check_skies.py [ID ...]     (needs NumPy + Pillow)
Exit status 1 if any check fails.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from skylib import FACES  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "client", "src", "main", "resources", "assets", "kestrel")
SKY = os.path.join(RES, "textures", "sky")


def lookup(atlas, px, dirs):
    """Nearest texel of the atlas for each direction (..., 3)."""
    out = np.zeros(dirs.shape[:-1] + (3,), dtype=np.float32)
    ax = np.argmax(np.abs(dirs), axis=-1)
    for col, row, tl, bl, br, tr in FACES:
        tl, bl, tr = (np.array(c, dtype=np.float64) for c in (tl, bl, tr))
        normal = np.cross(tr - tl, bl - tl)
        normal = -normal / np.linalg.norm(normal) if np.dot(normal, tl) < 0 else normal / np.linalg.norm(normal)
        k = int(np.argmax(np.abs(normal)))
        sel = (ax == k) & (np.sign(dirs[..., k]) == np.sign(normal[k]))
        if not sel.any():
            continue
        d = dirs[sel]
        p = d / np.abs(d[:, k:k + 1])  # onto the cube surface (|coord| = 1)
        eu, ev = tr - tl, bl - tl
        u = ((p - tl) @ eu) / (eu @ eu)
        v = ((p - tl) @ ev) / (ev @ ev)
        x = np.clip((u * px).astype(int), 0, px - 1) + col * px
        y = np.clip((v * px).astype(int), 0, px - 1) + row * px
        out[sel] = atlas[y, x]
    return out


def face_dirs(face, px):
    col, row, tl, bl, br, tr = face
    t = (np.arange(px) + 0.5) / px
    u, v = np.meshgrid(t, t)
    tl, bl, tr = (np.array(c, dtype=np.float64) for c in (tl, bl, tr))
    p = tl + u[..., None] * (tr - tl) + v[..., None] * (bl - tl)
    return p / np.linalg.norm(p, axis=-1, keepdims=True)


def seam_score(atlas, px):
    """Worst edge as (mean colour step across the cube edge, the same step taken just inside
    the face along that edge), in 0..255. Detail such as dense stars makes both large; a
    seam makes only the first one large."""
    worst = (0.0, 0.0, 0.0)
    for face in FACES:
        col, row = face[0], face[1]
        tex = atlas[row * px:(row + 1) * px, col * px:(col + 1) * px]
        d = face_dirs(face, px)
        for edge, inner, near, step in ((d[0, :], tex[0, :], tex[2, :], d[0, :] - d[1, :]),
                                        (d[-1, :], tex[-1, :], tex[-3, :], d[-1, :] - d[-2, :]),
                                        (d[:, 0], tex[:, 0], tex[:, 2], d[:, 0] - d[:, 1]),
                                        (d[:, -1], tex[:, -1], tex[:, -3], d[:, -1] - d[:, -2])):
            across = edge + step * 1.5
            across /= np.linalg.norm(across, axis=-1, keepdims=True)
            other = lookup(atlas, px, across)
            a = float(np.abs(other - inner).mean())
            i = float(np.abs(near - inner).mean())
            ratio = a / max(i, 1.0)
            if ratio > worst[2]:
                worst = (a, i, ratio)
    return worst[0], worst[1]


def main(ids):
    with open(os.path.join(RES, "skies.json")) as f:
        catalog = json.load(f)["skies"]
    failures = 0
    for entry in catalog:
        sid = entry["id"]
        if ids and sid not in ids:
            continue
        path = os.path.join(SKY, sid + ".png")
        thumb = os.path.join(SKY, "thumbs", sid + ".png")
        problems = []
        if not os.path.exists(path):
            problems.append("missing atlas")
        if not os.path.exists(thumb):
            problems.append("missing thumbnail")
        elif Image.open(thumb).size != (384, 128):
            problems.append("thumbnail is %dx%d" % Image.open(thumb).size)
        score = (0.0, 0.0)
        if os.path.exists(path):
            img = Image.open(path).convert("RGB")
            w, h = img.size
            px = w // 3
            if w != px * 3 or h != px * 2 or px < 1024:
                problems.append("atlas is %dx%d" % (w, h))
            atlas = np.asarray(img).astype(np.float32)
            score = seam_score(atlas, px)
            # Allow for the dither and fine detail: a seam shows as a step well above the
            # variation right next to it inside the face.
            if score[0] > max(4.0, score[1] * 3.0):
                problems.append("seam (edge step %.1f vs %.1f inside)" % score)
        size_kb = os.path.getsize(path) // 1024 if os.path.exists(path) else 0
        print("%-16s %6d KB  edge %.2f  inside %.2f  %s" % (sid, size_kb, score[0], score[1], "; ".join(problems) or "ok"))
        failures += bool(problems)
    listed = {e["id"] for e in catalog}
    stray = sorted(f[:-4] for f in os.listdir(SKY) if f.endswith(".png") and f[:-4] not in listed)
    if stray and not ids:
        print("atlases not in skies.json:", ", ".join(stray))
        failures += 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(set(sys.argv[1:])))
