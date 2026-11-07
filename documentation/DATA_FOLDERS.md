# Data folders

Night Launcher keeps all of its data in one per-user folder and never writes next to
`NightLauncher.exe`, so the exe can live anywhere (Desktop, Downloads, a folder with spaces,
another drive) and be started from a shortcut. Your normal `.minecraft` is never touched.

## Where

| | Folder |
|---|---|
| Default | `%LOCALAPPDATA%\Night Launcher` (e.g. `C:\Users\<you>\AppData\Local\Night Launcher`) |
| Night Launcher 1.1.0 and earlier, Kestrel 0.1 – 0.11 | `%LOCALAPPDATA%\Kestrel` (moved automatically, see below) |
| Chosen for one start | `NightLauncher.exe --root "<folder>"` |
| Chosen permanently | environment variable `NIGHT_LAUNCHER_HOME` (the older `KESTREL_HOME` still works) |

The folder is found with Windows' known-folder API (`FOLDERID_LocalAppData`), not from the
working directory or by building strings, so it is right whichever user is signed in and
however the launcher was started. If Windows can't provide it, the launcher says so instead
of guessing. Every folder below is created when needed; deleting one (other than your data)
is harmless, it comes back on the next start or launch.

All code gets these paths from one place: `kestrel_core::datadir` picks the folder and
`kestrel_core::paths::Layout` names everything inside it (per instance via
`instances.rs`). New features should add a `Layout` method instead of joining paths.

## Layout

```
%LOCALAPPDATA%\Night Launcher\
  launcher.json            launcher settings (and the pre-1.1 game settings that seed Default)
  accounts.dat             saved Microsoft accounts, encrypted with Windows DPAPI
  install-state.json       what is installed (shared game files), with their checksums
  migration.json           record of the move from the Kestrel folder (only after a move)
  renderer-fallback.txt    launcher renderer that worked when the first one didn't
  versions\                Minecraft and Forge version files, natives      (shared, read-only)
  libraries\               Java libraries                                   (shared, read-only)
  assets\                  sounds, languages, log config                    (shared, read-only)
  runtime\                 managed Java 8                                   (shared, read-only)
  cache\                   downloaded thumbnails and similar; safe to delete
  updates\                 launcher updates being downloaded/verified; cleaned up after updating
  app\NightLauncher.exe    only when the exe's own folder isn't writable (updates install here)
  logs\                    launcher.log, launcher.prev.log, Default instance game logs
  logs\from-kestrel\       logs copied from the old folder
  instance\                Default instance: the game folder (--gameDir)
    mods\ resourcepacks\ saves\ screenshots\ config\ logs\ options.txt
    kestrel\               Night client data: config.json, cosmetics, capes\, skies\, skins\
    night-instance.json    Default's instance settings
  mods-disabled\           Default's disabled mods (outside the game folder so Forge ignores them)
  mods-trash\              Default's removed mods
  instances\<name>\        every other instance: the same game-folder contents as instance\
    night-instance.json
    night-launcher\        its disabled mods, removed mods and captured game logs
  instances-trash\         deleted instances (moved here, never erased)
```

Shared folders are only read by the game; everything a game writes is inside its own
instance folder. Account sign-ins stay in `accounts.dat`, which only your Windows account
can decrypt. The client's own folder is still named `kestrel` inside each instance (renaming
it would orphan existing settings).

## Moving from the Kestrel folder

Every earlier version used `%LOCALAPPDATA%\Kestrel` with this same layout (later versions only
added folders: `mods-disabled`/`mods-trash` in 0.2.1, `instances` in 1.1.0), so one move covers
all of them; older settings formats inside are upgraded as before (`launcher.json`
`config_version`, the single-account `accounts.dat`, older install records are re-checked).

On start-up (`datadir::plan`):

| Found | Result |
|---|---|
| Neither folder | New install: `Night Launcher` is created. |
| `Kestrel` with data, no `Night Launcher` data | Move (below), with a progress screen. |
| `Night Launcher` with data that wasn't moved there | `Night Launcher` is used; `Kestrel` is never copied over it. |
| `migration.json` "complete" | `Night Launcher` is used; Settings → Advanced offers to remove `Kestrel`. |
| `migration.json` "copying" (interrupted) | The move resumes. |

"Data" means a settings, accounts or install file, a world, a mod, or an instance; the empty
folders a first start creates don't count, so they can never hide data waiting to move.

The move (`datadir::migrate`):

1. Refuses while a game started from `Kestrel` is running, and checks there is enough free disk space.
2. Writes `migration.json` with `"status": "copying"`.
3. Copies every file (not `updates\`, lock files, or links/junctions, which are listed as
   skipped). Old launcher logs go to `logs\from-kestrel\`. Each file is copied under a temporary
   name and renamed into place; files a previous attempt already copied identically are kept.
4. Compares every copied file with its original (SHA-256). Any difference stops the move.
5. Points absolute paths stored in the copied settings (`install-state.json`: Java, classpath,
   natives, log config; any other JSON settings) at the new folder.
6. Marks `migration.json` `"complete"` and only then uses the new folder. A note,
   `MOVED-TO-NIGHT-LAUNCHER.txt`, is added to `Kestrel`; nothing else there changes.

If anything fails, nothing is switched: the launcher shows why, offers **Try again** or **Use the
old folder for now**, and resumes the move on the next start. Running it again is always safe.

The old folder is removed only when you choose **Remove old folder** in Settings → Advanced, and
then it goes to the Recycle Bin (refused if the move didn't finish, if a game started from it is
running, or if `NightLauncher.exe` itself lives inside it).

**Manual recovery:** the old folder is a complete copy. To start over, close the launcher, delete
or rename `%LOCALAPPDATA%\Night Launcher`, and start it again; the move runs again from
`Kestrel`. To keep using the old folder, start with `--root "%LOCALAPPDATA%\Kestrel"`.

## Testing without your real data

`NIGHT_LAUNCHER_LOCALAPPDATA=<folder>` makes the launcher treat `<folder>` as the Windows
per-user data folder: a brand-new user (empty folder), a Kestrel user (put a `Kestrel` folder
in it), or any earlier layout. The game and update restarts inherit it.
