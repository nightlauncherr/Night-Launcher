//! Microsoft account authentication for Minecraft Java Edition.
//!
//! Flow: OAuth 2.0 device code (the user signs in on microsoft.com — the
//! launcher never sees their password) → Xbox Live user token → XSTS token →
//! Minecraft services token → Minecraft profile.
//!
//! Requires a Microsoft Entra app registration ("Personal Microsoft accounts
//! only", "Allow public client flows" enabled). The authority is therefore
//! `/consumers`. New app IDs must additionally be approved by Mojang before
//! `login_with_xbox` accepts them; until then step 3 fails with HTTP 403 and we
//! report that explicitly.

use std::path::Path;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::error::{Error, Result};
use crate::paths::atomic_write;
use crate::progress::Progress;
use crate::{redact, secure};

/// Personal-Microsoft-account-only authority (the app registration must use
/// "Personal Microsoft accounts only"; `/common` would also admit work/school
/// accounts, which cannot own Minecraft).
pub const DEVICE_CODE_URL: &str =
    "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
pub const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

/// Kestrel's own Microsoft Entra app registration, used when Settings >
/// Advanced has no client ID of its own. An application (client) ID is a
/// public identifier (it is sent in the clear in every sign-in request), not
/// a secret.
pub const BUILT_IN_CLIENT_ID: &str = "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb";
const XBL_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MC_LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MC_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";
pub const SCOPE: &str = "XboxLive.signin offline_access";

/// Microsoft first-party application IDs that show up in Entra/Azure portal
/// error pages (e.g. AADSTS50020). They are never a launcher's client ID; users
/// sometimes copy them from the error into Settings.
const MICROSOFT_PORTAL_APP_IDS: &[(&str, &str)] = &[(
    "74658136-14ec-4630-ad9b-26e160ff0fc6",
    "the Azure portal (ADIbizaUX)",
)];

/// Hosts Microsoft uses for the device-code sign-in page. Anything else in a
/// device-code response is refused instead of being opened in the browser.
const VERIFICATION_HOSTS: &[&str] = &[
    "microsoft.com",
    "www.microsoft.com",
    "login.microsoftonline.com",
    "login.live.com",
];

/// Normalise and check the configured Application (client) ID.
pub fn validate_client_id(raw: &str) -> Result<String> {
    let id = raw.trim();
    if id.is_empty() {
        return Err(Error::Auth(
            "no Microsoft application (client) ID configured. Register an app in Microsoft Entra and paste its Application (client) ID into Settings > Advanced (see documentation/BUILD.md, \"Microsoft sign-in\").".into(),
        ));
    }
    let lower = id.to_ascii_lowercase();
    if !is_guid(&lower) || lower == "00000000-0000-0000-0000-000000000000" {
        return Err(Error::Auth(format!(
            "\"{id}\" is not an Application (client) ID. It must be a GUID like 1a2b3c4d-1234-5678-9abc-def012345678, copied from your app registration's Overview page in Microsoft Entra."
        )));
    }
    if let Some((_, what)) = MICROSOFT_PORTAL_APP_IDS
        .iter()
        .find(|(known, _)| *known == lower)
    {
        return Err(Error::Auth(format!(
            "{id} is the application ID of {what}, shown in Microsoft error pages. It is not your own app registration. Use the Application (client) ID from your own app registration's Overview page."
        )));
    }
    Ok(lower)
}

fn is_guid(s: &str) -> bool {
    let b = s.as_bytes();
    b.len() == 36
        && b.iter().enumerate().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => *c == b'-',
            _ => c.is_ascii_hexdigit(),
        })
}

/// The exact device-code request Kestrel sends: endpoint and form fields.
pub fn device_code_request(client_id: &str) -> (&'static str, [(&'static str, String); 2]) {
    (
        DEVICE_CODE_URL,
        [
            ("client_id", client_id.to_string()),
            ("scope", SCOPE.to_string()),
        ],
    )
}

/// Only an `https://` URL on a Microsoft sign-in host may be shown/opened.
pub fn validate_verification_uri(uri: &str) -> Result<String> {
    let bad = || {
        Error::Auth(format!(
            "Microsoft returned an unexpected sign-in page address ({uri}); refusing to open it"
        ))
    };
    let rest = uri.strip_prefix("https://").ok_or_else(bad)?;
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    if authority.contains('@') || authority.contains(':') {
        return Err(bad());
    }
    let host = authority.to_ascii_lowercase();
    if VERIFICATION_HOSTS.contains(&host.as_str()) {
        Ok(uri.to_string())
    } else {
        Err(bad())
    }
}

