use std::path::{Path, PathBuf};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::time::Duration;

use egui::{Color32, CornerRadius, Pos2, Rect, RichText, Sense, Stroke, StrokeKind, Vec2, pos2};
use kestrel_core::auth::{self, DeviceCode, StoredAccount};
use kestrel_core::config::{GamePreset, LauncherConfig};
use kestrel_core::discord::{self, PresenceService};
use kestrel_core::install::InstallState;
use kestrel_core::launch::{GameActivity, GameEvent};
use kestrel_core::paths::Layout;
use kestrel_core::progress::{Progress, SharedProgress};
use kestrel_core::{FORGE_VERSION, LAUNCHER_VERSION, MC_VERSION, runtime, sysmem};

use crate::backdrop;
use crate::cosmetics_page::CosmeticsPage;
use crate::icons::{self, Icon};
use crate::logger::LogBuffer;
use crate::presence::{self, Phase};
use crate::qol_page::QolPage;
use crate::skin_page::SkinPage;
use crate::theme::{self, Kind, Tone};
use crate::updater::{Summary, Updater};
use crate::worker::{self, Msg, PlayRequest};

// Colours shared with the section pages (see `theme` for the full palette).
pub(crate) use crate::theme::{ACCENT, ERR, MUTED, OK, WARN};
/// Fill for items inside cards (list rows, tiles, chips).
pub(crate) const CARD: Color32 = theme::SURFACE_HI;
const MAX_GAME_LINES: usize = 5000;
/// Below this window width the sidebar shows icons only.
const COMPACT_BELOW: f32 = 1000.0;
/// Seconds a page takes to fade in.
const PAGE_FADE: f32 = 0.18;
/// Seconds an info/success notification stays up (errors stay until dismissed).
const TOAST_SECS: f64 = 6.0;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Page {
    Home,
    Cosmetics,
    Mods,
    Settings,
    Account,
    About,
    Logs,
}

impl Page {
    const NAV: [Page; 6] = [
        Page::Home,
        Page::Cosmetics,
        Page::Mods,
        Page::Settings,
        Page::Account,
        Page::About,
    ];

