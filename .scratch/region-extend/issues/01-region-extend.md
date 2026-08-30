# 01 — `/region extend <distance>`

**What to build:** A new command in the `/region` + `/rg` family that grows the
region the sender stands in outward by `<distance>` blocks along the cardinal edge
they are facing, keeping title/members/flags, with the size limit, sub-region,
overlap and membership rules from `../spec.md`.

**Blocked by:** nothing (the region module is `done` from fabric-port ticket 12–15).

**Status:** done

See `../spec.md` and `docs/research/portal-feature-inventory.md` §2.8.

- [x] `/rg extend <distance>` and `/region extend <distance>` registered; bare
      command and `distance < 1` reply `USAGE /rg extend <distance>`
- [x] Resident-or-admin gate; outside-region and non-member refusals
- [x] Facing → edge: NORTH −Z, SOUTH +Z, WEST −X, EAST +X, from `player.direction`
- [x] New area `> 5000` and not admin → `Region too large (<n> blocks). Limit is
      5000 blocks. Ask an admin to extend it further.`; admin bypass
- [x] Sub-region past parent footprint → `You cannot extend a region outside
      <parent>`; within the parent allowed
- [x] Overlap with a non-ancestor / non-descendant region → `Overlapping region
      <name>!`; the region's own subtree and ancestors are not overlaps
- [x] Embassy regions refuse
- [x] Success: corners normalised + written, `save()` (Lodeway map redraws via
      `onChange`), server-wide sidebar/mode recompute, `SUCCESS Extended <name>
      <n> blocks <direction>`
- [x] Gametests in `RegionCommandGameTest` for each branch

## Comments

Implemented entirely in `:runtime` (no bootstrap/mixin change — it is a command,
not an interception), so it hot-swaps onto a live server.

- **`RegionCommands.extend`** — the handler, next to `start`/`end`/`bounds`. Adds
  the `extend` node to `tree(alias)` and a `/rg extend <distance>` line to the
  help panel (`HELP`), so the new command is discoverable where the others are.
  `IntegerArgumentType.integer()` unbounded like `/rg bounds`, with `distance < 1`
  validated in the body to a USAGE reply rather than a Brigadier parse error.
- **`RegionService.firstOverlappingExtension(region, minX, maxX, minZ, maxZ)`** —
  a sibling of `firstIntersecting`. `firstIntersecting`'s `excluding` parameter
  excludes a prospective *parent* and its ancestors but deliberately keeps that
  parent's other descendants as overlaps (siblings of a new region). Extend needs
  the opposite on the low side — the region's *own* descendants lie inside it and
  are never overlaps — so it gets its own scan that skips the region node (and by
  not recursing past it, its whole subtree) and its ancestors.
- **`RegionTracker.afterBoundsChange(server)`** — `= afterRemoval(server)`. The
  footprint grew under everyone, so the same server-wide `refresh` sweep a removal
  runs is what puts the sidebar and forced-Adventure on players the region now
  covers, in the same tick rather than on the next per-tick sweep.
- **`MessageCapturingPlayer.face(Direction)`** (test fixture) — sets `yRot` /
  `yHeadRot` from `Direction.toYRot()` and asserts `player.direction` took, so a
  gametest can point the mock at an edge before `/rg extend`.

**Interpretations recorded here (not in spec.md):**

- **Corners normalised on write.** `Region` keeps un-normalised corners for
  legacy round-trip fidelity, but `/rg bounds` already writes `startY`/`endY`
  normalised, and a region a player just extended is not untouched migrated data.
  Extend writes `startX/startZ = min`, `endX/endZ = max`.
- **Embassy refusal.** The spec has no embassy clause from issue #50; blocking it
  mirrors `/rg delete` (`/embassy` owns that geometry — the plot spiral) and is
  cheap to relax later if an admin workflow needs it.
- **Pathological admin distance.** `distance` is an unbounded `int`; an admin
  passing a near-`Integer.MAX_VALUE` distance would overflow the corner
  arithmetic. Left unguarded — `/rg bounds` takes raw ints too, and admins are
  trusted. Flag if it ever bites.

**Gametests** (`RegionCommandGameTest`, `// ---- /rg extend ----`):
`bareRgExtendShowsUsage`, `extendOutsideAnyRegionErrors`, `aNonMemberCannotExtend`,
`extendRejectsZeroOrNegativeDistance`, `aResidentExtendsTheEdgeTheyFace` (south
then west, asserting both replies, both edges, and the grown ground answering as
the region), `extendRefusesToOverlapAnotherRegion`,
`aSubRegionCannotExtendOutsideItsParent` (refusal then an allowed in-parent
extend), `extendRefusesToCrossTheSizeLimit` (99×50 → +2 → 5148), and
`anAdminMayExtendPastTheSizeLimit`. The pinned help-panel component in the same
file gained the `/rg extend <distance>` line.
