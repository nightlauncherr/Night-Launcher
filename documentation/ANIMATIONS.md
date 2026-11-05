# First-person animations and visuals

Right Shift → **Animations** and **Visuals**. Everything here is client-side and visual
only. Each option can be switched off on its own, and each tab has a button that restores
Minecraft's own look (Visuals also has one that resets animations, visuals and kill effects
together).

## What is never changed

The animation code only changes *where the held item is drawn* in first person. It replaces
three ItemRenderer helpers (`doItemUsedTransformations`, `transformFirstPersonItem`,
`doBlockTransformations`, see `MixinItemRenderer`) and nothing else. Attacks are started,
timed and sent by vanilla code; the animation starts when vanilla's own arm swing starts
(`swingProgressInt` / `isSwingInProgress`). There is no attack delay, cooldown, reach,
hit-registration or rotation change, and blocking and item use behave as in vanilla.
Item switching selects the new slot at once; only the hand's motion is eased.

## Sword styles

| Style | What it does |
|---|---|
| Night slash (default) | Wind-up: the blade turns to face you, rises and cocks back. Strike: it rolls up and over to the left and down, leaving a glowing crescent trail in the sword's own colour with a bright leading edge. Follow-through: it carries on below the screen, keeps turning the same way and rises back into place (one full turn, never rewinding). |
| Smooth | Minecraft's motion, eased (vanilla's `sqrt` timing jumps at the start) and run on real time. |
| Chop | Lifts back, then drives down and across; suited to axes. |
| Vanilla | Minecraft's own animation, untouched. |

Tools (axes, pickaxes, shovels, hoes, fishing rods) have their own switch and style
(Smooth by default). Bows, food, potions and maps keep Minecraft's use animations.

**Speed** (0.5-2x) and **Intensity** (0.5-1.5x) scale the motion. The animation runs on
wall-clock time (`SwingState`), so it looks the same at 30 or 300 FPS. Clicking again
mid-swing cross-fades from the current pose over 70 ms instead of snapping, taking the
short way round for angles.

Poses are keyframed (`SwingStyle`) and interpolated with a monotone cubic
(`SwingCurve`, Fritsch-Carlson), so there is no overshoot between keys. The same pose maths
drives OpenGL and the trail sampling (`Mat4`), so the trail is computed analytically for any
frame rate without a history buffer. Unit tests cover the curves, wall-clock timing,
restart cross-fades, block easing at 30/144/500 FPS, rest poses and matrix composition.

First-person extras: smooth item switching, smooth blocking (eased in and out), an optional
visual swing while blocking, and a very small idle sway.

For checking the animation frame by frame, the JVM option
`-Dkestrel.animTimeScale=0.05` slows only the animation clock (diagnostic; default 1).

## Visuals

| Option | What it does |
|---|---|
| Hit sparks | Short spark rays where your own melee hits land; gold when the hit meets vanilla's critical-hit conditions. |
| Projectile trails | Fading ribbons behind arrows (gold for critical arrows), ender pearls, snowballs, eggs and potions. |
| Arrow impact bursts | A small glow and ring where an arrow lands. |
| Experience orb glow | A soft pulsing glow around experience orbs within 32 blocks. |
| Refined rain and snow | Night's finer rain and snow textures (`MixinEntityRendererWeather`); the amount, area and timing of weather are vanilla's. Off by default. |

Tracking is bounded (48 projectile trails, 16 spark bursts, 64 orbs) and trail buffers are
pooled, so nothing grows with the number of projectiles.
