"""Rendering toolkit for Night Launcher's bundled skies (see gen_skies.py).

Every sky is a pure function of the 3D view direction ``d`` (unit vectors,
shape (..., 3), y up). The atlas writer samples each texel's direction from
the same corner table SkyboxRenderer uses, so the six faces always join
seamlessly; everything here (noise, stars, clouds) is defined in 3D for that
reason. Colours are linear RGB floats; ``finish`` tone-maps and encodes.

NumPy only, deterministic (fixed seeds), float32 throughout.
"""
import numpy as np

F = np.float32
TAU = F(2 * np.pi)

# (col, row, TL, BL, BR, TR) per face. Must match SkyboxRenderer.render().
R = 1.0
FACES = [
    (0, 0, (-R, R, -R), (-R, -R, -R), (R, -R, -R), (R, R, -R)),   # north (-Z)
    (1, 0, (R, R, -R), (R, -R, -R), (R, -R, R), (R, R, R)),       # east (+X)
    (2, 0, (R, R, R), (R, -R, R), (-R, -R, R), (-R, R, R)),       # south (+Z)
    (0, 1, (-R, R, R), (-R, -R, R), (-R, -R, -R), (-R, R, -R)),   # west (-X)
    (1, 1, (-R, R, R), (-R, R, -R), (R, R, -R), (R, R, R)),       # up (+Y)
    (2, 1, (-R, -R, -R), (-R, -R, R), (R, -R, R), (R, -R, -R)),   # down (-Y)
]


# ---------------------------------------------------------------- basics
def hexc(s):
    s = s.lstrip("#")
    return srgb_to_linear(np.array([int(s[i:i + 2], 16) / 255.0 for i in (0, 2, 4)], dtype=F))


def srgb_to_linear(c):
    c = np.asarray(c, dtype=F)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4).astype(F)


def linear_to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055).astype(F)


def normalize(v):
    v = np.asarray(v, dtype=F)
    return v / np.linalg.norm(v, axis=-1, keepdims=True)


def dirv(az_deg, el_deg):
    """Unit direction from azimuth (0 = north (-Z), 90 = east (+X)) and elevation."""
    a, e = np.radians(az_deg), np.radians(el_deg)
    return normalize(np.array([np.sin(a) * np.cos(e), np.sin(e), -np.cos(a) * np.cos(e)], dtype=F))


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return (t * t * (3 - 2 * t)).astype(F)


def mix(a, b, t):
    t = np.asarray(t, dtype=F)
    if t.ndim and np.ndim(a) and np.shape(a)[-1:] == (3,) and t.shape[-1:] != (3,):
        t = t[..., None]
    elif t.ndim and np.ndim(b) and np.shape(b)[-1:] == (3,) and t.shape[-1:] != (3,):
        t = t[..., None]
    return (a * (1 - t) + b * t).astype(F)


def col3(v):
    """Broadcast a scalar field to RGB."""
    return np.asarray(v, dtype=F)[..., None]


def gradient(x, stops):
    """Piecewise-smooth colour ramp: stops = [(pos, rgb), ...] with increasing pos."""
    x = np.asarray(x, dtype=F)
    out = np.zeros(x.shape + (3,), dtype=F) + stops[0][1]
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        t = np.clip((x - p0) / (p1 - p0), 0, 1)
        t = t * t * (3 - 2 * t)
        inside = (x >= p0)[..., None]
        out = np.where(inside, c0 + (c1 - c0) * t[..., None], out)
    return out.astype(F)


def angle_to(d, v):
    """Angle (radians) between each direction and a unit vector."""
    return np.arccos(np.clip(np.tensordot(d, np.asarray(v, dtype=F), axes=([-1], [0])), -1, 1)).astype(F)


def rot_matrix(seed):
    """A random rotation (deterministic) to decorrelate noise octaves."""
    rng = np.random.RandomState(seed)
    q, _ = np.linalg.qr(rng.normal(size=(3, 3)))
    return q.astype(F)


