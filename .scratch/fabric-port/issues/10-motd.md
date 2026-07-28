# 10 — MOTD

**What to build:** The server-list presence: the Portal's exact two-line MOTD (bolded MCTraveler in the play-address line, the "Celebrating 13 years of vanilla survival" line, exact colors and spacing), live player count and up-to-12-player sample, and a real server icon supported via the standard mechanism.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** ready-for-agent

See `../spec.md` (User Story 1) and the MotdFeature section of `docs/research/portal-feature-inventory.md` for exact text.

- [ ] Status response carries both MOTD lines with exact text, colors, and bolding
- [ ] Player count and sample (first 12 names) reflect live state
- [ ] Standard server-icon file is served when present (the Portal's favicon was a broken placeholder — intent parity)
- [ ] Test asserts the status payload content
