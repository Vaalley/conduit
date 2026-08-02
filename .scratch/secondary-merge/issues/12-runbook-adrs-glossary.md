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

**Status:** done

- [x] A merge runbook exists in the shape of the two existing cutover runbooks
- [x] It states, unmissably, that the Worlds-retirement build must not be deployed before
      the merge has run
- [x] It requires a written rollback trigger list and a named decision-maker before the
      downtime starts
- [x] It documents every refusal the merge can produce and how to clear each one
- [x] It documents the rehearsal: how to run against a copy of production and why the
      merge is safe to repeat that way
- [x] It lists what cannot be fixed — command blocks, books, signs, shared coordinates,
      banked positions, Secondary's End — as things to communicate rather than bugs
- [x] It names the duplicate-terrain consequence of one seed, so it is not discovered as a
      surprise
- [x] A decision record supersedes the shared-player-state decision
- [x] The Embassies decision record is amended to define them against dimensions
- [x] The glossary retires World, Travel, Per-World Bucket and Position Memory, redefines
      Secondary as a place, and rewords every entry that was defined against a World
- [x] The existing migration runbook is updated where the merge changes it
- [x] It records that the relocation tool is a **patched** MCA Selector, why, and how to
      rebuild it from `gradle/mcaselector/2.8-mctraveler1.patch` — ticket 16 wrote the
      material into its own Comments because this runbook did not exist yet
- [x] The rehearsal step says to check the searched offset against Secondary's own footprint
      as well as against Primary's — ticket 18 wrote the material into the Comments below,
      because this runbook did not exist yet either

## Comments

### What was written

- **`docs/merge.md`** — the runbook, a sibling of `docs/migration.md` and `docs/nucleus-import.md`.
  Sections: what the merge does and carries across; a `Read these three things first` block; before
  you run it, including the patched relocation tool and the rehearsal; run it, with every option
  and the `--border`/`--bleed` and `--accept-end-loss` semantics; what it prints, with a whole
  annotated report and a walkthrough of the lines worth stopping on; every refusal by phase, with
  what it means and how to clear it; after it runs, with the deploy gate, the merge marker and the
  signpost; rollback; known limitations.
- **`docs/adr/0004-one-world-worlds-retired-from-the-server.md`** — supersedes ADR 0001. Says
  *retired from the server*, not deleted, and names the two things that survive on purpose: the
  Per-World Bucket as legacy record data plus `importer.PerWorldBuckets`, and the Region layer's
  legacy world strings.
- **ADR 0001** — a superseded banner, kept as the record of why the port divided state as it did.
- **ADR 0003** — amended, not superseded, with a banner saying so. The opening no longer defines a
  World or names `Worlds.worldOf`; the Embassies are now defined against the map. Title's "not a
  third World" became "not somewhere players live"; the filename is unchanged, since "out-of-trio"
  is still true of the vanilla trio.
- **`CONTEXT.md`** — World, Travel, Per-World Bucket and Position Memory are gone. **The map**,
  **Secondary** (a landmass, not a World) and **The merge** replace them. **Region** and
  **Embassies** are restated against dimensions, and Region carries the sentence about the legacy
  world vocabulary being deliberate.
- **`docs/migration.md`** — "first of two imports" → "first of three cutover operations", a note
  that it describes the two-World server and which section is the exception, a pointer on the
  verification list, the duplicate-terrain consequence of one seed, and ADR 0001 → 0004.

### Two source files were touched, both comment-only

This ticket was not expected to change code, so both are called out rather than slipped in.
Neither can affect a test: they are a KDoc paragraph and a one-line KDoc.

- **`importer/MergeEnd.kt`** — its `relocatedSpawn` KDoc linked `[eu.mctraveler.worlds.Worlds]`, a
  class ticket 09 deleted, so the link was dangling. Rewritten in the past tense (it is a statement
  about the save the merge reads, not about the running server) with a pointer to ADR 0004.
- **`embassy/EmbassiesFeature.kt`** — `DIMENSION`'s KDoc said "Outside every World's trio, so
  `Worlds.worldOf` is null for it", naming a deleted method. Now "Outside the vanilla trio, and not
  somewhere players live (ADR 0003)".

A sweep for other dangling references to the retired symbols found none. Every remaining mention of
`RegionWorlds` is the live legacy-vocabulary class, and the prose mentions in `CrystalMenu` and
`RegionCommands` are past-tense history and correct.

### The naming loose end: not renamed, and why

Ticket 09 left `eu.mctraveler.worlds` and `WorldsFeature` as they were and flagged them for this
ticket. **Declined, deliberately.**

Ticket 09's reason was a collision with ticket 18, which has since landed. But the same reason now
holds for **ticket 11**, which is in flight writing the merge gametest under `src/gametest/` —
`SwitchSignpostGameTest` already imports `eu.mctraveler.worlds`, and a merge gametest is very
likely to import `BankedPositions` or `DimensionRole` too. A package rename would touch ~25
importer imports plus the gametest source set, which is exactly the file another agent has open.