# ---------------------------------------------------------------- noise
_GRAD = np.array([[1, 1, 0], [-1, 1, 0], [1, -1, 0], [-1, -1, 0], [1, 0, 1], [-1, 0, 1],
                  [1, 0, -1], [-1, 0, -1], [0, 1, 1], [0, -1, 1], [0, 1, -1], [0, -1, -1],
                  [1, 1, 0], [-1, 1, 0], [0, -1, 1], [0, -1, -1]], dtype=F)


def _hash(ix, iy, iz, seed):
    h = (ix.astype(np.uint32) * np.uint32(0x8DA6B343)) ^ (iy.astype(np.uint32) * np.uint32(0xD8163841)) \
        ^ (iz.astype(np.uint32) * np.uint32(0xCB1AB31F)) ^ np.uint32(seed * 0x9E3779B1 & 0xFFFFFFFF)
    h ^= h >> np.uint32(15)
    h *= np.uint32(0x2C1B3C6D)
    h ^= h >> np.uint32(12)
    h *= np.uint32(0x297A2D39)
    h ^= h >> np.uint32(15)
    return h


def _hash01(ix, iy, iz, seed):
    return (_hash(ix, iy, iz, seed) & np.uint32(0xFFFFFF)).astype(F) / F(16777216.0)


def perlin(p, seed=0):
    """Improved 3D gradient noise, roughly in [-1, 1]."""
    p = np.asarray(p, dtype=F)
    fl = np.floor(p)
    i = fl.astype(np.int64)
    f = (p - fl).astype(F)
    u = f * f * f * (f * (f * 6 - 15) + 10)
    ix, iy, iz = i[..., 0], i[..., 1], i[..., 2]
    fx, fy, fz = f[..., 0], f[..., 1], f[..., 2]

    def g(dx, dy, dz):
        h = _hash(ix + dx, iy + dy, iz + dz, seed) & np.uint32(15)
        gv = _GRAD[h]
        return gv[..., 0] * (fx - dx) + gv[..., 1] * (fy - dy) + gv[..., 2] * (fz - dz)

    x00 = g(0, 0, 0) + (g(1, 0, 0) - g(0, 0, 0)) * u[..., 0]
    x10 = g(0, 1, 0) + (g(1, 1, 0) - g(0, 1, 0)) * u[..., 0]
    x01 = g(0, 0, 1) + (g(1, 0, 1) - g(0, 0, 1)) * u[..., 0]
    x11 = g(0, 1, 1) + (g(1, 1, 1) - g(0, 1, 1)) * u[..., 0]
    y0 = x00 + (x10 - x00) * u[..., 1]
    y1 = x01 + (x11 - x01) * u[..., 1]
    return ((y0 + (y1 - y0) * u[..., 2]) * F(1.05)).astype(F)


def fbm(p, seed=0, octaves=6, lacunarity=2.03, gain=0.5):
    """Fractal sum of rotated octaves, roughly in [-1, 1]."""
    p = np.asarray(p, dtype=F)
    total = np.zeros(p.shape[:-1], dtype=F)
    amp, norm, q = F(1), F(0), p
    for o in range(octaves):
        total += amp * perlin(q, seed + o * 101)
        norm += amp
        amp *= F(gain)
        q = (q @ rot_matrix(seed * 31 + o)) * F(lacunarity)
    return (total / norm).astype(F)


def ridged(p, seed=0, octaves=5, lacunarity=2.1, gain=0.55):
    """Ridged multifractal in [0, 1]: sharp filaments (dust lanes, nebula wisps)."""
    p = np.asarray(p, dtype=F)
    total = np.zeros(p.shape[:-1], dtype=F)
    amp, norm, q, prev = F(1), F(0), p, np.ones(p.shape[:-1], dtype=F)
    for o in range(octaves):
        n = 1 - np.abs(perlin(q, seed + o * 77))
        n = n * n * prev
        total += amp * n
        prev = np.clip(n * 1.6, 0, 1)
        norm += amp
        amp *= F(gain)
        q = (q @ rot_matrix(seed * 17 + o)) * F(lacunarity)
    return (total / norm).astype(F)


