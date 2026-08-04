# 06 — Moving the players

**What to build:** A player who was last in Secondary logs in standing exactly where they
logged out, and everything they own that remembers a place still remembers the right one.
This is the fiddliest pass in the merge, because a player carries far more geography than
their position.

Their save records where they stand and in which dimension; where their bed is; where they
last died, which is what a recovery compass reads; where they entered the nether; and the
vehicle they logged out inside, which has a position of its own. Their inventory and ender
chest can hold lodestone compasses, including inside shulker boxes inside other containers.
Their player record holds the World they were last in and a Per-World Bucket for Secondary
carrying a second position and a second respawn point.

Which side gets the offset is the trap: a player live in Secondary with a banked Primary
position needs the live one moved and the banked one left alone, and a player live in
Primary needs the mirror. Both mirrors go through one path, so both are covered by the
same tests.

Each swept record is stamped with the merge and the offset that was applied, so that months
from now "was this player swept?" is answerable from the record rather than guessed at.
And the banked position — the one being discarded — is transformed into merged coordinates
and written somewhere the signpost can read it back, so a player can be told where their
other base went.

**Blocked by:** 01 — Merge geometry and the placement search.

**Status:** done

- [x] A player last in Secondary's overworld or nether arrives at the same place, in the
      corresponding Primary dimension, facing the same way
- [x] Their respawn point moves with them, so dying puts them back at their own bed
- [x] Their last death location moves, so a recovery compass points at their items
- [x] The position they entered the nether from moves
- [x] A player logged out in a boat or minecart arrives still in it, at the relocated place
- [x] Lodestone compasses in the inventory and in the ender chest are retargeted, including
      inside nested containers
- [x] A player last in Primary is not moved, and neither is anything they own
- [x] A Secondary Per-World Bucket's position and respawn point are transformed; a Primary
      one is not
- [x] Every record the merge touched is stamped with the fact of the merge and the offset
      applied
- [x] All other fields in a player record, including legacy ones, pass through byte for
      byte
- [x] The banked position of every player who had one is transformed and written where the
      signpost can read it back
- [x] The report states how many players were swept, how many were left alone, and how many
      banked positions were recorded
- [x] Tests cover both mirrors — live in Secondary with a banked Primary position, and live
      in Primary with a banked Secondary one — plus the vehicle, the nested lodestone, and
      a record carrying unknown legacy fields

## Comments

### Implementation summary

- `src/main/kotlin/eu/mctraveler/importer/MergedPlayerdata.kt` — the transform, on NBT and
  on a Per-World Bucket. No file handling, no report; the claim path (ticket 10) calls this.
- `src/main/kotlin/eu/mctraveler/importer/PlayerSweep.kt` — the pass over `world/playerdata/`
  and `mctraveler/players/`, plus `MergeStamp`, `BankedPosition` and `PlayerSweepReport`.
- `src/main/kotlin/eu/mctraveler/importer/MergeStaging.kt` — the staging discipline for a
  merge that writes *into* a live save rather than beside it. Ticket 01 refused over a
  leftover staging directory but never made one; this makes and commits it.
- `src/test/kotlin/eu/mctraveler/importer/WorldMergePlayerSweepTest.kt` (17 tests) — the
  same seam as `WorldMergeTest`, a sibling class rather than more of that one, because 02
  and 05 are extending it in parallel.
- `WorldMerge.kt` gains only `MergeReport`, the `run()` signature, and a three-line staging
  block; `MergeGeometry.kt` gains the `Double` overload ticket 01 asked for.

### Public surface later tickets build on

```kotlin
object MergedPlayerdata {
  fun merged(tag: CompoundTag, offset: MergeOffset): CompoundTag   // always a copy
  fun merged(bucket: PerWorldBucket, offset: MergeOffset): PerWorldBucket  // Secondary only
}

object MergeStamp {                       // ticket 10 must write an identical one
  const val FIELD = "merge"
  fun json(offset: MergeOffset, at: Instant): String
}

data class MergeReport(val placement: MergePlacement, val players: PlayerSweepReport)

data class PlayerSweepReport(
  val swept: Int,
  val leftAlone: Int,
  val banked: List<BankedPosition>,
  val anchoredInSecondaryEnd: List<UUID>,   // ticket 07's input
)

data class BankedPosition(
  val uuid: UUID, val world: String, val dimension: String,
  val x: Double, val y: Double, val z: Double,
)

class MergeStaging {                      // tickets 02 and 05 want this too
  fun stage(live: Path): Path             // the staged twin of a run-directory file
  companion object { fun <T> commit(target: Path, root: Path, work: (MergeStaging) -> T): T }
}
```

### The artifact ticket 08 reads

`mctraveler/banked-positions.json`, written only when at least one player had a banked
position — `/switch` has to cope with the file's absence anyway (every unmerged server is in
that state), so an empty file would be a second spelling of the same case.

