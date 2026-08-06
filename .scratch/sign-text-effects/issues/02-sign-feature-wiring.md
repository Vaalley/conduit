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

**Status:** done

See ../spec.md (User Stories 1–7 and 12–14; Implementation Decisions "Where the
hook goes", "Raw and filtered", "Who may use what", and "Paint stays what it is").

- [x] `SignFeature` owns the rendering handoff. It has no `register()` method
      because this ticket adds no event or command registration.
- [x] The mixin targets the verified `SignBlockEntity` seam, with the choice
      between `RETURN` of private `setMessages(...)` and `TAIL` of
      `updateSignText(...)` recorded from `javap` or generated sources
- [x] The chosen seam receives the submitted raw and filtered line values and
      replaces each vanilla-written line with the parser result
- [x] Raw and filtered variants are parsed independently, so text filtering is
      not bypassed by styling or markup
- [x] Every non-click effect is available to every player by default; balance
      policy is read from ticket 05 rather than enforced as a fixed admin-only
      split here
- [x] Both front and back faces render, including hanging signs through the
      shared `SignBlockEntity` type
- [x] Vanilla editability, waxed-sign, and foreign-editor checks remain in
      force
- [x] `RegionSignEditMixin` remains ordered and unchanged at the packet-listener
      head; the new mixin does not move or replace that protection
- [x] The update path still causes the sign's block-entity data to reach nearby
      clients
- [x] GameTests send `ServerboundSignUpdatePacket` through
      `connection.handleSignUpdate(...)` and verify styled components on both
      faces and both filtering variants
- [x] GameTests prove region protection still refuses a foreign sign and waxed
      signs still refuse edits

## Comments

### Implemented

The sign update seam is the return of private `SignBlockEntity.setMessages`.
The Minecraft 26.2 merged deobfuscated artifact reports this exact signature:

```text
private SignText setMessages(Player, List<FilteredText>, SignText)
```

Its bytecode shows `updateSignText(Player, boolean, List<FilteredText>)`
calling `updateText(...)`, whose lambda invokes `setMessages(...)`; the
private method returns the updated `SignText`. A return injection therefore
keeps vanilla's editability checks and block update propagation intact while
receiving both the player and all submitted `FilteredText` values. The mixin
returns a `SignText` whose raw and filtered components have each been parsed
with `SignMarkupLimits.DEFAULT`.

`SignFeature.renderSubmittedLines(...)` is the public rendering surface that
ticket 03 can build on. It accepts a player, the submitted filtered lines, and
the vanilla `SignText`, then returns the independently rendered raw and
filtered variants while preserving dye and glow state. It collects all line
problems and sends one `Paint.warning` message without refusing the edit.

No `SignFeature.register()` call was added to `MCTraveler`. This handler has no
events or commands to register; the Java mixin is its actual registration
seam. Ticket 04 should establish the feature registration path when `/signfx`
commands arrive rather than introducing an empty initializer now.
