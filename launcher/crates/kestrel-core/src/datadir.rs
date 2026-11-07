//! Where Night Launcher keeps its data, and the one-time move from the folder
//! earlier versions used.
//!
//! Every version from Kestrel 0.2 to Night Launcher 1.1 kept everything in
//! `%LOCALAPPDATA%\Kestrel` with the same layout (later versions only added
//! folders: `mods-disabled`/`mods-trash` in 0.2.1, `instances` in 1.1).
//! From 1.1.1 the folder is `%LOCALAPPDATA%\Night Launcher`:
//!
//! * a new user gets `Night Launcher` straight away ([`RootPlan::Ready`]);
//! * a user with data in `Kestrel` gets it copied ([`migrate`]): every file is
//!   copied, then compared byte-for-byte (SHA-256) with the original, absolute
//!   paths inside the copied settings are pointed at the new folder, and only
//!   then is `migration.json` marked complete and the new folder used. The
//!   `Kestrel` folder is left exactly as it was (plus a note saying where the
//!   data went) until the user removes it ([`remove_legacy`]);
//! * an interrupted or failed copy leaves `migration.json` at "copying"; the
//!   next start resumes it (files already copied and identical are skipped), so
//!   running it any number of times is safe;
//! * if `Night Launcher` already holds data that was not migrated (the user
//!   started fresh first), it wins and `Kestrel` is never copied over it.
//!
//! `--root <dir>`, `NIGHT_LAUNCHER_HOME` (or the older `KESTREL_HOME`) pin the
//! data folder exactly and skip all of this. `NIGHT_LAUNCHER_LOCALAPPDATA`
//! replaces the per-user application-data folder itself; it exists to test a
//! fresh Windows profile or an upgrade without touching the real one.

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{Error, IoContext, Result};
use crate::paths::atomic_write;
use crate::progress::SharedProgress;

#[cfg(windows)]
pub const DATA_DIR: &str = "Night Launcher";
#[cfg(windows)]
pub const LEGACY_DIR: &str = "Kestrel";
#[cfg(not(windows))]
pub const DATA_DIR: &str = "night-launcher";
#[cfg(not(windows))]
pub const LEGACY_DIR: &str = "kestrel";

pub const MARKER: &str = "migration.json";
/// Left in the old folder once its data has been copied.
pub const LEGACY_NOTE: &str = "MOVED-TO-NIGHT-LAUNCHER.txt";
pub const ENV_HOME: &str = "NIGHT_LAUNCHER_HOME";
pub const ENV_HOME_LEGACY: &str = "KESTREL_HOME";
pub const ENV_LOCAL_APPDATA: &str = "NIGHT_LAUNCHER_LOCALAPPDATA";

/// Launcher downloads that are re-created on demand and need not be copied.
const SKIP_DIRS: &[&str] = &["updates"];
/// Held open (without sharing) while a game runs; never copied.
const SKIP_FILES: &[&str] = &["running.lock", "instance-running.lock"];
/// The old launcher logs keep their history under the new `logs` folder.
const OLD_LOGS_TO: &str = "logs/from-kestrel";
const MAX_REWRITE_JSON: u64 = 8 * 1024 * 1024;

fn env_abs(name: &str) -> Option<PathBuf> {
    let v = std::env::var_os(name)?;
    let p = PathBuf::from(v);
    (!p.as_os_str().is_empty() && p.is_absolute()).then_some(p)
}

/// The per-user local application-data folder (`%LOCALAPPDATA%`), from
/// Windows itself (the known-folder API, so a missing or odd environment
/// doesn't matter). Never relative and never the working directory.
pub fn local_app_data() -> Option<PathBuf> {
    if let Some(p) = env_abs(ENV_LOCAL_APPDATA) {
        return Some(p);
    }
    #[cfg(windows)]
    {
        if let Some(p) = known_local_app_data() {
            return Some(p);
        }
        if let Some(p) = env_abs("LOCALAPPDATA") {
            return Some(p);
        }
        env_abs("USERPROFILE").map(|h| h.join("AppData").join("Local"))
    }
    #[cfg(not(windows))]
    {
        env_abs("XDG_DATA_HOME").or_else(|| env_abs("HOME").map(|h| h.join(".local").join("share")))
    }
}

