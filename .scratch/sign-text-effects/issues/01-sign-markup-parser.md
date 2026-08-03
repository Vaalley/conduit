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

**Status:** done

See ../spec.md (User Stories 1–6 and 15–16; Implementation Decisions
"Rendering", "Cost cap", and "Paint stays what it is").

- [x] The parser has a small, documented pure API that accepts a source string
      and returns a `Component`, with no server or player dependencies
- [x] Named colors, `<#rrggbb>` colors, bold, italic, underline, strikethrough,
      obfuscated, and their supported legacy `&` codes produce the expected
      component styles
- [x] Nested tags, closing tags, `<reset>`, and backslash escapes for literal
      `<` and `&` behave consistently
- [x] `<gradient:...>` accepts two or more color stops and expands the content
      per character across those stops
- [x] `<rainbow>` expands its content per character into a rainbow of styles
- [x] `<sga>` uses the vanilla `minecraft:alt` font, `<illager>` uses
      `minecraft:illageralt`, and `<enchant>` combines the SGA font with
      obfuscation through `FontDescription.Resource`
- [x] Malformed markup keeps the text visible, renders it plainly with the
      markup shown literally, and returns or exposes a useful error for chat
      reporting without refusing the sign edit
- [x] Expansion stops at the configured component cap and reports that the
      requested effect exceeded the cap
- [x] The render-to-plain helper returns the readable text independent of
      obfuscation or font choice
- [x] JUnit tests cover ordinary tags, legacy codes, hex colors, nesting,
      escaping, gradients, rainbow, fonts, malformed input, plain rendering,
      and the component cap
- [x] No feature registration, command wiring, mixin, or GameTest is added

## Comments

### Implemented

`eu.mctraveler.text.SignMarkup` now owns the pure parser. Its public surface is:

- `SignMarkup.render(source, limits): SignMarkupResult`, carrying the rendered
  `Component`, `SignMarkupProblem` diagnostics, and the `hadMarkup` fast-path flag
- `SignMarkup.strip(source): String`, sharing the parser's readable-text result
- `SignMarkupLimits(maxComponentsPerLine)`, with `DEFAULT` set to 96
- `SignMarkupProblem(message, position)` and `SignMarkupResult`

The parser handles nested tags, legacy codes, hex and named colors, decorations,
font descriptions, escapes, gradients, rainbow, malformed-input recovery, and
the component cap. Ticket 02 can use `hadMarkup` to leave ordinary vanilla lines
alone and use the result diagnostics for later chat feedback. Gradients use
straight-line sRGB interpolation; rainbow uses a full-saturation, full-value HSV
cycle.

JUnit coverage lives in `SignMarkupTest` and asserts only the public parser
surface. No feature wiring, mixin, config, or GameTest was added.

### Reverified

Effect colors now continue across nested decoration spans instead of restarting
at each style boundary. Reported malformed closing tags remain visible, so
parser diagnostics never discard player-entered text.
