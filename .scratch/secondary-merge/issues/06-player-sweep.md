# 06 — Moving the players

**What to build:** A player who was last in Secondary logs in standing exactly where they
logged out, and everything they own that remembers a place still remembers the right one.
This is the fiddliest pass in the merge, because a player carries far more geography than
their position.

Their save records where they stand and in which dimension; where their bed is; where they
last died, which is what a recovery compass reads; where they entered the nether; and the
vehicle they logged out inside, which has a position of its own. Their inventory and ender
chest can hold lodestone compasses, including inside shulker boxes inside other containers.
Their player record holds the World they were last in and a Per-World Bucket for Secondary
carrying a second position and a second respawn point.

Which side gets the offset is the trap: a player live in Secondary with a banked Primary
position needs the live one moved and the banked one left alone, and a player live in
Primary needs the mirror. Both mirrors go through one path, so both are covered by the
same tests.

Each swept record is stamped with the merge and the offset that was applied, so that months
from now "was this player swept?" is answerable from the record rather than guessed at.
And the banked position — the one being discarded — is transformed into merged coordinates
and written somewhere the signpost can read it back, so a player can be told where their
other base went.

**Blocked by:** 01 — Merge geometry and the placement search.

**Status:** ready-for-agent

- [ ] A player last in Secondary's overworld or nether arrives at the same place, in the
      corresponding Primary dimension, facing the same way
- [ ] Their respawn point moves with them, so dying puts them back at their own bed
- [ ] Their last death location moves, so a recovery compass points at their items
- [ ] The position they entered the nether from moves
- [ ] A player logged out in a boat or minecart arrives still in it, at the relocated place
- [ ] Lodestone compasses in the inventory and in the ender chest are retargeted, including
      inside nested containers
- [ ] A player last in Primary is not moved, and neither is anything they own
- [ ] A Secondary Per-World Bucket's position and respawn point are transformed; a Primary
      one is not
- [ ] Every record the merge touched is stamped with the fact of the merge and the offset
      applied
- [ ] All other fields in a player record, including legacy ones, pass through byte for
      byte
- [ ] The banked position of every player who had one is transformed and written where the
      signpost can read it back
- [ ] The report states how many players were swept, how many were left alone, and how many
      banked positions were recorded
- [ ] Tests cover both mirrors — live in Secondary with a banked Primary position, and live
      in Primary with a banked Secondary one — plus the vehicle, the nested lodestone, and
      a record carrying unknown legacy fields
