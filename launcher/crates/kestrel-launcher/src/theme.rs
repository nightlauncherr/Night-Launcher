//! Kestrel's launcher design system: palette, type, spacing and the shared
//! components every page is built from.
//!
//! The palette is the in-game Kestrel main menu's: graphite backgrounds, a
//! warm Kestrel orange accent and cool grey text. Type is Inter (bundled,
//! SIL OFL) in three weights. Components are painted by hand so hover and
//! press states animate smoothly and look the same on every renderer.

use std::sync::Arc;

use egui::{
    Color32, CornerRadius, CursorIcon, FontData, FontDefinitions, FontFamily, FontId, Rect,
    Response, RichText, Sense, Shadow, Stroke, StrokeKind, TextStyle, Ui, Vec2, pos2,
};

use crate::embedded;
use crate::icons::{self, Icon};

// ---- palette ------------------------------------------------------------------

/// Backdrop gradient: lighter graphite at the top left to near black (the in-game menu's).
pub const BG_TOP: Color32 = Color32::from_rgb(0x26, 0x29, 0x2F);
pub const BG_BOTTOM: Color32 = Color32::from_rgb(0x0E, 0x0F, 0x12);
/// Flat background (panels, fallbacks).
pub const BG: Color32 = Color32::from_rgb(0x15, 0x16, 0x1A);
pub const SIDEBAR: Color32 = Color32::from_rgba_premultiplied(0x0D, 0x0E, 0x11, 0xE6);
/// Cards and raised surfaces.
pub const SURFACE: Color32 = Color32::from_rgb(0x1B, 0x1D, 0x22);
/// Hovered surfaces, inputs, the inactive part of controls.
pub const SURFACE_HI: Color32 = Color32::from_rgb(0x25, 0x28, 0x2E);
pub const BORDER: Color32 = Color32::from_rgb(0x2C, 0x2F, 0x36);
pub const TEXT: Color32 = Color32::from_rgb(0xEC, 0xEE, 0xF1);
pub const MUTED: Color32 = Color32::from_rgb(0x9A, 0xA0, 0xA8);
pub const FAINT: Color32 = Color32::from_rgb(0x6A, 0x70, 0x78);
pub const ACCENT: Color32 = Color32::from_rgb(0xE8, 0x83, 0x3A);
pub const ACCENT_HI: Color32 = Color32::from_rgb(0xF4, 0x9C, 0x58);
pub const ACCENT_LO: Color32 = Color32::from_rgb(0xC9, 0x6A, 0x26);
/// Text on accent fills.
pub const ON_ACCENT: Color32 = Color32::from_rgb(0x17, 0x11, 0x0C);
pub const OK: Color32 = Color32::from_rgb(0x5B, 0xC2, 0x7A);
pub const WARN: Color32 = Color32::from_rgb(0xE3, 0xB3, 0x41);
pub const ERR: Color32 = Color32::from_rgb(0xE0, 0x5A, 0x5A);

// ---- spacing --------------------------------------------------------------------

/// Gap between cards and between groups inside a card.
pub const GAP: f32 = 12.0;
/// Content column width cap, so wide windows keep a readable line length.
pub const CONTENT_MAX_W: f32 = 940.0;
pub const RADIUS: u8 = 12;
pub const RADIUS_SM: u8 = 8;
/// Standard control height.
pub const CONTROL_H: f32 = 34.0;
/// Duration of hover/press transitions (seconds).
pub const HOVER_T: f32 = 0.12;

// ---- type ------------------------------------------------------------------------

const SEMIBOLD: &str = "inter-semibold";
const BOLD: &str = "inter-bold";

pub fn regular(size: f32) -> FontId {
    FontId::proportional(size)
}

pub fn semibold(size: f32) -> FontId {
    FontId::new(size, FontFamily::Name(SEMIBOLD.into()))
}

/// Semibold Inter, for `RichText::family` (keeps the text's size).
pub fn semibold_family() -> FontFamily {
    FontFamily::Name(SEMIBOLD.into())
}