#[cfg(windows)]
fn known_local_app_data() -> Option<PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    use windows_sys::Win32::System::Com::CoTaskMemFree;
    use windows_sys::Win32::UI::Shell::{
        FOLDERID_LocalAppData, KF_FLAG_DEFAULT, SHGetKnownFolderPath,
    };
    let mut out: windows_sys::core::PWSTR = std::ptr::null_mut();
    // SAFETY: `out` receives a CoTaskMem-allocated, NUL-terminated wide string on
    // success (and may be set even on failure); it is read only up to its NUL and
    // freed exactly once with CoTaskMemFree.
    unsafe {
        let hr = SHGetKnownFolderPath(
            &FOLDERID_LocalAppData,
            KF_FLAG_DEFAULT as u32,
            std::ptr::null_mut(),
            &mut out,
        );
        let result = if hr >= 0 && !out.is_null() {
            let mut len = 0usize;
            while *out.add(len) != 0 {
                len += 1;
            }
            let s = std::ffi::OsString::from_wide(std::slice::from_raw_parts(out, len));
            Some(PathBuf::from(s))
        } else {
            None
        };
        if !out.is_null() {
            CoTaskMemFree(out as *const core::ffi::c_void);
        }
        result.filter(|p| p.is_absolute())
    }
}

/// What the launcher should do about its data folder at start-up.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RootPlan {
    /// `--root` or an environment variable named the folder: use it as is.
    Explicit(PathBuf),
    /// Use `root`. `legacy` is the old folder when it still exists next to it
    /// after a completed migration (offered for removal).
    Ready {
        root: PathBuf,
        legacy: Option<PathBuf>,
    },
    /// Copy the old folder's data into `to` first.
    Migrate { from: PathBuf, to: PathBuf },
    /// No application-data folder could be found at all.
    Unavailable,
}

/// Decide the data folder. `explicit` is a `--root` argument, if any.
pub fn plan(explicit: Option<PathBuf>) -> RootPlan {
    if let Some(p) = explicit
        .or_else(|| env_abs(ENV_HOME))
        .or_else(|| env_abs(ENV_HOME_LEGACY))
    {
        return RootPlan::Explicit(std::path::absolute(&p).unwrap_or(p));
    }
    match local_app_data() {
        Some(base) => plan_in(&base),
        None => RootPlan::Unavailable,
    }
}

/// [`plan`] for a given application-data folder.
pub fn plan_in(base: &Path) -> RootPlan {
    let new = base.join(DATA_DIR);
    let old = base.join(LEGACY_DIR);
    match read_marker(&new).map(|m| m.status) {
        Some(Status::Complete) => RootPlan::Ready {
            legacy: old.is_dir().then_some(old),
            root: new,
        },
        Some(Status::Copying) if has_data(&old) => RootPlan::Migrate { from: old, to: new },
        _ if has_data(&new) => RootPlan::Ready {
            root: new,
            legacy: None, // not migrated from it: never offer to remove it
        },
        _ if has_data(&old) => RootPlan::Migrate { from: old, to: new },
        _ => RootPlan::Ready {
            root: new,
            legacy: None,
        },
    }
}

/// Whether a folder holds launcher data (any version's layout). Only files a
/// user's activity creates count; the empty folders a fresh start creates
/// (`Layout::ensure`) do not, so they can never hide data waiting to move.
pub fn has_data(dir: &Path) -> bool {
    let non_empty = |p: PathBuf| {
        fs::read_dir(p)
            .map(|mut r| r.next().is_some())
            .unwrap_or(false)
    };
    [
        "launcher.json",
        "accounts.dat",
        "install-state.json",
        "instance/options.txt",
    ]
    .iter()
    .any(|n| dir.join(n).is_file())
        || non_empty(dir.join("instance").join("saves"))
        || non_empty(dir.join("instance").join("mods"))
        || fs::read_dir(dir.join("instances"))
            .map(|r| {
                r.flatten()
                    .any(|e| e.path().join("night-instance.json").is_file())
            })
            .unwrap_or(false)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Status {
    Copying,
    Complete,
}

/// `migration.json` in the new folder.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Marker {
    pub status: Status,
    pub from: PathBuf,
    pub launcher_version: String,
    pub started: u64,
    #[serde(default)]
    pub finished: Option<u64>,
    #[serde(default)]
    pub files: u64,
    #[serde(default)]
    pub bytes: u64,
    /// Settings files whose absolute paths were pointed at the new folder.
    #[serde(default)]
    pub rewritten: Vec<String>,
    /// Entries not copied (symbolic links, junctions) with the reason.
    #[serde(default)]
    pub skipped: Vec<String>,
}

