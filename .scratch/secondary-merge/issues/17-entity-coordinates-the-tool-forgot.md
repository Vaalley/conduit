# 17 — The entity coordinates the tool still forgets

**What to build:** A merge that does not stop in the middle of a downtime window because MCA
Selector never learned about one more kind of entity.

Ticket 16 fixed three defects in the tool and left a fourth on purpose: **a bee's `hive_pos`
and `flower_pos` are not relocated.** The tool moves entity positions from a hand-written
switch with no bee case, so a bee arrives in Primary still remembering a hive in Secondary.
The audit catches it, which means **a real merge refuses today on any Secondary chunk holding
a bee** — and bee nests generate naturally in flower forests, birch forests and meadows, so
that is not a rare shape.

The bee is not really the problem, though. The problem is the sentence ticket 16 used to
justify leaving it: *there is no reason bees are last*. Four defects have been found in this
tool by pointing an audit at it, and each was found by running into it. The next one will be
found the same way, and the worst possible moment to find it is at 2am with the server down
and thirteen thousand players waiting for a merge that has just refused.

So this ticket wants two things, and the second matters more than the first.

**Fix the bee** in the patched build, additively, the way ticket 16 fixed the others — it is
small, it is upstreamable with the rest, and it removes a refusal we know is coming.

**Then stop the next one being a cutover-night surprise.** After the relocation, before the
audit, complete what the tool left behind: apply the offset to coordinates that should have
moved and did not, by the same shape-based rule the audit uses to find them. Then let the
audit run exactly as it does now, and refuse over anything still standing.

The obvious objection is ticket 03's, and it was right: *an audit that patches over the
relocation's gaps stops being able to tell anyone the gaps are there.* The answer is that the
completion pass must **report every coordinate it had to fix, and what kind it was** — a
count and a list of field names in the merge report, loud enough that an operator reads it as
"the tool is behind again" rather than as nothing having happened. The information ticket 03
protected is preserved; what changes is that the merge finishes and tells you, instead of
stopping and telling you.

**Blocked by:** None — ticket 16 is complete.

**Status:** ready-for-agent

- [ ] A bee's `hive_pos` and `flower_pos` are relocated, in the patched build, additively,
      and the patch in this repo is updated to match
- [ ] A Secondary chunk containing a bee with a hive relocates and passes the audit
- [ ] A completion pass runs after the relocation and before the audit, applying the offset
      to coordinates that should have moved and did not
- [ ] It uses the same shape-based rule the audit uses, and the same exclusions — a velocity,
      a uuid, and the arbitrary-NBT escape hatches are not places and are not touched
- [ ] Every coordinate it completes is counted and named by kind in the merge report, so a
      tool that has fallen behind is visible rather than silently compensated for
- [ ] The audit still runs afterwards and still refuses over anything left, unchanged
- [ ] A test proves an entity field the tool does not know about is completed, reported, and
      passes the audit — using a field that is genuinely unhandled rather than a mock
- [ ] A test proves the report names it, so the operator's evidence is pinned, not incidental
- [ ] The runbook says what a non-zero completion count means and what to do about it
- [ ] The rehearsal step says to check whether Secondary actually contains bees in relocated
      chunks, because that answer is knowable before the downtime window rather than during it
