//! The Redeem page: enter a code, see what the active account owns and what
//! it has redeemed. Redemption itself is `kestrel_core::entitlements`; this
//! page only runs it off the UI thread for the active Microsoft account.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, channel};

use eframe::egui::{self, CornerRadius, RichText, Stroke, Vec2};
use kestrel_core::auth::{self, AccountSummary};
use kestrel_core::cosmetics;
use kestrel_core::entitlements::{self, Entitlements, OFFICIAL_CAPES, RedeemError, Redeemed};
use kestrel_core::error::Error;

use crate::icons::Icon;
use crate::theme::{self, Kind, Tone};

/// What the app should do after a frame of this page.
pub enum Action {
    /// Show the official capes in Cosmetics.
    OpenOfficialCapes,
    /// Saved accounts changed (a sign-in was refreshed or expired).
    AccountsChanged,
}

struct Pending {
    uuid: String,
    rx: Receiver<Result<Redeemed, RedeemError>>,
}

pub struct RedeemPage {
    root: PathBuf,
    accounts_path: PathBuf,
    code: String,
    pending: Option<Pending>,
    /// Outcome of the last redemption: tone, title, details (for `uuid`).
    message: Option<(String, Tone, String, String)>,
    /// Accounts whose rewards were fetched from the service this session.
    synced: HashSet<String>,
    /// Background refreshes in flight: account, result.
    refreshing: Vec<(String, Receiver<Result<(), String>>)>,
    /// Last refresh error per account (shown under the rewards).
    refresh_error: Option<(String, String)>,
    /// Cached document of the shown account, with the cache generation it was read at.
    shown: Option<(String, u64, Option<Entitlements>)>,
}

impl RedeemPage {
    pub fn new(root: PathBuf, accounts_path: PathBuf) -> Self {
        RedeemPage {
            root,
            accounts_path,
            code: String::new(),
            pending: None,
            message: None,
            synced: HashSet::new(),
            refreshing: Vec::new(),
            refresh_error: None,
            shown: None,
        }
    }

    /// Fetch an account's rewards from the service once per session (in the
    /// background), so locks are right even if it redeemed on another PC.
    pub fn sync(&mut self, uuid: Option<&str>, ctx: &egui::Context) {
        let Some(uuid) = uuid else { return };
        if !entitlements::configured() || !self.synced.insert(uuid.to_string()) {
            return;
        }
        let (tx, rx) = channel();
        let (root, u, c) = (self.root.clone(), uuid.to_string(), ctx.clone());
        let spawned = std::thread::Builder::new()
            .name("rewards-refresh".into())
            .spawn(move || {
                let r = entitlements::refresh(&root, &u).map(|_| ());
                let _ = tx.send(r);
                c.request_repaint();
            });
        match spawned {
            Ok(_) => self.refreshing.push((uuid.to_string(), rx)),
            Err(e) => log::warn!("could not start the rewards refresh: {e}"),
        }
    }

    fn poll(&mut self) -> Option<Action> {
        let mut action = None;
        self.refreshing.retain(|(uuid, rx)| match rx.try_recv() {
            Ok(Ok(())) => false,
            Ok(Err(e)) => {
                log::info!("rewards refresh failed: {e}");
                self.refresh_error = Some((uuid.clone(), e));
                false
            }
            Err(std::sync::mpsc::TryRecvError::Empty) => true,
            Err(_) => false,
        });
        if let Some(p) = &self.pending
            && let Ok(result) = p.rx.try_recv()
        {
            let uuid = p.uuid.clone();
            self.pending = None;
            action = Some(Action::AccountsChanged);
            self.message = Some(match result {
                Ok(Redeemed::New { code, entitlements }) => {
                    self.code.clear();
                    (
                        uuid,
                        Tone::Good,
                        format!("{code} redeemed"),
                        unlocked_text(&code, &entitlements),
                    )
                }
                Ok(Redeemed::Already { code, .. }) => {
                    self.code.clear();
                    (
                        uuid,
                        Tone::Accent,
                        "Already redeemed".into(),
                        format!(
                            "This account already redeemed {code}. Its rewards are below and stay unlocked."
                        ),
                    )
                }
                Err(RedeemError::InvalidCode) => (
                    uuid,
                    Tone::Bad,
                    "Invalid code".into(),
                    RedeemError::InvalidCode.to_string(),
                ),
                Err(e @ (RedeemError::Expired | RedeemError::LimitReached)) => (
                    uuid,
                    Tone::Warn,
                    "Can't redeem this code".into(),
                    e.to_string(),
                ),
                Err(e) => (uuid, Tone::Warn, "Not redeemed".into(), e.to_string()),
            });
        }
        action
    }