    fn label(self) -> &'static str {
        match self {
            Page::Home => "Home",
            Page::Cosmetics => "Cosmetics",
            Page::Mods => "Mods",
            Page::Settings => "Settings",
            Page::Account => "Account",
            Page::About => "About",
            Page::Logs => "Logs",
        }
    }

    fn icon(self) -> Icon {
        match self {
            Page::Home => Icon::Home,
            Page::Cosmetics => Icon::Cosmetics,
            Page::Mods => Icon::Cube,
            Page::Settings => Icon::Settings,
            Page::Account => Icon::Account,
            Page::About => Icon::About,
            Page::Logs => Icon::Logs,
        }
    }

    fn subtitle(self) -> &'static str {
        match self {
            Page::Home => "",
            Page::Cosmetics => {
                "Kill effects, skies, capes and your skin, shown in your Night client."
            }
            Page::Mods => {
                "Mods, resource packs and performance presets for Night's Minecraft 1.8.9."
            }
            Page::Settings => {
                "Game window, Java and memory, updates and advanced options. Changes save automatically."
            }
            Page::Account => "Your Microsoft account for online servers, or offline mode.",
            Page::About => "Versions, where Night Launcher keeps its files, and project notes.",
            Page::Logs => "Live game output and the launcher's own log.",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SettingsTab {
    Game,
    Java,
    Updates,
    Advanced,
}

const SETTINGS_TABS: [(SettingsTab, &str); 4] = [
    (SettingsTab::Game, "Game"),
    (SettingsTab::Java, "Java & memory"),
    (SettingsTab::Updates, "Updates"),
    (SettingsTab::Advanced, "Advanced"),
];

#[derive(Clone, Copy, PartialEq, Eq)]
enum Busy {
    Idle,
    Installing,
    SigningIn,
    Launching,
    Running,
}

enum Status {
    Info(String),
    Ok(String),
    Error(String),
}

/// A notification in the bottom-right corner.
struct Toast {
    id: u64,
    tone: Tone,
    text: String,
    /// UI time it appeared (set on the first frame it is drawn).
    born: Option<f64>,
}

pub struct LauncherApp {
    layout: Layout,
    cfg: LauncherConfig,
    cfg_dirty: bool,
    /// UI time (s) when unsaved settings were first noticed; saved 1 s later.
    cfg_dirty_since: Option<f64>,
    page: Page,
    /// UI time the current page was opened (for its fade-in); `None` = this frame.
    page_since: Option<f64>,
    settings_tab: SettingsTab,
    busy: Busy,
    progress: SharedProgress,
    tx: Sender<Msg>,
    rx: Receiver<Msg>,
    install: Option<InstallState>,
    install_problems: Vec<String>,
    account: Option<StoredAccount>,
    offline_mode: bool,
    device_code: Option<DeviceCode>,
    toasts: Vec<Toast>,
    next_toast: u64,
    forge_seen: bool,
    mod_seen: bool,
    game_lines: Vec<String>,
    launcher_log: LogBuffer,
    log_tab_game: bool,
    java_probe: Option<Result<String, String>>,
    total_ram: Option<u64>,
    bundled_mod: Option<PathBuf>,
    cosmetics: CosmeticsPage,
    skin: SkinPage,
    qol: QolPage,
    updater: Updater,
    /// Discord Rich Presence (background thread; no-op until configured).
    discord: PresenceService,
    launcher_started: u64,
    game_started: Option<u64>,
    game_activity: Option<GameActivity>,
    /// Night Launcher mark (the in-game menu's artwork).
    mark: Option<egui::TextureHandle>,
    /// Backdrop animation clock: advances only while the ribbons are animating.
    backdrop_time: f32,
    /// Where the warm light sits (behind the player card on Home).
    glow: Option<(Pos2, f32)>,
    /// Height of the Home dashboard last frame (for vertical centring).
    home_h: f32,
}

impl LauncherApp {
    pub fn new(
        cc: &eframe::CreationContext<'_>,
        layout: Layout,
        launcher_log: LogBuffer,
        updated: Option<String>,
    ) -> Self {
        theme::install(&cc.egui_ctx);
        let cfg = LauncherConfig::load(&layout.launcher_config());
        let install = InstallState::load(&layout.install_state());
        let install_problems = install
            .as_ref()
            .map(|s| s.quick_verify())
            .unwrap_or_default();
        let account = auth::load_account(&layout.accounts());
        let (tx, rx) = channel();
        let layout_for_cosmetics = layout.clone();
        // Starts the background update check (never blocks start-up).
        let updater = Updater::new(layout.clone(), &cfg, &cc.egui_ctx);
        let bundled_mod = crate::worker::bundled_mod(&layout);
        // Fullbright no longer has a launcher toggle (players use a mod for it), so
        // make sure the built-in one can't stay switched on with no way off.
        if kestrel_core::cosmetics::load_state(&layout)
            .settings
            .qol
            .fullbright
        {
            match kestrel_core::cosmetics::save_fullbright(&layout, false) {
                Ok(_) => {
                    log::info!("built-in Fullbright turned off (the launcher toggle was removed)")
                }
                Err(e) => log::warn!("could not turn off built-in Fullbright: {e}"),
            }
        }
        log::info!(
            "Night Launcher {LAUNCHER_VERSION}; data dir {}; bundled mod {:?}",
            layout.root().display(),
            bundled_mod
        );
        let mark = eframe::icon_data::from_png_bytes(crate::embedded::MARK_PNG)
            .ok()
            .map(|icon| {
                cc.egui_ctx.load_texture(
                    "kestrel-mark",
                    egui::ColorImage::from_rgba_unmultiplied(
                        [icon.width as usize, icon.height as usize],
                        &icon.rgba,
                    ),
                    egui::TextureOptions::LINEAR,
                )
            });
        let mut app = LauncherApp {
            // Sign-in always works now (built-in app ID), so start in online mode.
            offline_mode: false,
            layout,
            cfg,
            cfg_dirty: false,
            cfg_dirty_since: None,
            page: Page::Home,
            page_since: None,
            settings_tab: SettingsTab::Game,
            busy: Busy::Idle,
            progress: Progress::new(),
            tx,
            rx,
            install,
            install_problems,
            account,
            device_code: None,
            toasts: Vec::new(),
            next_toast: 0,
            forge_seen: false,
            mod_seen: false,
            game_lines: Vec::new(),
            launcher_log,
            log_tab_game: true,
            java_probe: None,
            total_ram: sysmem::total_mib(),
            bundled_mod,
            qol: QolPage::new(layout_for_cosmetics.clone()),
            skin: SkinPage::new(layout_for_cosmetics.clone()),
            cosmetics: CosmeticsPage::new(layout_for_cosmetics),
            updater,
            discord: PresenceService::start(discord::system_connector()),
            launcher_started: presence::now_unix(),
            game_started: None,
            game_activity: None,
            mark,
            backdrop_time: 0.0,
            glow: None,
            home_h: 0.0,
        };
        if let Some(msg) = updated {
            app.notify(Status::Ok(msg));
        }
        app
    }

    /// Show a notification. Errors stay until dismissed; others fade after a few seconds.
    fn notify(&mut self, s: Status) {
        let (tone, text) = match s {
            Status::Info(t) => (Tone::Neutral, t),
            Status::Ok(t) => (Tone::Good, t),
            Status::Error(t) => (Tone::Bad, t),
        };
        // The same message again just refreshes the existing one.
        self.toasts.retain(|t| t.text != text);
        self.toasts.push(Toast {
            id: self.next_toast,
            tone,
            text,
            born: None,
        });
        self.next_toast += 1;
        if self.toasts.len() > 4 {
            self.toasts.remove(0);
        }
    }

    fn go(&mut self, p: Page) {
        if p == self.page {
            return;
        }
        if self.page == Page::Settings || self.page == Page::Account {
            self.save_config();
        }
        match p {
            Page::Cosmetics => {
                self.cosmetics.refresh();
                self.skin.refresh();
            }
            Page::Mods => self.qol.refresh(),
            _ => {}
        }
        self.page = p;
        self.page_since = None;
    }

    fn start(&mut self, busy: Busy, f: impl FnOnce(SharedProgress, Sender<Msg>) + Send + 'static) {
        self.save_config();
        self.busy = busy;
        self.progress = Progress::new();
        let p = self.progress.clone();
        let tx = self.tx.clone();
        if let Err(e) = std::thread::Builder::new()
            .name("kestrel-worker".into())
            .spawn(move || f(p, tx))
        {
            self.busy = Busy::Idle;
            self.notify(Status::Error(format!("could not start worker thread: {e}")));
        }
    }

    fn save_config(&mut self) {
        if !self.cfg_dirty {
            return;
        }
        self.cfg.validate();
        match self.cfg.save(&self.layout.launcher_config()) {
            Ok(()) => self.cfg_dirty = false,
            Err(e) => self.notify(Status::Error(format!("could not save settings: {e}"))),
        }
    }

    fn play(&mut self, ctx: &egui::Context) {
        // A signed release marked this version as no longer supported.
        if let Some(v) = self.updater.required_version(&self.cfg) {
            self.notify(Status::Error(format!(
                "Night Launcher {v} is a required update. Install it before playing."
            )));
            self.updater.open_prompt(ctx);
            return;
        }
        if !self.offline_mode && self.account.is_none() {
            self.go(Page::Account);
            self.notify(Status::Error(
                "Sign in with Microsoft, or enable offline mode, before playing.".into(),
            ));
            return;
        }
        self.forge_seen = false;
        self.mod_seen = false;
        self.game_lines.clear();
        self.game_started = Some(presence::now_unix());
        self.game_activity = None;
        let req = PlayRequest {
            layout: self.layout.clone(),
            cfg: self.cfg.clone(),
            account: if self.offline_mode {
                None
            } else {
                self.account.clone()
            },
        };
        let ctx = ctx.clone();
        self.start(Busy::Launching, move |p, tx| worker::play(req, p, tx, ctx));
    }

    fn repair(&mut self, ctx: &egui::Context) {
        let (layout, cfg, ctx) = (self.layout.clone(), self.cfg.clone(), ctx.clone());
        self.start(Busy::Installing, move |p, tx| {
            worker::repair(layout, cfg, p, tx, ctx)
        });
    }

    fn handle_messages(&mut self) {
        while let Ok(msg) = self.rx.try_recv() {
            match msg {
                Msg::InstallFinished(r) => match *r {
                    Ok(state) => {
                        self.install_problems = state.quick_verify();
                        self.install = Some(state);
                        self.busy = Busy::Idle;
                        self.notify(Status::Ok("Installation verified.".into()));
                    }
                    Err(e) => {
                        self.busy = Busy::Idle;
                        self.notify(Status::Error(format!("Installation failed: {e}")));
                    }
                },
                Msg::DeviceCode(c) => self.device_code = Some(c),
                Msg::SignedIn(Ok(a)) => {
                    if self.busy == Busy::SigningIn {
                        self.busy = Busy::Idle;
                        self.notify(Status::Ok(format!("Signed in as {}.", a.name)));
                        self.offline_mode = false;
                    }
                    self.device_code = None;
                    self.account = Some(a);
                }
                Msg::SignedIn(Err(e)) => {
                    self.busy = Busy::Idle;
                    self.device_code = None;
                    if e == kestrel_core::Error::Cancelled.to_string() {
                        self.notify(Status::Info("Sign-in cancelled.".into()));
                    } else {
                        self.notify(Status::Error(format!("Sign-in failed: {e}")));
                    }
                }
                Msg::Launched { log_path } => {
                    self.busy = Busy::Running;
                    self.install = InstallState::load(&self.layout.install_state());
                    self.install_problems.clear();
                    log::info!("game log: {}", log_path.display());
                    self.notify(Status::Info("Minecraft is starting…".into()));
                }
                Msg::LaunchFailed(e) => {
                    self.busy = Busy::Idle;
                    self.game_started = None;
                    self.game_activity = None;
                    self.install = InstallState::load(&self.layout.install_state());
                    self.notify(Status::Error(e));
                }
                Msg::Game(GameEvent::Line(l)) => {
                    if self.game_lines.len() >= MAX_GAME_LINES {
                        self.game_lines.drain(..MAX_GAME_LINES / 10);
                    }
                    self.game_lines.push(l);
                }
                Msg::Game(GameEvent::ForgeLoaded) => self.forge_seen = true,
                Msg::Game(GameEvent::Activity(a)) => self.game_activity = Some(a),
                Msg::Game(GameEvent::ModReady) => {
                    self.mod_seen = true;
                    self.notify(Status::Ok(
                        "Minecraft is running with the Night client.".into(),
                    ));
                    if self.cfg.close_on_launch {
                        std::process::exit(0);
                    }
                }
                Msg::Game(GameEvent::Exited(code)) => {
                    self.busy = Busy::Idle;
                    self.game_started = None;
                    self.game_activity = None;
                    let s = match code {
                        Some(0) => Status::Info("Minecraft closed.".into()),
                        c => Status::Error(format!(
                            "Minecraft exited with code {}. See Logs for details{}.",
                            c.map(|c| c.to_string()).unwrap_or_else(|| "unknown".into()),
                            if self.mod_seen {
                                ""
                            } else {
                                " (the client mod never finished loading)"
                            }
                        )),
                    };
                    self.notify(s);
                }
                Msg::JavaProbe(r) => self.java_probe = Some(r),
            }
        }
    }

    /// Push the current state to Discord (dropped when unchanged; never blocks).
    fn sync_discord(&mut self) {
        let phase = match self.busy {
            Busy::Launching => Phase::Starting,
            Busy::Running => Phase::Running(self.game_activity),
            Busy::Idle | Busy::Installing | Busy::SigningIn => Phase::Launcher,
        };
        self.discord.set(presence::desired(
            &self.cfg,
            phase,
            self.launcher_started,
            self.game_started,
        ));
    }

    /// The Microsoft account whose real skin and restricted capes apply
    /// (none in offline mode).
    fn online_uuid(&self) -> Option<String> {
        self.account
            .as_ref()
            .filter(|_| !self.offline_mode)
            .map(|a| a.uuid.clone())
    }

    fn player_name(&self) -> Option<String> {
        if self.offline_mode {
            Some(self.cfg.offline_name.clone())
        } else {
            self.account.as_ref().map(|a| a.name.clone())
        }
    }

    // ---- shell ----------------------------------------------------------------------

    fn sidebar(&mut self, ui: &mut egui::Ui, compact: bool) {
        let ctx = ui.ctx().clone();
        // Brand.
        ui.add_space(18.0);
        ui.horizontal(|ui| {
            ui.add_space(if compact { 14.0 } else { 18.0 });
            let size = if compact { 30.0 } else { 34.0 };
            let (r, _) = ui.allocate_exact_size(Vec2::splat(size), Sense::hover());
            if let Some(m) = &self.mark {
                ui.painter().image(
                    m.id(),
                    r,
                    Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)),
                    Color32::WHITE,
                );
            }
            if !compact {
                ui.add_space(4.0);
                ui.vertical(|ui| {
                    ui.add_space(2.0);
                    ui.label(theme::spaced("NIGHT", theme::bold(17.0), theme::TEXT, 2.4));
                    ui.add_space(-6.0);
                    ui.label(theme::spaced(
                        &format!("LAUNCHER · {MC_VERSION}"),
                        theme::semibold(10.0),
                        ACCENT,
                        1.4,
                    ));
                });
            }
        });
        ui.add_space(26.0);

        let item_w = ui.available_width() - if compact { 16.0 } else { 24.0 };
        let mut target = None;
        let mut selected_y = None;
        for p in Page::NAV {
            let (clicked, rect) = nav_item(ui, p, self.page == p, compact, item_w);
            if self.page == p {
                selected_y = Some(rect);
            }
            if clicked {
                target = Some(p);
            }
            ui.add_space(4.0);
        }

        // Logs and the game state at the bottom.
        let bottom_h = if compact { 64.0 } else { 98.0 };
        let space = ui.available_height() - bottom_h;
        if space > 0.0 {
            ui.add_space(space);
        }
        let (clicked, rect) = nav_item(ui, Page::Logs, self.page == Page::Logs, compact, item_w);
        if self.page == Page::Logs {
            selected_y = Some(rect);
        }
        if clicked {
            target = Some(Page::Logs);
        }
        ui.add_space(10.0);
        let (text, tone) = match self.busy {
            Busy::Running if self.mod_seen => ("Playing", Tone::Good),
            Busy::Running | Busy::Launching => ("Starting", Tone::Accent),
            Busy::Installing => ("Installing", Tone::Accent),
            Busy::SigningIn => ("Signing in", Tone::Accent),
            Busy::Idle => ("Ready", Tone::Neutral),
        };
        ui.horizontal(|ui| {
            ui.add_space(if compact { 26.0 } else { 20.0 });
            if compact {
                theme::dot(ui, tone.color());
            } else {
                theme::pill(ui, text, tone).on_hover_text("Game status");
            }
        });

        // Animated accent bar beside the selected item.
        if let Some(r) = selected_y {
            let y = ctx.animate_value_with_time(egui::Id::unique("nav-sel-y"), r.center().y, 0.18);
            let bar =
                Rect::from_center_size(pos2(ui.max_rect().left() + 3.0, y), Vec2::new(3.0, 20.0));
            ui.painter().rect_filled(bar, CornerRadius::same(2), ACCENT);
        }
        if let Some(p) = target {
            self.go(p);
        }
    }

    fn toasts_ui(&mut self, ctx: &egui::Context) {
        if self.toasts.is_empty() {
            return;
        }
        let now = ctx.input(|i| i.time);
        let mut dismiss = None;
        let mut next_expiry: Option<f64> = None;
        egui::Area::new(egui::Id::unique("kestrel-toasts"))
            .anchor(egui::Align2::RIGHT_BOTTOM, Vec2::new(-20.0, -20.0))
            .order(egui::Order::Foreground)
            .interactable(true)
            .show(ctx, |ui| {
                ui.set_max_width(380.0);
                for t in &mut self.toasts {
                    let born = *t.born.get_or_insert(now);
                    let age = (now - born) as f32;
                    let sticky = t.tone == Tone::Bad;
                    let life = TOAST_SECS as f32;
                    let fade_in = (age / 0.2).clamp(0.0, 1.0);
                    let fade_out = if sticky {
                        1.0
                    } else {
                        ((life - age) / 0.4).clamp(0.0, 1.0)
                    };
                    if !sticky {
                        let exp = born + TOAST_SECS;
                        next_expiry = Some(next_expiry.map_or(exp, |e: f64| e.min(exp)));
                        if age >= life {
                            dismiss = Some(t.id);
                        }
                    }
                    if fade_in < 1.0 || fade_out < 1.0 {
                        ctx.request_repaint_after(Duration::from_millis(16));
                    }
                    let c = t.tone.color();
                    ui.scope(|ui| {
                        ui.multiply_opacity(fade_in * fade_out);
                        ui.add_space((1.0 - fade_in) * 10.0);
                        egui::Frame::NONE
                            .fill(theme::SURFACE)
                            .stroke(Stroke::new(1.0, c.gamma_multiply(0.6)))
                            .corner_radius(CornerRadius::same(theme::RADIUS))
                            .shadow(egui::Shadow {
                                offset: [0, 6],
                                blur: 20,
                                spread: 0,
                                color: Color32::from_black_alpha(120),
                            })
                            .inner_margin(egui::Margin::symmetric(14, 12))
                            .show(ui, |ui| {
                                ui.set_width(340.0);
                                ui.horizontal_top(|ui| {
                                    icons::show(ui, t.tone.icon(), 18.0, c);
                                    // Text column of a fixed width, then the close button.
                                    ui.allocate_ui_with_layout(
                                        Vec2::new(340.0 - 18.0 - 22.0 - 16.0, 0.0),
                                        egui::Layout::top_down(egui::Align::LEFT),
                                        |ui| {
                                            ui.add(
                                                egui::Label::new(
                                                    RichText::new(&t.text).color(theme::TEXT),
                                                )
                                                .wrap_mode(egui::TextWrapMode::Wrap),
                                            );
                                        },
                                    );
                                    if theme::icon_button(ui, Icon::Close, 22.0, "Dismiss")
                                        .clicked()
                                    {
                                        dismiss = Some(t.id);
                                    }
                                });
                            });
                    });
                    ui.add_space(8.0);
                }
            });
        if let Some(id) = dismiss {
            self.toasts.retain(|t| t.id != id);
        }
        if let Some(exp) = next_expiry {
            let wait = (exp - now).max(0.0);
            ctx.request_repaint_after(Duration::from_secs_f64(wait.min(1.0)));
        }
    }

    // ---- Home --------------------------------------------------------------------------

    fn home(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        let required = self.updater.required_version(&self.cfg);
        if let Some(v) = &required {
            theme::banner(
                ui,
                Tone::Warn,
                "Update required",
                &format!(
                    "Night Launcher {v} is required to play. This version ({LAUNCHER_VERSION}) can no longer start Minecraft. \
                     Your settings, accounts, game files, mods and resource packs are kept."
                ),
            );
            ui.add_space(6.0);
            let checking = self.updater.is_checking();
            ui.horizontal(|ui| {
                if theme::button(
                    ui,
                    Kind::Primary,
                    Some(Icon::Download),
                    "Update now",
                    !checking && self.busy == Busy::Idle,
                )
                .clicked()
                {
                    self.updater.open_prompt(&ctx);
                }
                if checking {
                    theme::spinner(ui, 16.0, MUTED);
                    ui.label(theme::muted("Checking for the update…"));
                }
            });
            ui.add_space(theme::GAP);
        }

        let wide = ui.available_width() >= 600.0;
        let card_w = if ui.available_width() >= 820.0 {
            276.0
        } else {
            236.0
        };
        if wide {
            ui.horizontal_top(|ui| {
                let hero_w = ui.available_width() - card_w - theme::GAP;
                ui.vertical(|ui| {
                    ui.set_width(hero_w);
                    self.hero(ui, &ctx, required.is_some());
                });
                ui.add_space(theme::GAP);
                ui.vertical(|ui| {
                    ui.set_width(card_w);
                    self.player_card(ui, &ctx);
                });
            });
        } else {
            self.hero(ui, &ctx, required.is_some());
            ui.add_space(theme::GAP);
            self.player_card(ui, &ctx);
        }
        ui.add_space(theme::GAP + 4.0);
        self.status_tiles(ui, &ctx);
    }

    fn hero(&mut self, ui: &mut egui::Ui, ctx: &egui::Context, required: bool) {
        ui.add_space(6.0);
        ui.label(theme::spaced(
            &format!("MINECRAFT {MC_VERSION} · PVP CLIENT"),
            theme::semibold(11.0),
            ACCENT,
            1.6,
        ));
        ui.add_space(2.0);
        let greeting = match self.player_name() {
            Some(n) if !n.trim().is_empty() => format!("Welcome back, {n}"),
            _ => "Welcome to Night Launcher".to_string(),
        };
        ui.add(
            egui::Label::new(
                RichText::new(greeting)
                    .font(theme::bold(32.0))
                    .color(theme::TEXT),
            )
            .truncate(),
        );
        let (line, _) = self.install_line();
        ui.label(theme::muted(line));
        ui.add_space(22.0);

        let busy = self.busy != Busy::Idle;
        let blocked = busy || required;
        let (label, detail) = match self.busy {
            Busy::Idle if required => ("UPDATE REQUIRED", "Install the update to play".to_string()),
            Busy::Idle if self.install.is_none() => {
                ("PLAY", "Downloads and installs on first start".to_string())
            }
            Busy::Idle => (
                "PLAY",
                format!("Night Launcher {LAUNCHER_VERSION}  ·  Minecraft {MC_VERSION}"),
            ),
            Busy::Installing => ("REPAIRING", self.progress.snapshot().0),
            Busy::SigningIn => (
                "SIGNING IN",
                "Finish signing in on the Account page".to_string(),
            ),
            Busy::Launching => ("STARTING", self.progress.snapshot().0),
            Busy::Running if self.mod_seen => {
                ("RUNNING", "Minecraft is running with Night".to_string())
            }
            Busy::Running => (
                "LOADING",
                "Minecraft is loading the Night client".to_string(),
            ),
        };
        let progress = match self.busy {
            Busy::Installing | Busy::Launching => {
                let (_, done, total) = self.progress.snapshot();
                Some((total > 0).then(|| done as f32 / total as f32))
            }
            Busy::SigningIn => Some(None),
            Busy::Running if !self.mod_seen => Some(None),
            _ => None,
        };
        let w = ui.available_width().clamp(260.0, 360.0);
        if theme::play_button(ui, Vec2::new(w, 68.0), label, &detail, !blocked, progress).clicked()
        {
            self.play(ctx);
        }
        if self.busy == Busy::SigningIn && self.page == Page::Home {
            ui.add_space(4.0);
            if theme::button(
                ui,
                Kind::Ghost,
                Some(Icon::Account),
                "Open the Account page",
                true,
            )
            .clicked()
            {
                self.go(Page::Account);
            }
        }
        ui.add_space(12.0);

        match self.busy {
            Busy::Installing | Busy::Launching => {
                let (stage, done, total) = self.progress.snapshot();
                ui.horizontal(|ui| {
                    ui.label(RichText::new(stage).color(theme::TEXT));
                    if total > 0 {
                        ui.label(theme::muted(format!("{done} / {total}")));
                    }
                });
                theme::progress_bar(ui, (total > 0).then(|| done as f32 / total as f32), w);
                ui.add_space(6.0);
                if theme::button(ui, Kind::Secondary, Some(Icon::Close), "Cancel", true).clicked() {
                    self.progress.cancel();
                }
            }
            Busy::Running => {
                ui.horizontal(|ui| {
                    theme::pill(
                        ui,
                        "Forge loaded",
                        if self.forge_seen {
                            Tone::Good
                        } else {
                            Tone::Neutral
                        },
                    );
                    theme::pill(
                        ui,
                        "Night client loaded",
                        if self.mod_seen {
                            Tone::Good
                        } else {
                            Tone::Neutral
                        },
                    );
                });
            }
            _ => {
                ui.horizontal(|ui| {
                    if theme::button(
                        ui,
                        Kind::Secondary,
                        Some(Icon::Repair),
                        "Repair / verify",
                        !busy,
                    )
                    .on_hover_text(
                        "Check every game file and re-download anything missing or damaged",
                    )
                    .clicked()
                    {
                        self.repair(ctx);
                    }
                    if theme::button(ui, Kind::Ghost, Some(Icon::Folder), "Game folder", true)
                        .clicked()
                    {
                        open_path(&self.layout.instance());
                    }
                });
            }
        }
    }

    /// Install status: sentence and tone.
    fn install_line(&self) -> (&'static str, Tone) {
        match (&self.install, self.install_problems.is_empty()) {
            (None, _) => (
                "Not installed yet. Play downloads Minecraft, Forge, Java and the Night client.",
                Tone::Warn,
            ),
            (Some(_), false) => (
                "The installation has problems. Play or Repair fixes it.",
                Tone::Warn,
            ),
            (Some(_), true) => ("Installed and ready to play.", Tone::Good),
        }
    }

    fn player_card(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let uuid = self.online_uuid();
        self.skin.set_account(uuid.as_deref());
        let preview = self.skin.active_preview(ctx);
        let mut change = false;
        egui::Frame::NONE
            .fill(theme::SURFACE.gamma_multiply(0.92))
            .stroke(Stroke::new(1.0, theme::BORDER))
            .corner_radius(CornerRadius::same(theme::RADIUS))
            .inner_margin(egui::Margin::same(18))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.vertical_centered(|ui| {
                    theme::overline(ui, "YOUR PLAYER");
                    ui.add_space(8.0);
                    let size = Vec2::new(96.0, 192.0);
                    let (r, _) = ui.allocate_exact_size(
                        Vec2::new(size.x + 40.0, size.y + 16.0),
                        Sense::hover(),
                    );
                    self.glow = Some((r.center(), 230.0));
                    let fig = Rect::from_center_size(r.center() - Vec2::new(0.0, 4.0), size);
                    // Floor shadow.
                    ui.painter().add(egui::Shape::ellipse_filled(
                        pos2(fig.center().x, fig.bottom() + 4.0),
                        Vec2::new(46.0, 7.0),
                        Color32::from_black_alpha(110),
                    ));
                    match &preview {
                        Ok(tex) => {
                            // Front view: the left 16 of the preview's 36 columns.
                            ui.painter().image(
                                tex.id(),
                                fig,
                                Rect::from_min_max(pos2(0.0, 0.0), pos2(16.0 / 36.0, 1.0)),
                                Color32::WHITE,
                            );
                        }
                        Err(_) => silhouette(ui.painter(), fig),
                    }
                    if let Err(Some(m)) = &preview {
                        ui.label(
                            RichText::new(if m.contains("Sign in") {
                                "Sign in with Microsoft to see your account skin"
                            } else if m.contains("default skin") {
                                "Your account uses a default skin"
                            } else {
                                "Skin unavailable right now"
                            })
                            .small()
                            .color(theme::FAINT),
                        );
                    }
                    ui.add_space(8.0);
                    let name = self.player_name().filter(|n| !n.trim().is_empty());
                    ui.label(
                        RichText::new(name.clone().unwrap_or_else(|| "Not signed in".into()))
                            .font(theme::semibold(18.0))
                            .color(theme::TEXT),
                    );
                    ui.add_space(2.0);
                    let (acct, tone) = match (&self.account, self.offline_mode) {
                        (_, true) => ("Offline mode", Tone::Warn),
                        (Some(_), false) => ("Microsoft account", Tone::Good),
                        (None, false) => ("Not signed in", Tone::Warn),
                    };
                    theme::pill(ui, acct, tone);
                    ui.add_space(6.0);
                    ui.label(
                        RichText::new(format!("Skin: {}", self.skin.active_name()))
                            .small()
                            .color(MUTED),
                    );
                    ui.add_space(6.0);
                    if theme::button(
                        ui,
                        Kind::Secondary,
                        Some(Icon::Cosmetics),
                        "Change skin",
                        true,
                    )
                    .clicked()
                    {
                        change = true;
                    }
                });
            });
        if change {
            self.go(Page::Cosmetics);
            self.cosmetics.show_skin_tab();
        }
    }

    fn status_tiles(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let install_value = match (&self.install, self.install_problems.is_empty()) {
            (None, _) => "Not installed",
            (Some(_), false) => "Needs repair",
            (Some(_), true) => "Ready",
        };
        let install_detail = match &self.install {
            Some(s) => s
                .java_version
                .as_ref()
                .map(|j| format!("Forge {FORGE_VERSION} · Java {j}"))
                .unwrap_or_else(|| format!("Forge {FORGE_VERSION}")),
            None => "Installed on first Play".into(),
        };
        let (upd_value, upd_detail) = match self.updater.summary() {
            Summary::UpToDate => (
                "Up to date".to_string(),
                "You have the latest Night Launcher".to_string(),
            ),
            Summary::Available(v) => (
                format!("{v} available"),
                "Click to see what's new".to_string(),
            ),
            Summary::Checking => (
                "Checking…".to_string(),
                "Looking for a new version".to_string(),
            ),
            Summary::Updating => ("Updating…".to_string(), "Click to see progress".to_string()),
            Summary::Failed => ("Check failed".to_string(), "Click to try again".to_string()),
            Summary::NotConfigured => (
                "Not available".to_string(),
                "This build can't update itself".to_string(),
            ),
            Summary::Unknown => ("Not checked".to_string(), "Click to check now".to_string()),
        };
        let (acct_value, acct_detail) = match (&self.account, self.offline_mode) {
            (_, true) => (
                "Offline".to_string(),
                format!("Playing as {}", self.cfg.offline_name),
            ),
            (Some(a), false) => (a.name.clone(), "Microsoft account".to_string()),
            (None, false) => (
                "Not signed in".to_string(),
                "Sign in to play online".to_string(),
            ),
        };
        let tiles: [(Icon, &str, String, String); 4] = [
            (
                Icon::Folder,
                "INSTALLATION",
                install_value.to_string(),
                install_detail,
            ),
            (
                Icon::Clock,
                "VERSION",
                format!("v{LAUNCHER_VERSION}"),
                format!("Minecraft {MC_VERSION}"),
            ),
            (Icon::Download, "UPDATES", upd_value, upd_detail),
            (Icon::Account, "ACCOUNT", acct_value, acct_detail),
        ];
        let cols = if ui.available_width() >= 760.0 { 4 } else { 2 };
        let w = (ui.available_width() - theme::GAP * (cols as f32 - 1.0)) / cols as f32;
        let mut clicked = None;
        for row in tiles.chunks(cols) {
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = theme::GAP;
                for (icon, label, value, detail) in row {
                    if tile(ui, w, *icon, label, value, detail).clicked() {
                        clicked = Some(*label);
                    }
                }
            });
            ui.add_space(theme::GAP - 8.0);
        }
        match clicked {
            Some("INSTALLATION") => {
                if self.busy == Busy::Idle && !self.install_problems.is_empty() {
                    self.repair(ctx);
                } else {
                    open_path(&self.layout.instance());
                }
            }
            Some("VERSION") => self.go(Page::About),
            Some("UPDATES") => self.updater.show_or_check(ctx),
            Some("ACCOUNT") => self.go(Page::Account),
            _ => {}
        }
    }

    // ---- Account -----------------------------------------------------------------------

    fn account_page(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        card(ui, |ui| {
            match self.account.clone() {
                Some(a) => {
                    ui.horizontal(|ui| {
                        self.skin.set_account(Some(&a.uuid));
                        let (r, _) = ui.allocate_exact_size(Vec2::splat(56.0), Sense::hover());
                        ui.painter().rect_filled(r, CornerRadius::same(10), CARD);
                        match self.skin.active_preview(&ctx) {
                            // Face: columns 4..12, rows 0..8 of the 36x32 preview.
                            Ok(tex) if !self.offline_mode => {
                                ui.painter().image(
                                    tex.id(),
                                    r.shrink(6.0),
                                    Rect::from_min_max(
                                        pos2(4.0 / 36.0, 0.0),
                                        pos2(12.0 / 36.0, 8.0 / 32.0),
                                    ),
                                    Color32::WHITE,
                                );
                            }
                            _ => icons::paint(ui.painter(), r.shrink(14.0), Icon::Account, MUTED),
                        }
                        ui.add_space(6.0);
                        ui.vertical(|ui| {
                            ui.label(
                                RichText::new(&a.name)
                                    .font(theme::semibold(20.0))
                                    .color(theme::TEXT),
                            );
                            ui.horizontal(|ui| {
                                theme::pill(ui, "Microsoft account", Tone::Good);
                                if self.offline_mode {
                                    theme::pill(ui, "Offline mode is on", Tone::Warn);
                                }
                            });
                            ui.label(
                                RichText::new(format!("UUID {}", a.uuid))
                                    .small()
                                    .color(theme::FAINT),
                            );
                        });
                    });
                    if !kestrel_core::secure::is_supported() {
                        ui.add_space(6.0);
                        theme::banner(
                            ui,
                            Tone::Warn,
                            "",
                            "Secure storage is unavailable on this OS; you will need to sign in again next time.",
                        );
                    }
                    ui.add_space(10.0);
                    if theme::button(
                        ui,
                        Kind::Danger,
                        Some(Icon::Close),
                        "Sign out",
                        self.busy == Busy::Idle,
                    )
                    .clicked()
                    {
                        auth::forget_account(&self.layout.accounts());
                        self.account = None;
                        self.notify(Status::Info("Signed out.".into()));
                    }
                }
                None => {
                    theme::card_title(
                        ui,
                        "Sign in with Microsoft",
                        "Needed for online servers such as Hypixel. You sign in on microsoft.com in your browser; \
                         Night Launcher never sees your password.",
                    );
                    ui.add_space(4.0);
                    let client_id = auth::validate_client_id(self.cfg.msa_client_id());
                    let can = self.busy == Busy::Idle && client_id.is_ok();
                    if theme::button_sized(
                        ui,
                        Kind::Primary,
                        Some(Icon::Account),
                        "Sign in with Microsoft",
                        can,
                        220.0,
                    )
                    .clicked()
                    {
                        let (id, path, ctx) = (
                            client_id.as_ref().cloned().unwrap_or_default(),
                            self.layout.accounts(),
                            ui.ctx().clone(),
                        );
                        self.start(Busy::SigningIn, move |p, tx| {
                            worker::sign_in(id, path, p, tx, ctx)
                        });
                    }
                    if let Err(e) = &client_id {
                        ui.add_space(6.0);
                        theme::banner(ui, Tone::Warn, "", &format!("{e}"));
                    }
                }
            }
            if let Some(code) = self.device_code.clone() {
                ui.add_space(14.0);
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
                            if theme::button(
                                ui,
                                Kind::Primary,
                                None,
                                "Open microsoft.com/link",
                                true,
                            )
                            .on_hover_text(&code.verification_uri)
                            .clicked()
                            {
                                ui.ctx()
                                    .open_url(egui::OpenUrl::new_tab(&code.verification_uri));
                            }
                            if theme::button(ui, Kind::Secondary, None, "Copy code", true).clicked()
                            {
                                ui.ctx().copy_text(code.user_code.clone());
                                self.notify(Status::Info("Code copied.".into()));
                            }
                            if theme::button(ui, Kind::Ghost, Some(Icon::Close), "Cancel", true)
                                .clicked()
                            {
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
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(
                ui,
                "Offline mode",
                "For singleplayer and offline-mode servers only. Online servers (Hypixel, etc.) reject offline sessions.",
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

    // ---- Settings ----------------------------------------------------------------------

    fn settings(&mut self, ui: &mut egui::Ui) {
        let labels: Vec<&str> = SETTINGS_TABS.iter().map(|(_, n)| *n).collect();
        let current = SETTINGS_TABS
            .iter()
            .position(|(t, _)| *t == self.settings_tab)
            .unwrap_or(0);
        if let Some(i) = theme::tab_bar(ui, "settings", &labels, current) {
            self.settings_tab = SETTINGS_TABS[i].0;
        }
        ui.add_space(theme::GAP);
        let dirty = match self.settings_tab {
            SettingsTab::Game => self.settings_game(ui),
            SettingsTab::Java => self.settings_java(ui),
            SettingsTab::Updates => {
                self.updater
                    .settings_ui(ui, &mut self.cfg, self.busy != Busy::Idle)
            }
            SettingsTab::Advanced => self.settings_advanced(ui),
        };
        if dirty {
            self.cfg_dirty = true;
        }
    }

    fn settings_game(&mut self, ui: &mut egui::Ui) -> bool {
        let mut dirty = false;
        card(ui, |ui| {
            theme::card_title(
                ui,
                "Game window",
                "Size and mode of the Minecraft window when it starts.",
            );
            dirty |= ui
                .checkbox(&mut self.cfg.fullscreen, "Start in fullscreen")
                .changed();
            ui.add_enabled_ui(!self.cfg.fullscreen, |ui| {
                ui.horizontal(|ui| {
                    ui.label("Window size");
                    dirty |= ui
                        .add(
                            egui::DragValue::new(&mut self.cfg.window_width)
                                .range(640..=7680)
                                .suffix(" px"),
                        )
                        .changed();
                    ui.label("×");
                    dirty |= ui
                        .add(
                            egui::DragValue::new(&mut self.cfg.window_height)
                                .range(480..=4320)
                                .suffix(" px"),
                        )
                        .changed();
                });
                ui.horizontal_wrapped(|ui| {
                    for (w, h) in [(854, 480), (1280, 720), (1600, 900), (1920, 1080)] {
                        let on = self.cfg.window_width == w && self.cfg.window_height == h;
                        if ui.selectable_label(on, format!("{w}×{h}")).clicked() {
                            self.cfg.window_width = w;
                            self.cfg.window_height = h;
                            dirty = true;
                        }
                    }
                });
            });
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(ui, "Launcher", "");
            dirty |= ui
                .checkbox(
                    &mut self.cfg.close_on_launch,
                    "Close the launcher once the game has loaded",
                )
                .changed();
        });
        dirty
    }

    fn settings_java(&mut self, ui: &mut egui::Ui) -> bool {
        let mut dirty = false;
        card(ui, |ui| {
            theme::card_title(
                ui,
                "Memory",
                "How much RAM Minecraft may use. 1.8.9 rarely benefits from more than 3–4 GB; larger heaps can increase GC pauses.",
            );
            let upper = self
                .total_ram
                .map(|t| t * 3 / 4)
                .unwrap_or(16384)
                .clamp(1024, 16384);
            dirty |= ui
                .add(
                    egui::Slider::new(&mut self.cfg.max_ram_mib, 512..=upper)
                        .suffix(" MB")
                        .step_by(256.0),
                )
                .changed();
            ui.horizontal_wrapped(|ui| {
                for p in sysmem::presets(self.total_ram) {
                    if ui
                        .selectable_label(self.cfg.max_ram_mib == p.max_mib, p.name)
                        .clicked()
                    {
                        self.cfg.max_ram_mib = p.max_mib;
                        dirty = true;
                    }
                }
            });
            ui.label(
                RichText::new(format!(
                    "System memory: {}.",
                    self.total_ram
                        .map(|t| format!("{t} MB"))
                        .unwrap_or_else(|| "unknown".into())
                ))
                .small()
                .color(MUTED),
            );
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(ui, "Java", "Minecraft 1.8.9 with Forge needs Java 8.");
            dirty |= ui
                .radio_value(
                    &mut self.cfg.use_managed_java,
                    true,
                    "Managed Java 8 from Mojang (recommended)",
                )
                .changed();
            dirty |= ui
                .radio_value(
                    &mut self.cfg.use_managed_java,
                    false,
                    "Custom Java executable",
                )
                .changed();
            let custom = !self.cfg.use_managed_java;
            ui.add_enabled_ui(custom, |ui| {
                ui.horizontal(|ui| {
                    dirty |= ui
                        .add(
                            egui::TextEdit::singleline(&mut self.cfg.custom_java_path)
                                .desired_width(ui.available_width().min(420.0) - 90.0)
                                .hint_text("C:\\Program Files\\Java\\jre1.8.0_xxx\\bin\\java.exe"),
                        )
                        .changed();
                    if theme::button(ui, Kind::Secondary, None, "Test", custom).clicked() {
                        self.java_probe = None;
                        let (p, tx, ctx) = (
                            PathBuf::from(&self.cfg.custom_java_path),
                            self.tx.clone(),
                            ui.ctx().clone(),
                        );
                        std::thread::spawn(move || worker::probe_java(p, tx, ctx));
                    }
                });
                match &self.java_probe {
                    Some(Ok(v)) if runtime::is_java8(v) => {
                        theme::pill(ui, &format!("Java {v} — OK"), Tone::Good);
                    }
                    Some(Ok(v)) => {
                        theme::pill(
                            ui,
                            &format!("Java {v} — 1.8.9 + Forge needs Java 8"),
                            Tone::Bad,
                        );
                    }
                    Some(Err(e)) => {
                        ui.label(RichText::new(e).color(ERR));
                    }
                    None => {}
                }
            });
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(ui, "Launch arguments", "");
            dirty |= ui
                .checkbox(
                    &mut self.cfg.use_default_jvm_args,
                    "Use tuned G1 garbage collector flags",
                )
                .changed();
            ui.label("Extra JVM arguments");
            dirty |= ui
                .add(
                    egui::TextEdit::singleline(&mut self.cfg.extra_jvm_args)
                        .desired_width(ui.available_width().min(520.0))
                        .hint_text("e.g. -XX:+UseStringDeduplication"),
                )
                .changed();
        });
        dirty
    }

    fn settings_advanced(&mut self, ui: &mut egui::Ui) -> bool {
        let mut dirty = false;
        card(ui, |ui| {
            theme::card_title(
                ui,
                "Microsoft sign-in",
                "Night Launcher signs in with its own Microsoft app. Only if you registered your own app in Microsoft Entra, \
                 paste its Application (client) ID here; leave it empty to use the built-in one.",
            );
            dirty |= ui
                .add(
                    egui::TextEdit::singleline(&mut self.cfg.msa_client_id)
                        .hint_text(format!("Built in: {}", auth::BUILT_IN_CLIENT_ID))
                        .desired_width(380.0),
                )
                .changed();
            if self.cfg.msa_client_id.trim().is_empty() {
                ui.label(
                    RichText::new("Using the built-in app.")
                        .small()
                        .color(MUTED),
                );
            } else {
                match auth::validate_client_id(&self.cfg.msa_client_id) {
                    Ok(_) => {
                        ui.label(
                            RichText::new("Signs in with personal Microsoft accounts (login.microsoftonline.com/consumers).")
                                .small()
                                .color(MUTED),
                        );
                    }
                    Err(e) => {
                        ui.label(RichText::new(format!("{e}")).small().color(WARN));
                    }
                }
            }
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(
                ui,
                "Launcher renderer",
                "Takes effect when Night Launcher restarts. If the launcher window flickers or the desktop stutters while it redraws, use OpenGL.",
            );
            ui.horizontal(|ui| {
                let label = |v: &str| match v {
                    "opengl" => "OpenGL".to_string(),
                    "direct3d" => crate::graphics::Gfx::Wgpu.label().to_string(),
                    _ => format!(
                        "Automatic ({})",
                        crate::graphics::platform_default().label()
                    ),
                };
                egui::ComboBox::from_id_salt("renderer")
                    .selected_text(label(&self.cfg.renderer))
                    .width(220.0)
                    .show_ui(ui, |ui| {
                        for v in ["auto", "opengl", "direct3d"] {
                            dirty |= ui
                                .selectable_value(&mut self.cfg.renderer, v.to_string(), label(v))
                                .changed();
                        }
                    });
                if let Some(g) = crate::graphics::active() {
                    ui.label(
                        RichText::new(format!("in use: {}", g.label()))
                            .small()
                            .color(MUTED),
                    );
                }
            });
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(
                ui,
                "Discord Rich Presence",
                "Shows only: in the launcher / starting / main menu / singleplayer / multiplayer, with elapsed time. \
                 Never server addresses, world names or account details.",
            );
            dirty |= ui
                .checkbox(
                    &mut self.cfg.discord_rpc_enabled,
                    "Show Night Launcher and your game state in Discord",
                )
                .changed();
            ui.add_enabled_ui(self.cfg.discord_rpc_enabled, |ui| {
                ui.horizontal(|ui| {
                    ui.label("Discord application ID");
                    dirty |= ui
                        .add(
                            egui::TextEdit::singleline(&mut self.cfg.discord_client_id)
                                .hint_text("e.g. 123456789012345678")
                                .desired_width(220.0),
                        )
                        .changed();
                });
                let (text, color) = match (
                    discord::validate_client_id(&self.cfg.discord_client_id),
                    self.discord.status(),
                ) {
                    (Err(_), _) if self.cfg.discord_client_id.trim().is_empty() => (
                        "Not configured: create an application named \"Night Launcher\" at discord.com/developers, \
                         upload an art asset named \"kestrel\", and paste its Application ID here."
                            .to_string(),
                        MUTED,
                    ),
                    (Err(e), _) => (e, WARN),
                    (Ok(_), discord::Status::Connected) => ("Connected to Discord.".into(), OK),
                    (Ok(_), discord::Status::Waiting(m)) => (format!("{m}; retrying in the background."), MUTED),
                    (Ok(_), discord::Status::Error(m)) => (m, ERR),
                    (Ok(_), _) => ("Connecting to Discord…".into(), MUTED),
                };
                ui.label(RichText::new(text).small().color(color));
            });
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(ui, "Downloads and files", "");
            ui.horizontal(|ui| {
                ui.label("Download threads");
                dirty |= ui
                    .add(egui::Slider::new(&mut self.cfg.download_threads, 1..=16))
                    .changed();
            });
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                ui.label(theme::muted(format!(
                    "Data folder: {}",
                    self.layout.root().display()
                )));
                if theme::button(ui, Kind::Ghost, Some(Icon::Folder), "Open", true).clicked() {
                    open_path(self.layout.root());
                }
            });
        });
        dirty
    }

    // ---- Logs and About -----------------------------------------------------------------

    fn logs(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            let sel = if self.log_tab_game { 0 } else { 1 };
            if let Some(i) = theme::tab_bar(ui, "logs", &["Game output", "Launcher"], sel) {
                self.log_tab_game = i == 0;
            }
            ui.add_space(8.0);
            if theme::button(ui, Kind::Ghost, Some(Icon::Folder), "Open folder", true).clicked() {
                open_path(&self.layout.logs());
            }
            if theme::button(ui, Kind::Ghost, None, "Copy all", true).clicked() {
                let text = if self.log_tab_game {
                    self.game_lines.join("\n")
                } else {
                    self.launcher_log.snapshot().join("\n")
                };
                ui.ctx().copy_text(text);
                self.notify(Status::Info("Log copied.".into()));
            }
            if theme::button(ui, Kind::Ghost, Some(Icon::Close), "Clear", true).clicked() {
                if self.log_tab_game {
                    self.game_lines.clear();
                } else {
                    self.launcher_log.clear();
                }
            }
        });
        ui.add_space(10.0);
        let launcher_lines;
        let lines: &[String] = if self.log_tab_game {
            &self.game_lines
        } else {
            launcher_lines = self.launcher_log.snapshot();
            &launcher_lines
        };
        egui::Frame::NONE
            .fill(theme::BG2.gamma_multiply(0.94))
            .stroke(Stroke::new(1.0, theme::BORDER))
            .corner_radius(CornerRadius::same(theme::RADIUS))
            .inner_margin(egui::Margin::same(12))
            .show(ui, |ui| {
                if lines.is_empty() {
                    ui.label(theme::muted(if self.log_tab_game {
                        "Game output appears here while Minecraft runs."
                    } else {
                        "No launcher messages yet."
                    }));
                }
                let row_h = ui.text_style_height(&egui::TextStyle::Monospace);
                egui::ScrollArea::both()
                    .stick_to_bottom(true)
                    .auto_shrink([false, false])
                    .show_rows(ui, row_h, lines.len(), |ui, range| {
                        for l in &lines[range] {
                            let col = if l.contains("ERROR") || l.contains("Exception") {
                                ERR
                            } else if l.contains("WARN") {
                                WARN
                            } else {
                                Color32::from_gray(200)
                            };
                            ui.label(RichText::new(l).monospace().color(col));
                        }
                    });
            });
    }

    fn about(&mut self, ui: &mut egui::Ui) {
        card(ui, |ui| {
            ui.horizontal(|ui| {
                let (r, _) = ui.allocate_exact_size(Vec2::splat(64.0), Sense::hover());
                if let Some(m) = &self.mark {
                    ui.painter().image(
                        m.id(),
                        r,
                        Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)),
                        Color32::WHITE,
                    );
                }
                ui.add_space(8.0);
                ui.vertical(|ui| {
                    ui.add_space(6.0);
                    ui.label(theme::spaced(
                        "NIGHT LAUNCHER",
                        theme::bold(26.0),
                        theme::TEXT,
                        4.0,
                    ));
                    ui.label(theme::spaced(
                        &format!(
                            "VERSION {LAUNCHER_VERSION}  ·  MINECRAFT {MC_VERSION} PVP CLIENT"
                        ),
                        theme::semibold(11.0),
                        ACCENT,
                        1.4,
                    ));
                });
            });
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(ui, "Details", "");
            egui::Grid::new("about")
                .num_columns(2)
                .spacing([28.0, 8.0])
                .show(ui, |ui| {
                    let client_mod = match (&self.bundled_mod, crate::embedded::EMBEDDED_MOD) {
                        (Some(_), Some(e)) => {
                            format!("{} (built into NightLauncher.exe)", e.file_name)
                        }
                        (Some(p), None) => p
                            .file_name()
                            .map(|n| n.to_string_lossy().into_owned())
                            .unwrap_or_default(),
                        (None, _) => "Not bundled (development build)".into(),
                    };
                    for (k, v) in [
                        ("Launcher", LAUNCHER_VERSION.to_string()),
                        ("Minecraft", MC_VERSION.to_string()),
                        ("Forge", FORGE_VERSION.to_string()),
                        ("Client mod", client_mod),
                        (
                            "Java runtime",
                            format!(
                                "Mojang {} ({})",
                                runtime::COMPONENT,
                                runtime::platform_key()
                            ),
                        ),
                        ("Game preset", self.cfg.game_preset.label().to_string()),
                        ("Data directory", self.layout.root().display().to_string()),
                    ] {
                        ui.label(theme::muted(k));
                        ui.label(RichText::new(v).color(theme::TEXT));
                        ui.end_row();
                    }
                });
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                if theme::button(
                    ui,
                    Kind::Secondary,
                    Some(Icon::Download),
                    "Check for updates",
                    true,
                )
                .clicked()
                {
                    self.updater.show_or_check(ui.ctx());
                    self.settings_tab = SettingsTab::Updates;
                    self.go(Page::Settings);
                }
                if theme::button(ui, Kind::Ghost, Some(Icon::Folder), "Data folder", true).clicked()
                {
                    open_path(self.layout.root());
                }
            });
        });
        ui.add_space(theme::GAP);
        card(ui, |ui| {
            theme::card_title(ui, "Notes", "");
            for line in [
                "Capes, skies and kill effects are rendered on your client only. Other players do not see them \
                 (except capes the bundled catalog assigns to a specific player, which Night clients draw on that player).",
                "Night Launcher installs into its own directory and never modifies your normal .minecraft folder.",
                "Game files are downloaded from Mojang and Forge servers and verified by SHA-1 where hashes are published.",
                "Inter typeface © The Inter Project Authors, SIL Open Font License 1.1.",
                "Not affiliated with Mojang or Microsoft.",
            ] {
                ui.horizontal_top(|ui| {
                    ui.label(RichText::new("•").color(ACCENT));
                    ui.label(theme::muted(line));
                });
            }
        });
    }
}

