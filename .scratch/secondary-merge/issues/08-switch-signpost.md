# 08 — `/switch` becomes a signpost

**What to build:** The merge is cold — players are told nothing beforehand and everything
afterwards — so the first thing a returning player does is the thing they have always done,
and that has to be the thing that explains what happened. `/switch` stops travelling and
starts answering.

It tells them the Worlds have merged and there is one map now; where they are standing;
where their other base is, if they had one, in the merged map's own coordinates; and that
Bed and Spawn on their Teleportation Crystal work exactly as they always did. That last
line matters more than it looks: it is the difference between a player who thinks they are
stranded and one who knows they have a way home.

This is the expand half of retiring the Worlds subsystem. Travel still exists underneath
after this ticket; nothing is deleted yet. The command simply stops using it.

**Blocked by:** 06 — Moving the players.

**Status:** ready-for-agent

- [ ] `/switch` no longer moves the player anywhere
- [ ] It states that the Worlds have merged into one map
- [ ] It reports the player's current position
- [ ] It reports where the player's other base is, in merged coordinates, when the merge
      recorded one for them
- [ ] It says nothing about another base for a player who never had one, rather than saying
      something empty
- [ ] It confirms that the crystal's Bed and Spawn destinations are unchanged
- [ ] It works for a player who joined after the merge and has no record at all
- [ ] The wording is checked by test, in the way the repo pins player-facing text elsewhere
- [ ] Nothing else about Travel, the Per-World Bucket or the Worlds service is removed in
      this ticket
