#!/usr/bin/env python3
"""Generate Night Launcher's bundled sky collection (deterministic, original art).

Writes into the client mod's resources:
  textures/sky/<id>.png         3x2 atlases (north, east, south / west, up, down), 1024 px faces
  textures/sky/thumbs/<id>.png  384x128 north/east/south strip for the menus
  skies.json                    the catalog (ids, names, categories)
and regenerates the launcher's embedded thumbnail table (bundled_previews.rs).

Every sky is a function of the 3D view direction built from skylib's
primitives (gradient noise, scattering gradients, lit cloud decks, stars,
galactic band, nebulae, aurora, moons), so the faces join without seams.

Usage:
  python tools/gen_skies.py                 render everything
  python tools/gen_skies.py ID [ID ...]     render only these skies (catalog still written)
  python tools/gen_skies.py --preview ID..  camera views into ./sky-previews (no assets written)
Requires NumPy and Pillow.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from skylib import (F, aurora, angle_to, cloud_layer, col3, dirv, fbm, galaxy_band, gradient, hexc, mix,  # noqa: E402
                    moon, nebula, normalize, perlin, render_atlas, render_view, ridged, scatter_sky, smoothstep,
                    stars, sun_glow, warp)

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "client", "src", "main", "resources", "assets", "kestrel")
TEX = os.path.join(RES, "textures")
FACE = 1024


# ============================================================== shared pieces
def ground(out, d, color, depth=0.07, glow=None, glow_width=0.03):
    """Below the horizon: fade to a dark ground tone (seen only from high up)."""
    y = d[..., 1]
    if glow is not None:
        out = out + col3(np.exp(-np.abs(y) / F(glow_width))) * glow
    return np.where((y < 0)[..., None], mix(out, color, smoothstep(0.0, -depth, y)), out)


def night_base(d, top, horizon, below=None):
    y = d[..., 1]
    sky = gradient(y, [(-1, below if below is not None else top * F(0.5)), (0.0, horizon), (0.35, top), (1, top * F(0.8))])
    return sky


def starfield(d, seed, amount=1.0, cells=22, mask=None):
    st = stars(d, seed, cells=cells) * F(amount)
    if mask is not None:
        st = st * col3(mask)
    return st


# ============================================================== space
def s_milky_way(d):
    sky = night_base(d, hexc("03060f"), hexc("0b1526"))
    n = normalize(np.array([0.35, 0.55, 0.75], dtype=F))
    core = normalize(np.cross(n, np.array([0, 1, 0], dtype=F)) * -1 + np.array([0, 0.25, 0], dtype=F))
    glow, dust, clumps = galaxy_band(d, n, core, 0.19, seed=4, core=0.9)
    near_core = np.clip(np.exp(-angle_to(d, core) / F(0.7)), 0, 1)
    tint = mix(hexc("4f64a8"), hexc("f0c79c"), near_core)
    band = col3(glow) * tint * F(0.14)
    band = band * col3(1 - dust) + col3(clumps * (1 - dust)) * hexc("aebdf2") * F(0.06)
    st = starfield(d, 11, 1.0, mask=1 - dust * 0.7)
    st = st + stars(d, 12, cells=46, density=0.6, brightness=0.3, power=80) * col3(np.clip(glow * 1.3, 0, 1) * (1 - dust))
    out = sky + band + st
    return ground(out, d, hexc("020305"), glow=hexc("163a2c") * F(0.05), glow_width=0.05)


def s_orion_veil(d):
    sky = night_base(d, hexc("04030a"), hexc("0b0816"))
    cen = dirv(200, 30)
    em, lanes = nebula(d, 21, (hexc("d0357e"), hexc("1fa3b8"), hexc("ffd2e8")), scale=1.7, warp_amt=1.0,
                       density=1.0, envelope_dir=cen, envelope_size=1.25, dust=0.75)
    em2, _ = nebula(d, 27, (hexc("6a2bc4"), hexc("ff5f6d"), hexc("ffe7c4")), scale=2.6, warp_amt=0.7,
                    density=0.55, envelope_dir=dirv(250, 55), envelope_size=0.8, dust=0.0)
    neb = (em * F(0.9) + em2 * F(0.6)) * col3(1 - lanes * 0.85)
    st = starfield(d, 31, 1.0, mask=1 - lanes * 0.6)
    out = sky + neb + st
    return ground(out, d, hexc("020206"))


def s_andromeda(d):
    sky = night_base(d, hexc("02040c"), hexc("071224"))
    c = dirv(30, 58)
    axis = normalize(np.array([0.3, 0.2, 0.9], dtype=F))  # disc normal
    # Galaxy in disc coordinates: project onto the plane through c perpendicular to the view.
    right = normalize(np.cross(axis, c))
    up = np.cross(c, right)
    u = np.tensordot(d, right, axes=([-1], [0]))
    v = np.tensordot(d, up, axes=([-1], [0])) / F(0.38)  # tilted: squashed ellipse
    front = np.tensordot(d, c, axes=([-1], [0])) > 0
    r = np.sqrt(u * u + v * v) / F(0.6)
    theta = np.arctan2(v, u)
    arms = np.cos(2 * (theta - np.log(r + 1e-3) * F(2.4))) * F(0.5) + F(0.5)
    tex = fbm(np.stack([u * F(18), v * F(18), np.zeros_like(u) + F(2)], -1), 41, 5) * F(0.5) + F(0.5)
    disc = np.exp(-r * F(3.2)) * (F(0.35) + F(0.9) * arms ** F(2) * tex)
    bulge = np.exp(-(r / F(0.12)) ** 2) * F(2.2)
    dustlane = smoothstep(0.55, 0.85, arms) * smoothstep(0.15, 0.5, r) * np.exp(-r * F(2.5)) * F(0.8)
    lum = (disc * (1 - dustlane) + bulge) * front
    color = mix(hexc("ffe2b8"), hexc("8fb3ff"), smoothstep(0.1, 0.8, r))
    gal = col3(lum) * color * F(0.55)
    halo = col3(np.exp(-r * F(1.2)) * front * F(0.06)) * hexc("6a88d0")
    st = starfield(d, 43, 1.0)
    out = sky + gal + halo + st
    return ground(out, d, hexc("010205"), glow=hexc("1a2a50") * F(0.06))


def s_ringed_giant(d):
    sky = night_base(d, hexc("02030a"), hexc("0a0f22"))
    st = starfield(d, 51, 1.0)
    pc = dirv(115, 16)
    pr = np.radians(17.0)
    ang = angle_to(d, pc)
    inside = smoothstep(pr * 1.005, pr * 0.99, ang)
    # Planet surface coordinates.
    right = normalize(np.cross(np.array([0, 1, 0], dtype=F), pc))
    up = np.cross(pc, right)
    u = np.tensordot(d, right, axes=([-1], [0])) / np.sin(pr)
    v = np.tensordot(d, up, axes=([-1], [0])) / np.sin(pr)
    w = np.sqrt(np.clip(1 - u * u - v * v, 0, 1))
    tilt = F(0.32)
    lat = v * np.cos(tilt) + u * np.sin(tilt)
    bands = fbm(np.stack([lat * F(9), np.zeros_like(lat), u * F(0.6)], -1), 52, 5) * F(0.5) + F(0.5)
    turb = fbm(np.stack([u * F(4), lat * F(14), w], -1), 53, 5)
    bcol = gradient(np.clip(bands + turb * 0.15, 0, 1), [(0, hexc("6d4a3a")), (0.35, hexc("c99a6c")), (0.55, hexc("f0dcb8")),
                                                        (0.75, hexc("b07858")), (1, hexc("e6c9a0"))])
    light = normalize(np.array([-0.7, 0.35, 0.6], dtype=F))
    lam = np.clip(u * light[0] + v * light[1] + w * light[2], 0, 1)
    planet = bcol * col3(lam ** F(0.7) * F(0.9) + F(0.02))
    rim = col3(np.exp(-(1 - w) / F(0.06)) * lam) * hexc("ffd9b0") * F(0.15)
    # Rings: an ellipse around the planet, in front at the bottom, behind at the top.
    # Ring plane shares the bands' tilt: along-ring axis and (squashed) across axis.
    ru = u * np.cos(tilt) - v * np.sin(tilt)
    rv = lat / F(0.22)
    rr = np.sqrt(ru * ru + rv * rv)
    ringprof = (smoothstep(1.25, 1.32, rr) * smoothstep(2.35, 2.25, rr)) * \
               (F(0.55) + F(0.45) * (perlin(np.stack([rr * F(40), np.zeros_like(rr), np.zeros_like(rr)], -1), 54) * 0.5 + 0.5))
    gap = smoothstep(1.78, 1.8, rr) * smoothstep(1.9, 1.88, rr)
    ringprof = ringprof * (1 - gap * 0.85)
    front = rv < 0
    in_view = ang < pr * 2.6
    ring_lum = ringprof * in_view * F(0.75)
    ring_rgb = col3(ring_lum) * hexc("e7d3b4")
    behind = col3(np.where(front, 0, 1) * inside)
    out = sky + st * col3(1 - inside)
    out = mix(out, planet + rim, inside)
    # Ring shadow on the planet, ring drawn over (front) or hidden (behind the planet).
    out = out * col3(1 - ringprof * in_view * 0.0) + ring_rgb * (1 - behind)
    moonlet, mc = moon(d, dirv(160, 34), 1.6, seed=55, phase=(-0.8, 0.3), tint=hexc("c9d2e0"), glow=0.2)
    out = mix(out, moonlet, mc) + moonlet * col3(1 - mc)
    return ground(out, d, hexc("010205"))


def s_emerald_halo(d):
    # A ring nebula: a lumpy, filamentary shell (teal inside, gold rim, faint red
    # outer halo) around a hot white dwarf, seen face-on.
    sky = night_base(d, hexc("02060a"), hexc("06141a"))
    c = dirv(320, 48)
    ang = angle_to(d, c)
    right = normalize(np.cross(np.array([0, 1, 0], dtype=F), c))
    upv = np.cross(c, right)
    u = np.tensordot(d, right, axes=([-1], [0]))
    v = np.tensordot(d, upv, axes=([-1], [0]))
    q = np.stack([u * F(9), v * F(9), np.zeros_like(u) + F(1.7)], -1)
    qw = warp(q, 61, 0.55, 0.6, 4)
    turb = fbm(qw, 62, 7) * F(0.5) + F(0.5)
    fil = ridged(qw * F(2.2), 64, 6)
    R0 = F(0.2)
    rr = ang / R0
    shell = np.exp(-((rr - F(1.0) - (turb - 0.5) * 0.35) / F(0.16)) ** 2) * (F(0.45) + F(0.9) * fil)
    knots = smoothstep(0.62, 0.9, fil) * np.exp(-((rr - F(0.95)) / F(0.25)) ** 2)
    inner = np.exp(-(rr / F(0.85)) ** 4) * (F(0.25) + F(0.5) * turb)
    outer = np.exp(-((rr - F(1.55)) / F(0.35)) ** 2) * (F(0.15) + F(0.6) * fil) * F(0.45)
    neb = (col3(inner) * hexc("3fd6c8") * F(0.55) + col3(shell) * mix(hexc("6ee0a8"), hexc("ffbe6a"), smoothstep(0.8, 1.15, rr)) * F(0.75)
           + col3(knots) * hexc("ffd9a0") * F(0.5) + col3(outer) * hexc("c23a58") * F(0.6))
    star_c = col3(np.exp(-ang / F(0.0035)) * F(3.0)) * hexc("eaf6ff")
    st = starfield(d, 63, 1.0, mask=1 - np.clip(inner * 0.6, 0, 0.6))
    out = sky + neb + star_c + st
    return ground(out, d, hexc("010304"))


def s_stellar_nursery(d):
    sky = night_base(d, hexc("05040a"), hexc("120c18"))
    cen = dirv(70, 26)
    em, lanes = nebula(d, 71, (hexc("e0a04a"), hexc("3b6fd8"), hexc("fff0d0")), scale=1.4, warp_amt=1.3,
                       density=1.1, envelope_dir=cen, envelope_size=1.1, dust=0.95)
    cluster = np.exp(-angle_to(d, dirv(78, 30)) / F(0.12))
    young = stars(d, 72, cells=60, density=0.9, brightness=1.2, power=18) * col3(cluster) * hexc("cfe0ff")
    st = starfield(d, 73, 1.0, mask=1 - lanes * 0.7)
    out = sky + em * col3(1 - lanes * 0.9) * F(0.85) + young + st
    return ground(out, d, hexc("030206"))


# ============================================================== sunsets
def sunset_sky(d, sun, zenith, mid, horizon, glow_col, haze=0.18):
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sky = gradient(up, [(0.0, horizon), (0.08, mid), (0.45, zenith), (1.0, zenith * F(0.85))])
    cosg = np.tensordot(d, sun, axes=([-1], [0]))
    lobe = np.clip((cosg + 1) / 2, 0, 1)
    sky = sky + col3(lobe ** F(4) * np.exp(-up / F(haze))) * glow_col
    sky = sky * col3(F(0.8) + F(0.2) * lobe)
    return sky


def s_golden_hour(d):
    sun = dirv(255, 4.5)
    sky = sunset_sky(d, sun, hexc("3b6fb6"), hexc("f0b878"), hexc("ffcf8a"), hexc("ffb15c") * F(0.9))
    dens, crgb = cloud_layer(d, 81, sun, scale=0.22, cover=0.42, sharp=3.0, billow=0.8, light=hexc("ffd59a") * F(1.3),
                             shadow=hexc("8a6a72"), thickness=1.2, fade_hi=0.15, edge=0.35, erode=1.0)
    out = mix(sky, crgb, dens * 0.95)
    out = out + sun_glow(d, sun, 0.65, glow=1.3, col=hexc("fff1cf"), halo_col=hexc("ffa64d")) * col3(1 - dens * 0.8)
    return ground(out, d, hexc("2a1b16"))


def s_lavender_dusk(d):
    y = d[..., 1]
    sun = dirv(90, -4)
    up = np.clip(y, 0, 1)
    # Belt of Venus opposite the sun, earth shadow below it, afterglow towards the sun.
    sky = gradient(up, [(0, hexc("7b6fa8")), (0.12, hexc("b58bc4")), (0.3, hexc("6c63a8")), (0.7, hexc("2b2d6a")), (1, hexc("161a46"))])
    cosg = np.tensordot(d, sun, axes=([-1], [0]))
    after = np.clip(cosg, 0, 1) ** F(3) * np.exp(-up / F(0.2))
    sky = sky + col3(after) * hexc("ff9c7a") * F(0.9)
    anti = np.clip(-cosg, 0, 1) ** F(2) * np.exp(-((up - F(0.12)) / F(0.07)) ** 2)
    sky = sky + col3(anti) * hexc("f2a6c2") * F(0.45)
    shadow = np.clip(-cosg, 0, 1) * np.exp(-up / F(0.06))
    sky = mix(sky, hexc("3a3f78"), shadow * 0.6)
    dens, crgb = cloud_layer(d, 92, sun, scale=0.18, cover=0.4, sharp=2.0, light=hexc("ffa890") * F(1.1),
                             shadow=hexc("5a4c86"), fade_lo=0.02, fade_hi=0.14, stretch=(1.0, 0.2), edge=0.7, detail=6)
    lit = np.clip(cosg * 0.5 + 0.5, 0, 1) ** F(2)
    crgb = mix(hexc("8c78b8") * F(0.8), crgb, lit)
    sky = mix(sky, crgb, dens * smoothstep(0.6, 0.15, up) * 0.75)
    st = starfield(d, 91, 0.5, mask=smoothstep(0.35, 0.8, up) * (1 - dens))
    venus = col3(np.exp(-angle_to(d, dirv(80, 14)) / F(0.0025)) * F(3)) * hexc("fff6e8")
    out = sky + st + venus
    return ground(out, d, hexc("1a1630"))


def s_tropical_blaze(d):
    sun = dirv(280, 2.0)
    sky = sunset_sky(d, sun, hexc("3a2a6e"), hexc("ff6a3d"), hexc("ffb347"), hexc("ff5a1f") * F(1.1), haze=0.22)
    # Mackerel sky: rippled altocumulus lit pink from below.
    x, z = d[..., 0] / np.maximum(d[..., 1], F(0.012)), d[..., 2] / np.maximum(d[..., 1], F(0.012))
    k = 1 / np.maximum(d[..., 1], F(0.012))
    far = np.clip(np.log2(k) / F(3.5), 0, 1)  # ripples blur out with distance instead of aliasing
    rip = np.sin(x * F(5.5) + fbm(np.stack([x * F(0.3), z * F(0.3), np.zeros_like(x)], -1), 101, 4) * F(4)) * F(0.5) + F(0.5)
    rip = rip * (1 - far) + F(0.6) * far
    dens, crgb = cloud_layer(d, 102, sun, scale=0.5, cover=0.55, sharp=2.0, light=hexc("ff8fa3") * F(1.4),
                             shadow=hexc("5a2c5a"), fade_lo=0.01, fade_hi=0.12, edge=0.4, stretch=(1.0, 0.6), erode=0.8)
    dens = dens * smoothstep(0.25, 0.75, rip) * (1 - far * 0.35)
    out = mix(sky, crgb, dens)
    out = out + sun_glow(d, sun, 0.8, glow=1.5, col=hexc("ffe0a0"), halo_col=hexc("ff5a1f")) * col3(1 - dens * 0.7)
    return ground(out, d, hexc("2a0f16"))


def mountains(d, seed, height_deg=4.0, rough=1.0):
    """A distant ridge line just above the horizon, as a function of azimuth only
    (sampled on a circle, so it wraps without a seam). Returns (mask, slope_light)."""
    a = np.arctan2(d[..., 0], -d[..., 2])
    ring = np.stack([np.cos(a) * F(2.2), np.sin(a) * F(2.2), np.zeros_like(a) + F(seed * 0.13)], -1)
    prof = ridged(ring, seed, 6) * F(0.75) + (fbm(ring * F(0.5), seed + 1, 3) * F(0.5) + F(0.5)) * F(0.45)
    top = np.sin(np.radians(height_deg)) * prof * F(rough)
    y = d[..., 1]
    mask = smoothstep(top + F(0.0015), top - F(0.0015), y)
    # Which side of each peak faces the sun: the ridge's slope along azimuth.
    eps = F(0.01)
    ring2 = np.stack([np.cos(a + eps) * F(2.2), np.sin(a + eps) * F(2.2), np.zeros_like(a) + F(seed * 0.13)], -1)
    prof2 = ridged(ring2, seed, 6) * F(0.75) + (fbm(ring2 * F(0.5), seed + 1, 3) * F(0.5) + F(0.5)) * F(0.45)
    slope = (prof2 - prof) / eps
    snow = smoothstep(0.55, 0.9, prof) * smoothstep(top * F(0.55), top * F(0.8), y)
    return mask, slope, snow


def s_alpine_dawn(d):
    # First light over a mountain range: deep blue zenith, a rose belt, a sliver
    # of sun on the ridge, rose-lit altocumulus and alpenglow on the snow.
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sun = dirv(100, 1.2)
    cosg = np.tensordot(d, sun, axes=([-1], [0]))
    sky = gradient(up, [(0, hexc("ffd2b0")), (0.04, hexc("f4b3a8")), (0.13, hexc("b99ac0")), (0.32, hexc("5a78b8")),
                        (0.7, hexc("2a4f98")), (1, hexc("1d3b80"))])
    sky = sky + col3(np.clip(cosg, 0, 1) ** F(6) * np.exp(-up / F(0.1))) * hexc("ffb070") * F(0.9)
    sky = mix(sky, hexc("6e6ea8"), np.clip(-cosg, 0, 1) * np.exp(-up / F(0.05)) * 0.35)
    dens, crgb = cloud_layer(d, 111, sun, scale=0.3, cover=0.44, sharp=2.4, billow=0.7,
                             light=hexc("ffc2a8") * F(1.25), shadow=hexc("6a5d8a"), ambient=hexc("9a86b0"),
                             fade_lo=0.02, fade_hi=0.14, thickness=1.1, edge=0.3, stretch=(1.0, 0.55), erode=1.0)
    warm = np.clip(cosg * 0.5 + 0.5, 0, 1)
    crgb = crgb * mix(hexc("c8b8e0"), hexc("ffffff"), warm)
    out = mix(sky, crgb, dens * 0.95)
    out = out + sun_glow(d, sun, 0.55, glow=1.1, col=hexc("fff0d8"), halo_col=hexc("ffb070")) * col3(1 - dens * 0.7)
    mask, slope, snow = mountains(d, 113, 6.5)
    rock = mix(hexc("1c1b30"), hexc("3a3150"), smoothstep(0.0, 0.07, y))
    glowsnow = mix(hexc("8a86b8"), hexc("ffb8a0"), np.clip(cosg * 0.5 + 0.55, 0, 1))
    ridge = mix(rock, glowsnow, snow * 0.8)
    # Aerial perspective: the far range takes on the sky colour near the horizon.
    ridge = mix(ridge, sky, F(0.22) * smoothstep(-0.01, 0.03, y))
    out = mix(out, ridge, mask)
    return ground(out, d, hexc("221f34"))


def s_desert_haze(d):
    # A hot, dusty evening: a swollen red-orange sun low in banded haze, dust
    # layers along the horizon and a pale, faded blue overhead.
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sun = dirv(240, 2.6)
    cosg = np.tensordot(d, sun, axes=([-1], [0]))
    sky = gradient(up, [(0, hexc("f0a060")), (0.05, hexc("e08a60")), (0.16, hexc("c4877e")), (0.38, hexc("7a8aa8")),
                        (0.75, hexc("3f6696")), (1, hexc("31588a"))])
    sky = sky + col3(np.clip(cosg, 0, 1) ** F(3) * np.exp(-up / F(0.16))) * hexc("ff8a3a") * F(0.75)
    a = np.arctan2(d[..., 0], -d[..., 2])
    ring = np.stack([np.cos(a) * F(1.5), np.sin(a) * F(1.5), y * F(60)], -1)
    bands = fbm(ring, 121, 5) * F(0.5) + F(0.5)
    haze = np.exp(-up / F(0.07)) * (F(0.4) + F(0.6) * smoothstep(0.35, 0.75, bands))
    sky = mix(sky, hexc("e8b48a"), haze * 0.45)
    # Thin altostratus streaks, lit from below.
    dens, crgb = cloud_layer(d, 122, sun, scale=0.2, cover=0.4, sharp=2.2, light=hexc("ffa070") * F(1.3),
                             shadow=hexc("7a5a6a"), fade_lo=0.02, fade_hi=0.16, stretch=(1.0, 0.22), edge=0.6, detail=6)
    out = mix(sky, crgb, dens * 0.8)
    # Low sun: a swollen red-orange orb, dimmed by dust and crossed by haze bands.
    ang = angle_to(d, sun)
    r_ = F(np.radians(1.6))
    orb = smoothstep(r_ * 1.05, r_ * 0.9, ang)
    orb_col = mix(hexc("ff5a2a"), hexc("ffd08a"), np.clip((y - float(sun[1])) / float(r_) * 0.5 + 0.6, 0, 1))
    across = smoothstep(0.4, 0.75, bands)
    out = mix(out, orb_col * F(2.2), orb * (1 - across * 0.45) * (1 - dens * 0.7))
    out = out + col3(np.exp(-ang / F(0.06)) * (1 - orb)) * hexc("ff7a3a") * F(0.6)
    out = out + col3(np.exp(-ang / F(0.35))) * hexc("ff8a3a") * F(0.25)
    return ground(out, d, hexc("5a3a2a"), depth=0.1)


# ============================================================== clouds & aurora
def s_aurora_curtains(d):
    sky = night_base(d, hexc("030812"), hexc("0b1a2a"))
    st = starfield(d, 131, 0.9)
    au = aurora(d, 132, (hexc("2bff9e"), hexc("33d6b0"), hexc("c04aff")), height=0.8, layers=56, depth=1.1,
                scale=0.5, strength=1.1, along=(0.9, 0.44))
    au2 = aurora(d, 133, (hexc("3dff8a"), hexc("8affc8"), hexc("ff5fa8")), height=1.2, layers=40, depth=0.8,
                 scale=0.8, strength=0.6, along=(0.5, -0.86))
    out = sky + st * col3(1 - np.clip(np.max(au, -1) * 2, 0, 0.7)) + au + au2
    return ground(out, d, hexc("02060a"), glow=hexc("0f3b2c") * F(0.08))


def s_fair_weather(d):
    sun = dirv(150, 55)
    sky = scatter_sky(d, sun, hexc("1f5fcf"), hexc("cfe0f2"), sun_side=hexc("6f8fb8") * F(0.25), haze=0.18)
    dens, crgb = cloud_layer(d, 141, sun, scale=0.26, cover=0.41, sharp=3.6, billow=1.0, light=hexc("ffffff") * F(1.25),
                             shadow=hexc("6c7c98"), fade_hi=0.16, thickness=1.6, edge=0.3, erode=0.8)
    out = mix(sky, crgb, dens)
    out = out + sun_glow(d, sun, 0.55, glow=0.45, col=hexc("fff8ea")) * col3(1 - dens * 0.95)
    return ground(out, d, hexc("3a4552"))


def s_storm_front(d):
    # A thunderstorm rolling in: a low, heavy cloud deck overhead, a dark shelf
    # cloud over a bright clearing where the evening sun breaks through, and rain
    # curtains hanging from the base on the far side.
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sun = dirv(250, 3.0)
    cosg = np.tensordot(d, sun, axes=([-1], [0]))
    sunward = np.clip(cosg, 0, 1)
    sky = gradient(up, [(0, hexc("50585c")), (0.04, hexc("4a5156")), (0.12, hexc("3a4248")), (1, hexc("262b31"))])
    gap_band = np.exp(-up / F(0.035)) * sunward ** F(3)
    sky = sky + col3(gap_band) * hexc("ffc070") * F(1.4)
    dens, crgb = cloud_layer(d, 151, sun, scale=0.22, cover=0.8, sharp=1.5, billow=1.0,
                             light=hexc("8a9296"), shadow=hexc("161a1e"), ambient=hexc("343b40"),
                             fade_lo=0.0, fade_hi=0.07, thickness=1.8, edge=0.25, detail=6)
    # The deck's underside glows warm on the sun side.
    crgb = crgb + col3(sunward ** F(4) * np.exp(-up / F(0.25))) * hexc("ff9a4a") * F(0.35)
    # Clearing under the shelf towards the sun.
    clear = sunward ** F(2) * smoothstep(0.1, 0.04, y)
    out = mix(sky, crgb, dens * (1 - clear * 0.95))
    a = np.arctan2(d[..., 0], -d[..., 2])
    ring = np.stack([np.cos(a) * F(3), np.sin(a) * F(3), np.zeros_like(a)], -1)
    edge_line = F(0.075) + (fbm(ring, 153, 4)) * F(0.02)
    shelf = smoothstep(edge_line - F(0.006), edge_line + F(0.004), y) * smoothstep(0.2, 0.13, y) * sunward ** F(0.7)
    shelf_col = mix(hexc("1a1e22"), hexc("4a4a48"), smoothstep(edge_line + F(0.02), edge_line, y))
    out = mix(out, shelf_col, shelf * 0.85)
    # Rain curtains: soft vertical streaks below the base, away from the sun.
    away = smoothstep(0.1, -0.4, cosg)
    curtains = smoothstep(0.45, 0.75, fbm(ring * F(1.3) + F(5), 152, 4) * 0.5 + 0.5)
    fine = perlin(np.stack([np.cos(a) * F(60), np.sin(a) * F(60), y * F(3)], -1), 154) * F(0.5) + F(0.5)
    rain = curtains * away * (F(0.75) + F(0.25) * fine) * smoothstep(0.11, 0.03, y) * smoothstep(-0.005, 0.01, y)
    out = mix(out, hexc("5a6268"), rain * 0.55)
    # A lightning glow lighting the cloud from inside.
    flash = np.exp(-angle_to(d, dirv(60, 16)) / F(0.18)) * dens
    out = out + col3(flash) * hexc("b8c8ff") * F(0.5)
    out = out + sun_glow(d, sun, 0.6, glow=0.8, col=hexc("ffd28a"), halo_col=hexc("ff9a4a")) * col3(1 - dens * (1 - clear))
    return ground(out, d, hexc("1d2226"))


def s_noctilucent(d):
    y = d[..., 1]
    sun = dirv(0, -10)
    up = np.clip(y, 0, 1)
    sky = gradient(up, [(0, hexc("2a3d6a")), (0.1, hexc("1a2b55")), (0.5, hexc("0c1634")), (1, hexc("070c20"))])
    cosg = np.tensordot(d, sun, axes=([-1], [0]))
    sky = sky + col3(np.clip(cosg, 0, 1) ** F(3) * np.exp(-up / F(0.12))) * hexc("ffb38a") * F(0.35)
    x, z = d[..., 0] / np.maximum(y, F(0.02)), d[..., 2] / np.maximum(y, F(0.02))
    waves = np.sin(x * F(3.2) + z * F(1.1) + fbm(np.stack([x * F(0.4), z * F(0.4), np.zeros_like(x)], -1), 161, 4) * F(5))
    wisp = fbm(np.stack([x * F(0.9), z * F(0.25), np.zeros_like(x) + F(1)], -1), 162, 6) * F(0.5) + F(0.5)
    nlc = smoothstep(0.5, 0.8, wisp) * (F(0.6) + F(0.4) * waves) * smoothstep(0.03, 0.12, y) * smoothstep(0.55, 0.2, y)
    nlc = nlc * np.clip(cosg + 0.6, 0, 1)
    out = sky + col3(np.clip(nlc, 0, 1)) * hexc("7fd3ff") * F(0.8)
    out = out + starfield(d, 163, 0.6, mask=smoothstep(0.25, 0.7, up))
    return ground(out, d, hexc("0a1020"))


def s_cirrus_silk(d):
    sun = dirv(210, 40)
    sky = scatter_sky(d, sun, hexc("1747b5"), hexc("b7d2f0"), sun_side=hexc("8fb0d8") * F(0.25), haze=0.2)
    y = d[..., 1]
    x, z = d[..., 0] / np.maximum(y, F(0.015)), d[..., 2] / np.maximum(y, F(0.015))
    rot = F(0.6)
    xr, zr = x * np.cos(rot) - z * np.sin(rot), x * np.sin(rot) + z * np.cos(rot)
    p = warp(np.stack([xr * F(0.12), zr * F(1.1), np.zeros_like(x)], -1), 171, 0.4)
    streak = fbm(p, 172, 7) * F(0.5) + F(0.5)
    hook = ridged(p * F(1.6), 173, 5)
    cirrus = (smoothstep(0.52, 0.85, streak) * F(0.6) + smoothstep(0.6, 0.95, hook) * F(0.5)) * smoothstep(0.02, 0.2, y)
    out = mix(sky, hexc("ffffff") * F(1.05), np.clip(cirrus, 0, 0.85))
    out = out + sun_glow(d, sun, 0.55, glow=0.5, col=hexc("fffaf0"))
    return ground(out, d, hexc("2c3a4e"))


# ============================================================== dark
def s_blood_moon(d):
    # A total lunar eclipse: a large copper-red moon above a bruised, red-lit
    # horizon, with torn cloud lit from below by the last glow.
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sky = gradient(up, [(0, hexc("5a1410")), (0.05, hexc("3a0c0e")), (0.2, hexc("1a0609")), (0.6, hexc("0b0306")),
                        (1, hexc("060205"))])
    mc_dir = dirv(140, 26)
    m, cov = moon(d, mc_dir, 7.0, seed=181, tint=hexc("d0461e"), glow=1.2)
    halo = np.exp(-angle_to(d, mc_dir) / F(0.3))
    glow = col3(halo) * hexc("8a1a12") * F(0.45) + col3(np.exp(-angle_to(d, mc_dir) / F(0.08))) * hexc("ff5a2a") * F(0.25)
    dens, crgb = cloud_layer(d, 182, mc_dir, scale=0.26, cover=0.42, sharp=2.0, billow=0.6, light=hexc("b8402a"),
                             shadow=hexc("1a0607"), ambient=hexc("3a0e0e"), fade_lo=0.01, fade_hi=0.12, edge=0.5,
                             stretch=(1.0, 0.45), erode=0.8)
    crgb = crgb * col3(F(0.35) + F(0.9) * np.exp(-up / F(0.18)) + F(0.6) * halo)
    st = starfield(d, 183, 0.6, mask=(1 - cov) * (1 - np.clip(halo * 1.5, 0, 0.9)))
    out = sky + glow + st
    out = mix(out, m, cov) + m * col3(1 - cov) * F(0.3)
    out = mix(out, crgb, dens * 0.85)
    return ground(out, d, hexc("070203"))


def s_moonless(d):
    y = d[..., 1]
    sky = night_base(d, hexc("010205"), hexc("03070c"))
    air = np.exp(-np.abs(np.clip(y, 0, 1) - F(0.08)) / F(0.07)) * (fbm(d * F(5), 191, 5) * 0.5 + 0.7)
    out = sky + col3(air) * hexc("123a24") * F(0.07) + col3(air) * hexc("2a1530") * F(0.02)
    out = out + stars(d, 192, cells=26, density=0.55, brightness=0.75)
    return ground(out, d, hexc("000102"))


def s_corona(d):
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sky = gradient(up, [(0, hexc("e08a4a")), (0.05, hexc("6b4a6a")), (0.25, hexc("152040")), (1, hexc("070b1c"))])
    c = dirv(180, 40)
    ang = angle_to(d, c)
    r = F(np.radians(1.4))
    disc = smoothstep(r * 1.02, r * 0.98, ang)
    # Corona: radial streamers.
    right = normalize(np.cross(np.array([0, 1, 0], dtype=F), c))
    upv = np.cross(c, right)
    th = np.arctan2(np.tensordot(d, upv, axes=([-1], [0])), np.tensordot(d, right, axes=([-1], [0])))
    streak = perlin(np.stack([np.cos(th) * F(4), np.sin(th) * F(4), np.zeros_like(th)], -1), 201) * F(0.5) + F(0.5)
    streak2 = perlin(np.stack([np.cos(th) * F(16), np.sin(th) * F(16), np.zeros_like(th) + 3], -1), 202) * F(0.5) + F(0.5)
    fall = np.exp(-np.maximum(ang - r, 0) / (F(0.05) + F(0.12) * streak ** F(3)))
    corona = fall * (F(0.6) + F(0.6) * streak2) * (1 - disc)
    out = sky + col3(corona) * hexc("e8eeff") * F(1.6)
    prom = np.exp(-np.abs(ang - r * 1.03) / F(0.0015)) * smoothstep(0.7, 0.9, streak2) * F(1.2)
    out = out + col3(prom) * hexc("ff4a6a")
    out = mix(out, hexc("000000"), disc)
    out = out + starfield(d, 203, 0.35, mask=smoothstep(0.2, 0.6, up))
    return ground(out, d, hexc("1a1218"))


def s_ashfall(d):
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sky = gradient(up, [(0, hexc("ff6a1f")), (0.06, hexc("8a3018")), (0.3, hexc("2e1612")), (1, hexc("120a0a"))])
    p = warp(np.stack([d[..., 0] * F(2), y * F(4), d[..., 2] * F(2)], -1), 211, 0.6)
    plume = fbm(p, 212, 6) * F(0.5) + F(0.5)
    out = mix(sky, hexc("1a0f0e"), smoothstep(0.45, 0.8, plume) * smoothstep(0.03, 0.2, y) * 0.85)
    glow = np.exp(-up / F(0.08)) * (plume * F(0.8) + F(0.4))
    out = out + col3(glow) * hexc("ff4a12") * F(0.5)
    emb = stars(d, 213, cells=34, density=0.25, brightness=0.6, power=12, color_var=0.0) * col3(smoothstep(0.0, 0.3, up))
    out = out + emb * hexc("ff8a3d")
    return ground(out, d, hexc("140807"))


# ============================================================== fantasy
def s_twin_moons(d):
    y = d[..., 1]
    sky = gradient(np.clip(y, 0, 1), [(0, hexc("5a3d8a")), (0.15, hexc("2c1f5e")), (0.6, hexc("140f36")), (1, hexc("0a0820"))])
    em, lanes = nebula(d, 221, (hexc("6a3dd0"), hexc("2ab3c4"), hexc("e7d9ff")), scale=1.2, warp_amt=1.1,
                       density=0.5, envelope_dir=dirv(300, 50), envelope_size=1.3, dust=0.4)
    m1, c1 = moon(d, dirv(200, 28), 6.0, seed=222, phase=(-0.6, 0.5), tint=hexc("e8e2ff"), glow=0.7)
    m2, c2 = moon(d, dirv(232, 42), 2.4, seed=223, phase=(-0.6, 0.5), tint=hexc("8ff2e0"), glow=0.6)
    st = starfield(d, 224, 0.8, mask=(1 - c1) * (1 - c2))
    out = sky + em * F(0.6) * col3(1 - lanes * 0.7) + st
    out = mix(out, m1, c1) + m1 * col3(1 - c1)
    out = mix(out, m2, c2) + m2 * col3(1 - c2)
    return ground(out, d, hexc("0f0a22"))


def s_dreamscape(d):
    # A soft, dreamlike day: candy-coloured light, pastel cumulus floating in a
    # pink and lilac haze, and a few stars showing through the blue overhead.
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sun = dirv(60, 22)
    sky = gradient(up, [(0, hexc("ffd0e0")), (0.1, hexc("f4c4ec")), (0.3, hexc("c9b8f4")), (0.65, hexc("8fb4f2")),
                        (1, hexc("6c8ff0"))])
    cosg = np.tensordot(d, sun, axes=([-1], [0]))
    sky = sky + col3(np.clip(cosg, 0, 1) ** F(8)) * hexc("fff0c8") * F(0.5)
    swirl = fbm(warp(d * F(2.0), 231, 1.0, 0.7, 4), 232, 5) * F(0.5) + F(0.5)
    sky = sky * mix(hexc("ffe8f6"), hexc("e8f4ff"), swirl)
    dens, crgb = cloud_layer(d, 233, sun, scale=0.24, cover=0.41, sharp=3.0, billow=1.0, light=hexc("fff4fb") * F(1.15),
                             shadow=hexc("b39adf"), ambient=hexc("e0c4f0"), fade_hi=0.14, thickness=1.2, edge=0.35,
                             erode=0.8)
    tint = mix(hexc("ffc8e4"), hexc("c8e8ff"), smoothstep(-0.5, 0.8, cosg))
    out = mix(sky, crgb * tint, dens)
    out = out + stars(d, 234, cells=18, density=0.35, brightness=0.5, color_var=0.3) * col3(smoothstep(0.55, 0.9, up) * (1 - dens))
    out = out + sun_glow(d, sun, 0.8, glow=0.6, col=hexc("fff6e0"), halo_col=hexc("ffc0e0")) * col3(1 - dens * 0.9)
    return ground(out, d, hexc("8a76b0") * F(0.6), depth=0.15)


def s_prism_veil(d):
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sky = gradient(up, [(0, hexc("1a1d3a")), (0.4, hexc("0d1030")), (1, hexc("080920"))])
    p = warp(d * F(1.5), 241, 0.8)
    ribbon = ridged(p, 242, 5)
    hue = (np.arctan2(d[..., 2], d[..., 0]) / (2 * np.pi) + fbm(p, 243, 3) * F(0.3)) % 1.0
    k = np.floor(hue * 6).astype(int) % 6
    f = hue * 6 - np.floor(hue * 6)
    q, t = 1 - f, f
    one = np.ones_like(f)
    zero = np.zeros_like(f)
    r = np.choose(k, [one, q, zero, zero, t, one])
    g = np.choose(k, [t, one, one, q, zero, zero])
    b = np.choose(k, [zero, zero, t, one, one, q])
    rainbow = np.stack([r, g, b], -1).astype(F) ** F(2.2)
    veil = smoothstep(0.55, 0.95, ribbon) * smoothstep(0.0, 0.3, up)
    out = sky + col3(veil) * rainbow * F(0.8) + col3(veil ** F(3)) * F(0.4)
    out = out + starfield(d, 244, 0.6, mask=1 - veil)
    return ground(out, d, hexc("06071a"))


def s_sky_rings(d):
    sun = dirv(120, 45)
    sky = scatter_sky(d, sun, hexc("2a5fc0"), hexc("d9e6f5"), sun_side=hexc("7f9fc8") * F(0.25), haze=0.2)
    # A planetary ring arcing across the daytime sky: a great-circle band seen edge-on.
    n = normalize(np.array([0.25, 0.94, 0.25], dtype=F))
    b = np.tensordot(d, n, axes=([-1], [0]))
    rr = F(0.2) + b
    rings = smoothstep(0.0, 0.01, rr) * smoothstep(0.14, 0.13, rr)
    texture = perlin(np.stack([rr * F(220), np.zeros_like(rr), np.zeros_like(rr)], -1), 251) * F(0.5) + F(0.5)
    gaps = smoothstep(0.065, 0.07, rr) * smoothstep(0.085, 0.08, rr)
    ring = rings * (F(0.4) + F(0.6) * texture) * (1 - gaps * 0.9) * smoothstep(-0.05, 0.05, d[..., 1])
    out = mix(sky, hexc("f6eedc") * F(1.15), ring * 0.85)
    dens, crgb = cloud_layer(d, 252, sun, scale=0.3, cover=0.39, sharp=3.0, billow=1.0, light=hexc("ffffff") * F(1.2),
                             shadow=hexc("7889a6"), fade_hi=0.18, thickness=1.4, edge=0.4)
    out = mix(out, crgb, dens)
    moonlet, mc = moon(d, dirv(250, 30), 2.2, seed=253, phase=(0.7, 0.4), tint=hexc("e8ecf2"), glow=0.0)
    out = mix(out, moonlet * F(0.6) + sky * F(0.4), mc * 0.85)
    out = out + sun_glow(d, sun, 0.55, glow=0.5, col=hexc("fff8ea")) * col3(1 - dens * 0.9)
    return ground(out, d, hexc("3a4656"))


# ============================================================== abstract
def s_synthwave(d):
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    sky = gradient(up, [(0, hexc("ff3d8a")), (0.07, hexc("b82aa8")), (0.25, hexc("4a1478")), (0.6, hexc("1a0838")), (1, hexc("0a0420"))])
    c = dirv(180, 9)
    ang = angle_to(d, c)
    r = F(np.radians(11))
    sun_in = smoothstep(r * 1.01, r * 0.99, ang)
    sv = np.tensordot(d, np.cross(np.cross(c, np.array([0, 1, 0], dtype=F)), c), axes=([-1], [0])) / np.sin(r)
    sun_col = gradient(sv * 0.5 + 0.5, [(0, hexc("ff2d6f")), (0.5, hexc("ff8a3d")), (1, hexc("ffe25a"))])
    stripes = np.where(sv < 0.15, (np.sin((sv + 1) * F(26)) > (sv * F(0.9))).astype(F), 1)
    out = mix(sky, sun_col * F(1.3), sun_in * stripes)
    out = out + col3(np.exp(-np.maximum(ang - r, 0) / F(0.12)) * (1 - sun_in)) * hexc("ff3d8a") * F(0.4)
    out = out + starfield(d, 261, 0.6, mask=smoothstep(0.2, 0.6, up))
    # Neon grid below the horizon.
    below = np.clip(-y, 0.004, 1)
    gx, gz = d[..., 0] / below, d[..., 2] / below
    fx = np.abs(((gx * F(0.5)) + 0.5) % 1 - 0.5)
    fz = np.abs(((gz * F(0.5)) + 0.5) % 1 - 0.5)
    w = F(0.02) + F(0.02) / (below * 20 + 0.5)
    # Lines thinner than a texel would alias near the horizon: fade them into an even glow there.
    near = smoothstep(0.01, 0.06, below)
    grid = np.maximum(smoothstep(w, 0, fx), smoothstep(w, 0, fz)) * near + F(0.25) * (1 - near)
    floor_ = col3(grid) * hexc("ff3dd8") * F(1.2) + hexc("12051f")
    out = np.where((y < 0)[..., None], mix(floor_, out, smoothstep(-0.01, 0.0, y)), out)
    return out


def s_neon_flux(d):
    base = gradient(np.clip(d[..., 1], -1, 1) * 0.5 + 0.5, [(0, hexc("020208")), (1, hexc("05030f"))])
    out = base
    for i, (ca, cb) in enumerate([(hexc("00f0ff"), hexc("0060ff")), (hexc("ff2ad4"), hexc("8a00ff")), (hexc("39ff9e"), hexc("00b3a6"))]):
        p = warp(d * F(1.1 + i * 0.3), 271 + i * 7, 0.9)
        r = ridged(p, 272 + i * 7, 4)
        line = smoothstep(0.86, 0.98, r)
        out = out + col3(line) * mix(cb, ca, line) * F(1.1) + col3(smoothstep(0.6, 0.95, r) * 0.15) * cb
    out = out + stars(d, 290, cells=20, density=0.3, brightness=0.4, color_var=0.0)
    return out


_LOW_POLY_POINTS = None


def _low_poly_points(n=1400, seed=271):
    """Jittered Fibonacci-sphere points: the facet centres."""
    global _LOW_POLY_POINTS
    if _LOW_POLY_POINTS is None:
        rng = np.random.default_rng(seed)
        i = np.arange(n, dtype=np.float64) + 0.5
        y = 1 - 2 * i / n
        r = np.sqrt(1 - y * y)
        phi = i * np.pi * (3 - np.sqrt(5))
        p = np.stack([r * np.cos(phi), y, r * np.sin(phi)], -1) + rng.normal(0, 0.03, (n, 3))
        _LOW_POLY_POINTS = (p / np.linalg.norm(p, axis=-1, keepdims=True)).astype(F)
    return _LOW_POLY_POINTS


def s_low_poly(d):
    # Faceted sunset: the sphere is split into flat-coloured polygons (the Voronoi cells of
    # ~1400 points), each coloured by the sky gradient at its centre, with a little jitter.
    pts = _low_poly_points()
    flat = d.reshape(-1, 3)
    idx = np.empty(flat.shape[0], dtype=np.int64)
    for k in range(0, flat.shape[0], 1 << 18):
        idx[k:k + (1 << 18)] = np.argmax(flat[k:k + (1 << 18)] @ pts.T, axis=1)
    c = pts[idx].reshape(d.shape)
    jitter = ((np.sin(idx.astype(np.float64) * 12.9898) * 43758.5453) % 1.0).astype(F).reshape(d.shape[:-1])
    yq = c[..., 1]
    base = gradient(np.clip(yq, -1, 1), [(-1, hexc("10132a")), (-0.15, hexc("1a2046")), (0.0, hexc("f4a86a")),
                                          (0.1, hexc("e88078")), (0.28, hexc("a070b0")), (0.55, hexc("5260b0")),
                                          (1, hexc("2c3c8c"))])
    sun = dirv(270, 4)
    warm = np.clip(np.tensordot(c, sun, axes=([-1], [0])), 0, 1) ** F(4) * np.exp(-np.clip(yq, 0, 1) / F(0.3))
    base = base + col3(warm) * hexc("ffb070") * F(0.6)
    return base * col3(F(0.86) + F(0.26) * jitter)


# ============================================================== simple
def s_clear_blue(d):
    sun = dirv(160, 60)
    sky = scatter_sky(d, sun, hexc("1e5ccc"), hexc("c6dcf2"), sun_side=hexc("7fa0cc") * F(0.25), haze=0.2)
    out = sky + sun_glow(d, sun, 0.55, glow=0.45, col=hexc("fff9ee"))
    return ground(out, d, hexc("3a4a5c"))


def s_soft_clouds(d):
    sun = dirv(200, 45)
    sky = scatter_sky(d, sun, hexc("4a7ec9"), hexc("dbe7f3"), sun_side=hexc("9fb6d6") * F(0.2), haze=0.25)
    dens, crgb = cloud_layer(d, 301, sun, scale=0.4, cover=0.42, sharp=1.4, light=hexc("ffffff") * F(1.1),
                             shadow=hexc("b6c0d0"), fade_hi=0.2, edge=0.9)
    out = mix(sky, crgb, dens * 0.9)
    out = out + sun_glow(d, sun, 0.55, glow=0.35, col=hexc("fffaf0")) * col3(1 - dens * 0.8)
    return ground(out, d, hexc("4a5562"))


def s_calm_night(d):
    sky = night_base(d, hexc("050b1e"), hexc("16264a"))
    m, cov = moon(d, dirv(220, 35), 1.8, seed=311, tint=hexc("eef0ff"), glow=0.6)
    glow = col3(np.exp(-angle_to(d, dirv(220, 35)) / F(0.4))) * hexc("2a3e70") * F(0.25)
    out = sky + glow + starfield(d, 312, 0.55, mask=1 - cov)
    out = mix(out, m, cov) + m * col3(1 - cov)
    return ground(out, d, hexc("040814"))


def s_starlight(d):
    sky = night_base(d, hexc("010103"), hexc("05070d"))
    out = sky + stars(d, 321, cells=16, density=0.45, brightness=0.9, color_var=0.5)
    return ground(out, d, hexc("000001"))


def s_pastel(d):
    y = d[..., 1]
    t = np.clip(y, -1, 1) * 0.5 + 0.5
    az = np.arctan2(d[..., 0], -d[..., 2]) / (2 * np.pi) + 0.5
    warm = np.cos((az - 0.25) * F(2 * np.pi)) * F(0.5) + F(0.5)
    a = gradient(t, [(0, hexc("c8b6e8")), (0.5, hexc("ffd2c2")), (0.7, hexc("cfe0ff")), (1, hexc("9fc3f2"))])
    b = gradient(t, [(0, hexc("b9c8ea")), (0.5, hexc("ffe3c8")), (0.7, hexc("e0d6ff")), (1, hexc("b3c9f5"))])
    return mix(a, b, warm) * F(0.8)


def s_slate(d):
    y = d[..., 1]
    t = np.clip(y, -1, 1) * 0.5 + 0.5
    out = gradient(t, [(0, hexc("15181c")), (0.48, hexc("3a4048")), (0.52, hexc("4a525c")), (1, hexc("23272d"))])
    return out * F(0.9)


def s_soft_sunset(d):
    sun = dirv(270, 6)
    sky = sunset_sky(d, sun, hexc("5b6fa8"), hexc("e8b4a0"), hexc("f5d0b0"), hexc("ffb48a") * F(0.45), haze=0.25)
    out = sky + sun_glow(d, sun, 0.6, glow=0.6, col=hexc("fff0dc"), halo_col=hexc("ffb48a"))
    return ground(out, d, hexc("2a2430"))


# ============================================================== Night signature
def s_night_nebula(d):
    sky = night_base(d, hexc("06030f"), hexc("140a28"))
    em, lanes = nebula(d, 331, (hexc("7a3df0"), hexc("c04aff"), hexc("f2d9ff")), scale=1.3, warp_amt=1.2,
                       density=1.0, envelope_dir=dirv(150, 40), envelope_size=1.4, dust=0.7)
    em2, _ = nebula(d, 337, (hexc("3a2aa8"), hexc("ff6ad5"), hexc("ffffff")), scale=2.4, warp_amt=0.6,
                    density=0.4, envelope_dir=dirv(80, 25), envelope_size=0.9, dust=0.0)
    st = starfield(d, 332, 1.0, mask=1 - lanes * 0.6)
    crescent, cov = moon(d, dirv(215, 38), 2.6, seed=333, phase=(-0.9, 0.2), tint=hexc("e9ddff"), glow=0.5)
    out = sky + (em * F(0.85) + em2 * F(0.5)) * col3(1 - lanes * 0.85) + st
    out = mix(out, crescent, cov) + crescent * col3(1 - cov)
    return ground(out, d, hexc("05020c"))


# ============================================================== catalog
SKIES = [
    # id, name, category, function, exposure
    ("milky_way", "Milky Way", "space", s_milky_way, 1.35),
    ("orion_veil", "Orion Veil", "space", s_orion_veil, 1.25),
    ("andromeda", "Andromeda", "space", s_andromeda, 1.3),
    ("ringed_giant", "Ringed Giant", "space", s_ringed_giant, 1.2),
    ("emerald_halo", "Emerald Halo", "space", s_emerald_halo, 1.25),
    ("stellar_nursery", "Stellar Nursery", "space", s_stellar_nursery, 1.2),
    ("golden_hour", "Golden Hour", "sunsets", s_golden_hour, 1.0),
    ("lavender_dusk", "Lavender Dusk", "sunsets", s_lavender_dusk, 1.0),
    ("tropical_blaze", "Tropical Blaze", "sunsets", s_tropical_blaze, 0.95),
    ("alpine_dawn", "Alpine Dawn", "sunsets", s_alpine_dawn, 1.0),
    ("desert_haze", "Desert Haze", "sunsets", s_desert_haze, 0.95),
    ("aurora_curtains", "Northern Lights", "atmospheric", s_aurora_curtains, 1.2),
    ("fair_weather", "Fair Weather", "atmospheric", s_fair_weather, 1.0),
    ("storm_front", "Storm Front", "atmospheric", s_storm_front, 1.1),
    ("noctilucent", "Blue Hour", "atmospheric", s_noctilucent, 1.15),
    ("cirrus_silk", "Cirrus Silk", "atmospheric", s_cirrus_silk, 1.0),
    ("blood_moon", "Blood Moon", "dark", s_blood_moon, 1.2),
    ("moonless", "Moonless Night", "dark", s_moonless, 1.4),
    ("corona", "Total Eclipse", "dark", s_corona, 1.1),
    ("ashfall", "Ashfall", "dark", s_ashfall, 1.05),
    ("twin_moons", "Twin Moons", "fantasy", s_twin_moons, 1.1),
    ("dreamscape", "Dreamscape", "fantasy", s_dreamscape, 1.0),
    ("prism_veil", "Prism Veil", "fantasy", s_prism_veil, 1.15),
    ("sky_rings", "Sky Rings", "fantasy", s_sky_rings, 1.0),
    ("synthwave", "Synthwave", "neon", s_synthwave, 1.0),
    ("neon_flux", "Neon Flux", "neon", s_neon_flux, 1.1),
    ("low_poly", "Low Poly", "neon", s_low_poly, 1.0),
    ("clear_blue", "Clear Blue", "minimal", s_clear_blue, 1.0),
    ("soft_clouds", "Soft Clouds", "minimal", s_soft_clouds, 1.0),
    ("calm_night", "Calm Night", "minimal", s_calm_night, 1.2),
    ("starlight", "Starlight", "minimal", s_starlight, 1.3),
    ("pastel", "Pastel", "minimal", s_pastel, 1.0),
    ("slate", "Slate", "minimal", s_slate, 1.0),
    ("soft_sunset", "Soft Sunset", "minimal", s_soft_sunset, 1.0),
    ("night_nebula", "Night Nebula", "kestrel", s_night_nebula, 1.25),
]


# Where each sky's centrepiece is (yaw, pitch), for the preview sheets.
HERO = {"milky_way": (120, 35), "orion_veil": (200, 30), "andromeda": (30, 58), "ringed_giant": (115, 16),
        "emerald_halo": (320, 48), "stellar_nursery": (70, 26), "golden_hour": (255, 8), "lavender_dusk": (90, 8),
        "tropical_blaze": (280, 8), "alpine_dawn": (100, 6), "desert_haze": (240, 8), "storm_front": (250, 8),
        "blood_moon": (140, 32), "corona": (180, 40), "twin_moons": (215, 35), "sky_rings": (120, 30),
        "synthwave": (180, 5), "calm_night": (220, 35), "night_nebula": (150, 40), "noctilucent": (0, 10)}


def thumb(atlas):
    img = Image.fromarray(atlas, "RGB")
    w, h = img.size
    return img.crop((0, 0, w, h // 2)).resize((384, 128), Image.LANCZOS)


def preview(ids):
    out = os.path.join(os.getcwd(), "sky-previews")
    os.makedirs(out, exist_ok=True)
    for sid, name, cat, fn, exp in SKIES:
        if ids and sid not in ids:
            continue
        hero = HERO.get(sid, (45, 30))
        cams = ((0, 8), (90, 8), (180, 8), (270, 8), (45, 75), hero)
        views = [render_view(fn, yaw, pitch, 90, 480, 270, exp) for yaw, pitch in cams]
        sheet = np.concatenate([np.concatenate(views[:3], 1), np.concatenate(views[3:], 1)], 0)
        Image.fromarray(sheet).save(os.path.join(out, sid + ".png"))
        print("preview", sid, flush=True)


def main(argv):
    if argv and argv[0] == "--preview":
        preview(set(argv[1:]))
        return
    only = set(argv)
    sky_dir = os.path.join(TEX, "sky")
    os.makedirs(os.path.join(sky_dir, "thumbs"), exist_ok=True)
    for sid, name, cat, fn, exp in SKIES:
        if only and sid not in only:
            continue
        atlas = render_atlas(fn, FACE, exp)
        Image.fromarray(atlas, "RGB").save(os.path.join(sky_dir, sid + ".png"), optimize=True)
        thumb(atlas).save(os.path.join(sky_dir, "thumbs", sid + ".png"), optimize=True)
        print("sky %-16s %5d KB" % (sid, os.path.getsize(os.path.join(sky_dir, sid + ".png")) // 1024), flush=True)
    catalog = {"_comment": "Bundled skies (3x2 atlases in textures/sky/<id>.png, 1024 px faces; thumbnails in "
                           "textures/sky/thumbs/). Generated by tools/gen_skies.py. See documentation/SKIES.md.",
               "skies": [{"id": i, "name": n, "category": c} for i, n, c, _, _ in SKIES]}
    with open(os.path.join(RES, "skies.json"), "w") as f:
        json.dump(catalog, f, indent=2)
        f.write("\n")
    from gen_cosmetics import write_preview_table
    write_preview_table(RES)


if __name__ == "__main__":
    main(sys.argv[1:])
