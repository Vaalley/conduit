# 01 — Away players excluded from the sleep requirement

**What to build:** see `../spec.md`.

**Status:** done

- [x] `AwayFeature.isAway(player)` — public read of the existing away flag.
- [x] `Hooks.isAway` bridge (interface + facade + `MixinHooksImpl`).
- [x] `SleepRequirementMixin` (`@Mixin(SleepStatus)`, `require = 0`): `@ModifyVariable`
      HEAD of `update` and `areEnoughDeepSleeping` drops away players from the list.
- [x] Registered in `mctraveler.mixins.json`.
- [x] Gametest `SleepRequirementGameTest.anAwayPlayerIsNotCountedTowardTheSleepRequirement`:
      `sleepersNeeded(100)` is 2 for two awake players, 1 when one of the pair is away.

## Notes

- `:bootstrap` change (new mixin + new `Hooks` method) — needs a full restart.
- Verified: compile, `:bootstrap:runGameTest` (only the 6 known mcaselector
  world-merge failures), prod smoke.
