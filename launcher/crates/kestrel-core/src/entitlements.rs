//! Cosmetic rewards an account owns: codes redeemed with the Night cosmetics
//! service (`services/cosmetics`), plus a few rewards built in for particular
//! accounts.
//!
//! The service keeps each account's redemptions and answers with an
//! *entitlement document*: a JSON payload and the service's RSA signature
//! over it (key held only as a Cloudflare secret). The launcher and the game
//! check that signature with the public key bundled in
//! `assets/kestrel/entitlement_public_key.txt` and that the document is for
//! the account asking. A cached document that was edited, or copied from
//! another account, therefore unlocks nothing.
//!
//! Redeeming is signed with the account's Mojang player certificate (the
//! same proof the cape sync uses), so nobody can redeem for an account they
//! can't sign in to. Retries are safe: the service grants a code once per
//! account and answers "already redeemed" afterwards.
//!
//! Cache: `<data folder>/entitlements/<uuid>.json` (one signed document per
//! account), and a copy in each instance's `kestrel/entitlements` before a
//! launch so the game has it at start-up without the network.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use serde::{Deserialize, Serialize};

use crate::auth::Session;
use crate::cosmetics::normalize_uuid;

/// Reward id of the complete official Minecraft cape collection.
pub const OFFICIAL_CAPES: &str = "official_capes";
pub const PROTOCOL: &str = "night-entitlements-v1";
pub const REDEEM_PROTOCOL: &str = "night-redeem-v1";
const CERTIFICATE_URL: &str = "https://api.minecraftservices.com/player/certificates";
const MAX_DOC: u64 = 64 * 1024;

/// Rewards some accounts have without redeeming a code (32-hex UUID). Must
/// match `BUILTIN_GRANTS` in services/cosmetics/src/rewards.js and the mod.
pub const BUILTIN_GRANTS: &[(&str, &[&str])] = &[
    // bringing
    ("ba14933ea7ad48168696a788cc1b3c1f", &[OFFICIAL_CAPES]),
];

const PUBLIC_KEY_TXT: &str =
    include_str!("../../../../client/src/main/resources/assets/kestrel/entitlement_public_key.txt");
const SERVICE_JSON: &str =
    include_str!("../../../../client/src/main/resources/assets/kestrel/cosmetics_service.json");

/// Bumped whenever a cached document changes, so pages can re-read cheaply.
static GENERATION: AtomicU64 = AtomicU64::new(0);

pub fn generation() -> u64 {
    GENERATION.load(Ordering::Relaxed)
}

/// What the service signs (see services/cosmetics/src/lib.js).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SignedDoc {
    pub payload: String,
    pub signature: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct RewardInfo {
    #[serde(rename = "type")]
    pub kind: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct Redemption {
    pub code: String,
    #[serde(default)]
    pub description: String,
    /// Unix seconds.
    pub at: u64,
    #[serde(default)]
    pub rewards: Vec<String>,
}

/// A verified entitlement document.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct Entitlements {
    pub v: u32,
    pub protocol: String,
    pub uuid: String,
    pub rewards: Vec<String>,
    #[serde(default)]
    pub catalog: BTreeMap<String, RewardInfo>,
    #[serde(default)]
    pub redemptions: Vec<Redemption>,
    #[serde(default)]
    pub issued: u64,
}

impl Entitlements {
    pub fn has(&self, reward: &str) -> bool {
        self.rewards.iter().any(|r| r == reward)
    }
}

/// Built-in rewards of an account.
pub fn builtin(uuid: Option<&str>) -> &'static [&'static str] {
    let Some(u) = uuid.and_then(normalize_uuid) else {
        return &[];
    };
    BUILTIN_GRANTS
        .iter()
        .find(|(id, _)| *id == u)
        .map(|(_, r)| *r)
        .unwrap_or(&[])
}

/// Everything an account has unlocked (built in and redeemed), for deciding
/// what exclusive content it may see and use. An account without a reward
/// gets no trace of the content that needs it.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Unlocks {
    rewards: std::collections::BTreeSet<String>,
}