pub fn read_marker(root: &Path) -> Option<Marker> {
    let b = fs::read(root.join(MARKER)).ok()?;
    serde_json::from_slice(&b).ok()
}

fn write_marker(root: &Path, m: &Marker) -> Result<()> {
    let data = serde_json::to_vec_pretty(m).map_err(|e| Error::json("migration.json", e))?;
    atomic_write(&root.join(MARKER), &data)
}

/// One file to copy: path relative to the old folder, and where it goes.
#[derive(Debug, Clone)]
struct Entry {
    from_rel: PathBuf,
    to_rel: PathBuf,
    size: u64,
}

fn is_reparse_point(md: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        md.file_attributes() & 0x400 != 0 // FILE_ATTRIBUTE_REPARSE_POINT
    }
    #[cfg(not(windows))]
    {
        md.file_type().is_symlink()
    }
}

fn scan(root: &Path, rel: &Path, out: &mut Vec<Entry>, skipped: &mut Vec<String>) -> Result<()> {
    let dir = root.join(rel);
    for e in fs::read_dir(&dir).at(&dir)? {
        let e = e.at(&dir)?;
        let name = e.file_name();
        let child = rel.join(&name);
        let md = fs::symlink_metadata(e.path()).at(e.path())?;
        if is_reparse_point(&md) {
            // Could point anywhere (even back into this folder); never followed.
            skipped.push(format!("{} (link)", child.display()));
            continue;
        }
        let name = name.to_string_lossy();
        if md.is_dir() {
            if rel.as_os_str().is_empty() && SKIP_DIRS.contains(&name.as_ref()) {
                continue;
            }
            scan(root, &child, out, skipped)?;
        } else if md.is_file() {
            let top = rel.as_os_str().is_empty();
            if SKIP_FILES.contains(&name.as_ref())
                || (top && (name == LEGACY_NOTE || name == MARKER))
            {
                continue;
            }
            let to_rel = match child.strip_prefix("logs") {
                Ok(rest) if rel.starts_with("logs") || rel.as_os_str() == "logs" => {
                    Path::new(OLD_LOGS_TO).join(rest)
                }
                _ => child.clone(),
            };
            out.push(Entry {
                from_rel: child,
                to_rel,
                size: md.len(),
            });
        }
    }
    Ok(())
}

fn sha256_file(path: &Path) -> Result<[u8; 32]> {
    let mut f = fs::File::open(path).at(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut buf).at(path)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finalize().into())
}

/// Is a game started from this folder running? (1.1+ hold a lock file open
/// without sharing while a game runs; Windows refuses to open it again.)
fn games_running(root: &Path) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        let mut locks = vec![root.join("instance-running.lock")];
        if let Ok(rd) = fs::read_dir(root.join("instances")) {
            locks.extend(
                rd.flatten()
                    .map(|e| e.path().join("night-launcher").join("running.lock")),
            );
        }
        locks.iter().filter(|p| p.is_file()).any(|p| {
            fs::OpenOptions::new()
                .read(true)
                .share_mode(0)
                .open(p)
                .is_err_and(|e| e.raw_os_error() == Some(32))
        })
    }
    #[cfg(not(windows))]
    {
        let _ = root;
        false
    }
}

/// Free space for the current user on the volume holding `dir`.
fn free_space(dir: &Path) -> Option<u64> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
        let wide: Vec<u16> = dir
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let mut avail = 0u64;
        // SAFETY: `wide` is NUL-terminated and outlives the call; the out-pointers are
        // either valid u64s or null, as the API allows.
        let ok = unsafe {
            GetDiskFreeSpaceExW(
                wide.as_ptr(),
                &mut avail,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        };
        (ok != 0).then_some(avail)
    }
    #[cfg(not(windows))]
    {
        let _ = dir;
        None
    }
}

