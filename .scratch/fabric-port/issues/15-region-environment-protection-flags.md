# 15 — Region environment protection + the five real flags

**What to build:** Regions defended against the world itself (new in the port): explosions, fire, piston reach-in, and mob griefing can't damage region blocks — and the five formerly inert flags become functional toggles.

**Blocked by:** 14 (Player protection).

**Status:** done

See `../spec.md` (User Stories 35–36; deviation register 7). Flag semantics are the spec's proposal from names + pre-proxy convention; if community lore disagrees at implementation time, adjust and note it in the deviation register.

- [x] Explosions of any source do not destroy blocks inside a region unless it has ENABLE_EXPLOSIONS
- [x] Fire does not spread into or burn region blocks unless ENABLE_FIRE_DAMAGE
- [x] Pistons outside a region cannot push into or pull blocks out of it; mob griefing (e.g. endermen) cannot alter region blocks
- [x] DISABLE_PLAYER_FALL_DAMAGE negates fall damage inside; DISABLE_PUBLIC_REDSTONE_TRIGGERS stops non-members using buttons/levers/pressure plates; DISABLE_GATES stops non-members using doors/gates/trapdoors
- [x] Gametests: each hazard with and without its flag; each flag toggled live via `/rg flag`

## Comments

Implemented as `eu.mctraveler.region.RegionEnvironment` plus additions to `RegionProtection`, behind seven small Java mixins. `./gradlew build` green: unit tier plus **202 headless gametests** (159 pre-existing, 43 new across `RegionEnvironmentGameTest` and `RegionFlagGameTest`).

**The one new module.** `RegionEnvironment` is the ambient half of `RegionProtection`, and the line between them is *whether anybody is asking*: everything in `RegionProtection` has a player to refuse and answers with the Portal's one message; everything in `RegionEnvironment` has no player at all — an explosion, a fire, a piston, a creeper — and is silent. Both are read from live state, so a `/rg flag` toggle is in force for the very next explosion (`togglingEnableExplosionsTakesEffectAtOnce` and its four siblings pin that for all five flags).

**Hook points, one per hazard.** Vanilla has no single "a block is about to change" seam that could be guarded without breaking the world's own physics, so each hazard is hooked where it is decided:

| Hazard | Hook | Why there |
| --- | --- | --- |
| Explosions (all sources) | `RegionExplosionMixin` → `ServerExplosion.calculateExplodedPositions` (RETURN) | Every explosion in the game is a `ServerExplosion`; it computes the reached blocks **once** and hands the same list to the block interaction *and* to the fire it leaves. Filtering that list is the whole rule for TNT, creepers, beds, anchors, end crystals, ghasts and wind charges at once. |
| Fire burning blocks | `RegionFireMixin` → `FireBlock.checkBurnOut` (HEAD) | The six neighbours a fire consumes each tick — named by the *block* being burnt, not the flame, which is what the rule needs when the fire is outside the region it is eating into. Also stops fire priming a region's TNT. |
| Fire spreading | `RegionFireMixin` → `FireBlock.getIgniteOdds(LevelReader, BlockPos)` (HEAD → 0) | The single caller is the spread loop; nought odds means fire never catches there. The other `getIgniteOdds` overload (BlockState) feeds `canSurvive`/`canBurn` and is deliberately untouched, so fire already inside keeps burning. |
| Pistons | `RegionPistonMixin` → `PistonStructureResolver.resolve` (RETURN → false) | The resolver is where a piston asks "can this structure move?", and by then every block involved is known. Answering no is the answer obsidian gives, so vanilla's own long-settled paths handle it: the extension is refused outright, and a sticky retraction brings the arm home empty (`aPistonOutsideCannotPullBlocksOutOfARegion` asserts no orphaned piston head). |
| Mob griefing | `RegionMobGriefingMixin` → `Level.destroyBlock(BlockPos, boolean, Entity, int)` (HEAD → false) | The one method every block a creature destroys goes through, and the only one carrying the creature's name: ravagers, withers, zombies at doors, villagers harvesting, rabbits raiding, silverfish waking friends. |
| Endermen taking | `RegionEndermanTakeMixin` → `@Redirect` on `Level.getBlockState` in `EnderMan$EndermanTakeBlockGoal.tick` | An enderman never *destroys*; it removes and pockets in one breath. Making the block look like air is the one refusal that leaves the goal's bookkeeping intact — refusing only the removal would leave it holding a copy of a block still in the ground. |
| Endermen dropping | `RegionEndermanPlaceMixin` → `EnderMan$EndermanLeaveBlockGoal.canPlaceBlock` (HEAD → false) | A block appearing uninvited is as much a change to a build as one going missing. The goal already asks whether the spot will do; this adds the region to that question, so the enderman keeps looking and keeps carrying. |

