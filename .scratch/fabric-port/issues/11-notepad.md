# 11 — Notepad

**What to build:** The private cross-World notebook: `/notepad` opens the player's pages as an editable book (a real server-side edit session now, not a faked item), saving persists and confirms, and the Portal's session guards and messages are preserved.

**Blocked by:** 01 (Scaffold), 02 (Text DSL), 03 (Persistence store).

**Status:** done

See `../spec.md` (User Stories 25–27) and the NotepadFeature section of `docs/research/portal-feature-inventory.md` for exact messages and the stored page format (which migrated data arrives in).

- [x] `/notepad` opens an editable book seeded with the player's saved pages, or the Portal's exact welcome page for first-timers
- [x] Saving persists the pages in the Portal's stored format and replies `SUCCESS Notepad saved`; a failed save replies the exact error
- [x] `/notepad` while already editing replies the exact already-editing message
- [x] Switching held item or clicking the inventory cancels the session with the exact cancellation error and leaves the real inventory untouched
- [x] Pages survive Travel and server restarts
- [x] Gametests: open/edit/save flow, guards, persistence across restart

## Comments

**Edit-session mechanism.** `/notepad` places a *real* writable book (named
"Click to edit your notepad", carrying the pages, plus a hidden custom-data
marker) into the player's held hotbar slot and keeps the displaced stack in the
session (`eu.mctraveler.notepad.NotepadFeature`). A Java mixin on
`ServerGamePacketListenerImpl` supplies the packet hooks: `handleEditBook` is
consumed while a session is live (it arrives on the netty thread; the save hops
to the server thread) — the pages go to the player store, never onto the item —
and `handleSetCarriedItem` / `handleContainerClick` cancel at HEAD on the
server-thread pass, so the slot is restored *before* vanilla applies the packet
and the click/switch lands on the real inventory.

**Guards beyond the Portal's two** (new here because the stand-in book is now a
real server-side item that must never escape):
- Dropping (Q) and offhand-swapping (F) also cancel, with the same cancellation
  error — vanilla then acts on the restored original.
- Death restores the original *before* death loot drops (silently); logout and
  server stop restore silently too.
- An end-of-tick sweep cancels if the book leaves its slot through any unhooked
  path, and every session end sweeps all marked books from the inventory
  (covers creative cloning).
- Join sweeps marked strays out of loaded playerdata (crash recovery). Known
  limitation: a hard crash between an autosave and session end loses the
  displaced original (held only in memory); the sweep still removes the book on
  next login. Seconds-scale window, accepted.
The specced triggers and all message texts are exactly the Portal's.

**Failed save.** The Portal's parse-failure reply maps to the store refusing the
write (e.g. an unparseable existing player record): exact `ERROR Failed to save
notepad`, and the session still ends with the slot restored (the Portal resynced
in every outcome too).

**Travel/restart.** Pages live in the shared `players/<uuid>.json` store —
nothing is per-World, so Travel cannot affect them (no Travel service exists on
this branch to test against yet). Restart durability is gametested by reading
back through a fresh `PersistenceService` over the server directory.

**Test infrastructure.** Gametests drive a fake player joined through the real
`PlayerList.placeNewPlayer` pipeline over an in-memory netty channel, asserting
on the actual clientbound system-chat packets. The helper sends the vanilla
"player loaded" ack after joining — without it the server keeps the player
invulnerable and `kill()` no-ops (relevant to the death gametest).