/// A ready-to-launch identity.
#[derive(Debug, Clone)]
pub struct Session {
    pub name: String,
    /// UUID without dashes.
    pub uuid: String,
    pub access_token: String,
    /// `msa` for Microsoft accounts, `legacy` for offline.
    pub user_type: &'static str,
}

impl Session {
    /// Offline session for singleplayer / offline-mode servers. The UUID is
    /// the standard `OfflinePlayer:<name>` v3 UUID used by vanilla servers.
    pub fn offline(name: &str) -> Session {
        use md5::{Digest, Md5};
        let mut h: [u8; 16] = Md5::digest(format!("OfflinePlayer:{name}").as_bytes()).into();
        h[6] = (h[6] & 0x0f) | 0x30;
        h[8] = (h[8] & 0x3f) | 0x80;
        Session {
            name: name.to_string(),
            uuid: hex::encode(h),
            access_token: "0".into(),
            user_type: "legacy",
        }
    }
}

/// One saved Microsoft account (persisted DPAPI-encrypted in the
/// [`AccountStore`]).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredAccount {
    pub name: String,
    pub uuid: String,
    pub refresh_token: String,
    /// Set when the refresh token stopped working; cleared by signing in again.
    #[serde(default)]
    pub needs_sign_in: bool,
}

impl StoredAccount {
    /// The parts that are safe to show and to pass around the UI.
    pub fn summary(&self) -> AccountSummary {
        AccountSummary {
            name: self.name.clone(),
            uuid: self.uuid.clone(),
            needs_sign_in: self.needs_sign_in,
        }
    }
}

/// An account without its credentials.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccountSummary {
    pub name: String,
    pub uuid: String,
    pub needs_sign_in: bool,
}

/// Every saved account and which one is active. The whole store is one
/// DPAPI-encrypted file (`accounts.dat`); launchers before 1.1 stored a single
/// [`StoredAccount`] there, which [`load_store`] still reads.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AccountStore {
    pub schema: u32,
    /// UUID of the active account.
    pub active: Option<String>,
    pub accounts: Vec<StoredAccount>,
}

pub const ACCOUNT_STORE_SCHEMA: u32 = 2;

impl AccountStore {
    pub fn get(&self, uuid: &str) -> Option<&StoredAccount> {
        self.accounts.iter().find(|a| a.uuid == uuid)
    }

    pub fn active_account(&self) -> Option<&StoredAccount> {
        self.active.as_deref().and_then(|u| self.get(u))
    }

    pub fn summaries(&self) -> Vec<AccountSummary> {
        self.accounts.iter().map(StoredAccount::summary).collect()
    }

    /// Add the account, or update it in place (same UUID) keeping its position.
    pub fn upsert(&mut self, acct: StoredAccount) {
        match self.accounts.iter_mut().find(|a| a.uuid == acct.uuid) {
            Some(a) => *a = acct,
            None => self.accounts.push(acct),
        }
        if self.active_account().is_none() {
            self.active = self.accounts.first().map(|a| a.uuid.clone());
        }
    }

    /// Make a saved account active. Returns false if it isn't saved.
    pub fn set_active(&mut self, uuid: &str) -> bool {
        if self.get(uuid).is_some() {
            self.active = Some(uuid.to_string());
            true
        } else {
            false
        }
    }

    /// Remove an account; if it was active, the first remaining one becomes active.
    pub fn remove(&mut self, uuid: &str) -> bool {
        let before = self.accounts.len();
        self.accounts.retain(|a| a.uuid != uuid);
        if self.active.as_deref() == Some(uuid) || self.active_account().is_none() {
            self.active = self.accounts.first().map(|a| a.uuid.clone());
        }
        self.accounts.len() != before
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct DeviceCode {
    pub user_code: String,
    pub device_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    #[serde(default = "default_interval")]
    pub interval: u64,
    #[serde(default)]
    pub message: String,
}

fn default_interval() -> u64 {
    5
}

#[derive(Debug, Deserialize)]
struct TokenResponse {
    access_token: Option<String>,
    refresh_token: Option<String>,
    error: Option<String>,
    error_description: Option<String>,
}

pub struct Authenticator {
    agent: ureq::Agent,
    client_id: String,
    device_code_url: String,
    token_url: String,
}

impl Authenticator {
    pub fn new(client_id: &str) -> Result<Authenticator> {
        let client_id = validate_client_id(client_id)?;
        // OAuth errors come back as 4xx with a JSON body we need to read.
        let agent = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(30)))
            .user_agent(format!("Night-Launcher/{}", crate::LAUNCHER_VERSION))
            .build()
            .into();
        Ok(Authenticator {
            agent,
            client_id,
            device_code_url: DEVICE_CODE_URL.to_string(),
            token_url: TOKEN_URL.to_string(),
        })
    }

