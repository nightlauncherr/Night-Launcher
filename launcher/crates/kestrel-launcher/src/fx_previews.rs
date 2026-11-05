//! Launcher previews for the advanced ("3D") kill effects.
//!
//! The game draws these with real geometry; here each one is a short 2D
//! illustration of the same choreography (same shapes, colours and timing,
//! compressed into the preview loop) so players can tell them apart before
//! picking one.

use std::f32::consts::{PI, TAU};

use egui::{Color32, Pos2, Shape, Stroke};

/// Shared drawing context of one preview frame.
pub(crate) struct Ctx<'a> {
    pub p: &'a egui::Painter,
    pub rect: egui::Rect,
    /// Ground line (y) and the victim's centre (x).
    pub ground: f32,
    pub cx: f32,
    /// Normalised loop time 0..1.
    pub u: f32,
    pub intensity: f32,
    /// The player's colour setting.
    pub user: Color32,
}

fn clamp01(v: f32) -> f32 {
    v.clamp(0.0, 1.0)
}

fn span(t: f32, a: f32, b: f32) -> f32 {
    clamp01((t - a) / (b - a))
}

fn ease_out(v: f32) -> f32 {
    let i = 1.0 - clamp01(v);
    1.0 - i * i * i
}

fn rgb(c: u32) -> Color32 {
    Color32::from_rgb((c >> 16) as u8, (c >> 8) as u8, c as u8)
}

fn hsv(h: f32, s: f32, v: f32) -> Color32 {
    Color32::from(egui::ecolor::Hsva::new(h.rem_euclid(1.0), s, v, 1.0))
}

/// Deterministic noise in 0..1 (same as the page's preview PRNG family).
fn noise(seed: u32) -> f32 {
    let mut x = seed.wrapping_mul(747_796_405).wrapping_add(2_891_336_453);
    x = ((x >> ((x >> 28) + 4)) ^ x).wrapping_mul(277_803_737);
    ((x >> 22) ^ x) as f32 / u32::MAX as f32
}

impl Ctx<'_> {
    fn a(&self, c: Color32, a: f32) -> Color32 {
        c.gamma_multiply(clamp01(a))
    }

    fn glow(&self, c: Pos2, r: f32, col: Color32, a: f32) {
        if a <= 0.01 || r <= 0.5 {
            return;
        }
        for k in 0..4 {
            let f = 1.0 - k as f32 * 0.22;
            self.p.circle_filled(c, r * f, self.a(col, a * 0.22));
        }
    }

    /// Ellipse outline (a ring seen at an angle).
    fn ellipse(&self, c: Pos2, radius: egui::Vec2, rot: f32, w: f32, col: Color32, a: f32) {
        let (rx, ry) = (radius.x, radius.y);
        if a <= 0.01 || rx <= 0.5 {
            return;
        }
        let pts: Vec<Pos2> = (0..=40)
            .map(|i| {
                let t = i as f32 / 40.0 * TAU;
                let (x, y) = (t.cos() * rx, t.sin() * ry);
                Pos2::new(
                    c.x + x * rot.cos() - y * rot.sin(),
                    c.y + x * rot.sin() + y * rot.cos(),
                )
            })
            .collect();
        self.p.add(Shape::line(pts, Stroke::new(w, self.a(col, a))));
    }

    fn line(&self, pts: Vec<Pos2>, w: f32, col: Color32, a: f32) {
        if a > 0.01 && pts.len() > 1 {
            self.p.add(Shape::line(pts, Stroke::new(w, self.a(col, a))));
        }
    }

    fn poly(&self, pts: Vec<Pos2>, col: Color32, a: f32) {
        if a > 0.01 && pts.len() > 2 {
            self.p
                .add(Shape::convex_polygon(pts, self.a(col, a), Stroke::NONE));
        }
    }

    fn at(&self, dx: f32, up: f32) -> Pos2 {
        Pos2::new(self.cx + dx, self.ground - up)
    }

    /// A shard/crystal pointing along `ang` (radians, 0 = up) from `base`.
    fn spike(&self, base: Pos2, ang: f32, len: f32, w: f32, col: Color32, a: f32) {
        let (s, c) = ang.sin_cos();
        let tip = Pos2::new(base.x + s * len, base.y - c * len);
        let l = Pos2::new(base.x - c * w, base.y - s * w);
        let r = Pos2::new(base.x + c * w, base.y + s * w);
        self.poly(vec![l, tip, r], col, a);
        self.line(vec![l, tip, r], 1.0, Color32::WHITE, a * 0.7);
    }
}

