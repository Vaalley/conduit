# 04 — Crystal menu, destinations, teleport requests

**What to build:** The chest-GUI flow: right-click with a crystal opens the
27-slot "Where would you like to go?" menu (exact layout), the five destinations
(Bed / Spawn / Player / Embassy / Wilderness), the "Select a player" head GUI, the
teleport-request flow with the hidden `/teleportation-crystal-accept` command, the
region-protection exemptions for crystal use and mod-owned menus, and menu/request
lifecycle cleanup.

**Blocked by:** 03 (crystal item + energy), 01 (embassies dimension, for the Embassy
destination and its test).

**Status:** done

See ../spec.md (User Stories 26–36; Implementation Decisions "GUI", "Commands";
deviations 4, 8, 13, 16) and the Nucleus source `teleportation-crystal.kt`
(`menuActions`, `createTeleportationCrystalInventory`, `createPlayersInventory`,
`useTeleportationCrystal`, `TeleportationCrystalListener`,
`cleanUpTeleportationCrystal`) in the reference clone at
/Users/jam/Development/MCTravelerNucleus.

- [x] Right-click air or block with a crystal opens the menu per stories 26–27,
      anywhere — including regions the player cannot modify (deviation 13); the
      vanilla use of the shard is cancelled
- [x] Menu layout and item presentation exactly per story 26; all clicks cancelled;
      only slots 11–15 act (Nucleus's slot window quirk: slot 16 is accepted but maps
      to no action)
- [x] Destination flow per stories 28–32 (energy cost, INFO message with aqua
      lowercase name, Bed/Spawn/Embassy coordinates and failures exact)
- [x] Player flow per stories 33–34; request expiry after 6000 ticks (deviation 4);
      `/teleportation-crystal-accept` per story 35, absent from tab completion
      (deviation 8)
- [x] Region container mixins skip mod-owned menus (deviation 16); notepad session
      interplay unaffected
- [x] Lifecycle per story 36 (close/disconnect clear state; SERVER_STOPPING closes
      open crystal GUIs)
- [x] Gametests: full menu open/click paths for every destination, both refusal
      gates, the request round-trip (send, accept, timeout, offline target), packet
      assertions for the GUIs; the Embassy destination lands at (0.5, 1.0, 0.5) and
      records an origin

## Comments

### Implementation summary

Done on branch `worktree-agent-a9c14c327f92a9630`. Full `./gradlew build` green:
270 gametests and the unit tier.

Two new files in `eu.mctraveler.crystal`:

- `CrystalMenu` — the two GUIs, the five destinations, the refusal gates, and
  `CrystalChestMenu`, the `ChestMenu` subclass both GUIs are.
- `CrystalRequests` — outstanding teleport requests, the invitation message, and
  the accept command's rules.

One new Java mixin, `CrystalAcceptCommandMixin` (a thin shim, listed in
`mctraveler.mixins.json`), and small changes to `CrystalFeature` (the
right-click and the lifecycle), `CrystalItem` (deviation 18), `RegionProtection`
(two new exemption seams), `Paint` (aqua made public), and the two region
container mixins.

### Three decisions worth the reviewer's time

**The menu is a `ChestMenu` subclass, not a click-cancelling mixin.** That puts
both of the things that make it ours in one place: every click is swallowed by
*not* delegating to `super.clicked` — which covers pick-up, shift-click, hotbar
swap, throw, clone and drag without enumerating them — and the type itself is
the marker region protection reads for deviation 16. `quickMoveStack` is
overridden to empty as well, so nothing moves even if some future path asks it
directly.

**The chosen destination runs on the server's task queue, not inside the
click.** `ServerGamePacketListenerImpl.handleContainerClick` re-reads
`player.containerMenu` at every step rather than holding a local (verified in
the bytecode), so closing the menu — or opening the head GUI — inside the click
hands vanilla's own post-click bookkeeping the wrong menu to apply it to. Worth
knowing: `MinecraftServer.execute` only *enqueues* when
`runningTask() || !isSameThread()`, so it defers during real packet handling
(which runs inside a task) and runs inline when a gametest calls
`menu.clicked(...)` directly. Both are correct here; the tests allow a tick
either way.

That queue opened a hole Nucleus did not have, found in review and fixed:
**two clicks in one tick both reached the queue before either had closed
anything**, so a double-click teleported twice for two energy (and, on a head,
sent two requests for two). Nucleus's synchronous `closeInventory()` cleared
its session before the second click was handled. Here the menu no longer being
the player's *is* that session having ended, so a queued action checks
`player.containerMenu === this` before running. `aDoubleClickSpendsOneEnergyAndTeleportsOnce`
was confirmed to fail without the guard (two energy, not one).

**There is no per-player menu state.** Nucleus kept two `WeakHashMap`s of who
had which GUI open and swept them on close and on quit. Here the open menu *is*
that state (`CrystalMenu.openMenuOf`), so story 36's lifecycle is vanilla's
container lifecycle, there is nothing to leak, and the "you are already in a
teleportation crystal" gate cannot go stale. Only the requests are book-keeping
of our own, and those are dropped on disconnect.

