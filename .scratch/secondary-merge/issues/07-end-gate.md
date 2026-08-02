# 07 — The End, and everything anchored in it

**What to build:** Secondary's End is destroyed by this merge, and that is the one
irreversible thing in it. So it is not something the operator can do by accident: the merge
refuses by default the moment anything is still anchored there, tells them exactly what and
who, and only proceeds when they say so explicitly.

The report has to be good enough to act on before the fact, because afterwards there is
nothing to act on. Regions are listed by title and by the names of their members, so the
operator knows who to warn. Players are counted, and each one's landing is stated in
advance — their Secondary overworld position if they have one banked, and the relocated
Secondary spawn if they do not. Embassy destinations pointing into the End are named, and
cleared rather than left aiming at nothing.

This ticket also adds the second cross-check the audit could not do alone: every respawn
point the player sweep transformed must now have a bed or a respawn anchor standing at it
in the relocated chunks. A respawn point and its bed are moved by two different passes, and
if they disagree, players wake up inside solid rock.

**Blocked by:** 05 — Moving the Regions; 06 — Moving the players.

**Status:** done

- [x] The merge refuses, writing nothing, if any Region, player or Embassy destination is
      anchored in Secondary's End
- [x] That refusal lists every affected Region by title and by its members' names
- [x] It counts the affected players and states what will happen to each group
- [x] It names every Embassy whose destination points into Secondary's End
- [x] An explicit opt-in accepts the loss and lets the merge proceed
- [x] With the opt-in, End Regions are deleted, End Embassy destinations are cleared, and
      each affected player lands at their banked Secondary overworld position, or at the
      relocated Secondary spawn if they have none
- [x] No player is left naming a dimension that will not exist after the merge
- [x] Every respawn point the sweep transformed has a bed or respawn anchor at it in the
      relocated chunks, or the merge fails naming the player and the position
- [x] The report records what was dropped, so the operator has a record of what to
      communicate afterwards
- [x] Tests cover the default refusal, the opt-in, both player landings, and a respawn
      point whose bed did not survive relocation

## Comments

### Implementation summary

- `src/main/kotlin/eu/mctraveler/importer/MergeEnd.kt` — the gate, `MergeEndReport`,
  `EndRegion` and `EndLanding`. One entry point, `MergeEnd(plan, staging, offset,
  anchored).close()`.
- `src/main/kotlin/eu/mctraveler/importer/RespawnBeds.kt` — the respawn-to-bed cross-check
  and `RespawnCheckReport`, including the block-state reader it needs.
- `src/test/kotlin/eu/mctraveler/importer/WorldMergeEndGateTest.kt` (16 tests), driving the
  merge command end to end against `MergedDeploymentFixture`.
- `MergeStaging.kt` gained `latest()` and two lines in `write()`; `MergePlan` gained
  `acceptEndLoss`; `WorldMergeMain` gained `--accept-end-loss`.

### Public surface later tickets build on

```kotlin
class MergeEnd(plan, staging, offset, anchored: List<UUID>) {
  fun close(): MergeEndReport
  companion object {
    const val OPT_IN = "--accept-end-loss"
    val END_WORLD: String        // "last_the_end", derived from RegionWorlds
    val END_DIMENSION: String    // "mctraveler:secondary_end"
  }
}

data class MergeEndReport(
  val regionsDeleted: List<EndRegion>,        // title + members' names
  val destinationsCleared: List<String>,      // Embassy Region titles
  val landed: List<EndLanding>,
) : MergeSection { val anythingLost: Boolean }

data class EndLanding(uuid, x, y, z, ownBase: Boolean) { val where: String }

class RespawnBeds(plan, staging, stagedLevelDir) { fun check(): RespawnCheckReport }
data class RespawnCheckReport(confirmed: Int, alreadyWithoutABed: Int) : MergeSection

class MergeStaging {
  fun latest(live: Path): Path   // the staged file if a phase built one, else the live one
}
```

### Judgement calls

1. **The gate runs after the sweeps, not before them.** Its inputs are exactly what 05 and
   06 hand over, and its deletions have to land on top of their rewrites — hence
   `MergeStaging.latest`, which reads the staged twin of a file when there is one. The cost
   is that the refusal comes after the relocation has been staged and thrown away; nothing
   is written either way, and the rehearsal the runbook mandates is where an operator meets
   it.
