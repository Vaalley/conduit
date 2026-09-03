# 01b — Buckets reach past the player's feet

**What to build:** Stop a non-member standing outside a region pouring water
(or lava, or a fish) anywhere inside it, or bailing a pool out of it — issue
`https://github.com/Vaalley/conduit/issues/49`.

**Blocked by:** 01.

**Status:** done

- [x] A bucket's placement is refused at the block the fluid actually lands on,
      not the block the player is looking at and not their feet.
- [x] An empty bucket cannot be filled from a source inside a region the player
      cannot modify.
- [x] Residents (and `PUBLIC` regions) are unaffected — a resident may pour into
      their own region from outside it.
- [x] Every refusal carries the one message.
- [x] Gametests: `aNonMemberCannotPourWaterIntoARegionFromOutside`,
      `aResidentPoursWaterIntoTheirRegionFromOutside`,
      `aNonMemberCannotBailWaterOutOfARegionFromOutside`.

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
- **Not covered:** powder snow (`SolidBucketItem` is a `BlockItem`, guarded by
  `ItemEvents.USE_ON` at the clicked position only), and water *flowing* across
  a region border from a source legally placed just outside — that is ambient
  spread, a `RegionEnvironment`-shaped problem, out of scope here.
- `:bootstrap` change (new mixin) — needs a restart.

## Comments

Compile green; region unit tests green; `:bootstrap:runGameTest` 354 tests, the
three new bucket tests pass, only the 6 world-merge gametests fail (mcaselector
jar absent here); prod smoke green (`RegionBucketMixin` applied).
