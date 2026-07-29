# Create: Tow & Haul

A NeoForge addon for **Create: Aeronautics / Create: Simulated** (Minecraft 1.21.1).

Towing gear for physics vehicles:

- a redstone driven **car winch** that reels a real physics rope in and out,
- a **tow bar** that the rope hooks onto,
- a **coupling** that links two tow bars with a rigid Create shaft, turning them into a drawbar.

The rope is Simulated's own `ServerRopeStrand`, the exact same one the Aeronautics rope winch
uses. The coupling is a Sable physics constraint between two sub-levels.

> The mod id is still `carwinch`, so every blockstate, model, lang key, recipe and existing world
> keeps working. Only the display name changed.

## Winch and rope

1. Place a **Car Winch** on your vehicle and a **Tow Bar** on whatever you want to drag.
2. Right click the winch with a **Steel Rope**, then right click the tow bar.
   The picked anchor is outlined, valid targets are highlighted, and a preview line is drawn.
   Sneak + right click cancels the selection.
3. The winch swaps to its spooled model, the tow bar swaps to its hooked model, and a physics
   rope appears between them.
4. Shears (anything in Simulated's `destroys_rope` tag) cut the rope. Breaking either end block
   does the same. Both give back the **Steel Rope**, never vanilla rope.

Plain vanilla rope is rejected with a message: winch and tow bar only accept the steel rope.
Winch-to-tow-bar is the only valid pair - two tow bars cannot be roped together, that is what the
coupling is for.

### Redstone control

The winch reads the two vertical sides separately, so one block can both pull and pay out:

| Signal | Behaviour |
| --- | --- |
| From **above** (or from any side) | Reels the rope **in**. Strength scales the speed. |
| From **below** | Pays the rope **out** under power. Strength scales the speed. |
| Both, equal strength | **Brake** - the rope is locked at its current length. |
| No signal | Freewheel - the winch pays rope out once it goes taut, so you can still drive away. |

Tuning constants live in `CarWinchBlockEntity`: `MAX_RANGE`, `REEL_SPEED`, `RELEASE_SPEED`,
`PAYOUT_SPEED`, `SLACK_TOLERANCE`.

## Coupling: a rigid Create shaft

Two tow bars can be joined by a **rigid horizontal shaft** instead of a rope.

1. Park the two contraptions so their tow bars face each other, **up to 4 blocks apart**.
2. Right click with a **Coupling** on the tow bar of the **towing vehicle first**, then on the one
   on the **trailer**. The first click is the ball side.
   A `create:shaft` is drawn between the two live hitch points as one-block segments.
3. **Create wrench** on either tow bar takes the coupling apart. Sneak + wrench rotates the block,
   plain wrench click rotates it too when it is not coupled.

How it behaves physically:

- Length is **measured at the moment you click** and then held fixed (`couplingLength`, capped by
  `MAX_COUPLING_LENGTH = 4.0`, floored by `MIN_COUPLING_LENGTH = 0.05`). Nothing gets teleported
  when the constraint snaps in, and the shaft never stretches afterwards.
- Frames and anchors come from the two blocks' own `FACING`, not from the direction you happened to
  be standing in, so the shaft aligns itself to the hitches instead of freezing in the pose it was
  created in.
- A height difference between the two hitches is **baked into the anchor** (`couplingHeightOffset`,
  up to `MAX_VERTICAL_OFFSET = 1.5`) instead of being left for the solver to remove. Without this
  the solver lifts the bodies and wheels leave the ground.
- The joint locks **only** `LINEAR_X/Y/Z`. All three angular axes are free and there are no
  `setLimit` stops, so the coupling is a pure ball joint: the trailer steers, pitches on hills and
  rolls a full 360 degrees without dragging the towing vehicle's orientation with it. Locking any
  angular axis stitches the two sub-levels' attitudes together, which lifts wheels on slopes and
  generates roll-over torque in turns.
- Both tow bars must sit on **different** assembled contraptions; two tow bars on the same
  contraption cannot be coupled.
- The coupling is stored in NBT (`CouplingTarget`, `CouplingOwner`, `CouplingLength`,
  `CouplingHeight`). If the constraint becomes invalid (chunk reload, contraption reassembly) it is
  recreated, with a cooldown between attempts and a cap on how many times it tries before giving up
  and releasing. A sanity watchdog releases the coupling if the bodies end up impossibly far apart,
  so a broken constraint can never fling a sub-level across the world.

Every rejection tells you exactly why: too far, too close, not horizontal, not on a contraption,
same contraption, or physics refused.

## Art

- The tow bar uses `rope_connector` geometry adapted from Simulated, oriented vertically.
- The rope strand is drawn by this mod's own renderer from `knot` and `rope` partial models that
  reference Create's `industrial_iron_block` textures, so it reads as steel rather than hemp.
- The coupling itself needs no model of its own - it is rendered as Create's shaft block via
  `CachedBuffers.block(KINETIC_BLOCK, state)`. Note that `renderSingleBlock` draws nothing for
  Create kinetic blocks, which is why the cached-buffer path is used.

If you want to replace the block models, export from Blockbench with **File -> Export ->
Block/Item Model** over these paths:

| Blockbench file | Export to |
| --- | --- |
| `winch.bbmodel`   | `src/main/resources/assets/carwinch/models/block/winch.json` |
| `winch_1.bbmodel` | `src/main/resources/assets/carwinch/models/block/winch_1.json` |
| `towbar.bbmodel`  | `src/main/resources/assets/carwinch/models/block/towbar.json` |
| `towbar_1.bbmodel`| `src/main/resources/assets/carwinch/models/block/towbar_1.json` |

The `_1` variants are the *hooked* / *spooled* states. `tools/import_models.sh` warns if a variant
is byte identical to its base model, because then the state change is invisible in game.
Models are authored pointing **up**; `blockstates/winch.json` and `blockstates/towbar.json` rotate
them for the other five facings.

## Building the jar

### Option A - GitHub Actions (no local setup)

`.github/workflows/build.yml` builds on every push to `main` and on manual dispatch, and uploads
`build/libs/carwinch-1.1.0.jar` as a workflow artifact.

### Option B - locally

Needs JDK 21 and internet access (Gradle pulls Create, Sable, Veil, Flywheel and the
Create: Aeronautics bundle from Maven/Modrinth).

```bash
gradle wrapper --gradle-version 8.12   # only once, if you have Gradle installed
./gradlew build
```

Output: `build/libs/carwinch-1.1.0.jar`

`./gradlew runClient` launches a dev client with Create + Aeronautics preloaded.

## How the Simulated dependency is resolved

Create: Aeronautics jar-in-jars the `simulated` module, so `build.gradle` has an
`extractBundledMods` task that downloads `maven.modrinth:create-aeronautics` and unpacks
`META-INF/jarjar/*.jar` into `build/bundled-mods`, then compiles against those.
That guarantees the API you compile against is the API you run against.

If you would rather pin a specific build, drop a `simulated-*.jar` into `libs/` - it is on the
compile classpath too.

## Version stack

| | |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.235 |
| Create | 6.0.10 |
| Create: Aeronautics | 1.3.0 (bundles Simulated 1.3.0) |
| Sable | 2.0.x |

Bump these in `gradle.properties` if the upstream stack moves.

## Known gaps

- `towbar_1.json` (hooked state) is still identical to the base model, so the hooked state is not
  visually distinct.
- No Ponder scene or JEI integration yet.
- With all angular axes free, a badly balanced trailer can sway at speed. Damping would have to be
  added as a soft torque, never as an axis lock.