    fn redeem(&mut self, account: &AccountSummary, client_id: &str, ctx: &egui::Context) {
        let (tx, rx) = channel();
        let (root, accounts, uuid, code, cid, c) = (
            self.root.clone(),
            self.accounts_path.clone(),
            account.uuid.clone(),
            self.code.clone(),
            client_id.to_string(),
            ctx.clone(),
        );
        self.message = None;
        let spawned = std::thread::Builder::new().name("redeem".into()).spawn(move || {
            let r = match auth::refresh_session(&cid, &accounts, &uuid) {
                Ok((session, _)) => {
                    kestrel_core::redact::register(&session.access_token);
                    entitlements::redeem(&root, &session, &code)
                }
                Err(Error::SessionExpired(name)) => Err(RedeemError::Failed(format!(
                    "{name}'s sign-in has expired. Sign in again on the Accounts page, then redeem."
                ))),
                Err(e) => Err(RedeemError::Failed(format!("Couldn't sign in to redeem: {e}"))),
            };
            match &r {
                Ok(Redeemed::New { code, .. }) => log::info!("redeemed {code}"),
                Ok(Redeemed::Already { code, .. }) => log::info!("{code} was already redeemed"),
                Err(e) => log::info!("redeem failed: {e}"),
            }
            let _ = tx.send(r);
            c.request_repaint();
        });
        match spawned {
            Ok(_) => {
                self.pending = Some(Pending {
                    uuid: account.uuid.clone(),
                    rx,
                })
            }
            Err(e) => {
                self.message = Some((
                    account.uuid.clone(),
                    Tone::Bad,
                    "Not redeemed".into(),
                    e.to_string(),
                ))
            }
        }
    }

    /// The shown account's verified document, re-read when the cache changes.
    fn document(&mut self, uuid: &str) -> Option<Entitlements> {
        let generation = entitlements::generation();
        let fresh = matches!(&self.shown, Some((u, g, _)) if u == uuid && *g == generation);
        if !fresh {
            self.shown = Some((
                uuid.to_string(),
                generation,
                entitlements::cached(&self.root, uuid),
            ));
        }
        self.shown.as_ref().and_then(|(_, _, e)| e.clone())
    }

