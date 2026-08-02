# 19 — Covering the chunk layout the tests never produced

**What to build:** A test that fails if the merge stops understanding pre-1.18 chunks.

The rehearsal against the live Secondary refused on chunk −3136, −3136: `SampledDiff` read
its status as empty and left it at the frontier, while MCA Selector had relocated it as
finished. A probe settled it — DataVersion **2230**, Minecraft 1.15.2, everything under
`Level`, `Status` reading `full` exactly where the diff was not looking.

The refusal was the harmless symptom. The dangerous one runs the other way: an absent section
list compares equal to an absent section list, so **every pre-1.18 chunk would have passed the
diff without being compared at all**, while the report said it had been. That is precisely the
silent pass the phase exists to prevent, and it would have shipped as a green run.

The reason no test caught it is that every fixture in the suite is built by a **26.2 server**,
so every chunk in every test is modern. The live Secondary is a mixture — vanilla upgrades a
chunk only when it loads one, so ground nobody has walked since before the Portal cutover is
still in the shape the version that generated it wrote. That mixture is the single most
recurrent theme of this whole effort: it is why ticket 16's patch had to be additive, and it
is what this ticket makes the test tier able to see.

The fix is already in (`SampledDiff.fieldsOf`), proven only by the rehearsal on real data.
This ticket adds the regression guard, so a later change cannot quietly undo it.

**Blocked by:** None — the fix is landed; this covers it.

**Status:** ready-for-agent

- [ ] The synthetic chunk builder can write a pre-1.18 chunk — everything under `Level`, with
      `Sections`, `TileEntities`, `Entities`, `Status` and `xPos`/`zPos` where that format puts
      them, and a DataVersion to match
- [ ] A test proves a legacy chunk that arrives intact is **compared**, not skipped — it must
      fail if the comparison is made vacuous again, which means asserting on a difference the
      comparison would have to notice
- [ ] A test proves a legacy chunk whose blocks were altered in transit is caught
- [ ] A test proves a legacy chunk with entities in `Level.Entities` has them compared, since
      entity storage only moved out of the terrain chunk in 1.17
- [ ] A test proves a legacy chunk that vanilla never finished is still recognised as
      unfinished, so the frontier rule holds in both layouts
- [ ] The audit and the completion pass are checked against the same fixture, since both read
      chunk NBT and neither has ever been shown a legacy chunk either
- [ ] The runbook notes that a save carrying a mixture of DataVersions is the normal case, not
      an edge one
