# 05 — Moving the Regions

**What to build:** A player who owns a Region in Secondary still owns it, protecting the
same build, after the merge. Every Region recorded against one of Secondary's worlds is
rewritten to name Primary's equivalent and to sit at the relocated coordinates — the whole
nest, so a sub-region moves with its parent and stays inside it.

The Embassies come along too. An Embassy's anchor remembers where it sends a visitor, and
that memory names a world and a position in exactly the same legacy form a Region does; a
destination naming one of Secondary's worlds is rewritten with everything else, so the plot
keeps working.

Regions are the one place the merge can collide with something that already exists, so it
checks rather than assumes: a relocated Region overlapping a Primary one fails the merge.

**Blocked by:** 01 — Merge geometry and the placement search.

**Status:** ready-for-agent

- [ ] A Region in Secondary's overworld is rewritten to Primary's overworld at the offset
- [ ] A Region in Secondary's nether is rewritten to Primary's nether at one eighth of it
- [ ] Sub-regions move with their parents, to any depth, and remain nested inside them
- [ ] Vertical bounds are never changed
- [ ] An Embassy's saved destination naming one of Secondary's worlds is rewritten the same
      way, and the anchor still sends visitors to the same build
- [ ] Regions already in Primary, and the Embassies' own regions, pass through byte for
      byte
- [ ] The stored file's formatting is unchanged for every region the merge did not touch,
      as the existing importers guarantee
- [ ] A relocated Region overlapping an existing Primary Region fails the merge, naming
      both, and nothing is written
- [ ] The report states how many Regions were moved, in which dimensions, and how many
      Embassy destinations were rewritten
- [ ] Tests cover a Region with a sub-region, an Embassy with a destination, a Region
      already in Primary, and the overlap refusal