pub fn bold(size: f32) -> FontId {
    FontId::new(size, FontFamily::Name(BOLD.into()))
}

/// Semibold rich text (labels, card titles).
pub fn strong(text: impl Into<String>) -> RichText {
    RichText::new(text.into()).font(semibold(14.0)).color(TEXT)
}

/// Muted secondary text.
pub fn muted(text: impl Into<String>) -> RichText {
    RichText::new(text.into()).color(MUTED)
}

/// Install fonts, text styles and widget visuals. Call once at start-up.
pub fn install(ctx: &egui::Context) {
    let mut fonts = FontDefinitions::default();
    for (name, bytes) in [
        ("inter", embedded::FONT_REGULAR),
        (SEMIBOLD, embedded::FONT_SEMIBOLD),
        (BOLD, embedded::FONT_BOLD),
    ] {
        fonts
            .font_data
            .insert(name.into(), Arc::new(FontData::from_static(bytes)));
    }
    // Inter first; egui's bundled fonts stay as fallbacks for symbols and emoji.
    let fallbacks = fonts
        .families
        .get(&FontFamily::Proportional)
        .cloned()
        .unwrap_or_default();
    fonts
        .families
        .entry(FontFamily::Proportional)
        .or_default()
        .insert(0, "inter".into());
    for name in [SEMIBOLD, BOLD] {
        let mut list = vec![name.to_string()];
        list.extend(fallbacks.iter().cloned());
        fonts.families.insert(FontFamily::Name(name.into()), list);
    }
    ctx.set_fonts(fonts);

    ctx.set_theme(egui::Theme::Dark);
    let mut v = egui::Visuals::dark();
    v.panel_fill = BG;
    v.window_fill = SURFACE;
    v.window_stroke = Stroke::new(1.0, BORDER);
    v.window_corner_radius = CornerRadius::same(RADIUS);
    v.window_shadow = Shadow {
        offset: [0, 10],
        blur: 32,
        spread: 0,
        color: Color32::from_black_alpha(140),
    };
    v.popup_shadow = Shadow {
        offset: [0, 6],
        blur: 18,
        spread: 0,
        color: Color32::from_black_alpha(120),
    };
    v.menu_corner_radius = CornerRadius::same(RADIUS_SM);
    v.extreme_bg_color = Color32::from_rgb(0x10, 0x11, 0x14);
    v.text_edit_bg_color = Some(Color32::from_rgb(0x12, 0x13, 0x17));
    v.faint_bg_color = Color32::from_rgb(0x1F, 0x21, 0x26);
    v.code_bg_color = Color32::from_rgb(0x10, 0x11, 0x14);
    v.selection.bg_fill = ACCENT.gamma_multiply(0.55);
    v.selection.stroke = Stroke::new(1.0, ACCENT_HI);
    v.hyperlink_color = ACCENT_HI;
    v.warn_fg_color = WARN;
    v.error_fg_color = ERR;
    v.slider_trailing_fill = true;
    v.handle_shape = egui::style::HandleShape::Circle;
    v.interact_cursor = Some(CursorIcon::PointingHand);
    let radius = CornerRadius::same(RADIUS_SM);
    let w = &mut v.widgets;
    w.noninteractive.bg_fill = SURFACE;
    w.noninteractive.weak_bg_fill = SURFACE;
    w.noninteractive.bg_stroke = Stroke::new(1.0, BORDER);
    w.noninteractive.fg_stroke = Stroke::new(1.0, TEXT);
    w.noninteractive.corner_radius = radius;
    w.inactive.bg_fill = SURFACE_HI;
    w.inactive.weak_bg_fill = SURFACE_HI;
    w.inactive.bg_stroke = Stroke::new(1.0, BORDER);
    w.inactive.fg_stroke = Stroke::new(1.0, Color32::from_rgb(0xD6, 0xD9, 0xDE));
    w.inactive.corner_radius = radius;
    w.hovered.bg_fill = Color32::from_rgb(0x2D, 0x30, 0x37);
    w.hovered.weak_bg_fill = Color32::from_rgb(0x2D, 0x30, 0x37);
    w.hovered.bg_stroke = Stroke::new(1.0, ACCENT.gamma_multiply(0.75));
    w.hovered.fg_stroke = Stroke::new(1.5, TEXT);
    w.hovered.corner_radius = radius;
    w.hovered.expansion = 0.0;
    w.active.bg_fill = Color32::from_rgb(0x33, 0x36, 0x3E);
    w.active.weak_bg_fill = Color32::from_rgb(0x33, 0x36, 0x3E);
    w.active.bg_stroke = Stroke::new(1.0, ACCENT);
    w.active.fg_stroke = Stroke::new(2.0, Color32::WHITE);
    w.active.corner_radius = radius;
    w.active.expansion = 0.0;
    w.open.bg_fill = SURFACE_HI;
    w.open.weak_bg_fill = SURFACE_HI;
    w.open.bg_stroke = Stroke::new(1.0, ACCENT.gamma_multiply(0.75));
    w.open.corner_radius = radius;
    ctx.set_visuals_of(egui::Theme::Dark, v);

    ctx.all_styles_mut(|s| {
        s.text_styles = [
            (TextStyle::Heading, bold(26.0)),
            (TextStyle::Body, regular(14.0)),
            (TextStyle::Button, semibold(13.5)),
            (TextStyle::Small, regular(12.0)),
            (TextStyle::Monospace, FontId::monospace(12.5)),
        ]
        .into();
        s.spacing.item_spacing = Vec2::new(8.0, 8.0);
        s.spacing.button_padding = Vec2::new(12.0, 6.0);
        s.spacing.interact_size = Vec2::new(40.0, 28.0);
        s.spacing.slider_width = 200.0;
        s.spacing.slider_rail_height = 6.0;
        s.spacing.combo_height = 300.0;
        s.spacing.icon_width = 16.0;
        s.spacing.icon_width_inner = 9.0;
        s.spacing.icon_spacing = 8.0;
        s.spacing.menu_margin = egui::Margin::same(6);
        s.animation_time = 0.14;
    });
}

