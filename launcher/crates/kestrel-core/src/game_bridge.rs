//! The launcher side of the in-game account switcher.
//!
//! The game asks with `[Kestrel] request <token> <n> <verb>` lines on stdout
//! (parsed by [`crate::launch::parse_request`]); this module answers on the
//! game's stdin, one JSON object per line, tagged `"re": n`:
//!
//! * `accounts` → `{"re":n,"type":"accounts","active":<uuid|null>,"accounts":[{"name","uuid","needsSignIn"}]}`
//! * `switch <uuid>` → `{"re":n,"type":"session","name","uuid","token"}` once the
//!   saved account has been refreshed through the normal Microsoft sign-in
//!   (the same [`auth::refresh_session`] a launch uses), or `{"type":"error"}`.
//!   Only the Minecraft access token for that one game is sent; refresh tokens
//!   never leave the launcher.
//! * `add` → `{"type":"code","code","uri"}` while waiting for the Microsoft
//!   device sign-in, then `{"type":"added","name","uuid"}` or an error.
//! * `cancel <m>` stops a pending `add`.

use std::collections::HashMap;
use std::io::Write;
use std::path::PathBuf;
use std::process::ChildStdin;
use std::sync::{Arc, Mutex};

use serde_json::json;

use crate::auth::{self, AccountStore, Authenticator, Session, StoredAccount};
use crate::error::Error;
use crate::launch::GameRequest;
use crate::progress::{Progress, SharedProgress};
use crate::redact;

/// What the launcher UI should hear about.
#[derive(Debug, Clone)]
pub enum BridgeEvent {
    /// Saved accounts changed (refresh, active account, new account).
    AccountsChanged,
    /// The game now plays as this account.
    Switched { name: String },
}

pub fn accounts_reply(n: u32, store: &AccountStore) -> String {
    let accounts: Vec<_> = store
        .accounts
        .iter()
        .map(|a| json!({"name": a.name, "uuid": a.uuid, "needsSignIn": a.needs_sign_in}))
        .collect();
    json!({"re": n, "type": "accounts", "active": store.active, "accounts": accounts}).to_string()
}

pub fn session_reply(n: u32, s: &Session) -> String {
    json!({"re": n, "type": "session", "name": s.name, "uuid": s.uuid, "token": s.access_token})
        .to_string()
}

pub fn code_reply(n: u32, code: &str, uri: &str) -> String {
    json!({"re": n, "type": "code", "code": code, "uri": uri}).to_string()
}

pub fn added_reply(n: u32, name: &str, uuid: &str) -> String {
    json!({"re": n, "type": "added", "name": name, "uuid": uuid}).to_string()
}

pub fn error_reply(n: u32, message: &str) -> String {
    json!({"re": n, "type": "error", "message": message}).to_string()
}

/// Serves one game's requests. Cheap to clone; slow work runs on its own threads.
#[derive(Clone)]
pub struct Bridge {
    stdin: Arc<Mutex<Option<ChildStdin>>>,
    accounts: PathBuf,
    client_id: String,
    notify: Arc<dyn Fn(BridgeEvent) + Send + Sync>,
    /// Unfinished `add` requests, cancellable by number.
    pending: Arc<Mutex<HashMap<u32, SharedProgress>>>,
    /// Data folder and this game's client data folder: on a switch, the new
    /// account's rewards are handed to this game (see [`crate::entitlements`]).
    game_data: Option<(PathBuf, PathBuf)>,
}

impl Bridge {
    pub fn new(
        stdin: Option<ChildStdin>,
        accounts: PathBuf,
        client_id: String,
        notify: impl Fn(BridgeEvent) + Send + Sync + 'static,
    ) -> Bridge {
        Bridge {
            stdin: Arc::new(Mutex::new(stdin)),
            accounts,
            client_id,
            notify: Arc::new(notify),
            pending: Arc::new(Mutex::new(HashMap::new())),
            game_data: None,
        }
    }

    /// Hand the switched-to account's rewards to this game (data folder, the
    /// game's `kestrel` folder).
    pub fn with_game_data(mut self, root: PathBuf, client_data: PathBuf) -> Bridge {
        self.game_data = Some((root, client_data));
        self
    }

    /// Refresh the account's rewards and copy them to this game, before the game
    /// hears about the new session. Never fails the switch.
    fn give_rewards(&self, uuid: &str) {
        let Some((root, client_data)) = &self.game_data else {
            return;
        };
        if !crate::entitlements::configured() {
            return;
        }
        if let Err(e) = crate::entitlements::refresh(root, uuid) {
            log::info!("rewards not refreshed on switch ({e}); using the saved ones");
        }
        if let Err(e) = crate::entitlements::copy_to_game(root, client_data, uuid) {
            log::warn!("could not give the game the account's rewards: {e}");
        }
    }

    /// Write one reply line. A closed pipe (game gone) silently ends the channel.
    fn send(&self, line: String) {
        let mut g = self.stdin.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(w) = g.as_mut()
            && (writeln!(w, "{line}").is_err() || w.flush().is_err())
        {
            *g = None;
        }
    }