def warp(p, seed, amount, scale=1.0, octaves=4):
    """Domain warp: offset p by a vector-valued fBm (swirling, organic shapes)."""
    q = p * F(scale)
    off = np.stack([fbm(q, seed, octaves), fbm(q + 5.2, seed + 1, octaves), fbm(q + 11.7, seed + 2, octaves)], -1)
    return (p + off * F(amount)).astype(F)


# ---------------------------------------------------------------- stars
STAR_TEMPS = [hexc("9bb4ff"), hexc("c8d6ff"), hexc("ffffff"), hexc("fff4e0"), hexc("ffd9a8"), hexc("ffb27a")]


def stars(d, seed, cells=22.0, density=0.5, brightness=1.0, sharp=1.0, px=1024, color_var=1.0, power=28.0):
    """Point stars of varied size, brightness and colour, from a 3D cell grid.

    ``cells`` sets how many cells span the unit sphere's radius (more = more
    stars); each cell holds at most one star. Sizes scale with the face
    resolution ``px`` so stars stay crisp, round and about 1-3 texels wide.
    """
    d = np.asarray(d, dtype=F)
    p = d * F(cells)
    base = np.floor(p).astype(np.int64)
    out = np.zeros(d.shape, dtype=F)
    texel = F(np.pi / 2 / px)  # angular size of one texel near the face centre
    temps = np.stack(STAR_TEMPS)
    for ox in (-1, 0, 1):
        for oy in (-1, 0, 1):
            for oz in (-1, 0, 1):
                cx, cy, cz = base[..., 0] + ox, base[..., 1] + oy, base[..., 2] + oz
                present = _hash01(cx, cy, cz, seed) < density
                sx = cx + _hash01(cx, cy, cz, seed + 1)
                sy = cy + _hash01(cx, cy, cz, seed + 2)
                sz = cz + _hash01(cx, cy, cz, seed + 3)
                sv = np.stack([sx, sy, sz], -1).astype(F)
                sn = sv / np.maximum(np.linalg.norm(sv, axis=-1, keepdims=True), 1e-6)
                diff = d - sn
                dist2 = np.sum(diff * diff, -1)
                r = _hash01(cx, cy, cz, seed + 4)
                # Like real magnitudes: most stars faint, a few percent bright.
                b = F(0.035) + F(0.25) * r ** F(4) + F(3.2) * r ** F(power)
                sigma = texel * (F(0.6) + F(0.9) * r ** F(power * 0.5)) / F(sharp)
                core = np.exp(-dist2 / (2 * sigma * sigma))
                halo = np.exp(-np.sqrt(dist2) / (texel * F(3.5))) * F(0.04) * (r > 0.985)
                k = (_hash(cx, cy, cz, seed + 5) % np.uint32(len(STAR_TEMPS))).astype(np.int64)
                tint = temps[k]
                tint = F(1) + (tint - F(1)) * F(color_var)
                out += (((core + halo) * b * present)[..., None]) * tint
    return out * F(brightness)


# ---------------------------------------------------------------- atmosphere
def sun_glow(d, sun, size_deg=0.6, glow=1.0, col=None, halo_col=None, mie=0.8):
    """Sun disc (limb-darkened) plus a two-lobe forward-scattering glow."""
    ang = angle_to(d, sun)
    r = F(np.radians(size_deg))
    disc = smoothstep(r * 1.02, r * 0.92, ang) * (F(0.55) + F(0.45) * np.sqrt(np.clip(1 - (ang / r) ** 2, 0, 1)))
    inner = np.exp(-ang / F(0.035)) * F(1.4)
    outer = np.exp(-ang / F(0.32)) * F(0.45) * F(mie)
    col = hexc("fff2d6") if col is None else col
    halo_col = col if halo_col is None else halo_col
    return (col3(disc) * col * F(14.0) + col3(inner) * col * F(glow) + col3(outer) * halo_col * F(glow)).astype(F)