    /// Test-only: point the OAuth requests at a local mock server.
    #[cfg(test)]
    fn with_oauth_endpoints(mut self, device_code_url: &str, token_url: &str) -> Self {
        self.device_code_url = device_code_url.to_string();
        self.token_url = token_url.to_string();
        self
    }

    pub fn start_device_code(&self) -> Result<DeviceCode> {
        let (_, form) = device_code_request(&self.client_id);
        let url = self.device_code_url.as_str();
        let mut resp = self
            .agent
            .post(url)
            .send_form(form.iter().map(|(k, v)| (*k, v.as_str())))
            .map_err(|e| http_err(url, e))?;
        let status = resp.status().as_u16();
        let body = resp
            .body_mut()
            .read_to_string()
            .map_err(|e| http_err(url, e))?;
        if status != 200 {
            return Err(Error::Auth(format!(
                "device code request failed (HTTP {status}): {}",
                oauth_message(&body)
            )));
        }
        let mut code: DeviceCode =
            serde_json::from_str(&body).map_err(|e| Error::json("device code response", e))?;
        code.verification_uri = validate_verification_uri(&code.verification_uri)?;
        Ok(code)
    }

    /// Poll until the user completes sign-in, the code expires, or `progress`
    /// is cancelled. Returns (MS access token, refresh token).
    pub fn poll_device_code(
        &self,
        code: &DeviceCode,
        progress: &Progress,
    ) -> Result<(String, String)> {
        let deadline = Instant::now() + Duration::from_secs(code.expires_in.min(1800));
        let mut interval = code.interval.clamp(1, 60);
        loop {
            // Sleep in short slices so cancellation is responsive.
            for _ in 0..interval * 4 {
                progress.check_cancel()?;
                std::thread::sleep(Duration::from_millis(250));
            }
            if Instant::now() > deadline {
                return Err(Error::Auth("sign-in code expired; please try again".into()));
            }
            let t = self.token_request(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", self.client_id.as_str()),
                ("device_code", code.device_code.as_str()),
            ])?;
            match t.error.as_deref() {
                None => return tokens(t),
                Some("authorization_pending") => {}
                Some("slow_down") => interval += 5,
                Some("authorization_declined") => {
                    return Err(Error::Auth("sign-in was declined".into()));
                }
                Some("expired_token") => {
                    return Err(Error::Auth("sign-in code expired; please try again".into()));
                }
                Some(other) => {
                    return Err(Error::Auth(format!(
                        "{other}: {}",
                        t.error_description.unwrap_or_default()
                    )));
                }
            }
        }
    }

    pub fn refresh(&self, refresh_token: &str) -> Result<(String, String)> {
        let t = self.token_request(&[
            ("grant_type", "refresh_token"),
            ("client_id", self.client_id.as_str()),
            ("refresh_token", refresh_token),
            ("scope", SCOPE),
        ])?;
        if let Some(e) = &t.error {
            return Err(Error::Auth(format!(
                "session expired, please sign in again ({e}: {})",
                t.error_description.clone().unwrap_or_default()
            )));
        }
        tokens(t)
    }

    fn token_request(&self, form: &[(&str, &str)]) -> Result<TokenResponse> {
        let url = self.token_url.as_str();
        let mut resp = self
            .agent
            .post(url)
            .send_form(form.iter().copied())
            .map_err(|e| http_err(url, e))?;
        let body = resp
            .body_mut()
            .read_to_string()
            .map_err(|e| http_err(url, e))?;
        serde_json::from_str(&body).map_err(|e| Error::json("token response", e))
    }

    /// Exchange a Microsoft access token for a Minecraft session.
    pub fn minecraft_session(&self, ms_access_token: &str) -> Result<Session> {
        redact::register(ms_access_token);

        // 1. Xbox Live user token.
        let xbl: serde_json::Value = self.post_json(
            XBL_URL,
            json!({
                "Properties": {"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": format!("d={ms_access_token}")},
                "RelyingParty": "http://auth.xboxlive.com",
                "TokenType": "JWT"
            }),
            None,
        )?;
        let xbl_token = str_field(&xbl, &["Token"])?;
        let uhs = str_field(&xbl, &["DisplayClaims", "xui", "0", "uhs"])?;
        redact::register(&xbl_token);

        // 2. XSTS token for Minecraft services.
        let xsts: serde_json::Value = self.post_json(
            XSTS_URL,
            json!({
                "Properties": {"SandboxId": "RETAIL", "UserTokens": [xbl_token]},
                "RelyingParty": "rp://api.minecraftservices.com/",
                "TokenType": "JWT"
            }),
            Some(xsts_error),
        )?;
        let xsts_token = str_field(&xsts, &["Token"])?;
        redact::register(&xsts_token);

        // 3. Minecraft access token.
        let mc: serde_json::Value = self.post_json(
            MC_LOGIN_URL,
            json!({"identityToken": format!("XBL3.0 x={uhs};{xsts_token}")}),
            Some(|status, _| {
                (status == 403).then(|| {
                    "Minecraft services rejected this application ID (HTTP 403). New Azure app IDs must be approved by Mojang before they can sign in to Minecraft — see BUILD.md.".to_string()
                })
            }),
        )?;
        let mc_token = str_field(&mc, &["access_token"])?;
        redact::register(&mc_token);

        // 4. Profile (fails with 404 if the account does not own Java Edition).
        let mut resp = self
            .agent
            .get(MC_PROFILE_URL)
            .header("Authorization", format!("Bearer {mc_token}"))
            .call()
            .map_err(|e| http_err(MC_PROFILE_URL, e))?;
        let status = resp.status().as_u16();
        let body = resp
            .body_mut()
            .read_to_string()
            .map_err(|e| http_err(MC_PROFILE_URL, e))?;
        if status == 404 {
            return Err(Error::Auth(
                "this Microsoft account does not own Minecraft: Java Edition".into(),
            ));
        }
        if status != 200 {
            return Err(Error::Auth(format!(
                "profile request failed (HTTP {status})"
            )));
        }
        let profile: serde_json::Value =
            serde_json::from_str(&body).map_err(|e| Error::json("profile", e))?;
        Ok(Session {
            name: str_field(&profile, &["name"])?,
            uuid: str_field(&profile, &["id"])?.replace('-', ""),
            access_token: mc_token,
            user_type: "msa",
        })
    }

    fn post_json(
        &self,
        url: &str,
        body: serde_json::Value,
        explain: Option<fn(u16, &str) -> Option<String>>,
    ) -> Result<serde_json::Value> {
        let mut resp = self
            .agent
            .post(url)
            .header("Accept", "application/json")
            .send_json(body)
            .map_err(|e| http_err(url, e))?;
        let status = resp.status().as_u16();
        let text = resp
            .body_mut()
            .read_to_string()
            .map_err(|e| http_err(url, e))?;
        if status != 200 {
            if let Some(msg) = explain.and_then(|f| f(status, &text)) {
                return Err(Error::Auth(msg));
            }
            return Err(Error::Auth(format!("{url} returned HTTP {status}")));
        }
        serde_json::from_str(&text).map_err(|e| Error::json(url.to_string(), e))
    }
}