impl Unlocks {
    /// An account's unlocks: built-in grants plus its verified cached document.
    /// `None` (offline or signed out) has none.
    pub fn for_account(root: &Path, uuid: Option<&str>) -> Unlocks {
        let mut rewards: std::collections::BTreeSet<String> =
            builtin(uuid).iter().map(|r| (*r).to_string()).collect();
        if let Some(e) = uuid.and_then(|u| cached(root, u)) {
            rewards.extend(e.rewards);
        }
        Unlocks { rewards }
    }

    pub fn from_rewards<I: IntoIterator<Item = S>, S: Into<String>>(rewards: I) -> Unlocks {
        Unlocks {
            rewards: rewards.into_iter().map(Into::into).collect(),
        }
    }

    pub fn has(&self, reward: &str) -> bool {
        self.rewards.contains(reward)
    }

    /// Whether content that requires `requires` (`None`: nothing) is available.
    pub fn allows(&self, requires: Option<&str>) -> bool {
        requires.is_none_or(|r| self.has(r))
    }
}

// ---- signature check --------------------------------------------------------------------

/// The service's public key (SubjectPublicKeyInfo DER), or None while none is bundled.
fn bundled_key() -> Option<Vec<u8>> {
    let text: String = PUBLIC_KEY_TXT
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#') && !l.starts_with("-----"))
        .collect();
    if text.is_empty() {
        return None;
    }
    B64.decode(text).ok()
}

/// Whether this build can check entitlement documents.
pub fn configured() -> bool {
    bundled_key().is_some() && service_url().is_some()
}

/// Read one DER element at `pos`: (tag, content start, content end).
fn der(data: &[u8], pos: usize) -> Option<(u8, usize, usize)> {
    let tag = *data.get(pos)?;
    let first = *data.get(pos + 1)? as usize;
    let (len, start) = if first < 0x80 {
        (first, pos + 2)
    } else {
        let n = first & 0x7f;
        if n == 0 || n > 3 {
            return None;
        }
        let mut len = 0usize;
        for i in 0..n {
            len = (len << 8) | *data.get(pos + 2 + i)? as usize;
        }
        (len, pos + 2 + n)
    };
    let end = start.checked_add(len)?;
    (end <= data.len()).then_some((tag, start, end))
}

/// The PKCS#1 RSAPublicKey inside an RSA SubjectPublicKeyInfo (what `ring` verifies with).
fn spki_to_pkcs1(spki: &[u8]) -> Option<&[u8]> {
    const RSA_OID: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01];
    let (t, s, e) = der(spki, 0)?;
    if t != 0x30 || e != spki.len() {
        return None;
    }
    let (t, alg_s, alg_e) = der(spki, s)?;
    if t != 0x30 {
        return None;
    }
    let (t, oid_s, oid_e) = der(spki, alg_s)?;
    if t != 0x06 || &spki[oid_s..oid_e] != RSA_OID || oid_e > alg_e {
        return None;
    }
    let (t, bs, be) = der(spki, alg_e)?;
    if t != 0x03 || be != e || spki.get(bs) != Some(&0) {
        return None;
    }
    Some(&spki[bs + 1..be])
}

/// Check `doc` against `spki` and that it is the document of `uuid`.
pub fn open_with(doc: &SignedDoc, uuid: &str, spki: &[u8]) -> Result<Entitlements, String> {
    let want = normalize_uuid(uuid).ok_or("invalid account id")?;
    let payload = B64
        .decode(doc.payload.trim())
        .map_err(|_| "bad payload encoding")?;
    let sig = B64
        .decode(doc.signature.trim())
        .map_err(|_| "bad signature encoding")?;
    let pkcs1 = spki_to_pkcs1(spki).ok_or("bad public key")?;
    ring::signature::UnparsedPublicKey::new(&ring::signature::RSA_PKCS1_2048_8192_SHA256, pkcs1)
        .verify(&payload, &sig)
        .map_err(|_| "the signature does not match")?;
    let e: Entitlements =
        serde_json::from_slice(&payload).map_err(|e| format!("bad entitlement document: {e}"))?;
    if e.v != 1 || e.protocol != PROTOCOL {
        return Err("unsupported entitlement document".into());
    }
    if normalize_uuid(&e.uuid).as_deref() != Some(want.as_str()) {
        return Err("the document is for another account".into());
    }
    Ok(e)
}

