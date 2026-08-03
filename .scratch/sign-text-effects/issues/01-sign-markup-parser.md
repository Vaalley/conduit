# 01 — Sign markup parser

**What to build:** A pure Sign Markup parser in `eu.mctraveler.text` that turns a
`String` into a styled `Component`, without server state or feature wiring. It
should support tags and legacy `&` codes, named and hex colors, nesting,
`<reset>`, backslash escaping, per-character gradient and rainbow expansion,
`<sga>`, `<illager>`, and `<enchant>` fonts through
`FontDescription.Resource`. Apply the malformed-input policy from story 6 and
the component cap from story 15, and expose a render-to-plain helper for story
16.

**Blocked by:** none.

**Status:** needs-triage

See ../spec.md (User Stories 1–6 and 15–16; Implementation Decisions
"Rendering", "Cost cap", and "Paint stays what it is").

- [ ] The parser has a small, documented pure API that accepts a source string
      and returns a `Component`, with no server or player dependencies
- [ ] Named colors, `<#rrggbb>` colors, bold, italic, underline, strikethrough,
      obfuscated, and their supported legacy `&` codes produce the expected
      component styles
- [ ] Nested tags, closing tags, `<reset>`, and backslash escapes for literal
      `<` and `&` behave consistently
- [ ] `<gradient:...>` accepts two or more color stops and expands the content
      per character across those stops
- [ ] `<rainbow>` expands its content per character into a rainbow of styles
- [ ] `<sga>` uses the vanilla `minecraft:alt` font, `<illager>` uses
      `minecraft:illageralt`, and `<enchant>` combines the SGA font with
      obfuscation through `FontDescription.Resource`
- [ ] Malformed markup keeps the text visible, renders it plainly with the
      markup shown literally, and returns or exposes a useful error for chat
      reporting without refusing the sign edit
- [ ] Expansion stops at the configured component cap and reports that the
      requested effect exceeded the cap
- [ ] The render-to-plain helper returns the readable text independent of
      obfuscation or font choice
- [ ] JUnit tests cover ordinary tags, legacy codes, hex colors, nesting,
      escaping, gradients, rainbow, fonts, malformed input, plain rendering,
      and the component cap
- [ ] No feature registration, command wiring, mixin, or GameTest is added
