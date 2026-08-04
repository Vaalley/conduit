# 01 — Merge geometry and the placement search

**What to build:** An operator can ask where Secondary would go, and get a real answer
without anything being written. A new offline command plans the merge: it measures how far
Primary's overworld and nether have been generated, measures Secondary's footprint, finds
the nearest place Secondary can go that Primary has never touched, and prints the offset it
chose along with both footprints and the clearance it actually achieved. An operator who
already knows the offset can pass it instead, and have it checked rather than trusted.

This ticket carries the vocabulary the rest of the merge is written in — the offset, the
rule that the nether gets one eighth of it, Secondary's footprint, and the transform from a
Secondary coordinate to its merged one — so it lands first and everything else builds on
it. It also stands up the command's skeleton: the report it prints, and the staging
discipline that makes every later refusal write nothing.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] Planning the merge prints the chosen offset, Secondary's overworld and nether
      footprints, and the clearance achieved in each, and writes nothing at all
- [x] The offset is always a multiple of 4096 on both horizontal axes, so that every source
      region file maps onto exactly one destination region file in both dimensions
- [x] The nether offset is exactly one eighth of the overworld offset, so existing portal
      pairs still link; no vertical offset is ever applied
- [x] Clearance is specified in nether blocks and applied to the overworld multiplied by
      eight, because the nether is the binding constraint
- [x] A candidate placement is rejected unless the overworld footprint plus its ring and
      the nether footprint plus its ring are both entirely free of region, entity and
      point-of-interest data
- [x] Candidates are considered in ascending distance from the origin, so the nearest
      viable placement wins
- [x] An offset supplied by the operator is validated by the same test as a searched one,
      and refused by name if its footprint is not clear
- [x] The command refuses, naming what it found, if no placement satisfies the requested
      clearance
- [x] The command refuses to run against a save that already carries the merge stamp
- [x] The command refuses if a staging directory is left over from an interrupted run,
      saying so and leaving it in place
- [x] Unit tests drive the whole command against a synthetic two-World save built under a
      temporary directory, in the shape the existing importer tests use

## Comments

### Implementation summary

`./gradlew mergeWorlds --args="--target <run dir>"`, wired the way `migrate` and
`importNucleus` are — `gradle/merge-worlds.gradle.kts`, one `apply(from = …)` line in
`build.gradle.kts`, `WorldMergeMain` parsing arguments and `WorldMerge` doing the work.

- `src/main/kotlin/eu/mctraveler/importer/MergeGeometry.kt` — the vocabulary every later
  ticket imports.
- `src/main/kotlin/eu/mctraveler/importer/WorldMerge.kt` — `MergePlan`, `MergePlacement`,
  `PlacementSearch` and the command.
- `src/test/kotlin/eu/mctraveler/importer/WorldMergeTest.kt` (24 tests) and
  `MergedDeploymentFixture.kt`.

### Public surface later tickets build on

```kotlin
object MergeGeometry {
  const val OFFSET_ALIGNMENT = 4096          // blocks, on each horizontal axis
  const val NETHER_DIVISOR = 8
  const val REGION_FILE_BLOCKS = 512
  val RELOCATED_ROLES: List<DimensionRole>   // overworld and nether; never the End
  fun overworldBlocksPer(role: DimensionRole): Int
  fun clearanceIn(role: DimensionRole, netherBlocks: Int): Int
}

data class MergeOffset(val x: Int, val z: Int) {  // init enforces the 4096 alignment
  fun shiftX(role: DimensionRole): Int
  fun shiftZ(role: DimensionRole): Int
  fun mergedX(x: Int, role: DimensionRole): Int
  fun mergedZ(z: Int, role: DimensionRole): Int
  fun regionFileShiftX(role: DimensionRole): Int
  fun regionFileShiftZ(role: DimensionRole): Int
  fun describe(role: DimensionRole): String
}

data class RegionFilePos(val x: Int, val z: Int) {
  val fileName: String
  companion object { fun parse(fileName: String): RegionFilePos? }
}

data class RegionFileArea(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
  val fileCount: Int
  val minBlockX: Int; val minBlockZ: Int; val maxBlockX: Int; val maxBlockZ: Int
  operator fun contains(file: RegionFilePos): Boolean
  fun containsBlock(x: Int, z: Int): Boolean
  fun grownBy(files: Int): RegionFileArea
  fun movedBy(offset: MergeOffset, role: DimensionRole): RegionFileArea
  fun filesTo(file: RegionFilePos): Int
  fun describeBlocks(): String
}

class Footprint {                              // one dimension's chunk data on disk
  val files: List<RegionFilePos>               // sorted, deduped across region/entities/poi
  val isEmpty: Boolean
  val bounds: RegionFileArea?
  fun within(area: RegionFileArea, limit: Int): List<RegionFilePos>
  fun clearanceFrom(area: RegionFileArea): Int?
  companion object {
    val CHUNK_DIRECTORIES: List<String>
    fun storageFolder(levelDir: Path, dimension: ResourceKey<Level>): Path
    fun of(levelDir: Path, dimension: ResourceKey<Level>): Footprint
  }
}
```

