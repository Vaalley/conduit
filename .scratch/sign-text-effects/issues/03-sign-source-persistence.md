# 03 — Sign source markup persistence and round-trip

**What to build:** Persist Sign Markup separately from the rendered
`SignText`, using mixin-owned `String[4]` source arrays for each face. Store
the arrays under a namespaced key through `saveAdditional(ValueOutput)` and
`loadAdditional(ValueInput)`. On edit, compare each submitted line with
`render(storedSource).getString()`: when they are equal, keep the stored source
and re-render it; when they differ, treat the submitted line as the new source.
Confirm how `getUpdateTag` carries the field, and check sign-item and
structure-copy behavior rather than assuming either path.

**Blocked by:** 02.

**Status:** needs-triage

See ../spec.md (User Stories 8–9; Implementation Decisions "Markup is the
source of truth", "Persistence", and "Animation never persists"; Further Notes
on `getUpdateTag`).

- [ ] The mixin owns four source strings for the front face and four for the
      back face, with a clear empty/plain representation
- [ ] Source strings are written and read under a namespaced custom key from
      `saveAdditional(ValueOutput)` and `loadAdditional(ValueInput)`
- [ ] Loading a sign with no source field preserves vanilla text and establishes
      a sensible plain source for later edits
- [ ] Each submitted line is compared with
      `render(storedSource).getString()` before deciding whether it was changed
- [ ] An unchanged editable line keeps its stored markup and is re-rendered;
      a changed line becomes the new source and is rendered from that source
- [ ] Re-opening and changing one line preserves the source markup and rendered
      effects on the other three lines
- [ ] Both faces round-trip independently, including hanging signs
- [ ] The custom source field is present in the block-entity update data sent to
      clients, with `getUpdateTag` behavior confirmed by a focused test or
      source inspection
- [ ] Sign-item copying and structure-copy behavior are checked and documented,
      including any deliberate limitation
- [ ] GameTests cover decorated re-edit round-trips, plain signs, both faces,
      missing source data, and filtered text
