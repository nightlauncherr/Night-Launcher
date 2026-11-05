//! The launcher's backdrop: Night Launcher's purple nebula. A midnight-violet
//! gradient, soft purple and magenta nebula clouds with faint stars (one
//! texture, generated once and cached), a few gently twinkling stars, a
//! purple glow behind the player preview, and a soft vignette.
//!
//! The clouds drift and the stars twinkle only while the launcher window is
//! focused, visible and on the Home page (see `LauncherApp`); otherwise the
//! frozen frame is drawn and nothing asks for repaints. Per frame this is a
//! handful of meshes and one textured quad.

use egui::{
    Color32, ColorImage, Context, Mesh, Painter, Pos2, Rect, Shape, TextureHandle, TextureOptions,
    pos2,
};

use crate::theme;

/// Size of the cached nebula texture; it is drawn stretched (and smoothed),
/// so a small texture keeps start-up instant.
const NEBULA_W: usize = 320;
const NEBULA_H: usize = 200;
const TWINKLES: usize = 10;

/// Draw the backdrop into `rect`. `time` is seconds of animation; `strength`
/// (0..1) fades the clouds and twinkles (pages other than Home show them dimmer).
pub fn paint(p: &Painter, rect: Rect, time: f32, strength: f32, glow: Option<(Pos2, f32)>) {
    let (w, h) = (rect.width(), rect.height());
    // Base gradient (deep violet top left to midnight bottom right), as four coloured corners.
    let mut m = Mesh::default();
    m.colored_vertex(rect.left_top(), theme::BG_TOP);
    m.colored_vertex(rect.right_top(), theme::BG2);
    m.colored_vertex(rect.right_bottom(), theme::BG_BOTTOM);
    m.colored_vertex(rect.left_bottom(), theme::BG2);
    m.add_triangle(0, 1, 2);
    m.add_triangle(0, 2, 3);
    p.add(Shape::mesh(m));

    // Nebula clouds: the cached texture, stretched over the window and drifting
    // very slowly (a few percent of the window over minutes).
    let tex = nebula_texture(p.ctx());
    let dx = 0.025 * (time * 0.021).sin();
    let dy = 0.018 * (time * 0.016 + 1.3).cos();
    let uv = Rect::from_min_max(pos2(0.04 + dx, 0.04 + dy), pos2(0.96 + dx, 0.96 + dy));
    let alpha = 0.55 + 0.45 * strength.clamp(0.0, 1.0);
    p.image(tex.id(), rect, uv, Color32::WHITE.gamma_multiply(alpha));

    // Purple light behind the player preview on Home.
    if let Some((c, r)) = glow {
        radial(p, c, r, theme::ACCENT_LO, 0.16);
        radial(p, c, r * 0.55, theme::MAGENTA, 0.06);
    }

    // A few brighter stars that twinkle.
    if strength > 0.01 {
        let mut m = Mesh::default();
        for s in 0..TWINKLES {
            let sf = s as f32;
            let fx = frac((sf * 12.9898).sin() * 43758.545);
            let fy = frac((sf * 78.233).sin() * 24634.634);
            let x = rect.left() + w * fx;
            let y = rect.top() + h * fy * 0.85;
            let tw = 0.5 + 0.5 * (time * (0.6 + 0.25 * (s % 4) as f32) + sf * 1.7).sin();
            let r = 2.2 + (s % 3) as f32 * 1.1;
            sparkle(&mut m, pos2(x, y), r, (0.10 + 0.22 * tw) * strength);
        }
        p.add(Shape::mesh(m));
    }

    // Vignette.
    let e = w.min(h) * 0.22;
    let dark = Color32::from_black_alpha(120);
    let clear = Color32::TRANSPARENT;
    let mut m = Mesh::default();
    quad(
        &mut m,
        [
            rect.left_top(),
            rect.right_top(),
            pos2(rect.right(), rect.top() + e),
            pos2(rect.left(), rect.top() + e),
        ],
        [dark, dark, clear, clear],
    );
    quad(
        &mut m,
        [
            pos2(rect.left(), rect.bottom() - e),
            pos2(rect.right(), rect.bottom() - e),
            rect.right_bottom(),
            rect.left_bottom(),
        ],
        [clear, clear, dark, dark],
    );
    quad(
        &mut m,
        [
            rect.left_top(),
            pos2(rect.left() + e, rect.top()),
            pos2(rect.left() + e, rect.bottom()),
            rect.left_bottom(),
        ],
        [dark, clear, clear, dark],
    );
    quad(
        &mut m,
        [
            pos2(rect.right() - e, rect.top()),
            rect.right_top(),
            rect.right_bottom(),
            pos2(rect.right() - e, rect.bottom()),
        ],
        [clear, dark, dark, clear],
    );
    p.add(Shape::mesh(m));
}

