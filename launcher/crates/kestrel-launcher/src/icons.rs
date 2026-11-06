//! The launcher's icons: simple line icons drawn as vector shapes, so
//! they stay sharp at every Windows display scale and need no icon font or
//! image files. Each icon is designed on a 24x24 grid with round joins and
//! drawn into any square rect.

use std::f32::consts::{PI, TAU};

use egui::{Color32, Painter, Pos2, Rect, Shape, Stroke, Vec2};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Icon {
    Home,
    Cosmetics,
    Settings,
    Account,
    About,
    Logs,
    Play,
    Folder,
    Repair,
    Download,
    Check,
    Alert,
    Close,
    Cube,
    Clock,
    /// Stacked layers (instances).
    Layers,
    /// Two opposing arrows (switch account).
    Switch,
    Plus,
    Copy,
    Trash,
    Edit,
    Stop,
    Star,
}

/// Maps the 24x24 design grid into `rect`.
struct Grid {
    origin: Pos2,
    unit: f32,
}

impl Grid {
    fn new(rect: Rect) -> Grid {
        let side = rect.width().min(rect.height());
        let origin = rect.center() - Vec2::splat(side / 2.0);
        Grid {
            origin,
            unit: side / 24.0,
        }
    }

    fn p(&self, x: f32, y: f32) -> Pos2 {
        self.origin + Vec2::new(x, y) * self.unit
    }

    fn r(&self, v: f32) -> f32 {
        v * self.unit
    }
}

