// Hide the console window in release builds on Windows.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod app;
mod backdrop;
mod bundled_previews;
mod cosmetics_page;
mod embedded;
mod fx_previews;
mod graphics;
mod icons;
mod logger;
mod presence;
mod qol_page;
mod redeem_page;
mod selfcheck;
mod skin_page;
mod startup;
mod theme;
mod updater;
mod worker;

use std::path::PathBuf;

use kestrel_core::datadir;
use kestrel_core::paths::Layout;

/// `--root <dir>` from the command line, if given.
fn root_arg(args: &[String]) -> Option<PathBuf> {
    args.iter()
        .position(|a| a == "--root")
        .and_then(|i| args.get(i + 1))
        .map(PathBuf::from)
}

/// Where the data lives (see `kestrel_core::datadir`). A relative `--root` is
/// made absolute: the game runs with its instance folder as the working
/// directory, so a relative game dir would resolve twice.
fn root_plan(args: &[String]) -> datadir::RootPlan {
    datadir::plan(root_arg(args).map(absolute_root))
}

fn absolute_root(root: PathBuf) -> PathBuf {
    // Falls back to the path as given if the current directory is unavailable.
    std::path::absolute(&root).unwrap_or(root)
}

fn install_crash_handler(log_dir: PathBuf) {
    let default = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let report = format!(
            "Night Launcher {} crashed\n{info}\n\nbacktrace:\n{}",
            kestrel_core::LAUNCHER_VERSION,
            std::backtrace::Backtrace::force_capture()
        );
        let path = log_dir.join(format!(
            "launcher-crash-{}.txt",
            kestrel_core::auth::now_secs()
        ));
        let _ = std::fs::create_dir_all(&log_dir);
        let _ = std::fs::write(&path, kestrel_core::redact::redact(&report));
        log::error!("{report}");
        default(info);
    }));
}