def scatter_sky(d, sun, zenith, horizon, sun_side=None, haze=0.35, ground=None, ground_dark=0.12, warm=None):
    """Clear-sky gradient with horizon brightening and a warmer, brighter sun side.

    A compact analytic stand-in for Rayleigh + Mie scattering: the zenith
    colour deepens upward, the horizon whitens through haze, and the half of
    the sky facing the sun brightens (``sun_side``) with a tint (``warm``).
    """
    y = d[..., 1]
    up = np.clip(y, 0, 1)
    t = np.power(up, F(0.45))
    sky = mix(horizon, zenith, t)
    if sun_side is not None:
        cosg = np.tensordot(d, sun, axes=([-1], [0]))
        lobe = np.clip((cosg + 1) / 2, 0, 1) ** F(3.0)
        hz = np.exp(-up / F(haze))
        sky = sky + col3(lobe * hz) * sun_side
        if warm is not None:
            sky = sky + col3(lobe ** F(6) * np.exp(-up / F(0.12))) * warm
    if ground is None:
        ground = horizon * F(ground_dark)
    below = np.clip(-y, 0, 1)
    g = mix(horizon * F(0.55), ground, smoothstep(0.0, 0.25, below))
    return np.where((y < 0)[..., None], g, sky).astype(F)


def plane_coords(d, height=1.0, eps=0.012):
    """Where each upward ray meets a horizontal layer at ``height`` (x, z), and 1/y."""
    y = np.maximum(d[..., 1], F(eps))
    k = F(height) / y
    return d[..., 0] * k, d[..., 2] * k, k


def cloud_layer(d, seed, sun, scale=0.35, cover=0.45, sharp=1.6, height=1.0, light=None, shadow=None,
                fade_lo=0.0, fade_hi=0.12, stretch=(1.0, 1.0), detail=7, wind=(0.0, 0.0), billow=0.0,
                thickness=1.0, ambient=None, edge=0.6, erode=0.0):
    """A perspective-correct cloud deck lit from ``sun``; returns (opacity, rgb).

    Shape: fBm on the layer plane (``billow`` > 0 rounds it into puffy,
    cauliflower-like cumulus). Lighting: density is sampled again a short way
    towards the sun; where more cloud lies sunward the texel is in the cloud's
    own shadow (grey base), where little lies sunward it is a lit edge, and
    texels near the sun get a bright silver lining. Thick cores darken
    (``thickness``), thin edges stay translucent.
    """
    x, z, k = plane_coords(d, height)
    x = x * F(stretch[0]) + F(wind[0])
    z = z * F(stretch[1]) + F(wind[1])

    # Distance (1/y) per texel: fine octaves fade out with distance so far clouds,
    # squeezed towards the horizon, don't alias into stripes.
    far = np.clip(np.log2(np.maximum(k, F(1))) / F(4.5), 0, 1)

    def density(px_, pz_):
        p = np.stack([px_ * F(scale), pz_ * F(scale), np.zeros_like(px_) + F(seed * 0.37)], -1)
        coarse = fbm(p, seed, octaves=3)
        finer = fbm(p, seed, octaves=detail)
        n = (coarse + (finer - coarse) * (1 - far)) * F(0.5) + F(0.5)
        if billow > 0:
            b = 1 - np.abs(fbm(p * F(2.3) + 3.1, seed + 50, max(3, detail - 2)))
            n = n * (1 - F(billow) * 0.35) + b * F(billow) * 0.35 * (1 - far * 0.7)
        if erode > 0:
            # Fine noise eats into thin edges (crisp, wispy cloud borders up close).
            e = fbm(p * F(5.0) + 7.7, seed + 90, 4) * F(0.5) + F(0.5)
            n = n + (e - F(0.5)) * F(erode) * F(0.22) * (1 - far)
        return np.clip((n - (1 - cover)) * F(sharp) / F(cover), 0, 1)

    dens = density(x, z)
    sx, sz = float(sun[0]), float(sun[2])
    sl = max(np.hypot(sx, sz), 1e-3)
    step = F(0.9) / F(scale) * F(0.25)
    dens2 = density(x + F(sx / sl) * step, z + F(sz / sl) * step)
    shadowed = np.clip(dens2 * F(1.4) - dens * F(0.25), 0, 1)
    lit = np.clip(1 - shadowed, 0, 1) ** F(0.8)
    core = np.clip(dens * F(thickness), 0, 1)
    light = hexc("ffffff") if light is None else light
    shadow = hexc("7d879b") if shadow is None else shadow
    ambient = shadow * F(1.15) if ambient is None else ambient
    rgb = mix(mix(light, ambient, core * F(0.35)), shadow, (1 - lit) * F(0.85))
    sunang = angle_to(d, sun)
    lining = np.exp(-sunang / F(0.22)) * np.clip(1 - dens, 0, 1) * dens * F(4.0)
    rgb = rgb + col3(lining) * light * F(1.2)
    fade = smoothstep(fade_lo, fade_hi, d[..., 1])
    # Crisp edges for dense decks (cumulus), soft for thin ones (cirrus, haze).
    opacity = smoothstep(0.0, F(edge), dens) * fade
    return opacity.astype(F), rgb.astype(F)