/// The nebula texture, generated on first use and kept in egui's memory.
fn nebula_texture(ctx: &Context) -> TextureHandle {
    let id = egui::Id::unique("night-nebula-texture");
    if let Some(t) = ctx.data(|d| d.get_temp::<TextureHandle>(id)) {
        return t;
    }
    let tex = ctx.load_texture("night-nebula", nebula_image(), TextureOptions::LINEAR);
    ctx.data_mut(|d| d.insert_temp(id, tex.clone()));
    tex
}

/// Soft purple/magenta clouds with faint stars, premultiplied, deterministic.
fn nebula_image() -> ColorImage {
    let mut px = vec![Color32::TRANSPARENT; NEBULA_W * NEBULA_H];
    let purple = theme::ACCENT_LO;
    let magenta = theme::MAGENTA;
    let lavender = theme::ACCENT_HI;
    for y in 0..NEBULA_H {
        for x in 0..NEBULA_W {
            let u = x as f32 / NEBULA_W as f32;
            let v = y as f32 / NEBULA_H as f32;
            // Large purple cloud (upper left and centre), smaller magenta wisp
            // (lower right), shaped by fractal noise.
            let n = fbm(u * 3.2, v * 2.2, 5);
            let n2 = fbm(u * 5.0 + 11.0, v * 3.6 + 4.0, 4);
            let cloud_a = smooth((n - 0.42) * 2.6) * falloff(u, v, 0.30, 0.32, 0.62);
            let cloud_b = smooth((n2 - 0.48) * 3.0) * falloff(u, v, 0.78, 0.74, 0.45);
            let core = smooth((n - 0.62) * 4.0) * falloff(u, v, 0.34, 0.30, 0.40);
            let a_p = cloud_a * 0.30;
            let a_m = cloud_b * 0.22;
            let a_l = core * 0.10;
            let mut c = blend(Color32::TRANSPARENT, purple, a_p);
            c = blend(c, magenta, a_m);
            c = blend(c, lavender, a_l);
            px[y * NEBULA_W + x] = c;
        }
    }
    // Faint static stars.
    let mut seed = 0x9E37_79B9u32;
    for _ in 0..70 {
        seed = xorshift(seed);
        let x = (seed % NEBULA_W as u32) as usize;
        seed = xorshift(seed);
        let y = (seed % NEBULA_H as u32) as usize;
        seed = xorshift(seed);
        let b = 0.12 + (seed % 100) as f32 / 100.0 * 0.30;
        let i = y * NEBULA_W + x;
        px[i] = blend(px[i], Color32::from_rgb(0xE6, 0xDC, 0xFF), b);
    }
    ColorImage::new([NEBULA_W, NEBULA_H], px)
}

/// Premultiplied "over": paint `top` with coverage `a` over `under`.
fn blend(under: Color32, top: Color32, a: f32) -> Color32 {
    let a = a.clamp(0.0, 1.0);
    let k = 1.0 - a;
    let ch = |u: u8, t: u8| ((u as f32) * k + (t as f32) * a).round().min(255.0) as u8;
    Color32::from_rgba_premultiplied(
        ch(under.r(), top.r()),
        ch(under.g(), top.g()),
        ch(under.b(), top.b()),
        ch(under.a(), 255),
    )
}

/// 1 at (cx, cy), fading to 0 at distance `r` (aspect-corrected).
fn falloff(u: f32, v: f32, cx: f32, cy: f32, r: f32) -> f32 {
    let dx = (u - cx) * 1.6;
    let dy = v - cy;
    let d = (dx * dx + dy * dy).sqrt() / r;
    smooth(1.0 - d)
}