/// What a migration did.
#[derive(Debug, Clone)]
pub struct Report {
    pub files: u64,
    pub bytes: u64,
    /// Files already present and identical (a resumed migration).
    pub reused: u64,
    pub rewritten: Vec<String>,
    pub skipped: Vec<String>,
}

/// Copy `from` (the old folder) into `to`, verify every file, point absolute
/// paths in the copied settings at `to`, then mark `to` ready. `from` is only
/// read (apart from a short note added at the very end). Safe to call again
/// after any failure.
pub fn migrate(from: &Path, to: &Path, progress: &SharedProgress) -> Result<Report> {
    if games_running(from) {
        return Err(Error::Other(
            "a game started from the Kestrel folder is still running; close it, then restart Night Launcher".into(),
        ));
    }
    progress.stage("Looking at your Kestrel data", 0);
    let mut entries = Vec::new();
    let mut skipped = Vec::new();
    scan(from, Path::new(""), &mut entries, &mut skipped)?;
    let total: u64 = entries.iter().map(|e| e.size).sum();
    log::info!(
        "migration: {} files, {} MB from {} to {}",
        entries.len(),
        total / (1024 * 1024),
        from.display(),
        to.display()
    );
    fs::create_dir_all(to).at(to)?;
    if let Some(free) = free_space(to) {
        let need = total + 64 * 1024 * 1024;
        if free < need {
            return Err(Error::Other(format!(
                "not enough free disk space to copy your data: {} MB needed, {} MB free",
                need / (1024 * 1024),
                free / (1024 * 1024)
            )));
        }
    }
    let started = read_marker(to)
        .filter(|m| m.status == Status::Copying)
        .map(|m| m.started)
        .unwrap_or_else(crate::auth::now_secs);
    let mut marker = Marker {
        status: Status::Copying,
        from: from.to_path_buf(),
        launcher_version: crate::LAUNCHER_VERSION.to_string(),
        started,
        finished: None,
        files: entries.len() as u64,
        bytes: total,
        rewritten: Vec::new(),
        skipped: skipped.clone(),
    };
    write_marker(to, &marker)?;

    // 1. Copy (skip files a previous attempt already copied identically).
    progress.stage("Copying your data to the Night Launcher folder", total);
    let mut reused = 0u64;
    for e in &entries {
        progress.check_cancel()?;
        let src = from.join(&e.from_rel);
        let dst = to.join(&e.to_rel);
        let same = dst.is_file()
            && fs::metadata(&dst)
                .map(|m| m.len() == e.size)
                .unwrap_or(false)
            && sha256_file(&src)? == sha256_file(&dst)?;
        if same {
            reused += 1;
        } else {
            if let Some(parent) = dst.parent() {
                fs::create_dir_all(parent).at(parent)?;
            }
            let tmp = dst.with_file_name(format!(
                "{}.migrating",
                dst.file_name()
                    .map(|n| n.to_string_lossy())
                    .unwrap_or_default()
            ));
            fs::copy(&src, &tmp).at(&src)?;
            fs::rename(&tmp, &dst).at(&dst)?;
        }
        progress.add(e.size);
    }

    // 2. Verify every file against the original.
    progress.stage("Checking the copy", total);
    for e in &entries {
        progress.check_cancel()?;
        let src = from.join(&e.from_rel);
        let dst = to.join(&e.to_rel);
        if sha256_file(&src)? != sha256_file(&dst)? {
            return Err(Error::Other(format!(
                "the copy of {} does not match the original; nothing was switched over",
                e.from_rel.display()
            )));
        }
        progress.add(e.size);
    }

    // 3. Settings that stored absolute paths into the old folder.
    progress.stage("Updating saved paths", 0);
    let rewritten = rewrite_paths(to, from)?;
    for r in &rewritten {
        log::info!("migration: updated paths in {r}");
    }

    // 4. Switch over.
    marker.status = Status::Complete;
    marker.finished = Some(crate::auth::now_secs());
    marker.rewritten = rewritten.clone();
    write_marker(to, &marker)?;
    let note = format!(
        "Night Launcher {} copied everything in this folder to:\r\n  {}\r\n\r\n\
         Night Launcher now uses that folder. This folder was left untouched as a backup; \
         remove it from Night Launcher (Settings > Advanced) once you're happy, or delete it yourself.\r\n",
        crate::LAUNCHER_VERSION,
        to.display()
    );
    if let Err(e) = fs::write(from.join(LEGACY_NOTE), note) {
        log::warn!("migration: could not leave a note in the old folder: {e}");
    }
    log::info!(
        "migration complete: {} files ({} reused), {} skipped",
        entries.len(),
        reused,
        skipped.len()
    );
    Ok(Report {
        files: entries.len() as u64,
        bytes: total,
        reused,
        rewritten,
        skipped,
    })
}

