# Main menu

Night replaces Minecraft's main menu with its own (`client/.../menu/`). `MenuReplacer`
swaps in `KestrelMainMenu` from Forge's `GuiOpenEvent` whenever the game would open
`GuiMainMenu` (start-up, leaving a world, disconnecting, closing a menu with no world), so
no vanilla code is patched and every path is covered.

## What it shows

- **Splash** (first time per session): the Night mark centred with "Click to enter". Any
  click or key enters; the mark glides to the top left and the menu builds in.
- **Navigation** (left column, plain text, accent bar and slide on hover): Singleplayer,
  Multiplayer, Realms, Mods (Forge), Minecraft Settings, Night Settings, Quit Game. Each
  opens the normal screen after a short fade; Back/Esc there returns to this menu.
- **Background** (`MenuBackground`): the launcher's purple nebula: a deep violet gradient,
  soft purple and magenta clouds drifting very slowly, faint fixed stars, a purple light
  behind the character, lavender ribbons on the right that slide in, a few drifting
  sparkles, vignette.
  Coloured geometry only, no textures; allocation-free.
- **Character**: your own skin (the applied Night skin, else your account skin, else
  Steve/Alex offline) as a 3D player model, classic or slim, with every second-layer part.
  It has your name tag and a soft floor shadow. The **SKIN** button under its feet opens
  the Skin screen; a newly applied skin shows on the character straight away.

## The entrance

`EntranceAnimation` (pure, unit-tested) drives it from elapsed wall-clock time, so it is
the same at any frame rate:

1. The character starts off-screen to the right and runs in at a constant speed.
2. It then brakes linearly to zero exactly at its spot: position and velocity are both
   continuous, so it does not snap or overshoot.
3. The stride is driven by distance travelled (one cycle per 2.2 blocks), so the feet keep
   pace with the ground; arms swing opposite the legs. The stride amplitude, forward lean
   and bounce follow the speed, so the last steps shorten and the legs come together.
4. Shortly before arriving it turns from running side-on to face the camera (smoothstep,
   0.5 s).
5. Idle: slight breathing in the arms and body, and the head looks at the pointer (see
   below).

The entrance plays each time the menu opens from elsewhere; returning from a screen opened
from the menu (worlds, servers, settings, skin) keeps the character standing.

## Rendering

`PlayerModelRenderer` draws `ModelPlayer` parts directly (no entity) with vanilla's
inventory-preview lighting, poses the limbs, copies the angles to the second layer, clears
depth first and restores the GL state it changes. The Skin screen uses the same renderer.
Layout is derived from the screen size (character height about half the screen). At GUI
scales that leave a lot of room (Small/Normal on a big window) `VirtualScale` draws the menu
and the Skin screen on a canvas about 260 units tall with a whole-number zoom, so the text
stays crisp and the proportions match every other scale; mouse input maps automatically.

## Head tracking

`HeadLook` (pure, unit-tested) aims the head at the pointer as if the pointer were a point
2.5 blocks (at the character's scale) in front of the screen, so the
angle follows where the pointer actually is at any window size or GUI scale. The angles are
worked out for `PlayerModelRenderer`'s mirrored transform (`scale(-s, s, s)` plus a 180°
turn), where a positive head yaw turns the face to the viewer's left; the body's resting
yaw is subtracted so the head, not the body, does the looking. Yaw is limited to ±55°,
pitch to 32° up and 28° down. It follows with light exponential smoothing (rate 14/s, about
70 ms) and eases back to facing forward while running and when the pointer leaves the window.
The tests check all four directions through the renderer's own transform chain.

## Frame rate

Vanilla 1.8.9 limits every screen without a world to 30 FPS (`Minecraft.getLimitFramerate`
returns a hard-coded 30). That was why the menu felt choppy: drawing it takes well under a
millisecond. `MixinMinecraft` makes Night's animated screens (`FullFrameRateScreen`: the
main menu and the Skin screen) use the player's own Max Framerate setting instead, the same
as in game, so VSync, a lower cap and Unlimited all behave as configured. Other screens keep
vanilla's behaviour.

The backdrop computes each ribbon's centre line once per frame, sends all ribbons in one
draw call and all sparkles in another (same primitives and order, so it looks the same),
forces the shade model once per frame, and the mark's texture filter and the footer text
are set once rather than every frame.

`-Dkestrel.menuStats=true` logs average FPS, 1% low, worst frame time and the menu's own
draw time every 5 s (`MenuDiagnostics`).