// ---- layout pieces ---------------------------------------------------------------

/// A raised card filling the available width.
pub fn card(ui: &mut Ui, add: impl FnOnce(&mut Ui)) {
    egui::Frame::NONE
        .fill(SURFACE)
        .stroke(Stroke::new(1.0, BORDER))
        .corner_radius(CornerRadius::same(RADIUS))
        .inner_margin(egui::Margin::same(18))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            add(ui);
        });
}

/// Page title with a one-line description underneath.
pub fn page_header(ui: &mut Ui, title: &str, subtitle: &str) {
    ui.label(RichText::new(title).font(bold(28.0)).color(TEXT));
    if !subtitle.is_empty() {
        ui.add_space(-2.0);
        ui.label(RichText::new(subtitle).color(MUTED));
    }
    ui.add_space(14.0);
}

/// Card title (semibold), optionally with a short description.
pub fn card_title(ui: &mut Ui, title: &str, description: &str) {
    ui.label(RichText::new(title).font(semibold(16.0)).color(TEXT));
    if !description.is_empty() {
        ui.label(RichText::new(description).color(MUTED));
    }
    ui.add_space(4.0);
}

/// Small spaced capitals ("ACCOUNT", "GAME").
pub fn overline(ui: &mut Ui, text: &str) {
    let job = spaced(text, semibold(11.0), FAINT, 1.2);
    ui.label(job);
}

/// Text with extra letter spacing.
pub fn spaced(text: &str, font: FontId, color: Color32, spacing: f32) -> egui::text::LayoutJob {
    let mut job = egui::text::LayoutJob::default();
    job.append(
        text,
        0.0,
        egui::TextFormat {
            font_id: font,
            color,
            extra_letter_spacing: spacing,
            ..Default::default()
        },
    );
    job
}

