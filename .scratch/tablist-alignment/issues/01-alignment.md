# 01 — Tab list column alignment

**What to build:** see `../spec.md`.

**Status:** reverted — see `02-revert.md`.

- [x] `TabListFeature.namePadding(player)`: right-pads to the longest
      currently-online name; wired into `displayNameWith` between the
      colored name and the `[Nms]` bracket.
- [x] Gametest `namesArePaddedToLineUpTheColumn`: a short and a long name
      pad to the same total width before `[Nms]` — asserted against
      `TabListFeature.tabDisplayName` directly (not through packet capture:
      the shared gametest server has other players online whose names this
      test does not control, so nothing about the *value* of that width is
      predictable — only that it is equal for both).
- [x] `assertDisplayName` (existing latency test) now checks by prefix
      (colored name) and by tail (`[Nms] <hearts>`) instead of one exact
      sequence, since a padding run may now sit in between.

## Comments

Verified: compile, `:bootstrap:runGameTest` (only the 6 known mcaselector
`world_merge_*` failures), `:runtime:test`, prod smoke.
