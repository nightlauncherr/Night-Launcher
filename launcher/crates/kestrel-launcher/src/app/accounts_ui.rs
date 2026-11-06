//! Saved Microsoft accounts: the sidebar account switcher and the Accounts page.
//!
//! Only names, UUIDs and "needs sign-in" flags reach the UI; refresh tokens stay in
//! the encrypted store (`kestrel_core::auth`).

use std::collections::HashMap;
use std::sync::mpsc::{Receiver, Sender, channel};

use kestrel_core::cosmetics::Rgba;
use kestrel_core::skins::{self, SkinSource};

use super::*;

enum Avatar {
    Loading,
    Ready(egui::TextureHandle),
    Failed,
}

pub(super) struct State {
    menu_open: bool,
    confirm_remove: Option<String>,
    avatars: HashMap<String, Avatar>,
    tx: Sender<(String, Result<Rgba, String>)>,
    rx: Receiver<(String, Result<Rgba, String>)>,
}

impl Default for State {
    fn default() -> Self {
        let (tx, rx) = channel();
        State {
            menu_open: false,
            confirm_remove: None,
            avatars: HashMap::new(),
            tx,
            rx,
        }
    }
}

/// The 8x8 face (with the hat layer on top) of a 64x64 or 64x32 skin.
fn face(skin: &Rgba) -> Rgba {
    let mut out = vec![0u8; 8 * 8 * 4];
    let px = |x: u32, y: u32| -> [u8; 4] {
        let i = ((y * skin.width + x) * 4) as usize;
        let mut p = [0u8; 4];
        p.copy_from_slice(&skin.pixels[i..i + 4]);
        p
    };
    for y in 0..8u32 {
        for x in 0..8u32 {
            let base = px(8 + x, 8 + y);
            let hat = px(40 + x, 8 + y);
            let a = hat[3] as u32;
            let mut c = [0u8; 4];
            for k in 0..3 {
                c[k] = ((hat[k] as u32 * a + base[k] as u32 * (255 - a)) / 255) as u8;
            }
            c[3] = 255;
            out[((y * 8 + x) * 4) as usize..][..4].copy_from_slice(&c);
        }
    }
    Rgba {
        width: 8,
        height: 8,
        pixels: out,
    }
}

impl LauncherApp {
    fn poll_avatars(&mut self, ctx: &egui::Context) {
        while let Ok((uuid, r)) = self.accounts_ui.rx.try_recv() {
            let a = match r {
                Ok(img) => Avatar::Ready(ctx.load_texture(
                    format!("avatar-{uuid}"),
                    egui::ColorImage::from_rgba_unmultiplied([8, 8], &img.pixels),
                    egui::TextureOptions::NEAREST,
                )),
                Err(e) => {
                    log::info!("no avatar for an account: {e}");
                    Avatar::Failed
                }
            };
            self.accounts_ui.avatars.insert(uuid, a);
        }
    }

