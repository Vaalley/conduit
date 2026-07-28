# 02 — Text DSL (Paint equivalent)

**What to build:** The server's message design language as a small Kotlin DSL over native text components: the Portal's exact color vocabulary (green, gray, white, yellow, red, blue, darkGray, reset), decorations (bold, italic, underline), nesting, and the ERROR / SUCCESS / USAGE prefixed helpers. Every later ticket's player-facing text is built with this.

**Blocked by:** 01 (Scaffold).

**Status:** ready-for-agent

See `../spec.md` (Implementation Decisions: Text) and the command-framework section of `docs/research/portal-feature-inventory.md` for the exact Paint semantics being reproduced.

- [ ] DSL produces components matching the Portal's Paint output semantics: single-part and multi-part nesting, parent style re-applied after styled children
- [ ] `error(...)` renders red+bold "ERROR" + gray content; `success(...)` green+bold "SUCCESS" + gray content; usage helper matches the Portal's USAGE styling
- [ ] Unit tests (loader-junit) assert generated components for every color/decoration, nesting, and the semantic helpers — mined from the Portal's paint tests