impl eframe::App for LauncherApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.handle_messages();
        self.sync_discord();
        let ctx = ui.ctx().clone();
        if self.busy != Busy::Idle {
            ctx.request_repaint_after(Duration::from_millis(100));
        }
        let now = ctx.input(|i| i.time);
        let page_since = *self.page_since.get_or_insert(now);

        // Backdrop: ribbons flow only on Home while the window is focused and
        // visible; anywhere else the frame is frozen and nothing repaints for it.
        let (focused, minimized, dt) = ctx.input(|i| {
            (
                i.viewport().focused.unwrap_or(true),
                i.viewport().minimized.unwrap_or(false),
                i.stable_dt.min(0.1),
            )
        });
        let animate = self.page == Page::Home && focused && !minimized;
        if animate {
            self.backdrop_time += dt;
            ctx.request_repaint_after(Duration::from_millis(16));
        }
        let screen = ctx.content_rect();
        let backdrop_strength = ctx.animate_value_with_time(
            egui::Id::unique("backdrop-strength"),
            if self.page == Page::Home { 1.0 } else { 0.4 },
            0.3,
        );
        let glow = if self.page == Page::Home {
            self.glow
        } else {
            None
        };
        backdrop::paint(
            &ctx.layer_painter(egui::LayerId::background()),
            screen,
            self.backdrop_time,
            backdrop_strength,
            glow,
        );

        let compact = screen.width() < COMPACT_BELOW;
        let side_w = if compact { 76.0 } else { 224.0 };
        egui::Panel::left("nav")
            .exact_size(side_w)
            .resizable(false)
            .show_separator_line(false)
            .frame(egui::Frame::NONE.fill(theme::SIDEBAR))
            .show(ui, |ui| {
                let r = ui.max_rect();
                ui.painter().line_segment(
                    [r.right_top(), r.right_bottom()],
                    Stroke::new(1.0, theme::BORDER),
                );
                self.sidebar(ui, compact);
            });

        egui::CentralPanel::default()
            .frame(egui::Frame::NONE.inner_margin(egui::Margin {
                left: 32,
                right: 28,
                top: 26,
                bottom: 18,
            }))
            .show(ui, |ui| {
                // Page fade-in with a slight rise.
                let t = ((now - page_since) as f32 / PAGE_FADE).clamp(0.0, 1.0);
                let ease = 1.0 - (1.0 - t) * (1.0 - t);
                if t < 1.0 {
                    ctx.request_repaint_after(Duration::from_millis(16));
                }
                ui.multiply_opacity(ease);
                ui.add_space((1.0 - ease) * 8.0);
                let width = ui.available_width().min(theme::CONTENT_MAX_W);
                if self.page != Page::Home {
                    ui.scope(|ui| {
                        ui.set_max_width(width);
                        theme::page_header(ui, self.page.label(), self.page.subtitle());
                    });
                }
                if self.page == Page::Logs {
                    self.logs(ui);
                    return;
                }
                egui::ScrollArea::vertical()
                    .auto_shrink([false, false])
                    .show(ui, |ui| {
                        ui.set_max_width(width);
                        match self.page {
                            Page::Home => {
                                // On tall windows the dashboard sits a little below the
                                // top instead of leaving all the space under it.
                                let spare = ui.available_height() - self.home_h;
                                if self.home_h > 0.0 && spare > 60.0 {
                                    ui.add_space((spare * 0.3).min(160.0));
                                }
                                let top = ui.cursor().top();
                                self.home(ui);
                                self.home_h = ui.cursor().top() - top;
                            }
                            Page::Account => self.account_page(ui),
                            Page::Settings => self.settings(ui),
                            Page::Cosmetics => {
                                // Restricted catalog capes are offered only to the
                                // signed-in Microsoft account that owns them; the real
                                // account skin is previewed only for it, too.
                                let uuid = self.online_uuid();
                                self.cosmetics.set_account(uuid.as_deref());
                                self.skin.set_account(uuid.as_deref());
                                self.cosmetics
                                    .ui(ui, self.busy == Busy::Running, &mut self.skin)
                            }
                            Page::Mods => {
                                let active =
                                    self.busy != Busy::Idle && self.busy != Busy::SigningIn;
                                let cfg = &mut self.cfg;
                                let mut dirty = false;
                                self.qol.ui(ui, active, &mut |ui| {
                                    dirty |= presets_ui(ui, cfg);
                                });
                                if dirty {
                                    self.cfg_dirty = true;
                                }
                            }
                            Page::About => self.about(ui),
                            Page::Logs => {}
                        }
                        ui.add_space(12.0);
                    });
            });
        if self.page != Page::Home {
            self.glow = None;
        }

        self.toasts_ui(&ctx);

        let actions = self
            .updater
            .prompt_ui(&ctx, &mut self.cfg, self.busy != Busy::Idle);
        if actions.save_config {
            self.cfg_dirty = true;
            self.save_config();
        }
        if actions.exit {
            // The updated launcher is running; close this one.
            self.save_config();
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }

        // Debounced autosave: settings are written when you leave the page,
        // start a job, or one second after the first unsaved change.
        if self.cfg_dirty {
            let since = *self.cfg_dirty_since.get_or_insert(now);
            if now - since >= 1.0 {
                self.save_config();
            }
        }
        if self.cfg_dirty {
            ctx.request_repaint_after(Duration::from_millis(250));
        } else {
            self.cfg_dirty_since = None;
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        self.save_config();
        self.progress.cancel();
    }
}

