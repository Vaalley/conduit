# 13 — Region membership + scoreboard sidebar

**What to build:** Region community features: `/rg add` and `/rg remove` with the Portal's caps, guards, suggestions and messages, and the live sidebar scoreboard that appears when standing in a region.

**Blocked by:** 12 (Region core).

**Status:** done

See `../spec.md` (User Stories 31, 33) and the RegionFeature scoreboard/membership subsections of `docs/research/portal-feature-inventory.md` for exact layout and messages. Member names resolve via the name cache (ticket 03).

- [x] `/rg add`: stand-in-region requirement, 99-member cap, resident/admin/parent-resident permission, duplicate error, success message — all exact
- [x] `/rg remove`: tab-completion suggests current region members by typed prefix; not-a-member and only-member guards; success message — all exact
- [x] Sidebar shows on region entry (unless NO_SCOREBOARD) and hides on exit: green bold title truncated to 20, bold Residents count, strikethrough separator, member list (self white, others gray, hidden score numbers)
- [x] Scoreboard updates live for everyone inside on add/remove/rename; moving between adjacent regions swaps cleanly
- [x] Gametests: membership flows and errors, scoreboard content and lifecycle

## Comments

Implemented in `eu.mctraveler.region` (branch `worktree-agent-a4896460932417f1a`). `./gradlew build` green: unit tier plus 125 headless gametests (96 pre-existing, 29 new across `RegionMembershipGameTest` and `RegionScoreboardGameTest`).

**Scoreboard mechanism — per-player synthetic packets (`RegionScoreboard`).**
The sidebar is drawn with `ClientboundSetObjective` / `SetScore` / `ResetScore` / `SetDisplayObjective` packets addressed to one player's connection; the server's shared `ServerScoreboard` is never touched. It has to be per-player — the board names one region, only while its occupant stands inside, with the reader's own name in white and everyone else's in gray — and going straight to the connection also leaves vanilla `/scoreboard` completely alone. The packets are shaped from a detached `Objective` (a throwaway `Scoreboard` instance that is never registered anywhere).

The objective (`region`, sidebar slot) is created **once per session**: a client rejects a second objective of the same name. The Portal's remove-then-create-per-connection trick existed only to survive the client-side scoreboard reset a backend server switch caused; one server means one connection, so one create — asserted by `theObjectiveIsOnlyEverCreatedOncePerSession`. `RegionScoreboard` remembers which rows are on each player's board, so a redraw retracts exactly the rows that went away; that one operation covers entry, membership change, rename, and the "switching regions removes only members not in the new region" rule.

**Tracker seam for ticket 14 (`RegionTracker`).**
Region entry/exit is a per-tick sweep: `END_SERVER_TICK` calls `refresh(player)` for every online player, which recomputes the region from the live position and reconciles the sidebar. Ticket 14 extends it at two points, neither of which requires touching `RegionScoreboard`:

- `RegionTracker.refresh(player)` — idempotent, cheap, safe to call anywhere. Call it from the teleport / portal / respawn / dimension-change hooks so an arrival is noticed in the same tick instead of up to one tick later (`/rg end` already calls it, so a creator's board appears immediately). The per-tick sweep stays underneath as the backstop that makes every other kind of arrival correct by construction, including a region appearing around a standing player.
- `RegionTracker.regionOf(player)` — the live "which region is this player in" lookup that every region command now shares; the natural place for 14's `canModifyRegion` (resident-or-PUBLIC) predicate to read from. `redraw(server, region)` and `clear(server, region)` push a changed or vanished region to everyone standing in it.

Ticket 14/15 also owe the protection re-evaluation the Portal did inside `/rg add` and `/rg remove` ("re-evaluates the added/removed player's adventure-mode state", inventory §2.8): the membership commands here deliberately only save and redraw.

**Deviations / interpretations recorded here:**

- **NO_SCOREBOARD now hides an existing board.** The Portal only skipped *showing* the sidebar in a `NO_SCOREBOARD` region; walking in from an ordinary neighbour left the previous board on screen, retitled and repopulated with the quiet region's members. Entering such a region now clears the board, which is what the flag plainly means.
- **Toggling `NO_SCOREBOARD` takes effect immediately** for everyone standing in the region, rather than the next time they walk in (`/rg flag` redraws). The Portal never refreshed on a flag toggle.
- **Membership changes redraw the whole board** instead of the Portal's three-packet patch, which re-sent the added member with score `0` — colliding with the first member's score and leaving the two lines' order to the client. A redraw produces exactly the content a fresh entry would, and removals no longer leave a gap in the ordering.
- `Paint` gained `strikethrough`. The Portal's Paint had no such decoration, so its one struck-through string — this sidebar's 30-space separator — was hand-built as raw NBT; the DSL is the repo's rule for all text, so the decoration belongs in it.
- `/rg remove` echoes the name **as typed** in its success and error messages (`/rg remove STEVE` answers "STEVE has been removed"), exactly as the Portal did — only the lookup is case-insensitive.
- `/rg add`'s target is resolved before any region guard, because the Portal's `onlinePlayer` argument parser ran before the command body; an unknown name therefore answers `Player <red name> not found or is offline` even when the sender is standing nowhere near a region.
- Name resolution for member lists, `/rg remove` and `/rg locate` is now one shared `RegionsFeature.usernameFor(server, uuid)` — online player first, then the real name cache (deviation 10). A member nothing can name is still skipped everywhere, as in the Portal.
- Gametest harness: `PacketCapture.drain` now flushes the channel first. The server flushes each connection once per tick, so without it a drain taken immediately after an action saw nothing that action had sent. Region gametests also keep every coordinate within a few blocks of their structure — the batch lays structures out only ~15 blocks apart, and a 20-block walk put a player inside a neighbouring test's region.
