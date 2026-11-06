//! The Instances page (create, rename, duplicate, delete, launch, per-instance
//! account and memory) and the "Editing" picker the per-instance pages share.

use kestrel_core::instances::{CopyFrom, InstanceMeta};

use super::*;

#[derive(Default)]
pub(super) struct State {
    /// An instance file operation is running (create/duplicate/delete).
    pub(super) working: bool,
    create: Option<CreateForm>,
    /// (instance id, new name) being renamed.
    rename: Option<(String, String)>,
    /// (instance id, name for the copy).
    duplicate: Option<(String, String)>,
    confirm_delete: Option<String>,
    /// Instance whose settings panel is expanded.
    expanded: Option<String>,
}

struct CreateForm {
    name: String,
    copy_from: Option<String>,
    copy_settings: bool,
    copy_mods: bool,
    copy_packs: bool,
    account: AccountChoice,
    game: GameSettings,
}

/// "3 min ago" style time.
fn ago(then: u64) -> String {
    let d = auth::now_secs().saturating_sub(then);
    match d {
        0..=59 => "just now".into(),
        60..=3599 => format!("{} min ago", d / 60),
        3600..=86_399 => format!("{} h ago", d / 3600),
        _ => format!("{} days ago", d / 86_400),
    }
}

impl LauncherApp {
    /// "Editing: <instance>" row shown on per-instance pages.
    pub(super) fn instance_picker(&mut self, ui: &mut egui::Ui, note: &str) {
        let mut pick = None;
        egui::Frame::NONE
            .fill(theme::SURFACE.gamma_multiply(0.85))
            .stroke(Stroke::new(1.0, theme::BORDER))
            .corner_radius(CornerRadius::same(theme::RADIUS))
            .inner_margin(egui::Margin::symmetric(14, 10))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    icons::show(ui, Icon::Layers, 18.0, ACCENT);
                    ui.label(theme::strong("Editing instance"));
                    egui::ComboBox::from_id_salt("editing-instance")
                        .selected_text(self.editing_instance().meta.name.clone())
                        .width(220.0)
                        .show_ui(ui, |ui| {
                            for i in &self.instances {
                                let label = if self.runs.contains_key(&i.id) {
                                    format!("{}  ·  running", i.meta.name)
                                } else {
                                    i.meta.name.clone()
                                };
                                if ui.selectable_label(i.id == self.editing, label).clicked() {
                                    pick = Some(i.id.clone());
                                }
                            }
                        });
                    if self.runs.contains_key(&self.editing) {
                        theme::pill(ui, "Running", Tone::Good)
                            .on_hover_text("Some changes apply on this instance's next start");
                    }
                });
                ui.label(RichText::new(note).small().color(MUTED));
            });
        if let Some(id) = pick {
            self.set_editing(&id);
        }
    }

    fn account_combo(&self, ui: &mut egui::Ui, salt: &str, choice: &mut AccountChoice) -> bool {
        let mut changed = false;
        egui::ComboBox::from_id_salt(salt)
            .selected_text(self.account_label(choice))
            .width(260.0)
            .show_ui(ui, |ui| {
                let active = AccountChoice::Active;
                if ui
                    .selectable_label(*choice == active, self.account_label(&active))
                    .on_hover_text("Follows the account you switch to in the launcher or in game")
                    .clicked()
                {
                    *choice = active;
                    changed = true;
                }
                for a in &self.accounts {
                    let c = AccountChoice::Account {
                        uuid: a.uuid.clone(),
                    };
                    let label = if a.needs_sign_in {
                        format!("{} (sign in again)", a.name)
                    } else {
                        a.name.clone()
                    };
                    if ui.selectable_label(*choice == c, label).clicked() {
                        *choice = c;
                        changed = true;
                    }
                }
                let off = AccountChoice::Offline;
                if ui
                    .selectable_label(*choice == off, self.account_label(&off))
                    .on_hover_text("Singleplayer and offline-mode servers only")
                    .clicked()
                {
                    *choice = off;
                    changed = true;
                }
            });
        changed
    }

    fn memory_slider(&self, ui: &mut egui::Ui, mib: &mut u64) -> bool {
        let upper = self
            .total_ram
            .map(|t| t * 3 / 4)
            .unwrap_or(16384)
            .clamp(1024, 16384);
        ui.add(
            egui::Slider::new(mib, 512..=upper)
                .suffix(" MB")
                .step_by(256.0),
        )
        .changed()
    }

    pub(super) fn instances_page(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        let working = self.instances_ui.working;
        ui.horizontal(|ui| {
            if theme::button(
                ui,
                Kind::Primary,
                Some(Icon::Plus),
                "New instance",
                !working,
            )
            .clicked()
            {
                self.instances_ui.create = Some(CreateForm {
                    name: String::new(),
                    copy_from: Some(self.cfg.default_instance.clone()),
                    copy_settings: true,
                    copy_mods: false,
                    copy_packs: false,
                    account: AccountChoice::Active,
                    game: self
                        .instance(&self.cfg.default_instance)
                        .map(|i| i.meta.game.clone())
                        .unwrap_or_default(),
                });
            }
            if theme::button(
                ui,
                Kind::Ghost,
                Some(Icon::Folder),
                "Instances folder",
                true,
            )
            .clicked()
            {
                let d = self.layout.instances_dir();
                let _ = std::fs::create_dir_all(&d);
                open_path(&d);
            }
            if working {
                theme::spinner(ui, 16.0, MUTED);
                ui.label(theme::muted("Working…"));
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let running = self.runs.len();
                ui.label(theme::muted(format!(
                    "{} instances · {running} running",
                    self.instances.len()
                )));
            });
        });
        ui.add_space(theme::GAP);

        if self.instances_ui.create.is_some() {
            self.create_form(ui);
            ui.add_space(theme::GAP);
        }

        let list = self.instances.clone();
        for inst in &list {
            self.instance_card(ui, &ctx, inst);
            ui.add_space(theme::GAP - 2.0);
        }
        ui.add_space(4.0);
        ui.label(
            RichText::new(
                "Each instance has its own game folder: mods, resource packs, options and keybinds, worlds, screenshots, \
                 logs and Night cosmetics. Minecraft, Forge, Java and your saved accounts are shared. \
                 Deleted instances go to the instances-trash folder and can be restored by moving them back.",
            )
            .small()
            .color(theme::FAINT),
        );
    }

    fn create_form(&mut self, ui: &mut egui::Ui) {
        let Some(mut f) = self.instances_ui.create.take() else {
            return;
        };
        let mut close = false;
        let mut submit = false;
        card(ui, |ui| {
            theme::card_title(ui, "New instance", "");
            egui::Grid::new("create-instance")
                .num_columns(2)
                .spacing([18.0, 10.0])
                .show(ui, |ui| {
                    ui.label(theme::muted("Name"));
                    let r = ui.add(
                        egui::TextEdit::singleline(&mut f.name)
                            .hint_text("e.g. Hypixel PvP")
                            .char_limit(40)
                            .desired_width(280.0),
                    );
                    if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                        submit = true;
                    }
                    ui.end_row();

                    ui.label(theme::muted("Version"));
                    ui.horizontal(|ui| {
                        egui::ComboBox::from_id_salt("create-version")
                            .selected_text(format!("Minecraft {MC_VERSION}"))
                            .width(180.0)
                            .show_ui(ui, |ui| {
                                let _ = ui.selectable_label(true, format!("Minecraft {MC_VERSION}"));
                            });
                        ui.label(
                            RichText::new("The only version Night supports so far.")
                                .small()
                                .color(MUTED),
                        );
                    });
                    ui.end_row();

                    ui.label(theme::muted("Client"));
                    ui.label(RichText::new(format!("Forge {FORGE_VERSION} + Night client")).color(theme::TEXT));
                    ui.end_row();

                    ui.label(theme::muted("Account"));
                    let mut acct = f.account.clone();
                    if self.account_combo(ui, "create-account", &mut acct) {
                        f.account = acct;
                    }
                    ui.end_row();

                    ui.label(theme::muted("Memory"));
                    let mut mib = f.game.max_ram_mib;
                    if self.memory_slider(ui, &mut mib) {
                        f.game.max_ram_mib = mib;
                    }
                    ui.end_row();

                    ui.label(theme::muted("Java"));
                    ui.vertical(|ui| {
                        ui.radio_value(&mut f.game.use_managed_java, true, "Managed Java 8 (recommended)");
                        ui.radio_value(&mut f.game.use_managed_java, false, "Custom Java 8 executable");
                        if !f.game.use_managed_java {
                            ui.add(
                                egui::TextEdit::singleline(&mut f.game.custom_java_path)
                                    .desired_width(320.0)
                                    .hint_text("C:\\Program Files\\Java\\jre1.8.0_xxx\\bin\\java.exe"),
                            );
                        }
                    });
                    ui.end_row();

                    ui.label(theme::muted("Copy from"));
                    ui.vertical(|ui| {
                        let label = f
                            .copy_from
                            .as_deref()
                            .map(|id| self.instance_name(id))
                            .unwrap_or_else(|| "Nothing (fresh instance)".into());
                        egui::ComboBox::from_id_salt("create-copy")
                            .selected_text(label)
                            .width(240.0)
                            .show_ui(ui, |ui| {
                                if ui.selectable_label(f.copy_from.is_none(), "Nothing (fresh instance)").clicked() {
                                    f.copy_from = None;
                                }
                                for i in &self.instances {
                                    if ui
                                        .selectable_label(f.copy_from.as_deref() == Some(&i.id), &i.meta.name)
                                        .clicked()
                                    {
                                        f.copy_from = Some(i.id.clone());
                                    }
                                }
                            });
                        ui.add_enabled_ui(f.copy_from.is_some(), |ui| {
                            ui.checkbox(&mut f.copy_settings, "Settings: options, keybinds, mod configs, Night client settings and cosmetics, server list");
                            ui.checkbox(&mut f.copy_mods, "Mods (enabled and disabled)");
                            ui.checkbox(&mut f.copy_packs, "Resource packs");
                        });
                    });
                    ui.end_row();
                });
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                let ok = !f.name.trim().is_empty() && !self.instances_ui.working;
                if theme::button(ui, Kind::Primary, Some(Icon::Plus), "Create", ok).clicked() {
                    submit = true;
                }
                if theme::button(ui, Kind::Ghost, None, "Cancel", true).clicked() {
                    close = true;
                }
            });
        });
        if submit && !f.name.trim().is_empty() {
            let name = match instances::clean_name(&f.name) {
                Ok(n) => n,
                Err(e) => {
                    self.notify(Status::Error(e.to_string()));
                    self.instances_ui.create = Some(f);
                    return;
                }
            };
            let source = f
                .copy_from
                .as_deref()
                .and_then(|id| self.instance(id))
                .cloned();
            if let Some(src) = &source
                && src.id == self.editing
            {
                self.save_game();
            }
            let copy = CopyFrom {
                source,
                settings: f.copy_settings,
                mods: f.copy_mods,
                resource_packs: f.copy_packs,
            };
            let meta = InstanceMeta {
                account: f.account.clone(),
                game: f.game.clone(),
                ..InstanceMeta::default()
            };
            let layout = self.layout.clone();
            self.instances_ui.working = true;
            worker::instance_op(
                move || {
                    let i = instances::create(&layout, &name, meta, &copy)?;
                    Ok(format!("Created {}.", i.meta.name))
                },
                self.tx.clone(),
                ui.ctx().clone(),
            );
            return;
        }
        if !close {
            self.instances_ui.create = Some(f);
        }
    }

    fn instance_card(&mut self, ui: &mut egui::Ui, ctx: &egui::Context, inst: &Instance) {
        let id = inst.id.clone();
        let is_default = self.cfg.default_instance == id;
        let run = self.runs.get(&id);
        let (status, tone) = match run {
            Some(r) if r.stopping => ("Stopping".to_string(), Tone::Warn),
            Some(r) if r.mod_seen => (
                match r.pid() {
                    Some(p) => format!("Running · PID {p}"),
                    None => "Running".into(),
                },
                Tone::Good,
            ),
            Some(r) if r.running() => ("Loading".to_string(), Tone::Accent),
            Some(_) => ("Starting".to_string(), Tone::Accent),
            None => match inst.meta.last_exit {
                Some(c) if c != 0 => (format!("Stopped · last run exited with {c}"), Tone::Warn),
                _ => ("Stopped".to_string(), Tone::Neutral),
            },
        };
        let running = run.is_some();
        let player = run.map(|r| r.player.clone()).filter(|p| !p.is_empty());
        let working = self.instances_ui.working;
        let mut play = false;
        let mut stop = false;
        egui::Frame::NONE
            .fill(if running {
                ACCENT.gamma_multiply(0.08)
            } else {
                theme::SURFACE.gamma_multiply(0.92)
            })
            .stroke(Stroke::new(
                1.0,
                if running {
                    ACCENT.gamma_multiply(0.55)
                } else {
                    theme::BORDER
                },
            ))
            .corner_radius(CornerRadius::same(theme::RADIUS))
            .inner_margin(egui::Margin::same(14))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    let (r, _) = ui.allocate_exact_size(Vec2::splat(44.0), Sense::hover());
                    ui.painter().rect_filled(r, CornerRadius::same(10), CARD);
                    icons::paint(
                        ui.painter(),
                        r.shrink(11.0),
                        Icon::Layers,
                        if running { OK } else { ACCENT },
                    );
                    ui.add_space(6.0);
                    ui.vertical(|ui| {
                        ui.horizontal(|ui| {
                            if let Some((rid, name)) = &mut self.instances_ui.rename
                                && *rid == id
                            {
                                let r = ui.add(egui::TextEdit::singleline(name).char_limit(40).desired_width(220.0));
                                let enter = r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                                let save = theme::button(ui, Kind::Primary, Some(Icon::Check), "Save", true).clicked() || enter;
                                let cancel = theme::button(ui, Kind::Ghost, None, "Cancel", true).clicked();
                                if save {
                                    let new = name.clone();
                                    self.instances_ui.rename = None;
                                    match instances::clean_name(&new) {
                                        Ok(n) => self.update_instance(&id, |i| i.meta.name = n),
                                        Err(e) => self.notify(Status::Error(e.to_string())),
                                    }
                                } else if cancel {
                                    self.instances_ui.rename = None;
                                }
                            } else {
                                ui.label(
                                    RichText::new(&inst.meta.name)
                                        .font(theme::semibold(18.0))
                                        .color(theme::TEXT),
                                );
                                if is_default {
                                    theme::pill(ui, "Default", Tone::Accent)
                                        .on_hover_text("Play on the Home page starts this instance");
                                }
                                theme::pill(ui, &status, tone);
                            }
                        });
                        let version = if inst.supported() {
                            format!("Minecraft {} · Forge + Night", inst.meta.mc_version)
                        } else {
                            format!("Minecraft {} · {} (not supported)", inst.meta.mc_version, inst.meta.loader)
                        };
                        let account = match &player {
                            Some(p) => format!("Playing as {p}"),
                            None => format!("Account: {}", self.account_label(&inst.meta.account)),
                        };
                        let last = inst
                            .meta
                            .last_launch
                            .map(|t| format!("Last played {}", ago(t)))
                            .unwrap_or_else(|| "Never played".into());
                        ui.label(theme::muted(format!("{version}  ·  {account}  ·  {last}")));
                    });
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if running {
                            stop = theme::button(ui, Kind::Secondary, Some(Icon::Stop), "Stop", true)
                                .on_hover_text("Closes this instance's game only")
                                .clicked();
                        } else {
                            play = theme::button(
                                ui,
                                Kind::Primary,
                                Some(Icon::Play),
                                "Play",
                                self.busy != Busy::Installing && inst.supported(),
                            )
                            .clicked();
                        }
                    });
                });
                ui.add_space(8.0);
                ui.horizontal_wrapped(|ui| {
                    let expanded = self.instances_ui.expanded.as_deref() == Some(id.as_str());
                    if theme::button(ui, Kind::Ghost, Some(Icon::Settings), if expanded { "Hide settings" } else { "Settings" }, true).clicked() {
                        self.instances_ui.expanded = if expanded { None } else { Some(id.clone()) };
                    }
                    if !is_default
                        && theme::button(ui, Kind::Ghost, Some(Icon::Star), "Set as default", true).clicked()
                    {
                        self.cfg.default_instance = id.clone();
                        self.cfg_dirty = true;
                        self.save_config();
                        self.notify(Status::Ok(format!("Play now starts {}.", inst.meta.name)));
                    }
                    if theme::button(ui, Kind::Ghost, Some(Icon::Edit), "Rename", self.instances_ui.rename.is_none()).clicked() {
                        self.instances_ui.rename = Some((id.clone(), inst.meta.name.clone()));
                    }
                    if theme::button(ui, Kind::Ghost, Some(Icon::Copy), "Duplicate", !working && !running)
                        .on_hover_text(if running { "Close the game first" } else { "Copy everything, including worlds" })
                        .clicked()
                    {
                        self.instances_ui.duplicate = Some((id.clone(), format!("{} copy", inst.meta.name)));
                    }
                    if theme::button(ui, Kind::Ghost, Some(Icon::Folder), "Folder", true).clicked() {
                        open_path(&inst.game_dir);
                    }
                    if theme::button(ui, Kind::Ghost, Some(Icon::Cosmetics), "Cosmetics", true).clicked() {
                        self.set_editing(&id);
                        self.go(Page::Cosmetics);
                    }
                    if theme::button(ui, Kind::Ghost, Some(Icon::Cube), "Mods", true).clicked() {
                        self.set_editing(&id);
                        self.go(Page::Mods);
                    }
                    if !inst.is_default()
                        && theme::button(ui, Kind::Ghost, Some(Icon::Trash), "Delete", !working && !running)
                            .on_hover_text(if running { "Close the game first" } else { "Moves it to instances-trash" })
                            .clicked()
                        {
                            self.instances_ui.confirm_delete = Some(id.clone());
                        }
                });

                // Duplicate: name the copy.
                if let Some((did, name)) = &mut self.instances_ui.duplicate
                    && *did == id
                {
                    ui.add_space(6.0);
                    let mut go = false;
                    let mut cancel = false;
                    ui.horizontal(|ui| {
                        ui.label(theme::muted("Name of the copy"));
                        ui.add(egui::TextEdit::singleline(name).char_limit(40).desired_width(220.0));
                        go = theme::button(ui, Kind::Primary, Some(Icon::Copy), "Duplicate", !name.trim().is_empty()).clicked();
                        cancel = theme::button(ui, Kind::Ghost, None, "Cancel", true).clicked();
                    });
                    if go {
                        let name = name.clone();
                        self.instances_ui.duplicate = None;
                        if id == self.editing {
                            self.save_game();
                        }
                        let (layout, src) = (self.layout.clone(), self.instance(&id).cloned());
                        if let Some(src) = src {
                            self.instances_ui.working = true;
                            worker::instance_op(
                                move || {
                                    let i = instances::duplicate(&layout, &src, &name)?;
                                    Ok(format!("Duplicated {} as {}.", src.meta.name, i.meta.name))
                                },
                                self.tx.clone(),
                                ctx.clone(),
                            );
                        }
                    } else if cancel {
                        self.instances_ui.duplicate = None;
                    }
                }

                // Delete: confirm.
                if self.instances_ui.confirm_delete.as_deref() == Some(id.as_str()) {
                    ui.add_space(6.0);
                    theme::banner(
                        ui,
                        Tone::Warn,
                        &format!("Delete {}?", inst.meta.name),
                        "Its folder (worlds, mods, settings) moves to instances-trash in the data folder. \
                         Nothing is erased; move it back into instances to restore it.",
                    );
                    ui.horizontal(|ui| {
                        if theme::button(ui, Kind::Danger, Some(Icon::Trash), "Delete", !working && !running).clicked() {
                            self.instances_ui.confirm_delete = None;
                            let (layout, victim) = (self.layout.clone(), inst.clone());
                            self.instances_ui.working = true;
                            worker::instance_op(
                                move || {
                                    let to = instances::delete(&layout, &victim)?;
                                    Ok(format!("Moved {} to {}.", victim.meta.name, to.display()))
                                },
                                self.tx.clone(),
                                ctx.clone(),
                            );
                        }
                        if theme::button(ui, Kind::Ghost, None, "Keep it", true).clicked() {
                            self.instances_ui.confirm_delete = None;
                        }
                    });
                }

                if self.instances_ui.expanded.as_deref() == Some(id.as_str()) {
                    ui.add_space(8.0);
                    self.instance_settings(ui, inst);
                }
            });
        if play {
            self.play_instance(&id, ctx);
        }
        if stop {
            self.stop_instance(&id);
        }
    }

    /// The quick settings panel inside an instance card.
    fn instance_settings(&mut self, ui: &mut egui::Ui, inst: &Instance) {
        let id = inst.id.clone();
        let mut meta = inst.meta.clone();
        if id == self.editing {
            meta.game = self.game.clone(); // include unsaved edits
        }
        let mut changed = false;
        egui::Frame::NONE
            .fill(CARD)
            .corner_radius(CornerRadius::same(theme::RADIUS_SM))
            .inner_margin(egui::Margin::same(12))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                egui::Grid::new(format!("inst-settings-{id}"))
                    .num_columns(2)
                    .spacing([18.0, 10.0])
                    .show(ui, |ui| {
                        ui.label(theme::muted("Account"));
                        ui.vertical(|ui| {
                            changed |= self.account_combo(ui, &format!("acct-{id}"), &mut meta.account);
                            if self.runs.contains_key(&id) {
                                ui.label(
                                    RichText::new("The running game keeps its account; this applies on the next start.")
                                        .small()
                                        .color(MUTED),
                                );
                            }
                        });
                        ui.end_row();
                        ui.label(theme::muted("Memory"));
                        changed |= self.memory_slider(ui, &mut meta.game.max_ram_mib);
                        ui.end_row();
                        ui.label(theme::muted("Window"));
                        ui.horizontal(|ui| {
                            changed |= ui.checkbox(&mut meta.game.fullscreen, "Fullscreen").changed();
                            ui.add_enabled_ui(!meta.game.fullscreen, |ui| {
                                changed |= ui
                                    .add(egui::DragValue::new(&mut meta.game.window_width).range(640..=7680).suffix(" px"))
                                    .changed();
                                ui.label("×");
                                changed |= ui
                                    .add(egui::DragValue::new(&mut meta.game.window_height).range(480..=4320).suffix(" px"))
                                    .changed();
                            });
                        });
                        ui.end_row();
                        ui.label(theme::muted("Java"));
                        ui.label(RichText::new(if meta.game.use_managed_java {
                            "Managed Java 8".to_string()
                        } else {
                            format!("Custom: {}", meta.game.custom_java_path)
                        }).color(theme::TEXT));
                        ui.end_row();
                    });
                ui.add_space(6.0);
                if theme::button(ui, Kind::Ghost, Some(Icon::Settings), "All settings for this instance", true).clicked() {
                    self.set_editing(&id);
                    self.settings_tab = SettingsTab::Java;
                    self.go(Page::Settings);
                }
            });
        if changed {
            if id == self.editing {
                self.game = meta.game.clone();
            }
            self.update_instance(&id, |i| i.meta = meta);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ago_is_readable() {
        let now = auth::now_secs();
        assert_eq!(ago(now), "just now");
        assert_eq!(ago(now - 125), "2 min ago");
        assert_eq!(ago(now - 7200), "2 h ago");
        assert_eq!(ago(now - 3 * 86_400), "3 days ago");
    }
}
