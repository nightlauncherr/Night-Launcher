# Night Launcher

`NightLauncher.exe` is a native Rust application (egui). This page describes its interface; how
it installs, signs in, launches and updates is in [ARCHITECTURE.md](ARCHITECTURE.md) and
[UPDATES.md](UPDATES.md).

## Design

The launcher shares the in-game Night main menu's look: a purple nebula backdrop (a deep
violet gradient, soft purple and magenta clouds, faint stars and a purple glow behind your
player), the crescent-and-star Night mark, and a purple accent (#A16BEF, with lavender
#C6A4FF and magenta #C05AE8 highlights). All colours are tokens in `theme.rs`. Type is Inter (bundled, SIL Open Font License) in regular, semibold and bold.
Icons are vector line icons drawn by the launcher itself, so they stay sharp at every
Windows display scale. Everything follows Windows scaling (100%, 125%, 150%, …).

- `theme.rs` – palette, type scale, spacing and the shared components: cards, buttons
  (primary, secondary, ghost, danger), the Play button, the sliding tab bar, pills,
  banners, progress bars and spinners. Hover and press states animate in about 0.1 s.
- `icons.rs` – the icon set (24-unit grid).
- `backdrop.rs` – the nebula backdrop. The clouds are one small texture generated once and
  cached; per frame the backdrop is a few meshes and one textured quad. It drifts only
  while the launcher window is focused, not minimised, and on Home; anywhere else it is
  drawn still and asks for no repaints, so an idle launcher uses no CPU for it.
- `tools/make_night_logo.py` regenerates the logo, window icon, exe icon and in-game mark.
- Pages fade in (0.18 s); notifications appear bottom right (info and success fade after
  6 s, errors stay until dismissed).
- The sidebar collapses to icons below 1000 px window width; Home stacks its columns on
  narrow windows and sits lower on tall ones.

## Sections

| Section | What it holds |
|---|---|
| **Home** | Instance picker and Play for the default instance (installs on first start, then shows each stage with progress and Cancel/Stop; an instance can't be started twice), Repair / verify (disabled while any game runs), game folder, your player (the active account's skin) and status tiles for installation, version, updates and account. Other running instances are listed under Play. A required update shows a banner here. |
| **Instances** | One card per instance: name, Minecraft version, account, Running (PID) / Starting / Stopped (with the last exit code if it crashed), last played. Play/Stop, Settings (account, memory, window), Set as default, Rename, Duplicate (everything except logs), Folder, Cosmetics, Mods, Delete (moves to `instances-trash`; Default can't be deleted). New instance: name, version (1.8.9 + Forge + Night only), account, memory, Java, and what to copy from an existing instance (settings, mods, resource packs). Several instances can run at once. |
| **Cosmetics** | "Editing instance" picker (cosmetics are saved per instance), then tabs: Kill effects, Skies, Capes, Skin (the local skin changer). The kill effect list scrolls on its own next to (or, on narrow windows, below) the preview, which shrinks on short windows so it always stays in view. |
| **Mods** | "Editing instance" picker, then tabs: Mod Manager, Resource Packs, Performance presets. |
| **Settings** | Tabs: Game (per instance: window size or fullscreen; launcher-wide: close on launch), Java & memory (per instance) (RAM, managed or custom Java with Test, G1 flags, extra JVM arguments), Updates (version, check, automatic prompt), Advanced (Microsoft app ID, launcher renderer, Discord Rich Presence, download threads, data folder). Changes save automatically. |
| **Accounts** | Saved Microsoft accounts with face, name, UUID, Active and "Sign-in expired" badges and the instances assigned to each; Make active, Sign in again, Remove (confirmed; instances using it are named). Add another account (device code shown with Open / Copy / Cancel). Offline mode and offline name. The account switcher at the bottom of the sidebar switches the active account from any page. |
| **About** | Versions, client mod, Java runtime, data directory, notes and licences. |
| **Logs** | Live game output and the launcher log, with Open folder, Copy all and Clear. |

Settings, accounts, game files and instances live in `%LOCALAPPDATA%\Night Launcher` (copied
from `%LOCALAPPDATA%\Kestrel` on first start for users of 1.1.0 and earlier). The original game
folder (`instance\`) is the Default instance; other instances are under `instances\`. See
DATA_FOLDERS.md for the full layout.