// ---- tones, pills, banners ---------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Tone {
    Neutral,
    Good,
    Warn,
    Bad,
    Accent,
}

impl Tone {
    pub fn color(self) -> Color32 {
        match self {
            Tone::Neutral => MUTED,
            Tone::Good => OK,
            Tone::Warn => WARN,
            Tone::Bad => ERR,
            Tone::Accent => ACCENT,
        }
    }

    pub fn icon(self) -> Icon {
        match self {
            Tone::Good => Icon::Check,
            Tone::Warn | Tone::Bad => Icon::Alert,
            Tone::Neutral | Tone::Accent => Icon::About,
        }
    }
}

/// Small status dot.
pub fn dot(ui: &mut Ui, color: Color32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(10.0), Sense::hover());
    ui.painter().circle_filled(rect.center(), 4.0, color);
}

/// Rounded status pill: dot plus text on a tinted background.
pub fn pill(ui: &mut Ui, text: &str, tone: Tone) -> Response {
    let c = tone.color();
    let galley = ui
        .painter()
        .layout_no_wrap(text.to_owned(), semibold(12.0), c);
    let size = Vec2::new(galley.size().x + 26.0, 22.0);
    let (rect, resp) = ui.allocate_exact_size(size, Sense::hover());
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        p.rect_filled(rect, CornerRadius::same(11), c.gamma_multiply(0.14));
        p.circle_filled(pos2(rect.left() + 11.0, rect.center().y), 3.5, c);
        p.galley(
            pos2(rect.left() + 19.0, rect.center().y - galley.size().y / 2.0),
            galley,
            c,
        );
    }
    resp
}

/// Inline notice with an icon and optional title.
pub fn banner(ui: &mut Ui, tone: Tone, title: &str, body: &str) {
    let c = tone.color();
    egui::Frame::NONE
        .fill(c.gamma_multiply(0.10))
        .stroke(Stroke::new(1.0, c.gamma_multiply(0.55)))
        .corner_radius(CornerRadius::same(RADIUS_SM))
        .inner_margin(egui::Margin::symmetric(14, 12))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                icons::show(ui, tone.icon(), 18.0, c);
                ui.vertical(|ui| {
                    if !title.is_empty() {
                        ui.label(RichText::new(title).font(semibold(14.0)).color(c));
                    }
                    if !body.is_empty() {
                        ui.label(RichText::new(body).color(TEXT));
                    }
                });
            });
        });
}

// ---- buttons -----------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    /// Accent fill: the one main action of a view.
    Primary,
    /// Raised neutral button.
    Secondary,
    /// Text-only button that gains a background on hover.
    Ghost,
    /// Destructive actions.
    Danger,
}

/// A themed button with an optional leading icon. Disabled buttons are
/// drawn faded and never report clicks.
pub fn button(ui: &mut Ui, kind: Kind, icon: Option<Icon>, text: &str, enabled: bool) -> Response {
    button_sized(ui, kind, icon, text, enabled, 0.0)
}