/// Draw the preview for an advanced effect. Returns false for other ids.
pub(crate) fn draw(c: &Ctx, effect: &str) -> bool {
    let u = c.u;
    let n = (16.0 * c.intensity).max(6.0) as u32;
    match effect {
        "bolt_strike" => {
            for strike in 0..3 {
                let st = strike as f32 * 0.12;
                let e = (-(u - st).max(0.0) * 14.0).exp() * if u >= st { 1.0 } else { 0.0 };
                let mut pts = Vec::new();
                let mut x = 0.0;
                for k in 0..12 {
                    let h = 1.0 - k as f32 / 11.0;
                    x = x * 0.6 + (noise(strike * 31 + k) - 0.5) * 30.0 * (0.3 + h);
                    pts.push(c.at(if k == 11 { 0.0 } else { x }, h * (c.ground - c.rect.min.y)));
                }
                c.line(pts.clone(), 7.0, rgb(0x5C8CFF), e * 0.5);
                c.line(pts, 2.0, Color32::WHITE, e);
                c.glow(c.at(0.0, 4.0), 40.0, rgb(0x9CC0FF), e * 0.7);
            }
            let r = span(u, 0.0, 0.5);
            c.ellipse(
                c.at(0.0, 2.0),
                egui::vec2(20.0 + ease_out(r) * 110.0, 6.0 + ease_out(r) * 14.0),
                0.0,
                3.0,
                rgb(0x8FB4FF),
                1.0 - r,
            );
        }
        "energy_nova" => {
            let col = c.user;
            let centre = c.at(0.0, 60.0);
            if u < 0.25 {
                let ch = span(u, 0.0, 0.25);
                c.glow(centre, 10.0 + ch * 16.0, col, 0.9);
                for k in 0..3 {
                    c.ellipse(
                        centre,
                        egui::vec2(28.0, 9.0),
                        u * 9.0 + k as f32 * 1.05,
                        1.5,
                        col,
                        ch,
                    );
                }
            } else {
                let b = span(u, 0.25, 0.9);
                let r = 14.0 + ease_out(b) * 100.0;
                c.p.circle_stroke(centre, r, Stroke::new(6.0, c.a(col, (1.0 - b) * 0.6)));
                c.p.circle_stroke(
                    centre,
                    r * 0.92,
                    Stroke::new(2.0, c.a(Color32::WHITE, 1.0 - b)),
                );
                c.ellipse(
                    centre,
                    egui::vec2(r * 1.1, r * 0.25),
                    0.0,
                    3.0,
                    col,
                    1.0 - b,
                );
                c.ellipse(
                    centre,
                    egui::vec2(r * 0.3, r),
                    0.4,
                    2.0,
                    col,
                    (1.0 - b) * 0.7,
                );
                c.glow(centre, 50.0 * (1.0 - b), Color32::WHITE, 1.0 - b);
            }
        }
        "black_hole" => {
            let centre = c.at(0.0, 80.0);
            let s = ease_out(span(u, 0.0, 0.2)) * (1.0 - span(u, 0.7, 0.8));
            if s > 0.01 {
                c.glow(centre, 70.0 * s, rgb(0x6A2CFF), 0.5);
                for k in 0..5 {
                    let col = rgb(0xFFF0C8).lerp_to_gamma(rgb(0x7A2CFF), k as f32 / 4.0);
                    c.ellipse(
                        centre,
                        egui::vec2((34.0 + k as f32 * 13.0) * s, (8.0 + k as f32 * 3.0) * s),
                        -0.2,
                        3.0,
                        col,
                        0.8 - k as f32 * 0.12,
                    );
                }
                for i in 0..n / 2 {
                    let ph = noise(i) * TAU + u * 18.0 * (1.0 + noise(i + 7));
                    let r = (30.0 + 60.0 * (1.0 - (u * 3.0 + noise(i + 3)).fract())) * s;
                    c.p.circle_filled(
                        Pos2::new(centre.x + ph.cos() * r, centre.y + ph.sin() * r * 0.25),
                        1.6,
                        c.a(rgb(0xFFE8C0), 0.9),
                    );
                }
                c.p.circle_filled(centre, 20.0 * s, Color32::BLACK);
                c.p.circle_stroke(centre, 22.0 * s, Stroke::new(2.5, rgb(0xFFD9A0)));
            }
            let f = span(u, 0.8, 1.0);
            if u > 0.78 {
                c.glow(centre, 80.0 * ease_out(f), rgb(0xE8D8FF), 1.0 - f);
            }
        }
        "dragon_burst" => {
            let hp = ease_out(span(u, 0.0, 0.45));
            let fb = 1.0 - span(u, 0.5, 0.65);
            for k in 0..3 {
                let mut pts = Vec::new();
                for j in 0..20 {
                    let q = (hp - j as f32 * 0.03).max(0.0);
                    let a = k as f32 * TAU / 3.0 + q * 9.4;
                    pts.push(c.at(
                        a.cos() * 40.0 * (1.0 - 0.45 * q),
                        6.0 + q * 150.0 + a.sin() * 8.0,
                    ));
                }
                let head = pts[0];
                c.line(pts.clone(), 7.0, rgb(0xC040E0), fb * 0.6);
                c.line(pts, 2.0, rgb(0xFFB0FF), fb);
                c.glow(head, 10.0, rgb(0xFF9CFF), fb);
            }
            let b = span(u, 0.45, 0.85);
            if u > 0.45 {
                let top = c.at(0.0, 156.0);
                c.p.circle_stroke(
                    top,
                    10.0 + ease_out(b) * 80.0,
                    Stroke::new(4.0, c.a(rgb(0xB044FF), 1.0 - b)),
                );
                c.glow(top, 40.0, rgb(0xFFB0FF), 1.0 - span(u, 0.45, 0.6));
            }
        }
        "crystal_burst" | "frost_spikes" => {
            let frost = effect == "frost_spikes";
            let shatter = 0.55;
            let palette: [u32; 4] = if frost {
                [0xCFF4FF, 0xE8FBFF, 0xA8E8FF, 0xFFFFFF]
            } else {
                [0xB57BFF, 0x7FE3FF, 0xFF8AD8, 0xC9A6FF]
            };
            if frost {
                c.ellipse(
                    c.at(0.0, 1.0),
                    egui::vec2(100.0 * ease_out(span(u, 0.0, 0.15)), 10.0),
                    0.0,
                    6.0,
                    rgb(0xE8F8FF),
                    0.5 * (1.0 - span(u, 0.7, 1.0)),
                );
            }
            for k in 0..9 {
                let col = rgb(palette[k % 4]);
                let ang = (k as f32 - 4.0) * 0.28;
                let base = c.at((k as f32 - 4.0) * if frost { 16.0 } else { 8.0 }, 0.0);
                let len = (if frost { 60.0 } else { 70.0 }) + noise(k as u32) * 50.0;
                if u < shatter {
                    let g = ease_out(span(u, k as f32 * 0.02, k as f32 * 0.02 + 0.15));
                    c.spike(base, ang, len * g, if frost { 5.0 } else { 8.0 }, col, 0.85);
                } else {
                    let dt = (u - shatter) * 2.2;
                    for f in 0..3 {
                        let sd = (k * 3 + f) as u32;
                        let x = base.x + ang.sin() * len * 0.5 + (noise(sd) - 0.5) * 140.0 * dt;
                        let y =
                            (base.y - len * 0.5 + (-80.0 + 260.0 * dt) * dt).min(c.ground - 2.0);
                        c.spike(
                            Pos2::new(x, y),
                            dt * 12.0 + sd as f32,
                            9.0,
                            4.0,
                            col,
                            1.0 - span(u, 0.8, 1.0),
                        );
                    }
                }
            }
            c.glow(
                c.at(0.0, 50.0),
                60.0,
                Color32::WHITE,
                span(u, shatter - 0.03, shatter) * (1.0 - span(u, shatter, shatter + 0.12)),
            );
        }
        "fire_tornado" => {
            let h = 170.0 * ease_out(span(u, 0.0, 0.2));
            let lift = span(u, 0.7, 1.0) * 60.0;
            let fade = 1.0 - span(u, 0.75, 1.0);
            for s in 0..6 {
                let mut pts = Vec::new();
                for k in 0..24 {
                    let q = k as f32 / 23.0;
                    let a = s as f32 * PI / 3.0 + u * 30.0 + q * 5.0;
                    pts.push(c.at(a.cos() * (8.0 + q * 55.0), q * h + lift + a.sin() * 4.0));
                }
                c.line(
                    pts,
                    3.0,
                    rgb(0xFF7A1A).lerp_to_gamma(rgb(0xFFF0A0), s as f32 / 6.0),
                    fade,
                );
            }
            c.glow(c.at(0.0, 10.0), 50.0, rgb(0xFF8A2A), fade * 0.8);
        }
        "portal_rift" => {
            let open = ease_out(span(u, 0.0, 0.18)) * (1.0 - span(u, 0.8, 1.0));
            let centre = c.at(0.0, 70.0);
            if open > 0.01 {
                c.p.add(Shape::ellipse_filled(
                    centre,
                    egui::vec2(36.0 * (0.4 + 0.6 * open), 62.0 * open),
                    c.a(rgb(0x12021F), 0.9),
                ));
                for k in 0..4 {
                    let col = if k % 2 == 0 {
                        rgb(0xB060FF)
                    } else {
                        rgb(0x5FE0FF)
                    };
                    let sc = 1.0 - k as f32 * 0.14;
                    c.ellipse(
                        centre,
                        egui::vec2(40.0 * sc * (0.4 + 0.6 * open), 66.0 * sc * open),
                        0.0,
                        2.5,
                        col,
                        0.9,
                    );
                }
                for arm in 0..5 {
                    let pts: Vec<Pos2> = (0..20)
                        .map(|i| {
                            let q = i as f32 / 19.0;
                            let a = arm as f32 * TAU / 5.0 + u * 14.0 + q * 7.0;
                            Pos2::new(
                                centre.x + a.cos() * 34.0 * (1.0 - q) * open,
                                centre.y + a.sin() * 58.0 * (1.0 - q) * open,
                            )
                        })
                        .collect();
                    c.line(pts, 2.0, rgb(0xE6C8FF), 0.8);
                }
            }
        }
        "meteor" => {
            let impact = 0.3;
            if u < impact {
                let f = (u / impact).powf(1.7);
                let head = Pos2::new(
                    c.rect.min.x + 20.0 + (c.cx - c.rect.min.x - 20.0) * f,
                    c.rect.min.y + 10.0 + (c.ground - c.rect.min.y - 20.0) * f,
                );
                let tail = Pos2::new(head.x - 50.0, head.y - 60.0);
                c.line(vec![tail, head], 14.0, rgb(0xFF7A20), 0.4);
                c.line(
                    vec![Pos2::new(head.x - 25.0, head.y - 30.0), head],
                    5.0,
                    rgb(0xFFF4C0),
                    0.9,
                );
                c.glow(head, 18.0, rgb(0xFF9A30), 1.0);
                c.p.circle_filled(head, 7.0, rgb(0x3A1608));
            } else {
                let it = span(u, impact, 1.0);
                c.glow(
                    c.at(0.0, 10.0),
                    90.0 * ease_out(span(it, 0.0, 0.1)),
                    rgb(0xFFE0A0),
                    1.0 - span(it, 0.0, 0.3),
                );
                c.ellipse(
                    c.at(0.0, 2.0),
                    egui::vec2(20.0 + ease_out(it) * 120.0, 4.0 + ease_out(it) * 16.0),
                    0.0,
                    4.0,
                    rgb(0xFFB050),
                    1.0 - it,
                );
                for k in 0..12 {
                    let a = noise(k) * PI;
                    let sp = 60.0 + noise(k + 40) * 80.0;
                    let x = c.cx + a.cos() * sp * it;
                    let y =
                        (c.ground - a.sin() * sp * 2.0 * it + 300.0 * it * it).min(c.ground - 2.0);
                    let col = rgb(0xFF7020).lerp_to_gamma(rgb(0x4A2A1A), span(it, 0.0, 0.3));
                    c.p.rect_filled(
                        egui::Rect::from_center_size(Pos2::new(x, y), egui::vec2(5.0, 5.0)),
                        0.0,
                        c.a(col, 1.0 - span(it, 0.7, 1.0)),
                    );
                }
            }
        }
        "spirit_ascend" => {
            let oy = 60.0 + span(u, 0.05, 0.65).powf(1.5) * 140.0;
            let f = 1.0 - span(u, 0.75, 1.0);
            for w in 0..5 {
                let pts: Vec<Pos2> = (0..14)
                    .map(|j| {
                        let tt = (u - j as f32 * 0.02).max(0.0);
                        let y = 60.0 + span(tt, 0.05, 0.65).powf(1.5) * 140.0;
                        let r = 30.0 * (1.0 - span(tt, 0.05, 0.65)) + 4.0;
                        let a = w as f32 * TAU / 5.0 + tt * 20.0;
                        c.at(a.cos() * r, y + a.sin() * 4.0)
                    })
                    .collect();
                c.line(pts, 3.0, rgb(0xBFEFFF), f * 0.85);
            }
            c.glow(c.at(0.0, oy), 16.0, rgb(0xE6FBFF), f);
            if u > 0.6 {
                let h = span(u, 0.6, 1.0);
                c.ellipse(
                    c.at(0.0, oy + 14.0),
                    egui::vec2(10.0 + ease_out(h) * 40.0, 3.0 + ease_out(h) * 10.0),
                    0.0,
                    2.5,
                    rgb(0xFFF6C8),
                    1.0 - h,
                );
            }
        }
        "plasma_sphere" => {
            let centre = c.at(0.0, 70.0);
            let r = 46.0 * ease_out(span(u, 0.0, 0.15)) * (1.0 - span(u, 0.82, 0.95));
            if r > 1.0 {
                c.glow(centre, r * 1.6, rgb(0x3F7FFF), 0.5);
                c.p.circle_stroke(centre, r, Stroke::new(4.0, c.a(rgb(0x3FA8FF), 0.9)));
                let frame = (u * 30.0) as u32;
                for k in 0..7 {
                    let pts: Vec<Pos2> = (0..8)
                        .map(|j| {
                            let a = noise(frame * 13 + k) * TAU + j as f32 * 0.14;
                            let rr = r * (0.85 + noise(frame * 7 + k * 9 + j) * 0.15);
                            Pos2::new(centre.x + a.cos() * rr, centre.y + a.sin() * rr)
                        })
                        .collect();
                    c.line(pts, 1.5, Color32::WHITE, 0.95);
                }
                let gx = (noise(frame) - 0.5) * 120.0;
                c.line(
                    vec![
                        Pos2::new(centre.x, centre.y + r),
                        c.at(gx * 0.5 + 6.0, 30.0),
                        c.at(gx, 0.0),
                    ],
                    2.0,
                    rgb(0xDDEEFF),
                    0.8,
                );
            }
            c.glow(
                centre,
                60.0,
                Color32::WHITE,
                span(u, 0.88, 0.93) * (1.0 - span(u, 0.93, 1.0)),
            );
        }
        "dark_vortex" => {
            let suck = span(u, 0.55, 0.72);
            let form = ease_out(span(u, 0.0, 0.18));
            for s in 0..5 {
                let pts: Vec<Pos2> = (0..20)
                    .map(|k| {
                        let q = k as f32 / 19.0;
                        let a = s as f32 * TAU / 5.0 + u * 14.0 + q * 5.0;
                        let r = (8.0 + 80.0 * (1.0 - q) * (1.0 - q)) * form * (1.0 - suck);
                        c.at(
                            a.cos() * r,
                            50.0 + ((180.0 - q * 170.0) - 50.0) * (1.0 - suck) * form,
                        )
                    })
                    .collect();
                c.line(pts.clone(), 9.0, rgb(0x1A0526), 0.85);
                c.line(pts, 2.0, rgb(0xA040FF), 0.8);
            }
            if u > 0.72 {
                let s = span(u, 0.72, 1.0);
                c.ellipse(
                    c.at(0.0, 50.0),
                    egui::vec2(10.0 + ease_out(s) * 120.0, 10.0 + ease_out(s) * 120.0),
                    0.0,
                    10.0,
                    Color32::BLACK,
                    0.8 * (1.0 - s),
                );
                c.ellipse(
                    c.at(0.0, 50.0),
                    egui::vec2(16.0 + ease_out(s) * 120.0, 16.0 + ease_out(s) * 120.0),
                    0.0,
                    2.5,
                    rgb(0xC060FF),
                    1.0 - s,
                );
            }
        }
        "prism_burst" => {
            let centre = c.at(0.0, 60.0);
            let grow = ease_out(span(u, 0.0, 0.18));
            let travel = ease_out(span(u, 0.25, 1.0)) * 60.0;
            let fade = 1.0 - span(u, 0.4, 1.0);
            c.glow(centre, 40.0, Color32::WHITE, 1.0 - span(u, 0.0, 0.3));
            for k in 0..20 {
                let a = k as f32 / 20.0 * TAU;
                let base = Pos2::new(centre.x + a.sin() * travel, centre.y - a.cos() * travel);
                c.spike(
                    base,
                    a,
                    (55.0 + noise(k) * 30.0) * grow,
                    3.5,
                    hsv(k as f32 / 20.0, 0.85, 1.0),
                    fade,
                );
            }
            for k in 0..3 {
                let s = span(u, k as f32 * 0.06, k as f32 * 0.06 + 0.5);
                c.ellipse(
                    c.at(0.0, 20.0 + k as f32 * 16.0),
                    egui::vec2(
                        14.0 + ease_out(s) * (90.0 + k as f32 * 20.0),
                        4.0 + ease_out(s) * 14.0,
                    ),
                    0.0,
                    3.0,
                    hsv(k as f32 / 3.0 + u, 0.8, 1.0),
                    1.0 - s,
                );
            }
        }
        "star_rain" => {
            for k in 0..7u32 {
                let st = k as f32 * 0.08;
                let lt = u - st;
                if lt < 0.0 {
                    continue;
                }
                let x = c.cx + (noise(k) - 0.5) * 160.0;
                let f = span(lt, 0.0, 0.25);
                let y = c.rect.min.y + 10.0 + (c.ground - c.rect.min.y - 16.0) * f * f;
                let land = span(lt, 0.25, 0.45);
                let col = [rgb(0xFFD54A), rgb(0xFFF6D8), rgb(0xFFE89A)][(k % 3) as usize];
                if lt < 0.25 {
                    c.line(
                        vec![Pos2::new(x, y - 40.0), Pos2::new(x, y)],
                        5.0,
                        rgb(0xFF9A30),
                        0.5,
                    );
                }
                let s = 9.0 * (1.0 - land);
                if s > 0.5 {
                    let rot = lt * 20.0;
                    let pts: Vec<Pos2> = (0..10)
                        .map(|i| {
                            let a = rot + i as f32 * PI / 5.0;
                            let r = if i % 2 == 0 { s } else { s * 0.45 };
                            Pos2::new(x + a.sin() * r, y - a.cos() * r)
                        })
                        .collect();
                    for i in 0..10 {
                        c.poly(vec![Pos2::new(x, y), pts[i], pts[(i + 1) % 10]], col, 1.0);
                    }
                }
                if land > 0.0 {
                    c.ellipse(
                        Pos2::new(x, c.ground - 2.0),
                        egui::vec2(4.0 + ease_out(land) * 26.0, 2.0 + ease_out(land) * 6.0),
                        0.0,
                        2.0,
                        col,
                        1.0 - land,
                    );
                }
            }
        }
        "void_burst" => {
            let centre = c.at(0.0, 70.0);
            let boom = 0.42;
            for k in 0..30u32 {
                let a = noise(k) * TAU;
                let d = if u < boom {
                    (90.0 + noise(k + 50) * 30.0) * (1.0 - span(u, 0.0, 0.33).powi(3)) + 4.0
                } else {
                    8.0 + (u - boom) * (180.0 + noise(k + 9) * 140.0)
                };
                let pos = Pos2::new(centre.x + a.cos() * d, centre.y + a.sin() * d);
                let rect = egui::Rect::from_center_size(pos, egui::vec2(7.0, 7.0));
                let fade = 1.0 - span(u, 0.7, 1.0);
                c.p.rect_filled(rect.expand(2.0), 1.0, c.a(rgb(0x9A40FF), fade * 0.4));
                c.p.rect_filled(rect, 1.0, c.a(rgb(0x1A0830), fade));
            }
            if u > 0.25 && u < boom + 0.02 {
                c.p.circle_filled(centre, 16.0, Color32::BLACK);
                c.p.circle_stroke(centre, 18.0, Stroke::new(2.0, rgb(0xC070FF)));
            }
            if u >= boom {
                let s = span(u, boom, 1.0);
                c.p.circle_stroke(
                    centre,
                    12.0 + ease_out(s) * 110.0,
                    Stroke::new(10.0, c.a(Color32::BLACK, 0.8 * (1.0 - s))),
                );
                c.p.circle_stroke(
                    centre,
                    20.0 + ease_out(s) * 110.0,
                    Stroke::new(2.5, c.a(rgb(0xB060FF), 1.0 - s)),
                );
            }
        }
        "magic_circle" => {
            let drawn = ease_out(span(u, 0.0, 0.2));
            let env = 1.0 - span(u, 0.82, 1.0);
            let centre = c.at(0.0, 2.0);
            let light = c.user.lerp_to_gamma(Color32::WHITE, 0.5);
            let rot = u * 2.0;
            let arc: Vec<Pos2> = (0..=(60.0 * drawn) as usize)
                .map(|i| {
                    let a = rot + i as f32 / 60.0 * TAU;
                    Pos2::new(centre.x + a.cos() * 110.0, centre.y + a.sin() * 24.0)
                })
                .collect();
            c.line(arc, 3.0, light, env);
            c.ellipse(
                centre,
                egui::vec2(96.0, 21.0),
                0.0,
                1.5,
                c.user,
                env * drawn,
            );
            for tri in 0..2 {
                let pts: Vec<Pos2> = (0..4)
                    .map(|i| {
                        let a = rot + tri as f32 * PI + i as f32 * TAU / 3.0;
                        Pos2::new(centre.x + a.cos() * 90.0, centre.y + a.sin() * 19.0)
                    })
                    .collect();
                c.line(pts, 1.5, light, env * span(u, 0.08, 0.25));
            }
            if u > 0.45 {
                let p = ease_out(span(u, 0.45, 0.58));
                let top = c.ground - 200.0 * p;
                c.p.rect_filled(
                    egui::Rect::from_min_max(
                        Pos2::new(c.cx - 40.0, top),
                        Pos2::new(c.cx + 40.0, c.ground),
                    ),
                    6.0,
                    c.a(light, 0.3 * env),
                );
                c.p.rect_filled(
                    egui::Rect::from_min_max(
                        Pos2::new(c.cx - 12.0, top),
                        Pos2::new(c.cx + 12.0, c.ground),
                    ),
                    4.0,
                    c.a(Color32::WHITE, 0.5 * env),
                );
                c.ellipse(
                    c.at(0.0, 110.0),
                    egui::vec2(55.0, 12.0),
                    0.0,
                    2.0,
                    light,
                    env * span(u, 0.5, 0.6),
                );
            }
        }
        "flame_wings" => {
            let spread = ease_out(span(u, 0.0, 0.22));
            let fs = span(u, 0.25, 0.5);
            let flap = if fs > 0.0 && fs < 1.0 {
                (fs * TAU).sin() * 0.25
            } else {
                0.0
            };
            let fade = 1.0 - span(u, 0.65, 1.0);
            let lift = span(u, 0.65, 1.0) * 40.0;
            let shoulder = c.at(0.0, 50.0 + lift);
            for side in [-1.0f32, 1.0] {
                for f in 0..8 {
                    let aim = (15.0 + f as f32 * 14.0).to_radians();
                    let a = -1.2 + (aim + 1.2) * spread + flap;
                    let len = (60.0 + 35.0 * (PI * (0.25 + 0.6 * f as f32 / 7.0)).sin())
                        * (0.3 + 0.7 * spread);
                    let tip = Pos2::new(
                        shoulder.x + side * a.cos() * len,
                        shoulder.y - a.sin() * len,
                    );
                    c.line(vec![shoulder, tip], 7.0, rgb(0xFF5A1A), fade * 0.8);
                    c.line(vec![shoulder, tip], 2.0, rgb(0xFFE07A), fade);
                }
            }
            c.glow(shoulder, 30.0, rgb(0xFF8A2A), fade);
        }
        "shattered_dimension" => {
            let centre = c.at(0.0, 70.0);
            if u < 0.25 {
                let g = ease_out(span(u, 0.0, 0.12));
                let fade = 1.0 - span(u, 0.13, 0.25);
                for k in 0..8u32 {
                    let mut p = centre;
                    let mut pts = vec![p];
                    for j in 0..5 {
                        let a = k as f32 * TAU / 8.0 + (noise(k * 9 + j) - 0.5);
                        p = Pos2::new(p.x + a.cos() * 14.0 * g, p.y + a.sin() * 14.0 * g);
                        pts.push(p);
                    }
                    c.line(pts, 2.0, Color32::WHITE, fade);
                }
            }
            if u > 0.11 {
                let appear = ease_out(span(u, 0.11, 0.2));
                let fly = span(u, 0.48, 1.0).powi(3) * 120.0;
                let fade = 1.0 - span(u, 0.7, 1.0);
                for k in 0..18u32 {
                    let a = noise(k) * TAU;
                    let d =
                        (40.0 + noise(k + 3) * 30.0) * appear + fly * (0.8 + noise(k + 5) * 0.6);
                    let pc = Pos2::new(centre.x + a.cos() * d, centre.y + a.sin() * d * 0.8);
                    let spin = u * (noise(k + 8) - 0.5) * 20.0;
                    let pts: Vec<Pos2> = (0..5)
                        .map(|i| {
                            let b = spin + (i as f32 + 0.3 + noise(k * 5 + i) * 0.4) * TAU / 5.0;
                            let r = 9.0 + noise(k * 7 + i) * 6.0;
                            Pos2::new(pc.x + b.cos() * r, pc.y + b.sin() * r)
                        })
                        .collect();
                    let hue = hsv(noise(k + 11) + u * 0.3, 0.55, 1.0);
                    c.poly(pts.clone(), Color32::from_rgb(10, 6, 20), 0.8 * fade);
                    let mut edge = pts;
                    edge.push(edge[0]);
                    c.line(edge, 1.5, hue, fade);
                }
            }
        }
        "neon_pulse" => {
            let second = Color32::from_rgb(c.user.b(), c.user.r(), c.user.g());
            let env = 1.0 - span(u, 0.72, 1.0);
            for k in 0..5 {
                let t0 = k as f32 * 0.12;
                if u < t0 {
                    break;
                }
                let r = 10.0 + (u - t0) * 260.0;
                let col = if k % 2 == 0 { c.user } else { second };
                let a = 1.0 - span(u, t0, t0 + 0.5);
                let pts: Vec<Pos2> = (0..=6)
                    .map(|i| {
                        let ang = PI / 6.0 + i as f32 * TAU / 6.0;
                        c.at(ang.cos() * r, -ang.sin() * r * 0.22)
                    })
                    .collect();
                c.line(pts, 3.0, col, a);
            }
            for k in 0..10 {
                let x = (k as f32 - 4.5) * 18.0;
                let h = (0.4 + 0.6 * (u * 14.0 + k as f32 * 0.9).sin().abs())
                    * 90.0
                    * span(u, k as f32 * 0.01, k as f32 * 0.01 + 0.1);
                let col = if k % 2 == 0 { c.user } else { second };
                c.line(vec![c.at(x, 0.0), c.at(x, h)], 6.0, col, env * 0.35);
                c.line(
                    vec![c.at(x, 0.0), c.at(x, h)],
                    2.5,
                    col.lerp_to_gamma(Color32::WHITE, 0.4),
                    env,
                );
            }
        }
        "kestrel_talon" => {
            let s = span(u, 0.0, 0.24);
            if u < 0.26 {
                let x = c.rect.min.x + 10.0 + (c.rect.width() - 20.0) * s;
                let y = c.ground
                    - 60.0
                    - (1.0 - (2.0 * s - 1.0).powi(2)) * -40.0
                    - 100.0 * (2.0 * s - 1.0).powi(2);
                let rust = rgb(0xE0662A);
                let beat = 1.0 + 0.2 * (u * 60.0).sin();
                c.poly(
                    vec![
                        Pos2::new(x + 16.0, y),
                        Pos2::new(x - 4.0, y - 30.0 * beat),
                        Pos2::new(x - 8.0, y),
                    ],
                    rust,
                    0.95,
                );
                c.poly(
                    vec![
                        Pos2::new(x + 16.0, y),
                        Pos2::new(x - 8.0, y),
                        Pos2::new(x - 4.0, y + 12.0 * beat),
                    ],
                    rgb(0xFFE8C8),
                    0.95,
                );
                c.glow(Pos2::new(x, y), 18.0, rust, 0.6);
            }
            for k in 0..3 {
                let t0 = 0.12 + k as f32 * 0.035;
                if u < t0 {
                    break;
                }
                let p = ease_out(span(u, t0, t0 + 0.06));
                let a = 1.0 - span(u, t0 + 0.08, t0 + 0.4);
                let pts: Vec<Pos2> = (0..=16)
                    .map(|i| {
                        let q = i as f32 / 16.0 * p;
                        Pos2::new(
                            c.cx - 40.0 + (k as f32 - 1.0) * 14.0 + q * 80.0,
                            c.ground - 100.0 + q * 70.0 + (q * PI).sin() * -14.0,
                        )
                    })
                    .collect();
                c.line(pts.clone(), 7.0, rgb(0xE0662A), a * 0.6);
                c.line(pts, 2.5, rgb(0xFFF2D8), a);
            }
            if u > 0.2 {
                let ft = u - 0.2;
                for k in 0..12u32 {
                    let col = [rgb(0xFFE8C8), rgb(0xE0662A), rgb(0x6E8CB0)][(k % 3) as usize];
                    let x = c.cx
                        + (noise(k) - 0.5) * 90.0
                        + (ft * 8.0 + noise(k + 1) * 6.0).sin() * 10.0;
                    let y =
                        (c.ground - 80.0 - noise(k + 2) * 30.0 + ft * 140.0).min(c.ground - 3.0);
                    let a = (ft * 6.0 + noise(k + 3) * 6.0).sin() * 0.8;
                    let (sa, ca) = a.sin_cos();
                    c.line(
                        vec![
                            Pos2::new(x - ca * 7.0, y - sa * 7.0),
                            Pos2::new(x + ca * 7.0, y + sa * 7.0),
                        ],
                        3.0,
                        col,
                        1.0 - span(u, 0.75, 1.0),
                    );
                }
            }
        }
        _ => return false,
    }
    true
}
