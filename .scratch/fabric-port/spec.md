# Spec: MCTraveler Fabric Port

Status: ready-for-agent

Porting the MCTraveler Portal (TypeScript/Bun Minecraft proxy) to a server-side Fabric mod in Kotlin. Companion documents: the feature-parity inventory and platform research in `docs/research/`, the glossary in `CONTEXT.md`, and ADRs 0001–0002 in `docs/adr/`.

## Problem Statement

MCTraveler runs as a custom proxy in front of two vanilla backend servers. The proxy reimplements the Minecraft protocol by hand, so every game feature is built as fragile packet interception; the two-server topology forces a brittle playerdata sync on every world switch; and the whole stack (proxy + two servers + TUI supervisor) is expensive to operate and slow to evolve. The community's gameplay (chat, regions, notepads, travel between two survival worlds) is loved and must not change, but the platform under it is a dead end.

## Solution

A single Fabric dedicated server (Minecraft 26.2) running one server-side Kotlin mod that reproduces the Portal's player-facing behaviour with Intent Parity. The two backend servers become two Worlds — Primary and Secondary — each a trio of dimensions (overworld, nether, end) on the one server. `/switch` teleports instead of reconnecting. Player state is shared across Worlds except the Per-World Bucket (position, respawn point, current dimension). Existing world saves and Portal data migrate in via a one-time importer. Vanilla clients connect with nothing installed.

## User Stories

### Connecting and presence

1. As a player, I want the server list to show MCTraveler's two-line MOTD (the play address line and the "Celebrating 13 years of vanilla survival" line, exact text and colors) with the live player count and sample, so that the server presents itself as it always has.
2. As a player, I want to join with an unmodified vanilla client, so that nothing changes about how I connect.
3. As a player, I want to log back in to the World I logged out from, at the position I left, so that my sense of place persists across sessions.
4. As a player, I want to see `[+] <name> joined` and `[-] <name> left.` messages in the Portal's exact format (and never vanilla's), so that presence reads the same as today.
5. As a player, I want the join message to appear only once I am actually in the game, so that there are no ghost announcements.

### Tab list

6. As a player, I want one unified tab list showing every online player regardless of World, so that the community feels like one server.
7. As a player, I want the tab header ("MCTraveler" in green) and footer (play address plus a TPS line) exactly as today, except the TPS is now the server's real TPS.
8. As a player, I want each tab entry to show the player's name with their latency as `name [Nms]`, so that ping stays visible.

### Chat and messaging

