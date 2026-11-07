//! The first thing the window shows: moving data from the Kestrel-era folder
//! when needed (`kestrel_core::datadir`), then the launcher itself.

use std::path::PathBuf;
use std::sync::mpsc::{Receiver, channel};

use eframe::egui::{self, RichText, Vec2};
use kestrel_core::datadir;
use kestrel_core::paths::Layout;
use kestrel_core::progress::{Progress, SharedProgress};

use crate::app::LauncherApp;
use crate::logger::LogBuffer;
use crate::theme::{self, Kind, Tone};

enum Stage {
    Migrating {
        progress: SharedProgress,
        rx: Receiver<Result<datadir::Report, String>>,
    },
    Failed(String),
    Ready(Box<LauncherApp>),
}

pub struct Startup {
    stage: Stage,
    /// The folder copied from and the folder being copied to.
    from: PathBuf,
    to: PathBuf,
    buffer: LogBuffer,
    args: Vec<String>,
    health: Option<PathBuf>,
}

/// Create the folders a fresh install needs; never fatal (each writer also
/// creates its own folder).
pub fn prepare(layout: &Layout) {
    if let Err(e) = layout.ensure() {
        log::warn!("could not create data folders: {e}");
    }
}

/// Build the launcher for `layout` once its data folder is settled.
pub fn launcher(
    ctx: &egui::Context,
    layout: Layout,
    legacy: Option<PathBuf>,
    buffer: LogBuffer,
    args: &[String],
    health: Option<PathBuf>,
) -> LauncherApp {
    prepare(&layout);
    let updated = crate::updater::on_startup(&layout, args, health);
    LauncherApp::new(ctx, layout, buffer, updated, legacy)
}

impl Startup {
    pub fn migrate(
        ctx: &egui::Context,
        from: PathBuf,
        to: PathBuf,
        buffer: LogBuffer,
        args: Vec<String>,
        health: Option<PathBuf>,
    ) -> Self {
        theme::install(ctx);
        let mut s = Startup {
            stage: Stage::Failed(String::new()),
            from,
            to,
            buffer,
            args,
            health,
        };
        s.start(ctx);
        s
    }

    fn start(&mut self, ctx: &egui::Context) {
        let progress = Progress::new();
        let (tx, rx) = channel();
        let (from, to, p, ctx2) = (
            self.from.clone(),
            self.to.clone(),
            progress.clone(),
            ctx.clone(),
        );
        log::info!("moving data from {} to {}", from.display(), to.display());
        let spawned = std::thread::Builder::new()
            .name("migration".into())
            .spawn(move || {
                let r = datadir::migrate(&from, &to, &p).map_err(|e| e.to_string());
                let _ = tx.send(r);
                ctx2.request_repaint();
            });
        self.stage = match spawned {
            Ok(_) => Stage::Migrating { progress, rx },
            Err(e) => Stage::Failed(format!("could not start copying: {e}")),
        };
    }

    fn finish(&mut self, ctx: &egui::Context, layout: Layout, legacy: Option<PathBuf>) {
        let app = launcher(
            ctx,
            layout,
            legacy,
            self.buffer.clone(),
            &self.args,
            self.health.take(),
        );
        self.stage = Stage::Ready(Box::new(app));
    }
}

impl eframe::App for Startup {
    fn ui(&mut self, ui: &mut egui::Ui, frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        if let Stage::Ready(app) = &mut self.stage {
            app.ui(ui, frame);
            return;
        }
        if let Stage::Migrating { rx, .. } = &self.stage
            && let Ok(r) = rx.try_recv()
        {
            match r {
                Ok(report) => {
                    log::info!(
                        "data moved: {} files, {} MB; old folder kept at {}",
                        report.files,
                        report.bytes / (1024 * 1024),
                        self.from.display()
                    );
                    let (to, from) = (self.to.clone(), self.from.clone());
                    self.finish(&ctx, Layout::new(to), Some(from));
                    return;
                }
                Err(e) => {
                    log::error!("moving data failed: {e}");
                    self.stage = Stage::Failed(e);
                }
            }
        }

        let mut retry = false;
        let mut use_old = false;
        crate::backdrop::paint(
            &ctx.layer_painter(egui::LayerId::background()),
            ctx.content_rect(),
            0.0,
            0.6,
            None,
        );
        egui::CentralPanel::default()
            .frame(egui::Frame::NONE.inner_margin(egui::Margin::same(40)))
            .show(ui, |ui| {
                ui.vertical_centered(|ui| {
                    ui.add_space((ui.available_height() * 0.22).max(20.0));
                    ui.label(
                        RichText::new("Moving your data to the Night Launcher folder")
                            .font(theme::bold(26.0))
                            .color(theme::TEXT),
                    );
                    ui.add_space(6.0);
                    ui.label(theme::muted(format!(
                        "From {}\nto {}",
                        self.from.display(),
                        self.to.display()
                    )));
                    ui.add_space(4.0);
                    ui.label(theme::muted(
                        "Everything is copied and checked; the old folder is kept untouched as a backup.",
                    ));
                    ui.add_space(22.0);
                    let w = ui.available_width().min(520.0);
                    match &self.stage {
                        Stage::Migrating { progress, .. } => {
                            let (stage, done, total) = progress.snapshot();
                            ui.label(RichText::new(stage).color(theme::TEXT));
                            ui.add_space(6.0);
                            theme::progress_bar(ui, (total > 0).then(|| done as f32 / total as f32), w);
                            if total > 0 {
                                ui.label(theme::muted(format!(
                                    "{} / {} MB",
                                    done / (1024 * 1024),
                                    total / (1024 * 1024)
                                )));
                            }
                            ctx.request_repaint_after(std::time::Duration::from_millis(100));
                        }
                        Stage::Failed(e) => {
                            ui.allocate_ui(Vec2::new(w, 0.0), |ui| {
                                theme::banner(
                                    ui,
                                    Tone::Warn,
                                    "The data wasn't moved",
                                    &format!(
                                        "{e}\n\nNothing was lost: your data is still in the old folder. \
                                         Night Launcher tries again next time it starts."
                                    ),
                                );
                            });
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                ui.add_space(((ui.available_width() - 420.0) / 2.0).max(0.0));
                                retry = theme::button(ui, Kind::Primary, None, "Try again", true).clicked();
                                use_old = theme::button(ui, Kind::Secondary, None, "Use the old folder for now", true)
                                    .on_hover_text("Starts Night Launcher with your data where it is; the move is retried next start")
                                    .clicked();
                            });
                        }
                        Stage::Ready(_) => {}
                    }
                });
            });
        if retry {
            self.start(&ctx);
        } else if use_old {
            log::warn!(
                "using the old data folder {} for this session",
                self.from.display()
            );
            let from = self.from.clone();
            self.finish(&ctx, Layout::new(from), None);
        }
    }

    fn on_exit(&mut self, gl: Option<&eframe::glow::Context>) {
        if let Stage::Ready(app) = &mut self.stage {
            app.on_exit(gl);
        }
        if let Stage::Migrating { progress, .. } = &self.stage {
            // The copy resumes next start; stop reading files now.
            progress.cancel();
        }
    }
}
