# 13 — Clipping the import to Secondary's world border

**What to build:** Only the part of Secondary anybody was ever meant to reach comes across.

Secondary's world border runs from −50,000 to +50,000 on both horizontal axes. Chunks exist
outside it anyway: an admin teleport, a stray command, anything that put someone past the
border for long enough to generate terrain. None of that is worth carrying, and carrying it
is actively harmful — the placement search sizes its slot from Secondary's whole footprint,
so a single chunk generated a million blocks out would demand an enormous free area in
Primary and drop a speck of junk terrain into the middle of it.

So the import is clipped to the border plus a bleed. The bleed is what stops the terrain
being sliced off at a visible edge: chunks a little way past the border come too, so anyone
standing at the border still sees continuous ground beyond it rather than a wall.

The clip only ever *shrinks* what is taken — it is the intersection of "chunks that exist"
with "inside the border plus bleed", so a Secondary that never generated anything near its
border is unaffected.

**Deliberately not gated:** a Region, player or Embassy destination anchored outside the
border is swept exactly like any other. The operator's call, and the risk is small — but the
consequence is worth knowing, so the report counts them: such a player's coordinates move
while their chunks do not, so they arrive in terrain that regenerates from Primary's seed.

**Blocked by:** None — tickets 01 and 02 are complete. Should land after wave 3 is
reconciled, because it touches the same command surface.

**Status:** ready-for-agent

- [ ] Only chunks inside Secondary's world border plus the bleed are measured into the
      footprint the placement search sizes its slot from
- [ ] Only those chunks are relocated; the rest are left behind, not moved and not deleted
- [ ] The border half-extent and the bleed are both options, defaulting to 50,000 and 512
      blocks, and both are echoed in the plan output so a rehearsal and the real run can be
      compared
- [ ] The border applies at the same coordinates in both relocated dimensions, as a vanilla
      world border does — it is not scaled by the nether's ÷8
- [ ] The clip works in whole region files, so it composes with the 4096 alignment and no
      chunk is ever split from the file it lives in
- [ ] The report states how many chunks were dropped as outside the border, and how far out
      the furthest one was, so the operator can tell a stray teleport from a real base
- [ ] The report counts Regions and players anchored outside the border, without refusing
- [ ] A test proves a chunk far outside the border is excluded from the footprint, so the
      placement search is not dragged out to meet it
- [ ] A test proves that same chunk is not relocated, and that the ones inside still are
- [ ] A test proves a chunk in the bleed — just outside the border — does come across
- [ ] A Secondary with nothing near its border relocates exactly as it does today, so the
      existing tests are unchanged