Region protection grew two seams — `exemptItem` and `exemptMenu` — rather than
the crystal relying on being registered after it. Listener order is invisible at
the point it matters, and one reordering of `MCTraveler.onInitialize` would
quietly take the crystal away from everyone standing on someone else's land.
The exemption is applied on both item-use paths, because the block path is
guarded by `allowsBlockChange` (the building rule) and the air path by
`allowsItemUse`.

### Deviations and judgement calls

1. **The crystal wins the click against an interactive block — no deviation.**
   The block right-click hangs off `UseBlockCallback`, not the narrower
   `ItemEvents.USE_ON` the ticket named. Vanilla's `useItemOn` runs the *block's*
   behaviour first (`useItemOn`, then `useWithoutItem`) and only reaches the
   item's own `useOn` if neither consumed the click, so on `ItemEvents.USE_ON` a
   crystal aimed at a chest opened the chest and the menu never appeared.
   `UseBlockCallback` is ahead of all of it, which is exactly where Nucleus
   stood — it cancelled Bukkit's `PlayerInteractEvent` before the chest could
   open. Returning anything but `PASS` cancels the block interaction outright.
   `aCrystalAimedAtAChestOpensTheMenuNotTheChest` asserts the parity: the menu
   opens, the chest does not (checked by its diamond not appearing in the open
   window), and nothing is spent until a destination is chosen.
   **Only the hand the click arrived on is considered**, which is Nucleus's
   `e.item` — its interact event fired per hand and read that hand's item. The
   visible consequence is that a crystal in the *off* hand with an empty main
   hand loses to an interactive block, because vanilla resolves the empty main
   hand against the block first and never asks the off hand; Nucleus behaved the
   same way. A crystal in the off hand against air, ground or a non-interactive
   block does open the menu (`aCrystalInTheOffHandOpensTheMenuToo`).
   Air, ground and sneaking are unchanged, and the region exemptions still hold:
   `UseBlockCallback` carries no region hook of its own, so the block path is
   free on foreign land by construction rather than by exemption.
2. **The head menu is capped at six rows.** Nucleus's row count was
   `ceil(others / 9)` unbounded; past 54 other players that asks for more rows
   than a chest screen has. Capped, taking the first 54 — a real limit on who
   can be picked, which beats a menu whose slot count disagrees with the screen
   type sent to the client. Unit-tested either side of a row and of the cap.
3. **Menu button names use `custom_name`, the crystal's own name uses
   `item_name`.** This is not an inconsistency: Nucleus set the buttons with
   Bukkit's `displayName` (which is `custom_name`, rendered *italic* by vanilla,
   as any renamed item is) and the crystal itself with `itemName`. Matching each
   one keeps what players saw.
4. **`HIDE_ADDITIONAL_TOOLTIP` is now per-component.** 26.2 replaced the blanket
   flag with `TOOLTIP_DISPLAY` naming the component to hide. Of the five icons
   and the heads, only `PROFILE` draws an additional line (the head's owner
   name, which would repeat the name already on the item), so that is what is
   hidden — applied uniformly rather than special-cased.
5. **The invitation's click event sits on the message body, not on the INFO
   prefix.** This is Nucleus's own structure (`INFO_COMPONENT.append(message)`,
   click event on the appended builder), and it is what story 34's "the message
   clickable" means. The prefix was never clickable.
6. **Player lookup by name is case-insensitive.** Nucleus used
   `getPlayerExact`; the house uses `PlayerList.getPlayerByName` everywhere
   (`CrystalCommands`, `RegionCommands`, `PrivateMessages`), and consistency
   inside the mod is worth more than case-sensitivity Nucleus never explained.
7. **Requests are dropped in both directions on disconnect.** Nucleus removed
   only the leaving player's *outgoing* request and leaned on a `WeakHashMap` to
   collect the rest. A plain map has no such collector, and a request whose
   target has gone can never be accepted, so both directions go.
8. **`Paint.aqua` is now public.** It was private, existing only for the USAGE
   and INFO prefixes; stories 28, 34 and 35 make it a content colour.
9. **Bed treats a broken bed as no bed.** Bukkit's `getRespawnLocation()`
   returned null both when no respawn point was set and when the block was gone,
   so both give Nucleus's "You have no bed to go to". Ours gates on
   `respawnConfig == null` *or* the transition reporting `missingRespawnBlock`.
   Resolved with `useSpawnBlock = false` — this is travel, not a respawn, so an
   exhausted respawn anchor must not be spent — and it goes through
   `findRespawnPositionAndUseSpawnBlock`, so ticket 04 gets the Worlds respawn
   routing (`ServerPlayerRespawnMixin`) for free.
10. **Spawn resolves Primary's overworld through the Worlds service** rather
    than assuming `server.overworld()`. They are the same dimension today; the
    indirection is what keeps story 30 true if Primary ever stops being the
    vanilla trio.
