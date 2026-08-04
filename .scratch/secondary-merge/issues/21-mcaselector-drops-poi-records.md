# 21 — MCA Selector drops point-of-interest records

**What to build:** A fix, in `gradle/mcaselector/2.8-mctraveler1.patch`, for records the import
leaves behind — and the evidence to say how many.

The fifth live merge measured it: of Secondary's 72,620 point-of-interest records, 25 that a
villager still claimed did not arrive in the staging area. The audit proves the claim is real
rather than inferred — it reads the record out of unrelocated Secondary at the un-shifted
position, so "the record existed" is a fact about the source and not a guess about the
destination.

Confirmed on a sample: source overworld `poi` chunk −59, −1528 holds a record at
−943, 69, −24435; its terrain chunk exists; both are DataVersion 4556; the whole region file
travelled — and no record arrived at −943, 69, 778381. So it is not the border, not the
selection, and not a legacy layout. Something in the import path drops individual records.

**Why it is a ticket and not a blocker.** The merge reports these and does not refuse (see
`ChunkAudit.reportVillagersThatLostTheirPoi`). The bed or bell block itself moves — the sampled
diff compares blocks against their sources and the coordinate audit passed clean — so what is
lost is the villager's *claim*, and a villager whose home does not resolve re-validates on load
and takes a free one nearby. That is vanilla behaviour nobody has to trigger, for 25 villagers
out of 13,032 memories. It is a defect, not damage.

**Blocked by:** None. The merge has shipped without it.

**Status:** ready-for-agent

- [ ] The mechanism is identified — which of MCA Selector's import paths drops a record, and
      under what condition. 25 out of 72,620 is an edge case, so the shape of the edge is the
      finding
- [ ] A test in `WorldMergeAuditTest` covers it. Today the "the record existed and did not
      arrive" branch is unreachable from the fixtures, because they can only fabricate a sound
      relocation — which is why this defect reached a live run before anything caught it
- [ ] The patch is extended additively and the pinned sha256 in `gradle/merge-worlds.gradle.kts`
      updated, as ticket 16 and ticket 17 did
- [ ] Reported upstream, with the reproduction above

## Comments

### What made it measurable

The check began as a refusal and stopped the fourth live merge at 1222 memories. That number was
useless: it could not tell a record the merge dropped from a bell somebody broke in 2019. Asking
the *source* the same question cut it to 25 and made the remainder legible — 11,810 found their
record, 1,196 had been pointing at nothing for years, 1 was clipped by the border.

Worth remembering when the next check refuses over a four-figure count on a decade-old world:
find out what the number was *before* the merge, and the finding usually shrinks to something you
can act on.