**Hook points, one per flag.**

| Flag | Hook | Rule as built |
| --- | --- | --- |
| `ENABLE_EXPLOSIONS` | the explosion filter above | Opt-**in**. Without it a region's blocks are removed from every blast. |
| `ENABLE_FIRE_DAMAGE` | the two fire hooks above | Opt-**in**, and one flag for both halves — the name reads as "this region burns", so without it fire neither eats in nor spreads across the boundary. |
| `DISABLE_PLAYER_FALL_DAMAGE` | `ServerLivingEntityEvents.ALLOW_DAMAGE` | Any damage tagged `#minecraft:is_fall` to a player whose current region (`RegionTracker.regionOf`) flies the flag is cancelled. Everyone inside is caught, member or not — it is the ground that is soft. Nothing else is forgiven (`onlyTheFallIsForgiven`). |
| `DISABLE_PUBLIC_REDSTONE_TRIGGERS` | `BlockEvents.USE_WITHOUT_ITEM` for buttons and levers; `RegionPressurePlateMixin` → `BasePressurePlateBlock.entityInside` for plates | Opt-in **restriction on non-members** (`!canModifyRegion`, so residents and any `PUBLIC` region are unaffected). |
| `DISABLE_GATES` | `BlockEvents.USE_WITHOUT_ITEM` for `DoorBlock`, `FenceGateBlock`, `TrapDoorBlock` | Same shape. The two flags are independent switches — `disableGatesLeavesTheLeverAlone` pins that each closes only its own door. |

`BlockEvents.USE_WITHOUT_ITEM` is the exact seam ticket 14 predicted: the *block's own* right-click behaviour, which 14 deliberately left unguarded so these two flags would have something to disable. It is the right one rather than `UseBlockCallback` because it fires only for that behaviour, and because it hands over the `BlockState` the classification needs. Vanilla reaches it even with something in hand (`BlockBehaviour.useItemOn` defaults to `TRY_WITH_EMPTY_HAND`), so a stranger cannot open a door by holding a stick.

**Deviations / interpretations recorded here:**