fn xsts_error(status: u16, body: &str) -> Option<String> {
    if status != 401 {
        return None;
    }
    let code = serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .and_then(|v| v.get("XErr").and_then(|x| x.as_u64()));
    Some(match code {
        Some(2148916233) => "this Microsoft account has no Xbox profile; sign in once at minecraft.net to create one".into(),
        Some(2148916235) => "Xbox Live is not available in your country".into(),
        Some(2148916236) | Some(2148916237) => "this account needs adult verification on xbox.com".into(),
        Some(2148916238) => "child accounts must be added to a Microsoft Family by an adult".into(),
        other => format!("Xbox authorization failed (XErr {other:?})"),
    })
}

fn tokens(t: TokenResponse) -> Result<(String, String)> {
    match (t.access_token, t.refresh_token) {
        (Some(a), Some(r)) => {
            redact::register(&a);
            redact::register(&r);
            Ok((a, r))
        }
        _ => Err(Error::Auth("token response missing tokens".into())),
    }
}

fn str_field(v: &serde_json::Value, path: &[&str]) -> Result<String> {
    let mut cur = v;
    for p in path {
        cur = match p.parse::<usize>() {
            Ok(i) => cur.get(i),
            Err(_) => cur.get(*p),
        }
        .ok_or_else(|| Error::Auth(format!("unexpected response: missing {}", path.join("."))))?;
    }
    cur.as_str().map(str::to_string).ok_or_else(|| {
        Error::Auth(format!(
            "unexpected response: {} is not a string",
            path.join(".")
        ))
    })
}