    /// Draw an account's face in `rect`, fetching it in the background the
    /// first time (Mojang's public profile API; nothing secret is sent).
    pub(super) fn paint_avatar(
        &mut self,
        ui: &egui::Ui,
        rect: Rect,
        uuid: Option<&str>,
        name: &str,
    ) {
        let p = ui.painter();
        p.rect_filled(rect, CornerRadius::same(6), CARD);
        if let Some(uuid) = uuid {
            if !self.accounts_ui.avatars.contains_key(uuid) {
                self.accounts_ui
                    .avatars
                    .insert(uuid.to_string(), Avatar::Loading);
                let (tx, id, ctx) = (
                    self.accounts_ui.tx.clone(),
                    uuid.to_string(),
                    ui.ctx().clone(),
                );
                std::thread::spawn(move || {
                    let r = skins::resolve(&SkinSource::Profile(id.clone()), &skins::http_fetch)
                        .and_then(|d| skins::validate_skin(&d.bytes))
                        .map(|s| face(&s.rgba));
                    let _ = tx.send((id, r));
                    ctx.request_repaint();
                });
            }
            if let Some(Avatar::Ready(t)) = self.accounts_ui.avatars.get(uuid) {
                p.image(
                    t.id(),
                    rect.shrink(rect.width() * 0.12),
                    Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)),
                    Color32::WHITE,
                );
                return;
            }
        }
        let initial = name
            .chars()
            .next()
            .unwrap_or('?')
            .to_ascii_uppercase()
            .to_string();
        p.text(
            rect.center(),
            egui::Align2::CENTER_CENTER,
            initial,
            theme::bold(rect.height() * 0.45),
            ACCENT_TEXT,
        );
    }

    fn switch_account(&mut self, uuid: &str) {
        let target = uuid.to_string();
        match auth::update_store(&self.layout.accounts(), |s| s.set_active(&target)) {
            Ok((true, _)) => {
                self.offline_mode = false;
                self.reload_accounts();
                let name = self.active().map(|a| a.name.clone()).unwrap_or_default();
                let running = self.runs.len();
                self.notify(Status::Ok(if running > 0 {
                    format!(
                        "{name} is now the active account. Games already running keep their account; it applies from the next launch."
                    )
                } else {
                    format!("{name} is now the active account.")
                }));
            }
            Ok((false, _)) => {
                self.reload_accounts();
                self.notify(Status::Error("That account is no longer saved.".into()));
            }
            Err(e) => self.notify(Status::Error(format!("could not switch accounts: {e}"))),
        }
    }

    fn remove_account(&mut self, uuid: &str) {
        let target = uuid.to_string();
        match auth::update_store(&self.layout.accounts(), |s| s.remove(&target)) {
            Ok(_) => {
                let name = self
                    .account(uuid)
                    .map(|a| a.name.clone())
                    .unwrap_or_default();
                self.reload_accounts();
                self.notify(Status::Info(format!("Removed {name} from this PC.")));
            }
            Err(e) => self.notify(Status::Error(format!("could not remove the account: {e}"))),
        }
    }

    fn start_sign_in(&mut self, ctx: &egui::Context) {
        let client_id = match auth::validate_client_id(self.cfg.msa_client_id()) {
            Ok(id) => id,
            Err(e) => {
                self.notify(Status::Error(e.to_string()));
                return;
            }
        };
        let (path, ctx) = (self.layout.accounts(), ctx.clone());
        self.go(Page::Account);
        self.start(Busy::SigningIn, move |p, tx| {
            worker::sign_in(client_id, path, p, tx, ctx)
        });
    }

    /// Instances that launch with this specific account.
    fn instances_using(&self, uuid: &str) -> Vec<String> {
        self.instances
            .iter()
            .filter(|i| matches!(&i.meta.account, AccountChoice::Account { uuid: u } if u == uuid))
            .map(|i| i.meta.name.clone())
            .collect()
    }

    // ---- sidebar switcher -------------------------------------------------------------

    pub(super) fn sidebar_account(&mut self, ui: &mut egui::Ui, compact: bool, width: f32) {
        let ctx = ui.ctx().clone();
        self.poll_avatars(&ctx);
        let (name, uuid, detail) = match (self.active(), self.offline_mode) {
            (_, true) => (self.cfg.offline_name.clone(), None, "Offline".to_string()),
            (Some(a), false) => (
                a.name.clone(),
                Some(a.uuid.clone()),
                if a.needs_sign_in {
                    "Sign in again".to_string()
                } else {
                    format!("{} saved", self.accounts.len())
                },
            ),
            (None, false) => ("Not signed in".into(), None, "Add an account".into()),
        };
        let h = if compact { 44.0 } else { 52.0 };
        let mut button = None;
        ui.horizontal(|ui| {
            ui.add_space(if compact { 8.0 } else { 12.0 });
            let (rect, resp) = ui.allocate_exact_size(Vec2::new(width, h), Sense::click());
            let hover = ctx.animate_bool_with_time(
                resp.id,
                resp.hovered() || self.accounts_ui.menu_open,
                theme::HOVER_T,
            );
            ui.painter().rect(
                rect,
                CornerRadius::same(theme::RADIUS_SM),
                theme::lerp(theme::SURFACE.gamma_multiply(0.7), theme::SURFACE_HI, hover),
                Stroke::new(
                    1.0,
                    theme::lerp(theme::BORDER, ACCENT.gamma_multiply(0.7), hover),
                ),
                StrokeKind::Inside,
            );
            let av = Rect::from_min_size(rect.min + Vec2::splat(8.0), Vec2::splat(h - 16.0));
            self.paint_avatar(ui, av, uuid.as_deref(), &name);
            if !compact {
                let x = av.right() + 10.0;
                let p = ui.painter();
                let g = one_line(
                    p,
                    &name,
                    theme::semibold(13.5),
                    theme::TEXT,
                    rect.right() - x - 34.0,
                );
                p.galley(pos2(x, rect.top() + 9.0), g, theme::TEXT);
                p.text(
                    pos2(x, rect.top() + 29.0),
                    egui::Align2::LEFT_TOP,
                    detail,
                    theme::regular(11.5),
                    MUTED,
                );
                icons::paint(
                    p,
                    Rect::from_center_size(
                        pos2(rect.right() - 18.0, rect.center().y),
                        Vec2::splat(16.0),
                    ),
                    Icon::Switch,
                    MUTED,
                );
            }
            let resp = resp.on_hover_text("Switch account");
            if resp.clicked() {
                self.accounts_ui.menu_open = !self.accounts_ui.menu_open;
            }
            button = Some(rect);
        });
        if self.accounts_ui.menu_open
            && let Some(anchor) = button
        {
            self.account_menu(&ctx, anchor);
        }
    }

    /// The account list that opens above the sidebar button.
    fn account_menu(&mut self, ctx: &egui::Context, anchor: Rect) {
        let mut pick = None;
        let mut add = false;
        let mut manage = false;
        let mut offline = false;
        let rows: Vec<AccountSummary> = self.accounts.clone();
        let area = egui::Area::new(egui::Id::unique("account-menu"))
            .order(egui::Order::Foreground)
            .fixed_pos(pos2(anchor.left(), anchor.top() - 8.0))
            .pivot(egui::Align2::LEFT_BOTTOM)
            .show(ctx, |ui| {
                egui::Frame::NONE
                    .fill(theme::SURFACE)
                    .stroke(Stroke::new(1.0, theme::BORDER))
                    .corner_radius(CornerRadius::same(theme::RADIUS))
                    .shadow(egui::Shadow {
                        offset: [0, 8],
                        blur: 24,
                        spread: 0,
                        color: Color32::from_black_alpha(140),
                    })
                    .inner_margin(egui::Margin::same(10))
                    .show(ui, |ui| {
                        ui.set_width(260.0);
                        ui.label(theme::spaced(
                            "SWITCH ACCOUNT",
                            theme::semibold(10.5),
                            ACCENT,
                            1.4,
                        ));
                        ui.add_space(6.0);
                        for a in &rows {
                            let active = !self.offline_mode
                                && self.active_account.as_deref() == Some(a.uuid.as_str());
                            let (r, resp) =
                                ui.allocate_exact_size(Vec2::new(260.0, 40.0), Sense::click());
                            let hover = resp.hovered();
                            if hover || active {
                                ui.painter().rect_filled(
                                    r,
                                    CornerRadius::same(theme::RADIUS_SM),
                                    if active {
                                        ACCENT.gamma_multiply(0.16)
                                    } else {
                                        CARD
                                    },
                                );
                            }
                            let av =
                                Rect::from_min_size(r.min + Vec2::new(6.0, 6.0), Vec2::splat(28.0));
                            self.paint_avatar(ui, av, Some(&a.uuid), &a.name);
                            let p = ui.painter();
                            p.text(
                                pos2(
                                    av.right() + 10.0,
                                    r.center().y - (if a.needs_sign_in { 7.0 } else { 0.0 }),
                                ),
                                egui::Align2::LEFT_CENTER,
                                &a.name,
                                theme::semibold(13.0),
                                theme::TEXT,
                            );
                            if a.needs_sign_in {
                                p.text(
                                    pos2(av.right() + 10.0, r.center().y + 9.0),
                                    egui::Align2::LEFT_CENTER,
                                    "Needs sign-in",
                                    theme::regular(11.0),
                                    WARN,
                                );
                            }
                            if active {
                                icons::paint(
                                    p,
                                    Rect::from_center_size(
                                        pos2(r.right() - 16.0, r.center().y),
                                        Vec2::splat(16.0),
                                    ),
                                    Icon::Check,
                                    OK,
                                );
                            }
                            if resp.clicked() && !active {
                                pick = Some(a.uuid.clone());
                            }
                        }
                        if rows.is_empty() {
                            ui.label(theme::muted("No saved accounts yet."));
                        }
                        ui.add_space(6.0);
                        ui.separator();
                        ui.add_space(2.0);
                        if self.offline_mode
                            && ui
                                .selectable_label(
                                    true,
                                    format!("Offline: {}  (turn off)", self.cfg.offline_name),
                                )
                                .clicked()
                        {
                            offline = true;
                        }
                        ui.horizontal(|ui| {
                            add = theme::button(
                                ui,
                                Kind::Secondary,
                                Some(Icon::Plus),
                                "Add account",
                                self.busy == Busy::Idle,
                            )
                            .clicked();
                            manage = theme::button(ui, Kind::Ghost, None, "Manage", true).clicked();
                        });
                    });
            });
        // Close on a click anywhere outside the menu and its button.
        let clicked_outside = ctx.input(|i| i.pointer.any_click())
            && ctx
                .pointer_interact_pos()
                .is_some_and(|p| !area.response.rect.contains(p) && !anchor.contains(p));
        if clicked_outside || ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            self.accounts_ui.menu_open = false;
        }
        if let Some(u) = pick {
            self.accounts_ui.menu_open = false;
            self.switch_account(&u);
        }
        if offline {
            self.offline_mode = false;
        }
        if add {
            self.accounts_ui.menu_open = false;
            self.start_sign_in(ctx);
        }
        if manage {
            self.accounts_ui.menu_open = false;
            self.go(Page::Account);
        }
    }

    // ---- Accounts page ----------------------------------------------------------------

    pub(super) fn account_page(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        self.poll_avatars(&ctx);
        card(ui, |ui| {
            theme::card_title(
                ui,
                "Saved accounts",
                "Click Make active to switch. Instances set to a specific account keep it; the rest use the active one. \
                 Games already running keep the account they started with.",
            );
            ui.add_space(4.0);
            let rows = self.accounts.clone();
            if rows.is_empty() {
                ui.label(theme::muted("No Microsoft accounts on this PC yet."));
            }
            for a in rows {
                let active =
                    !self.offline_mode && self.active_account.as_deref() == Some(a.uuid.as_str());
                egui::Frame::NONE
                    .fill(if active { ACCENT.gamma_multiply(0.10) } else { CARD })
                    .stroke(Stroke::new(1.0, if active { ACCENT.gamma_multiply(0.55) } else { theme::BORDER }))
                    .corner_radius(CornerRadius::same(theme::RADIUS_SM))
                    .inner_margin(egui::Margin::same(10))
                    .show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.horizontal(|ui| {
                            let (r, _) = ui.allocate_exact_size(Vec2::splat(46.0), Sense::hover());
                            self.paint_avatar(ui, r, Some(&a.uuid), &a.name);
                            ui.add_space(6.0);
                            ui.vertical(|ui| {
                                ui.horizontal(|ui| {
                                    ui.label(RichText::new(&a.name).font(theme::semibold(17.0)).color(theme::TEXT));
                                    if active {
                                        theme::pill(ui, "Active", Tone::Good);
                                    }
                                    if a.needs_sign_in {
                                        theme::pill(ui, "Sign-in expired", Tone::Warn);
                                    }
                                });
                                let using = self.instances_using(&a.uuid);
                                let line = if using.is_empty() {
                                    format!("UUID {}", a.uuid)
                                } else {
                                    format!("UUID {}  ·  assigned to {}", a.uuid, using.join(", "))
                                };
                                ui.label(RichText::new(line).small().color(theme::FAINT));
                            });
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                let idle = self.busy == Busy::Idle;
                                if self.accounts_ui.confirm_remove.as_deref() == Some(a.uuid.as_str()) {
                                    if theme::button(ui, Kind::Danger, None, "Remove", true).clicked() {
                                        self.accounts_ui.confirm_remove = None;
                                        self.remove_account(&a.uuid);
                                    }
                                    if theme::button(ui, Kind::Ghost, None, "Keep", true).clicked() {
                                        self.accounts_ui.confirm_remove = None;
                                    }
                                } else {
                                    if theme::icon_button(ui, Icon::Trash, 28.0, "Remove from this PC").clicked() {
                                        self.accounts_ui.confirm_remove = Some(a.uuid.clone());
                                    }
                                    if a.needs_sign_in {
                                        if theme::button(ui, Kind::Primary, Some(Icon::Account), "Sign in again", idle).clicked() {
                                            self.start_sign_in(&ctx);
                                        }
                                    } else if !active
                                        && theme::button(ui, Kind::Secondary, Some(Icon::Switch), "Make active", true).clicked()
                                    {
                                        self.switch_account(&a.uuid);
                                    }
                                }
                            });
                        });
                        if self.accounts_ui.confirm_remove.as_deref() == Some(a.uuid.as_str()) {
                            let using = self.instances_using(&a.uuid);
                            ui.label(
                                RichText::new(if using.is_empty() {
                                    "Removes the saved sign-in from this PC. Your Microsoft account itself is not affected.".to_string()
                                } else {
                                    format!(
                                        "Removes the saved sign-in from this PC. {} will need another account before they can start.",
                                        using.join(", ")
                                    )
                                })
                                .small()
                                .color(WARN),
                            );
                        }
                    });
                ui.add_space(6.0);
            }
            if !kestrel_core::secure::is_supported() {
                theme::banner(
                    ui,
                    Tone::Warn,
                    "",
                    "Secure storage is unavailable on this OS; accounts are not saved between launches.",
                );
            }
            ui.add_space(6.0);
            let client_id = auth::validate_client_id(self.cfg.msa_client_id());
            let can = self.busy == Busy::Idle && client_id.is_ok();
            if theme::button_sized(
                ui,
                Kind::Primary,
                Some(Icon::Plus),
                if self.accounts.is_empty() { "Sign in with Microsoft" } else { "Add another Microsoft account" },
                can,
                260.0,
            )
            .on_hover_text("You sign in on microsoft.com in your browser; Night Launcher never sees your password.")
            .clicked()
            {
                self.start_sign_in(&ctx);
            }
            if let Err(e) = &client_id {
                ui.add_space(6.0);
                theme::banner(ui, Tone::Warn, "", &format!("{e}"));
            }
            if let Some(code) = self.device_code.clone() {
                ui.add_space(14.0);
                self.device_code_card(ui, &code);
            }
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(
                ui,
                "Offline mode",
                "Replaces the active account with an offline name. For singleplayer and offline-mode servers only; \
                 online servers (Hypixel, etc.) reject offline sessions.",
            );
            ui.checkbox(&mut self.offline_mode, "Play offline");
            ui.horizontal(|ui| {
                ui.label("Offline name");
                if ui
                    .add(
                        egui::TextEdit::singleline(&mut self.cfg.offline_name).desired_width(220.0),
                    )
                    .changed()
                {
                    self.cfg_dirty = true;
                }
            });
        });
    }

    fn device_code_card(&mut self, ui: &mut egui::Ui, code: &DeviceCode) {
        egui::Frame::NONE
            .fill(CARD)
            .stroke(Stroke::new(1.0, ACCENT.gamma_multiply(0.6)))
            .corner_radius(CornerRadius::same(theme::RADIUS))
            .inner_margin(egui::Margin::same(16))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.label(theme::muted("Open the Microsoft page and enter this code:"));
                ui.add_space(4.0);
                ui.label(theme::spaced(
                    &code.user_code,
                    theme::bold(30.0),
                    ACCENT_TEXT,
                    4.0,
                ));
                ui.add_space(8.0);
                ui.horizontal_wrapped(|ui| {
                    if theme::button(ui, Kind::Primary, None, "Open microsoft.com/link", true)
                        .on_hover_text(&code.verification_uri)
                        .clicked()
                    {
                        ui.ctx()
                            .open_url(egui::OpenUrl::new_tab(&code.verification_uri));
                    }
                    if theme::button(ui, Kind::Secondary, None, "Copy code", true).clicked() {
                        ui.ctx().copy_text(code.user_code.clone());
                        self.notify(Status::Info("Code copied.".into()));
                    }
                    if theme::button(ui, Kind::Ghost, Some(Icon::Close), "Cancel", true).clicked() {
                        self.progress.cancel();
                    }
                });
                ui.horizontal(|ui| {
                    theme::spinner(ui, 14.0, MUTED);
                    ui.label(
                        RichText::new("Waiting for you to finish signing in…")
                            .small()
                            .color(MUTED),
                    );
                });
            });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn face_blends_the_hat_over_the_head() {
        let mut px = vec![0u8; 64 * 64 * 4];
        let set = |px: &mut Vec<u8>, x: u32, y: u32, c: [u8; 4]| {
            let i = ((y * 64 + x) * 4) as usize;
            px[i..i + 4].copy_from_slice(&c);
        };
        set(&mut px, 8, 8, [200, 0, 0, 255]); // head pixel (0,0)
        set(&mut px, 9, 8, [0, 0, 200, 255]); // head pixel (1,0)
        set(&mut px, 41, 8, [0, 255, 0, 255]); // opaque hat over (1,0)
        let f = face(&Rgba {
            width: 64,
            height: 64,
            pixels: px,
        });
        assert_eq!(&f.pixels[0..4], &[200, 0, 0, 255]);
        assert_eq!(&f.pixels[4..8], &[0, 255, 0, 255]);
    }
}
