//! Long-running jobs, executed off the UI thread. Results come back as
//! [`Msg`] over a channel; progress is read from the shared [`Progress`].

use std::path::PathBuf;
use std::process::Child;
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use kestrel_core::auth::{self, AccountSummary, Authenticator, DeviceCode, Session, StoredAccount};
use kestrel_core::config::LauncherConfig;
use kestrel_core::entitlements;
use kestrel_core::game_bridge::{Bridge, BridgeEvent};
use kestrel_core::install::{self, InstallOptions, InstallState};
use kestrel_core::instances::{GameSettings, RunLock};
use kestrel_core::launch::{self, GameEvent};
use kestrel_core::mojang::Platform;
use kestrel_core::paths::Layout;
use kestrel_core::progress::SharedProgress;
use kestrel_core::runtime;

/// A running game process, shared so the UI can stop it.
pub type GameHandle = Arc<Mutex<Child>>;

pub enum Msg {
    InstallFinished(Box<Result<InstallState, String>>),
    DeviceCode(DeviceCode),
    /// A sign-in finished (the account is saved and active when Ok).
    SignedIn(Result<AccountSummary, String>),
    /// Saved accounts changed on disk (refresh, expiry); reload the list.
    AccountsChanged,
    /// Instance `id` finished preparing and its game process started.
    Launched {
        id: String,
        log_path: PathBuf,
        handle: GameHandle,
        account: String,
    },
    /// The running game of instance `id` switched to another account in game.
    PlayerChanged {
        id: String,
        name: String,
    },
    LaunchFailed {
        id: String,
        error: String,
    },
    Game {
        id: String,
        event: GameEvent,
    },
    JavaProbe(Result<String, String>),
    /// An instance create/duplicate/delete finished: Ok(message) or Err(error).
    InstanceOp(Result<String, String>),
}

/// Installs and repairs touch the shared game files; never run two at once
/// (two instances may be started together).
static INSTALL_LOCK: Mutex<()> = Mutex::new(());

/// The client mod to install: embedded in this exe, or next to it (dev builds).
pub fn bundled_mod(layout: &Layout) -> Option<PathBuf> {
    install::resolve_mod(layout, crate::embedded::EMBEDDED_MOD.as_ref())
}

fn install_opts(layout: &Layout, cfg: &LauncherConfig, managed_java: bool) -> InstallOptions {
    InstallOptions {
        managed_java,
        download_threads: cfg.download_threads,
        mod_jar: bundled_mod(layout),
    }
}

/// Full (re)install with verification of every file.
pub fn repair(
    layout: Layout,
    cfg: LauncherConfig,
    progress: SharedProgress,
    tx: Sender<Msg>,
    ctx: egui::Context,
) {
    let r = {
        let _g = INSTALL_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        install::install(&layout, &install_opts(&layout, &cfg, true), &progress)
    }
    .map_err(|e| e.to_string());
    if let Err(e) = &r {
        log::error!("installation failed: {e}");
    }
    let _ = tx.send(Msg::InstallFinished(Box::new(r)));
    ctx.request_repaint();
}

pub fn sign_in(
    client_id: String,
    accounts_path: PathBuf,
    progress: SharedProgress,
    tx: Sender<Msg>,
    ctx: egui::Context,
) {
    let r = (|| -> kestrel_core::Result<AccountSummary> {
        let a = Authenticator::new(&client_id)?;
        progress.stage("Requesting sign-in code", 0);
        let code = a.start_device_code()?;
        let _ = tx.send(Msg::DeviceCode(code.clone()));
        ctx.request_repaint();
        progress.stage("Waiting for you to sign in at microsoft.com", 0);
        let (ms, refresh) = a.poll_device_code(&code, &progress)?;
        progress.stage("Signing in to Minecraft", 0);
        let session = a.minecraft_session(&ms)?;
        let acct = StoredAccount {
            name: session.name,
            uuid: session.uuid,
            refresh_token: refresh,
            needs_sign_in: false,
        };
        let summary = acct.summary();
        auth::add_account(&accounts_path, acct)?;
        progress.stage("Signed in", 0);
        Ok(summary)
    })()
    .map_err(|e| e.to_string());
    if let Err(e) = &r {
        log::error!("sign-in failed: {e}");
    }
    let _ = tx.send(Msg::SignedIn(r));
    ctx.request_repaint();
}

