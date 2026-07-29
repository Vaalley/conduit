# 06 — Chat: global format, join/leave, death messages, emotes

**What to build:** MCTraveler's chat voice on vanilla signed chat: every chat line reaches all players in all Worlds formatted as green name + message (via chat decoration, keeping signatures — a deliberate deviation), custom join/leave lines replace vanilla's, death messages broadcast globally, and `/shrug` + `/tableflip` actually emote.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** done

See `../spec.md` (User Stories 9–15, Implementation Decisions: Chat; deviation register 1, 6, 13) and the ChatFeature section of `docs/research/portal-feature-inventory.md` for exact message formats.

- [x] Chat is signed vanilla chat, formatted `<green name> <message>` via decoration, visible across Worlds
- [x] Vanilla join/leave messages are suppressed; the Portal's exact `[+] <name> joined` / `[-] <name> left.` formats broadcast instead, join only once the player is actually in play
- [x] Death messages from any dimension broadcast to every player, no duplicates
- [x] `/shrug` and `/tableflip` send their emoticons as the player's chat line, visible to everyone (fixes the Portal's no-op)
- [x] Status response advertises secure chat honestly
- [x] Gametests: chat format and cross-World visibility, join/leave wording, death broadcast, both emotes

## Comments

Key decisions (implementation in `src/main/kotlin/eu/mctraveler/chat/ChatFeature.kt`, tests in `src/gametest/kotlin/eu/mctraveler/gametest/ChatGameTest.kt` + `TestPlayer.kt`):

- **Chat format = a registered chat type, not content rewriting.** `data/mctraveler/chat_type/chat.json` registers `mctraveler:chat` with decoration `"%s %s"` `[sender, content]` (the untranslated-key-as-format trick). `ServerMessageEvents.ALLOW_CHAT_MESSAGE` cancels vanilla-`minecraft:chat`-bound broadcasts and rebroadcasts the *same* `PlayerChatMessage` bound to `mctraveler:chat` with `Paint.green(username)` as the sender name; re-entry passes through (guard: already our type). Signatures untouched — chat reporting works (deviation 6). `/say`, `/me`, future `/msg` chat types are untouched. Cross-World visibility is inherent: `PlayerList.broadcastChatMessage` is server-wide.
- **Join/leave**: `ALLOW_GAME_MESSAGE` suppresses `multiplayer.player.joined`, `.joined.renamed`, and `.left`. Join line is queued on `ServerPlayConnectionEvents.JOIN` and broadcast at `END_SERVER_TICK` — after `placeNewPlayer` completes, i.e. the player is actually in play; a connection that drops in between announces nothing (gametest covers it). Leave line broadcasts on `DISCONNECT` only for players whose join line went out. Exact colors per inventory: gray line, dark-gray brackets, green +/name on join, red -/name on leave, trailing period on leave.
- **Death messages**: no code. On a single server vanilla `ServerPlayer.die` already broadcasts to every player in every dimension exactly once; the gametest pins that behaviour (nether death seen exactly once by an overworld observer and by the victim).
- **Emotes**: `/shrug` and `/tableflip` broadcast `PlayerChatMessage.system(emoticon)` bound to `mctraveler:chat` — server-authored player-voiced chat (`ClientboundDisguisedChatPacket`), rendered identically to the player's chat lines. Registered via `CommandRegistrationCallback`, player-only (`playerOrException`).
- **Secure chat honesty (deviation 13)**: nothing to do here — vanilla's status response reports `enforcesSecureChat` from `enforce-secure-profile` truthfully, and chat is now genuinely signed. The MOTD ticket (10) owns the status payload; no collision.
- **Possible register note (not a new deviation, a nuance of 1)**: emote lines are necessarily *unsigned* server-authored chat (commands cannot produce a client signature), so an emote line itself is not chat-reportable, unlike regular chat.
- **Test harness**: `TestPlayer` joins headless players through the real `PlayerList.placeNewPlayer` with an `EmbeddedChannel`-backed `Connection`, captures every clientbound packet as the assertion seam, drives chat via real `ServerboundChatPacket` handling, and acks client-load/dimension-change like a real client (until that ack the server keeps players invulnerable — bit us in the death test). Disconnect goes through `Connection.disconnect` + `handleDisconnection` (Fabric's DISCONNECT fires there, not in `ServerGamePacketListenerImpl.onDisconnect`).
