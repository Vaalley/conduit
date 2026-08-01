# 01 — Merge geometry and the placement search

**What to build:** An operator can ask where Secondary would go, and get a real answer
without anything being written. A new offline command plans the merge: it measures how far
Primary's overworld and nether have been generated, measures Secondary's footprint, finds
the nearest place Secondary can go that Primary has never touched, and prints the offset it
chose along with both footprints and the clearance it actually achieved. An operator who
already knows the offset can pass it instead, and have it checked rather than trusted.

This ticket carries the vocabulary the rest of the merge is written in — the offset, the
rule that the nether gets one eighth of it, Secondary's footprint, and the transform from a
Secondary coordinate to its merged one — so it lands first and everything else builds on
it. It also stands up the command's skeleton: the report it prints, and the staging
discipline that makes every later refusal write nothing.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Planning the merge prints the chosen offset, Secondary's overworld and nether
      footprints, and the clearance achieved in each, and writes nothing at all
- [ ] The offset is always a multiple of 4096 on both horizontal axes, so that every source
      region file maps onto exactly one destination region file in both dimensions
- [ ] The nether offset is exactly one eighth of the overworld offset, so existing portal
      pairs still link; no vertical offset is ever applied
- [ ] Clearance is specified in nether blocks and applied to the overworld multiplied by
      eight, because the nether is the binding constraint
- [ ] A candidate placement is rejected unless the overworld footprint plus its ring and
      the nether footprint plus its ring are both entirely free of region, entity and
      point-of-interest data
- [ ] Candidates are considered in ascending distance from the origin, so the nearest
      viable placement wins
- [ ] An offset supplied by the operator is validated by the same test as a searched one,
      and refused by name if its footprint is not clear
- [ ] The command refuses, naming what it found, if no placement satisfies the requested
      clearance
- [ ] The command refuses to run against a save that already carries the merge stamp
- [ ] The command refuses if a staging directory is left over from an interrupted run,
      saying so and leaving it in place
- [ ] Unit tests drive the whole command against a synthetic two-World save built under a
      temporary directory, in the shape the existing importer tests use
