//! Minecraft instances: independent game directories that share one
//! installation (versions, libraries, assets and Java under the data root).
//!
//! On disk:
//!
//! ```text
//! <root>/instance/                      the Default instance (the original game folder,
//!                                       kept where it always was)
//! <root>/instance/night-instance.json   its settings
//! <root>/mods-disabled, mods-trash, logs   its launcher files (unchanged locations)
//! <root>/instances/<id>/                every other instance's game folder
//! <root>/instances/<id>/night-instance.json
//! <root>/instances/<id>/night-launcher/ its disabled mods, mod trash, game logs
//! <root>/instances-trash/<id>-<time>/   deleted instances (removed only by hand)
//! ```
//!
//! An instance's id is its folder name and never changes; renaming changes
//! only the display name, so nothing is moved while a game might be using it.
//! Instances are found by scanning those folders, so a damaged settings file
//! affects only its own instance.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::config::{GamePreset, LauncherConfig};
use crate::error::{Error, IoContext, Result};
use crate::paths::{Layout, atomic_write};
use crate::sysmem;

pub const DEFAULT_ID: &str = "default";
pub const META_FILE: &str = "night-instance.json";
const AUX_DIR: &str = "night-launcher";
const META_SCHEMA: u32 = 1;
/// The only game setup Night supports today: Minecraft 1.8.9 + Forge + the
/// Night client mod. Other versions need their own launch configuration and
/// client build before they can be offered.
pub const LOADER_NIGHT_FORGE: &str = "night-forge";
const MAX_NAME: usize = 40;

/// Per-instance launch settings (what was global in launcher.json before 1.1).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct GameSettings {
    /// Max heap (-Xmx) in MiB.
    pub max_ram_mib: u64,
    pub use_managed_java: bool,
    pub custom_java_path: String,
    pub use_default_jvm_args: bool,
    /// Extra JVM arguments, whitespace-separated.
    pub extra_jvm_args: String,
    pub window_width: u32,
    pub window_height: u32,
    pub fullscreen: bool,
    pub game_preset: GamePreset,
}

impl Default for GameSettings {
    fn default() -> Self {
        GameSettings::from_config(&LauncherConfig::default())
    }
}

impl GameSettings {
    /// The settings an instance had before instances existed.
    pub fn from_config(c: &LauncherConfig) -> GameSettings {
        GameSettings {
            max_ram_mib: c.max_ram_mib,
            use_managed_java: c.use_managed_java,
            custom_java_path: c.custom_java_path.clone(),
            use_default_jvm_args: c.use_default_jvm_args,
            extra_jvm_args: c.extra_jvm_args.clone(),
            window_width: c.window_width,
            window_height: c.window_height,
            fullscreen: c.fullscreen,
            game_preset: c.game_preset,
        }
    }

    /// Clamp every value into a safe range.
    pub fn validate(&mut self) {
        self.max_ram_mib = sysmem::clamp_max_mib(self.max_ram_mib, sysmem::total_mib());
        self.window_width = self.window_width.clamp(640, 7680);
        self.window_height = self.window_height.clamp(480, 4320);
        self.custom_java_path = self.custom_java_path.trim().to_string();
    }

    /// Extra JVM args split on whitespace, minus the ones the launcher controls.
    pub fn extra_jvm_args(&self) -> Vec<String> {
        crate::config::filter_jvm_args(&self.extra_jvm_args)
    }
}

/// Which account an instance launches with.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(tag = "mode", rename_all = "lowercase")]
pub enum AccountChoice {
    /// Whatever account is active in the launcher at launch time.
    #[default]
    Active,
    /// A specific saved account (by Minecraft UUID).
    Account { uuid: String },
    /// Offline session (singleplayer and offline-mode servers only).
    Offline,
}

/// What `night-instance.json` holds.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct InstanceMeta {
    pub schema: u32,
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub account: AccountChoice,
    pub game: GameSettings,
    /// Unix seconds.
    pub created: u64,
    pub last_launch: Option<u64>,
    /// Exit code of the last run (`None` = never ran, or killed without one).
    pub last_exit: Option<i32>,
}