/// Draw `icon` into `rect` (a square works best) with line colour `color`.
pub fn paint(painter: &Painter, rect: Rect, icon: Icon, color: Color32) {
    let g = Grid::new(rect);
    // 1.8 grid units at 24 px = the usual 1.8 px icon stroke; never thinner than 1.2 px.
    let s = Stroke::new(g.r(1.8).max(1.2), color);
    let line = |pts: &[(f32, f32)]| {
        painter.add(Shape::line(
            pts.iter().map(|&(x, y)| g.p(x, y)).collect(),
            s,
        ));
    };
    let closed = |pts: &[(f32, f32)]| {
        painter.add(Shape::closed_line(
            pts.iter().map(|&(x, y)| g.p(x, y)).collect(),
            s,
        ));
    };
    let arc = |cx: f32, cy: f32, r: f32, a0: f32, a1: f32| -> Vec<Pos2> {
        let n = 24;
        (0..=n)
            .map(|i| {
                let a = a0 + (a1 - a0) * i as f32 / n as f32;
                g.p(cx + r * a.cos(), cy + r * a.sin())
            })
            .collect()
    };
    match icon {
        Icon::Home => {
            closed(&[
                (3.5, 11.0),
                (12.0, 3.8),
                (20.5, 11.0),
                (20.5, 20.0),
                (14.5, 20.0),
                (14.5, 14.0),
                (9.5, 14.0),
                (9.5, 20.0),
                (3.5, 20.0),
            ]);
        }
        Icon::Cosmetics => {
            // A four-pointed sparkle plus a small one.
            let star = |cx: f32, cy: f32, r: f32| {
                let k = r * 0.28;
                vec![
                    (cx, cy - r),
                    (cx + k, cy - k),
                    (cx + r, cy),
                    (cx + k, cy + k),
                    (cx, cy + r),
                    (cx - k, cy + k),
                    (cx - r, cy),
                    (cx - k, cy - k),
                ]
            };
            closed(&star(10.5, 13.0, 7.5));
            closed(&star(18.5, 5.5, 3.0));
        }
        Icon::Settings => {
            // Gear: 8 teeth around a ring, with a hole.
            let teeth = 8;
            let mut pts = Vec::with_capacity(teeth * 4);
            for i in 0..teeth {
                let a = i as f32 * TAU / teeth as f32;
                let half = TAU / teeth as f32 / 2.0;
                for (da, r) in [
                    (-half * 0.92, 7.0),
                    (-half * 0.42, 9.6),
                    (half * 0.42, 9.6),
                    (half * 0.92, 7.0),
                ] {
                    pts.push((12.0 + r * (a + da).cos(), 12.0 + r * (a + da).sin()));
                }
            }
            closed(&pts);
            painter.circle_stroke(g.p(12.0, 12.0), g.r(3.0), s);
        }
        Icon::Account => {
            painter.circle_stroke(g.p(12.0, 8.0), g.r(4.0), s);
            painter.add(Shape::line(arc(12.0, 21.0, 7.5, PI, TAU), s));
        }
        Icon::About => {
            painter.circle_stroke(g.p(12.0, 12.0), g.r(9.0), s);
            line(&[(12.0, 11.0), (12.0, 16.5)]);
            painter.circle_filled(g.p(12.0, 7.6), g.r(1.25), color);
        }
        Icon::Logs => {
            closed(&[(3.0, 4.5), (21.0, 4.5), (21.0, 19.5), (3.0, 19.5)]);
            line(&[(7.0, 9.5), (10.0, 12.0), (7.0, 14.5)]);
            line(&[(12.0, 15.0), (17.0, 15.0)]);
        }
        Icon::Play => {
            painter.add(Shape::convex_polygon(
                vec![g.p(7.5, 4.5), g.p(19.5, 12.0), g.p(7.5, 19.5)],
                color,
                Stroke::NONE,
            ));
        }
        Icon::Folder => {
            closed(&[
                (3.0, 6.0),
                (9.5, 6.0),
                (11.5, 8.5),
                (21.0, 8.5),
                (21.0, 19.0),
                (3.0, 19.0),
            ]);
        }
        Icon::Repair => {
            // Circular arrows (verify / repair).
            painter.add(Shape::line(arc(12.0, 12.0, 7.5, -PI * 0.9, PI * 0.35), s));
            painter.add(Shape::line(arc(12.0, 12.0, 7.5, PI * 0.1, PI * 1.35), s));
            line(&[(19.5, 9.0), (18.6, 14.1), (14.6, 11.5)]);
            line(&[(4.5, 15.0), (5.4, 9.9), (9.4, 12.5)]);
        }
        Icon::Download => {
            line(&[(12.0, 3.5), (12.0, 15.0)]);
            line(&[(7.0, 10.5), (12.0, 15.5), (17.0, 10.5)]);
            line(&[(4.0, 16.5), (4.0, 20.0), (20.0, 20.0), (20.0, 16.5)]);
        }
        Icon::Check => {
            line(&[(5.0, 12.5), (10.0, 17.5), (19.5, 6.5)]);
        }
        Icon::Alert => {
            closed(&[(12.0, 3.5), (21.5, 20.0), (2.5, 20.0)]);
            line(&[(12.0, 9.5), (12.0, 14.0)]);
            painter.circle_filled(g.p(12.0, 17.0), g.r(1.15), color);
        }
        Icon::Close => {
            line(&[(6.0, 6.0), (18.0, 18.0)]);
            line(&[(18.0, 6.0), (6.0, 18.0)]);
        }
        Icon::Cube => {
            closed(&[
                (12.0, 2.8),
                (20.5, 7.4),
                (20.5, 16.6),
                (12.0, 21.2),
                (3.5, 16.6),
                (3.5, 7.4),
            ]);
            line(&[(3.5, 7.4), (12.0, 12.0), (20.5, 7.4)]);
            line(&[(12.0, 12.0), (12.0, 21.2)]);
        }
        Icon::Clock => {
            painter.circle_stroke(g.p(12.0, 12.0), g.r(9.0), s);
            line(&[(12.0, 7.0), (12.0, 12.0), (15.5, 14.0)]);
        }
        Icon::Layers => {
            closed(&[(12.0, 3.5), (21.0, 8.0), (12.0, 12.5), (3.0, 8.0)]);
            line(&[(3.0, 12.0), (12.0, 16.5), (21.0, 12.0)]);
            line(&[(3.0, 16.0), (12.0, 20.5), (21.0, 16.0)]);
        }
        Icon::Switch => {
            line(&[(4.0, 8.0), (19.0, 8.0)]);
            line(&[(15.5, 4.5), (19.0, 8.0), (15.5, 11.5)]);
            line(&[(20.0, 16.0), (5.0, 16.0)]);
            line(&[(8.5, 12.5), (5.0, 16.0), (8.5, 19.5)]);
        }
        Icon::Plus => {
            line(&[(12.0, 5.0), (12.0, 19.0)]);
            line(&[(5.0, 12.0), (19.0, 12.0)]);
        }
        Icon::Copy => {
            closed(&[(8.5, 8.5), (20.0, 8.5), (20.0, 20.0), (8.5, 20.0)]);
            line(&[
                (15.5, 8.5),
                (15.5, 4.0),
                (4.0, 4.0),
                (4.0, 15.5),
                (8.5, 15.5),
            ]);
        }
        Icon::Trash => {
            line(&[(4.0, 6.5), (20.0, 6.5)]);
            line(&[(9.5, 6.5), (9.5, 3.8), (14.5, 3.8), (14.5, 6.5)]);
            closed(&[(6.0, 6.5), (18.0, 6.5), (17.0, 20.5), (7.0, 20.5)]);
            line(&[(10.0, 10.5), (10.0, 16.5)]);
            line(&[(14.0, 10.5), (14.0, 16.5)]);
        }
        Icon::Edit => {
            closed(&[
                (15.5, 4.0),
                (20.0, 8.5),
                (9.0, 19.5),
                (4.0, 20.0),
                (4.5, 15.0),
            ]);
            line(&[(13.0, 6.5), (17.5, 11.0)]);
        }
        Icon::Stop => {
            painter.rect_filled(
                Rect::from_min_max(g.p(6.5, 6.5), g.p(17.5, 17.5)),
                g.r(2.0),
                color,
            );
        }
        Icon::Star => {
            let pts: Vec<(f32, f32)> = (0..10)
                .map(|i| {
                    let a = -PI / 2.0 + i as f32 * PI / 5.0;
                    let r = if i % 2 == 0 { 9.0 } else { 4.0 };
                    (12.0 + r * a.cos(), 12.6 + r * a.sin())
                })
                .collect();
            closed(&pts);
        }
    }
}

/// Allocate a square of `size` points in `ui` and draw `icon` in it.
pub fn show(ui: &mut egui::Ui, icon: Icon, size: f32, color: Color32) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), egui::Sense::hover());
    if ui.is_rect_visible(rect) {
        paint(ui.painter(), rect, icon, color);
    }
    resp
}

#[cfg(test)]
mod tests {
    use super::*;
    use egui::pos2;

    #[test]
    fn grid_maps_the_24_unit_square_into_the_rect() {
        let g = Grid::new(Rect::from_min_size(pos2(10.0, 20.0), Vec2::new(48.0, 48.0)));
        assert_eq!(g.p(0.0, 0.0), pos2(10.0, 20.0));
        assert_eq!(g.p(24.0, 24.0), pos2(58.0, 68.0));
        // Non-square rects use the largest centred square.
        let g = Grid::new(Rect::from_min_size(pos2(0.0, 0.0), Vec2::new(48.0, 24.0)));
        assert_eq!(g.p(0.0, 0.0), pos2(12.0, 0.0));
        assert_eq!(g.r(24.0), 24.0);
    }
}
