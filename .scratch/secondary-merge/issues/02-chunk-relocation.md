# 02 — Relocating Secondary's chunk data

**What to build:** The merge actually moves Secondary. Its overworld and nether chunk data
— terrain, entities and points of interest alike — is relocated into Primary's own
dimensions at the planned offset, into the staging area rather than over the live save.
Secondary's End is discarded. Partially generated chunks at Secondary's frontier are
dropped rather than moved, so that frontier regenerates cleanly from one seed instead of
arriving half from another.

The relocation itself is performed by MCA Selector, which has done this job for a decade
and tracks the current Minecraft version. It is resolved by the build as a pinned,
checksummed artifact and run as a subprocess: it is a tool we run, not a library we link,
so its dependencies never reach the mod's compile classpath. Because the build resolves it,
the tests drive the real thing rather than a stand-in.

**Blocked by:** 01 — Merge geometry and the placement search.

**Status:** ready-for-agent

- [ ] Secondary's overworld chunk data lands where Primary's overworld will look for it,
      offset by the planned amount
- [ ] Secondary's nether chunk data lands where Primary's nether will look for it, offset
      by one eighth
- [ ] Terrain, entity and point-of-interest data are all relocated, not just terrain
- [ ] Secondary's End chunk data is discarded, along with Secondary's level-wide saved data
- [ ] Chunks that are not fully generated are dropped rather than relocated
- [ ] Nothing is written outside the staging area; the live save is untouched until the
      whole merge succeeds
- [ ] The relocation tool is resolved by the build at a pinned version and verified against
      a checksum, so an operator never has to fetch anything by hand
- [ ] A failure or non-zero exit from the relocation fails the merge with the tool's own
      output attached, and nothing is moved into place
- [ ] The report states how many chunks were relocated, how many were dropped as
      incomplete, and how many bytes were transferred
- [ ] A test builds a real region file containing more than one chunk, relocates it for
      real, and reads it back to confirm both chunks arrived at the expected coordinates