def galaxy_band(d, normal, core_dir, width=0.16, seed=0, core=1.0):
    """Milky-Way-like band along the great circle perpendicular to ``normal``:
    a soft glow, a brighter bulge towards ``core_dir`` and dark dust rifts."""
    b = np.tensordot(d, normal, axes=([-1], [0]))
    band = np.exp(-(b / F(width)) ** 2)
    tight = np.exp(-(b / F(width * 0.35)) ** 2)
    bulge = np.exp(-angle_to(d, core_dir) / F(0.55)) * F(core)
    n = fbm(d * F(6), seed, 6) * F(0.5) + F(0.5)
    patch = smoothstep(0.35, 0.9, fbm(d * F(14), seed + 30, 5) * 0.5 + 0.5)  # star clouds
    glow = band * (F(0.25) + F(0.7) * n + F(0.6) * patch) + bulge * band * F(1.6)
    dust = ridged(d * F(9) + F(3.1), seed + 9, 5)
    dust = smoothstep(0.35, 0.85, dust) * tight
    clumps = smoothstep(0.55, 0.95, fbm(d * F(40), seed + 20, 4) * 0.5 + 0.5) * band
    return glow.astype(F), (dust * F(0.85)).astype(F), clumps.astype(F)


def moon(d, center, radius_deg, seed=0, phase=None, tint=None, glow=0.5):
    """A moon disc with procedural maria and limb darkening; optional terminator
    (``phase`` = direction of its sunlight). Returns (rgb, coverage)."""
    c = np.asarray(center, dtype=F)
    r = F(np.radians(radius_deg))
    ang = angle_to(d, c)
    cov = smoothstep(r * 1.01, r * 0.97, ang)
    # Local disc coordinates.
    up = np.array([0, 1, 0], dtype=F)
    right = normalize(np.cross(up, c)) if abs(c[1]) < 0.99 else np.array([1, 0, 0], dtype=F)
    upv = np.cross(c, right)
    u = np.tensordot(d, right, axes=([-1], [0])) / np.sin(r)
    v = np.tensordot(d, upv, axes=([-1], [0])) / np.sin(r)
    w = np.sqrt(np.clip(1 - u * u - v * v, 0, 1))
    p = np.stack([u, v, w], -1) * F(2.2)
    maria = smoothstep(0.05, 0.45, fbm(p, seed, 5) * 0.5 + 0.5)
    craters = ridged(p * F(3.0), seed + 3, 4)
    alb = F(0.95) - F(0.32) * maria + F(0.06) * craters
    limb = F(0.55) + F(0.45) * w
    shade = np.ones_like(u)
    if phase is not None:
        lx, ly = phase
        shade = smoothstep(-0.08, 0.12, u * lx + v * ly + w * 0.25)
    tint = hexc("f2efe6") if tint is None else tint
    rgb = col3(alb * limb * shade) * tint * F(1.6)
    halo = np.exp(-np.maximum(ang - r, 0) / F(0.06)) * F(glow) * (1 - cov)
    return (rgb * col3(cov) + col3(halo) * tint * F(0.35)).astype(F), cov.astype(F)