impl Default for InstanceMeta {
    fn default() -> Self {
        InstanceMeta {
            schema: META_SCHEMA,
            name: String::new(),
            mc_version: crate::MC_VERSION.to_string(),
            loader: LOADER_NIGHT_FORGE.to_string(),
            account: AccountChoice::Active,
            game: GameSettings::default(),
            created: 0,
            last_launch: None,
            last_exit: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Instance {
    /// Folder name; `default` for the original instance.
    pub id: String,
    pub meta: InstanceMeta,
    pub game_dir: PathBuf,
    pub aux_dir: PathBuf,
}

impl Instance {
    pub fn is_default(&self) -> bool {
        self.id == DEFAULT_ID
    }

    /// The installation's layout with this instance selected.
    pub fn layout(&self, base: &Layout) -> Layout {
        base.with_instance(self.game_dir.clone(), self.aux_dir.clone())
    }

    pub fn meta_path(&self) -> PathBuf {
        self.game_dir.join(META_FILE)
    }

    /// Whether this build can launch it.
    pub fn supported(&self) -> bool {
        self.meta.mc_version == crate::MC_VERSION && self.meta.loader == LOADER_NIGHT_FORGE
    }

    pub fn save(&self) -> Result<()> {
        let mut m = self.meta.clone();
        m.schema = META_SCHEMA;
        m.game.validate();
        let data =
            serde_json::to_vec_pretty(&m).map_err(|e| Error::json("instance settings", e))?;
        atomic_write(&self.meta_path(), &data)
    }
}

fn read_meta(path: &Path) -> Option<std::result::Result<InstanceMeta, String>> {
    let bytes = match fs::read(path) {
        Ok(b) => b,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return None,
        Err(e) => return Some(Err(e.to_string())),
    };
    Some(
        serde_json::from_slice::<InstanceMeta>(&bytes)
            .map(|mut m| {
                m.game.validate();
                m
            })
            .map_err(|e| e.to_string()),
    )
}

/// The Default instance. Its settings default to the ones in launcher.json
/// (where they lived before instances existed) until it is first saved.
pub fn default_instance(layout: &Layout, cfg: &LauncherConfig) -> Instance {
    let base = layout.default_instance();
    let game_dir = base.instance();
    let mut meta = match read_meta(&game_dir.join(META_FILE)) {
        Some(Ok(m)) => m,
        Some(Err(e)) => {
            log::warn!("Default instance settings unreadable ({e}); using launcher settings");
            fresh_default_meta(cfg)
        }
        None => fresh_default_meta(cfg),
    };
    if meta.name.trim().is_empty() {
        meta.name = "Default".into();
    }
    Instance {
        id: DEFAULT_ID.into(),
        meta,
        game_dir,
        aux_dir: base.aux_dir().to_path_buf(),
    }
}

fn fresh_default_meta(cfg: &LauncherConfig) -> InstanceMeta {
    InstanceMeta {
        name: "Default".into(),
        game: GameSettings::from_config(cfg),
        ..InstanceMeta::default()
    }
}

/// Every instance: Default first, then the rest oldest first.
pub fn load_all(layout: &Layout, cfg: &LauncherConfig) -> Vec<Instance> {
    let mut out = vec![default_instance(layout, cfg)];
    let mut rest = Vec::new();
    if let Ok(rd) = fs::read_dir(layout.instances_dir()) {
        for e in rd.flatten() {
            let Ok(ft) = e.file_type() else { continue };
            if !ft.is_dir() {
                continue; // symlinks and files are never instances
            }
            let Some(id) = e.file_name().to_str().map(str::to_string) else {
                continue;
            };
            if !valid_id(&id) {
                continue; // includes in-progress ".creating-…" folders
            }
            let dir = e.path();
            let meta = match read_meta(&dir.join(META_FILE)) {
                Some(Ok(m)) => m,
                Some(Err(err)) => {
                    log::warn!(
                        "instance {id}: settings unreadable ({err}); showing it with defaults"
                    );
                    InstanceMeta {
                        name: id.clone(),
                        ..InstanceMeta::default()
                    }
                }
                None => continue, // not an instance folder
            };
            let mut inst = Instance {
                aux_dir: dir.join(AUX_DIR),
                game_dir: dir,
                id: id.clone(),
                meta,
            };
            if inst.meta.name.trim().is_empty() {
                inst.meta.name = id;
            }
            rest.push(inst);
        }
    }
    rest.sort_by(|a, b| {
        (a.meta.created, a.meta.name.to_lowercase())
            .cmp(&(b.meta.created, b.meta.name.to_lowercase()))
    });
    out.extend(rest);
    out
}

/// A folder name that is safe on Windows and can't escape `instances/`.
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_NAME
        && id != DEFAULT_ID
        && !id.starts_with('.')
        && !id.ends_with('.')
        && !id.ends_with(' ')
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, ' ' | '-' | '_' | '.' | '(' | ')'))
        && !is_reserved_windows_name(id)
}

