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
267 gametests and the unit tier.

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

1. **A crystal aimed at a chest opens the chest.** Nucleus cancelled the whole
   interaction from Bukkit's `PlayerInteractEvent` and so always got the menu.
   Vanilla runs a block's own right-click first and stops there when it consumes
   the click, so `ItemEvents.USE_ON` — the seam this ticket specified — never
   fires for an interactive block. Air, ground and sneaking are unaffected.
   Pinned by `aCrystalAimedAtAChestOpensTheChest`, which says what would have to
   change (cancelling the block's own use, which needs a mixin on
   `ServerPlayerGameMode.useItemOn`) if the other behaviour is wanted.
   **Worth a register entry or a decision.**
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
11. **`CrystalRequests` carries two test seams**, `clear()` and
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
- **Pre-existing, not from this ticket:**
  `.scratch/embassy-crystal/issues/03-crystal-item-energy.md` is on `main` with
  12 unresolved merge-conflict markers left behind by merge commit `f0ccdd2`, so
  that ticket's Status line and checklist each appear twice and contradict
  themselves. Left alone here rather than editing another ticket's record;
  raised separately.
- **Nothing new is persisted**, so `GameTestJanitor` is unchanged: menus are
  in-memory and requests are dropped on disconnect and on server stop.