/// [`button`] with a minimum width.
pub fn button_sized(
    ui: &mut Ui,
    kind: Kind,
    icon: Option<Icon>,
    text: &str,
    enabled: bool,
    min_width: f32,
) -> Response {
    let font = semibold(13.5);
    let galley = ui
        .painter()
        .layout_no_wrap(text.to_owned(), font, Color32::WHITE);
    let icon_w = if icon.is_some() { 16.0 + 8.0 } else { 0.0 };
    let width = (galley.size().x + icon_w + 30.0).max(min_width);
    let sense = if enabled {
        Sense::click()
    } else {
        Sense::hover()
    };
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(width, CONTROL_H), sense);
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, enabled, text));
    if !ui.is_rect_visible(rect) {
        return resp;
    }
    let ctx = ui.ctx();
    let hover = ctx.animate_bool_with_time(resp.id.with("h"), enabled && resp.hovered(), HOVER_T);
    let press = ctx.animate_bool_with_time(
        resp.id.with("p"),
        enabled && resp.is_pointer_button_down_on(),
        0.06,
    );
    if enabled && resp.hovered() {
        ctx.set_cursor_icon(CursorIcon::PointingHand);
    }
    let (fill, fill_hover, stroke, text_c) = match kind {
        Kind::Primary => (ACCENT, ACCENT_HI, Color32::TRANSPARENT, ON_ACCENT),
        Kind::Secondary => (
            SURFACE_HI,
            Color32::from_rgb(0x30, 0x33, 0x3A),
            BORDER,
            TEXT,
        ),
        Kind::Ghost => (Color32::TRANSPARENT, SURFACE_HI, Color32::TRANSPARENT, TEXT),
        Kind::Danger => (
            ERR.gamma_multiply(0.16),
            ERR.gamma_multiply(0.28),
            ERR.gamma_multiply(0.6),
            Color32::from_rgb(0xFF, 0xB4, 0xB4),
        ),
    };
    let mut fill = lerp(fill, fill_hover, hover);
    let mut text_c = text_c;
    let mut stroke_c = stroke;
    if !enabled {
        fill = fill.gamma_multiply(0.45);
        text_c = text_c.gamma_multiply(0.45);
        stroke_c = stroke_c.gamma_multiply(0.45);
    }
    let r = rect.shrink(press * 1.0);
    let p = ui.painter();
    p.rect_filled(r, CornerRadius::same(RADIUS_SM), fill);
    if stroke_c != Color32::TRANSPARENT {
        p.rect_stroke(
            r,
            CornerRadius::same(RADIUS_SM),
            Stroke::new(1.0, stroke_c),
            StrokeKind::Inside,
        );
    }
    let content_w = galley.size().x + icon_w;
    let mut x = r.center().x - content_w / 2.0;
    if let Some(i) = icon {
        let ir = Rect::from_center_size(pos2(x + 8.0, r.center().y), Vec2::splat(16.0));
        icons::paint(p, ir, i, text_c);
        x += icon_w;
    }
    p.galley(
        pos2(x, r.center().y - galley.size().y / 2.0),
        galley,
        text_c,
    );
    resp
}