/// Brighter accent for large text on dark surfaces.
const ACCENT_TEXT: Color32 = theme::ACCENT_HI;

/// The Performance presets tab (inside QoL). Returns true when changed.
fn presets_ui(ui: &mut egui::Ui, cfg: &mut LauncherConfig) -> bool {
    let mut dirty = false;
    ui.label(theme::muted(
        "Choosing a preset asks the client to apply it on next start. Individual settings remain adjustable in game \
         (Right Shift), and presets never reset unrelated preferences such as HUD layout or keybinds.",
    ));
    ui.add_space(8.0);
    for p in GamePreset::ALL {
        let selected = cfg.game_preset == p;
        let resp = egui::Frame::NONE
            .fill(if selected {
                ACCENT.gamma_multiply(0.12)
            } else {
                theme::SURFACE
            })
            .stroke(Stroke::new(
                1.0,
                if selected {
                    ACCENT.gamma_multiply(0.8)
                } else {
                    theme::BORDER
                },
            ))
            .corner_radius(CornerRadius::same(theme::RADIUS))
            .inner_margin(egui::Margin::same(16))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    let (r, _) = ui.allocate_exact_size(Vec2::splat(18.0), Sense::hover());
                    ui.painter().circle_stroke(
                        r.center(),
                        8.0,
                        Stroke::new(1.5, if selected { ACCENT } else { MUTED }),
                    );
                    if selected {
                        ui.painter().circle_filled(r.center(), 4.5, ACCENT);
                    }
                    ui.vertical(|ui| {
                        ui.label(
                            RichText::new(p.label())
                                .font(theme::semibold(15.0))
                                .color(theme::TEXT),
                        );
                        ui.label(theme::muted(p.description()));
                    });
                });
            })
            .response
            .interact(Sense::click());
        if resp.hovered() {
            ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
        }
        if resp.clicked() && !selected {
            cfg.game_preset = p;
            dirty = true;
        }
        ui.add_space(8.0);
    }
    dirty
}

