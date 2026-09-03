# 02 — Harmless item-use at the player's feet

**What to build:** Flip `RegionProtection.allowsItemUse` (behind
`UseItemCallback` / `ItemEvents.USE`) from "refuse any non-exempt item" to
"allow any item that does not affect the region". See `../spec.md` "Items used
at the player's feet".

**Blocked by:** 01, 01b.

**Status:** done

- [x] A non-member may raise a spyglass, read a map, sound a goat horn, throw a
      pearl or a snowball, cast a line inside a foreign region with no message.
- [x] Food / potions / milk / honey / golden apples / firework rockets stay
      allowed.
- [x] The one feet-level use still refused is a bucket (`stack.item is
      BucketItem`) — it places or lifts a fluid. `RegionBucketMixin` (01b)
      already judges that by the block the fluid reaches; this stops a
      non-member starting one on land they cannot modify.
- [x] Bows / crossbows / tridents / fishing rods / snowballs / eggs / splash
      potions stay allowed (they launch a projectile, they do not touch a
      block).
- [x] Gametest `aNonMemberMayUseAHarmlessItemInAForeignRegion` (spyglass, goat
      horn, map, stick — silent; then a bucket — refused). The old
      `aNonMemberCannotUseAnItem` is inverted.

## Comments

`allowsItemUse` collapsed from a hand-kept exempt list to
`stack.item !is BucketItem`. Runtime-only — hot-swappable, no restart.

The `CrystalMenuGameTest` "menu opens in a foreign region" test used an
ordinary item-use as its precondition ("prove the guest is refused on this
ground"); a block *item* used in the air is no longer refused, so the
precondition switched to an actual block placement.

Compile green; region unit tests green (`RegionProtectionPermissionsTest`
still 4/4); `:bootstrap:runGameTest` 356 tests, the new test passes, only the 6
world-merge gametests fail (mcaselector jar absent here).