fn oauth_message(body: &str) -> String {
    serde_json::from_str::<TokenResponse>(body)
        .ok()
        .and_then(|t| t.error_description.or(t.error))
        .unwrap_or_else(|| "unexpected response".into())
}

fn http_err(url: &str, e: ureq::Error) -> Error {
    Error::Http {
        url: url.to_string(),
        message: e.to_string(),
    }
}

// ---- persistence -----------------------------------------------------------

/// Serialises every read-modify-write of the account store within this
/// process. Held only around file access, never across network calls, so the
/// UI thread can take it.
static STORE_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
/// Serialises refreshes. Refresh tokens rotate on use, so two launches
/// refreshing the same account must not race (the loser would save a token
/// Microsoft already replaced). Held across the network exchange.
static REFRESH_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn store_guard() -> std::sync::MutexGuard<'static, ()> {
    // A panic while holding the lock leaves nothing half-written (writes are atomic).
    STORE_LOCK.lock().unwrap_or_else(|p| p.into_inner())
}

/// Decode a decrypted store: the current format, or a pre-1.1 single account.
fn decode_store(plain: &[u8]) -> Result<AccountStore> {
    let v: serde_json::Value =
        serde_json::from_slice(plain).map_err(|e| Error::json("accounts", e))?;
    if v.get("accounts").is_some() {
        let mut s: AccountStore =
            serde_json::from_value(v).map_err(|e| Error::json("accounts", e))?;
        // Drop duplicate UUIDs (first wins) and a dangling active marker.
        let mut seen = std::collections::HashSet::new();
        s.accounts.retain(|a| seen.insert(a.uuid.clone()));
        if s.active_account().is_none() {
            s.active = s.accounts.first().map(|a| a.uuid.clone());
        }
        Ok(s)
    } else {
        let a: StoredAccount = serde_json::from_value(v).map_err(|e| Error::json("account", e))?;
        Ok(AccountStore {
            schema: ACCOUNT_STORE_SCHEMA,
            active: Some(a.uuid.clone()),
            accounts: vec![a],
        })
    }
}

fn read_store(path: &Path) -> AccountStore {
    let cipher = match std::fs::read(path) {
        Ok(c) => c,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return AccountStore::default(),
        Err(e) => {
            log::warn!("cannot read saved accounts ({e})");
            return AccountStore::default();
        }
    };
    match secure::unprotect(&cipher).and_then(|p| decode_store(&p)) {
        Ok(s) => {
            for a in &s.accounts {
                redact::register(&a.refresh_token);
            }
            s
        }
        Err(e) => {
            // Keep the unreadable file (another Windows user's, or damaged)
            // instead of overwriting it on the next save.
            let keep = path.with_extension(format!("dat.unreadable-{}", now_secs()));
            log::warn!(
                "saved accounts unreadable ({e}); kept as {} — sign in again",
                keep.display()
            );
            let _ = std::fs::rename(path, &keep);
            AccountStore::default()
        }
    }
}

/// The store as written. The active account is also mirrored into the
/// top-level `name`/`uuid`/`refresh_token` fields of the pre-1.1 format, so an
/// older launcher (before it updates) still finds that account instead of
/// asking to sign in again. [`decode_store`] prefers `accounts`.
fn encode_store(store: &AccountStore) -> Result<Vec<u8>> {
    let mut s = store.clone();
    s.schema = ACCOUNT_STORE_SCHEMA;
    let mut v = serde_json::to_value(&s).map_err(|e| Error::json("accounts", e))?;
    if let (Some(a), Some(obj)) = (s.active_account(), v.as_object_mut()) {
        obj.insert("name".into(), a.name.clone().into());
        obj.insert("uuid".into(), a.uuid.clone().into());
        obj.insert("refresh_token".into(), a.refresh_token.clone().into());
    }
    serde_json::to_vec(&v).map_err(|e| Error::json("accounts", e))
}

fn write_store(path: &Path, store: &AccountStore) -> Result<()> {
    if !secure::is_supported() {
        log::warn!("secure storage unavailable on this platform; accounts not persisted");
        return Ok(());
    }
    let plain = encode_store(store)?;
    let cipher = secure::protect(&plain)?;
    atomic_write(path, &cipher)
}

/// The saved accounts (empty when none, or when the file can't be read).
pub fn load_store(path: &Path) -> AccountStore {
    let _g = store_guard();
    read_store(path)
}

