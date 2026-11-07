# In-game QoL features (PvP and visuals)

Nine client-side features in Night's in-game settings (**Right Shift → QoL**),
grouped as Combat, HUD, Movement & Building and Visuals. Every one is **off by
default**, has its own switch and description, shows its settings only while it is on,
and is saved per instance in `<instance folder>\kestrel\config.json` (for the Default instance
`%LOCALAPPDATA%\Night Launcher\instance\kestrel\config.json`; kept across restarts and updates).
Nothing here sends extra packets, changes what the server sees, or moves, aims or acts
for you.

| Feature | Category | What it changes |
|---|---|---|
| Hit Color | Combat | Tint on a player you hit, after the server confirms the hit |
| Hit Delay Fix | Combat | Client-side click and swing-animation responsiveness |
| Low Health Alert | Combat | Banner (and optional sound) when your target drops to a threshold |
| Opponent Health Prediction | Combat | Your target's health, exact or `~` estimated |
| Block Counter | HUD | `BLOCKS: 112`, placeable blocks in your hotbar |
| Bridge Assist | Movement & Building | Placement preview, face highlight, edge guide |
| Clear Water | Visuals | Thinner, lighter underwater fog and overlay |
| No Blindness | Visuals | Removes blindness's dark fog from your screen |
| Weather Customizer | Visuals | Rain, snow, splashes, rain sounds, intensity, clear sky |

## How your hits are confirmed

Hit Color, Low Health Alert and Opponent Health Prediction share one tracker. Your
**target** is the last player you attacked. A hit counts as *confirmed* only when you
attacked a player **and** the server's "this player was hurt" status (entity status 2)
for that player arrives within 0.8 s. Misses send no attack, and hits on a player who is
still invulnerable from the last hit make the server send no hurt status, so neither is
counted. The target is dropped when they die, leave render distance, you change target,
you change world, or 15 s pass without you attacking them.

Known limit: if someone else hurts your target within 0.8 s of your attack (while
your own attack did nothing), that hurt is credited to you. The server does not tell
the client who caused a hurt.

## Combat

### Hit Color
Colour (palette), **Duration** 0.1–2 s, **Intensity** 10–90 %, **Fade out**.
Drawn with vanilla's own entity tint (`RendererLivingEntity.setBrightness`): while the
tint is active it replaces vanilla's red hurt flash for that player only. Works with any
skin and with armour (armour layers use the same tint). Capes are drawn without the
entity tint in vanilla, so they are not coloured. The skin texture, model, hitbox and
the player's real hurt time are not changed.

### Hit Delay Fix
* **Remove 0.5 s lock after missed clicks.** In survival, vanilla 1.8.9 ignores left
  clicks for 10 ticks after a click that hits air (`Minecraft.clickMouse` sets
  `leftClickCounter = 10`). The lock is client-side only. Clicks on an entity never set
  it, so removing it adds **no** attacks. Clicking air sends the normal swing packet,
  which vanilla would have skipped during the lock.
* **Restart swing animation on every click.** Vanilla restarts the arm swing only once
  the current swing is half done, so fast clicks show one long swing. With this on, the
  local player's swing restarts on each click (after the first tick of a swing). It
  only changes the animation. The swing packet is sent on every click either way.

Not changed: reach, attack packets, hit registration, invulnerability ticks and any
server rule. Minecraft 1.8.9 has no attack cooldown, so there is none to bypass. Any
remaining delay between your click and the hit comes from the server (ping and the
server's own checks), and the client can't remove it.

### Low Health Alert
**Threshold** 1–19 HP (2 HP = one heart), **Alert colour**, **Sound**, **Show target
health**. Fires once when your target's health crosses to the threshold or below. It
resets when they heal above it or you change target. Only health the server really sends
is used. A value counts as real once it has changed while you watch (a fixed value,
even one below full, could be fake). If the target took two hurts and the value never moved, the server hides health,
and the line shows "health unavailable on this server" instead of a number. The banner is
a movable HUD element ("Low health alert" in the HUD editor).

### Opponent Health Prediction
**Start HP** (starting assumption) 1–40 HP, **Estimate colour**. A movable HUD element:

* `Opponent HP: 14.0` (text colour): the server's value, proven real.
* `Opponent HP: ~8.5` (estimate colour): estimated while the server's value is not
  proven real. It starts from **Start HP** and subtracts only the
  expected damage of your **confirmed** hits. As soon as the server's value proves real,
  the exact value replaces the estimate.
* `Opponent HP: 20.0?` (grey): the server's value, not yet shown to be real.
* `Opponent HP: unavailable`: there is nothing trustworthy to show.
* `+4.0` after the value is absorption.

The damage estimate uses the 1.8.9 formulas: weapon attack damage, Sharpness/Smite/Bane,
critical hits (falling, not on ground, ladder, water, blindness or a vehicle),
blocking (`(1 + d) / 2`), armour points, and the target's Protection enchantments (as
the average of vanilla's random roll). Absorption is used up first. It can't see
Resistance, Strength or other potion effects on the attacker, regeneration or healing,
fall or fire damage, or server-side damage changes. On servers that hide health the
estimate drifts, and it never claims to be exact.

## HUD

### Block Counter
A HUD element (also listed under HUD → Modules). It counts every frame, so the number
changes as soon as you place, pick up, move or switch blocks. Only full solid blocks
count (wool, clay, wood, stone, glass and other full blocks). Items, partial blocks
(slabs, carpets, torches, flowers) and blocks with a tile entity (chests, furnaces) are
skipped. Options: text colour, background, scale (also by scrolling in the HUD editor),
block icon, held stack shown separately, and **Count only selected block types**
(wool, clay, wood, stone, glass, other). Move it in the HUD editor. It turns red at 0.

## Movement & Building

### Bridge Assist
While you hold a block and aim at a block face:

* the **placement preview** shows where the block would go (red if vanilla would refuse
  the placement, checked with `ItemBlock.canPlaceBlockOnSide`);
* the face you aim at is highlighted;
* on a side face, the **edge alignment guide** draws its top edge, which brightens as
  you approach the edge and is fully bright within 0.35 blocks of it. When you are close
  enough and the placement is valid, the preview brightens too.

Options: colour, opacity, line size, placement preview, edge guide. It is drawn only. It
never moves you, turns the camera, selects a slot, places a block or touches movement
packets.

## Visuals

### Clear Water
**Clarity** 0–95 % (thins underwater fog density), **Brightness** (lifts the underwater
fog colour toward light blue), **Overlay fade** (fades the first-person
underwater texture). Applied only while the camera is in water. Water blocks, the water
surface, collision and swimming are unchanged. Only fog and the overlay are changed,
so custom skies and other visuals are not affected.

### No Blindness
Removes the dark fog that blindness draws (both blindness checks in the fog code).
The effect, its duration and its gameplay effect (no sprinting) stay exactly as the
server sent them. Other potions are not affected. **Some servers, such as Hypixel, do not
allow this. Check the server's rules.**

### Weather Customizer
**Rain**, **Snow**, **Splash particles**, **Rain sounds**, **Intensity** (rain and snow
opacity, splash amount), **Clear-weather look** (your world copy reports no rain or thunder,
so the sky, light and fog look clear). It only changes what you see and hear. The
server's weather and world time are not changed (in singleplayer too), and other players
are not affected.

Limits: rain and snow are drawn per column, so **Rain** off also hides snow in snowy
parts of rain biomes. Lightning bolts and thunder are separate server events and are
not hidden.
