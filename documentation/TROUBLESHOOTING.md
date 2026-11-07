# Troubleshooting

Logs:
- Launcher: `%LOCALAPPDATA%\Night Launcher\logs\launcher.log` (previous run: `launcher.prev.log`;
  crashes: `launcher-crash-*.txt`).
- Game: `%LOCALAPPDATA%\Night Launcher\logs\latest.log` and `game-*.log` (tokens are redacted).
- Forge: `%LOCALAPPDATA%\Night Launcher\instance\logs\fml-client-latest.log`.
- Night Launcher → Logs (bottom of the sidebar) shows both live and has "Copy all".

| Symptom | Cause / fix |
|---|---|
| `setup.ps1` cannot run | `powershell -ExecutionPolicy Bypass -File scripts\setup.ps1` |
| Rust build: `link.exe not found` | Visual Studio C++ Build Tools missing → `setup.ps1` |
| Gradle: "Unsupported class file major version" | Gradle running on a JDK newer than 21; install JDK 17 (`setup.ps1`) or set `JAVA_HOME` |
| Gradle: "Could not find tools.jar … jre1.8…" | Gradle picked an installed Java 8 JRE. Fixed in `client/gradle.properties` (`org.gradle.java.installations.auto-detect=false`) so Gradle downloads its own JDK 8 |
| Gradle cannot resolve `gg.essential.loom` / Forge | Network/proxy blocking `repo.essential.gg`, `maven.minecraftforge.net`, `maven.architectury.dev`, `maven.fabricmc.net` |
| Home page: "Client mod: Not bundled" | Run `scripts\build.ps1` (it copies the jar to `dist\NightLauncher\client`), or set `KESTREL_MOD_JAR` |
| Install: "refusing to download from untrusted host" | Metadata pointed outside the allowlist; this is a safety stop. Report it with `launcher.log` |
| Install: hash/size mismatch | Interrupted or corrupted download; press Play again or Repair (only bad files are re-fetched) |
| "custom Java is version 17…" | 1.8.9 + Forge needs Java 8. Use the managed runtime (Settings → Java) |
| Sign-in: "Minecraft services rejected this application ID (HTTP 403)" | The app ID is not approved by Mojang yet (BUILD.md → Microsoft sign-in, step 5) |
| Sign-in: HTTP 403 from Minecraft services | Your Azure app ID is not approved by Mojang yet (<https://aka.ms/mce-reviewappid>) |
| Sign-in: "does not own Minecraft: Java Edition" | The Microsoft account has no Java Edition licence |
| Sign-in: XErr child/adult/region messages | Fix the account at xbox.com / Microsoft Family as the message says |
| Game exits, "Night Launcher loaded" never appears | Check `latest.log` for the first exception. Try Repair. Remove other mods from `instance\mods` |
| Cape not visible | "Show cape" is off, the cape layer is disabled in Options → Skin Customization (the in-game cape menu and the launcher's Capes tab warn about this), or the cape failed to load (the menu shows "Last error"). Before 1.0.2, cape mods such as WaveyCapes hid Night capes completely; Night now draws capes with its own model. An equipped Night cape replaces your official cape |
| Others don't see my cape | They need Night with "Show other Night players' capes" on; you need "Share my cape with Night players" on and a Microsoft account (offline sessions can't share). Uploaded capes are never shared. The game log shows `cape sharing: published …` and `other Night players now see …`; changes reach others within 5 minutes |
| Custom cape/sky rejected | Night Launcher menu shows "Last error"; see size/format rules in the menu or SKIES.md |
| Custom sky does nothing in Nether/End | By design: Overworld only |
| No kill effects on a server | Detection needs you to have hit the victim recently and either a 0-health update or a kill-feed line naming both of you. Some servers do neither; try toggling detection modes |
| Low FPS | Try the Potato preset (QoL → Performance presets), disable custom sky/kill effects, enable "Also apply video settings" |
| Cosmetics page: "Upload rejected: …" | The message says why (not a PNG, wrong size/aspect, too large). Cape: 64x32 or multiples, ≤ 2 MB. Sky: 3:2, 384–3072 px wide, ≤ 16 MB |
| Cosmetics page stays "Pending" | Normal until the game starts; the client applies it on launch (or within ~1 s while running). Check `latest.log` for "applied cosmetics from launcher" |
| Card shows "invalid image" | A file in the capes/skies folder is not a valid image; remove it with the card's Remove button |
| Launcher window never opens (VMs, old drivers) | Night Launcher tries OpenGL, then Direct3D 12 (incl. Windows' software renderer), and shows an error box if both fail. In a VM enable 3D acceleration and install guest tools — Minecraft itself still needs OpenGL 2.0+. Choose the renderer in Settings → Advanced → Renderer, or for one launch with `NightLauncher.exe --renderer opengl` / `--renderer d3d`. A renderer that only worked as the fallback is remembered in `%LOCALAPPDATA%\Night Launcher\renderer-fallback.txt` |
| Launcher window flashes washed-out frames, or the desktop stutters while moving/hovering the launcher | Direct3D 12 hit by the Multi-Plane Overlay issue on some NVIDIA/Windows 10 systems. Settings → Advanced → Renderer → OpenGL (the default since 0.4.1) |
| Settings → Updates: "Not configured in this build" | The exe was built before `scripts\setup-updates.ps1` was run. Run it, rebuild with `release.ps1` and install that exe once |
| Update check: "no release found" | Nothing published yet, the repo is private, or the release lacks `kestrel-update.json`. See UPDATES.md |
| Update: "signature check failed" | The release was signed with a different key than this launcher trusts (lost/regenerated key). Install the new build manually once |
| Update: "can't update itself in its current folder" | NightLauncher.exe is in a protected folder (Program Files). Move it to Desktop/Documents, or use "Start new version" |
| Update rolled back | The new version crashed on start; see `launcher.log` and `launcher.prev.log` |
