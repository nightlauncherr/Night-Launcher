# Kill effects

A kill effect plays at the victim's position when **you** kill another **player**. It is
drawn by your client only (particles, sounds and some custom geometry); nothing is sent to
the server.

## When an effect plays

Only for a player you hit yourself, and only when the kill is clearly yours:

- **Never** for mobs or other non-player entities, other players' kills, deaths from
  falling, lava, fire, the void or mobs, or your own death.
- **Death seen in the world** (`healthDetection`): the victim's health reaches 0, the last
  damage it took arrived within 1 s of your hit, it died within 1.5 s of your hit, and no
  other player was swinging within 6 blocks of it (or an arrow that is not yours flying at
  it) at any time from 1 s before your last hit until its death, as far as your client
  saw. Implemented in `KillAttribution` (unit-tested).
- **Kill-feed message** (`chatDetection`): a server line that names you and the victim *and*
  makes you the killer: "<victim> was slain by <you>", "<you> killed <victim>". Lines such as
  "<you> was slain by <victim>", "... assisted by <you>" and player chat never match.
  Implemented in `KillMessageMatcher` (unit-tested).
- Known limit of the health path: damage of any kind that lands within 1 s of your hit
  counts as yours (the client cannot see the damage source), much like vanilla credits a
  fall or lava death shortly after a hit to the last attacker.
- If attribution is unclear, nothing plays. A kill confirmed both ways plays once; the same
  victim cannot trigger another effect for 6 s.

## Effects

