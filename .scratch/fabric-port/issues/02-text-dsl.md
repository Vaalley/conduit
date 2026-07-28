# 02 — Text DSL (Paint equivalent)

**What to build:** The server's message design language as a small Kotlin DSL over native text components: the Portal's exact color vocabulary (green, gray, white, yellow, red, blue, darkGray, reset), decorations (bold, italic, underline), nesting, and the ERROR / SUCCESS / USAGE prefixed helpers. Every later ticket's player-facing text is built with this.

**Blocked by:** 01 (Scaffold).

**Status:** done

See `../spec.md` (Implementation Decisions: Text) and the command-framework section of `docs/research/portal-feature-inventory.md` for the exact Paint semantics being reproduced.

- [x] DSL produces components matching the Portal's Paint output semantics: single-part and multi-part nesting, parent style re-applied after styled children
- [x] `error(...)` renders red+bold "ERROR" + gray content; `success(...)` green+bold "SUCCESS" + gray content; usage helper matches the Portal's USAGE styling
- [x] Unit tests (loader-junit) assert generated components for every color/decoration, nesting, and the semantic helpers — mined from the Portal's paint tests

## Comments

Implemented as `eu.mctraveler.text.Paint` (`src/main/kotlin/eu/mctraveler/text/Paint.kt`), tests in `src/test/kotlin/eu/mctraveler/text/PaintTest.kt` (fabric-loader-junit; no registry bootstrap needed — text components are registry-free).

Key API decisions:

- **Chainable immutable brushes, invoke applies content**: `Paint.green("...")`, `Paint.red.bold("...")`, decorations accumulate, a later color wins — mirroring the Portal's `p.green.bold` chains. The Kotlin equivalent of the tagged template is a vararg invoke: `` p.red`This is ${inner} text` `` becomes `Paint.red("This is ", inner, " text")`.
- **Returns native `MutableComponent`** — no wrapper type, no `.toComponent()`; results feed `sendSystemMessage`/Brigadier directly. Content args accept `String`, `Component` (nesting), or any value via `toString()`; `null` and `""` are dropped (Portal parity).
- **Component shape matches the Portal's `toNbtObject`**: zero parts → empty unstyled component; one part → style collapsed onto that part; many parts → unstyled-text root carrying the style with the parts as siblings, so text after a styled child inherits the parent style (the §r-and-reapply semantics, natively).
- **Intent-parity deviations registered in the spec (entries 15–16)**: single-part collapse keeps a nested child's own style (child wins via `Style.applyTo`; the Portal's NBT path clobbered it — a bug); `usage` is a styled component (aqua+bold "USAGE" + " " + gray content, parallel to error/success) rather than the Portal's raw `§b§lUSAGE §7` legacy string, and preserves nested styling in its content.
- **`reset` clears only color** (produces uncolored, inheriting text), as the Portal's NBT mapping did; aqua exists only internally for the USAGE prefix and is not part of the public vocabulary.
- The Portal's `toLegacyString`/`toTerminal`/`toNbtObject` output formats are proxy-era concerns and were deliberately not ported; `Component.getString()` covers unformatted text.
