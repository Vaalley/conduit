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
