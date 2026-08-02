# 14 — Hardening the claim path

**What to build:** Two corrections to the one part of the merge that has to keep working for
years — the path that hands a returning player their quarantined Portal-era save. Both were
found by ticket 10 while building it; neither could be fixed there without editing files
another ticket had open.

**The offset must not be a constant somebody remembers to edit.** Ticket 10 put the applied
offset in source, null until an operator fills it in between planning the merge and running
it. If they forget, the claim path silently moves nobody, for the life of the quarantine,
with no error and no log line — and the people it fails are exactly the ones nobody is
watching for. The merge already writes the offset it used into the save's own merge marker,
so the claim path should read it from there: one value, written by the operation that
actually happened, with no manual step to skip and nothing to keep in sync. A save that
carries no marker has not been merged, and the claim path stays inert exactly as it does
today.

**A returning player quarantined on both sides gets the wrong save made live.** The player
sweep rewrites every record's last-World field to Primary — correct, since there is only one
World now — but the claim path uses that same field to decide which of a player's two
quarantined saves becomes their live one and which seeds the other World. After the merge it
always answers Primary, whichever World they were actually last in. Their *coordinates* are
right either way, which is what makes this so quiet; what may be wrong is which save's
inventory, XP and advancements they get back. The fix is for the sweep to record the
pre-merge value where the claim path can find it, and for the claim path to prefer it.

**Blocked by:** None — tickets 06 and 10 are complete. Land after wave 3 is reconciled,
since it edits files that were open during it.

**Status:** ready-for-agent

- [ ] The claim path takes the offset from the merge marker the merge itself wrote, not from
      a value anyone has to edit by hand
- [ ] There is no step in the runbook between planning and running that, if skipped, leaves
      the claim path unable to move anyone
- [ ] A save in a run directory carrying no merge marker is not moved, exactly as today
- [ ] A marker that cannot be read is refused loudly rather than treated as "no merge" — a
      returning player silently not moved is the failure this ticket exists to remove
- [ ] The sweep records each player's pre-merge last World somewhere the claim path can read
- [ ] A player quarantined on both sides gets the save from the World they were actually
      last in made live, with the other seeding what the merge kept of the other World
- [ ] A player quarantined on one side only is unaffected
- [ ] A player who was never swept — no record at cutover — still claims exactly as today
- [ ] Both corrections are covered by tests that would fail if the old behaviour returned,
      extending the existing claim suites rather than replacing them
- [ ] `docs/migration.md`'s quarantine section reflects what the claim path now does
