# 01b — Buckets, and fluid flow across a region border

**What to build:** Stop a non-member standing outside a region pouring water
(or lava, or a fish) anywhere inside it, or bailing a pool out of it — and stop
a fluid *flowing* across the border from a source just outside — issue
`https://github.com/Vaalley/conduit/issues/49`.

**Blocked by:** 01.

**Status:** done

- [x] A bucket's placement is refused at the block the fluid actually lands on,
      not the block the player is looking at and not their feet.
- [x] An empty bucket cannot be filled from a source inside a region the player
      cannot modify.
- [x] Residents (and `PUBLIC` regions) are unaffected — a resident may pour into
      their own region from outside it.
- [x] A flowing fluid does not cross into a region it is not already in — water
      or lava poured just past the border stops at it. It flows freely within a
      region and out onto unclaimed ground. No flag (like the piston rule).
- [x] Every bucket refusal carries the one message; the flow rule is silent
      (ambient, like fire and pistons).
- [x] Gametests: `aNonMemberCannotPourWaterIntoARegionFromOutside`,
      `aResidentPoursWaterIntoTheirRegionFromOutside`,
      `aNonMemberCannotBailWaterOutOfARegionFromOutside`,
      `waterDoesNotFlowIntoARegionFromOutside`, `waterFlowsFreelyWithinARegion`.

## Notes

- `BucketItem` does not act through `useItemOn` — it ray-traces from the eyes
  and empties/fills at whatever it strikes — so `RegionProtection`'s item hooks
  (which read the feet) never see the affected block. New `RegionBucketMixin`
  (`:bootstrap`) guards `BucketItem.emptyContents` (HEAD, the resolved placement
  position) and `@Redirect`s the `BucketPickup.pickupBlock` call in
  `BucketItem.use` (the source about to be removed). `MobBucketItem` inherits
  `emptyContents`, so fish buckets are covered.
- Dispensers pass a non-player `entity` and fall through untouched — a dispenser
  firing into a region is `RegionEnvironment` territory.
- Fluid flow across the border is `RegionFluidSpreadMixin` on
  `FlowingFluid.spreadTo`: `direction` points from the fluid to the block it is
  about to fill, so the source is `pos.relative(direction.getOpposite())`. New
  `RegionEnvironment.allowsFluidSpread(level, from, to)` — allow when `to` is
  unclaimed or in the same region the fluid is already in. Covers water and
  lava (both `FlowingFluid`).
- **Not covered:** powder snow (`SolidBucketItem` is a `BlockItem`, guarded by
  `ItemEvents.USE_ON` at the clicked position only).
- `:bootstrap` change (two new mixins, a `MixinHooks` signature) — needs a
  restart.

## Comments

Compile green; region unit tests green; `:bootstrap:runGameTest` 354 tests, the
three new bucket tests pass, only the 6 world-merge gametests fail (mcaselector
jar absent here); prod smoke green (`RegionBucketMixin` applied).