/// Check `doc` with the bundled key.
pub fn open(doc: &SignedDoc, uuid: &str) -> Result<Entitlements, String> {
    let key = bundled_key().ok_or("this build has no entitlement key")?;
    open_with(doc, uuid, &key)
}

// ---- cache -------------------------------------------------------------------------------

/// Cached documents of the launcher: `<root>/entitlements`.
pub fn cache_dir(root: &Path) -> PathBuf {
    root.join("entitlements")
}

/// The game's copy inside an instance: `<instance>/kestrel/entitlements`.
pub fn game_cache_dir(client_data: &Path) -> PathBuf {
    client_data.join("entitlements")
}

fn cache_file(dir: &Path, uuid: &str) -> Option<PathBuf> {
    normalize_uuid(uuid).map(|u| dir.join(format!("{u}.json")))
}

/// The verified cached document of an account in `dir`, if any.
pub fn load_from(dir: &Path, uuid: &str) -> Option<Entitlements> {
    let path = cache_file(dir, uuid)?;
    let bytes = std::fs::read(&path).ok()?;
    let doc: SignedDoc = serde_json::from_slice(&bytes).ok()?;
    match open(&doc, uuid) {
        Ok(e) => Some(e),
        Err(err) => {
            log::warn!("ignoring entitlement cache {}: {err}", path.display());
            None
        }
    }
}

/// The launcher's verified cached document of an account.
pub fn cached(root: &Path, uuid: &str) -> Option<Entitlements> {
    load_from(&cache_dir(root), uuid)
}

/// Store a verified document for `uuid` in `dir` (written atomically).
pub fn store_in(dir: &Path, uuid: &str, doc: &SignedDoc) -> std::io::Result<()> {
    let path = cache_file(dir, uuid).ok_or_else(|| std::io::Error::other("invalid account id"))?;
    std::fs::create_dir_all(dir)?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(
        &tmp,
        serde_json::to_vec(doc).map_err(std::io::Error::other)?,
    )?;
    std::fs::rename(&tmp, &path)?;
    GENERATION.fetch_add(1, Ordering::Relaxed);
    Ok(())
}

/// Copy an account's cached document into a game's cache, so the game knows
/// its rewards at start-up. Nothing to copy is not an error.
pub fn copy_to_game(root: &Path, client_data: &Path, uuid: &str) -> std::io::Result<()> {
    let (Some(src), Some(dst)) = (
        cache_file(&cache_dir(root), uuid),
        cache_file(&game_cache_dir(client_data), uuid),
    ) else {
        return Ok(());
    };
    match std::fs::read(&src) {
        Ok(bytes) => {
            std::fs::create_dir_all(game_cache_dir(client_data))?;
            let tmp = dst.with_extension("json.tmp");
            std::fs::write(&tmp, bytes)?;
            std::fs::rename(&tmp, &dst)
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e),
    }
}

/// Whether an account (None: offline or signed out) has a reward, from its
/// built-in grants and verified cached document.
pub fn has_reward(root: &Path, uuid: Option<&str>, reward: &str) -> bool {
    if builtin(uuid).contains(&reward) {
        return true;
    }
    uuid.and_then(|u| cached(root, u))
        .is_some_and(|e| e.has(reward))
}

// ---- service -----------------------------------------------------------------------------

/// Base URL of the cosmetics service (no trailing slash), if this build has one.
pub fn service_url() -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(SERVICE_JSON).ok()?;
    let url = v
        .get("url")?
        .as_str()?
        .trim()
        .trim_end_matches('/')
        .to_string();
    url.starts_with("https://").then_some(url)
}

