# 04 — Sampled block-for-block verification

**What to build:** Evidence that the terrain actually arrived, not merely that it is
internally consistent. The audit can only find coordinates that are wrong; it cannot find a
chunk that was dropped, truncated or half-written, because a missing chunk has no stale
coordinates to notice.

So the merge samples. It picks chunks from the relocated data, loads each one alongside the
source chunk it came from, and compares them block for block — states, block entities and
entities — allowing for the offset and nothing else. The operator chooses how many, trading
rehearsal time against confidence. A single mismatch fails the merge.

**Blocked by:** 02 — Relocating Secondary's chunk data.

**Status:** ready-for-agent

- [ ] The operator can choose how many chunks are sampled, and the choice is recorded in
      the report
- [ ] Sampled chunks are drawn from across the whole relocated footprint rather than from
      one corner of it
- [ ] Each sampled chunk is compared against its source for block states, block entities
      and entities, with the offset applied and no other difference tolerated
- [ ] The sample is reproducible for a given save and sample size, so a rehearsal and the
      real run check the same chunks
- [ ] A mismatch fails the merge, names the chunk and describes what differed, and leaves
      the live save untouched
- [ ] The report states how many chunks were compared and that they matched
- [ ] A test proves a deliberately corrupted relocated chunk is caught
- [ ] A test proves a deliberately missing relocated chunk is caught — the case the audit
      structurally cannot see
