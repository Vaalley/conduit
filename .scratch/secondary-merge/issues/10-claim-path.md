# 10 — Returning players from the quarantine

**What to build:** The merge sweeps every player it can see. It cannot see the thousands of
saves still sitting in quarantine from the Portal cutover, waiting for their owners to come
back — and some of those owners will come back years from now, long after anyone is
watching for it.

So the claim path learns the merge. A save claimed out of Secondary's quarantine has the
offset applied on the way in, in exactly the way the sweep would have applied it, and is
stamped exactly as the sweep would have stamped it. A save from Primary's quarantine is
untouched, because its owner was never anywhere that moved. Both say which happened in the
log, so a wrong landing years from now is diagnosable rather than mysterious.

The offset is a permanent constant in the codebase after this, living beside Secondary's
footprint and shared with the merge itself so the two can never drift apart. This is the
whole live-code surface of the migration: everything else is offline and one-shot.

**Blocked by:** 01 — Merge geometry and the placement search; 06 — Moving the players.

**Status:** ready-for-agent

- [ ] A save claimed from Secondary's quarantine arrives at its relocated position, in the
      corresponding Primary dimension
- [ ] Everything the sweep transforms is transformed here too — respawn point, last death
      location, nether entry position, logged-out vehicle, and lodestone compasses in the
      inventory and ender chest
- [ ] The resulting record carries the same merge stamp the sweep would have written
- [ ] A save claimed from Primary's quarantine is not moved
- [ ] A claim that applied the merge transform is distinguishable in the log from one that
      did not
- [ ] The existing guarantee holds unchanged: a player who already has a save is never
      overwritten
- [ ] A claim that cannot be made still writes nothing and leaves the quarantine intact
- [ ] The offset and Secondary's footprint are stated once and used by both the merge and
      the claim path
- [ ] The existing claim unit tests and gametests are extended rather than duplicated