/// The dashboard's Play button: large, warm gradient, with a second line of
/// detail. `progress` = `Some(None)` for indeterminate work, `Some(Some(f))`
/// for a known fraction; the button then shows the progress as its fill.
pub fn play_button(
    ui: &mut Ui,
    size: Vec2,
    label: &str,
    detail: &str,
    enabled: bool,
    progress: Option<Option<f32>>,
) -> Response {
    let sense = if enabled {
        Sense::click()
    } else {
        Sense::hover()
    };
    let (rect, resp) = ui.allocate_exact_size(size, sense);
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, enabled, label));
    if !ui.is_rect_visible(rect) {
        return resp;
    }
    let ctx = ui.ctx().clone();
    let hover = ctx.animate_bool_with_time(resp.id.with("h"), enabled && resp.hovered(), 0.16);
    let press = ctx.animate_bool_with_time(
        resp.id.with("p"),
        enabled && resp.is_pointer_button_down_on(),
        0.07,
    );
    if enabled && resp.hovered() {
        ctx.set_cursor_icon(CursorIcon::PointingHand);
    }
    let r = rect.shrink(press * 2.0);
    let radius = CornerRadius::same(14);
    let p = ui.painter();
    // Soft warm glow that grows on hover.
    if enabled {
        let glow = Shadow {
            offset: [0, 8],
            blur: (18.0 + 14.0 * hover) as u8,
            spread: 0,
            color: ACCENT.gamma_multiply(0.22 + 0.18 * hover),
        };
        p.add(glow.as_shape(r, radius));
    }
    let busy = progress.is_some();
    let (top, bottom) = if busy || !enabled {
        (
            Color32::from_rgb(0x3A, 0x2C, 0x20),
            Color32::from_rgb(0x2E, 0x24, 0x1B),
        )
    } else {
        (
            lerp(ACCENT_HI, Color32::from_rgb(0xFF, 0xB0, 0x70), hover),
            lerp(ACCENT_LO, ACCENT, hover),
        )
    };
    vertical_gradient(p, r, radius, top, bottom);
    if let Some(prog) = progress {
        let t = ctx.input(|i| i.time) as f32;
        let fill_rect = match prog {
            Some(f) => {
                let f = f.clamp(0.0, 1.0);
                Rect::from_min_max(r.min, pos2(r.left() + r.width() * f, r.bottom()))
            }
            None => {
                // A highlight sweeping across while the length of the work is unknown.
                let w = r.width() * 0.35;
                let x = r.left() - w + ((t * 0.55) % 1.0) * (r.width() + w);
                Rect::from_min_max(pos2(x, r.top()), pos2(x + w, r.bottom())).intersect(r)
            }
        };
        if fill_rect.width() > 0.5 {
            let clip = p.with_clip_rect(fill_rect);
            vertical_gradient(
                &clip,
                r,
                radius,
                ACCENT.gamma_multiply(0.85),
                ACCENT_LO.gamma_multiply(0.85),
            );
        }
        ctx.request_repaint_after(std::time::Duration::from_millis(16));
    }
    // Top sheen.
    let sheen = Rect::from_min_max(r.min, pos2(r.right(), r.top() + r.height() * 0.5));
    p.rect_filled(
        sheen,
        CornerRadius {
            nw: radius.nw,
            ne: radius.ne,
            sw: 0,
            se: 0,
        },
        Color32::from_white_alpha(if enabled { 14 } else { 6 }),
    );
    let text_c = if enabled {
        ON_ACCENT
    } else {
        Color32::from_rgb(0xE9, 0xD6, 0xC6)
    };
    let detail_c = if enabled {
        ON_ACCENT.gamma_multiply(0.72)
    } else {
        Color32::from_rgb(0xB8, 0xA8, 0x9A)
    };
    let label_g = p.layout_no_wrap(label.to_owned(), bold(22.0), text_c);
    let detail_g = p.layout_no_wrap(detail.to_owned(), semibold(12.0), detail_c);
    let icon_size = 22.0;
    let content_w = icon_size + 14.0 + label_g.size().x.max(detail_g.size().x);
    let x0 = r.center().x - content_w / 2.0;
    let total_h = label_g.size().y
        + if detail.is_empty() {
            0.0
        } else {
            detail_g.size().y
        };
    let y0 = r.center().y - total_h / 2.0;
    let icon_rect = Rect::from_center_size(
        pos2(x0 + icon_size / 2.0, r.center().y),
        Vec2::splat(icon_size),
    );
    if busy && enabled {
        spinner_at(p, icon_rect, text_c, ctx.input(|i| i.time) as f32);
    } else {
        icons::paint(p, icon_rect, Icon::Play, text_c);
    }
    let tx = x0 + icon_size + 14.0;
    let label_h = label_g.size().y;
    p.galley(pos2(tx, y0), label_g, text_c);
    if !detail.is_empty() {
        p.galley(pos2(tx, y0 + label_h), detail_g, detail_c);
    }
    resp
}

