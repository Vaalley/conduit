# 11 — Proving it on a real server

**What to build:** Everything up to here is proven against files. This proves it against a
running server: the merged save is booted for real, and the things a player would actually
do are done.

A relocated chunk loads and its block entities are readable — the chest at the new
coordinates still has what was in it. A Region protects at its new coordinates, so a player
who is not a member still cannot build there. A player dies and wakes up on their own bed,
which exercises the respawn point and the bed together across the two passes that moved
them separately. And `/switch` prints the signpost, with the other base at the coordinates
the merge recorded.

This is the last line of evidence before the operation is run for real, and the one that
covers the gap between "the files are right" and "the game behaves".

**Blocked by:** 09 — Retiring the Worlds subsystem.

**Status:** ready-for-agent

- [ ] A server boots on a merged save with only the dimensions that should now exist
- [ ] A relocated chunk loads and a container in it still holds its contents
- [ ] A relocated Region refuses a non-member at its new coordinates
- [ ] A player whose respawn point was transformed dies and respawns on their own bed
- [ ] A relocated nether portal still leads to its own twin
- [ ] `/switch` prints the signpost, including the other base at its merged coordinates
- [ ] The fixture is built by the merge itself rather than by hand, so the test exercises
      the real output
- [ ] The test runs headlessly in the standard build alongside the existing gametests
