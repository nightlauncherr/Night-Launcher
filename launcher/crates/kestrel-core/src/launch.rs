//! Build the game command line and run the game process.

use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::Sender;

use crate::auth::Session;
use crate::error::{Error, IoContext, Result};
use crate::install::InstallState;
use crate::instances::GameSettings;
use crate::mojang::Platform;
use crate::paths::Layout;
use crate::redact::redact;
use crate::{LAUNCHER_BRAND, LAUNCHER_VERSION};

/// Marker the client mod prints once it has fully initialised.
pub const MOD_READY_MARKER: &str = "[Kestrel] Client initialized";
pub const FORGE_MARKER: &str = "Forge Mod Loader version";
/// The mod prints `[Kestrel] activity <token>: <menu|singleplayer|multiplayer>`
/// whenever the player's situation changes (`ActivityReporter.java`). The
/// per-launch token keeps chat messages from spoofing the state.
pub const ACTIVITY_MARKER: &str = "[Kestrel] activity ";
/// The mod prints `[Kestrel] request <token> <n> <verb> [arg]` to ask the
/// launcher for something (the in-game account switcher, `AccountBridge.java`).
/// Replies go to the game's stdin as one JSON object per line, tagged `"re": n`.
pub const REQUEST_MARKER: &str = "[Kestrel] request ";

/// What the game can ask the launcher.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GameRequest {
    /// The saved accounts (names, UUIDs; never credentials).
    Accounts,
    /// A fresh session for this saved account (Minecraft UUID, 32 hex).
    Switch(String),
    /// Sign in another Microsoft account (device code shown in game).
    Add,
    /// Stop a pending request (an unfinished Add).
    Cancel(u32),
}

/// Parse a request line carrying this launch's token. Anything malformed,
/// or without the exact token (chat quoting the marker), is ignored.
pub fn parse_request(line: &str, token: &str) -> Option<(u32, GameRequest)> {
    if token.is_empty() {
        return None;
    }
    let needle = format!("{REQUEST_MARKER}{token} ");
    let rest = &line[line.find(&needle)? + needle.len()..];
    // The game's console output is log4j XML: the message sits in a CDATA
    // section, so the request ends where `]]>` (or any markup) begins.
    let end = rest
        .find("]]>")
        .or_else(|| rest.find('<'))
        .unwrap_or(rest.len());
    let mut parts = rest[..end].split_whitespace();
    let n = parts.next()?;
    if n.is_empty() || n.len() > 9 || !n.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let n: u32 = n.parse().ok()?;
    let req = match (parts.next()?, parts.next()) {
        ("accounts", None) => GameRequest::Accounts,
        ("add", None) => GameRequest::Add,
        ("switch", Some(u)) if u.len() == 32 && u.bytes().all(|b| b.is_ascii_hexdigit()) => {
            GameRequest::Switch(u.to_ascii_lowercase())
        }
        ("cancel", Some(c)) if c.len() <= 9 && c.bytes().all(|b| b.is_ascii_digit()) => {
            GameRequest::Cancel(c.parse().ok()?)
        }
        _ => return None,
    };
    if parts.next().is_some() {
        return None;
    }
    Some((n, req))
}

#[derive(Debug, Clone)]
pub struct LaunchSpec {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    /// Random per launch; passed to the mod as `-Dkestrel.activityToken`.
    pub activity_token: String,
}

/// What the player is doing in game, as reported by the mod.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GameActivity {
    MainMenu,
    Singleplayer,
    Multiplayer,
}

impl GameActivity {
    pub fn id(self) -> &'static str {
        match self {
            GameActivity::MainMenu => "menu",
            GameActivity::Singleplayer => "singleplayer",
            GameActivity::Multiplayer => "multiplayer",
        }
    }

    fn from_id(s: &str) -> Option<GameActivity> {
        match s {
            "menu" => Some(GameActivity::MainMenu),
            "singleplayer" => Some(GameActivity::Singleplayer),
            "multiplayer" => Some(GameActivity::Multiplayer),
            _ => None,
        }
    }
}