fn is_reserved_windows_name(id: &str) -> bool {
    let stem = id
        .split('.')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_uppercase();
    matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((stem.starts_with("COM") || stem.starts_with("LPT"))
            && stem.len() == 4
            && stem.as_bytes()[3].is_ascii_digit())
}

/// Validate and tidy a display name.
pub fn clean_name(name: &str) -> Result<String> {
    let n: String = name.trim().chars().filter(|c| !c.is_control()).collect();
    if n.is_empty() {
        return Err(Error::Other("enter a name for the instance".into()));
    }
    if n.chars().count() > MAX_NAME {
        return Err(Error::Other(format!(
            "instance names can be at most {MAX_NAME} characters"
        )));
    }
    Ok(n)
}

/// A folder name derived from a display name, unique under `instances/`.
fn new_id(layout: &Layout, name: &str) -> String {
    let mut base: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, ' ' | '-' | '_') {
                c
            } else {
                '_'
            }
        })
        .collect::<String>()
        .trim()
        .trim_matches('.')
        .to_string();
    base.truncate(MAX_NAME - 5);
    let base = base.trim().to_string();
    let base = if base.is_empty() || !valid_id(&base) {
        "Instance".to_string()
    } else {
        base
    };
    let dir = layout.instances_dir();
    let mut id = base.clone();
    let mut n = 2;
    while dir.join(&id).exists() || id.eq_ignore_ascii_case(DEFAULT_ID) {
        id = format!("{base} ({n})");
        n += 1;
    }
    id
}

/// What to bring over from an existing instance when creating one.
#[derive(Debug, Clone, Default)]
pub struct CopyFrom {
    pub source: Option<Instance>,
    /// options.txt and other game options, keybinds, mod configs, Night client
    /// settings and cosmetics, server list.
    pub settings: bool,
    pub mods: bool,
    pub resource_packs: bool,
}

/// Game files that make up "settings" (relative to the game folder).
const SETTINGS_FILES: &[&str] = &[
    "options.txt",
    "optionsof.txt",
    "optionsshaders.txt",
    "servers.dat",
];
const SETTINGS_DIRS: &[&str] = &["config", "kestrel"];

/// Create an instance. The folder is assembled under a temporary name and
/// renamed into place at the end, so a failure never leaves a half-made
/// instance in the list.
pub fn create(
    layout: &Layout,
    name: &str,
    meta: InstanceMeta,
    copy: &CopyFrom,
) -> Result<Instance> {
    let name = clean_name(name)?;
    let parent = layout.instances_dir();
    fs::create_dir_all(&parent).at(&parent)?;
    let id = new_id(layout, &name);
    let tmp = parent.join(format!(
        ".creating-{}-{}",
        crate::auth::now_secs(),
        std::process::id()
    ));
    fs::create_dir(&tmp).at(&tmp)?;
    let result = (|| -> Result<()> {
        if let Some(src) = &copy.source {
            if copy.settings {
                for f in SETTINGS_FILES {
                    let p = src.game_dir.join(f);
                    if p.is_file() {
                        fs::copy(&p, tmp.join(f)).at(&p)?;
                    }
                }
                for d in SETTINGS_DIRS {
                    copy_tree(&src.game_dir.join(d), &tmp.join(d), &[])?;
                }
            }
            if copy.mods {
                copy_tree(&src.game_dir.join("mods"), &tmp.join("mods"), &[])?;
                copy_tree(
                    &src.layout(layout).disabled_mods(),
                    &tmp.join(AUX_DIR).join("mods-disabled"),
                    &[],
                )?;
            }
            if copy.resource_packs {
                copy_tree(
                    &src.game_dir.join("resourcepacks"),
                    &tmp.join("resourcepacks"),
                    &[],
                )?;
            }
        }
        Ok(())
    })();
    if let Err(e) = result {
        let _ = fs::remove_dir_all(&tmp);
        return Err(e);
    }
    let final_dir = parent.join(&id);
    let inst = Instance {
        id: id.clone(),
        meta: InstanceMeta {
            name,
            created: crate::auth::now_secs(),
            last_launch: None,
            last_exit: None,
            ..meta
        },
        aux_dir: final_dir.join(AUX_DIR),
        game_dir: final_dir.clone(),
    };
    // Write the settings into the temp folder, then move it into place.
    let staged = Instance {
        game_dir: tmp.clone(),
        aux_dir: tmp.join(AUX_DIR),
        ..inst.clone()
    };
    if let Err(e) = staged
        .save()
        .and_then(|_| fs::rename(&tmp, &final_dir).at(&final_dir))
    {
        let _ = fs::remove_dir_all(&tmp);
        return Err(e);
    }
    log::info!("created instance {id}");
    Ok(inst)
}