```json
{
  "mergedAt": "2026-08-02T00:49:31.123456Z",
  "offset": {"x": 8192, "z": -4096},
  "players": {
    "11111111-2222-4333-8444-555555555555": {"world":"primary","dimension":"minecraft:overworld","x":1.5,"y":70.0,"z":2.5}
  }
}
```

`world` is the World the base *was* in, which is the word the player recognises; `dimension`
and the coordinates are where it is **now**, already merged. One player per line so an
operator can grep it. A player with no entry never had another base — say nothing rather
than something empty.

### Judgement calls

1. **Which side a place is on is the place's own answer, never its owner's.** One save
   routinely names both Worlds at once — a Primary player's ender chest holds a Secondary
   lodestone, and a death location outlives the Travel that followed it — so there is no
   "this is a Secondary player" decision anywhere. Every place carrying a dimension is asked
   which one, and both mirrors are literally one code path. Only the places with *no*
   dimension of their own (position, vehicle, `sleeping_pos`, `raid_omen_position`, the
   explosion impact positions) fall back to the save's `Dimension`.
2. **Global positions are found by shape, at any depth, not by key path.** A compound with a
   `dimension` string and a `pos` int-array of three is vanilla's `GlobalPos`, and that one
   rule covers the respawn point, the last death location, every lodestone target however
   deeply nested, and a villager passenger's brain memories. It is a shape and not a
   heuristic: a bare int-array of three matches a hundred harmless things and a uuid is an
   int-array of four, so the dimension string — which must name one of *Secondary's* three —
   is the discriminator.
3. **Secondary's End is left exactly as it is, and the players in it are named.** There is no
   offset for a dimension being deleted, and re-pointing an End place at Primary's End would
   invent somewhere the player has never been. `PlayerSweepReport.anchoredInSecondaryEnd` is
   the list ticket 07 needs; the report grows a line only when it is non-empty.
4. **`lastServer` is rewritten `secondary` → `primary` for everyone.** There is one World
   after this, and a record still naming Secondary would have `Worlds.handleLogin` place its
   owner into a World that is being retired.
5. **A record is copied into staging and edited through the real `JsonPlayerStore`**, so the
   byte-for-byte passthrough of unknown fields is the store's own already-proven guarantee
   rather than a second implementation of it. A record with nothing to change has its staged
   copy deleted again, so it is never written at all.
6. **`<uuid>.dat_old` is swept beside `<uuid>.dat`.** `PlayerDataStorage.load` falls back to
   it, and an unswept backup would strand its owner on exactly the day their save went bad.
7. **The stamp goes on records the merge actually changed.** A player it had nothing to do
   for is left alone in the strict sense — the file is never opened for writing — which is
   what makes the byte-for-byte criterion checkable.

### Things the later tickets and the runbook must know

- **`WorldMerge.run()` now returns `MergeReport`, and the command now writes.** `WorldMergeMain`'s
  usage text and `gradle/merge-worlds.gradle.kts`'s description no longer say "writes nothing";
  they say nothing is written unless the whole merge succeeds. There is still no `--plan`
  flag — if the operator wants a placement without a sweep, that is a flag someone has to add.
- **Coordinate-bearing fields the ticket did not name, all now handled:**
  `raid_omen_position`, `sleeping_pos`, `last_explosion_impact_pos` and
  `current_explosion_impact_pos` (two distinct keys), `ender_pearls[]` with its own
  `ender_pearl_dimension`, `ShoulderEntityLeft`/`Right`, a vehicle's `leash` block position,
  and `Passengers` recursion at any depth.
- **Deliberately not rewritten:** bare positions inside the arbitrary-NBT escape hatches —
  root `data` (`CustomData`), `minecraft:entity_data`, `minecraft:block_entity_data`,
  `minecraft:bucket_entity_data`. A bucketed axolotl's `Pos` is not a place anyone stands.
  Global positions *inside* them are still found by the shape walk.
- **`RootVehicle.Attach` is a uuid** — an int-array of four — and is left alone. This is the
  one thing in a player save that is an int array and not a position.
- **`entered_nether_pos` is an overworld position** and takes the overworld shift even for a
  player standing in the nether. A player who has since Travelled carries a stale one from
  the other World with nothing recording which; the cost of guessing wrong is one advancement
  measured from the wrong place.
- **The quarantine is not swept.** `mctraveler/orphaned-saves/` is ticket 10's, and those
  saves name the *vanilla* trio (a backend wrote them), so `MergedPlayerdata.merged` as it
  stands would not move them — the claim path knows which quarantine a save came out of and
  has to say so.
- **Ticket 07's bed cross-check has no list to work from.** The sweep does not accumulate the
  respawn points it moved; 07 will need its own pass, or a field added here.
