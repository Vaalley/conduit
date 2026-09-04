# 02 — Revert the padding

**What to build:** "Forget the alignment, put the ping next to the name
with only one space in between."

**Status:** done

- [x] `TabListFeature.tabDisplayName` back to `<rank-colored name> [<N>ms]`
      — `namePadding` removed.
- [x] Gametest `namesArePaddedToLineUpTheColumn` and its
      `widthBeforeLatencyBracket` helper removed.
- [x] `assertDisplayName` back to one exact three-run sequence (name, a
      plain space, the `[Nms]` bracket) — no padding run to route around.

## Comments

Verified: compile, `:bootstrap:runGameTest` (only the 6 known mcaselector
`world_merge_*` failures), prod smoke.