2. **The gate re-reads the region tree rather than acting on `MergeRegionsReport.endAnchored`.**
   That list is prose (`the Region "…"`), which is the right shape for 05's own report line
   and cannot carry members' names or be deleted from. The gate needs the tree to delete
   from anyway, so it parses it once and answers both questions from it. 05's section still
   says what the sweep left; this one says what the gate then destroyed, in phase order.
3. **Member names come from the run directory's own `usercache.json`.** It is what vanilla
   fills in as players log in, so it names everyone who has played since the Portal cutover;
   a member it does not know comes out as their uuid, which is still lookup-able. There is
   no name anywhere else offline — a player record has no name field.
4. **Secondary's spawn is the save's spawn, moved.** Both Worlds share one `level.dat`, and
   `Worlds.place` lands a first-time visitor to Secondary at that position in *Secondary's*
   overworld — so "the relocated Secondary spawn" is `Data.spawn` plus the offset. It is
   read through `LevelData.RespawnData.CODEC`, the codec the server writes it with, rather
   than key by key.
5. **Every save is scrubbed of the End, not only the anchored ones.** A player who never
   left Primary can hold a compass bound to a lodestone in Secondary's End, or a death
   location there. Anything naming the End is *dropped* rather than repointed: there is no
   offset for a dimension being deleted, and aiming a bed at Primary's End would invent
   somewhere its owner has never been. This runs whether or not the opt-in was given,
   because it is not a loss anyone has to accept — the dimension goes either way.
6. **A player landing out of the End loses their `RootVehicle`.** Vanilla remounts a
   logged-out vehicle at the *vehicle's* saved position, so leaving it would drag its owner
   straight back to End coordinates. `sleeping_pos` goes for the same reason.
7. **A Secondary *nether* bucket is not a landing.** The spec says the Secondary overworld
   bucket or the spawn; out of a deleted dimension into a lava-lit cave is worse than a
   spawn everybody knows.
8. **The bed cross-check compares the two passes and does not invent a problem that was
   already there.** Where Secondary's own chunk data has a bed or respawn anchor at the
   point's old position, the relocated data must have one at its new position, and the merge
   refuses naming the player and both positions when it does not. A respawn point whose bed
   was broken months ago — completely ordinary, vanilla keeps the point and tells its owner
   on death — is counted and reported rather than refused; failing a real merge over
   hundreds of those would make it unrunnable while proving nothing about the relocation.
   Only a player's *own* respawn point is checked: a Per-World Bucket carries one too, but
   that is the bed of a World that stops existing at this merge.

### Existing tests this ticket changed, and why

Four tests asserted the interim state that 05 and 06 deliberately left behind ("until it
lands, this sweep's answer is unchanged, and said out loud"). All four now pass
`acceptEndLoss = true`, because the gate otherwise stops the whole merge over their fixture:

- `WorldMergeRegionsTest`: `a Region in Secondary's End is left where it is…` and
  `an Embassy destination naming Secondary's End is left alone…` lost their assertions on
  the *committed* `regions.json` — the gate deletes and clears exactly those — and gained
  `assertFalse(report.regions.rewroteFile)`, which is the same claim about the sweep and is
  still true. `the report names everything still anchored in Secondary's End` is unchanged
  but for the flag.
- `WorldMergePlayerSweepTest`: `a player standing in Secondary's End is left where they are,
  and named` lost its two assertions on the committed save, which are now the gate's answer
  and are asserted in `WorldMergeEndGateTest`; its report assertions are untouched.

### Things the runbook (ticket 12) must know

- **`--accept-end-loss` is the flag, and it is a decision, not a formality.** The rehearsal
  is where the refusal is met: run without it, read the list, warn the named players, then
  pass it on the night. It only permits the loss — with it, End Regions are deleted, End
  Embassy destinations cleared, and the players standing there are put down.
- **The refusal comes late**, after the chunk relocation has been staged, so a rehearsal that
  refuses has still spent the relocation's time. Nothing is written.
- **The report section is a record to communicate from**: every deleted Region with its
  members' names, every cleared destination, and every landed player with where they landed.
  Keep it.
- **`level.dat` must be readable** and carry `Data`/`spawn`, or a merge with players standing
  in the End refuses. It always will on a save this server has booted.
- **The bed cross-check can refuse**, naming a player and two positions. It means a bed that
  exists in Secondary did not arrive where the respawn point says it went — a relocation
  problem, not a data problem, and worth stopping over.