fn host_of(url: &str) -> &str {
    url.strip_prefix("https://")
        .and_then(|r| r.split('/').next())
        .unwrap_or("")
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(Duration::from_secs(15)))
        .max_redirects(0)
        .user_agent(format!("Night-Launcher/{}", crate::LAUNCHER_VERSION))
        .build()
        .into()
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Fetch, check and cache an account's document from the service.
pub fn refresh(root: &Path, uuid: &str) -> Result<Entitlements, String> {
    let base = service_url().ok_or("this build has no cosmetics service")?;
    let u = normalize_uuid(uuid).ok_or("invalid account id")?;
    let url = format!("{base}/v1/entitlements/{u}");
    let mut resp = agent()
        .get(&url)
        .call()
        .map_err(|e| format!("can't reach the cosmetics service: {e}"))?;
    let status = resp.status().as_u16();
    let body = resp
        .body_mut()
        .with_config()
        .limit(MAX_DOC)
        .read_to_string()
        .map_err(|e| format!("can't read the cosmetics service's reply: {e}"))?;
    if status != 200 {
        return Err(format!("the cosmetics service answered HTTP {status}"));
    }
    let doc: SignedDoc =
        serde_json::from_str(&body).map_err(|_| "the cosmetics service sent a bad reply")?;
    let e = open(&doc, &u)?;
    store_in(&cache_dir(root), &u, &doc).map_err(|e| format!("can't save rewards: {e}"))?;
    Ok(e)
}

/// How a redemption ended (errors are [`RedeemError`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Redeemed {
    /// Newly redeemed now.
    New {
        code: String,
        entitlements: Entitlements,
    },
    /// This account had already redeemed it (also the answer to a retry).
    Already {
        code: String,
        entitlements: Entitlements,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RedeemError {
    /// Offline account or no account: there is nothing to bind the code to.
    NeedsMicrosoftAccount,
    InvalidCode,
    Expired,
    LimitReached,
    /// Couldn't complete it (network, service, Mojang); trying again is safe.
    Failed(String),
}

impl std::fmt::Display for RedeemError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RedeemError::NeedsMicrosoftAccount => {
                write!(
                    f,
                    "Codes are redeemed for a Microsoft account. Sign in (offline mode can't redeem)."
                )
            }
            RedeemError::InvalidCode => write!(
                f,
                "That code doesn't exist. Check the spelling and try again."
            ),
            RedeemError::Expired => write!(f, "That code has expired."),
            RedeemError::LimitReached => write!(f, "That code has been used up."),
            RedeemError::Failed(m) => write!(f, "{m}"),
        }
    }
}

/// Upper-case, trimmed code, or None when it can't be a code (same rule as the service).
pub fn normalize_code(raw: &str) -> Option<String> {
    let c = raw.trim().to_ascii_uppercase();
    let mut chars = c.chars();
    let first_ok = chars.next().is_some_and(|ch| ch.is_ascii_alphanumeric());
    (first_ok
        && c.len() <= 32
        && c.chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ch == '_' || ch == '-'))
    .then_some(c)
}

/// The text the player's certificate key signs to redeem. Must match redeemText in the service.
pub fn redeem_text(uuid: &str, code: &str, ts: u64, nonce: &str) -> String {
    format!("{REDEEM_PROTOCOL}|{uuid}|{code}|{ts}|{nonce}")
}

/// The DER inside a PEM block (Mojang labels its PKCS#8 keys "RSA PRIVATE KEY").
fn pem(text: &str) -> Result<Vec<u8>, String> {
    let b64: String = text
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with("-----"))
        .collect();
    B64.decode(b64)
        .map_err(|_| "bad key in Mojang's reply".to_string())
}

/// An account's Mojang player certificate.
struct Certificate {
    private_key: Vec<u8>,
    public_key: Vec<u8>,
    mojang_signature: Vec<u8>,
    expires_at_ms: i64,
}