/// Replace `old` (as a path prefix) with `new` inside a string. Matches are
/// case-insensitive on Windows and must end at a separator or the string's end.
pub fn replace_root(s: &str, old: &Path, new: &Path) -> Option<String> {
    let old = old.to_string_lossy();
    let new = new.to_string_lossy();
    if old.is_empty() {
        return None;
    }
    let (hay, needle) = if cfg!(windows) {
        (s.to_lowercase(), old.to_lowercase())
    } else {
        (s.to_string(), old.to_string())
    };
    // Lower-casing can change byte lengths for some non-ASCII text; only map
    // positions when it didn't.
    if hay.len() != s.len() || needle.len() != old.len() {
        return None;
    }
    let mut out = String::with_capacity(s.len());
    let mut last = 0;
    let mut changed = false;
    let mut from = 0;
    while let Some(i) = hay[from..].find(&needle) {
        let at = from + i;
        let end = at + needle.len();
        let boundary = matches!(
            s[end..].chars().next(),
            None | Some('\\') | Some('/') | Some('"') | Some(';')
        );
        if boundary {
            out.push_str(&s[last..at]);
            out.push_str(&new);
            last = end;
            changed = true;
        }
        from = end;
    }
    if !changed {
        return None;
    }
    out.push_str(&s[last..]);
    Some(out)
}

fn rewrite_value(v: &mut serde_json::Value, old: &Path, new: &Path) -> bool {
    match v {
        serde_json::Value::String(s) => match replace_root(s, old, new) {
            Some(r) => {
                *s = r;
                true
            }
            None => false,
        },
        serde_json::Value::Array(a) => a
            .iter_mut()
            .fold(false, |c, x| rewrite_value(x, old, new) | c),
        serde_json::Value::Object(o) => o
            .values_mut()
            .fold(false, |c, x| rewrite_value(x, old, new) | c),
        _ => false,
    }
}

/// Point absolute paths inside the copied JSON settings (install state,
/// launcher and instance settings, client configs) at the new folder.
/// Returns the files changed, relative to `root`.
fn rewrite_paths(root: &Path, old: &Path) -> Result<Vec<String>> {
    let mut changed = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for e in fs::read_dir(&dir).at(&dir)? {
            let e = e.at(&dir)?;
            let p = e.path();
            let md = fs::symlink_metadata(&p).at(&p)?;
            if is_reparse_point(&md) {
                continue;
            }
            if md.is_dir() {
                // Worlds and downloaded game files hold no launcher paths.
                let n = e.file_name();
                if !matches!(
                    n.to_str(),
                    Some("saves" | "assets" | "libraries" | "versions" | "runtime")
                ) {
                    stack.push(p);
                }
                continue;
            }
            if p.extension().and_then(|x| x.to_str()) != Some("json")
                || md.len() > MAX_REWRITE_JSON
                || p == root.join(MARKER)
            {
                continue;
            }
            let Ok(bytes) = fs::read(&p) else { continue };
            let Ok(mut v) = serde_json::from_slice::<serde_json::Value>(&bytes) else {
                continue;
            };
            if rewrite_value(&mut v, old, root) {
                let data = serde_json::to_vec_pretty(&v).map_err(|e| Error::json("settings", e))?;
                atomic_write(&p, &data)?;
                changed.push(p.strip_prefix(root).unwrap_or(&p).display().to_string());
            }
        }
    }
    changed.sort();
    Ok(changed)
}

