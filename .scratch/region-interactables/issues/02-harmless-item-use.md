# 02 — Harmless item-use at the player's feet

**What to build:** Flip `RegionProtection.allowsItemUse` (behind
`UseItemCallback` / `ItemEvents.USE`) from "refuse any non-exempt item" to
"allow any item that does not affect the region". See `../spec.md` "Items used
at the player's feet".

**Blocked by:** 01 (shares the region-modifying-item predicate).

**Status:** blocked

- [ ] A non-member may raise a spyglass, read a map, sound a goat horn, open a
      written book, use a brush, hold a clock/compass, etc. inside a foreign
      region with no message.
- [ ] Food / potions / milk / honey / golden apples / firework rockets stay
      allowed (existing exemptions fold into the new rule).
- [ ] The only feet-level uses still refused are ones that place or change
      something in the region — reuse ticket 01's `isRegionModifyingItem`.
- [ ] Bows / crossbows / tridents / fishing rods / snowballs / eggs / splash
      potions stay allowed (they launch a projectile, they do not modify a
      block).
- [ ] Gametests: the spyglass / map / goat-horn cases pass silently; a
      region-modifying item used at the feet is still refused; a resident is
      unaffected.

## Comments