fn parse_certificate(json: &str) -> Result<Certificate, String> {
    let v: serde_json::Value =
        serde_json::from_str(json).map_err(|_| "bad certificate reply from Mojang")?;
    let field = |o: &serde_json::Value, k: &str| -> Result<String, String> {
        o.get(k)
            .and_then(|x| x.as_str())
            .map(str::to_string)
            .ok_or_else(|| format!("Mojang's certificate reply has no {k}"))
    };
    let kp = v
        .get("keyPair")
        .ok_or("Mojang's certificate reply has no keyPair")?;
    let expires = field(&v, "expiresAt")?;
    Ok(Certificate {
        private_key: pem(&field(kp, "privateKey")?)?,
        public_key: pem(&field(kp, "publicKey")?)?,
        mojang_signature: B64
            .decode(field(&v, "publicKeySignatureV2")?)
            .map_err(|_| "bad signature in Mojang's reply")?,
        expires_at_ms: parse_rfc3339_ms(&expires).ok_or("bad expiry in Mojang's reply")?,
    })
}

/// Milliseconds since the epoch of an RFC 3339 UTC time such as
/// `2026-11-08T03:12:45.123456Z` (what Mojang sends).
fn parse_rfc3339_ms(s: &str) -> Option<i64> {
    let s = s.trim();
    let (date, rest) = s.split_once('T')?;
    let mut d = date.split('-');
    let (y, mo, da): (i64, i64, i64) = (
        d.next()?.parse().ok()?,
        d.next()?.parse().ok()?,
        d.next()?.parse().ok()?,
    );
    let (time, offset_ms) = if let Some(t) = rest.strip_suffix('Z') {
        (t, 0)
    } else {
        let i = rest.rfind(['+', '-'])?;
        let (t, off) = rest.split_at(i);
        let sign = if off.starts_with('-') { -1 } else { 1 };
        let mut hm = off[1..].split(':');
        let (h, m): (i64, i64) = (
            hm.next()?.parse().ok()?,
            hm.next().unwrap_or("0").parse().ok()?,
        );
        (t, sign * (h * 60 + m) * 60_000)
    };
    let (hms, frac) = time.split_once('.').unwrap_or((time, ""));
    let mut t = hms.split(':');
    let (h, mi, se): (i64, i64, i64) = (
        t.next()?.parse().ok()?,
        t.next()?.parse().ok()?,
        t.next()?.parse().ok()?,
    );
    let ms: i64 = format!("{:0<3}", frac.chars().take(3).collect::<String>())
        .parse()
        .ok()?;
    // Days from civil (Howard Hinnant's algorithm).
    let yy = if mo <= 2 { y - 1 } else { y };
    let era = if yy >= 0 { yy } else { yy - 399 } / 400;
    let yoe = yy - era * 400;
    let doy = (153 * (mo + if mo > 2 { -3 } else { 9 }) + 2) / 5 + da - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    Some(((days * 24 + h) * 60 + mi) * 60_000 + se * 1000 + ms - offset_ms)
}

fn fetch_certificate(agent: &ureq::Agent, session: &Session) -> Result<Certificate, String> {
    let mut resp = agent
        .post(CERTIFICATE_URL)
        .header("Authorization", format!("Bearer {}", session.access_token))
        .send_empty()
        .map_err(|e| format!("can't reach Mojang: {e}"))?;
    let status = resp.status().as_u16();
    let body = resp
        .body_mut()
        .with_config()
        .limit(MAX_DOC)
        .read_to_string()
        .map_err(|e| format!("can't read Mojang's reply: {e}"))?;
    if status != 200 {
        return Err(format!(
            "Mojang didn't issue a player certificate (HTTP {status})"
        ));
    }
    parse_certificate(&body)
}