/// Move the old folder to the Recycle Bin after a completed migration. Refuses
/// if the migration isn't complete, the folder isn't the one migrated from, or
/// the running launcher itself lives inside it.
pub fn remove_legacy(root: &Path, legacy: &Path) -> Result<()> {
    let m = read_marker(root)
        .filter(|m| m.status == Status::Complete)
        .ok_or_else(|| {
            Error::Other("the move to the Night Launcher folder hasn't finished".into())
        })?;
    if m.from != legacy {
        return Err(Error::Other(
            "that is not the folder Night Launcher copied from".into(),
        ));
    }
    if let Ok(exe) = std::env::current_exe()
        && let (Ok(exe), Ok(old)) = (fs::canonicalize(exe), fs::canonicalize(legacy))
        && exe.starts_with(&old)
    {
        return Err(Error::Other(
            "Night Launcher itself is running from inside the old folder; move NightLauncher.exe out first".into(),
        ));
    }
    if games_running(legacy) {
        return Err(Error::Other(
            "a game started from the old folder is still running".into(),
        ));
    }
    recycle(legacy)
}

#[cfg(windows)]
fn recycle(dir: &Path) -> Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::UI::Shell::{
        FO_DELETE, FOF_ALLOWUNDO, FOF_NOCONFIRMATION, FOF_NOERRORUI, FOF_SILENT, SHFILEOPSTRUCTW,
        SHFileOperationW,
    };
    // pFrom is a list of NUL-terminated paths ending with an extra NUL.
    let from: Vec<u16> = dir.as_os_str().encode_wide().chain([0, 0]).collect();
    let mut op = SHFILEOPSTRUCTW {
        wFunc: FO_DELETE,
        pFrom: from.as_ptr(),
        fFlags: (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI | FOF_SILENT) as u16,
        ..Default::default()
    };
    // SAFETY: `op` is fully initialised; `from` is double-NUL-terminated and outlives the call.
    let r = unsafe { SHFileOperationW(&mut op) };
    if r != 0 || op.fAnyOperationsAborted != 0 {
        return Err(Error::Other(format!(
            "Windows could not move the folder to the Recycle Bin (code {r})"
        )));
    }
    if dir.exists() {
        return Err(Error::Other(
            "the folder is still there; close anything using it and try again".into(),
        ));
    }
    Ok(())
}