/// Read, change and save the store as one step. The store is re-read from
/// disk first, so changes made elsewhere in this process are kept.
pub fn update_store<R>(
    path: &Path,
    f: impl FnOnce(&mut AccountStore) -> R,
) -> Result<(R, AccountStore)> {
    let _g = store_guard();
    let mut s = read_store(path);
    let r = f(&mut s);
    write_store(path, &s)?;
    Ok((r, s))
}

/// Save a newly signed-in account (or a fresh sign-in of a saved one) and
/// make it active.
pub fn add_account(path: &Path, acct: StoredAccount) -> Result<AccountStore> {
    let uuid = acct.uuid.clone();
    update_store(path, move |s| {
        s.upsert(StoredAccount {
            needs_sign_in: false,
            ..acct
        });
        s.set_active(&uuid);
    })
    .map(|(_, s)| s)
}

/// Refresh a saved account and turn it into a launchable session. The store
/// lock is held for the whole exchange so concurrent launches of the same
/// account can't race on the rotating refresh token. On an expired or revoked
/// sign-in the account is marked [`StoredAccount::needs_sign_in`] and
/// [`Error::SessionExpired`] is returned; nothing else in the store changes.
pub fn refresh_session(
    client_id: &str,
    path: &Path,
    uuid: &str,
) -> Result<(Session, StoredAccount)> {
    let _refreshing = REFRESH_LOCK.lock().unwrap_or_else(|p| p.into_inner());
    let acct = load_store(path)
        .get(uuid)
        .cloned()
        .ok_or_else(|| Error::Auth("that account is no longer saved; add it again".into()))?;
    let a = Authenticator::new(client_id)?;
    let (ms, refresh) = match a.refresh(&acct.refresh_token) {
        Ok(t) => t,
        Err(Error::Auth(m)) if m.starts_with("session expired") => {
            log::warn!("refresh failed for {}: {m}", acct.name);
            update_store(path, |s| {
                if let Some(x) = s.accounts.iter_mut().find(|x| x.uuid == uuid) {
                    x.needs_sign_in = true;
                }
            })?;
            return Err(Error::SessionExpired(acct.name));
        }
        Err(e) => return Err(e),
    };
    redact::register(&refresh);
    let session = a.minecraft_session(&ms)?;
    if session.uuid != acct.uuid {
        // The refresh token belongs to a different Minecraft profile than the one
        // saved under this entry: never launch as someone else.
        return Err(Error::Auth(format!(
            "the saved sign-in for {} now belongs to another Minecraft profile ({}); sign in again",
            acct.name, session.name
        )));
    }
    let updated = StoredAccount {
        name: session.name.clone(),
        uuid: session.uuid.clone(),
        refresh_token: refresh,
        needs_sign_in: false,
    };
    let saved = updated.clone();
    // Only this account's entry changes; it is skipped if the account was
    // removed meanwhile (removing must win over a background refresh).
    update_store(path, move |s| {
        if let Some(x) = s.accounts.iter_mut().find(|x| x.uuid == saved.uuid) {
            *x = saved;
        }
    })?;
    Ok((session, updated))
}

