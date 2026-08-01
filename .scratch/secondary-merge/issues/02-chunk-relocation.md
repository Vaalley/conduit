# 02 — Relocating Secondary's chunk data

**What to build:** The merge actually moves Secondary. Its overworld and nether chunk data
— terrain, entities and points of interest alike — is relocated into Primary's own
dimensions at the planned offset, into the staging area rather than over the live save.
Secondary's End is discarded. Partially generated chunks at Secondary's frontier are
dropped rather than moved, so that frontier regenerates cleanly from one seed instead of
arriving half from another.

The relocation itself is performed by MCA Selector, which has done this job for a decade
and tracks the current Minecraft version. It is resolved by the build as a pinned,
checksummed artifact and run as a subprocess: it is a tool we run, not a library we link,
so its dependencies never reach the mod's compile classpath. Because the build resolves it,
the tests drive the real thing rather than a stand-in.

**Blocked by:** 01 — Merge geometry and the placement search.

**Status:** done

- [x] Secondary's overworld chunk data lands where Primary's overworld will look for it,
      offset by the planned amount
- [x] Secondary's nether chunk data lands where Primary's nether will look for it, offset
      by one eighth
- [x] Terrain, entity and point-of-interest data are all relocated, not just terrain
- [x] Secondary's End chunk data is discarded, along with Secondary's level-wide saved data
- [x] Chunks that are not fully generated are dropped rather than relocated
- [x] Nothing is written outside the staging area; the live save is untouched until the
      whole merge succeeds
- [x] The relocation tool is resolved by the build at a pinned version and verified against
      a checksum, so an operator never has to fetch anything by hand
- [x] A failure or non-zero exit from the relocation fails the merge with the tool's own
      output attached, and nothing is moved into place
- [x] The report states how many chunks were relocated, how many were dropped as
      incomplete, and how many bytes were transferred
- [x] A test builds a real region file containing more than one chunk, relocates it for
      real, and reads it back to confirm both chunks arrived at the expected coordinates

## Comments

### How MCA Selector is resolved

**2.8**, whose release notes state "Updated mappings for Minecraft 26.2". The **GitHub
release jar**, reached as a genuine Gradle artifact through an Ivy repository laid over the
releases URL and scoped with `exclusiveContent` so nothing else can be served from it:

```
net.querz:mcaselector:2.8@jar   ->   https://github.com/Querz/mcaselector/releases/download/2.8/mcaselector-2.8.jar
sha256 64505f39edf9c9b5d47e666981f81e3c3a889d4f122b3065af7e269f48e53423
```

`provideMcaSelector` verifies that checksum and copies the jar to
`build/tools/mcaselector-2.8.jar`; both `mergeWorlds` and `test` depend on it and receive
the path as `-Dmctraveler.mcaSelectorJar`. It resolves into a configuration of its own
(`mcaSelector`, non-transitive) that no source set extends, so nothing of its tree reaches
the mod's compile classpath.

**JitPack was tried and rejected.** `com.github.Querz:mcaselector:2.8` does resolve (JitPack
reports the build "ok"), but its jar is the 2.2 MB *library*, not the 18 MB runnable one,
and its POM drags fifteen runtime dependencies behind it including `org.openjfx:javafx-*`
with platform-specific natives — resolved for a process we only ever `exec`. The release jar
is self-contained and is what upstream ships for running headless.

### The real CLI surface

Two passes per dimension, both headless, no display and no `$HOME` side effects:

```
java -jar mcaselector.jar --mode select --world <secondary dim> --query "Status = full" --output <staging>/mcaselector/finished-<role>.csv
java -jar mcaselector.jar --mode import --world <staged primary dim> --source-world <secondary dim> \
     --source-selection <csv> --x-offset <chunks> --z-offset <chunks>
```

- **The offset is in *chunks*, not blocks** (`ChunkImporter.getTargetRegions` adds it to a
  chunk coordinate). The merge divides its block offset by 16. `--y-offset` is never passed.
- `--world`/`--source-world` auto-detect `region`, `poi` and `entities` beneath them, which
  is exactly the `dimensions/<ns>/<path>/` layout ticket 01 documented — no `--region`,
  `--poi` or `--entities` overrides are needed.
- `--overwrite` is deliberately **not** passed, so the tool can never destroy terrain.
- Version dispatch is `floorEntry(dataVersion)`, so 26.2 chunks (DataVersion 4903) use the
  newest implementation rather than being refused.

### The trap that cost the most, and that later tickets must not undo