pub fn probe_java(path: PathBuf, tx: Sender<Msg>, ctx: egui::Context) {
    let r = runtime::probe_version(&path).map_err(|e| e.to_string());
    let _ = tx.send(Msg::JavaProbe(r));
    ctx.request_repaint();
}

/// Who a launch plays as.
#[derive(Debug, Clone)]
pub enum LaunchAccount {
    Offline(String),
    /// A saved Microsoft account (Minecraft UUID).
    Microsoft(String),
}

pub struct PlayRequest {
    pub id: String,
    /// The installation with this instance selected.
    pub layout: Layout,
    pub cfg: LauncherConfig,
    pub game: GameSettings,
    pub account: LaunchAccount,
}

/// Ensure installation, authenticate, build the command, start the game and
/// wait for it to exit. Several of these can run at once, one per instance.
pub fn play(req: PlayRequest, progress: SharedProgress, tx: Sender<Msg>, ctx: egui::Context) {
    let PlayRequest {
        id,
        layout,
        cfg,
        game,
        account,
    } = req;
    let accounts_path = layout.accounts();
    let fail = |e: String| {
        let _ = tx.send(Msg::LaunchFailed {
            id: id.clone(),
            error: e,
        });
        ctx.request_repaint();
    };

    let prepared = (|| -> kestrel_core::Result<(launch::LaunchSpec, RunLock, String)> {
        // 0. This instance's folders (a new user, or folders removed by hand), then
        // one game per instance folder, even across launcher windows.
        layout.ensure()?;
        let lock = RunLock::acquire(&layout)?;

        // 1. Shared installation (fast path when the recorded files are intact).
        let state = {
            let _g = INSTALL_LOCK.lock().unwrap_or_else(|p| p.into_inner());
            let opts = install_opts(&layout, &cfg, game.use_managed_java);
            let mut state = match InstallState::load(&layout.install_state()) {
                Some(s) if s.quick_verify().is_empty() => s,
                Some(s) => {
                    for p in s.quick_verify().iter().take(10) {
                        log::warn!("installation problem: {p}");
                    }
                    install::install(&layout, &opts, &progress)?
                }
                None => install::install(&layout, &opts, &progress)?,
            };
            // This instance's copy of the client mod (a launcher update may ship a newer one).
            progress.stage("Checking the Night client mod", 0);
            let (jar, sha) = install::install_mod(&layout, bundled_mod(&layout).as_deref())?;
            if state.mod_sha1 != sha {
                state.mod_jar = jar;
                state.mod_sha1 = sha;
                state.save(&layout.install_state())?;
            }
            // 1b. Java.
            if game.use_managed_java && !matches!(&state.managed_java, Some(p) if p.is_file()) {
                let dl = kestrel_core::download::Downloader::new();
                let (p, v) = runtime::ensure(&dl, &layout.runtime(), &progress)?;
                state.managed_java = Some(p);
                state.java_version = Some(v);
                state.save(&layout.install_state())?;
            }
            state
        };
        progress.check_cancel()?;

        // 2. Session.
        progress.stage("Authenticating", 0);
        let session = match &account {
            LaunchAccount::Offline(name) => {
                log::info!("instance {id}: offline launch as {name}");
                Session::offline(name)
            }
            LaunchAccount::Microsoft(uuid) => {
                let r = auth::refresh_session(cfg.msa_client_id(), &accounts_path, uuid);
                let _ = tx.send(Msg::AccountsChanged);
                r?.0
            }
        };
        // This account's rewards, for this game only: refresh from the service when it
        // answers (else the verified cache), then give the game its own copy so it has them
        // at start-up. Never fatal; the game also checks with the service itself.
        if session.user_type == "msa" && entitlements::configured() {
            progress.stage("Checking rewards", 0);
            if let Err(e) = entitlements::refresh(layout.root(), &session.uuid) {
                log::info!("instance {id}: rewards not refreshed ({e}); using the saved ones");
            }
            if let Err(e) =
                entitlements::copy_to_game(layout.root(), &layout.client_data(), &session.uuid)
            {
                log::warn!("instance {id}: could not give the game its rewards: {e}");
            }
        }

        // 3. Java.
        let java = if game.use_managed_java {
            state.managed_java.clone().ok_or_else(|| {
                kestrel_core::Error::Launch("managed Java is missing; use Repair".into())
            })?
        } else {
            let p = PathBuf::from(&game.custom_java_path);
            let v = runtime::probe_version(&p)?;
            if !runtime::is_java8(&v) {
                return Err(kestrel_core::Error::Launch(format!(
                    "custom Java is version {v}; Minecraft 1.8.9 with Forge requires Java 8. \
                     Switch this instance to the managed runtime in its settings."
                )));
            }
            p
        };

        // 4. Command.
        let spec = launch::build(
            &state,
            &session,
            &game,
            &java,
            &layout,
            &Platform::current(),
        )?;
        Ok((spec, lock, session.name))
    })();

    let (spec, lock, player) = match prepared {
        Ok(x) => x,
        Err(e) => {
            log::error!("instance {id}: launch preparation failed: {e}");
            fail(e.to_string());
            return;
        }
    };

    progress.stage("Starting Minecraft", 0);
    let (gtx, grx) = std::sync::mpsc::channel::<GameEvent>();
    let game_proc = match launch::spawn(&spec, &layout, gtx) {
        Ok(g) => g,
        Err(e) => {
            log::error!("instance {id}: {e}");
            fail(e.to_string());
            return;
        }
    };
    // Answers the in-game account switcher on the game's stdin.
    let bridge = {
        let (btx, bctx, bid) = (Mutex::new(tx.clone()), ctx.clone(), id.clone());
        Bridge::new(
            game_proc.stdin,
            accounts_path.clone(),
            cfg.msa_client_id().to_string(),
            move |ev| {
                let msg = match ev {
                    BridgeEvent::AccountsChanged => Msg::AccountsChanged,
                    BridgeEvent::Switched { name } => Msg::PlayerChanged {
                        id: bid.clone(),
                        name,
                    },
                };
                let _ = btx.lock().unwrap_or_else(|p| p.into_inner()).send(msg);
                bctx.request_repaint();
            },
        )
        .with_game_data(layout.root().to_path_buf(), layout.client_data())
    };
    let handle: GameHandle = Arc::new(Mutex::new(game_proc.child));
    let _ = tx.send(Msg::Launched {
        id: id.clone(),
        log_path: game_proc.log_path.clone(),
        handle: handle.clone(),
        account: player,
    });
    ctx.request_repaint();

    // Forward game output until both pipes close.
    let (fwd_tx, fwd_ctx, fwd_id) = (tx.clone(), ctx.clone(), id.clone());
    let forwarder = std::thread::spawn(move || {
        for event in grx {
            if let GameEvent::Request(n, req) = event {
                bridge.handle(n, req);
                continue;
            }
            if fwd_tx
                .send(Msg::Game {
                    id: fwd_id.clone(),
                    event,
                })
                .is_err()
            {
                break;
            }
            fwd_ctx.request_repaint();
        }
    });
    // Poll instead of blocking in wait() so the UI can lock the handle to stop the game.
    let code = loop {
        let status = match handle.lock() {
            Ok(mut c) => c.try_wait(),
            Err(p) => p.into_inner().try_wait(),
        };
        match status {
            Ok(Some(s)) => break s.code(),
            Ok(None) => std::thread::sleep(Duration::from_millis(250)),
            Err(e) => {
                log::warn!("instance {id}: cannot query the game process: {e}");
                break None;
            }
        }
    };
    let _ = forwarder.join();
    drop(lock);
    log::info!("instance {id}: game exited with code {code:?}");
    let _ = tx.send(Msg::Game {
        id,
        event: GameEvent::Exited(code),
    });
    ctx.request_repaint();
}

/// Run an instance file operation (create, duplicate, delete) off the UI thread.
pub fn instance_op(
    f: impl FnOnce() -> kestrel_core::Result<String> + Send + 'static,
    tx: Sender<Msg>,
    ctx: egui::Context,
) {
    let spawned = std::thread::Builder::new()
        .name("instance-op".into())
        .spawn(move || {
            let r = f().map_err(|e| e.to_string());
            if let Err(e) = &r {
                log::error!("instance operation failed: {e}");
            }
            let _ = tx.send(Msg::InstanceOp(r));
            ctx.request_repaint();
        });
    if let Err(e) = spawned {
        log::error!("could not start instance operation: {e}");
    }
}