Beyond the timing, the package's contents do not want the same name. It holds `/switch` and
`BankedPositions` (the merge's own signpost, which *is* about the Worlds having merged), plus
`Landing`, `Waypoint` and `DimensionRole`, which belong to the crystal, the Embassies and the
importer respectively. There is no single name that fits all five, and the glossary does not need
one: `CONTEXT.md` is where the vocabulary lives, and it now says plainly that World is retired.
`WorldsFeature`'s KDoc already explains what is left and why the name stayed.

If someone does rename later, the honest split is to move `DimensionRole` to
`eu.mctraveler.importer` (its KDoc already says it is the importer's vocabulary), `Landing` and
`Waypoint` to wherever the crystal's destinations live, and leave `/switch` and `BankedPositions`
in a package named for the signpost. That is a refactor with its own ticket, not a rename.

### Contradictions the assembly surfaced

Six, in rough order of how much damage they could do at 2am.

1. **Ticket 10's runbook step no longer exists, and following it would be harmful.** Ticket 10
   wrote: *"The runbook must say this: the placement is found with `--plan-only`, the chosen offset
   is written into `MergeGeometry.APPLIED_OFFSET`, and only then is the real run performed."*
   Ticket 14 **deleted `APPLIED_OFFSET`** along with `WorldMerge.run()`'s fallback to it, and its
   own Comments say so: *"The step ticket 10 added to the runbook is gone."* The runbook documents
   ticket 14's sequence — plan, check, run with `--offset` — and says outright that there is
   nothing to fill in between planning and running, because the offset is now a fact about the save
   rather than a line of source.
2. **Ticket 04's advice for a missing chunk is void, and inverted.** Ticket 04: *"a merge suite
   that goes red with a chunk missing from an expected set is that defect and should be re-run, not
   retuned."* That defect was the `--mode select` race, which **ticket 16 fixed at source**. After
   16, a chunk-count mismatch means real data loss. The runbook says the opposite of ticket 04:
   do not re-run and hope; confirm you are on the patched jar first.
3. **Ticket 15's runbook acceptance criterion states the opposite of what shipped.** It asked the
   runbook to record *"that the selection is ours, and why — an operator reading MCA Selector's own
   documentation would otherwise expect `--mode select` to be involved."* Ticket 15 is **wontfix**,
   superseded by 16: the selection is emphatically **not** ours and `--mode select` **is** what
   produces it. Not copied.
4. **Ticket 16's rehearsal step was superseded within ticket 12's own Comments.** 16: *"A rehearsal
   must find out whether Secondary has bees in relocated chunks."* 17 fixed the bee and replaced
   the step with the completion count, which answers the same question about every field at once.
   The runbook carries only 17's version.
5. **Ticket 02's account of the relocation tool describes a jar that no longer runs.** It documents
   a pinned upstream `net.querz:mcaselector:2.8` with sha256 `64505f39…`; what ships is a locally
   patched build at `f7d088d3…`, and ticket 16 removed the upstream resolution entirely rather than
   keeping it as a fallback — *"a fallback here would be a way to silently run the defective tool."*
   Relatedly, ticket 02 quotes 2.8's release notes claiming *"Updated mappings for Minecraft 26.2"*;
   ticket 03 then demonstrated that claim false. The runbook does not repeat it.
6. **Ticket 05 and ticket 07 both describe what happens to Secondary's End, differently.** 05 says
   `MergeRegionsReport.endAnchored` *"is the list 07 turns into its refusal"*; 07 says the gate
   re-reads the region tree instead, because 05's list is prose. The operator-visible consequence is
   that **the same Region can appear twice in one report** — once under `still in Secondary's End`
   and once under `Region deleted` — and that is phase order, not a double count. The runbook says
   so. Also reconciled: ticket 06 says *"Secondary's End is left exactly as it is"*, while ticket 07
   scrubs every save of End references **whether or not `--accept-end-loss` was given**. The flag
   does not gate the End's destruction, only the loss of the things anchored in it. The runbook has
   a subsection on exactly this, because reading either ticket alone gives the wrong answer.

Two smaller ones, recorded but not runbook-facing: ticket 06's *"There is still no `--plan` flag"*
is stale (it is `--plan-only`, added by ticket 02); and tickets 03 and 04 each claim their phase
runs *"immediately after the relocation"*. The code settles it — relocation, sampled diff,
completion, audit — and the runbook's report walkthrough follows the code.

### Two things left alone on purpose

- **The wrong-target refusal still points at `docs/migration.md`.** Ticket 01 asked for that string
  to be revisited once the runbook existed. Revisited and kept: the refusal is about *which
  directory* — the one the Portal migration produced — and `docs/migration.md` is the document that
  defines it. Pointing at `docs/merge.md` would send the operator to the document they are already
  holding.
- **`gradle/merge-worlds.gradle.kts`'s failure message says the released tool "leaves four kinds of
  26.2 coordinate behind".** That was true at ticket 16 and ticket 17 widened it a long way past
  four; the comment block above it says "in two ways" and then lists three. Both are stale by a
  count rather than wrong in substance, and the file is ticket 16/17's rather than this one's, so it
  is recorded here rather than edited.

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

*Superseded by ticket 17, below: the bee is fixed, and a rehearsal no longer has to guess
which field will be next.*

### From ticket 17 — the completion count, and what a non-zero one means

Ticket 17 widened the patch a long way past the bee (its Comments carry the enumeration) and
added a phase the runbook has to explain, because it prints a number an operator will
otherwise not know how to read.

**There is a new report section, between the sampled diff and the audit:**

```
coordinates completed    : none — the relocation tool moved everything it should have
```

**Zero is the expected reading, and the boring one.** It says MCA Selector moved every
coordinate Minecraft 26.2 writes, and there was nothing left for the merge to finish.

**A non-zero count is a finding.** It looks like this:

```
coordinates completed    : 2 in 1 chunk, which the relocation tool did not move. See below.
  the tool left behind   : minecraft:bee_nest.flower_pos — 1 coordinate
  the tool left behind   : minecraft:bee.hive_pos — 1 coordinate
  what this means        : MCA Selector has fallen behind what Minecraft writes. The merge
                           finished these itself and the audit below still checked all of
                           them, so the map is sound — but the patch wants widening before
                           the next run.
```

What it means: the pinned tool does not know about a coordinate the game writes, and the merge
applied the offset itself rather than stopping. **It is not a reason to abort the run.** The
audit runs afterwards, unchanged, over the completed chunks, and would still have refused if
anything were left — so a merge that prints this and then completes is a merge whose map is
sound.

What to do about it, in order:

1. **Nothing, during the window.** The run is good. Do not stop it and do not try to widen the
   patch at 2am.
2. **Afterwards, record the field names.** They are the whole value of the section: each names
   a field to add to `gradle/mcaselector/2.8-mctraveler1.patch`. Ticket 17's Comments explain
   the shape the additions take — keyed by NBT name rather than by entity id.
3. **Expect it to be non-zero on the first real run.** Ticket 17 deliberately left the tool's
   *block entity* switch unfixed, because the completion pass had to be proved against a
   defect that is real rather than mocked. A bee nest in a relocated chunk is what will show
   up here, and it is expected rather than alarming.
4. **Compare it between the rehearsal and the real run.** They should match. A count that grew
   means the save changed underneath, and is worth understanding before reopening.

**This replaces the "check whether Secondary has bees" rehearsal step above.** That step
existed because one known field would have refused the merge; now no known field refuses it,
and the completion count answers the same question about *every* field at once — including
the ones nobody has found yet. The rehearsal step is simply: run it, and read this number.

**The one refusal this does not clear.** An end gateway's `exit_portal` names a place in the
End, and Secondary's End is discarded, so the merge cannot know where to point it and
deliberately does not try. If the audit refuses over one, that is a genuine decision for a
person: the gateway is in relocated terrain and its destination no longer exists.
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

### From ticket 18 — the rehearsal step for Secondary's own ground

**The rehearsal must check the searched offset against Secondary's own footprint as well as
against Primary's.** The plan prints both, per dimension, and they are the two lines to read
together:

```
  Secondary's footprint  : x 0…50175  z 0…511  (98 region files)
  lands at               : x 8192…58367  z -4096…-3585
```

`lands at` must not overlap `Secondary's footprint` on **both** axes at once, in the overworld
and in the nether. Above it does not: the X ranges overlap heavily, and the Z ranges do not
meet at all, so no landed coordinate can be mistaken for one that stayed. Where they overlap, the audit cannot tell a coordinate that moved from one
that never left — it decides that question by asking whether a coordinate still points into
Secondary's old footprint, and inside the overlap the answer is the same either way.

The merge enforces this itself now: a slot is only a candidate when it clears Secondary's own
ground as well as Primary's chunk data, and an `--offset` that does not is refused by name.
The rehearsal step exists to **confirm** that rather than to trust it — this is the one check
that protects the audit itself, so a merge whose audit is unreadable would otherwise report
success.

**Two refusals it can produce**, and neither is cleared by asking for less clearance:

- *"the offset x +8192, z +0 would set Secondary's nether back down on ground it already
  covers …"* — the offset is too small. Throw the landmass further; the nether is usually the
  dimension that fails first, because it moves one region file per lattice step where the
  overworld moves eight.
- *"no 4096-aligned slot within N blocks of the origin can take Secondary — M slots tried, A
  of them ruled out by the ground Secondary is being moved off and B by Primary's chunk data
  …"* — read the two numbers. A large `A` means `--search-limit` is too small for how wide
  Secondary is; a large `B` means the clearance is too large for how much of Primary's map is
  generated.

**Why this is unlikely to bite in production, and why it is checked anyway.** Primary has
years of play behind it, so its chunk data almost certainly covers the ground Secondary sits
on, and the clearance measured in nether blocks pushes the offset past 90,000 blocks. But the
merge is one-shot and irreversible, so "almost certainly" is not the standard, and the search
is against a *small* Primary in every rehearsal fixture — which is exactly the shape that
fails.