- **Blast damage to players and entities is untouched.** Only a region's *blocks* are shielded; the blast is computed from the centre and radius, not from the filtered list, so it still knocks players about and hurts them (`anExplosionStillHurtsInsideAProtectedRegion` pins the choice). Story 35 says "harming my region", and a region is its blocks.
- **The piston rule is "same region as the piston".** Every block a piston would take, land on or destroy — plus where its arm lands — must belong to the piston's own region or to nobody. That single sentence gives both halves of the acceptance criterion, keeps a region's own redstone working (`aPistonInsideTheRegionPushesFreely`), still lets a resident push out onto unclaimed ground, and treats a sub-region as the separate owner it is.
- **Pressure plates refuse silently**, unlike buttons, levers and doors. Standing is not an attempt, and `entityInside` is asked on every tick a foot is on the plate — the Portal's message there would be a stream. The right-clicked triggers all carry the exact Portal refusal.
- **A thrown thing counts as whoever threw it.** A splash water bottle dousing a fire destroys the block as *itself*, so without this a resident could not put out a fire in their own region; `Projectile.getOwner()` is unwrapped before the creature test (`aPlayersOwnSplashOfWaterStillWorksInTheirRegion`, `aMobsThrownThingIsStillTheMob`).
- **A change with no entity behind it is the world's physics and is never refused** — a block losing its support, a plant losing its light. Guarding those would stop a region's own blocks behaving, so `theWorldsOwnBlockPhysicsStillRunInsideARegion` pins the boundary deliberately.
- **Flags are read off the deepest region**, exactly like every other flag (`RegionsFeature.regionAt`), so a sub-region's `ENABLE_EXPLOSIONS` applies to its own footprint and not its parent's.
- **A wind charge cannot trigger blocks inside a protected region**, because triggering rides the same exploded-positions list. Consistent with the rest, and noted in case it ever surprises anyone.
- **An iron door or iron trapdoor in a `DISABLE_GATES` region answers with the refusal** even though a hand could not have opened it anyway. Harmless, and the alternative is per-block special-casing of what would have happened.
- `RegionService.regionAt` no longer allocates: it used to build a filtered list of roots per call, which is fine for a command and wrong for a block tick. Same answer, no allocation.

**Perf reasoning.** Every rule here starts from `RegionsFeature.regionAt`, a linear scan of one World's *root* regions that descends only into a region it is already inside. A server with no regions pays one empty-list check per question. Per event the cost is bounded and small: an explosion asks once per reached position (a few hundred, once); a fire tick asks six times for burn-out plus once per candidate spread cell (54), and a fire block ticks roughly every 35 ticks; a piston asks at most 2×12+1, only when it fires; mob griefing asks once per destroyed block. Nothing caches, and nothing reads a member set. The scan is linear in the number of *root* regions, so if MCTraveler's region count ever grows into the thousands, that lookup — not these hooks — is the thing to index by chunk column.

**For ticket 19's audit.**

- Known gaps, all deliberate: **sheep** (`EatBlockGoal`) crop grass through an *entity-less* `destroyBlock`/`setBlock` and so slip the creature rule; **silverfish infesting** stone (`SilverfishMergeWithStoneGoal`) is a plain `setBlock` and is likewise uncovered, though silverfish *destroying* infested blocks is covered; **buttons pressed by arrows** (`ButtonBlock.onProjectileHit`) bypass `DISABLE_PUBLIC_REDSTONE_TRIGGERS`; **tripwire** is not in the trigger vocabulary (the acceptance list names buttons, levers and pressure plates).
- **Fluid flow is out of scope.** Inventory §7 item 7 lists "fluid" alongside explosion/fire/piston/mob, but story 35 and this ticket's acceptance list do not — lava or water spreading into a region is untouched. Worth a conscious decision at audit time.
- A pressure plate already pressed by a member stays pressed while a non-member also stands on it: the un-press check (`getSignalStrength`) counts every entity in the box, and only the *pressing* is guarded.
- Verified by inspection rather than by test: that each named griefer (ravager, wither, villager, zombie, rabbit) reaches `Level.destroyBlock` with itself as the entity — read at each call site, while the gametest drives that seam with a real ravager rather than each mob's AI; and that non-overworld dimensions behave, since every gametest runs in the gametest overworld and the World-string mapping is `RegionWorlds`' single seam (unit-tested in ticket 12). All seven mixins are exercised by at least one gametest, so none of them can fail to apply unnoticed.
- Gametest harness notes: fire is driven through `BlockState.tick` on a fixed seed rather than waiting ~35 ticks between natural ticks, and the test fire is kept lit and fed because a fire with nothing burnable beside it gives up before it ever looks around. Enderman behaviour is driven by fetching the mob's own goal off `getGoalSelector()` and ticking it — vanilla's mob goals are private inner classes, so they are found by the tail of their class name (`RegionEnvironmentGameTest.runGoal`).
