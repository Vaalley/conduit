# 16 — Fixing MCA Selector rather than working around it

**What to build:** A patched build of MCA Selector that relocates 26.2 chunks completely and
selects them deterministically, replacing the stock 2.8 jar the merge runs today.

Two defects have been found in the stock tool, and both are ours to fix now that no released
version has caught up. It is MIT licensed, so this is allowed; the patch is small, and the
same commits are worth offering upstream, which visibly takes fixes of exactly this kind.

**The selection races.** `Selection.merge` mutates a non-thread-safe fastutil
`Long2ObjectMap` from concurrent per-region-file jobs, so `--mode select` silently
under-selects — around 7% of runs lose an entire region file's worth of chunks. In
production that is player builds left behind with the merge reporting success. This is a
missing lock, not a design problem.

**The relocation is incomplete for 26.2.** Four kinds of coordinate arrive in Primary still
naming Secondary. The first three are 1.21.5's `InlineBlockPosFormatFix` renames the tool
never followed; the fourth is older still.

| what | 26.2 writes | the tool moves |
| --- | --- | --- |
| a leash tied to a fence | `leash`, an int array of three | `Leash`, a compound of `X`/`Y`/`Z` |
| an item frame's tile | `block_pos` | `TileX`/`TileY`/`TileZ` |
| a painting's tile | `block_pos` | `TileX`/`TileY`/`TileZ` |
| a villager's `home`, `job_site`, `meeting_point` | `{value:{dimension,pos}}` | reads `pos` off the memory itself |

**Both spellings must keep working.** This is the trap in the whole ticket: Secondary's
chunks are a *mix* of DataVersions, because vanilla upgrades a chunk only when it loads one,
so pre-cutover chunks nobody has visited still carry the old spelling while post-cutover ones
carry the new. The fixes are additive — handle the new form *as well as* the old — never
replacements.

Keeping the tool rather than reimplementing it is deliberate: its per-version relocation
chain is what copes with that mixture, and it is the part that would be most painful and most
dangerous to write ourselves.

**Where the build lives.** The patched jar is built locally and kept outside this repo, pinned
by path and checksum the way the stock artifact is pinned by URL and checksum today. That
costs reproducibility, so the patch itself — as a diff against the upstream tag — is kept
*in* this repo: a few kilobytes that make the fixes reviewable in our own history and reduce
recovering a lost jar to clone, apply, build.

**Supersedes ticket 15**, which routed around the selection race by computing the selection
ourselves. Fixing the race at source is smaller and keeps the tool's own version handling.
What ticket 15 would also have bought, and this does not, is a selected count derived
independently of the relocated count — the audit and the sampled diff are what cover that
now, and both are stronger evidence anyway.

**Blocked by:** None — tickets 02 and 03 are complete, and ticket 03's audit is the test.

**Status:** ready-for-agent

- [ ] The selection is deterministic: repeated runs over the same save select the same chunks
      every time, demonstrated over enough runs to have caught a 7%-per-run defect
- [ ] A leash, an item frame, a painting and a villager's memories all arrive in Primary
      naming their relocated positions
- [ ] Chunks written in the *older* spelling still relocate correctly, so a Secondary chunk
      nobody has visited since before the Portal cutover is not left behind
- [ ] Ticket 03's fixture of one-of-every-coordinate-bearing-thing passes the audit after a
      real relocation — that acceptance criterion was left unmet for this ticket to close
- [ ] The named tests in `WorldMergeAuditTest` that pin each of the four fields now pass
      rather than asserting a refusal
- [ ] The build resolves the patched jar by path and verifies its checksum, and fails with an
      instruction an operator can follow when it is missing
- [ ] The patch is kept in this repo as a diff against the upstream tag it applies to
- [ ] The runbook records how to rebuild the jar from that diff, and why it exists at all
- [ ] The fixes are offered upstream, with attribution to this repo's finding
