# 17 — XP orb merging (server-side)

**What to build:** The Portal's orb-merge effect done properly: bursts of experience orbs merge into fewer orbs server-side (values summed, no XP lost), so grinder scenes stay smooth — replacing the Portal's client-side packet illusion.

**Blocked by:** 01 (Scaffold).

**Status:** ready-for-agent

See `../spec.md` (User Story 45; deviation register 12) and the XpOrbMergeModule section of `docs/research/portal-feature-inventory.md`. If vanilla 26.2's own orb clumping already measurably delivers the effect, document that finding and close with a note instead of building redundantly.

- [ ] A burst of orb spawns in close succession results in visibly fewer orb entities whose total XP value equals the sum
- [ ] Gametest: spawn a burst, assert merged entity count and preserved total value