/// One sidebar entry. Returns (clicked, rect).
fn nav_item(
    ui: &mut egui::Ui,
    page: Page,
    selected: bool,
    compact: bool,
    width: f32,
) -> (bool, Rect) {
    let h = 40.0;
    let (outer, _) = ui.allocate_exact_size(Vec2::new(ui.available_width(), h), Sense::hover());
    let r = Rect::from_center_size(outer.center(), Vec2::new(width, h));
    let resp = ui
        .interact(r, ui.scope_id().with(("nav", page.label())), Sense::click())
        .on_hover_text_at_pointer(if compact { page.label() } else { "" });
    resp.widget_info(|| egui::WidgetInfo::selected(egui::Role::Tab, true, selected, page.label()));
    let ctx = ui.ctx();
    let hover = ctx.animate_bool_with_time(resp.id.with("h"), resp.hovered(), theme::HOVER_T);
    let sel = ctx.animate_bool_with_time(resp.id.with("s"), selected, 0.18);
    if resp.hovered() {
        ctx.set_cursor_icon(egui::CursorIcon::PointingHand);
    }
    let p = ui.painter();
    let fill = theme::lerp(
        theme::lerp(Color32::TRANSPARENT, Color32::from_white_alpha(8), hover),
        theme::ACCENT_LO.gamma_multiply(0.24),
        sel,
    );
    p.rect_filled(r, CornerRadius::same(theme::RADIUS_SM), fill);
    // Purple active indicator on the left edge, growing in as the item is selected.
    if sel > 0.01 {
        let bar_h = (h - 16.0) * sel;
        let bar = Rect::from_center_size(pos2(r.left() + 2.0, r.center().y), Vec2::new(3.0, bar_h));
        p.rect_filled(bar, CornerRadius::same(2), ACCENT);
    }
    let fg = theme::lerp(theme::lerp(MUTED, theme::TEXT, hover), theme::TEXT, sel);
    let icon_c = theme::lerp(fg, ACCENT, sel);
    let icon_rect = if compact {
        Rect::from_center_size(r.center(), Vec2::splat(20.0))
    } else {
        Rect::from_center_size(pos2(r.left() + 24.0, r.center().y), Vec2::splat(19.0))
    };
    icons::paint(p, icon_rect, page.icon(), icon_c);
    if !compact {
        p.text(
            pos2(r.left() + 46.0, r.center().y),
            egui::Align2::LEFT_CENTER,
            page.label(),
            if selected {
                theme::semibold(14.0)
            } else {
                theme::regular(14.0)
            },
            fg,
        );
    }
    (resp.clicked(), r)
}