def aurora(d, seed, colors, height=1.0, layers=40, depth=0.9, scale=0.55, strength=1.0, along=(1.0, 0.0)):
    """Aurora curtains, ray-marched: a thin wavy sheet hanging from a base
    height, sampled at many jittered heights so it rises into soft vertical
    rays (green at the base, through teal, to red/violet at the top)."""
    out = np.zeros(d.shape, dtype=F)
    low, mid, high = colors
    ax, az = along
    jit = (_hash01(np.floor(d[..., 0] * 4096).astype(np.int64), np.floor(d[..., 1] * 4096).astype(np.int64),
                   np.floor(d[..., 2] * 4096).astype(np.int64), seed) - F(0.5))
    # How far a ray moves across the sheet between two slices; the sheet is widened
    # by that much (keeping its total brightness) so thin slices don't show as bands.
    s0 = F(1.0 / np.sqrt(28.0))
    hor = np.hypot(d[..., 0], d[..., 2]) / np.maximum(d[..., 1], F(0.02))
    step = hor * F(depth / layers * scale)
    width = np.sqrt(s0 * s0 + step * step)
    for i in range(layers):
        t = (i + 0.5 + jit) / layers
        h = F(height) + F(depth) * t
        x, z, k = plane_coords(d, h, 0.02)
        s_ = x * ax + z * az
        q = -x * az + z * ax
        zero = np.zeros_like(s_)
        wob = fbm(np.stack([s_ * F(scale * 0.45), zero + F(seed), zero], -1), seed, 4)
        line = q * F(scale) - wob * F(1.8) - F(0.6)
        sheet = np.exp(-line * line / (2 * width * width)) * (s0 / width)
        fold = perlin(np.stack([s_ * F(scale * 2.2), zero + F(3.3), zero], -1), seed + 9) * F(0.5) + F(0.5)
        rays = (perlin(np.stack([s_ * F(scale * 24), q * F(scale * 4), zero + F(0.7)], -1), seed + 5) * F(0.5) + F(0.5)) ** F(2.0)
        cc = mix(mix(low, mid, smoothstep(0.0, 0.35, t)), high, smoothstep(0.35, 1.0, t))
        fall = (1 - t) ** F(1.8) * smoothstep(0.0, 0.06, t) * (F(0.35) + F(0.65) * fold)
        dist = np.exp(-k * F(0.10))
        out += col3(sheet * (F(0.35) + F(0.9) * rays) * fall * dist) * cc
    return (out * F(strength / layers * 5.0)).astype(F)