/// Folders a duplicate leaves behind: logs and crash reports belong to the
/// original's history.
const DUPLICATE_SKIP: &[&str] = &["logs", "crash-reports"];

/// Copy an instance completely (worlds, mods, packs, settings) under a new
/// name. The source must not be running.
pub fn duplicate(layout: &Layout, src: &Instance, name: &str) -> Result<Instance> {
    let mut meta = src.meta.clone();
    meta.name = clean_name(name)?;
    let inst = create(layout, &meta.name.clone(), meta, &CopyFrom::default())?;
    let result = (|| -> Result<()> {
        // `create` made the folder; fill it. The Default instance's launcher
        // files live at the data root, so copy them explicitly.
        let skip: Vec<&str> = DUPLICATE_SKIP
            .iter()
            .copied()
            .chain([META_FILE, AUX_DIR])
            .collect();
        copy_tree(&src.game_dir, &inst.game_dir, &skip)?;
        let src_layout = src.layout(layout);
        copy_tree(
            &src_layout.disabled_mods(),
            &inst.layout(layout).disabled_mods(),
            &[],
        )?;
        Ok(())
    })();
    if let Err(e) = result {
        // Undo: move the partial copy to the trash rather than deleting.
        let _ = delete(layout, &inst);
        return Err(e);
    }
    log::info!("duplicated instance {} as {}", src.id, inst.id);
    Ok(inst)
}

/// Move an instance to `instances-trash` (never deleted outright). The
/// Default instance can't be deleted.
pub fn delete(layout: &Layout, inst: &Instance) -> Result<PathBuf> {
    if inst.is_default() {
        return Err(Error::Other("the Default instance can't be deleted".into()));
    }
    let expected = layout.instances_dir().join(&inst.id);
    if inst.game_dir != expected || !valid_id(&inst.id) {
        return Err(Error::Other(
            "refusing to delete a folder outside instances".into(),
        ));
    }
    let trash = layout.instance_trash();
    fs::create_dir_all(&trash).at(&trash)?;
    let dest = trash.join(format!("{}-{}", inst.id, crate::auth::now_secs()));
    fs::rename(&inst.game_dir, &dest).at(&inst.game_dir)?;
    log::info!("moved instance {} to {}", inst.id, dest.display());
    Ok(dest)
}

/// Recursive copy of regular files and folders; symlinks are skipped (they
/// could point outside the instance). Missing `src` is not an error.
fn copy_tree(src: &Path, dst: &Path, skip: &[&str]) -> Result<()> {
    let rd = match fs::read_dir(src) {
        Ok(rd) => rd,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(Error::io(src, e)),
    };
    fs::create_dir_all(dst).at(dst)?;
    for e in rd {
        let e = e.at(src)?;
        let name = e.file_name();
        if name.to_str().is_some_and(|n| skip.contains(&n)) {
            continue;
        }
        let ft = e.file_type().at(e.path())?;
        let to = dst.join(&name);
        if ft.is_dir() {
            copy_tree(&e.path(), &to, &[])?;
        } else if ft.is_file() {
            fs::copy(e.path(), &to).at(e.path())?;
        }
    }
    Ok(())
}

/// Held while an instance's game runs; a second launch of the same instance
/// (from this or another launcher window) fails while it exists. On Windows
/// the file is opened without sharing, so the OS releases it if the launcher
/// dies.
pub struct RunLock {
    _file: fs::File,
    #[cfg(not(windows))]
    path: PathBuf,
}

impl RunLock {
    pub fn acquire(layout: &Layout) -> Result<RunLock> {
        let dir = layout.aux_dir().to_path_buf();
        fs::create_dir_all(&dir).at(&dir)?;
        let path = dir.join(if layout.aux_dir() == layout.root() {
            "instance-running.lock"
        } else {
            "running.lock"
        });
        let busy = || Error::Launch("this instance is already running".into());
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            let file = fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .share_mode(0)
                .open(&path)
                .map_err(|e| {
                    if e.raw_os_error() == Some(32) {
                        busy()
                    } else {
                        Error::io(&path, e)
                    }
                })?;
            Ok(RunLock { _file: file })
        }
        #[cfg(not(windows))]
        {
            let file = fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)
                .map_err(|e| {
                    if e.kind() == std::io::ErrorKind::AlreadyExists {
                        busy()
                    } else {
                        Error::io(&path, e)
                    }
                })?;
            Ok(RunLock { _file: file, path })
        }
    }
}

