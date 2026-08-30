# Spec: `/region extend`

Status: ready-for-human

A new player-facing region command, from GitHub issue
`https://github.com/Vaalley/conduit/issues/50`. No Portal or Nucleus precedent —
this is a Phase 2 addition. Companion docs: `../fabric-port/spec.md` for house
conventions, `CONTEXT.md` for vocabulary, `docs/research/portal-feature-inventory.md`
§2.8 for the existing `/rg` family and the size-limit arithmetic this reuses.

## Problem statement

Growing a region today means deleting it and re-running `/rg start` + `/rg end`
over the larger footprint — losing the title, members and flags. Players who have
simply run out of room want to push one edge out in place.

## Solution

`/region extend <distance>` (and the `/rg` alias). The player stands in the region,
looks toward the edge they want to move (snapped to a cardinal — N/S/E/W), and the
matching edge moves out `<distance>` blocks. Y bounds are untouched; this is a
horizontal-only operation.

## User stories

1. As a region member, I want `/rg extend <n>` to move the edge I am facing out by
   `n` blocks, keeping the region's title, members and flags.
2. As a region member, I want to be refused if the larger region would exceed the
   5000-block limit, with a message that names the limit and tells me to ask an
   admin.
3. As an admin, I want to extend any region past the limit, the same way `/rg end`
   lets me create one past it.
4. As a player, I want the usual refusals: not standing in a region, not a member
   of it, an extension that overlaps another region, or one that would push a
   sub-region outside its parent.

## Rules

- **Who**: resident of the region, or an admin (vanilla op). Same gate as
  `/rg rename` / `/rg delete`. Non-members get `ERROR You are not a member of this
  region`; outside every region, `ERROR You must stand in the region you want to
  extend`.
- **Region chosen**: the deepest region at the player's feet
  (`RegionTracker.regionOf`), matching every other stand-in-it command.
- **Direction**: `player.direction` — the player's horizontal facing, already
  snapped to one of N/S/E/W by vanilla. NORTH → −Z edge, SOUTH → +Z, WEST → −X,
  EAST → +X.
- **Distance**: a positive integer. `< 1`, or the bare command, →
  `USAGE /rg extend <distance>` (house rule: malformed invocations get USAGE).
- **Size limit**: new area `(spanX+1)·(spanZ+1)`. `> 5000` and not an admin →
  `ERROR Region too large (<n> blocks). Limit is 5000 blocks. Ask an admin to
  extend it further.` — the `/rg end` message, re-worded for extension. Admins
  bypass, as they do for `/rg end`.
- **Sub-regions**: a sub-region may not extend past its parent's footprint —
  `ERROR You cannot extend a region outside <parent>` (hard error; the player
  extends the parent first, or an admin does). Within the parent it is allowed.
- **Overlap**: the grown footprint is checked against every other region except
  the region itself, its ancestors (which contain it) and its own descendants
  (which it contains) → `ERROR Overlapping region <name>!`, the `/rg end` message.
- **Embassies**: refused (`ERROR You cannot extend an embassy`), matching
  `/rg delete`'s protective stance toward the `/embassy`-managed geometry.
- **On success**: the region's corners are rewritten (normalised to min/max, as
  `/rg bounds` normalises Y), `regions.json` is saved (which fires the existing
  `RegionService.onChange` listener, so the Lodeway web map redraws), and every
  online player's sidebar + Adventure mode is recomputed so anyone the region now
  covers sees it in the same tick. Reply: `SUCCESS Extended <name> <n> blocks
  <direction>`.

## Deviations / interpretations

- **No Portal counterpart.** Nothing to match message-for-message; the wording
  above reuses the `/rg end` family's shapes so the command reads as part of it.
- **Corners are normalised on write.** Regions store un-normalised corners for
  legacy-data compatibility, but a region a player just mutated is not legacy
  data, and `/rg bounds` already sets `startY`/`endY` normalised. Extend does the
  same for X/Z.
- **Full recompute after the bounds change.** `RegionTracker.afterBoundsChange`
  is `afterRemoval` under another name — the footprint moved under everyone's
  feet, so the same server-wide sweep a removal triggers is the right one.
