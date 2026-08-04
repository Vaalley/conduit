# 15 — Choosing the chunks ourselves

**What to build:** The list of chunks to relocate, computed by us and not asked of MCA
Selector.

`--mode select` is not deterministic. It races: `ParamExecutor.select` hands
`Selection::merge` to `ChunkFilterSelector.selectFilter` as a per-job callback, and that
method mutates a non-thread-safe fastutil `Long2ObjectMap` with no synchronisation, so
concurrent per-region-file merges lose entries. Reproduced against the CLI alone, with none
of our code involved: thirty identical runs over a two-region-file Secondary produced the
right three-chunk selection twenty-eight times and a two-chunk selection twice. What went
missing was an entire region file's worth of chunks.

In production that is player builds left behind in Secondary, and the merge reporting
success. **The existing relocated-versus-selected guard cannot see it** — both numbers are
read from the same short CSV, so they agree with each other and are both wrong. It also makes
the merge suites flaky in roughly a quarter of full runs.

So the selection stops being something we ask for and becomes something we know: walk the
source region files, read each chunk's status, and write the selection CSV ourselves. MCA
Selector keeps doing the part it is good at and that we do not want to write — rewriting
coordinates inside chunks as it imports them.

Two things fall out of this, and both are the point rather than a bonus. The relocated count
and the selected count become genuinely independent — one from our own walk of the source,
one from what landed on disk — so the guard that could not work now can. And ticket 13's
clip to Secondary's world border becomes a predicate on a selection we are already
computing, rather than a filter over somebody else's output.

**Blocked by:** None.

**Status:** wontfix — superseded by 16 — Fixing MCA Selector rather than working around it

Superseded before it was started. The race is a missing lock on `Selection.merge`, and MCA
Selector is MIT licensed, so fixing it at source is both smaller than this ticket and keeps
the tool's per-version relocation chain — which matters more than it looked, because
Secondary's chunks are a mix of DataVersions and that chain is what copes with the mixture.
Ticket 16 carries the fix, alongside the four relocation fields ticket 03's audit found.

What this ticket would additionally have bought is a *selected* count derived independently
of the *relocated* count, so the two agreeing would have been evidence rather than a
tautology. That is worth naming as a real loss. It is covered instead by ticket 03's audit,
which checks a property over every relocated chunk, and by ticket 04's sampled diff, which
compares relocated chunks against their sources — both stronger evidence than two counts
agreeing.

- [ ] The selection is computed by reading the source chunks, never by asking MCA Selector
      for it
- [ ] Only fully generated chunks are selected, exactly as the previous query intended, so
      frontier proto-chunks are still left behind
- [ ] The selection is byte-for-byte identical across repeated runs over the same save
- [ ] The CSV is in the form MCA Selector's import expects; the format is established from
      the tool rather than assumed
- [ ] The count of relocated chunks is checked against the selection, and the two are now
      derived independently — from our walk of the source, and from what is on disk
      afterwards — so a disagreement is real evidence rather than a tautology
- [ ] A mismatch fails the merge and names what was expected and what arrived
- [ ] A test relocates a save whose chunk data spans more than one region file and asserts
      every full chunk arrives, repeatedly enough to have caught the old race
- [ ] The merge suites are no longer flaky
- [ ] The runbook records that the selection is ours, and why — an operator reading MCA
      Selector's own documentation would otherwise expect `--mode select` to be involved