/// Parse an activity marker carrying this launch's token. Lines without the
/// exact token (including chat that merely quotes the marker) are ignored.
pub fn parse_activity(line: &str, token: &str) -> Option<GameActivity> {
    if token.is_empty() {
        return None;
    }
    let needle = format!("{ACTIVITY_MARKER}{token}: ");
    let rest = &line[line.find(&needle)? + needle.len()..];
    let word: String = rest
        .chars()
        .take_while(|c| c.is_ascii_lowercase())
        .collect();
    GameActivity::from_id(&word)
}

/// 128 random bits as hex, from the standard library's OS-seeded hasher keys.
fn new_activity_token() -> String {
    use std::hash::{BuildHasher, Hasher};
    let mut out = String::with_capacity(32);
    for i in 0..2u64 {
        let mut h = std::collections::hash_map::RandomState::new().build_hasher();
        h.write_u64(i);
        h.write_u128(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0),
        );
        out.push_str(&format!("{:016x}", h.finish()));
    }
    out
}

/// Tuned defaults for 1.8.9 on Java 8: G1 with short pauses. Kept modest;
/// users can disable them in settings.
pub const DEFAULT_JVM_ARGS: &[&str] = &[
    "-XX:+UseG1GC",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:G1NewSizePercent=20",
    "-XX:G1ReservePercent=20",
    "-XX:MaxGCPauseMillis=50",
    "-XX:G1HeapRegionSize=16M",
];

/// Pure function: compute the full command. No I/O besides path joins, so
/// it is unit-testable.
pub fn build(
    state: &InstallState,
    session: &Session,
    cfg: &GameSettings,
    java: &Path,
    layout: &Layout,
    platform: &Platform,
) -> Result<LaunchSpec> {
    if state.main_class.is_empty() {
        return Err(Error::Launch("install state has no main class".into()));
    }
    let mut args: Vec<String> = Vec::new();

    let xmx = cfg.max_ram_mib;
    let xms = (xmx / 2).clamp(256, 1024);
    args.push(format!("-Xms{xms}M"));
    args.push(format!("-Xmx{xmx}M"));
    if cfg.use_default_jvm_args {
        args.extend(DEFAULT_JVM_ARGS.iter().map(|s| s.to_string()));
    }
    args.extend(cfg.extra_jvm_args());
    args.push(format!(
        "-Djava.library.path={}",
        state.natives_dir.display()
    ));
    args.push(format!("-Dminecraft.launcher.brand={LAUNCHER_BRAND}"));
    args.push(format!("-Dminecraft.launcher.version={LAUNCHER_VERSION}"));
    // Defence in depth against Log4Shell on top of Mojang's patched config.
    args.push("-Dlog4j2.formatMsgNoLookups=true".into());
    if let Some(l) = &state.logging_arg {
        args.push(l.clone());
    }
    // Forge 1.8.9 on modern Mojang-distributed jars.
    args.push("-Dfml.ignoreInvalidMinecraftCertificates=true".into());
    args.push("-Dfml.ignorePatchDiscrepancies=true".into());
    args.push(format!(
        "-Dkestrel.requestedPreset={}",
        cfg.game_preset.id()
    ));
    args.push(format!("-Dkestrel.launcherVersion={LAUNCHER_VERSION}"));
    let activity_token = new_activity_token();
    args.push(format!("-Dkestrel.activityToken={activity_token}"));

    let cp = state
        .classpath
        .iter()
        .map(|p| p.to_string_lossy().into_owned())
        .collect::<Vec<_>>()
        .join(platform.classpath_separator());
    args.push("-cp".into());
    args.push(cp);
    args.push(state.main_class.clone());

    let game_dir = layout.instance();
    let assets = layout.assets();
    for tok in state.minecraft_arguments.split_whitespace() {
        let v = match tok {
            "${auth_player_name}" => session.name.clone(),
            "${version_name}" => state.version_id.clone(),
            "${game_directory}" => game_dir.to_string_lossy().into_owned(),
            "${assets_root}" | "${game_assets}" => assets.to_string_lossy().into_owned(),
            "${assets_index_name}" => state.asset_index.clone(),
            "${auth_uuid}" => session.uuid.clone(),
            "${auth_access_token}" | "${auth_session}" => session.access_token.clone(),
            "${user_properties}" => "{}".into(),
            "${user_type}" => session.user_type.into(),
            "${version_type}" => "release".into(),
            other if other.starts_with("${") => {
                return Err(Error::Launch(format!("unknown launch placeholder {other}")));
            }
            other => other.to_string(),
        };
        args.push(v);
    }
    if cfg.fullscreen {
        args.push("--fullscreen".into());
    } else {
        args.push("--width".into());
        args.push(cfg.window_width.to_string());
        args.push("--height".into());
        args.push(cfg.window_height.to_string());
    }

    Ok(LaunchSpec {
        program: java.to_path_buf(),
        args,
        cwd: game_dir,
        activity_token,
    })
}