/// Fill a rounded rect with a top-to-bottom gradient: the outline (with
/// arcs at the corners) is fanned from the centre with per-vertex colours,
/// which is exact because the shape is convex.
fn vertical_gradient(
    p: &egui::Painter,
    r: Rect,
    radius: CornerRadius,
    top: Color32,
    bottom: Color32,
) {
    let rad = (radius.nw as f32)
        .min(r.height() / 2.0)
        .min(r.width() / 2.0);
    let color_at = |y: f32| lerp(top, bottom, (y - r.top()) / r.height().max(1.0));
    let mut mesh = egui::Mesh::default();
    mesh.colored_vertex(r.center(), color_at(r.center().y));
    let corners = [
        (
            pos2(r.right() - rad, r.top() + rad),
            -std::f32::consts::FRAC_PI_2,
        ),
        (pos2(r.right() - rad, r.bottom() - rad), 0.0),
        (
            pos2(r.left() + rad, r.bottom() - rad),
            std::f32::consts::FRAC_PI_2,
        ),
        (pos2(r.left() + rad, r.top() + rad), std::f32::consts::PI),
    ];
    let steps = 8;
    for (c, a0) in corners {
        for i in 0..=steps {
            let a = a0 + std::f32::consts::FRAC_PI_2 * i as f32 / steps as f32;
            let v = c + Vec2::new(a.cos(), a.sin()) * rad;
            mesh.colored_vertex(v, color_at(v.y));
        }
    }
    let n = mesh.vertices.len() as u32 - 1;
    for i in 1..=n {
        mesh.add_triangle(0, i, if i == n { 1 } else { i + 1 });
    }
    p.add(egui::Shape::mesh(mesh));
}

/// Small square icon-only button (e.g. close). `label` is its tooltip and
/// accessible name.
pub fn icon_button(ui: &mut Ui, icon: Icon, size: f32, label: &str) -> Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), Sense::click());
    resp.widget_info(|| egui::WidgetInfo::labeled(egui::Role::Button, true, label));
    let hover = ui
        .ctx()
        .animate_bool_with_time(resp.id.with("h"), resp.hovered(), HOVER_T);
    if resp.hovered() {
        ui.ctx().set_cursor_icon(CursorIcon::PointingHand);
    }
    let p = ui.painter();
    p.rect_filled(
        rect,
        CornerRadius::same(6),
        lerp(Color32::TRANSPARENT, SURFACE_HI, hover),
    );
    icons::paint(p, rect.shrink(size * 0.25), icon, lerp(MUTED, TEXT, hover));
    resp.on_hover_text(label)
}

// ---- tabs -----------------------------------------------------------------------------

/// Segmented tab bar with a sliding accent pill. Returns the newly clicked
/// tab, if any.
pub fn tab_bar(ui: &mut Ui, id_salt: &str, labels: &[&str], selected: usize) -> Option<usize> {
    let font = semibold(13.5);
    let galleys: Vec<_> = labels
        .iter()
        .map(|l| {
            ui.painter()
                .layout_no_wrap((*l).to_owned(), font.clone(), TEXT)
        })
        .collect();
    let pad = 4.0;
    let widths: Vec<f32> = galleys.iter().map(|g| g.size().x + 30.0).collect();
    let total = widths.iter().sum::<f32>() + pad * 2.0;
    let (outer, _) =
        ui.allocate_exact_size(Vec2::new(total, CONTROL_H + pad * 2.0), Sense::hover());
    let id = ui.scope_id().with(("kestrel-tabs", id_salt));
    let p = ui.painter().clone();
    p.rect_filled(outer, CornerRadius::same(RADIUS), SURFACE);
    p.rect_stroke(
        outer,
        CornerRadius::same(RADIUS),
        Stroke::new(1.0, BORDER),
        StrokeKind::Inside,
    );
    // Sliding selection pill.
    let sel = selected.min(labels.len().saturating_sub(1));
    let target_x: f32 = widths[..sel].iter().sum();
    let ctx = ui.ctx().clone();
    let x = ctx.animate_value_with_time(id.with("x"), target_x, 0.18);
    let w = ctx.animate_value_with_time(id.with("w"), widths[sel], 0.18);
    let pill = Rect::from_min_size(
        pos2(outer.left() + pad + x, outer.top() + pad),
        Vec2::new(w, CONTROL_H),
    );
    p.rect_filled(pill, CornerRadius::same(RADIUS_SM), ACCENT);
    let mut clicked = None;
    let mut cx = outer.left() + pad;
    for (i, (g, wi)) in galleys.into_iter().zip(&widths).enumerate() {
        let r = Rect::from_min_size(pos2(cx, outer.top() + pad), Vec2::new(*wi, CONTROL_H));
        let resp = ui.interact(r, id.with(i), Sense::click());
        resp.widget_info(|| egui::WidgetInfo::selected(egui::Role::Tab, true, i == sel, labels[i]));
        let hover = ctx.animate_bool_with_time(resp.id.with("h"), resp.hovered(), HOVER_T);
        if resp.hovered() {
            ctx.set_cursor_icon(CursorIcon::PointingHand);
        }
        // How much of the sliding pill covers this tab decides its text colour.
        let cover =
            ((pill.right().min(r.right()) - pill.left().max(r.left())) / r.width()).clamp(0.0, 1.0);
        let base = lerp(MUTED, TEXT, hover);
        let c = lerp(base, ON_ACCENT, cover);
        p.galley(r.center() - g.size() / 2.0, g, c);
        if resp.clicked() && i != sel {
            clicked = Some(i);
        }
        cx += wi;
    }
    clicked
}