/// Seconds since the Unix epoch (for logs/UI).
pub fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn acct(name: &str, uuid: &str) -> StoredAccount {
        StoredAccount {
            name: name.into(),
            uuid: uuid.into(),
            refresh_token: format!("rt-{name}"),
            needs_sign_in: false,
        }
    }

    #[test]
    fn pre_1_1_single_account_becomes_the_active_one() {
        let s = decode_store(br#"{"name":"Bringing","uuid":"ba14","refresh_token":"x"}"#).unwrap();
        assert_eq!(s.accounts.len(), 1);
        assert_eq!(s.active.as_deref(), Some("ba14"));
        assert_eq!(s.active_account().unwrap().name, "Bringing");
        assert!(!s.accounts[0].needs_sign_in);
    }

    #[test]
    fn store_roundtrip_and_cleanup() {
        let mut s = AccountStore::default();
        s.upsert(acct("A", "a"));
        s.upsert(acct("B", "b"));
        assert_eq!(s.active.as_deref(), Some("a")); // first added becomes active
        assert!(s.set_active("b"));
        assert!(!s.set_active("zzz"));
        // Updating keeps position and the active marker.
        s.upsert(StoredAccount {
            name: "B2".into(),
            ..acct("B", "b")
        });
        assert_eq!(
            s.summaries()
                .iter()
                .map(|a| a.name.as_str())
                .collect::<Vec<_>>(),
            ["A", "B2"]
        );
        assert_eq!(s.active.as_deref(), Some("b"));
        let json = serde_json::to_vec(&s).unwrap();
        let back = decode_store(&json).unwrap();
        assert_eq!(back.active.as_deref(), Some("b"));
        assert_eq!(back.accounts.len(), 2);
        // Removing the active account activates the next one.
        let mut back = back;
        assert!(back.remove("b"));
        assert_eq!(back.active.as_deref(), Some("a"));
        assert!(back.remove("a"));
        assert_eq!(back.active, None);
        assert!(!back.remove("a"));
    }

    #[test]
    fn written_store_is_still_readable_by_pre_1_1_launchers() {
        let mut s = AccountStore::default();
        s.upsert(acct("A", "a"));
        s.upsert(acct("B", "b"));
        s.set_active("b");
        let bytes = encode_store(&s).unwrap();
        // What a 1.0.x launcher does: deserialize one StoredAccount, ignoring unknown fields.
        #[derive(Deserialize)]
        struct Old {
            name: String,
            uuid: String,
            refresh_token: String,
        }
        let old: Old = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(
            (
                old.name.as_str(),
                old.uuid.as_str(),
                old.refresh_token.as_str()
            ),
            ("B", "b", "rt-B")
        );
        // And this version reads the full list back.
        let back = decode_store(&bytes).unwrap();
        assert_eq!(back.accounts.len(), 2);
        assert_eq!(back.active.as_deref(), Some("b"));
    }

    #[test]
    fn store_drops_duplicates_and_dangling_active() {
        let s = decode_store(
            br#"{"schema":2,"active":"gone","accounts":[
                {"name":"A","uuid":"a","refresh_token":"1"},
                {"name":"A-dup","uuid":"a","refresh_token":"2"},
                {"name":"B","uuid":"b","refresh_token":"3","needs_sign_in":true}]}"#,
        )
        .unwrap();
        assert_eq!(s.accounts.len(), 2);
        assert_eq!(s.accounts[0].refresh_token, "1");
        assert_eq!(s.active.as_deref(), Some("a"));
        assert!(s.get("b").unwrap().needs_sign_in);
        assert!(decode_store(b"[1,2]").is_err());
    }

    #[test]
    fn summaries_carry_no_credentials() {
        let a = acct("A", "a");
        let dbg = format!("{:?}", a.summary());
        assert!(!dbg.contains("rt-A"));
    }

    #[test]
    fn offline_uuid_matches_vanilla() {
        // Known value: offline UUID for "Notch" is b50ad385-829d-3141-a216-7e7d7539ba7f.
        let s = Session::offline("Notch");
        assert_eq!(s.uuid, "b50ad385829d3141a2167e7d7539ba7f");
        assert_eq!(s.user_type, "legacy");
    }

    #[test]
    fn requires_client_id() {
        assert!(Authenticator::new("  ").is_err());
    }

    const APP: &str = "1a2b3c4d-1234-5678-9abc-def012345678";

    #[test]
    fn client_id_must_be_an_app_guid() {
        assert_eq!(
            validate_client_id(&format!("  {}  ", APP.to_uppercase())).unwrap(),
            APP
        );
        for bad in [
            "",
            "kestrel",
            "1a2b3c4d12345678 9abcdef012345678",
            "{1a2b3c4d-1234-5678-9abc-def012345678}",
            "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps",
            "00000000-0000-0000-0000-000000000000",
        ] {
            assert!(validate_client_id(bad).is_err(), "{bad:?} accepted");
        }
    }

    #[test]
    fn rejects_azure_portal_app_id_from_aadsts_errors() {
        let e = validate_client_id("74658136-14EC-4630-AD9B-26E160FF0FC6").unwrap_err();
        assert!(e.to_string().contains("Azure portal"), "{e}");
        assert!(Authenticator::new("74658136-14ec-4630-ad9b-26e160ff0fc6").is_err());
    }

    #[test]
    fn device_code_request_uses_consumers_authority_and_configured_client_id() {
        let (url, form) = device_code_request(APP);
        assert_eq!(
            url,
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        );
        assert_eq!(
            TOKEN_URL,
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        );
        assert_eq!(form[0], ("client_id", APP.to_string()));
        assert_eq!(
            form[1],
            ("scope", "XboxLive.signin offline_access".to_string())
        );
        for u in [DEVICE_CODE_URL, TOKEN_URL] {
            assert!(
                !u.contains("/common/") && !u.contains("/organizations/"),
                "{u}"
            );
            assert!(!u.contains("portal.azure.com") && !u.contains("entra.microsoft.com"));
        }
    }

    #[test]
    fn verification_uri_must_be_a_microsoft_sign_in_page() {
        for ok in [
            "https://www.microsoft.com/link",
            "https://microsoft.com/devicelogin",
            "https://login.microsoftonline.com/common/oauth2/deviceauth",
            "https://login.live.com/oauth20_remoteconnect.srf",
        ] {
            assert_eq!(validate_verification_uri(ok).unwrap(), ok);
        }
        for bad in [
            "http://www.microsoft.com/link",
            "https://portal.azure.com/",
            "https://entra.microsoft.com/#home",
            "https://microsoft.com.evil.example/link",
            "https://evil.example/?next=https://www.microsoft.com/link",
            "https://www.microsoft.com@evil.example/link",
            "https://www.microsoft.com:8443/link",
            "javascript:alert(1)",
            "",
        ] {
            assert!(validate_verification_uri(bad).is_err(), "{bad:?} accepted");
        }
    }

    /// Serve one canned HTTP response and hand back the raw request.
    fn one_shot_server(response_body: &'static str) -> (String, std::thread::JoinHandle<String>) {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let handle = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            let mut req = Vec::new();
            let mut buf = [0u8; 4096];
            loop {
                let n = s.read(&mut buf).unwrap();
                if n == 0 {
                    break;
                }
                req.extend_from_slice(&buf[..n]);
                let text = String::from_utf8_lossy(&req);
                if let Some(end) = text.find("\r\n\r\n") {
                    let len = text[..end]
                        .lines()
                        .find_map(|l| {
                            let (k, v) = l.split_once(':')?;
                            k.eq_ignore_ascii_case("content-length")
                                .then(|| v.trim().parse::<usize>().ok())?
                        })
                        .unwrap_or(0);
                    if req.len() >= end + 4 + len {
                        break;
                    }
                }
            }
            let reply = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                response_body.len(),
                response_body
            );
            s.write_all(reply.as_bytes()).unwrap();
            String::from_utf8(req).unwrap()
        });
        (format!("http://{addr}"), handle)
    }

    #[test]
    fn sends_the_configured_client_id_and_returns_the_verification_url() {
        let (base, server) = one_shot_server(
            r#"{"user_code":"ABCD-EFGH","device_code":"dc","verification_uri":"https://www.microsoft.com/link","expires_in":900,"interval":5,"message":"To sign in, use a web browser to open the page https://www.microsoft.com/link and enter the code ABCD-EFGH to authenticate."}"#,
        );
        let auth = Authenticator::new(&format!(" {APP} "))
            .unwrap()
            .with_oauth_endpoints(
                &format!("{base}/consumers/oauth2/v2.0/devicecode"),
                &format!("{base}/consumers/oauth2/v2.0/token"),
            );
        let code = auth.start_device_code().unwrap();
        let req = server.join().unwrap();
        let (head, body) = req.split_once("\r\n\r\n").unwrap();
        assert!(
            head.starts_with("POST /consumers/oauth2/v2.0/devicecode HTTP/1.1"),
            "{head}"
        );
        assert!(
            head.to_ascii_lowercase()
                .contains("content-type: application/x-www-form-urlencoded"),
            "{head}"
        );
        assert_eq!(
            body,
            format!("client_id={APP}&scope=XboxLive.signin+offline_access")
        );
        assert_eq!(code.verification_uri, "https://www.microsoft.com/link");
        assert_eq!(code.user_code, "ABCD-EFGH");
    }

    #[test]
    fn refuses_a_device_code_response_pointing_at_the_azure_portal() {
        let (base, server) = one_shot_server(
            r#"{"user_code":"ABCD-EFGH","device_code":"dc","verification_uri":"https://portal.azure.com/","expires_in":900,"interval":5}"#,
        );
        let auth = Authenticator::new(APP)
            .unwrap()
            .with_oauth_endpoints(&format!("{base}/devicecode"), &format!("{base}/token"));
        let err = auth.start_device_code().unwrap_err();
        server.join().unwrap();
        assert!(err.to_string().contains("refusing to open"), "{err}");
    }

    #[test]
    fn json_path() {
        let v: serde_json::Value =
            serde_json::from_str(r#"{"DisplayClaims":{"xui":[{"uhs":"123"}]}}"#).unwrap();
        assert_eq!(
            str_field(&v, &["DisplayClaims", "xui", "0", "uhs"]).unwrap(),
            "123"
        );
        assert!(str_field(&v, &["nope"]).is_err());
    }

    #[test]
    fn xsts_messages() {
        assert!(
            xsts_error(401, r#"{"XErr":2148916238}"#)
                .unwrap()
                .contains("child")
        );
        assert!(xsts_error(200, "").is_none());
    }
}