def nebula(d, seed, colors, scale=1.6, warp_amt=0.9, density=1.0, envelope_dir=None, envelope_size=1.2, dust=0.6):
    """Emission nebula: domain-warped fBm coloured by a three-stop ramp, with
    filaments and dark dust lanes. Returns (emission rgb, dust absorption)."""
    p = warp(d * F(scale), seed, warp_amt, 1.0, 4)
    n = fbm(p, seed + 3, 7) * F(0.5) + F(0.5)
    fil = ridged(p * F(1.8), seed + 7, 6)
    env = np.ones(d.shape[:-1], dtype=F)
    if envelope_dir is not None:
        env = np.exp(-(angle_to(d, envelope_dir) / F(envelope_size)) ** 2)
    dens = smoothstep(0.38, 0.95, n) * env * F(density)
    a, b, c = colors
    hue = smoothstep(0.3, 0.8, fbm(p * F(0.7), seed + 11, 3) * 0.5 + 0.5)
    rgb = mix(mix(a, b, hue), c, smoothstep(0.6, 1.0, fil) * 0.8)
    em = col3(dens * (0.35 + 0.9 * fil)) * rgb
    lanes = smoothstep(0.45, 0.8, ridged(p * F(1.2) + 7.3, seed + 13, 5)) * env * F(dust)
    return em.astype(F), lanes.astype(F)


# ---------------------------------------------------------------- output
def aces(x):
    a, b, c, d_, e = 2.51, 0.03, 2.43, 0.59, 0.14
    return np.clip((x * (a * x + b)) / (x * (c * x + d_) + e), 0, 1).astype(F)


_BAYER8 = None


def _bayer8():
    global _BAYER8
    if _BAYER8 is None:
        m = np.array([[0, 2], [3, 1]])
        for _ in range(2):
            m = np.block([[4 * m, 4 * m + 2], [4 * m + 3, 4 * m + 1]])
        _BAYER8 = (m / 64.0 - 0.5 + 1 / 128).astype(F)
    return _BAYER8


def finish(rgb, exposure=1.0):
    """Linear HDR -> display sRGB floats in [0, 1] (filmic roll-off)."""
    return linear_to_srgb(aces(np.asarray(rgb, dtype=F) * F(exposure)))


def face_dirs(face, px):
    col, row, tl, bl, br, tr = face
    t = (np.arange(px, dtype=F) + F(0.5)) / F(px)
    u, v = np.meshgrid(t, t)
    tl, bl, br, tr = (np.array(c, dtype=F) for c in (tl, bl, br, tr))
    top = tl + u[..., None] * (tr - tl)
    bottom = bl + u[..., None] * (br - bl)
    p = top + v[..., None] * (bottom - top)
    return normalize(p)


def render_atlas(fn, px, exposure=1.0):
    """Render all six faces into a 3x2 uint8 atlas (seamless by construction)."""
    atlas = np.zeros((px * 2, px * 3, 3), dtype=np.uint8)
    bay = np.tile(_bayer8(), (px // 8 + 1, px // 8 + 1))[:px, :px]
    for face in FACES:
        col, row = face[0], face[1]
        d = face_dirs(face, px)
        img = finish(fn(d), exposure)
        img = img * 255 + bay[..., None] * 1.0 + 0.5
        atlas[row * px:(row + 1) * px, col * px:(col + 1) * px] = np.clip(img, 0, 255).astype(np.uint8)
    return atlas


def render_view(fn, yaw_deg, pitch_deg, fov_deg=90, w=960, h=540, exposure=1.0):
    """A perspective camera view (for checking a sky the way a player sees it)."""
    yaw, pitch = np.radians(yaw_deg), np.radians(pitch_deg)
    fwd = np.array([np.sin(yaw) * np.cos(pitch), np.sin(pitch), -np.cos(yaw) * np.cos(pitch)], dtype=F)
    right = normalize(np.cross(fwd, np.array([0, 1, 0], dtype=F)))
    up = np.cross(right, fwd)
    th = np.tan(np.radians(fov_deg) / 2)
    xs = (np.arange(w, dtype=F) + 0.5) / w * 2 - 1
    ys = 1 - (np.arange(h, dtype=F) + 0.5) / h * 2
    X, Y = np.meshgrid(xs * th, ys * th * h / w)
    d = normalize(fwd + X[..., None] * right + Y[..., None] * up)
    return (finish(fn(d), exposure) * 255 + 0.5).clip(0, 255).astype(np.uint8)