/// One dashboard status tile.
fn tile(
    ui: &mut egui::Ui,
    width: f32,
    icon: Icon,
    label: &str,
    value: &str,
    detail: &str,
) -> egui::Response {
    let (r, resp) = ui.allocate_exact_size(Vec2::new(width, 86.0), Sense::click());
    resp.widget_info(|| {
        egui::WidgetInfo::labeled(egui::Role::Button, true, format!("{label}: {value}"))
    });
    if !ui.is_rect_visible(r) {
        return resp;
    }
    let ctx = ui.ctx();
    let hover = ctx.animate_bool_with_time(resp.id.with("h"), resp.hovered(), theme::HOVER_T);
    if resp.hovered() {
        ctx.set_cursor_icon(egui::CursorIcon::PointingHand);
    }
    let p = ui.painter();
    p.rect_filled(
        r,
        CornerRadius::same(theme::RADIUS),
        theme::lerp(
            theme::SURFACE.gamma_multiply(0.92),
            theme::SURFACE_HI,
            hover,
        ),
    );
    p.rect_stroke(
        r,
        CornerRadius::same(theme::RADIUS),
        Stroke::new(
            1.0,
            theme::lerp(theme::BORDER, ACCENT.gamma_multiply(0.6), hover),
        ),
        StrokeKind::Inside,
    );
    // Neutral icon: the value text carries the state, not colour.
    let ib = Rect::from_min_size(r.min + Vec2::new(14.0, 14.0), Vec2::splat(34.0));
    p.rect_filled(
        ib,
        CornerRadius::same(9),
        theme::lerp(
            Color32::from_white_alpha(10),
            Color32::from_white_alpha(16),
            hover,
        ),
    );
    icons::paint(
        p,
        ib.shrink(8.0),
        icon,
        theme::lerp(MUTED, theme::TEXT, hover),
    );
    let tx = ib.right() + 12.0;
    let clip = p.with_clip_rect(Rect::from_min_max(
        pos2(tx, r.top()),
        pos2(r.right() - 10.0, r.bottom()),
    ));
    let label_g = clip.layout_job(theme::spaced(
        label,
        theme::semibold(10.5),
        theme::FAINT,
        1.2,
    ));
    clip.galley(pos2(tx, r.top() + 14.0), label_g, theme::FAINT);
    let max_w = r.right() - 12.0 - tx;
    clip.galley(
        pos2(tx, r.top() + 31.0),
        one_line(&clip, value, theme::semibold(15.0), theme::TEXT, max_w),
        theme::TEXT,
    );
    clip.galley(
        pos2(tx, r.top() + 54.0),
        one_line(&clip, detail, theme::regular(12.0), MUTED, max_w),
        MUTED,
    );
    resp
}

