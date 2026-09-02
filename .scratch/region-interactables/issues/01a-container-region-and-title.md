# 01a — Container region, and the "Region protected" title

**What to build:** Two fixes to ticket 01's container model, surfaced by
live-testing.

**Blocked by:** 01.

**Status:** done

- [x] A container is judged by the region of the **block it is**, not the
      opener's feet. Reaching a chest / furnace / dispenser from just outside
      the region no longer unlocks it. `RegionProtection.rememberContainerBlock`
      records the region under the block a player right-clicks (in
      `UseBlockCallback`), and `containerOpened` / the title hook prefer it over
      `RegionTracker.regionOf` when it was set this tick.
- [x] A non-member opening a container in a region they cannot modify sees the
      screen titled **Region protected** (grey, bold). `@ModifyArg` on
      `ServerPlayer.openMenu`'s `ClientboundOpenScreenPacket` constructor, via a
      new `Hooks.containerTitleFor`. Workstations, the mod's own menus, and the
      player's own inventory keep their name; `PUBLIC` /
      `ENABLE_PUBLIC_CONTAINERS` regions keep it too (a non-member may modify
      those).

## Notes

- The title packet is sent a few instructions before `initMenu`, so the region
  cannot be read from the (not-yet-captured) session — both the title hook and
  `containerOpened` read `pendingContainerRegion`, set moments earlier by
  `UseBlockCallback`, with a one-tick recency guard so a stale block click does
  not leak into a later entity-menu open.
- Touches `:bootstrap` (new `MixinHooks` signature, a `@ModifyArg` in
  `RegionContainerSessionMixin`) — needs a full restart.

## Comments

Compile green; `RegionInteractablesTest` 8/8; all region unit tests green;
`:bootstrap:runGameTest` 351 tests — new tests
`aContainerIsJudgedByItsBlockNotTheOpenersFeet`,
`aNonMemberSeesRegionProtectedAsTheContainerTitle`,
`aNonMemberCannotOpenACrafter` pass; only the 6 world-merge gametests fail
(mcaselector jar absent here). Prod smoke green.