/// Redeem `code` for the signed-in account of `session`, and cache the
/// resulting document. Retrying after any failure is safe.
pub fn redeem(root: &Path, session: &Session, code: &str) -> Result<Redeemed, RedeemError> {
    if session.user_type != "msa" {
        return Err(RedeemError::NeedsMicrosoftAccount);
    }
    let uuid = normalize_uuid(&session.uuid).ok_or(RedeemError::NeedsMicrosoftAccount)?;
    let code = normalize_code(code).ok_or(RedeemError::InvalidCode)?;
    let base = service_url()
        .ok_or_else(|| RedeemError::Failed("This build can't redeem codes.".into()))?;
    if bundled_key().is_none() {
        return Err(RedeemError::Failed("This build can't redeem codes.".into()));
    }
    let agent = agent();
    let cert = fetch_certificate(&agent, session).map_err(RedeemError::Failed)?;

    let rng = ring::rand::SystemRandom::new();
    let mut nonce = [0u8; 16];
    ring::rand::SecureRandom::fill(&rng, &mut nonce)
        .map_err(|_| RedeemError::Failed("no system randomness".into()))?;
    let nonce = hex::encode(nonce);
    let ts = now_secs();
    let key = ring::signature::RsaKeyPair::from_pkcs8(&cert.private_key)
        .map_err(|_| RedeemError::Failed("Mojang's player key couldn't be used".into()))?;
    let mut sig = vec![0u8; key.public().modulus_len()];
    key.sign(
        &ring::signature::RSA_PKCS1_SHA256,
        &rng,
        redeem_text(&uuid, &code, ts, &nonce).as_bytes(),
        &mut sig,
    )
    .map_err(|_| RedeemError::Failed("couldn't sign the request".into()))?;
    let body = serde_json::json!({
        "uuid": uuid,
        "code": code,
        "ts": ts,
        "nonce": nonce,
        "key": {
            "publicKey": B64.encode(&cert.public_key),
            "expiresAt": cert.expires_at_ms,
            "signature": B64.encode(&cert.mojang_signature),
        },
        "signature": B64.encode(&sig),
    });
    let url = format!("{base}/v1/redeem");
    if host_of(&url).is_empty() {
        return Err(RedeemError::Failed("bad service address".into()));
    }
    let mut resp = agent
        .post(&url)
        .header("Accept", "application/json")
        .send_json(body)
        .map_err(|e| {
            RedeemError::Failed(format!(
                "Couldn't reach the cosmetics service ({e}). Try again."
            ))
        })?;
    let status = resp.status().as_u16();
    let text = resp
        .body_mut()
        .with_config()
        .limit(MAX_DOC)
        .read_to_string()
        .map_err(|e| RedeemError::Failed(format!("Couldn't read the reply ({e}). Try again.")))?;
    let reply: serde_json::Value = serde_json::from_str(&text).unwrap_or(serde_json::Value::Null);
    let reason = reply.get("reason").and_then(|r| r.as_str()).unwrap_or("");
    match (status, reason) {
        (404, "invalid_code") => return Err(RedeemError::InvalidCode),
        (410, "expired") => return Err(RedeemError::Expired),
        (410, "limit_reached") => return Err(RedeemError::LimitReached),
        (200, _) => {}
        _ => {
            let msg = reply
                .get("error")
                .and_then(|e| e.as_str())
                .unwrap_or("unexpected reply");
            return Err(RedeemError::Failed(format!(
                "The cosmetics service refused it (HTTP {status}: {msg}). Try again."
            )));
        }
    }
    let doc: SignedDoc = reply
        .get("entitlements")
        .cloned()
        .and_then(|d| serde_json::from_value(d).ok())
        .ok_or_else(|| RedeemError::Failed("The reply had no rewards. Try again.".into()))?;
    let entitlements = open(&doc, &uuid).map_err(RedeemError::Failed)?;
    store_in(&cache_dir(root), &uuid, &doc)
        .map_err(|e| RedeemError::Failed(format!("Redeemed, but the rewards couldn't be saved ({e}). Open the Redeem page again to reload them.")))?;
    let code = reply
        .get("code")
        .and_then(|c| c.as_str())
        .unwrap_or(&code)
        .to_string();
    match reply.get("status").and_then(|s| s.as_str()) {
        Some("already_redeemed") => Ok(Redeemed::Already { code, entitlements }),
        _ => Ok(Redeemed::New { code, entitlements }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // A test key pair (not the service's), RSA 2048, made with openssl for these tests only.
    const TEST_PKCS8: &str = include_str!("../tests/fixtures/entitlement-test-key.pk8.b64");
    const TEST_SPKI: &str = include_str!("../tests/fixtures/entitlement-test-key.spki.b64");
    const UUID: &str = "0123456789abcdef0123456789abcdef";
    const OTHER: &str = "fedcba9876543210fedcba9876543210";

    fn spki() -> Vec<u8> {
        B64.decode(TEST_SPKI.trim()).unwrap()
    }

    fn sign(payload: &str) -> SignedDoc {
        let key = ring::signature::RsaKeyPair::from_pkcs8(&B64.decode(TEST_PKCS8.trim()).unwrap())
            .unwrap();
        let mut sig = vec![0u8; key.public().modulus_len()];
        key.sign(
            &ring::signature::RSA_PKCS1_SHA256,
            &ring::rand::SystemRandom::new(),
            payload.as_bytes(),
            &mut sig,
        )
        .unwrap();
        SignedDoc {
            payload: B64.encode(payload),
            signature: B64.encode(sig),
        }
    }

    fn payload(uuid: &str, rewards: &[&str]) -> String {
        serde_json::json!({
            "v": 1, "protocol": PROTOCOL, "uuid": uuid, "rewards": rewards,
            "catalog": {"official_capes": {"type": "cape_collection", "name": "Official Minecraft capes", "description": "d"}},
            "redemptions": [{"code": "LOVEMOJANG", "description": "x", "at": 1800000000u64, "rewards": rewards}],
            "issued": 1800000000u64
        })
        .to_string()
    }

    #[test]
    fn a_signed_document_opens_for_its_account_only() {
        let doc = sign(&payload(UUID, &[OFFICIAL_CAPES]));
        let e = open_with(&doc, "01234567-89AB-cdef-0123-456789abcdef", &spki()).unwrap();
        assert!(e.has(OFFICIAL_CAPES));
        assert_eq!(e.redemptions[0].code, "LOVEMOJANG");
        assert_eq!(e.catalog[OFFICIAL_CAPES].kind, "cape_collection");
        // Another account can't use it.
        assert!(
            open_with(&doc, OTHER, &spki())
                .unwrap_err()
                .contains("another account")
        );
    }

    #[test]
    fn documents_signed_by_the_worker_open() {
        // Made by services/cosmetics (lib.js, WebCrypto) with the same test key.
        let doc: SignedDoc = serde_json::from_str(include_str!(
            "../tests/fixtures/entitlement-worker-doc.json"
        ))
        .unwrap();
        let e = open_with(&doc, UUID, &spki()).unwrap();
        assert_eq!(e.rewards, vec![OFFICIAL_CAPES.to_string()]);
        assert_eq!(e.redemptions.len(), 1);
        assert_eq!(e.redemptions[0].at, 1_800_000_000);
        assert_eq!(e.catalog[OFFICIAL_CAPES].name, "Official Minecraft capes");
    }

    #[test]
    fn edited_documents_are_refused() {
        let doc = sign(&payload(UUID, &[]));
        // Grant yourself the capes by editing the payload: the signature no longer matches.
        let forged = SignedDoc {
            payload: B64.encode(payload(UUID, &[OFFICIAL_CAPES])),
            signature: doc.signature.clone(),
        };
        assert!(open_with(&forged, UUID, &spki()).is_err());
        let garbled = SignedDoc {
            payload: doc.payload.clone(),
            signature: "AAAA".into(),
        };
        assert!(open_with(&garbled, UUID, &spki()).is_err());
        let bad_proto = sign(&payload(UUID, &[]).replace(PROTOCOL, "other"));
        assert!(open_with(&bad_proto, UUID, &spki()).is_err());
        assert!(spki_to_pkcs1(b"\x30\x00").is_none());
    }

    #[test]
    fn builtin_grant_for_bringing_only() {
        assert_eq!(
            builtin(Some("BA14933E-A7AD-4816-8696-A788CC1B3C1F")),
            &[OFFICIAL_CAPES]
        );
        assert!(builtin(Some(UUID)).is_empty());
        assert!(builtin(None).is_empty());
        assert!(builtin(Some("bringing")).is_empty());
        let dir = tempfile::tempdir().unwrap();
        let has = |u: Option<&str>| Unlocks::for_account(dir.path(), u).has(OFFICIAL_CAPES);
        assert!(has(Some("ba14933ea7ad48168696a788cc1b3c1f")));
        assert!(!has(Some(UUID)));
        assert!(!has(None));
        // Offline sessions get an OfflinePlayer UUID, never the owner's.
        assert!(!has(Some(&Session::offline("bringing").uuid)));
    }

    #[test]
    fn unlocks_gate_required_content() {
        let none = Unlocks::default();
        let some = Unlocks::from_rewards([OFFICIAL_CAPES]);
        assert!(none.allows(None) && some.allows(None));
        assert!(!none.allows(Some(OFFICIAL_CAPES)));
        assert!(some.allows(Some(OFFICIAL_CAPES)));
        assert!(!some.allows(Some("ghost_cape")));
    }

    #[test]
    fn cache_files_are_per_account_and_copied_for_the_game() {
        let dir = tempfile::tempdir().unwrap();
        let doc = SignedDoc {
            payload: "cA==".into(),
            signature: "cw==".into(),
        };
        let before = generation();
        store_in(&cache_dir(dir.path()), UUID, &doc).unwrap();
        assert!(generation() > before);
        assert!(cache_dir(dir.path()).join(format!("{UUID}.json")).is_file());
        // Unverifiable (test signature, not the bundled key): unlocks nothing.
        assert!(!has_reward(dir.path(), Some(UUID), OFFICIAL_CAPES));
        let game = dir.path().join("instance/kestrel");
        copy_to_game(dir.path(), &game, UUID).unwrap();
        assert_eq!(
            std::fs::read(game_cache_dir(&game).join(format!("{UUID}.json"))).unwrap(),
            std::fs::read(cache_dir(dir.path()).join(format!("{UUID}.json"))).unwrap()
        );
        copy_to_game(dir.path(), &game, OTHER).unwrap();
        assert!(!game_cache_dir(&game).join(format!("{OTHER}.json")).exists());
        assert!(store_in(&cache_dir(dir.path()), "../escape", &doc).is_err());
    }

    #[test]
    fn codes_and_redeem_text_match_the_service() {
        assert_eq!(normalize_code("  loveMojang "), Some("LOVEMOJANG".into()));
        assert_eq!(normalize_code("love mojang"), None);
        assert_eq!(normalize_code(""), None);
        assert_eq!(normalize_code("-X"), None);
        assert_eq!(
            redeem_text(UUID, "LOVEMOJANG", 1800000000, &"a".repeat(32)),
            format!(
                "night-redeem-v1|{UUID}|LOVEMOJANG|1800000000|{}",
                "a".repeat(32)
            )
        );
    }

    #[test]
    fn offline_sessions_cannot_redeem() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(
            redeem(dir.path(), &Session::offline("x"), "LOVEMOJANG"),
            Err(RedeemError::NeedsMicrosoftAccount)
        );
    }

    #[test]
    fn mojang_times_parse() {
        assert_eq!(parse_rfc3339_ms("1970-01-01T00:00:00Z"), Some(0));
        // Microseconds (Mojang's format) are truncated to milliseconds.
        assert_eq!(
            parse_rfc3339_ms("2027-01-15T03:12:45.123456Z"),
            parse_rfc3339_ms("2027-01-15T03:12:45.123Z")
        );
        assert_eq!(
            parse_rfc3339_ms("2026-11-07T12:00:00+01:00"),
            parse_rfc3339_ms("2026-11-07T11:00:00Z")
        );
        assert_eq!(
            parse_rfc3339_ms("2026-11-07T11:00:00.5Z").unwrap()
                - parse_rfc3339_ms("2026-11-07T11:00:00Z").unwrap(),
            500
        );
        assert_eq!(
            parse_rfc3339_ms("2026-11-07T11:00:00Z"),
            Some(1_794_049_200_000)
        );
        assert!(parse_rfc3339_ms("yesterday").is_none());
    }
}