/// Single-line text cut with an ellipsis to `max_w`.
fn one_line(
    p: &egui::Painter,
    text: &str,
    font: egui::FontId,
    color: Color32,
    max_w: f32,
) -> std::sync::Arc<egui::Galley> {
    let mut job = egui::text::LayoutJob::simple_singleline(text.to_owned(), font, color);
    job.wrap = egui::text::TextWrapping::truncate_at_width(max_w);
    p.layout_job(job)
}

/// Placeholder figure while the skin loads.
fn silhouette(p: &egui::Painter, fig: Rect) {
    let u = fig.width() / 16.0;
    let c = Color32::from_white_alpha(16);
    let part = |x: f32, y: f32, w: f32, h: f32| {
        Rect::from_min_size(fig.min + Vec2::new(x * u, y * u), Vec2::new(w * u, h * u))
    };
    for r in [
        part(4.0, 0.0, 8.0, 8.0),
        part(4.0, 8.0, 8.0, 12.0),
        part(0.0, 8.0, 4.0, 12.0),
        part(12.0, 8.0, 4.0, 12.0),
        part(4.0, 20.0, 4.0, 12.0),
        part(8.0, 20.0, 4.0, 12.0),
    ] {
        p.rect_filled(r.shrink(0.5), CornerRadius::same(2), c);
    }
}

pub(crate) fn card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    theme::card(ui, add);
}

pub(crate) fn open_path(p: &Path) {
    let _ = std::fs::create_dir_all(p);
    #[cfg(windows)]
    let r = std::process::Command::new("explorer").arg(p).spawn();
    #[cfg(target_os = "macos")]
    let r = std::process::Command::new("open").arg(p).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    let r = std::process::Command::new("xdg-open").arg(p).spawn();
    if let Err(e) = r {
        log::error!("cannot open {}: {e}", p.display());
    }
}
