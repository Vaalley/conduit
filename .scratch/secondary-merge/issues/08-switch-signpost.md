# 08 — `/switch` becomes a signpost

**What to build:** The merge is cold — players are told nothing beforehand and everything
afterwards — so the first thing a returning player does is the thing they have always done,
and that has to be the thing that explains what happened. `/switch` stops travelling and
starts answering.

It tells them the Worlds have merged and there is one map now; where they are standing;
where their other base is, if they had one, in the merged map's own coordinates; and that
Bed and Spawn on their Teleportation Crystal work exactly as they always did. That last
line matters more than it looks: it is the difference between a player who thinks they are
stranded and one who knows they have a way home.

This is the expand half of retiring the Worlds subsystem. Travel still exists underneath
after this ticket; nothing is deleted yet. The command simply stops using it.

**Blocked by:** 06 — Moving the players.

**Status:** done

- [x] `/switch` no longer moves the player anywhere
- [x] It states that the Worlds have merged into one map
- [x] It reports the player's current position
- [x] It reports where the player's other base is, in merged coordinates, when the merge
      recorded one for them
- [x] It says nothing about another base for a player who never had one, rather than saying
      something empty
- [x] It confirms that the crystal's Bed and Spawn destinations are unchanged
- [x] It works for a player who joined after the merge and has no record at all
- [x] The wording is checked by test, in the way the repo pins player-facing text elsewhere
- [x] Nothing else about Travel, the Per-World Bucket or the Worlds service is removed in
      this ticket

## Comments

### What `/switch` prints

For a player whose other base the merge recorded (the header's brackets dark gray, its
title green and bold; body gray, coordinates white, place names green, the two crystal
destinations aqua):

```
--[ One World ]--
Primary and Secondary have merged into one map. There is nowhere left to switch to — Secondary is somewhere you can walk to now.
You are at 100/64/-201 in the Overworld.
Your other base — where you last stood in Secondary — is now at 1024/70/-513 in the Nether.
Bed and Spawn on your Teleportation Crystal still work exactly as they always did.
```

A player the merge recorded no banked position for, and a player who joined after the merge
and is named nowhere in the artifact, get the same message without the fourth line. There is
no third spelling: no artifact at all — the state every unmerged server is in — is the same
answer as an artifact that does not name you.

### Implementation summary

- `src/main/kotlin/eu/mctraveler/worlds/SwitchCommand.kt` — the signpost. The command no
  longer touches `Worlds` at all, so ticket 09 can delete that service without coming back
  here.
- `src/main/kotlin/eu/mctraveler/worlds/BankedPositions.kt` — the reader for
  `mctraveler/banked-positions.json`, plus `OtherBase`, the one entry it hands back.
- `src/main/kotlin/eu/mctraveler/worlds/WorldsFeature.kt` — binds the artifact's path at
  server start and hands `/switch` a lambda to it, as it already did for `Worlds`.
- `src/main/kotlin/eu/mctraveler/importer/PlayerSweep.kt` — one line: the artifact's file
  name now comes from `BankedPositions.FILE_NAME` rather than being spelled a second time.
- `src/test/kotlin/eu/mctraveler/worlds/BankedPositionsTest.kt` (8 tests) and
  `src/gametest/kotlin/eu/mctraveler/gametest/SwitchSignpostGameTest.kt` (2 tests).

### Judgement calls

1. **The artifact is cached against the file's size and modification time**, not re-parsed
   per command and not read once and frozen. Thirteen thousand players typing `/switch` in
   the same ten minutes cannot each pay for a parse on the server thread; equally, an
   operator who has to repair the file should not have to restart the server to make it
   count. The stamp check costs one stat call and made the gametest able to drive the real
   command against a real artifact instead of a seam built for it.
2. **A file that will not parse is logged and treated as absent.** The signpost is the one
   thing that must never be the reason a player cannot be answered, and the operator has
   both the log line and the merge's own report; refusing to start would be the more
   expensive failure.
3. **Only the vanilla trio gets a name** — "the Overworld", "the Nether", "the End".
   Anything else is printed as its own dimension id, which is the stance the Region layer
   already takes on a dimension outside every World. Until ticket 09 lands, a player
   standing in Secondary sees `mctraveler:secondary`; after it, there is nothing left that
   can reach that branch except the Embassies.
4. **The message says the Worlds have merged whether or not this server has been merged.**
   There is no window where that is wrong: the jar carrying this change is deployed in the
   same downtime as `mergeWorlds`. The artifact's absence is not evidence either way — it
   is also what a merge in which nobody kept two bases writes.

### The boundary ticket 09 inherits

`/switch` was how four gametest suites made a player Travel, and `Worlds.travel` still
exists, so those cases now call it directly through a new
`ServerPlayer.travelToTheOtherWorld()` in `TestPlayers.kt`. Every assertion in them is
unchanged — `RespawnAndPortalsGameTest` (7 call sites), `MigrationGameTest`,
`OrphanedSaveClaimGameTest`, and three of `WorldsGameTest`'s cases. They go when Travel
goes.

The one case that could not survive is `WorldsGameTest.switchTogglesWorldsWithThePortalsExactMessages`.
Its two assertions on the Portal's `Switching to <green World>...` line were assertions on a
message that no longer exists anywhere, and they are gone; its two assertions on the World
toggle are kept, and the case is now `travelTogglesBetweenTheTwoWorlds`. Nothing was
weakened to get green — the removed assertions describe removed behaviour, and the new
wording is pinned harder than the old was, as a whole `Component` rather than as text runs.
