
## Launcher

A dashboard with one Play button, your player and status at a glance, plus Cosmetics, QoL,
Settings, Account, About and Logs, in the same look as the in-game menu. See
[documentation/LAUNCHER.md](documentation/LAUNCHER.md).

## Mods tab

Manage external Forge 1.8.9 mods (install, enable/disable, remove with recovery) and
resource packs (enable, disable, reorder, preview; saved to `options.txt` like
Minecraft's own menu), all from the launcher. See
[documentation/QOL.md](documentation/QOL.md).

## In-game QoL (PvP and visuals)

Hit Color, Hit Delay Fix, Low Health Alert, Opponent Health Prediction, Block Counter,
Bridge Assist, Clear Water, No Blindness and Weather Customizer, under Right Shift →
QoL. All of them are client-side and visual or informational only. See
[documentation/PVP_QOL.md](documentation/PVP_QOL.md).

## Automatic updates

`NightLauncher.exe` checks [GitHub Releases](https://github.com/nightlauncherr/Night-Launcher/releases)
at start-up and offers new versions in a window (**Update Now** / **Later**). It verifies
each one (Ed25519-signed manifest, SHA-256, self-test), swaps it in, and restarts. Your
data in `%LOCALAPPDATA%\Kestrel` is kept.

Download the latest `NightLauncher.exe` from the Releases page once; after that it updates itself.

Publishing a version: `scripts\bump-version.ps1 -Version x.y.z`, write `notes\x.y.z.md`,
commit and push, then `scripts\publish-release.ps1 -Notes notes\x.y.z.md` (needs the
private signing key). Details, security notes and key backup are in
[documentation/UPDATES.md](documentation/UPDATES.md).

See [documentation/BUILD.md](documentation/BUILD.md) for details, including Microsoft sign-in setup.

Game data lives in `%LOCALAPPDATA%\Kestrel` (the folder keeps the project's earlier name so
existing settings, accounts and cosmetics carry over) and never touches your normal `.minecraft`.
Not affiliated with Mojang or Microsoft.