// ---- progress -------------------------------------------------------------------------

/// Thin rounded progress bar; `None` = indeterminate (animated sweep).
pub fn progress_bar(ui: &mut Ui, fraction: Option<f32>, width: f32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::new(width, 6.0), Sense::hover());
    let p = ui.painter();
    p.rect_filled(rect, CornerRadius::same(3), SURFACE_HI);
    let fill = match fraction {
        Some(f) => Rect::from_min_size(
            rect.min,
            Vec2::new(rect.width() * f.clamp(0.0, 1.0), rect.height()),
        ),
        None => {
            let t = ui.input(|i| i.time) as f32;
            let w = rect.width() * 0.3;
            let x = rect.left() - w + ((t * 0.7) % 1.0) * (rect.width() + w);
            ui.ctx()
                .request_repaint_after(std::time::Duration::from_millis(16));
            Rect::from_min_max(pos2(x, rect.top()), pos2(x + w, rect.bottom())).intersect(rect)
        }
    };
    if fill.width() > 0.0 {
        p.rect_filled(fill, CornerRadius::same(3), ACCENT);
    }
}

/// Small spinning arc.
pub fn spinner(ui: &mut Ui, size: f32, color: Color32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(size), Sense::hover());
    let t = ui.input(|i| i.time) as f32;
    spinner_at(ui.painter(), rect, color, t);
    ui.ctx()
        .request_repaint_after(std::time::Duration::from_millis(16));
}

fn spinner_at(p: &egui::Painter, rect: Rect, color: Color32, t: f32) {
    let c = rect.center();
    let r = rect.width().min(rect.height()) * 0.38;
    let start = t * 5.0;
    let pts: Vec<_> = (0..=20)
        .map(|i| {
            let a = start + i as f32 / 20.0 * std::f32::consts::PI * 1.4;
            c + Vec2::new(a.cos(), a.sin()) * r
        })
        .collect();
    p.add(egui::Shape::line(
        pts,
        Stroke::new((r * 0.28).max(1.5), color),
    ));
}

// ---- misc -------------------------------------------------------------------------------

/// Linear blend of two colours (gamma space; fine for UI tints).
pub fn lerp(a: Color32, b: Color32, t: f32) -> Color32 {
    a.lerp_to_gamma(b, t.clamp(0.0, 1.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lerp_clamps_and_reaches_both_ends() {
        assert_eq!(lerp(ACCENT, TEXT, 0.0), ACCENT);
        assert_eq!(lerp(ACCENT, TEXT, 1.0), TEXT);
        assert_eq!(lerp(ACCENT, TEXT, -3.0), ACCENT);
        assert_eq!(lerp(ACCENT, TEXT, 9.0), TEXT);
    }

    #[test]
    fn bundled_fonts_are_truetype() {
        for f in [
            embedded::FONT_REGULAR,
            embedded::FONT_SEMIBOLD,
            embedded::FONT_BOLD,
        ] {
            assert_eq!(&f[..4], &[0, 1, 0, 0], "TrueType outline font expected");
        }
    }
}