**`--mode import` returns exit 0 having done nothing when the target holds no region files
at all.** `ChunkImporter.importChunks` calls `listRegions()` on the target first and bails
with "no files" if it is empty. Two consequences, both now load-bearing:

1. `ChunkRelocation.prepareDestination` pre-creates an empty (8 KB header) region file at
   every destination — knowable in advance only because the 4096 alignment moves whole
   region files. This is what makes the relocation happen at all, not a convenience.
2. **The exit status is not sufficient evidence.** Every dimension's chunk count is read back
   off disk and compared with the selection, and a mismatch fails the merge.

### Public surface later tickets build on

```kotlin
class McaSelector(jar: Path, java: Path = currentJava()) {
  fun select(from: Path, into: Path): String
  fun relocate(from: Path, into: Path, selection: Path, chunksX: Int, chunksZ: Int): String
  companion object { const val FULL_STATUS; const val JAR_PROPERTY; fun resolved(): McaSelector }
}

class ChunkRelocation(levelDir, stagedLevelDir, workDir, offset, tool) { fun run(): RelocationReport }
data class DimensionRelocation(role, relocated, dropped, files, bytes)
data class Discarded(what, files, bytes)
data class RelocationReport(dimensions, discarded) { relocated; dropped; bytes; dimension(role); lines() }

data class MergeReport(placement: MergePlacement, relocation: RelocationReport? = null) {
  val offset: MergeOffset
  fun lines(): List<String>
}

class MergeStaging(plan, staging, levelDir, tool = McaSelector.resolved()) {
  fun write(placement: MergePlacement): MergeReport
}
```

`WorldMerge.run()` now returns `MergeReport`; the placement is embedded as ticket 01 asked.
`reportLine` in `WorldMerge.kt` is now `internal`, so every ticket's report section aligns
with the same 24-column key width instead of inventing its own.

### Judgement calls

1. **`MergeStaging` owns the whole writing half, not `WorldMerge`.** `WorldMerge.run()` gained
   one branch and one call. Tickets 05 and 06 stage their own output and commit it *there*,
   so the merge stays one all-or-nothing move and `WorldMerge.kt` stays uncontended.
2. **`--plan-only` was added, defaulting to off.** Ticket 01's command wrote nothing by
   definition; now that it writes, User Story 5 ("see the placement before anything is
   written, so I can reject it") needs a way to still ask. `MergedDeploymentFixture.plan()`
   defaults to `planOnly = true`, so every ticket-01 test body is unchanged.
3. **Staged into empty destination folders, not into a copy of Primary.** Primary's own region
   files are never opened, so they cannot be corrupted; the commit is a move of files that did
   not previously exist. `Primary's own chunk data is never opened, let alone changed` proves
   it by leaving Primary's fixture files as unparseable nonsense and passing anyway.
4. **Incomplete chunks are dropped by never selecting them**, rather than by relocating and
   then deleting, so a frontier chunk is never written anywhere at all.
5. **Chunk counts are read from region-file headers**, not by parsing NBT. Whether the data
   inside is readable is ticket 03's question, not this one's.
6. **Destination files that nothing landed in are deleted before commit**, or a frontier
   source file of nothing but proto-chunks would drop an empty region file into Primary —
   which `Footprint` (which counts files, not chunks) would forever after read as chunk data.
7. **A failed run clears its staging directory** (as `EmbassyImport` does) so a fixed cause
   can be retried; an *interrupted* one leaves it, and ticket 01's refusal covers that.
8. **Commit checks every destination before moving anything**, so a collision leaves the save
   untouched rather than half-merged.

### Things the later tickets must know

- **The merge stamp is now written** — `mctraveler/merge.json`, holding `mergedAt`, `offsetX`
  and `offsetZ`. Ticket 06's per-player stamp is separate and still unwritten.
- **`WorldMerge.run()` returns `MergeReport`, not `MergePlacement`.** Add report sections by
  extending `MergeReport.lines()`, and stage new output inside `MergeStaging`.
- **Secondary is only ever copied.** Its chunk data is byte-identical after a merge; ticket 09
  is what retires those folders.
- **`SyntheticChunks`** (test source) writes and reads real region files through Minecraft's
  own `RegionFile`. Tickets 03 and 04 need real chunk NBT and should build on it rather than
  inventing a second chunk writer; `MergedDeploymentFixture.withRealSecondaryChunks()` is the
  save-level entry point.
- **Secondary's End and its `data/` folders are reported as discarded**, not deleted. Ticket 07
  gates on what is anchored there; ticket 09 removes the folders.
