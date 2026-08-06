# 04 — `/signfx` command family

**What to build:** A Brigadier `/signfx` command family for discovering,
previewing, inspecting, clearing, and repairing sign markup. `/signfx help`
must show rendered examples, `/signfx preview <markup>` must render a chat
preview, `/signfx source` must print the source of the sign being looked at,
and `/signfx clear` must strip it back to plain text. Add `/signfx reload` for
the configuration file from ticket 05. Admin-only subcommands use in-body
gating, and all player-facing output uses the existing `Paint` vocabulary.

**Blocked by:** 02.

**Status:** needs-triage

See ../spec.md (User Stories 9–11 and 13; Implementation Decisions "Who may use
what", "Configuration", and "Paint stays what it is").

- [ ] `/signfx` registers through Brigadier from the sign feature module
- [ ] `/signfx help` lists the supported tags and shows a rendered example for
      each relevant effect
- [ ] `/signfx preview <markup>` parses the source and displays the rendered
      result in chat, including a useful malformed-input report
- [ ] `/signfx source` reports the stored markup for the sign the player is
      looking at, for the selected face
- [ ] `/signfx clear` replaces the selected face with plain text and removes
      its stored markup
- [ ] `/signfx reload` reloads the documented policy without restarting the
      server
- [ ] Admin-only subcommands gate inside their command bodies using the house
      vanilla-op rule rather than hiding the command tree with `.requires(...)`
- [ ] Success, usage, error, preview, and permission output uses `Paint`
      helpers and follows existing command voice
- [ ] Commands refuse non-sign targets and missing or unloaded targets with
      clear player-facing feedback
- [ ] Unit or GameTest coverage proves help examples, preview, source, clear,
      malformed input, and admin gating
