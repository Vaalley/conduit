# Spec: Sign text effects

Status: needs-triage

Player-authored formatting on signs — colors, gradients, rainbow, the enchanting-table
scribble — typed as markup in the vanilla sign editor and rendered server-side into
styled components. Tracker issue: `Vaalley/conduit#6` (feature request from Valentin
via Observer): "Support text effects on signs. such as rainbow effect, enchant table
scribble effect, colors and other things possibly".

Companion docs: `../fabric-port/spec.md` for house conventions, `CONTEXT.md` for
vocabulary, `docs/adr/0002-idiomatic-fabric-no-ported-hook-framework.md` for the
feature-module shape.

## Problem Statement

Vanilla gives a sign four lines of one color: whatever dye was last applied, plus the
glow-ink toggle. Everything richer that the game itself can render — per-character
color, gradients, the Standard Galactic Alphabet the enchanting table uses, the
churning obfuscated glyphs, bold/italic — is reachable only through `/data merge` with
hand-written component JSON, which no player will ever do and which the region rules
do not gate. Shops, embassy plots, and warp boards all end up as flat white text.

The renderer for all of this already ships in every vanilla client. Nothing here needs
a client mod or a resource pack: a sign draws whatever `Component` the server put in
its block entity, fonts and per-character styles included. The only thing missing is a
way for a player to *say* what they want, in a four-line plain-text box.

## Solution

A `sign` feature module that intercepts the sign edit the moment vanilla has written
it, parses each line as **Sign Markup** — a small tag language (`<rainbow>`,
`<gradient:#ff0000:#0000ff>`, `<enchant>`, `<#ff8800>`, plus legacy `&c` codes) — and
replaces the line with the styled component the markup describes. The typed markup is
kept beside the rendered text in the block entity so that re-opening the sign and
editing one line does not flatten the other three. A `/signfx` command family covers
discovery (what tags exist), inspection (what markup is on this sign), and repair.
Animated effects (a rainbow that moves, a wave, a typewriter) are a later, budgeted
tier that pushes block-entity updates to nearby players without persisting frames.

## User Stories

### Writing effects

1. As a player, I want to type `<rainbow>MCTraveler</rainbow>` on a sign line and see
   the line drawn in a per-character rainbow when I close the editor.
2. As a player, I want `<gradient:#ff0000:#0000ff>Welcome</gradient>` to fade a line
   from one color to another across its characters, with more than two stops allowed.
3. As a player, I want `<enchant>` to render text in the enchanting-table scribble —
   the `minecraft:alt` font (Standard Galactic Alphabet), optionally churning like the
   enchanting table does — and `<sga>` for the same font held still.
4. As a player, I want the sixteen named colors and `<#rrggbb>` hex, plus bold,
   italic, underline, strikethrough, and obfuscated, both as tags and as the `&`
   codes I already know from every other server.
5. As a player, I want tags to nest and to close properly, `<reset>` to drop back to
   plain, and a backslash to escape a literal `<` or `&` when I actually want one.
6. As a player, I want a line whose markup is malformed to keep my text (rendered
   plain, markup shown literally) and tell me what was wrong in chat, rather than
   silently eating the line or refusing the edit.
7. As a player, I want effects to work on both faces of a sign and on hanging signs,
   because they are the same block entity to the game.

### Living with effects

8. As a player, I want to re-open a sign I decorated, change line 3, and find lines 1,
   2 and 4 still decorated exactly as they were.
9. As a player, I want the editor to show me something I can work with when I re-open
   a decorated sign, and I want `/signfx source` to print the markup of the sign I am
   looking at so I can copy it.
10. As a player, I want `/signfx help` to list the tags with a rendered example of
    each, and `/signfx preview <markup>` to show me a line in chat before I climb a
    ladder to place it.
11. As a player, I want `/signfx clear` to strip a sign back to plain text.
12. As a player, I want dye color and glow ink to keep working, and to compose with
    markup rather than fight it.

### Rules

13. As an admin, I want to tune the balance of sign effects through configuration,
    because a wall of obfuscated text is a moderation problem and a click command
    on a sign is a security one.
14. As an admin, I want effect signs to respect region protection exactly as plain
    signs do — no new way to write into someone else's claim.
15. As an admin, I want a bound on what one sign can cost: a cap on how many styled
    pieces a line may expand into, so a 384-character rainbow line cannot be used to
    inflate chunk data or the block-entity packet.
16. As an admin, I want the server log (and, later, the HTTP API) to be able to show
    the plain reading of what a sign says, so obfuscated or scribbled text is not a
    hiding place.