11. **One invented string: ERROR "The embassy world is not available".** Story
    31 has no failure branch, because Nucleus had none — it passed a possibly
    null world into `Location` and let the teleport fail. The dimension ships in
    the mod jar and `SmokeHook` asserts it on a real boot, so this is
    unreachable in practice; a player whose click did nothing still deserves to
    be told, which silence would not do. Flagged because it is the one
    player-facing string here with no counterpart in the spec or in Nucleus.
12. **Story 29 resolves the bed in the player's *current* World, deliberately.**
    `findRespawnPositionAndUseSpawnBlock` is rewritten by
    `ServerPlayerRespawnMixin` → `WorldRouting.withinDeathWorld`, so a bed
    standing in another World becomes this World's spawn. That is what story 29
    asks for in as many words ("as vanilla resolves it in my current World") and
    what the ticket meant by "use the Worlds respawn plumbing where it fits".
    A review raised this as a bug on the grounds that the rewrite also drops
    `missingRespawnBlock` and so would charge a player for a broken bed —
    checked, and it does not: `Worlds.spawnTransition` passes
    `template.missingRespawnBlock()` straight through (`Worlds.kt:89`), so the
    free "You have no bed to go to" still fires across Worlds.
13. **`CrystalRequests` carries two test seams**, `clear()` and
    `backdate(ticks)`, both documented as such. The timeout is measured against
    `server.tickCount`, which a test cannot fast-forward; back-dating the
    outstanding request is the least invasive way to reach it, and the decision
    itself is also exposed as the pure `hasTimedOut(createdAt, now)` and
    unit-tested at its boundary (a request *exactly* at the timeout still
    stands, as Nucleus's strict comparison had it).

### Two things the gametest harness taught us

Both are written into the test file, because they will bite the next ticket too.

- **The gametest server broadcasts its own running commentary** — every test
  result and every player any test joins — to every player on the server, and it
  lands in the same capture `MessageCapturingPlayer` keeps. Assertions therefore
  read only the lines carrying a `Paint` prefix (`spokenMessages()`); "the
  player saw exactly one message" is otherwise never true.
- **A test that reaches for every player needs its own environment**, and so its
  own batch — the note ticket 01 left about `returnEveryoneInside` generalises.
  Two here do: `theServerStoppingClosesEveryOpenCrystalMenu` (which was closing
  its neighbours' menus mid-assertion, and cost an hour to find) uses
  `mctraveler-test:sweep`, and `playerRefusesWhenNobodyElseIsOnline` uses
  `mctraveler-test:solo` *and* makes its precondition true by logging everyone
  else out, rather than hoping an earlier batch cleaned up.

### For ticket 05 and the final review

- **Legacy crystals are recognised, so the importer does not have to rewrite
  them** (deviation 18, the open question ticket 03 flagged). `CrystalItem`
  reads both layouts: our own `is-teleportation-crystal` marker and Bukkit's
  `PublicBukkitValues` → `mctravelernucleus:is-teleportation-crystal` /
  `…-tier`, with Nucleus's missing-tier-reads-as-3 preserved. The menu, the
  damage bar and the crafting guard all go through `isCrystal`/`tierOf`, so all
  three treat a Nucleus-era crystal identically. Ticket 05 still only needs to
  import *energy*.
- `CrystalMenu.Head(uuid, name)` and `CrystalRequests.send(player, head)` are
  the seam if anything else ever wants to raise a teleport request.
- **Nothing new is persisted**, so `GameTestJanitor` is unchanged: menus are
  in-memory and requests are dropped on disconnect and on server stop.

### Interactions checked

- **Notepad.** No interaction is possible: an edit session holds a stand-in book
  in the player's selected slot, and `NotepadFeature`'s tick sweep ends the
  session the moment that slot stops holding it — so a player cannot be holding
  a crystal and mid-edit at once. `UseBlockCallback` returns `PASS` for
  everything that is not a crystal, so a session's own right-clicks are
  untouched, and the notepad gametests are unchanged and green.
- **Region protection.** The block path no longer runs through
  `ItemEvents.USE_ON` at all, so `RegionProtection`'s exemption there is now
  belt-and-braces for the crystal (it still governs every other item). The
  air path still needs the exemption on `allowsItemUse`, and both region tests
  — air and block, inside a region the player cannot modify — still pass.

### Known-untested, on purpose

- **Story 36's disconnect clause.** `CrystalRequests.forget` is wired to
  `ServerPlayConnectionEvents.DISCONNECT` but has no test of its own, because
  the clearing has no observable difference from the player simply being gone:
  `accept` looks the requester up by name first and answers "&lt;name&gt; is not
  online" either way, and a rejoining gametest player gets a fresh uuid, so the
  request could never have been found regardless. Testing it would need a read
  seam into the map purely to watch it empty. The close-menu and server-stop
  halves of story 36 are both covered.
- **The `RegionContainerClickMixin` half of deviation 16** is belt-and-braces
  and cannot currently be reached: `CrystalChestMenu.clicked` overrides the very
  method that mixin injects into and never delegates, so the exemption there is
  insurance for a future mod-owned menu that *does* delegate. The reachable
  half — `RegionContainerSessionMixin` not capturing a container-region session
  for a mod-owned menu — is asserted directly by
  `aCrystalMenuCapturesNoContainerSession`.