#[cfg(not(windows))]
impl Drop for RunLock {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn setup() -> (tempfile::TempDir, Layout, LauncherConfig) {
        let d = tempfile::tempdir().unwrap();
        let layout = Layout::new(d.path().join("Kestrel"));
        layout.ensure().unwrap();
        let cfg = LauncherConfig {
            max_ram_mib: 3072,
            ..LauncherConfig::default()
        };
        (d, layout, cfg)
    }

    #[test]
    fn default_instance_is_the_original_folder_with_old_settings() {
        let (_d, layout, cfg) = setup();
        let all = load_all(&layout, &cfg);
        assert_eq!(all.len(), 1);
        let d = &all[0];
        assert!(d.is_default());
        assert_eq!(d.game_dir, layout.root().join("instance"));
        // Launcher files stay where earlier versions kept them.
        let l = d.layout(&layout);
        assert_eq!(l, layout);
        assert_eq!(l.disabled_mods(), layout.root().join("mods-disabled"));
        assert_eq!(l.game_logs(), layout.root().join("logs"));
        assert_eq!(
            d.meta.game.max_ram_mib,
            sysmem::clamp_max_mib(3072, sysmem::total_mib())
        );
        assert!(d.supported());
        // Nothing is written until saved.
        assert!(!d.meta_path().exists());
        let mut d = d.clone();
        d.meta.name = "Main".into();
        d.save().unwrap();
        assert_eq!(load_all(&layout, &cfg)[0].meta.name, "Main");
    }

    #[test]
    fn instances_are_isolated_and_listed_after_reload() {
        let (_d, layout, cfg) = setup();
        let def = &load_all(&layout, &cfg)[0];
        fs::write(def.game_dir.join("options.txt"), "fov:0.5\n").unwrap();
        fs::create_dir_all(def.game_dir.join("mods")).unwrap();
        fs::write(def.game_dir.join("mods/a.jar"), "a").unwrap();
        fs::write(layout.disabled_mods().join("off.jar"), "x").ok();
        fs::create_dir_all(layout.disabled_mods()).unwrap();
        fs::write(layout.disabled_mods().join("off.jar"), "x").unwrap();
        fs::create_dir_all(def.game_dir.join("resourcepacks")).unwrap();
        fs::write(def.game_dir.join("resourcepacks/p.zip"), "p").unwrap();

        let a = create(
            &layout,
            "Hypixel PvP",
            InstanceMeta {
                account: AccountChoice::Account { uuid: "ab".into() },
                ..InstanceMeta::default()
            },
            &CopyFrom {
                source: Some(def.clone()),
                settings: true,
                mods: true,
                resource_packs: false,
            },
        )
        .unwrap();
        assert_eq!(a.id, "Hypixel PvP");
        assert_eq!(a.game_dir, layout.root().join("instances/Hypixel PvP"));
        assert_eq!(
            fs::read_to_string(a.game_dir.join("options.txt")).unwrap(),
            "fov:0.5\n"
        );
        assert!(a.game_dir.join("mods/a.jar").is_file());
        assert!(a.layout(&layout).disabled_mods().join("off.jar").is_file());
        assert!(!a.game_dir.join("resourcepacks").exists());

        // Changing one instance leaves the other alone.
        fs::write(a.game_dir.join("options.txt"), "fov:1.0\n").unwrap();
        assert_eq!(
            fs::read_to_string(def.game_dir.join("options.txt")).unwrap(),
            "fov:0.5\n"
        );
        let la = a.layout(&layout);
        assert_ne!(la.mods(), layout.mods());
        assert_ne!(la.client_data(), layout.client_data());
        assert_ne!(la.options_txt(), layout.options_txt());
        assert_ne!(la.disabled_mods(), layout.disabled_mods());
        assert_ne!(la.mod_trash(), layout.mod_trash());
        assert_ne!(la.game_logs(), layout.game_logs());
        assert_eq!(la.libraries(), layout.libraries()); // shared, read-only

        let b = create(
            &layout,
            "Hypixel PvP",
            InstanceMeta::default(),
            &CopyFrom::default(),
        )
        .unwrap();
        assert_eq!(b.id, "Hypixel PvP (2)");

        let all = load_all(&layout, &cfg);
        let names: Vec<_> = all.iter().map(|i| i.meta.name.as_str()).collect();
        assert_eq!(names, ["Default", "Hypixel PvP", "Hypixel PvP"]);
        assert_eq!(
            all[1].meta.account,
            AccountChoice::Account { uuid: "ab".into() }
        );
        // No leftovers from staging.
        assert!(
            fs::read_dir(layout.instances_dir())
                .unwrap()
                .flatten()
                .all(|e| !e.file_name().to_string_lossy().starts_with('.'))
        );
    }

