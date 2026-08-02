# 12 — The runbook, the decisions and the language

**What to build:** Everything a person needs at 2am, and everything the codebase needs to
stop describing a world that no longer exists.

**The runbook.** A third document beside the two existing cutover runbooks, in their shape:
what the merge carries over, what to do before running it, how to run it, what it prints,
what every refusal means and how to clear it, what to verify afterwards and in what order,
and what it cannot do. Two things in it are not routine and must be impossible to miss: the
build that retires the Worlds subsystem must not reach production until the merge has
actually run, and the rollback window needs a written trigger list and one named
decision-maker agreed *before* the downtime begins, not argued about during it. The
existing migration runbook is updated wherever the merge changes what it says.

**The decisions.** The shared-player-state decision is superseded rather than amended — its
subject stops existing when the Worlds do. The Embassies decision is amended, not
superseded: the Embassies are exactly what they always were, but their definition was
written against a trio and has to be restated against dimensions.

**The language.** The glossary loses World, Travel, Per-World Bucket and Position Memory,
and Secondary stops being a World and becomes a place — a landmass with a known footprint,
no spawn of its own and no per-player state. Region and Embassies are both defined against
World today and need rewording. The glossary is what every future session reads first, so
leaving it describing two Worlds is leaving a trap.

**Blocked by:** 09 — Retiring the Worlds subsystem.

**Status:** ready-for-agent

- [ ] A merge runbook exists in the shape of the two existing cutover runbooks
- [ ] It states, unmissably, that the Worlds-retirement build must not be deployed before
      the merge has run
- [ ] It requires a written rollback trigger list and a named decision-maker before the
      downtime starts
- [ ] It documents every refusal the merge can produce and how to clear each one
- [ ] It documents the rehearsal: how to run against a copy of production and why the
      merge is safe to repeat that way
- [ ] It lists what cannot be fixed — command blocks, books, signs, shared coordinates,
      banked positions, Secondary's End — as things to communicate rather than bugs
- [ ] It names the duplicate-terrain consequence of one seed, so it is not discovered as a
      surprise
- [ ] A decision record supersedes the shared-player-state decision
- [ ] The Embassies decision record is amended to define them against dimensions
- [ ] The glossary retires World, Travel, Per-World Bucket and Position Memory, redefines
      Secondary as a place, and rewords every entry that was defined against a World
- [ ] The existing migration runbook is updated where the merge changes it
- [ ] It records that the relocation tool is a **patched** MCA Selector, why, and how to
      rebuild it from `gradle/mcaselector/2.8-mctraveler1.patch` — ticket 16 wrote the
      material into its own Comments because this runbook did not exist yet

## Comments

### From ticket 16 — what the runbook must say about the relocation tool

The merge does not run a released MCA Selector. It runs a local build of the 2.8 tag with
`gradle/mcaselector/2.8-mctraveler1.patch` applied, pinned by path and sha256 in
`gradle/merge-worlds.gradle.kts`. The runbook needs three things about it.

**Why it exists.** Released 2.8 has two defects that make it unusable here, both found by
this project and both fixed at source: `--mode select` loses a whole region file's worth of
chunks in about one run in twenty, silently and with exit 0; and it leaves a leash, an item
frame's and a painting's tile position and every villager's memories naming Secondary. The
audit refuses on the second, so a merge on stock 2.8 could not complete at all.

**Where it lives and how to rebuild it.** The build prints the whole procedure when the jar
is missing, so the shortest correct instruction in the runbook is to run `./gradlew
provideMcaSelector` and follow what it says. For reference, that is: clone the 2.8 tag to
`~/.mctraveler/src/mcaselector`, `git apply` the patch, `./gradlew shadowJar`, and copy
`build/libs/mcaselector-2.8-all.jar` to `~/.mctraveler/tools/mcaselector-2.8-mctraveler1.jar`.
It needs a JDK 21 and downloads JavaFX, which its build needs even though the merge only ever
runs it headless. The build is reproducible, so the rebuilt jar matches the pinned checksum
exactly — a mismatch means something is wrong, not that a rebuild drifted.

**What it still does not do.** The tool moves an entity's positions from a list of the entity
types that have them, and the list is not complete: a bee's `hive_pos` and `flower_pos` are
not on it. The audit refuses on those, naming the bee and the chunk, so it is a refusal rather
than a silent loss — but it *is* a refusal the operator cannot clear, and a rehearsal against
production is what will say whether Secondary has any bees in a relocated chunk. That is worth
finding out before the downtime window rather than during it.

### From ticket 13 — what the runbook must say about the border and the bleed

**The two options, and what they mean.**

```
--border <blocks>   Secondary's world border, in blocks from the origin on each
                    horizontal axis.                              [default: 50000]
--bleed <blocks>    how far past it terrain is still carried.       [default: 512]
```

`--border 50000` is the border Secondary actually ran, given by the operator. It is not a
number the tool can measure, so if it is wrong nothing will say so — it is the one value in
the merge that has to be checked against the server's own configuration before the night.
Chunks past the border are **left behind**: not moved, not deleted, still in Secondary's
folders after the merge, and gone for good when ticket 09 removes those folders.

`--bleed 512` is one region file. It exists so the ground does not end at a visible wall at
the border, and it is the smallest value that can do anything at all, because the clip
carries whole region files.

**Both are echoed in every plan**, as `Secondary's border` and `left outside the border`, so
a rehearsal and the real run can be compared line for line. Rehearse first and read those two
lines: `left outside the border : nothing` is the expected answer, and anything else is a
number to look at before the downtime window rather than during it.

**Reading `left outside the border`.** It names region files, not chunks, and states how far
past the border the furthest one reached. Tens of thousands of blocks out is a stray teleport
or an admin excursion and is exactly what the clip is for. A few hundred blocks past the
border, or a lot of files, is somebody's base — stop and find out whose before running the
merge, because the clip will leave it behind and the merge will not say so again.

**The border is not divided by eight in the nether.** It is a vanilla world border, which
applies at the same coordinates in every dimension, so the nether is clipped at ±50,000
*nether* blocks. That is deliberate and is what catches a stray in the nether at all.

**Three things are counted rather than refused over**, all in the report, all consequences of
the operator's own decision that a merge should not be gated on them:

- `Regions outside border` and `destinations outside it` — swept onto Primary like any other,
  but the chunks under them stayed in Secondary, so they will protect terrain regenerated
  from Primary's seed.
- `players outside border` — moved like everyone else, and they will log in somewhere that
  looks nothing like where they logged out.
- `beds outside the border` — their owners will respawn at the world spawn instead.

If any of those is non-zero, the people concerned need telling. They are named in no other
report, so the count is the only warning there will be.

**Two refusals the border can produce:**

- *"Secondary's border of ±N blocks … carries none of Secondary's chunk data"* — `--border`
  or `--bleed` is wrong, or the target is not the save you think it is. Nothing was written.
- *"a merge offset must be a multiple of 4096 blocks"* and *"Secondary's border must be at
  least 512 blocks from the origin"* — both are argument mistakes, refused before the merge
  reads anything.