fn main() -> eframe::Result {
    let args: Vec<String> = std::env::args().collect();
    let plan = root_plan(&args);
    // The folder to read settings from now (a pending move reads the old one),
    // and the folder this session ends up using.
    let (read_root, legacy, migrate) = match plan.clone() {
        datadir::RootPlan::Explicit(r) => (r, None, None),
        datadir::RootPlan::Ready { root, legacy } => (root, legacy, None),
        datadir::RootPlan::Migrate { from, to } => (from.clone(), None, Some((from, to))),
        datadir::RootPlan::Unavailable => {
            graphics::show_fatal(
                "Night Launcher couldn't find your Windows user data folder (%LOCALAPPDATA%), \
                 so it has nowhere to keep its files.\n\n\
                 Start it with --root \"<folder>\" to choose a folder.",
            );
            return Ok(());
        }
    };

    // `NightLauncher.exe --self-check <report.txt> [--root <dir>]`: verify the
    // packaged executable without opening a window (used by release.ps1). It
    // never moves data.
    if let Some(i) = args.iter().position(|a| a == "--self-check") {
        let report = args.get(i + 1).map(PathBuf::from);
        // A pending move checks the folder it will move to (creating only empty
        // folders there, which never count as data: see `datadir::has_data`).
        let root = migrate
            .as_ref()
            .map(|(_, to)| to.clone())
            .unwrap_or(read_root);
        std::process::exit(selfcheck::run(&Layout::new(root), report.as_deref()));
    }

    // Logs go to the folder in use from now on (the new one while moving data).
    let log_root = migrate
        .as_ref()
        .map(|(_, to)| to.clone())
        .unwrap_or_else(|| read_root.clone());
    let buffer = logger::LogBuffer::default();
    let log_dir = Layout::new(log_root.clone()).logs();
    logger::init(&log_dir, buffer.clone());
    install_crash_handler(log_dir);
    updater::e2e_startup_hook(&args);
    log::info!("data folder plan: {plan:?}");

    let renderer_setting = kestrel_core::config::LauncherConfig::load(
        &Layout::new(read_root.clone()).launcher_config(),
    )
    .renderer;
    let gfx = graphics::choose(&args, &read_root, &renderer_setting);
    // `--no-fallback` marks the second attempt, started after the first renderer failed.
    let fallback_allowed = !args.iter().any(|a| a == "--no-fallback");
    log::info!(
        "Night Launcher {} starting on {} ({}); renderer {}",
        kestrel_core::LAUNCHER_VERSION,
        std::env::consts::OS,
        std::env::consts::ARCH,
        gfx.id()
    );

    let mut options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Night Launcher")
            .with_inner_size([1040.0, 640.0])
            .with_min_inner_size([800.0, 540.0])
            .with_icon(std::sync::Arc::new(
                eframe::icon_data::from_png_bytes(embedded::ICON_PNG).unwrap_or_default(),
            )),
        ..Default::default()
    };
    graphics::configure(&mut options, gfx);

    let startup_args = args.clone();
    let started = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let started_in_app = started.clone();
    // A renderer that panics during start-up counts as a failure too.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        eframe::run_native(
            "Night Launcher",
            options,
            Box::new(move |cc| {
                // Reaching this point means the renderer initialised.
                started_in_app.store(true, std::sync::atomic::Ordering::SeqCst);
                graphics::set_active(gfx);
                if !fallback_allowed {
                    graphics::remember_fallback(&log_root, gfx);
                }
                // Tell a launcher that just updated us that we started, before any data move.
                let health =
                    updater::report_health(&startup_args, &[read_root.clone(), log_root.clone()]);
                let app: Box<dyn eframe::App> = match migrate {
                    Some((from, to)) => Box::new(startup::Startup::migrate(
                        &cc.egui_ctx,
                        from,
                        to,
                        buffer,
                        startup_args,
                        health,
                    )),
                    None => Box::new(startup::launcher(
                        &cc.egui_ctx,
                        Layout::new(read_root),
                        legacy,
                        buffer,
                        &startup_args,
                        health,
                    )),
                };
                Ok(app)
            }),
        )
    }));
    let error = match result {
        Ok(Ok(())) => return Ok(()),
        Ok(Err(e)) => e.to_string(),
        Err(_) => "the renderer crashed during start-up".to_string(),
    };
    if started.load(std::sync::atomic::Ordering::SeqCst) {
        // Failed after the window was up: not a start-up problem.
        graphics::show_fatal(&format!("Night Launcher stopped unexpectedly: {error}"));
        return Ok(());
    }
    log::error!("{} renderer failed to start: {error}", gfx.id());
    if fallback_allowed && graphics::relaunch_with(gfx.other(), &args) {
        log::info!("retrying with the {} renderer", gfx.other().id());
        return Ok(());
    }
    graphics::show_fatal(&format!(
        "Night Launcher could not open its window: no working graphics renderer was found.\n\n\
         Last error: {error}\n\n\
         Update your graphics driver. In a virtual machine, enable 3D acceleration and install the \
         guest tools. Note that Minecraft itself also needs OpenGL 2.0 or newer.\n\n\
         Log: {}",
        log_dir_for_message(&args).display()
    ));
    Ok(())
}

/// Where the launcher log is, for the fatal-error message (no side effects).
fn log_dir_for_message(args: &[String]) -> PathBuf {
    match root_plan(args) {
        datadir::RootPlan::Explicit(r) | datadir::RootPlan::Ready { root: r, .. } => r,
        datadir::RootPlan::Migrate { to, .. } => to,
        datadir::RootPlan::Unavailable => PathBuf::from("(unknown)"),
    }
    .join("logs")
    .join("launcher.log")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn root_argument_is_found_and_made_absolute() {
        let args: Vec<String> = [
            "NightLauncher.exe",
            "--renderer",
            "opengl",
            "--root",
            "dist/my data",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        match root_plan(&args) {
            datadir::RootPlan::Explicit(p) => {
                assert!(p.is_absolute());
                assert!(p.ends_with("dist/my data"));
            }
            other => panic!("unexpected {other:?}"),
        }
        assert_eq!(root_arg(&args[..3]), None);
    }

    #[test]
    fn relative_roots_become_absolute() {
        let p = absolute_root(PathBuf::from("dist/qol-test/data"));
        assert!(p.is_absolute());
        assert!(p.ends_with("dist/qol-test/data"));
        let abs = std::env::temp_dir();
        assert_eq!(absolute_root(abs.clone()), abs);
    }
}
