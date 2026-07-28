# 17 — XP orb merging (server-side)

**What to build:** The Portal's orb-merge effect done properly: bursts of experience orbs merge into fewer orbs server-side (values summed, no XP lost), so grinder scenes stay smooth — replacing the Portal's client-side packet illusion.

**Blocked by:** 01 (Scaffold).

**Status:** done

See `../spec.md` (User Story 45; deviation register 12) and the XpOrbMergeModule section of `docs/research/portal-feature-inventory.md`. If vanilla 26.2's own orb clumping already measurably delivers the effect, document that finding and close with a note instead of building redundantly.

- [x] A burst of orb spawns in close succession results in visibly fewer orb entities whose total XP value equals the sum
- [x] Gametest: spawn a burst, assert merged entity count and preserved total value

## Comments

**Vanilla-clumping verdict: partial — not enough, so we built the (minimal) thing.** The probing gametest was written first, against unmodified vanilla 26.2. Vanilla does merge orbs losslessly (`ExperienceOrb.award` folds a spawn into a nearby same-value orb by bumping its `count`; ticking orbs re-merge every 20 ticks), but it gates every merge behind a random id-mod-40 bucket. Measured: a single-tick burst of 100 awards (3 XP each) still left **38 orb entities** (300 XP intact), and a second identical burst grew the pile to **68** (600 XP intact) — unbounded growth under sustained grinding, an order of magnitude short of the Portal's effect.

**What was built:** `src/main/java/eu/mctraveler/mixin/ExperienceOrbMixin.java` (Java, per ADR 0002) — a single `@ModifyConstant` that shrinks vanilla's merge-bucket modulus from 40 to 1 inside `ExperienceOrb.canMerge`, making the merge deterministic while leaving the rest of vanilla's predicate and its lossless count-stacking untouched. No XP can be lost by construction: the mixin performs no value arithmetic.

**Measured with the mixin** (`XpOrbMergeGameTest`, now the regression guard; 100 kills × 5 XP per burst, which vanilla splits into orbs of 3+1+1, so mixed value classes are exercised): first burst → **2 orb entities, 500 XP exact**; second burst at the same spot → **4 orb entities, 1000 XP exact**. Stable across repeated runs; the guard's bound is 8 entities, which vanilla's 38/68 cannot pass.

**Intent-parity note (recorded in deviation register 12):** merged orbs stack per value class (one orb per orb size) via vanilla's count mechanism, rather than the Portal's client-side summing of mixed values into one orb. Totals are identical; pickup and Mending granularity stay vanilla.