    /// `account`: the active Microsoft account (None when none is signed in).
    /// `offline_mode`: whether the launcher plays offline right now.
    pub fn ui(
        &mut self,
        ui: &mut egui::Ui,
        account: Option<&AccountSummary>,
        offline_mode: bool,
        client_id: &str,
    ) -> Option<Action> {
        let mut action = self.poll();
        let ctx = ui.ctx().clone();
        self.sync(account.map(|a| a.uuid.as_str()), &ctx);
        if !entitlements::configured() {
            theme::banner(
                ui,
                Tone::Warn,
                "Redeeming isn't available in this build",
                "This copy of Night Launcher has no cosmetics service key. Update Night Launcher to redeem codes.",
            );
            ui.add_space(theme::GAP);
        }

        // ---- enter a code ----
        theme::card(ui, |ui| {
            theme::card_title(
                ui,
                "Redeem a code",
                "Codes unlock Night cosmetics for one Microsoft account. Each account can redeem a code once, \
                 and its rewards stay with that account on every instance and PC.",
            );
            ui.add_space(6.0);
            match account {
                None => {
                    ui.label(theme::muted(
                        "Sign in with a Microsoft account on the Accounts page to redeem codes.",
                    ));
                }
                Some(a) => {
                    ui.horizontal(|ui| {
                        ui.label(theme::muted("Redeeming for"));
                        theme::pill(ui, &a.name, Tone::Accent);
                        if offline_mode {
                            ui.label(theme::muted("(offline mode only changes how you play)"));
                        }
                    });
                    ui.add_space(8.0);
                    let busy = self.pending.is_some();
                    let mut submit = false;
                    ui.horizontal(|ui| {
                        let edit = egui::TextEdit::singleline(&mut self.code)
                            .hint_text("Enter a code")
                            .char_limit(32)
                            .font(theme::semibold(15.0))
                            .margin(Vec2::new(12.0, 8.0))
                            .desired_width((ui.available_width() - 150.0).clamp(160.0, 420.0));
                        let resp = ui.add_enabled(!busy, edit);
                        if resp.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
                            submit = true;
                        }
                        let ready =
                            !busy && !self.code.trim().is_empty() && entitlements::configured();
                        if theme::button(ui, Kind::Primary, Some(Icon::Gift), "Redeem", ready)
                            .clicked()
                        {
                            submit = true;
                        }
                        submit &= ready;
                    });
                    if submit {
                        self.redeem(a, client_id, &ctx);
                    }
                    if busy {
                        ui.add_space(8.0);
                        ui.horizontal(|ui| {
                            theme::spinner(ui, 16.0, theme::ACCENT_HI);
                            ui.label(theme::muted(
                                "Checking the code with the Night cosmetics service…",
                            ));
                        });
                    }
                    if let Some((uuid, tone, title, body)) = &self.message
                        && *uuid == a.uuid
                    {
                        ui.add_space(10.0);
                        theme::banner(ui, *tone, title, body);
                    }
                }
            }
        });
        ui.add_space(theme::GAP);

        let Some(a) = account else { return action };
        let doc = self.document(&a.uuid);
        let builtin = entitlements::builtin(Some(&a.uuid));

        // ---- owned rewards ----
        theme::card(ui, |ui| {
            theme::card_title(
                ui,
                &format!("Rewards on {}", a.name),
                "What this account has unlocked. Other accounts keep their own.",
            );
            ui.add_space(6.0);
            let mut ids: Vec<String> = doc.as_ref().map(|d| d.rewards.clone()).unwrap_or_default();
            for b in builtin {
                if !ids.iter().any(|i| i == b) {
                    ids.push((*b).to_string());
                }
            }
            if ids.is_empty() {
                ui.label(theme::muted(
                    "No rewards on this account yet. Redeem a code above.",
                ));
            }
            for id in &ids {
                let info = doc.as_ref().and_then(|d| d.catalog.get(id));
                let name = info
                    .map(|i| i.name.clone())
                    .unwrap_or_else(|| reward_name(id));
                let description = info.map(|i| i.description.clone()).unwrap_or_default();
                let from_code = doc
                    .as_ref()
                    .is_some_and(|d| d.redemptions.iter().any(|r| r.rewards.contains(id)));
                reward_row(
                    ui,
                    &name,
                    &description,
                    !from_code && builtin.contains(&id.as_str()),
                    |ui| {
                        if id == OFFICIAL_CAPES {
                            let capes = cosmetics::mojang_capes();
                            ui.add_space(6.0);
                            ui.horizontal_wrapped(|ui| {
                                ui.spacing_mut().item_spacing = Vec2::new(6.0, 6.0);
                                for c in capes {
                                    theme::pill(ui, &c.name, Tone::Neutral);
                                }
                            });
                            ui.add_space(8.0);
                            if theme::button(
                                ui,
                                Kind::Secondary,
                                Some(Icon::Cosmetics),
                                "Equip in Cosmetics",
                                true,
                            )
                            .clicked()
                            {
                                action = Some(Action::OpenOfficialCapes);
                            }
                        }
                    },
                );
            }
            if let Some((uuid, e)) = &self.refresh_error
                && *uuid == a.uuid
            {
                ui.add_space(6.0);
                ui.label(
                    RichText::new(format!("Couldn't check with the cosmetics service ({e}); showing the rewards saved on this PC."))
                        .small()
                        .color(theme::FAINT),
                );
            }
        });
        ui.add_space(theme::GAP);

