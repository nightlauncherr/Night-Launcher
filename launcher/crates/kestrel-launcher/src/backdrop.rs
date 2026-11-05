//! The launcher's backdrop, matching the in-game Kestrel main menu: a
//! graphite gradient, a faint warm light, flowing silk-like ribbons on the
//! right with a few drifting sparkles, and a soft vignette.
//!
//! The ribbons move only while the launcher window is focused, visible and
//! on the Home page (see `LauncherApp`); otherwise the frozen frame is drawn
//! and nothing asks for repaints.

use egui::{Color32, Mesh, Painter, Pos2, Rect, Shape, pos2};

use crate::theme;

const RIBBONS: usize = 7;
const STEPS: usize = 40;
const SPARKS: usize = 12;

/// Draw the backdrop into `rect`. `time` is seconds of animation; `ribbons`
/// (0..1) fades the ribbons and sparkles (pages other than Home show them dimmer).
pub fn paint(p: &Painter, rect: Rect, time: f32, ribbons: f32, glow: Option<(Pos2, f32)>) {
    let (w, h) = (rect.width(), rect.height());
    // Base gradient (top-left light to bottom-right dark), as four coloured corners.
    let mut m = Mesh::default();
    m.colored_vertex(rect.left_top(), theme::BG_TOP);
    m.colored_vertex(rect.right_top(), Color32::from_rgb(0x1F, 0x22, 0x27));
    m.colored_vertex(rect.right_bottom(), theme::BG_BOTTOM);
    m.colored_vertex(rect.left_bottom(), Color32::from_rgb(0x14, 0x15, 0x19));
    m.add_triangle(0, 1, 2);
    m.add_triangle(0, 2, 3);
    p.add(Shape::mesh(m));

    // Cool soft light upper left; warm light behind the player preview.
    radial(
        p,
        rect.left_top() + egui::vec2(w * 0.12, h * 0.18),
        w.max(h) * 0.45,
        Color32::from_rgb(0x8F, 0xA6, 0xC8),
        0.05,
    );
    if let Some((c, r)) = glow {
        radial(p, c, r, theme::ACCENT, 0.10);
    }

    if ribbons > 0.01 {
        let mut m = Mesh::default();
        for k in 0..RIBBONS {
            let kf = k as f32;
            let base = rect.left() + w * (0.66 + 0.055 * kf);
            let amp = h * (0.035 + 0.008 * kf);
            let width = w * (0.045 + 0.006 * (k % 3) as f32);
            let speed = 0.22 + 0.04 * kf;
            let alpha = (0.13 + 0.035 * (k % 2) as f32) * ribbons;
            let phase = time * speed + kf * 0.8;
            let centre = |i: usize| {
                let ph = i as f32 / STEPS as f32 * std::f32::consts::PI * 2.1 + phase;
                base + amp * ph.sin() + amp * 0.35 * (ph * 2.3 + kf).sin()
            };
            let lit = Color32::from_rgb(0xF2, 0xF4, 0xF8);
            let shade = Color32::from_rgb(0xB9, 0xBE, 0xC8);
            let mut prev = centre(0);
            for i in 0..STEPS {
                let next = centre(i + 1);
                let y0 = rect.top() + h * i as f32 / STEPS as f32;
                let y1 = rect.top() + h * (i + 1) as f32 / STEPS as f32;
                // Fill: light on the leading edge, fading across.
                quad(
                    &mut m,
                    [
                        pos2(prev - width / 2.0, y0),
                        pos2(prev + width / 2.0, y0),
                        pos2(next + width / 2.0, y1),
                        pos2(next - width / 2.0, y1),
                    ],
                    [
                        with_alpha(lit, alpha),
                        with_alpha(shade, 0.0),
                        with_alpha(shade, 0.0),
                        with_alpha(lit, alpha),
                    ],
                );
                // Thin highlight along the leading edge.
                let (e0, e1) = (prev - width / 2.0, next - width / 2.0);
                quad(
                    &mut m,
                    [
                        pos2(e0 - 1.2, y0),
                        pos2(e0 + 0.6, y0),
                        pos2(e1 + 0.6, y1),
                        pos2(e1 - 1.2, y1),
                    ],
                    [
                        with_alpha(Color32::WHITE, 0.0),
                        with_alpha(Color32::WHITE, alpha * 1.6),
                        with_alpha(Color32::WHITE, alpha * 1.6),
                        with_alpha(Color32::WHITE, 0.0),
                    ],
                );
                prev = next;
            }
        }
        // Sparkles drifting up along the ribbons.
        for s in 0..SPARKS {
            let sf = s as f32;
            let fx = frac((sf * 12.9898).sin() * 43758.545);
            let fy = frac((sf * 12.9898 * 1.7).sin() * 24634.634);
            let x = rect.left() + w * (0.70 + 0.28 * fx);
            let y = rect.bottom() - frac(fy + time * (0.015 + 0.01 * (s % 3) as f32)) * h * 1.1
                + h * 0.05;
            let tw = 0.5 + 0.5 * (time * (1.3 + 0.3 * (s % 4) as f32) + sf).sin();
            let r = (1.4 + (s % 3) as f32 * 0.6) * 2.6;
            sparkle(&mut m, pos2(x, y), r, 0.5 * tw * ribbons);
        }
        p.add(Shape::mesh(m));
    }

    // Vignette.
    let e = w.min(h) * 0.22;
    let dark = Color32::from_black_alpha(110);
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

/// Four-pointed sparkle.
fn sparkle(m: &mut Mesh, c: Pos2, r: f32, a: f32) {
    if a <= 0.01 {
        return;
    }
    const D: [(f32, f32); 4] = [(0.0, -1.0), (1.0, 0.0), (0.0, 1.0), (-1.0, 0.0)];
    for (i, di) in D.iter().enumerate() {
        let dj = D[(i + 1) % 4];
        let tip_i = c + egui::vec2(di.0, di.1) * r;
        let tip_j = c + egui::vec2(dj.0, dj.1) * r;
        let waist = c + egui::vec2(di.0 + dj.0, di.1 + dj.1) * 0.18 * r;
        let base = m.vertices.len() as u32;
        m.colored_vertex(c, with_alpha(Color32::WHITE, a));
        m.colored_vertex(tip_i, with_alpha(Color32::WHITE, 0.0));
        m.colored_vertex(waist, with_alpha(Color32::WHITE, a * 0.4));
        m.colored_vertex(tip_j, with_alpha(Color32::WHITE, 0.0));
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
}
