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

**Status:** done

See ../spec.md (User Stories 8–9; Implementation Decisions "Markup is the
source of truth", "Persistence", and "Animation never persists"; Further Notes
on `getUpdateTag`).

- [x] The mixin owns four source strings for the front face and four for the
      back face, with a clear empty/plain representation
- [x] Source strings are written and read under a namespaced custom key from
      `saveAdditional(ValueOutput)` and `loadAdditional(ValueInput)`
- [x] Loading a sign with no source field preserves vanilla text and establishes
      a sensible plain source for later edits
- [x] Each submitted line is compared with
      `render(storedSource).getString()` before deciding whether it was changed
- [x] An unchanged editable line keeps its stored markup and is re-rendered;
      a changed line becomes the new source and is rendered from that source
- [x] Re-opening and changing one line preserves the source markup and rendered
      effects on the other three lines
- [x] Both faces round-trip independently, including hanging signs
- [x] The custom source field is present in the block-entity update data sent to
      clients, with `getUpdateTag` behavior confirmed by a focused test or
      source inspection
- [x] Sign-item copying and structure-copy behavior are checked and documented,
      including any deliberate limitation
- [x] GameTests cover decorated re-edit round-trips, plain signs, both faces,
      missing source data, and filtered text

## Comments

### Implemented

Source markup now lives in mixin-owned nullable `String[4]` arrays for each
face, exposed to Kotlin through `SignSourceAccess`. Empty and plain lines use
`null`, so an unstyled sign does not gain a custom field. The arrays are
stored below `mctraveler:sign_sources` by the `saveAdditional` and
`loadAdditional` hooks.

The seam investigation used the 26.2 bytecode for
`updateSignText(Player, boolean, List<FilteredText>)`. Vanilla calls
`updateText(UnaryOperator<SignText>, boolean)` once, at ordinal 0; the
operator then calls `setMessages`, followed by one
`setAllowedPlayerEditor(null)` invocation before the block update. The mixin
wraps that ordinal-0 `updateText` argument, so the vanilla result is
reconciled and returned to the same update path without a second `setText`
call or block update. The player, face, and submitted lines are captured
directly from the method arguments, and refusal paths do not reach the call.

This consolidates the ticket-02 rendering and ticket-03 reconciliation into
one path. It keeps the ticket-02 RETURN seam's externally visible behavior,
but no longer uses that redundant injection: both variants are always
rendered, diagnostics are deduplicated, and the returned `SignText` is the
one vanilla applies.

For each raw line, a submitted string equal to
`render(storedSource).component.getString()` keeps the stored source;
otherwise the submitted raw string becomes the new source. The raw source is
authoritative because there is one source array per face. When filtered text
equals the raw submission it reuses the source-rendered component; when it
differs, the filtered submission is rendered independently while the raw
source remains authoritative for later edits.

`getUpdateTag` delegates through `saveCustomOnly`, so the custom field is
included in update data. GameTests cover styled re-edits, one-line changes,
clearing, both faces, hanging signs, filtered variants, missing source data,
plain signs, and explicit block-entity serialization.

Vanilla sign-item placement applies the item's block-entity data component,
but the source arrays are currently saved through the block-entity save path
and are not collected as an implicit data component. Copying a styled sign to
a sign item therefore remains a documented limitation: rendered text can be
copied while authored source is not. Structure saves use block-entity data and
therefore carry the namespaced source field. Piston movement does not move
sign block entities in 26.2; `PistonBaseBlock.isPushable` rejects block states
with block entities, so there is no source-transfer path to extend here.