/// Events emitted while the game runs.
#[derive(Debug, Clone)]
pub enum GameEvent {
    Line(String),
    ForgeLoaded,
    ModReady,
    Activity(GameActivity),
    Request(u32, GameRequest),
    Exited(Option<i32>),
}

pub struct RunningGame {
    pub child: Child,
    pub log_path: PathBuf,
    /// The game's stdin: the launcher's reply channel (see [`REQUEST_MARKER`]).
    pub stdin: Option<ChildStdin>,
}

/// Spawn the game. Output is redacted, written to `logs/latest.log` (plus a
/// timestamped copy) and forwarded to `events`.
pub fn spawn(spec: &LaunchSpec, layout: &Layout, events: Sender<GameEvent>) -> Result<RunningGame> {
    if !spec.program.is_file() {
        return Err(Error::Launch(format!(
            "Java executable not found: {}",
            spec.program.display()
        )));
    }
    std::fs::create_dir_all(&spec.cwd).at(&spec.cwd)?;
    let logs = layout.game_logs();
    std::fs::create_dir_all(&logs).at(&logs)?;
    let log_path = logs.join(format!("game-{}.log", crate::auth::now_secs()));

    let mut cmd = Command::new(&spec.program);
    cmd.args(&spec.args)
        .current_dir(&spec.cwd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    log::info!(
        "launching: {} {}",
        spec.program.display(),
        redact(&spec.args.join(" "))
    );
    let mut child = cmd
        .spawn()
        .map_err(|e| Error::Launch(format!("failed to start {}: {e}", spec.program.display())))?;

    let log_file = std::fs::File::create(&log_path).at(&log_path)?;
    let log_file = std::sync::Arc::new(std::sync::Mutex::new(log_file));
    {
        let mut f = log_file
            .lock()
            .map_err(|_| Error::Other("log lock poisoned".into()))?;
        let _ = writeln!(f, "# Kestrel launcher {LAUNCHER_VERSION}");
        let _ = writeln!(
            f,
            "# command: {} {}",
            spec.program.display(),
            redact(&spec.args.join(" "))
        );
    }

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let stdin = child.stdin.take();
    for (stream, tag) in [
        (
            stdout.map(|s| Box::new(s) as Box<dyn std::io::Read + Send>),
            "out",
        ),
        (
            stderr.map(|s| Box::new(s) as Box<dyn std::io::Read + Send>),
            "err",
        ),
    ] {
        let Some(stream) = stream else { continue };
        let events = events.clone();
        let log_file = log_file.clone();
        let token = spec.activity_token.clone();
        std::thread::Builder::new()
            .name(format!("game-{tag}"))
            .spawn(move || pump(stream, &log_file, &events, &token))
            .map_err(|e| Error::Other(e.to_string()))?;
    }

    let latest = logs.join("latest.log");
    let _ = std::fs::remove_file(&latest);
    Ok(RunningGame {
        child,
        log_path: latest_link(&log_path, &latest),
        stdin,
    })
}

fn latest_link(log_path: &Path, latest: &Path) -> PathBuf {
    // Hard links keep "latest.log" pointing at the live file without copying.
    match std::fs::hard_link(log_path, latest) {
        Ok(()) => latest.to_path_buf(),
        Err(_) => log_path.to_path_buf(),
    }
}

fn pump(
    stream: Box<dyn std::io::Read + Send>,
    log: &std::sync::Mutex<std::fs::File>,
    events: &Sender<GameEvent>,
    activity_token: &str,
) {
    let mut reader = BufReader::new(stream);
    let mut buf = Vec::new();
    loop {
        buf.clear();
        match reader.read_until(b'\n', &mut buf) {
            Ok(0) | Err(_) => break,
            Ok(_) => {
                let line = redact(String::from_utf8_lossy(&buf).trim_end());
                if let Ok(mut f) = log.lock() {
                    let _ = writeln!(f, "{line}");
                }
                if line.contains(FORGE_MARKER) {
                    let _ = events.send(GameEvent::ForgeLoaded);
                }
                if line.contains(MOD_READY_MARKER) {
                    let _ = events.send(GameEvent::ModReady);
                }
                if line.contains(ACTIVITY_MARKER)
                    && let Some(a) = parse_activity(&line, activity_token)
                {
                    let _ = events.send(GameEvent::Activity(a));
                }
                if line.contains(REQUEST_MARKER)
                    && let Some((n, r)) = parse_request(&line, activity_token)
                {
                    let _ = events.send(GameEvent::Request(n, r));
                }
                if events.send(GameEvent::Line(line)).is_err() {
                    // UI gone: keep draining so the game never blocks on a full pipe.
                    continue;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::install::INSTALL_SCHEMA;

    #[test]
    fn requests_need_the_token_and_a_valid_shape() {
        let t = "0123456789abcdef0123456789abcdef";
        let u = "BA14933EA7AD48168696A788CC1B3C1F";
        let line =
            |rest: &str| format!("[12:00:00] [Client thread/INFO]: [Kestrel] request {t} {rest}");
        assert_eq!(
            parse_request(&line("1 accounts"), t),
            Some((1, GameRequest::Accounts))
        );
        assert_eq!(
            parse_request(&line("7 add"), t),
            Some((7, GameRequest::Add))
        );
        assert_eq!(
            parse_request(&line("8 cancel 7"), t),
            Some((8, GameRequest::Cancel(7)))
        );
        assert_eq!(
            parse_request(&line(&format!("2 switch {u}")), t),
            Some((2, GameRequest::Switch(u.to_ascii_lowercase())))
        );
        // Wrong or missing token, bad ids, bad args, trailing junk.
        assert_eq!(
            parse_request(&line("1 accounts").replace(t, &"f".repeat(32)), t),
            None
        );
        assert_eq!(
            parse_request("[CHAT] [Kestrel] request 1 accounts", t),
            None
        );
        assert_eq!(parse_request(&line("x accounts"), t), None);
        assert_eq!(parse_request(&line("1234567890 accounts"), t), None);
        assert_eq!(parse_request(&line("1 switch notauuid"), t), None);
        assert_eq!(parse_request(&line("1 switch"), t), None);
        assert_eq!(parse_request(&line("1 accounts extra"), t), None);
        assert_eq!(parse_request(&line("1 delete-everything"), t), None);
        assert_eq!(parse_request(&line("1 accounts"), ""), None);
        // What Minecraft's XML console layout actually prints.
        let xml = format!(
            "    <log4j:Message><![CDATA[[Kestrel] request {t} 4 switch {u}]]></log4j:Message>"
        );
        assert_eq!(
            parse_request(&xml, t),
            Some((4, GameRequest::Switch(u.to_ascii_lowercase())))
        );
        let xml =
            format!("<log4j:Message><![CDATA[[Kestrel] request {t} 1 accounts]]></log4j:Message>");
        assert_eq!(parse_request(&xml, t), Some((1, GameRequest::Accounts)));
    }

    fn state() -> InstallState {
        InstallState {
            schema: INSTALL_SCHEMA,
            mc_version: "1.8.9".into(),
            forge_version: crate::FORGE_VERSION.into(),
            version_id: "1.8.9-forge1.8.9-11.15.1.2318-1.8.9".into(),
            main_class: "net.minecraft.launchwrapper.Launch".into(),
            minecraft_arguments: "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userProperties ${user_properties} --userType ${user_type} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker".into(),
            asset_index: "1.8".into(),
            classpath: vec![PathBuf::from("C:/My Libs/a.jar"), PathBuf::from("C:/v/1.8.9.jar")],
            natives_dir: PathBuf::from("C:/K/natives"),
            logging_arg: Some("-Dlog4j.configurationFile=C:/K/client-1.7.xml".into()),
            managed_java: None,
            java_version: None,
            mod_jar: None,
            mod_sha1: None,
            files: vec![],
            installed_at: 0,
        }
    }

    #[test]
    fn builds_expected_command() {
        let layout = Layout::new("C:/Users/Some One/AppData/Local/Kestrel");
        let session = Session {
            name: "Kellen".into(),
            uuid: "0123".into(),
            access_token: "tok".into(),
            user_type: "msa",
        };
        let cfg = GameSettings {
            max_ram_mib: 2048,
            ..Default::default()
        };
        let spec = build(
            &state(),
            &session,
            &cfg,
            Path::new("java"),
            &layout,
            &Platform::windows_x64(),
        )
        .unwrap();
        let a = &spec.args;
        let pos = |s: &str| a.iter().position(|x| x == s).unwrap();
        assert_eq!(a[0], "-Xms1024M");
        assert_eq!(a[1], "-Xmx2048M");
        assert_eq!(a[pos("-cp") + 1], "C:/My Libs/a.jar;C:/v/1.8.9.jar");
        assert_eq!(a[pos("-cp") + 2], "net.minecraft.launchwrapper.Launch");
        assert_eq!(a[pos("--username") + 1], "Kellen");
        assert_eq!(a[pos("--accessToken") + 1], "tok");
        assert_eq!(a[pos("--assetIndex") + 1], "1.8");
        assert_eq!(a[pos("--userProperties") + 1], "{}");
        assert_eq!(
            a[pos("--tweakClass") + 1],
            "net.minecraftforge.fml.common.launcher.FMLTweaker"
        );
        // Paths with spaces stay a single argument.
        assert!(a[pos("--gameDir") + 1].contains("Some One"));
        let tok = format!("-Dkestrel.activityToken={}", spec.activity_token);
        assert!(pos(&tok) < pos("-cp"), "token is a JVM property");
        assert!(
            a.iter()
                .any(|x| x == "-Dkestrel.requestedPreset=competitive")
        );
        assert_eq!(a[a.len() - 4], "--width");
    }

    #[test]
    fn rejects_unknown_placeholder() {
        let mut s = state();
        s.minecraft_arguments.push_str(" ${surprise}");
        let r = build(
            &s,
            &Session::offline("abc"),
            &GameSettings::default(),
            Path::new("java"),
            &Layout::new("/k"),
            &Platform::windows_x64(),
        );
        assert!(r.is_err());
    }

    #[test]
    fn activity_markers_need_this_launchs_token() {
        let t = "0123456789abcdef0123456789abcdef";
        let line = format!("[12:00:00] [Client thread/INFO]: [Kestrel] activity {t}: multiplayer");
        assert_eq!(parse_activity(&line, t), Some(GameActivity::Multiplayer));
        // Mojang's XML console layout wraps the message in CDATA.
        let xml =
            format!("    <log4j:Message><![CDATA[[Kestrel] activity {t}: menu]]></log4j:Message>");
        assert_eq!(parse_activity(&xml, t), Some(GameActivity::MainMenu));
        assert_eq!(
            parse_activity(&format!("[Kestrel] activity {t}: singleplayer"), t),
            Some(GameActivity::Singleplayer)
        );
        // Chat can quote the marker but cannot know the token.
        assert_eq!(
            parse_activity("[CHAT] <x> [Kestrel] activity : multiplayer", t),
            None
        );
        assert_eq!(
            parse_activity("[CHAT] [Kestrel] activity deadbeef: multiplayer", t),
            None
        );
        assert_eq!(
            parse_activity(&format!("[Kestrel] activity {t}: lobby"), t),
            None
        );
        assert_eq!(
            parse_activity(&format!("[Kestrel] activity {t}: menu"), ""),
            None
        );
    }

    #[test]
    fn each_launch_gets_a_fresh_token() {
        let a = new_activity_token();
        let b = new_activity_token();
        assert_eq!(a.len(), 32);
        assert!(a.bytes().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(a, b);
    }
}