        // ---- history ----
        theme::card(ui, |ui| {
            theme::card_title(ui, "Redemption history", "");
            let history = doc
                .as_ref()
                .map(|d| d.redemptions.clone())
                .unwrap_or_default();
            if history.is_empty() {
                ui.label(theme::muted("This account hasn't redeemed any codes."));
                return;
            }
            for r in history.iter().rev() {
                ui.add_space(6.0);
                egui::Frame::NONE
                    .fill(theme::SURFACE_HI)
                    .corner_radius(CornerRadius::same(theme::RADIUS_SM))
                    .inner_margin(egui::Margin::symmetric(12, 8))
                    .show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.horizontal(|ui| {
                            ui.label(
                                RichText::new(&r.code)
                                    .font(theme::semibold(14.0))
                                    .color(theme::ACCENT_HI),
                            );
                            ui.with_layout(
                                egui::Layout::right_to_left(egui::Align::Center),
                                |ui| {
                                    ui.label(theme::muted(format_date(r.at)));
                                },
                            );
                        });
                        let names: Vec<String> = r
                            .rewards
                            .iter()
                            .map(|id| {
                                doc.as_ref()
                                    .and_then(|d| d.catalog.get(id))
                                    .map(|i| i.name.clone())
                                    .unwrap_or_else(|| reward_name(id))
                            })
                            .collect();
                        let what = if r.description.is_empty() {
                            names.join(", ")
                        } else {
                            r.description.clone()
                        };
                        ui.label(RichText::new(what).color(theme::TEXT_SOFT));
                    });
            }
        });
        action
    }
}

fn unlocked_text(code: &str, e: &Entitlements) -> String {
    let names: Vec<String> = e
        .redemptions
        .iter()
        .filter(|r| r.code == code)
        .flat_map(|r| r.rewards.iter())
        .map(|id| {
            e.catalog
                .get(id)
                .map(|i| i.name.clone())
                .unwrap_or_else(|| reward_name(id))
        })
        .collect();
    let what = if names.is_empty() {
        "its rewards".to_string()
    } else {
        names.join(", ")
    };
    if e.has(OFFICIAL_CAPES) {
        format!("Unlocked: {what}. Equip one in Cosmetics → Capes → Mojang.")
    } else {
        format!("Unlocked: {what}.")
    }
}

fn reward_name(id: &str) -> String {
    match id {
        OFFICIAL_CAPES => "Official Minecraft capes".into(),
        other => other.replace('_', " "),
    }
}

fn reward_row(
    ui: &mut egui::Ui,
    name: &str,
    description: &str,
    builtin: bool,
    extra: impl FnOnce(&mut egui::Ui),
) {
    ui.add_space(4.0);
    egui::Frame::NONE
        .fill(theme::SURFACE_HI)
        .stroke(Stroke::new(1.0, theme::ACCENT.gamma_multiply(0.35)))
        .corner_radius(CornerRadius::same(theme::RADIUS_SM))
        .inner_margin(egui::Margin::same(12))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                crate::icons::show(ui, Icon::Gift, 20.0, theme::ACCENT_HI);
                ui.label(
                    RichText::new(name)
                        .font(theme::semibold(15.0))
                        .color(theme::TEXT),
                );
                theme::pill(
                    ui,
                    if builtin {
                        "Included with this account"
                    } else {
                        "Unlocked"
                    },
                    Tone::Good,
                );
            });
            if !description.is_empty() {
                ui.label(theme::muted(description));
            }
            extra(ui);
        });
}

/// "7 Nov 2026" (UTC) for a Unix time in seconds.
fn format_date(secs: u64) -> String {
    let days = (secs / 86_400) as i64;
    // Civil from days (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    format!("{d} {} {y}", MONTHS[(m - 1) as usize])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_format() {
        assert_eq!(format_date(0), "1 Jan 1970");
        assert_eq!(format_date(1_794_049_200), "7 Nov 2026");
        assert_eq!(format_date(951_782_400), "29 Feb 2000");
    }
}