`WorldMerge` also owns the constants the later phases need:
`STAGING_DIRECTORY = ".mctraveler-merge"`, `MARKER_FILE = "mctraveler/merge.json"`,
`DEFAULT_CLEARANCE = 512` (nether blocks), `DEFAULT_SEARCH_LIMIT = 64`,
`MAX_SEARCH_LIMIT = 256`.

### Judgement calls

1. **`MergeOffset` is the only thing that knows the ÷8 rule.** Nothing else may derive the
   nether's move. `MergeGeometry.overworldBlocksPer` states the ratio once and both the
   offset and the clearance divide by it, so the offset and the ring cannot disagree.
   Ticket 06 needs the same transform on doubles: add `mergedX(x: Double, role)` beside the
   `Int` one rather than adding `shiftX(role)` at the call site by hand.
2. **`DimensionRole.END` throws** everywhere in the geometry rather than returning zero.
   Secondary's End is discarded, and an accidental End offset would be a silent wrong
   answer rather than a loud one.
3. **The measurement is file existence, nothing more.** No chunk is parsed at plan time.
   That is what the spec defines a free slot against, and it cannot be fooled by a region
   file that has been emptied but not deleted — still a file the relocation must land beside.
4. **Region-file granularity throughout.** An offset moves whole region files by
   construction, so a footprint stays a rectangle of them. `RegionFileArea.containsBlock` is
   therefore slightly over-inclusive for ticket 03's "does this point into Secondary's old
   footprint" — conservative in the safe direction, since a leftover in an empty corner of a
   Secondary region file is still a leftover.
5. **Ties in the search break towards +X then +Z**, so a rehearsal and the real run choose
   the same slot and a printed offset reads as a move east rather than an arbitrary one.
6. **The origin is not a slot.** A zero offset would leave Secondary where it is and would
   pass the clearance test whenever Primary's data happens to be elsewhere, so it is refused
   by name on the supplied path and excluded from the search.
7. **`MAX_SEARCH_LIMIT = 256`** bounds the lattice at ~263k slots, so `--search-limit` cannot
   be typed into an out-of-memory sort.
8. **Achieved clearance is `(gap − 1) × 512` blocks**, where gap is the Chebyshev distance in
   region files from the landed footprint to Primary's nearest chunk data: adjacent files
   mean zero empty ground between them. It is `null` when Primary has generated nothing in
   that dimension, and the report says "unbounded" rather than inventing a number.

### Things the later tickets and the runbook must know

- **Every dimension lives under `<level>/dimensions/<namespace>/<path>/`** in 26.2 —
  `world/dimensions/minecraft/overworld/region`, not `world/region`, and the nether is
  `minecraft/the_nether`, not `DIM-1`. Verified against a save the gametest server actually
  wrote. `PortalImport.backendDimension`'s `DIM-1`/`DIM1` are the *pre-relayout backend*
  saves and are not this. Use `Footprint.storageFolder`.
- **The merge stamp is refused but not yet written** — ticket 01 writes nothing at all.
  Whichever ticket first writes it must use `WorldMerge.MARKER_FILE`.
- **`WorldMerge.run()` currently returns a `MergePlacement`.** Ticket 02 onwards will want a
  fuller report; embed the placement in it rather than replacing it, since the operator has
  to see the placement before anything is written either way.
- **`docs/merge.md` does not exist yet** (ticket 12 owns it), so the refusal for a wrong
  target directory points at `docs/migration.md`. Revisit that string with the runbook.
- **The default clearance of 512 nether blocks is a starting point, not a recommendation.**
  The spec is explicit that the distance is an operator judgement to be checked against the
  real map; the command says so after every plan.