fn smooth(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

fn xorshift(mut x: u32) -> u32 {
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    x
}

/// Hash-based value noise in 0..1.
fn hash(ix: i32, iy: i32) -> f32 {
    let mut h = (ix as u32).wrapping_mul(0x8DA6_B343) ^ (iy as u32).wrapping_mul(0xD816_3841);
    h = xorshift(h ^ 0x5bd1_e995);
    (h & 0xFFFF) as f32 / 65535.0
}

fn value_noise(x: f32, y: f32) -> f32 {
    let (x0, y0) = (x.floor(), y.floor());
    let (fx, fy) = (x - x0, y - y0);
    let (ix, iy) = (x0 as i32, y0 as i32);
    let sx = fx * fx * (3.0 - 2.0 * fx);
    let sy = fy * fy * (3.0 - 2.0 * fy);
    let a = hash(ix, iy) + (hash(ix + 1, iy) - hash(ix, iy)) * sx;
    let b = hash(ix, iy + 1) + (hash(ix + 1, iy + 1) - hash(ix, iy + 1)) * sx;
    a + (b - a) * sy
}

/// Fractal noise in 0..1.
fn fbm(x: f32, y: f32, octaves: u32) -> f32 {
    let (mut sum, mut amp, mut freq, mut norm) = (0.0, 0.5, 1.0, 0.0);
    for _ in 0..octaves {
        sum += value_noise(x * freq, y * freq) * amp;
        norm += amp;
        amp *= 0.5;
        freq *= 2.0;
    }
    sum / norm
}

fn frac(v: f32) -> f32 {
    v - v.floor()
}

/// Premultiplied colour with alpha `a` (0..1).
fn with_alpha(c: Color32, a: f32) -> Color32 {
    let a = (a.clamp(0.0, 1.0) * 255.0).round() as u8;
    Color32::from_rgba_unmultiplied(c.r(), c.g(), c.b(), a)
}

fn quad(m: &mut Mesh, pts: [Pos2; 4], cols: [Color32; 4]) {
    let i = m.vertices.len() as u32;
    for (p, c) in pts.into_iter().zip(cols) {
        m.colored_vertex(p, c);
    }
    m.add_triangle(i, i + 1, i + 2);
    m.add_triangle(i, i + 2, i + 3);
}

/// Soft round light: alpha `a` at the centre, fading to nothing at radius `r`.
fn radial(p: &Painter, c: Pos2, r: f32, color: Color32, a: f32) {
    let mut m = Mesh::default();
    m.colored_vertex(c, with_alpha(color, a));
    let segs = 48;
    for i in 0..=segs {
        let an = i as f32 / segs as f32 * std::f32::consts::TAU;
        m.colored_vertex(
            c + egui::vec2(an.cos(), an.sin()) * r,
            with_alpha(color, 0.0),
        );
    }
    for i in 1..=segs as u32 {
        m.add_triangle(0, i, i + 1);
    }
    p.add(Shape::mesh(m));
}

/// Four-pointed star.
fn sparkle(m: &mut Mesh, c: Pos2, r: f32, a: f32) {
    if a <= 0.01 {
        return;
    }
    const D: [(f32, f32); 4] = [(0.0, -1.0), (1.0, 0.0), (0.0, 1.0), (-1.0, 0.0)];
    let star = theme::ACCENT_HI;
    for (i, di) in D.iter().enumerate() {
        let dj = D[(i + 1) % 4];
        let tip_i = c + egui::vec2(di.0, di.1) * r;
        let tip_j = c + egui::vec2(dj.0, dj.1) * r;
        let waist = c + egui::vec2(di.0 + dj.0, di.1 + dj.1) * 0.18 * r;
        let base = m.vertices.len() as u32;
        m.colored_vertex(c, with_alpha(Color32::WHITE, a));
        m.colored_vertex(tip_i, with_alpha(star, 0.0));
        m.colored_vertex(waist, with_alpha(star, a * 0.4));
        m.colored_vertex(tip_j, with_alpha(star, 0.0));
        m.add_triangle(base, base + 1, base + 2);
        m.add_triangle(base, base + 2, base + 3);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn premultiplied_alpha_is_clamped() {
        assert_eq!(with_alpha(Color32::WHITE, 2.0), Color32::WHITE);
        assert_eq!(with_alpha(Color32::WHITE, -1.0), Color32::TRANSPARENT);
        assert_eq!(frac(2.25), 0.25);
        assert_eq!(frac(-0.25), 0.75);
    }

    #[test]
    fn nebula_is_deterministic_premultiplied_and_restrained() {
        let a = nebula_image();
        let b = nebula_image();
        assert_eq!(a.pixels, b.pixels);
        assert_eq!(a.size, [NEBULA_W, NEBULA_H]);
        let mut max_a = 0u8;
        for c in &a.pixels {
            // Premultiplied: no channel above alpha.
            assert!(c.r() <= c.a() && c.g() <= c.a() && c.b() <= c.a(), "{c:?}");
            max_a = max_a.max(c.a());
        }
        // Clouds are visible but never close to opaque (stars excepted).
        let cloud_max = a
            .pixels
            .iter()
            .filter(|c| c.r() < 0xC0)
            .map(|c| c.a())
            .max()
            .unwrap_or(0);
        assert!(cloud_max > 30 && cloud_max < 200, "cloud alpha {cloud_max}");
        assert!(max_a > 0);
    }

    #[test]
    fn noise_stays_in_range() {
        for i in 0..200 {
            let v = fbm(i as f32 * 0.37, i as f32 * 0.11, 5);
            assert!((0.0..=1.0).contains(&v));
        }
    }
}