    #[test]
    fn duplicate_copies_everything_but_history_and_delete_goes_to_trash() {
        let (_d, layout, cfg) = setup();
        let a = create(
            &layout,
            "Testing",
            InstanceMeta::default(),
            &CopyFrom::default(),
        )
        .unwrap();
        fs::create_dir_all(a.game_dir.join("saves/World/region")).unwrap();
        fs::write(a.game_dir.join("saves/World/level.dat"), "lvl").unwrap();
        fs::create_dir_all(a.game_dir.join("logs")).unwrap();
        fs::write(a.game_dir.join("logs/latest.log"), "log").unwrap();
        fs::create_dir_all(a.layout(&layout).game_logs()).unwrap();
        fs::write(a.layout(&layout).game_logs().join("game-1.log"), "g").unwrap();
        let mut a2 = a.clone();
        a2.meta.game.max_ram_mib = 2048;
        a2.meta.last_launch = Some(5);
        a2.save().unwrap();

        let c = duplicate(&layout, &a2, "Testing copy").unwrap();
        assert_eq!(
            fs::read_to_string(c.game_dir.join("saves/World/level.dat")).unwrap(),
            "lvl"
        );
        assert!(!c.game_dir.join("logs").exists());
        assert!(!c.layout(&layout).game_logs().exists());
        assert_eq!(c.meta.name, "Testing copy");
        assert_eq!(c.meta.last_launch, None);
        assert_eq!(c.meta.game.max_ram_mib, a2.meta.game.max_ram_mib);

        let trashed = delete(&layout, &c).unwrap();
        assert!(trashed.join("saves/World/level.dat").is_file());
        assert!(!c.game_dir.exists());
        assert!(delete(&layout, &load_all(&layout, &cfg)[0]).is_err());
        assert_eq!(load_all(&layout, &cfg).len(), 2);
    }

    #[test]
    fn names_and_ids_are_safe() {
        let (_d, layout, _cfg) = setup();
        assert!(clean_name("   ").is_err());
        assert!(clean_name(&"x".repeat(41)).is_err());
        assert_eq!(new_id(&layout, "../../evil"), "______evil");
        assert_eq!(new_id(&layout, "CON"), "Instance");
        assert_eq!(new_id(&layout, "default"), "Instance");
        assert_eq!(new_id(&layout, "Ünïcode"), "_n_code");
        assert!(!valid_id("default"));
        assert!(!valid_id(".creating-1"));
        assert!(!valid_id("a/b"));
        assert!(!valid_id("COM1"));
        assert!(valid_id("Hypixel PvP (2)"));
    }

    #[test]
    fn unreadable_settings_keep_the_instance_listed() {
        let (_d, layout, cfg) = setup();
        let a = create(
            &layout,
            "Broken",
            InstanceMeta::default(),
            &CopyFrom::default(),
        )
        .unwrap();
        fs::write(a.meta_path(), "{oops").unwrap();
        let all = load_all(&layout, &cfg);
        assert_eq!(all.len(), 2);
        assert_eq!(all[1].meta.name, "Broken");
        // A folder without settings is not an instance.
        fs::create_dir_all(layout.instances_dir().join("random")).unwrap();
        assert_eq!(load_all(&layout, &cfg).len(), 2);
    }

    #[test]
    fn run_lock_is_exclusive() {
        let (_d, layout, cfg) = setup();
        let a = create(&layout, "A", InstanceMeta::default(), &CopyFrom::default()).unwrap();
        let la = a.layout(&layout);
        let l1 = RunLock::acquire(&la).unwrap();
        assert!(RunLock::acquire(&la).is_err());
        // Other instances are independent.
        let d = load_all(&layout, &cfg)[0].layout(&layout);
        let _l2 = RunLock::acquire(&d).unwrap();
        drop(l1);
        let _l3 = RunLock::acquire(&la).unwrap();
    }
}
