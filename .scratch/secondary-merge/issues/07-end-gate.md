# 07 — The End, and everything anchored in it

**What to build:** Secondary's End is destroyed by this merge, and that is the one
irreversible thing in it. So it is not something the operator can do by accident: the merge
refuses by default the moment anything is still anchored there, tells them exactly what and
who, and only proceeds when they say so explicitly.

The report has to be good enough to act on before the fact, because afterwards there is
nothing to act on. Regions are listed by title and by the names of their members, so the
operator knows who to warn. Players are counted, and each one's landing is stated in
advance — their Secondary overworld position if they have one banked, and the relocated
Secondary spawn if they do not. Embassy destinations pointing into the End are named, and
cleared rather than left aiming at nothing.

This ticket also adds the second cross-check the audit could not do alone: every respawn
point the player sweep transformed must now have a bed or a respawn anchor standing at it
in the relocated chunks. A respawn point and its bed are moved by two different passes, and
if they disagree, players wake up inside solid rock.

**Blocked by:** 05 — Moving the Regions; 06 — Moving the players.

**Status:** ready-for-agent

- [ ] The merge refuses, writing nothing, if any Region, player or Embassy destination is
      anchored in Secondary's End
- [ ] That refusal lists every affected Region by title and by its members' names
- [ ] It counts the affected players and states what will happen to each group
- [ ] It names every Embassy whose destination points into Secondary's End
- [ ] An explicit opt-in accepts the loss and lets the merge proceed
- [ ] With the opt-in, End Regions are deleted, End Embassy destinations are cleared, and
      each affected player lands at their banked Secondary overworld position, or at the
      relocated Secondary spawn if they have none
- [ ] No player is left naming a dimension that will not exist after the merge
- [ ] Every respawn point the sweep transformed has a bed or respawn anchor at it in the
      relocated chunks, or the merge fails naming the player and the position
- [ ] The report records what was dropped, so the operator has a record of what to
      communicate afterwards
- [ ] Tests cover the default refusal, the opt-in, both player landings, and a respawn
      point whose bed did not survive relocation
