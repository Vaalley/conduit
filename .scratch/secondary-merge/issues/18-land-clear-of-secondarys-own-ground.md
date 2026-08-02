# 18 — Landing clear of Secondary's own ground

**What to build:** A placement search that will not put Secondary somewhere the audit cannot
reason about.

The audit's test for "did this coordinate move?" is *does it still point into Secondary's old
footprint*. Ticket 03 knew that test has one blind spot and said so plainly (judgement call
5): an offset small enough that the *landed* footprint overlaps the *old* one makes the
question unanswerable, because a coordinate inside the overlap reads the same whether it
travelled or not. It accepted the blind spot on the grounds that the placement search makes it
unreachable — the search only lands Secondary clear of Primary's chunk data, and Primary's
data is what covers Secondary's old ground.

Ticket 13 found that reasoning does not hold. Clipped to its border, Secondary spans about a
hundred region files, and against a *small* Primary the nearest clear slot can sit inside the
box Secondary used to occupy. The search checked the landing against Primary's footprint and
never against Secondary's own.

**How much this matters in production is honestly not much** — Primary has thirteen thousand
players and years of play behind it, so its data almost certainly covers Secondary's old
ground, and the nether-measured clearance pushes the offset out past 90,000 blocks anyway. The
reason to fix it is that "almost certainly" is the wrong standard for a one-shot irreversible
operation, the fix is one more condition on a search that already exists, and an assumption
worth writing down in a judgement call is worth enforcing rather than hoping for.

**Blocked by:** 13 — Clipping the import to Secondary's world border.

**Status:** ready-for-agent

- [ ] A slot is only a candidate when the landed footprint clears Secondary's own source
      footprint, as well as Primary's chunk data
- [ ] That applies in both relocated dimensions, since either can rule a slot out alone
- [ ] The refusal, when no slot satisfies it, says which constraint could not be met — an
      operator reading "no slot found" needs to know whether to ask for less clearance or
      whether something else is wrong
- [ ] Ticket 03's judgement call about the audit's blind spot is updated to say the search now
      enforces what it used to assume
- [ ] A test proves a save whose nearest Primary-clear slot overlaps Secondary's own footprint
      is rejected, and that the search goes on to find one that does not
- [ ] The rehearsal step in the runbook says to check the searched offset against Secondary's
      own footprint as well as Primary's, because that is the check this ticket automates and
      a rehearsal should confirm it rather than trust it