### Movement (later tier)

17. As a player, I want `<rainbow:animate>` to make the colors travel along the line,
    and `<wave>`, `<pulse>`, `<typewriter>`, `<marquee>` for the other obvious motions.
18. As an admin, I want animation to be budgeted — a cap on animated signs per world,
    a tick interval, work skipped entirely when no player is nearby — and I want
    animation frames never to reach disk.

## Implementation Decisions

Verified against the Minecraft 26.2 server and client jars; anything still open is
marked as such in the tickets.

- **Where the hook goes**: `ServerGamePacketListenerImpl.handleSignUpdate` filters the
  four strings asynchronously and then calls a private `updateSignText`, which does no
  more than find the block entity and call
  `SignBlockEntity.updateSignText(Player, boolean, List<FilteredText>)`. That method
  runs the editability checks, builds one `Component.literal(...)` per line (keeping
  the line's *existing* style), stores it through `SignText.setMessage(int, Component)`
  or its raw/filtered pair, and then calls
  `level.sendBlockUpdated(pos, state, state, 3)`. So the seam is inside
  `SignBlockEntity`: a mixin that takes the `SignText` vanilla just wrote and replaces
  each line with the parse of the submitted string. Hooking here rather than in the
  packet listener means `RegionSignEditMixin` — which cancels at HEAD of the listener
  method — still runs first and unchanged, and waxed/foreign-editor refusals are
  vanilla's to make. Ticket 02 picks between the `RETURN` of the private
  `setMessages(...)` and the `TAIL` of `updateSignText(...)`; both are inside the same
  call, and a second `sendBlockUpdated` in the same tick coalesces in
  `ChunkHolder.broadcastChanges`.
- **Raw and filtered**: `SignText` carries two `Component[4]`, raw and filtered, and
  the client is served whichever matches its text-filtering setting. The parse runs on
  both strings; the filtered variant renders the filtered text with the same effects,
  so filtering can never be dodged by decorating a line.
- **Markup is the source of truth**: the sign edit screen builds its four editable
  strings from `Component.getString()` of the stored components — styling, fonts and
  click events are invisible to it — and sends back plain text. So a decorated sign
  re-edited by hand would flatten. Ticket 03 gives `SignBlockEntity` a mixin-owned
  `String[4]` per face, persisted under a namespaced key in
  `saveAdditional`/`loadAdditional` (26.2 uses `ValueOutput`/`ValueInput` with
  `SignText.DIRECT_CODEC`), and on every edit compares each submitted line against
  `render(storedSource).getString()`: equal means the player did not touch that line,
  so the stored markup is re-rendered; different means the submission is the new
  source. That is what makes story 8 work.
- **Rendering**: the parser is pure — `String -> Component` — and lives in
  `eu.mctraveler.text` beside `Paint`, with no Minecraft server state. Spans carry a
  style; only gradient, rainbow and the animated effects explode to one component per
  character. `Style` in 26.2 takes `withColor(Int)`/`TextColor.fromRgb`,
  `withObfuscated(Boolean)`, and fonts through `FontDescription.Resource(Identifier)`
  — not a bare `ResourceLocation`. Vanilla ships four fonts: `minecraft:default`,
  `minecraft:alt` (the SGA the enchanting table draws), `minecraft:illageralt`, and
  `minecraft:uniform`. `<enchant>` is `alt` plus obfuscated; `<sga>` is `alt` alone.
- **Paint stays what it is**: `Paint` is the chat vocabulary — fixed colors,
  decorations, the `error`/`success`/`usage`/`info`/`warning` prefixes. Sign markup is
  a parser with a different job and gets its own file; it may reuse `Paint`'s color
  table, not its API shape.
- **Who may use what**: no permission library exists here and none is being added —
  vanilla `/op` remains the mechanism, checked in-body with
  `RegionsFeature.isAdmin` where a security gate is needed. Every effect is open to
  everyone by default; the config tunes balance rather than assigning a fixed
  admin-only set. Click actions are the security exception: their tags are admin-only
  and off by default, because they can cause server-side actions on waxed signs.
- **Configuration**: ticket 05 adds the first config file now:
  `mctraveler/signs.json`, read through `PersistenceService`'s directory. It holds
  the effect policy, per-line component cap, and animation budget, with a
  `/signfx reload`. Effects start open to everyone; admins tune their balance in this
  file. The click-action policy remains admin-only and off by default as a security
  boundary, not a balance setting.
- **Cost cap**: a line refuses to render (and says so) past a configured number of
  styled pieces — 96 by default against vanilla's 384-character line limit. Signs are
  90 pixels wide; nothing legible comes close to the cap.
- **Animation never persists**: the animator mutates the in-memory `SignText` and
  sends `ClientboundBlockEntityDataPacket` to tracked players without `setChanged`,
  and — because the markup is the source of truth — the save path re-renders the base
  frame from the stored source, so a chunk saved mid-animation writes the resting
  sign. Only signs whose chunk is tracked by a player animate.
- **Click actions are a separate, admin-only tag**: 26.2 dispatches `run_command`,
  `show_dialog` and `custom` click events from waxed sign text
  (`SignBlockEntity.executeClickCommandsIfPresent`, called from
  `SignBlock.useWithoutItem`); `open_url` is not dispatched server-side. A
  `<cmd:/warp spawn>` tag is therefore possible and is exactly the kind of thing a
  non-admin must not be able to write. Ticket 06, off by default.

## Feature Ideas

Beyond what the tickets below build, in rough order of appetite:

- **Presets** — `<preset:shop>` expanding to a house style, so an embassy row looks
  like an embassy row without every player retyping a gradient.
- **Placeholders** — `%player%`, `%online%`, `%time%`, `%region%`, `%world%` refreshed
  on the animator's tick; a warp board that counts players.
- **A formatting brush** — copy the markup of the sign you are looking at, paste it
  onto another, with an item in hand rather than a command.
- **Region-scoped policy** — restricted effects allowed to region members inside their
  own region, so a shop owner can decorate their shop without being an op.
- **Observer integration** — a `custom` click action on a waxed sign posting through
  the Conduit HTTP API into Discord: a suggestion box, an application form, a bell.
- **Chat parity** — the same markup, same parser, allowed in `/notepad` books or in
  region titles once it has proven itself on signs.

## Testing Decisions

The parser is pure and gets the bulk of the coverage as JUnit tests under
`src/test/kotlin/eu/mctraveler/text/` — a table of markup strings against expected
component trees, plus the malformed cases from story 6 and the cap from story 15.
Behavior lives in GameTests (`src/gametest/kotlin/eu/mctraveler/gametest/`) driving
`connection.handleSignUpdate(ServerboundSignUpdatePacket(...))` the way
`RegionProtectionGameTest` already does, and reading back
`sign.frontText.getMessage(i, false)`: an effect renders; the raw/filtered pair both
render; a re-edit of one line preserves the other three; region protection still
refuses a foreign sign; a waxed sign still refuses; ordinary effects work for a
non-op while click actions remain gated; the cap refuses a hostile line. Every
new GameTest class registers in `src/gametest/resources/fabric.mod.json`, and
`GameTestJanitor` learns about `signs.json`.

## Out of Scope

- Any client mod, resource pack, or custom font. If vanilla cannot draw it, it is not
  in this feature.
- Rewriting `Paint` or routing chat through the new parser (see Feature Ideas).
- Signs written by datapack, `/data merge`, or `/setblock` — those already carry
  components and are left exactly as authored.
- Item names, lore, books, or anything that is not a sign block entity.
- Migrating existing signs on the live save; effects apply to edits made from here on.

## Open Questions for Triage

- Whether the cap is a parameter of the pure parse call or a policy check applied
  around it — ticket 01 is pure and ticket 05 owns the number.
- `/signfx source` and `/signfx clear`: how a face is selected (looked-at block plus
  the face you are facing, presumably) and whether either needs more than the region
  write permission the player already has for that sign.
- Whether `<enchant>` is defined as "SGA font plus obfuscated" or as the font alone
  with obfuscation left to `<obf>`, and what the enchanting-table churn should feel
  like at rest.
- What a sign item, structure block, or piston copy should carry: the rendered text
  only, or the markup with it.
- The first set of animated effects and their frame semantics, and the grammar for
  `show_dialog` / `custom` click payloads (ticket 06 can land with `run_command` only).
- Whether malformed-markup feedback goes to chat only, or also leaves the line visibly
  marked so the author notices later.

## Further Notes

- The checked-in build targets Minecraft **26.2** (`gradle.properties`), Java 25,
  Kotlin 2.4.10. All API facts above were read from the 26.2 jars, not from legacy
  1.21 memory.
- `HangingSignBlockEntity` extends `SignBlockEntity`, so hanging signs come free.
- Custom NBT written in `saveAdditional` also rides `getUpdateTag` to clients. The
  source strings are small and harmless there, but ticket 03 should confirm the
  behavior rather than assume it.
