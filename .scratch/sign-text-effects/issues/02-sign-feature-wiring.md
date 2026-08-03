# 02 — SignFeature wiring and submitted-line rendering

**What to build:** A `SignFeature` module and a `SignBlockEntity` mixin that
re-renders submitted sign lines after vanilla has written them. Parse both the
raw and filtered variants, preserve the filtering distinction, and keep the
existing `RegionSignEditMixin` ordering untouched. During implementation,
verify the seam choice with `javap` or `genSources`: choose between returning
from private `setMessages(...)` and injecting at the tail of
`updateSignText(...)`. Wire `SignFeature.register()` from `MCTraveler.kt`, and
prove behavior with GameTests driven through `handleSignUpdate`.

**Blocked by:** 01.

**Status:** needs-triage

See ../spec.md (User Stories 1–7 and 12–14; Implementation Decisions "Where the
hook goes", "Raw and filtered", "Who may use what", and "Paint stays what it
is").

- [ ] A plain Kotlin `SignFeature` has `register()` and is wired from
      `MCTraveler.onInitialize()`
- [ ] The mixin targets the verified `SignBlockEntity` seam, with the choice
      between `RETURN` of private `setMessages(...)` and `TAIL` of
      `updateSignText(...)` recorded from `javap` or generated sources
- [ ] The chosen seam receives the submitted raw and filtered line values and
      replaces each vanilla-written line with the parser result
- [ ] Raw and filtered variants are parsed independently, so text filtering is
      not bypassed by styling or markup
- [ ] Both front and back faces render, including hanging signs through the
      shared `SignBlockEntity` type
- [ ] Vanilla editability, waxed-sign, and foreign-editor checks remain in
      force
- [ ] `RegionSignEditMixin` remains ordered and unchanged at the packet-listener
      head; the new mixin does not move or replace that protection
- [ ] The update path still causes the sign's block-entity data to reach nearby
      clients
- [ ] GameTests send `ServerboundSignUpdatePacket` through
      `connection.handleSignUpdate(...)` and verify styled components on both
      faces and both filtering variants
- [ ] GameTests prove region protection still refuses a foreign sign and waxed
      signs still refuse edits
