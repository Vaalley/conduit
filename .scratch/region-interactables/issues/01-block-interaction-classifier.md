# 01 — Block interaction classifier

**What to build:** Replace `RegionProtection`'s blanket "refuse any item on any
block" with a per-block classifier, so a non-member may use a workstation or
look in a furnace without a message, but is still refused at the blocks and
item-uses that change a region. See `../spec.md` "Block interaction classes",
"Items applied to a block", "Blocks used with an item".

**Blocked by:** nothing (region module is done).

**Status:** done

- [x] `RegionInteractables` (new, in `eu.mctraveler.region`): classifies a
      `BlockState` into `FREE` / `CONTAINER_VIEW` / `REQUIRES_MEMBERSHIP` /
      `CAMPFIRE`, and a held `ItemStack` into region-modifying-or-not. Pure,
      unit-tested (`RegionInteractablesTest`, 8 cases).
- [x] `ItemEvents.USE_ON`: refuses for a non-member only when the item is
      region-modifying (BlockItem, buckets, bonemeal, flint&steel, fire charge,
      hoe/shovel/axe/shears, armour-stand / hanging-entity / end-crystal /
      spawn-egg / lead / honeycomb) or `exemptUseChangesBlock` (water potion →
      mud, ender eye → frame). Everything else → `null`.
- [x] `UseBlockCallback.EVENT` (replaces the block-use handlers): one hook,
      firing before vanilla resolves block-vs-item, carrying both the
      `DISABLE_GATES` / `DISABLE_PUBLIC_REDSTONE_TRIGGERS` flags and the
      interaction classes.
- [x] Bonemeal refused for a non-member wherever used; unchanged for members.
- [x] Refusals carry the one existing message and honour its throttle.
- [x] Gametests in `RegionProtectionGameTest` (`// ---- issue #40 ----`): open a
      workstation empty-handed and holding a tool with no message; open a
      furnace to view but a slot click is refused; refused at note block / anvil
      / jukebox / decorated pot / composter; food on a campfire but not a
      shovel; bonemeal inert for a non-member, fine for a resident; a sword on
      stone says nothing. The old
      `aStrangerCanPlaceAndTakeFlowersFromRegionPots` is inverted to
      `aStrangerCannotPotOrUnpotFlowersInARegion` (issue #40 reverses the
      Portal-parity behaviour that test encoded).

## Notes / decisions

- **`UseBlockCallback`, not the two `BlockEvents` hooks.** Vanilla routes an
  empty-hand right-click straight to `useWithoutItem`, and a potted-plant
  un-pot is done inside `FlowerPotBlock.useItemOn` even for an empty stack, so
  neither `USE_WITHOUT_ITEM` nor `USE_ITEM_ON` catches every case on its own.
  `UseBlockCallback` fires once, before the block-vs-item split, and covers all
  of them; `allowsBlockInteract` is its whole body.
- `ItemEvents.USE_ON` fires only after the block declined the click, so its
  firing already means "the item is about to act" — the item list only has to
  say *which* items, not *which* blocks. Tools that would do nothing to the
  block they hit (a hoe on stone) still count — poking someone's land with a
  tool reads as a build intent, and the classes that must stay silent are
  settled by `classify` before the item is consulted.
- A feature that owns its own right-click (`isExemptItem` — the Teleportation
  Crystal) short-circuits `allowsBlockInteract` so its handler still runs over
  a block this would otherwise refuse.
- No lodestone row: it has no block class, and compass-linking is the compass's
  own use-on (changes the compass, not the region) which already passes.
- Not yet done, deferred to ticket 06's sweep: the CAMPFIRE water-bottle
  extinguish path, and cauldron / chiselled-bookshelf / bee-nest / shelf /
  dragon-egg / respawn-anchor gametests (all classified, none yet pinned).

## Comments

`:runtime` compile green; `RegionInteractablesTest` 8/8; all region unit tests
green; `:bootstrap:runGameTest` 347 tests, the new interaction tests pass, only
the 6 world-merge gametests fail (unrelated — the mcaselector jar is absent on
this machine).