#[cfg(not(windows))]
fn recycle(_dir: &Path) -> Result<()> {
    Err(Error::Other(
        "removing the old folder from the launcher is only supported on Windows; delete it by hand"
            .into(),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::progress::Progress;

    fn write(p: &Path, s: &str) {
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, s).unwrap();
    }

    /// A Kestrel-era folder like 1.1.0 left it.
    fn old_install(base: &Path) -> PathBuf {
        let old = base.join(LEGACY_DIR);
        let o = old.display().to_string().replace('\\', "\\\\");
        write(
            &old.join("launcher.json"),
            r#"{"config_version":1,"max_ram_mib":3072}"#,
        );
        write(&old.join("accounts.dat"), "encrypted-bytes");
        write(
            &old.join("install-state.json"),
            &format!(
                r#"{{"schema":3,"managed_java":"{o}{s}runtime{s}bin{s}java.exe","classpath":["{o}{s}libraries{s}a.jar"],"logging_arg":"-Dlog4j.configurationFile={o}{s}assets{s}log_configs{s}client.xml","other":"C:\\elsewhere\\x"}}"#,
                s = if cfg!(windows) { "\\\\" } else { "/" }
            ),
        );
        write(&old.join("instance/options.txt"), "fov:0.5\n");
        write(&old.join("instance/saves/World/level.dat"), "world");
        write(
            &old.join("instance/kestrel/config.json"),
            r#"{"cape":"ember"}"#,
        );
        write(
            &old.join("instances/PvP/night-instance.json"),
            r#"{"name":"PvP"}"#,
        );
        write(&old.join("mods-disabled/off.jar"), "jar");
        write(&old.join("logs/launcher.log"), "old log");
        write(
            &old.join("updates/NightLauncher-9.9.9.exe.part"),
            "partial download",
        );
        write(&old.join("instance-running.lock"), "");
        old
    }

    #[test]
    fn fresh_user_gets_the_new_folder() {
        let d = tempfile::tempdir().unwrap();
        assert_eq!(
            plan_in(d.path()),
            RootPlan::Ready {
                root: d.path().join(DATA_DIR),
                legacy: None
            }
        );
    }

    #[test]
    fn explicit_root_skips_everything() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("custom root with spaces");
        assert_eq!(plan(Some(p.clone())), RootPlan::Explicit(p));
    }

    #[test]
    fn kestrel_data_is_copied_verified_and_paths_updated() {
        let d = tempfile::tempdir().unwrap();
        let old = old_install(d.path());
        let new = d.path().join(DATA_DIR);
        assert_eq!(
            plan_in(d.path()),
            RootPlan::Migrate {
                from: old.clone(),
                to: new.clone()
            }
        );
        let before = fs::read(old.join("install-state.json")).unwrap();
        let r = migrate(&old, &new, &Progress::new()).unwrap();
        assert_eq!(r.reused, 0);
        assert_eq!(
            fs::read_to_string(new.join("instance/saves/World/level.dat")).unwrap(),
            "world"
        );
        assert_eq!(
            fs::read_to_string(new.join("accounts.dat")).unwrap(),
            "encrypted-bytes"
        );
        assert!(new.join("instances/PvP/night-instance.json").is_file());
        assert!(new.join("mods-disabled/off.jar").is_file());
        // Old logs keep their history in a subfolder; downloads and locks are not copied.
        assert_eq!(
            fs::read_to_string(new.join("logs/from-kestrel/launcher.log")).unwrap(),
            "old log"
        );
        assert!(!new.join("logs/launcher.log").exists());
        assert!(!new.join("updates").exists());
        assert!(!new.join("instance-running.lock").exists());
        // Absolute paths now point into the new folder; others are untouched.
        let st: serde_json::Value =
            serde_json::from_slice(&fs::read(new.join("install-state.json")).unwrap()).unwrap();
        let java = st["managed_java"].as_str().unwrap();
        assert!(java.starts_with(&new.display().to_string()), "{java}");
        assert!(
            st["classpath"][0]
                .as_str()
                .unwrap()
                .starts_with(&new.display().to_string())
        );
        assert!(
            st["logging_arg"]
                .as_str()
                .unwrap()
                .contains(&new.display().to_string())
        );
        assert!(
            !st.to_string()
                .contains(&format!("{}{}", LEGACY_DIR, std::path::MAIN_SEPARATOR))
                || !cfg!(windows)
        );
        assert_eq!(st["other"], "C:\\elsewhere\\x");
        assert_eq!(r.rewritten, vec!["install-state.json".to_string()]);
        // The old folder is untouched apart from the note.
        assert_eq!(fs::read(old.join("install-state.json")).unwrap(), before);
        assert!(old.join(LEGACY_NOTE).is_file());
        assert!(old.join("instance/saves/World/level.dat").is_file());
        // From now on the new folder is used and the old one offered for removal.
        assert_eq!(read_marker(&new).unwrap().status, Status::Complete);
        assert_eq!(
            plan_in(d.path()),
            RootPlan::Ready {
                root: new.clone(),
                legacy: Some(old.clone())
            }
        );
    }

    #[test]
    fn interrupted_migration_resumes_and_reuses_identical_files() {
        let d = tempfile::tempdir().unwrap();
        let old = old_install(d.path());
        let new = d.path().join(DATA_DIR);
        // A previous attempt got part of the way: marker says copying, one file
        // copied, one half-written.
        fs::create_dir_all(new.join("instance")).unwrap();
        write_marker(
            &new,
            &Marker {
                status: Status::Copying,
                from: old.clone(),
                launcher_version: "1.1.1".into(),
                started: 5,
                finished: None,
                files: 0,
                bytes: 0,
                rewritten: vec![],
                skipped: vec![],
            },
        )
        .unwrap();
        fs::copy(old.join("accounts.dat"), new.join("accounts.dat")).unwrap();
        write(&new.join("instance/options.txt"), "fov:0."); // truncated
        assert!(matches!(plan_in(d.path()), RootPlan::Migrate { .. }));
        let r = migrate(&old, &new, &Progress::new()).unwrap();
        assert!(r.reused >= 1);
        assert_eq!(
            fs::read_to_string(new.join("instance/options.txt")).unwrap(),
            "fov:0.5\n"
        );
        assert_eq!(read_marker(&new).unwrap().started, 5);
        // Running it again changes nothing and still succeeds.
        // Running it again is harmless: only the files whose paths were updated are
        // copied again (and updated again).
        let again = migrate(&old, &new, &Progress::new()).unwrap();
        assert_eq!(again.reused, again.files - again.rewritten.len() as u64);
        assert_eq!(again.files, r.files);
    }

    #[test]
    fn existing_night_launcher_data_is_never_overwritten() {
        let d = tempfile::tempdir().unwrap();
        old_install(d.path());
        let new = d.path().join(DATA_DIR);
        write(
            &new.join("launcher.json"),
            r#"{"config_version":1,"max_ram_mib":8192}"#,
        );
        assert_eq!(
            plan_in(d.path()),
            RootPlan::Ready {
                root: new.clone(),
                legacy: None
            }
        );
    }

    #[test]
    fn oldest_layouts_migrate_too() {
        // Kestrel 0.2.0: no mods-disabled, no instances, single-account file.
        let d = tempfile::tempdir().unwrap();
        let old = d.path().join(LEGACY_DIR);
        write(&old.join("launcher.json"), r#"{"max_ram_mib":2048}"#);
        write(&old.join("instance/mods/a.jar"), "a");
        let new = d.path().join(DATA_DIR);
        assert!(matches!(plan_in(d.path()), RootPlan::Migrate { .. }));
        migrate(&old, &new, &Progress::new()).unwrap();
        assert!(new.join("instance/mods/a.jar").is_file());
        // An empty or unrelated Kestrel folder is not "data".
        let d2 = tempfile::tempdir().unwrap();
        fs::create_dir_all(d2.path().join(LEGACY_DIR).join("logs")).unwrap();
        assert!(matches!(
            plan_in(d2.path()),
            RootPlan::Ready { legacy: None, .. }
        ));
    }

    #[test]
    fn empty_folders_from_a_fresh_start_never_block_a_migration() {
        let d = tempfile::tempdir().unwrap();
        let old = old_install(d.path());
        let new = d.path().join(DATA_DIR);
        // e.g. a self-check or a crashed first start created the standard folders.
        crate::paths::Layout::new(new.clone()).ensure().unwrap();
        assert_eq!(plan_in(d.path()), RootPlan::Migrate { from: old, to: new });
    }

    #[test]
    fn root_replacement_respects_boundaries() {
        let old = if cfg!(windows) {
            PathBuf::from(r"C:\Users\A B\AppData\Local\Kestrel")
        } else {
            PathBuf::from("/home/a b/.local/share/kestrel")
        };
        let new = old.parent().unwrap().join(DATA_DIR);
        let o = old.display().to_string();
        let n = new.display().to_string();
        let sep = std::path::MAIN_SEPARATOR;
        assert_eq!(
            replace_root(&format!("{o}{sep}x"), &old, &new),
            Some(format!("{n}{sep}x"))
        );
        assert_eq!(replace_root(&o, &old, &new), Some(n.clone()));
        assert_eq!(
            replace_root(
                &format!("-Dlog4j.configurationFile={o}{sep}a.xml"),
                &old,
                &new
            ),
            Some(format!("-Dlog4j.configurationFile={n}{sep}a.xml"))
        );
        // A different folder that merely starts the same is left alone.
        assert_eq!(replace_root(&format!("{o}-backup{sep}x"), &old, &new), None);
        assert_eq!(replace_root("unrelated", &old, &new), None);
    }

    #[test]
    fn remove_legacy_needs_a_completed_migration_from_that_folder() {
        let d = tempfile::tempdir().unwrap();
        let old = old_install(d.path());
        let new = d.path().join(DATA_DIR);
        assert!(remove_legacy(&new, &old).is_err()); // not migrated
        migrate(&old, &new, &Progress::new()).unwrap();
        assert!(remove_legacy(&new, &d.path().join("elsewhere")).is_err());
        assert!(old.is_dir());
    }
}