9. As a player, I want chat to reach every player in every World, formatted as a green name followed by the message, so that conversation is global.
10. As a player, I want chat to be vanilla signed chat (with the custom format applied via decoration), so that chat reporting works as Mojang intends — a deliberate change from the Portal.
11. As a player, I want death messages from any World broadcast to everyone, so that dramatic ends are shared.
12. As a player, I want `/msg <player> <message>` with the Portal's exact format and errors (including refusing to message myself), so that private conversation works as before.
13. As a player, I want `/reply` (`/r`) to answer the last person who messaged me, with the Portal's exact no-partner and gone-offline errors.
14. As a player, I want `/tell` and `/w` to behave as aliases of `/msg`, so that vanilla habits work.
15. As a player, I want `/shrug` and `/tableflip` to actually send ¯\\\_(ツ)\_/¯ and (╯°□°）╯︵ ┻━┻ as my chat line (fixing the Portal's no-op bug), so that the commands do what they always promised.

### Away

16. As a player, I want `/away` to mark me away immediately and broadcast "<name> is now away" in the Portal's format.
17. As a player, I want to be auto-marked away after 5 minutes without interacting (movement, chat, commands, block changes, item use all count), and un-marked the moment I interact, with the matching broadcasts.
18. As a player, I want the `/away` cooldown after returning (3 seconds, with the Portal's error message) preserved.

### Travel between Worlds

19. As a player, I want `/switch` to move me to the other World with the "Switching to Primary/Secondary..." message, so that travel feels identical.
20. As a player, I want to arrive where I last stood in the destination World (or its spawn on my first visit), so that each World remembers me.
21. As a player, I want my inventory, XP, health, hunger, ender chest, advancements, and stats to come with me when I Travel, so that I am one character across Worlds.
22. As a player, I want my bed or respawn anchor to count only in the World it stands in — dying in a World respawns me in that World — so that death never teleports me across Worlds.
23. As a player, I want nether and end portals to take me to my current World's own nether and end, so that each World remains a complete self-contained trio.
24. As a player, I want switching to be near-instant (no 2-second proxy delay), so that travel improves without changing meaning.

### Notepad

25. As a player, I want `/notepad` to open my private cross-World notebook as an editable book, seeded with the Portal's welcome page for new users.
26. As a player, I want saving the book to persist my pages and reply "SUCCESS Notepad saved", and my existing Portal notepad content to have migrated in.
27. As a player, I want the already-editing guard and session-cancellation messages (switching held item, clicking inventory) preserved.

### Regions

28. As a player, I want `/rg start` and `/rg end` to create a region from two corners with all the Portal's validations, messages, size limits (10–5000 blocks, admin override above), and sub-region rules preserved.
29. As a player, I want new regions to protect the full build height (y −64 to 320, fixing the Portal's 255/15 bug).
30. As a player, I want region overlap fully detected (any intersection, fixing the corner-only bug), so that regions cannot overlap undetected.
31. As a player, I want to rename my region (`/rg rename`, same name rules and messages), add members (`/rg add`, 99-member cap), and remove members (`/rg remove`, with tab-completion of member names and the can't-empty-a-region rule).
32. As a player, I want `/rg delete` to remove my region with the Portal's confirmation and embassy refusal messages.
33. As a player, I want the region sidebar scoreboard (title, Residents count, separator, member list with self in white) to appear when I enter a region and update live as membership changes, honoring NO_SCOREBOARD.
34. As a player, I want regions I'm not a member of to be unbreakable and unbuildable for me (with the "This area is protected by <name>" error), covering digging, placing, sign editing, container use (per container-open rules), item use, and entity interaction (empty-hand villager trading allowed per the Portal's rules).
35. As a player, I want region protection to also stop explosions, fire spread and damage, piston reach-in, and mob griefing from harming my region (new in the port), so that protection actually protects.
36. As a region owner, I want the five formerly inert flags to work: ENABLE_EXPLOSIONS and ENABLE_FIRE_DAMAGE opt my region back into that damage, DISABLE_PLAYER_FALL_DAMAGE prevents fall damage inside, DISABLE_PUBLIC_REDSTONE_TRIGGERS stops non-members using buttons/levers/pressure plates, and DISABLE_GATES stops non-members using doors/gates/trapdoors.
37. As an admin, I want `/rg flag`, `/rg bounds`, and `/rg locate` with the Portal's exact syntax, gating, validation, and output formats.
38. As an admin, I want to bypass region management restrictions (but not protection itself), exactly as today.
39. As a player, I want region entry and exit detected even when I arrive by teleport or portal (fixing the move-packet-only gap), so that the scoreboard and protection are always current.
40. As a player, I want region member names to resolve properly on scoreboards and in `/rg locate` via a real name cache (fixing the op-only uuid cache), so that member lists aren't silently incomplete.

### Admin

41. As a server operator, I want admin status to be vanilla operator status — managed with vanilla `/op` and `/deop`, no mod-side admin flag or custom wrappers — so that there is a single source of truth, and every admin-gated behaviour (region flag/bounds/locate, size-limit override, management bypass) keys off it.
42. As the aliased players, I want DemonicNoodle and AlsoJames to still become travelcraft2012 and iElmo (name and UUID) at login, so that their identities and everything keyed to them survive.

### Migration and operations

43. As the server operator, I want a one-time importer that brings both backend worlds in as the Primary and Secondary trios, re-keys offline-UUID playerdata and ops to Mojang UUIDs (respecting the remaps), merges each player's two playerdata sets (live state from their last World; the other World's position and respawn into its Per-World Bucket), and imports notepads, admin flags, lastServer routing, and regions (with world-name mapping) — preserving unknown legacy fields untouched.
44. As the server operator, I want the importer to be re-runnable safely (idempotent, refusing to clobber an already-migrated save), so that cutover is rehearsable.
45. As the server operator, I want XP orbs to visually merge rather than swarm (the Portal's orb-merge effect, now done server-side), so that grinder scenes stay smooth.
46. As a developer, I want an edit-compile-test loop measured in seconds (hot-swap into a running dev server where possible), so that iteration stays fast.
47. As a developer, I want the full behaviour suite runnable headlessly in one Gradle invocation, so that parity is continuously verified.

## Implementation Decisions

- **Platform**: Minecraft 26.2, Fabric Loader 0.19.x, Fabric API 0.156+, the new no-remap Loom, Java 25, Kotlin 2.4.x via fabric-language-kotlin. Mojang mappings (Yarn is discontinued). `fabric.mod.json` declares `"environment": "server"`; vanilla clients join uninstalled.
- **Architecture** (ADR 0002): no ported hook framework. Features are plain Kotlin modules registering Fabric events and Brigadier commands. Deep modules: Worlds/Travel service (Per-World Bucket swap, login routing, portal routing), Region service (geometry, protection, scoreboard, storage), Persistence service (player store, name cache, importer). Mixins, where unavoidable, are written in Java.
- **Worlds**: Primary is the vanilla overworld/nether/end; Secondary is a static datapack-defined trio shipped in the mod jar, generation-identical to the overworld. No runtime world library (Fantasy) — the topology is fixed. Nether/end portals route within the player's current trio. Player-facing names remain "Primary" and "Secondary".
- **Per-World Bucket** (ADR 0001): position + rotation, current dimension within the trio, and respawn point are stored per World and swapped on Travel; everything else rides with the one player entity. Death respawns within the World of death.
- **Chat**: vanilla signed chat kept; the green-name format applied via chat decoration (deliberate deviation — chat reporting returns). Join/leave/death broadcasts are system messages in the Portal's exact formats; vanilla join/leave messages suppressed.
- **Commands**: Brigadier registration with custom suggestion providers (online players, region members). Usage errors surface to players (resurrecting the Portal's dead usage-message intent). Unknown commands fall through to vanilla/server commands as today.
- **Text**: a small Kotlin Paint-equivalent DSL over native text components, preserving the exact color vocabulary and the ERROR/SUCCESS/USAGE prefixes — the server's entire message design language.
- **Persistence**: the Portal's flat-JSON formats are retained as the live store (per-player JSON with legacy fields preserved; legacy regions file format kept read/write compatible), behind a small store interface. World-name strings map old to new dimension ids. Server ops managed via the real ops list.
- **Timing**: all gameplay timing (away timeouts, cooldowns, delays) is server-tick-based, never wall-clock.
- **Identity**: login-time GameProfile swaps implement the two remaps. Admin status is vanilla operator status — the Portal's custom /op /deop wrappers, its stored admin flag, and the username-based escalation are all not ported; admin-gated features check vanilla permission level.
- **Importer**: a Gradle-invocable one-shot tool within the mod codebase; offline-UUID computation ported solely for migration; world folders imported then upgraded by vanilla's own migration on first boot.
- **Dev loop**: Loom dev dedicated server on JetBrains Runtime with enhanced class redefinition (and the mixin hotswap agent); Gradle configuration cache + K2 incremental compilation; secondary trio available in dev.

## Testing Decisions

- A good test asserts player-visible behaviour — messages received (exact text and styling), blocks changed or refused, position/World after an action — never internal structure.
- **Primary seam: the running server.** Fabric gametests with fake players drive commands and actions and assert outcomes; the suite runs headlessly in the standard Gradle build. Every behaviour in the parity inventory's per-feature list gets a gametest; the Portal's feature tests (admin gating, away wording and cooldown, msg/reply flows, region command validations, switch messaging, tab/MOTD text, remaps) are mined as the case list.
- **Unit tier (fabric-loader-junit)** only for genuinely pure logic: region geometry (containment, overlap, sub-regions, y-bounds), the text DSL's component output, the store round-trip, and the importer (UUID re-keying, playerdata merge, region world-mapping) against fixture files.
- **Smoke tier**: a production-launcher boot test proving the built jar starts a real dedicated server with both Worlds present.
- Tick-based timing is fast-forwarded in gametests (away timeout in ticks, not five real minutes).
- Existing Portal tests that verify proxy mechanism (packet plumbing, encoding, crypto) are deliberately not ported.

## Out of Scope

- The TUI launcher, backend-server supervision, auto-download/provisioning, webhook deploy, and Sentry instrumentation (ops tooling died with the proxy; deployment of the Fabric server is the operator's concern).
- Per-World inventories/XP or any strict-parity state separation beyond the Per-World Bucket (ADR 0001).
- The Portal's proxy workarounds: switch delays, dimension-switch trick, fake adventure gamemode, fake book item and window-click resync, packet-level orb merging, interact_at drops, rate limits, tab-list delays.
- The Portal's dead code: unused modules, login/logout timestamp tracking, the hardcoded-spawn playerdata converter, the /embassy command family (the embassy *flag* semantics are preserved as today: untoggleable, delete-refusal message).
- A third World, runtime world creation, or generalizing /switch beyond a two-World toggle (the model is built N-capable; the product ships with two).
- Chat-adjacent moderation features beyond what the Portal had.

## Further Notes

**Deviation register** (every intentional departure from Portal behaviour, per Intent Parity):

1. /shrug and /tableflip actually send their emotes (were no-ops).
2. New regions protect y −64..320 (were 255..15).
3. Region overlap detection catches all intersections (was corner-only).
4. Tab footer TPS is the real server TPS (was a fake ~20).
5. Malformed known commands get usage errors (previously fell through silently).
6. Chat is signed; chat reporting becomes possible again (was stripped unsigned).
7. Environmental region protection exists and the five inert flags work (were accepted-but-ignored).
8. The Portal's custom /op /deop commands (their messages, the stored admin flag, and the iElmo backdoor) are not ported — vanilla /op /deop and operator status are the single admin mechanism.
9. Region entry/exit is detected on teleports and portal arrivals (was move-packet-only).
10. A real name cache replaces the op-only uuid cache (member lists complete).
11. Travel is near-instant (no 2 s disconnect delay) and cannot lose state to a stale sync.
12. XP orb merging happens server-side (was client-side packet illusion). Merged orbs stack per value class via vanilla's lossless count mechanism — a burst leaves one orb per orb size — rather than the Portal's summing of mixed values into a single orb; totals are identical, and pickup/Mending granularity stays vanilla.
13. enforcesSecureChat is advertised honestly.
14. Player skins/identity need no offline-UUID surgery (single online-mode server).
15. The text DSL keeps a nested part's own style when collapsing single-part content (the Portal's NBT path overwrote the child's color with the parent's; its legacy-string path kept it — the legacy behaviour is the plain intent).
16. The usage helper renders as a styled component — aqua+bold "USAGE", then gray content, structurally parallel to ERROR/SUCCESS — instead of the Portal's raw legacy `§b§lUSAGE §7` string; nested styling inside usage content is preserved rather than stripped.
17. The server list advertises the real max-players (Portal hardcoded 20 — operators set `max-players=20` for capacity parity), and the real version/protocol (Portal claimed "MCTraveler Proxy" / protocol 773).
18. The server-list sample honors vanilla's per-player allow-server-listings opt-out and `hide-online-players` (the Portal always listed real names).
19. A real server icon works via the standard mechanism (the Portal's favicon was a broken placeholder).

Entries 1–19 keep their numbers permanently — ticket comments cite them. Entries 20+ were consolidated from ticket `## Comments` by the parity audit (ticket 19); each was decided while implementing the feature it belongs to.

**Chat, messaging, away**

20. Emote lines (`/shrug`, `/tableflip`) are server-authored and therefore unsigned, so an emote itself is not chat-reportable even though ordinary chat is (entry 6).
21. A reply partner who disconnects and returns is reachable again; the Portal held a stale session object and answered "no longer online" forever after a partner rejoined.
22. Vanilla `/msg`, `/tell` and `/w` are removed and replaced rather than shadowed, so vanilla's selector and whisper behaviour cannot leak through; console senders get Brigadier's player-required error (the Portal had no console concept).
23. Within the 3-second return window `/away` always answers the cooldown error; the Portal's silent-at-exactly-3.0s rounding artifact is not reproduced. Remaining seconds render as the Portal's JS template did (whole values drop the decimal).

**Regions — commands and storage**

24. `/rg start`'s "Position not available yet, please move first" error no longer exists: the Portal needed a client move packet before it knew a position, and the server always has one.
25. Overlap detection (entry 3) is parent-aware — a prospective parent is not an overlap — with the consequence that a sub-region may now exactly cover its parent's footprint, a shape the Portal's corner test happened to refuse.
26. Malformed `/rg` invocations answer USAGE before admin gating, so a non-admin typing a bare admin subcommand sees usage rather than the permission error.
27. An unreadable `regions.json` fails server start instead of continuing with zero regions (whose next save would have wiped the file) — the repo's persistence rule: never overwrite what could not be read.
28. `/rg remove` echoes the target name as typed while matching case-insensitively, exactly as the Portal did.

**Regions — scoreboard**

29. `NO_SCOREBOARD` now clears an existing sidebar on entry; the Portal only skipped *showing* one, so walking in from an ordinary neighbour left the previous board on screen, retitled with the quiet region's members.
30. Toggling a flag takes effect immediately for everyone standing in the region rather than on their next entry.
31. Membership changes redraw the whole sidebar instead of the Portal's three-packet patch, which re-sent the added member at score 0 — colliding with the first member's score and leaving row order to the client.
32. The text DSL gained `strikethrough`; the Portal lacked the decoration and hand-built the sidebar's separator as raw NBT.

**Regions — protection**

33. "Block place" means an item applied to a block, not every right-click. The Portal's hook was the raw `use_item_on` packet, which also carries opening a chest and pressing a button; ported literally, `ENABLE_PUBLIC_CONTAINERS` would be unreachable and `DISABLE_GATES` / `DISABLE_PUBLIC_REDSTONE_TRIGGERS` would have nothing to disable. The block's own right-click behaviour is governed by the container rule and those two flags instead.
34. "Holding an item" is the acting hand's item; the Portal could only see the selected hotbar slot because a proxy cannot see inventories.
35. Attacks are refused for every entity in a region you cannot modify, not only animals — the Portal's rule made no distinction either; only the flag's name mentions animals.
36. Without the Portal's fake adventure gamemode (a proxy illusion, out of scope), a stranger sees the break animation play and the block reappear rather than the client refusing first.
37. Blast damage to players and entities is untouched — only a region's *blocks* are shielded from explosions.
38. The piston rule is "same region as the piston": every block a piston would take, land on or destroy must belong to the piston's own region or to nobody, so a region's own redstone works and a resident may still push onto unclaimed ground.
39. Pressure plates refuse silently (they are asked every tick a foot is on them); every right-clicked trigger and gate carries the exact Portal refusal.
40. A thrown projectile counts as whoever threw it, so a resident's splash water bottle can still douse a fire in their own region.
41. A block change with no entity behind it is the world's own physics and is never refused.
42. Environmental flags are read off the deepest region, like every other flag, so a sub-region's `ENABLE_EXPLOSIONS` applies to its footprint and not its parent's. A wind charge cannot trigger blocks inside a protected region (triggering rides the same exploded-positions list), and an iron door in a `DISABLE_GATES` region answers with the refusal even though a bare hand could not have opened it.
43. Fluid flow is out of scope: inventory §7 lists fluids alongside explosion/fire/piston/mob, but story 35 does not, so lava and water spreading into a region are untouched.

**Worlds and travel**

44. Secondary's End runs the end-credits sequence like Primary's, which required translating the dimension check that gates it.

**Notepad**

45. The notepad session also cancels on dropping the book, offhand-swapping it, dying, logging out and server stop — guards the Portal did not need, because its book was a client-side illusion and this one is a real server item that must never escape.

**Migration** (see `docs/migration.md`)

46. Advancements and statistics import from the player's live World only; two sets cannot merge into the one shared set ADR 0001 keeps.
47. Secondary's level-wide saved data (maps, raids, world border, force-loaded chunks) is not imported — map ids are level-wide and cannot merge with Primary's without renumbering every map item.
48. Secondary inherits Primary's world seed: existing Secondary chunks import intact, but terrain generated past the current frontier will seam. Inherent to one server holding both Worlds.
49. Bans and whitelists are not imported (the Portal did its own authentication), and pre-1.16 playerdata is refused by player name rather than guessed at.
50. A player whose `lastServer` names a World they have no save in is made live in the World they do have, with `lastWorld` rewritten to match.
51. The name cache is seeded with every identity the migration resolved, not only the Portal's cache entries — the cutover form of entry 10.
52. The two aliased players (entry 8's remaps) render with their **real** skin: the authenticated profile's whole property map rides across the login swap, so vanilla's own player-info packets carry the Mojang-signed `textures` blob. This entry originally said the opposite — "they render with default skins, a signed texture set would not validate against the aliased UUID" — which was wrong about what the signature covers. It covers the texture payload alone, never the profile the property hangs on (`YggdrasilMinecraftSessionService.unpackTextures` reads no id at all), so it still validates on the alias. Corrected in ticket 21; it is also what the Portal itself did, via the `SetProfileProperties` hook feeding `TabListModule.profilePropertiesMap`.

**Orphaned-save claim at login** (ticket 20)

53. A save the migration cannot identify is **quarantined**, not left behind: `mctraveler/orphaned-saves/<world-id>/<offline uuid>.dat` plus its `advancements/` and `stats/` sidecars, outside the level so vanilla never walks it. The report counts them instead of naming thousands; an unresolved *operator* is still named line by line, because an op list is read before anyone can connect and so can never be claimed. `--skip-unidentified` therefore means "quarantine", not "abandon".
54. A joining player's username unlocks their own quarantine entry, and the claim happens before vanilla reads their save, so a login that inherits a Portal-era save is indistinguishable from any other login. This is the migration's per-player half performed later; the transforms are the importer's own.
55. **A player who already has a save is never overwritten** — the claim is skipped and the orphan left alone. An offline uuid is a hash of a *username*, and usernames can be released and re-registered, so it is evidence of ownership and never proof. Consequence, accepted because nothing can distinguish the cases: a player who registered a name a Portal-era player once used would inherit that player's save. Every claim and every skip is logged, which is how such a case would be caught.
56. A claim that cannot be made (playerdata this server cannot place, e.g. the pre-1.16 form of entry 49) writes nothing and leaves the quarantine intact, but is an operator action item rather than a self-healing state: once the player plays and gets a save of their own, entry 55's guard refuses the claim for good. Logged at ERROR for exactly that reason.

**Secure chat for the aliased identities** (ticket 21)

57. The two aliased players' chat is **unsigned**, and so is the one kind of ordinary chat that entry 6 does not make reportable. Vanilla's client only opens a chat session when the uuid the server hands it is the one it authenticated with (`ClientPacketListener.setKeyPair` guards on `Minecraft.isLocalPlayer`), so an aliased client never sends its profile public key at all — validating that key against the identity they authenticated as, the shape ticket 21 preferred, has nothing to validate. They are therefore exempted from secure-chat enforcement by name, off `IdentityRemaps.REMAPS`, and only they: `enforce-secure-profile` stays `true`, everyone else's chat stays signed and reportable, and the advertised `enforcesSecureChat` (entry 13) is untouched. Their lines reach other players as **disguised** chat rather than player chat — rendered identically, through the same chat type and green name — because a client whose server enforces secure chat drops unsigned *player* chat unseen. The exemption cannot distinguish an aliased player from the real account that owns the alias; by construction, that is what a remap is.

Flag semantics for the five newly-real flags are the port's proposal (names + pre-proxy convention); if lore says otherwise, adjust with a note here. The shared player state decision (ADR 0001) means cross-World item transfer is now possible — communicate to the community at cutover alongside the chat-signing change (entry 6) and the seam note (entry 48).

## Parity audit (ticket 19)

**Suite**: 206 headless gametests + 171 unit tests, green in one `./gradlew build` (ticket 20 added 2 and 18; ticket 21 added 2 and 3). `./gradlew prodServer` boots the built jar on the real Fabric dedicated-server launcher and asserts all six dimensions are live — `minecraft:overworld/the_nether/the_end` and `mctraveler:secondary/_nether/_end`. Gametests run on vanilla's `GameTestServer`, which needs a mixin to see datapack dimensions at all, so the production boot is the only place "both Worlds genuinely ship" can be proven.

**Coverage by feature** (Portal feature → gametests that pin it): Motd → MotdGameTest (2, now including live styling); Away → AwayGameTest (11); Chat/join/leave/death/emotes → ChatGameTest (6); private messages → PrivateMessagesGameTest (13); Switch + Worlds + Position Memory → WorldsGameTest (5); respawn + portal routing → RespawnAndPortalsGameTest (6); TabList → TabListGameTest (4, cross-World now using the real Secondary World); Notepad → NotepadGameTest (10); Regions → RegionCommandGameTest (25), RegionAdminCommandGameTest (13), RegionMembershipGameTest (18), RegionScoreboardGameTest (11), RegionProtectionGameTest (26), RegionEnvironmentGameTest (24), RegionFlagGameTest (19); TravelPatch identity → IdentityRemapGameTest (5, now including the skin properties and the aliased chat path); persistence → PersistenceGameTest (1) + JsonPlayerStoreTest/NameCacheTest (23 unit); migration → MigrationGameTest (2) + 51 importer unit tests; orphaned-save claim → OrphanedSaveClaimGameTest (2) + OrphanedSaveClaimTest (13 unit) + 5 quarantine tests in PortalImportTest; orb merging → XpOrbMergeGameTest (1); command/text framework → PaintTest (23), RegionServiceTest (22), TabListFeatureTest (6), MotdTest (7). CoreFeature, the Portal's module/hook framework, and its four dead API-only modules have no port surface by ADR 0002.

**Deviations**: 57 register entries above. Every behaviour in the inventory is either pinned by a named test or retired by an entry.

**Verified by inspection, not by test** (stated plainly rather than counted as coverage):
- That each named griefer (ravager, wither, villager, zombie, rabbit) reaches `Level.destroyBlock` carrying itself — read at each call site; the gametest drives that seam with a real ravager.
- Region behaviour in non-overworld dimensions — every gametest runs in the gametest overworld; the World-string mapping is `RegionWorlds`' single seam, unit-tested.
- Downstream state keying to a remapped identity — vanilla builds it all from the one field the mixin swaps.
- The secure-chat *gate* the aliased players are exempted from (entry 57): `MinecraftServer.enforceSecureProfile()` is `false` on `GameTestServer`, so the gate is never shut in a headless run and the exemption cannot be observed there. What the exemption opens onto — the disguised-chat delivery — is gametested. So is the fact that the skin property survives the swap; that it then *renders* is inspection of `SkinManager.createLookup` → `YggdrasilMinecraftSessionService.unpackTextures`, which validates the signature and reads no profile id.
- Respawn *anchors* (stored and handled identically to beds; only beds are gametested).
- IntelliJ-side hotswap (the JVM/agent wiring is machine-verified; the interactive reload is not).

**Known gaps, deliberately left** (each cheap to fix later, none affecting today's Portal parity since the Portal protected none of it): sheep cropping grass and silverfish *infesting* stone change blocks with no entity attached and slip the creature rule; buttons pressed by arrows bypass `DISABLE_PUBLIC_REDSTONE_TRIGGERS`; tripwire is outside the trigger vocabulary; fluid flow into a region is untouched (inventory §7 lists it, story 35 does not). The `/switch` failure branch is implemented with the Portal's wording but not gametested — a healthy server offers no way to make Travel fail. The end-to-end server-icon path is vanilla's own and is covered only as pass-through at the unit tier.

**Test-harness debt**: five fake-player harnesses coexist (`FakePlayer`, `TestPlayer`, `AwayTestPlayer`, `MessageCapturingPlayer`, `TestPlayers`) plus two text-run flatteners (`runsOf`/`Run` and `Component.textRuns()`/`TextRun`) — an artifact of parallel implementation. Consolidating them touches every gametest file, so it is left as follow-up work rather than risking the suite at the close of the port.

**Cutover checklist** (runbook: `docs/migration.md`):
1. Build an identities file for the players you want live from the first boot — operators especially, since an op list is read before anyone can connect and an unresolved operator is dropped (entry 53). Resolving *everyone* is neither expected nor feasible: the Portal's uuid cache covers a few hundred of ~13,000 offline-keyed saves.
2. Run with `--skip-unidentified`: the rest are quarantined and claimed by their owners at login (entries 53–54). Without it the migration refuses, by design.
3. Rehearse the migration; it stages everything and refuses a second run against an already-migrated save.
4. First boot after import is slower and required: vanilla's own file fixer relays out the save layout and runs the version upgrade. Quarantined saves are *not* part of that pass — each is upgraded by vanilla's data fixers when its claim is read, so it works whenever the player turns up.
5. Set `max-players=20` to advertise today's capacity (entry 17).
6. Watch the log through the first days for `orphaned-save claim` lines. A `FAILED` claim must be resolved before that player plays again (entry 56); a `skipped` one means a quarantined save is keyed to a name someone else already plays under (entry 55).
7. Tell the community three things: items and XP now move between Worlds (ADR 0001), chat is reportable again (entry 6), and new terrain past Secondary's current frontier will seam (entry 48).
