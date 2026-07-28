# 11 — Notepad

**What to build:** The private cross-World notebook: `/notepad` opens the player's pages as an editable book (a real server-side edit session now, not a faked item), saving persists and confirms, and the Portal's session guards and messages are preserved.

**Blocked by:** 01 (Scaffold), 02 (Text DSL), 03 (Persistence store).

**Status:** ready-for-agent

See `../spec.md` (User Stories 25–27) and the NotepadFeature section of `docs/research/portal-feature-inventory.md` for exact messages and the stored page format (which migrated data arrives in).

- [ ] `/notepad` opens an editable book seeded with the player's saved pages, or the Portal's exact welcome page for first-timers
- [ ] Saving persists the pages in the Portal's stored format and replies `SUCCESS Notepad saved`; a failed save replies the exact error
- [ ] `/notepad` while already editing replies the exact already-editing message
- [ ] Switching held item or clicking the inventory cancels the session with the exact cancellation error and leaves the real inventory untouched
- [ ] Pages survive Travel and server restarts
- [ ] Gametests: open/edit/save flow, guards, persistence across restart