| Effect | Category | Colour setting | Sound | What it does |
|---|---|---|---|---|
| Particle burst (`burst`) | Classic | - | `random.orb` |  |
| Particle ring (`ring`) | Classic | - | `random.orb` |  |
| Colour helix (`helix`) | Classic | yes | `random.orb` |  |
| Colour burst (`dust`) | Classic | yes | `random.orb` |  |
| Explosion (`explosion`) | Classic | - | `random.explode` |  |
| Smoke bomb (`smoke`) | Classic | - | `random.fizz` |  |
| Pixel blast (`pixel`) | Classic | - | `random.pop` |  |
| Lightning (`lightning`) | Elemental | - | `ambient.weather.thunder` |  |
| Flame pillar (`flames`) | Elemental | - | `mob.ghast.fireball` |  |
| Electric shock (`shock`) | Elemental | - | `random.fizz` |  |
| Ice shatter (`ice`) | Elemental | - | `dig.glass` |  |
| Nether blaze (`nether`) | Elemental | - | `mob.blaze.hit` |  |
| Tornado (`tornado`) | Elemental | - | `mob.enderdragon.wings` |  |
| Arcane (`arcane`) | Magic | - | `mob.endermen.portal` |  |
| Soul release (`soul`) | Magic | - | `mob.ghast.moan` |  |
| Ender vanish (`ender`) | Magic | - | `mob.endermen.portal` |  |
| Portal (`portal`) | Magic | - | `mob.endermen.portal` |  |
| Dragon breath (`dragon`) | Magic | - | `mob.enderdragon.growl` |  |
| Energy burst (`energy`) | Magic | yes | `random.orb` |  |
| Hearts (`hearts`) | Celebration | - | `random.levelup` |  |
| Fireworks (`fireworks`) | Celebration | yes | `fireworks.blast` |  |
| Confetti (`confetti`) | Celebration | - | `fireworks.twinkle` |  |
| Rainbow rings (`rainbow`) | Celebration | - | `random.orb` |  |
| Falling stars (`starfall`) | Celebration | - | `fireworks.twinkle` |  |
| Kestrel strike (`kestrel`) | Signature | - | `mob.bat.takeoff` |  |
| Lightning strike (`bolt_strike`) | 3D | - | `ambient.weather.thunder` | Three forked 3D bolts (glow + white core ribbons) strike from 22 blocks up, with a ground flash, expanding shock ring and a scorch mark. |
| Energy explosion (`energy_nova`) | 3D | yes | `random.explode` | An orb in your colour charges with orbiting rings, then detonates into a Fresnel-lit shell with three crossing shock rings. |
| Black hole (`black_hole`) | 3D | - | `mob.endermen.portal` | A black event horizon with a photon ring, a tilted spinning accretion disk with spiral arms and in-falling streaks; collapses into a flash and shock ring. |
| Dragon burst (`dragon_burst`) | 3D | - | `mob.enderdragon.growl` | Three serpent ribbons coil up around the victim over a rotating rune ring and burst into a violet shell at the top. |
| Crystal explosion (`crystal_burst`) | 3D | - | `dig.glass` | Ten faceted (lit) bipyramid crystals erupt with an overshoot, glow, then shatter into tumbling shards that fall under gravity. |
| Fire tornado (`fire_tornado`) | 3D | - | `mob.ghast.fireball` | Six flame ribbons spin up a widening funnel around a burning cone and ground ring, then the funnel lifts off and burns out. |
| Frost spikes (`frost_spikes`) | 3D | - | `dig.glass` | A frost floor spreads, ice spikes burst from it in a ring, then break into ice cubes that tumble and settle. |
| Portal rift (`portal_rift`) | 3D | - | `portal.trigger` | An oval rift opens facing you: dark core, counter-rotating gapped rings and a spiral swirl; it snaps shut with a flash. |
| Meteor impact (`meteor`) | 3D | - | `random.explode` | A meteor with a two-layer fire tail falls from the sky; impact flash, blast dome, two shock rings, glowing ground cracks and flying debris. |
| Soul ascension (`spirit_ascend`) | 3D | - | `mob.ghast.charge` | A soul orb rises out of the victim trailed by five spiralling wisps and a fading light beam, crowned by expanding halos. |
| Electric sphere (`plasma_sphere`) | 3D | - | `random.fizz` | A plasma shell with crackling arcs regenerated every two ticks and bolts down to the ground; implodes in a flash. |
| Dark energy (`dark_vortex`) | 3D | - | `mob.wither.shoot` | A funnel of dark (normally blended) smoke ribbons with violet edges spirals into the victim, implodes, and releases a dark shock wave. |
| Rainbow explosion (`prism_burst`) | 3D | - | `fireworks.largeBlast` | 24 prism shards in every hue shoot out on a golden-angle sphere, with rainbow rings and a shimmering shell. |
| Star rain (`star_rain`) | 3D | - | `fireworks.twinkle` | Nine spinning 3D stars fall one after another with glowing tails; each lands with a sparkle and ripple. |
| Void explosion (`void_burst`) | 3D | - | `mob.endermen.portal` | 40 dark voxels are pulled into a void core, which blasts them outwards with a dark shock wave and violet ring. |
| Magic circle (`magic_circle`) | 3D | yes | `random.levelup` | A rune circle in your colour draws itself (rings, ticks, hexagram, procedural runes), rotates, fires a pillar of light and raises a second circle. |
| Flame wings (`flame_wings`) | 3D | - | `mob.enderdragon.wings` | Two wings of flame feathers unfold behind the victim (facing you), beat once and burst into embers. |
| Shattered dimension (`shattered_dimension`) | 3D | - | `dig.glass` | Glowing cracks open in space, then 26 glassy shards (dark interior, iridescent edges) drift and fly apart. |
| Neon pulse (`neon_pulse`) | 3D | yes | `note.pling` | Neon hexagon pulses race over the ground while equaliser bars in your colour and its partner hue bounce around the victim. |
| Kestrel talon (`kestrel_talon`) | 3D | - | `mob.bat.takeoff` | Night's signature: a falcon of light dives through the victim, leaves three talon slashes and a flurry of kestrel-coloured feathers. |

### 3D effects

The **3D** category is drawn with real geometry rather than particles alone: spheres with a
Fresnel rim, faceted crystals and cubes, extruded stars, rings, spirals, camera-facing
ribbons and soft glows, additively blended (dark parts such as the black hole's horizon use
ordinary blending). `Fx` is the geometry toolkit and `Fx3dEffect` the base class
(`AdvancedEffects`, `AdvancedEffects2`). Each effect lays out its random shape once when it
starts, into fixed arrays, so drawing a frame allocates nothing; GL state is put back after
every effect even if drawing fails. Vanilla particles are used only as accents and stay
within the particle budget.

Effects tinted by the colour setting are marked COLOR in game and COLOUR in the launcher.
Particles are capped per effect ("Max particles per effect") and at most a few effects run
at once, so FPS stays reasonable; every effect removes itself when it finishes, or when
you leave the world.

## Previewing

In game: Right Shift → Kill effects → click an effect (equips it and plays it in front of
you), or **Preview effect**. The launcher's Cosmetics → Kill effects page shows an
approximate 2D animation of each effect.