    pub fn handle(&self, n: u32, req: GameRequest) {
        match req {
            GameRequest::Accounts => {
                self.send(accounts_reply(n, &auth::load_store(&self.accounts)))
            }
            GameRequest::Cancel(m) => {
                if let Some(p) = self
                    .pending
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .remove(&m)
                {
                    p.cancel();
                }
            }
            GameRequest::Switch(uuid) => {
                let me = self.clone();
                spawn("game-switch", move || me.switch(n, &uuid));
            }
            GameRequest::Add => {
                let me = self.clone();
                spawn("game-add-account", move || me.add(n));
            }
        }
    }

    fn switch(&self, n: u32, uuid: &str) {
        log::info!("in-game account switch requested");
        match auth::refresh_session(&self.client_id, &self.accounts, uuid) {
            Ok((session, _)) => {
                redact::register(&session.access_token);
                self.give_rewards(&session.uuid);
                self.send(session_reply(n, &session));
                // The launcher follows the game: the account it switched to becomes active.
                let target = uuid.to_string();
                if let Err(e) = auth::update_store(&self.accounts, |s| s.set_active(&target)) {
                    log::warn!("could not mark the switched account active: {e}");
                }
                (self.notify)(BridgeEvent::AccountsChanged);
                (self.notify)(BridgeEvent::Switched { name: session.name });
            }
            Err(e) => {
                log::warn!("in-game account switch failed: {e}");
                let msg = match &e {
                    Error::SessionExpired(name) => {
                        format!(
                            "{name}'s sign-in has expired. Use Add account to sign in to it again."
                        )
                    }
                    other => other.to_string(),
                };
                self.send(error_reply(n, &msg));
                (self.notify)(BridgeEvent::AccountsChanged);
            }
        }
    }

    fn add(&self, n: u32) {
        let progress = Progress::new();
        self.pending
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(n, progress.clone());
        let r = (|| -> crate::Result<StoredAccount> {
            let a = Authenticator::new(&self.client_id)?;
            let code = a.start_device_code()?;
            self.send(code_reply(n, &code.user_code, &code.verification_uri));
            let (ms, refresh) = a.poll_device_code(&code, &progress)?;
            let s = a.minecraft_session(&ms)?;
            let acct = StoredAccount {
                name: s.name,
                uuid: s.uuid,
                refresh_token: refresh,
                needs_sign_in: false,
            };
            auth::add_account(&self.accounts, acct.clone())?;
            Ok(acct)
        })();
        self.pending
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(&n);
        match r {
            Ok(a) => {
                log::info!("account added from the game");
                self.send(added_reply(n, &a.name, &a.uuid));
                (self.notify)(BridgeEvent::AccountsChanged);
            }
            Err(Error::Cancelled) => self.send(error_reply(n, "Sign-in cancelled.")),
            Err(e) => {
                log::warn!("in-game sign-in failed: {e}");
                self.send(error_reply(n, &format!("Sign-in failed: {e}")));
            }
        }
    }
}

fn spawn(name: &str, f: impl FnOnce() + Send + 'static) {
    if let Err(e) = std::thread::Builder::new().name(name.into()).spawn(f) {
        log::error!("could not start {name}: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replies_are_single_json_lines_without_refresh_tokens() {
        let mut store = AccountStore::default();
        store.upsert(StoredAccount {
            name: "Bringing".into(),
            uuid: "ba14".into(),
            refresh_token: "SECRET-REFRESH".into(),
            needs_sign_in: false,
        });
        store.upsert(StoredAccount {
            name: "Alt".into(),
            uuid: "a17".into(),
            refresh_token: "SECRET-2".into(),
            needs_sign_in: true,
        });
        let r = accounts_reply(3, &store);
        assert!(!r.contains('\n'));
        assert!(!r.contains("SECRET"));
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["re"], 3);
        assert_eq!(v["type"], "accounts");
        assert_eq!(v["active"], "ba14");
        assert_eq!(v["accounts"][1]["needsSignIn"], true);

        let s = Session {
            name: "Bringing".into(),
            uuid: "ba14".into(),
            access_token: "tok\n\"x".into(),
            user_type: "msa",
        };
        let r = session_reply(4, &s);
        assert!(!r.contains('\n'), "escaped: {r}");
        let v: serde_json::Value = serde_json::from_str(&r).unwrap();
        assert_eq!(v["token"], "tok\n\"x");
        let e: serde_json::Value = serde_json::from_str(&error_reply(5, "a\nb")).unwrap();
        assert_eq!(e["message"], "a\nb");
        let c: serde_json::Value =
            serde_json::from_str(&code_reply(6, "ABCD", "https://microsoft.com/link")).unwrap();
        assert_eq!(c["code"], "ABCD");
        let a: serde_json::Value = serde_json::from_str(&added_reply(7, "Alt", "a17")).unwrap();
        assert_eq!(a["type"], "added");
    }

    #[test]
    fn closed_pipe_is_ignored() {
        let b = Bridge::new(
            None,
            PathBuf::from("/nonexistent/accounts.dat"),
            "id".into(),
            |_| {},
        );
        b.send("{}".into());
        // Accounts with no store file: empty list, no panic.
        b.handle(1, GameRequest::Accounts);
        b.handle(2, GameRequest::Cancel(99));
    }
}
