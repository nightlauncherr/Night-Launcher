//! Resources compiled into the executable.

include!(concat!(env!("OUT_DIR"), "/embedded_mod.rs"));

/// Window/taskbar icon (the .exe file icon comes from the Windows resource).
pub const ICON_PNG: &[u8] = include_bytes!("../assets/night-icon.png");

/// Update source, compiled in by scripts/release.ps1 from release.json and
/// launcher/update-public-key.txt. Both empty = updates not configured.
pub const UPDATE_MANIFEST_URL: Option<&str> = option_env!("KESTREL_UPDATE_MANIFEST_URL");
pub const UPDATE_PUBLIC_KEY: Option<&str> = option_env!("KESTREL_UPDATE_PUBLIC_KEY");

/// The Night Launcher mark (same artwork as the in-game main menu), 128x128 PNG.
pub const MARK_PNG: &[u8] = include_bytes!("../assets/night-mark.png");

/// Inter (SIL Open Font License 1.1, see assets/fonts/OFL.txt).
pub const FONT_REGULAR: &[u8] = include_bytes!("../assets/fonts/Inter-Regular.ttf");
pub const FONT_SEMIBOLD: &[u8] = include_bytes!("../assets/fonts/Inter-SemiBold.ttf");
pub const FONT_BOLD: &[u8] = include_bytes!("../assets/fonts/Inter-Bold.ttf");
