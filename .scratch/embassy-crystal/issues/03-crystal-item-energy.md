# 03 — Teleportation Crystal item, energy, recipes

**What to build:** The crystal item (Echo Shard + `CUSTOM_DATA` marker + tier,
Notepad idiom), per-player energy behind `PlayerStore` (energy + next-regen-at typed
pairs), the play-time-driven regen loop, the viewer-relative damage display (outgoing
container packet rewrite), the three datapack recipes with the server-side crafting
guard, and `/set-teleportation-crystal-energy`.

**Blocked by:** none.

**Status:** done

See ../spec.md (User Stories 20–25, 37; Implementation Decisions "Crystal item",
"Energy", "Recipes"; deviations 4, 5, 7, 10, 12) and the Nucleus source
`teleportation-crystal.kt` (item/lore/tier constants, `modifyEnergy`/`getEnergy`/
`getNextRegenAt`, `updateItemEnergy`, `initTeleportationCrystal` recipes + packet
adapters + regen task, `onCraft`, `SetTeleportationCrystalEnergyCommand`) in the
reference clone at /Users/jam/Development/MCTravelerNucleus.

- [x] Crystal stacks identified by custom data; name, lore, glint, max stack 1, max
      damage = tier per spec story 22; tiers 1–3 constructible in code
- [x] Energy 0–3 (default 3) persisted per player via PlayerStore; unknown-field
      pass-through in players/*.json intact; regen per story 25 driven by
      `Stats.PLAY_TIME` thresholds, checked every 20 ticks, message on each point;
      dropping below 3 arms the first threshold exactly once (Nucleus `modifyEnergy`
      semantics)
- [x] Outgoing set-slot and set-content packets rewrite crystal stacks to
      damage = 3 − viewer's energy (story 23); stored stacks never mutated; verified
      at the packet level in a gametest
- [x] Recipes: tier 1 shapeless (ender eye), tiers 2/3 plus-pattern per story 20;
      guard blocks a plain echo shard centre and blocks crystals as ingredients in
      any foreign recipe (story 21, recovery compass case tested)
- [x] `/set-teleportation-crystal-energy` per story 37 (USAGE before admin gate,
      house rule; feedback to sender, deviation 5)
- [x] GameTestJanitor updated if any new persisted file appears — no new file
      appears; see note 6 below
- [x] Gametests: crafting all three tiers + both guard cases, regen over
      fast-forwarded play time, damage-bar packets, admin command ladder; unit tests
      for pure energy/threshold logic and store round-trip

## Comments

### Implementation summary

Done on branch `worktree-agent-a9d928846fc924d5e`. Full `./gradlew build` green:
226 gametests + the unit tier.

New package `eu.mctraveler.crystal`:

- `CrystalItem` — the item. Echo Shard + a `minecraft:custom_data` marker, the
  Notepad idiom, no registry writes.
- `CrystalEnergy` — the 0–3 pool, its play-time recharge clock, and Nucleus's
  `modifyEnergy` semantics. Split in two: store-shaped functions carry the whole
  behaviour and are unit-tested against a real `JsonPlayerStore` over a temp
  directory; player-shaped ones are the façade everything at runtime uses.
- `CrystalCrafting` — the component-aware crafting guard (deviation 7), a pure
  `blocks(grid, width, result)` plus the menu-side enforcement.
- `CrystalDamageDisplay` — the per-viewer damage-bar rewrite (deviation 12).
- `CrystalCommands` — `/set-teleportation-crystal-energy`.
- `CrystalFeature` — the recharge loop and command registration; one line in
  `MCTraveler.onInitialize`.

Two Java mixins, both thin shims into `@JvmStatic` Kotlin, both listed in
`mctraveler.mixins.json`: `CrystalCraftingGuardMixin` (tail of
`CraftingMenu.slotChangedCraftingGrid` — which `InventoryMenu` also routes
through, so the 2×2 grid is covered) and `CrystalDamageDisplayMixin`
(`ModifyVariable` on `ServerCommonPacketListenerImpl.send`).

Three datapack recipes under `src/main/resources/data/mctraveler/recipe/`
(26.2 uses the singular `recipe/` directory and an `ItemStackTemplate` result).

### Public surface for tickets 04 and 05

**Identifying and building crystals** — `eu.mctraveler.crystal.CrystalItem`:

```kotlin
const val MIN_TIER = 1; const val MAX_TIER = 3
const val ITEM_NAME = "Teleportation Crystal"
fun of(tier: Int): ItemStack              // throws outside 1..3
@JvmStatic fun isCrystal(stack: ItemStack): Boolean
@JvmStatic fun tierOf(stack: ItemStack): Int   // absent tier reads as 3 (Nucleus parity)
```

Ticket 04 wants `isCrystal` + `tierOf` on the held stack for the right-click and
the `energy <= 3 - tier` refusal (story 27).

**Reading and changing energy** — `eu.mctraveler.crystal.CrystalEnergy`:

```kotlin
const val MAX_ENERGY = 3
const val RECHARGE_MINUTES = 15
const val RECHARGE_TICKS = 18000          // 15 * 60 * 20

// player-shaped façade — what ticket 04's menu should use
fun energyOf(player: ServerPlayer): Int
fun modify(player: ServerPlayer, delta: Int): Int    // returns the new energy
fun setEnergy(player: ServerPlayer, energy: Int): Int
fun regen(player: ServerPlayer): Boolean
fun playTimeTicks(player: ServerPlayer): Int
fun resync(player: ServerPlayer)          // resends the open container

// store-shaped — what ticket 05's importer should use
fun energyOf(store: PlayerStore, uuid: UUID): Int
fun nextRegenAt(store: PlayerStore, uuid: UUID): Int?
fun modify(store: PlayerStore, uuid: UUID, delta: Int, playTimeTicks: Int): Int
fun regen(store: PlayerStore, uuid: UUID, playTimeTicks: Int): Boolean
```

`modify(player, -1)` is the one call the menu needs when a destination is
chosen; it already resyncs, so every crystal in view repaints itself.

**Store field names** — in `mctraveler/players/<uuid>.json`, alongside
`lastServer` / `notepad` / `worlds`:

| field | type | meaning |
| --- | --- | --- |
| `crystalEnergy` | int 0–3 | absent means full (3) |
| `crystalNextRegenAt` | int | play-time tick the next point is due; **absent means no recharge pending** |

`PlayerStore` gained four methods; `setCrystalNextRegenAt(uuid, null)` *removes*
the field rather than writing a sentinel, so a full player's record looks like
one that never spent anything. Unknown-field pass-through is intact and tested.

Ticket 05 maps Nucleus's `mctravelernucleus:tc-teleportation-energy` →
`crystalEnergy` and `tc-next-regen-at` → `crystalNextRegenAt`. Both are
play-time ticks in Nucleus too (`Statistic.PLAY_ONE_MINUTE`), so no unit
conversion is needed.

**Elsewhere**: `Paint.gold`, `Paint.info`, `Paint.warning` (deviation 10);
`RegionsFeature.adminGate(player): Component?` (hoisted out of `RegionCommands`
so both command families share one refusal string).

### Deviations and judgement calls

1. **Clamping, not throwing.** Nucleus's `modifyEnergy` threw
   `IllegalArgumentException` outside 0–3. Per the ticket, ours clamps —
   a throw inside a per-second tick loop is not something to court.
2. **The crafting guard is positional, and demands exactly one crystal.** The
   ticket asked for "the centre stack must be a crystal of tier n−1"; a
   grid-wide check is *not* equivalent, and my first implementation was wrong.
   The tier-3 recipe keys its arms *and* its centre to `minecraft:echo_shard`,
   and a crystal is an echo shard — so a tier-2 crystal parked in an arm
   satisfied a grid-wide check while a plain shard in the middle got upgraded
   for free. Nucleus was immune (exact-NBT ingredient bound to the centre
   slot). Now: exactly one crystal, in the middle, one tier down. Covered by
   `CrystalCraftingTest` and two gametests; verified they fail against the old
   rule.
3. **Two packet types beyond the ticket's list.** The ticket named
   `ClientboundContainerSetSlotPacket` and `ClientboundContainerSetContentPacket`.
   Since 1.21.4 the cursor and single player-inventory slots have their own
   packets (`ClientboundSetCursorItemPacket`,
   `ClientboundSetPlayerInventoryPacket`), so a crystal picked up onto the
   cursor was losing its bar. Story 23 says "every crystal I see", so both are
   rewritten too.
4. **Nucleus's stale-threshold quirk is preserved, deliberately.** `modify`
   never *clears* the recharge clock; only the regen loop does, on reaching
   full. So an admin setting a below-full player to 3 leaves a stale threshold,
   and that player's next spend is refunded once at the following check. This
   is exactly Nucleus's behaviour (its admin command also went through
   `modifyEnergy`), and the ticket asked for those semantics exactly — but it
   is arguably a bug and may deserve a deviation-register entry. **Flagging for
   the spec owner rather than fixing unilaterally.**
5. **The tier is an NBT byte, not an int.** JSON has no integer widths, so the
   datapack decoder narrows `"tier": 2` to a `ByteTag`. `CrystalItem.of` writes
   a byte too, so a crafted crystal and a code-built one are byte-identical.
   Reading goes through `getIntOr`, which accepts any numeric tag, so a
   hand-written `tier:2` still works. `CrystalRecipeJsonTest` is the anti-drift
   guard between the JSON and the builder — it caught this immediately.
6. **`GameTestJanitor` unchanged, on purpose.** No new persisted *file* appears:
   energy lands in the existing `mctraveler/players/<uuid>.json` records the
   Notepad and Worlds tests already wrote. Gametest players join under
   `UUID.randomUUID()`, so no run can ever read another run's record — verified
   by repeated consecutive green runs. Those files do accumulate in the run
   directory (~2 800 of them now), but that is pre-existing and affects every
   ticket; raised separately rather than widening this ticket's merge surface.
7. **`Paint.warning` has no caller yet.** Added as INFO's half of deviation 10;
   its first consumer is `/embassy delete`'s confirmation (story 17, ticket 02).
8. **USAGE stays aqua.** Nucleus's USAGE prefix was white; the house `Paint.usage`
   (the Portal's `§b§lUSAGE`) is aqua and predates this ticket. Untouched. INFO,
   WARNING, ERROR and SUCCESS all match Nucleus exactly.
9. **Bounds are checked before the player lookup**, so `… 9 Nobody` says
   "Energy must be between 0 and 3" where Nucleus said "Nobody is not online".
   Story 37 lists the checks in this order.

### Things ticket 05 should know

- **Nucleus-era crystals already in player inventories will not be recognised.**
  Ours look for `is-teleportation-crystal` at the top of `minecraft:custom_data`;
  a Bukkit-written crystal carries
  `PublicBukkitValues: {"mctravelernucleus:is-teleportation-crystal": 1b,
  "mctravelernucleus:teleportation-crystal-tier": Nb}` instead. The spec's
  importer section only covers energy, so this was out of scope here — but
  returning players carrying old crystals would find them inert. Either the
  importer rewrites those stacks, or `CrystalItem.isCrystal` grows a legacy
  branch. **Needs a decision.**
- Energy import is a plain `setCrystalEnergy` / `setCrystalNextRegenAt` pair;
  both take play-time ticks directly.
- The importer runs against a stopped server, so it can write the store
  directly — nothing caches these fields in memory.

### Performance note

`CrystalEnergy`'s store reads are whole-file JSON reads, per the store's stated
model. Steady state is one read per online player per second (the recharge loop
early-returns on a full player before the second read), plus one read per
outgoing packet that actually contains a crystal — energy is read once per
packet, not once per stack. If ticket 04's menu makes energy a hot read, an
in-memory mirror in the player-shaped façade is the natural next step; it was
not needed here.
