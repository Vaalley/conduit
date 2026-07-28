# 06 — Chat: global format, join/leave, death messages, emotes

**What to build:** MCTraveler's chat voice on vanilla signed chat: every chat line reaches all players in all Worlds formatted as green name + message (via chat decoration, keeping signatures — a deliberate deviation), custom join/leave lines replace vanilla's, death messages broadcast globally, and `/shrug` + `/tableflip` actually emote.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** ready-for-agent

See `../spec.md` (User Stories 9–15, Implementation Decisions: Chat; deviation register 1, 6, 13) and the ChatFeature section of `docs/research/portal-feature-inventory.md` for exact message formats.

- [ ] Chat is signed vanilla chat, formatted `<green name> <message>` via decoration, visible across Worlds
- [ ] Vanilla join/leave messages are suppressed; the Portal's exact `[+] <name> joined` / `[-] <name> left.` formats broadcast instead, join only once the player is actually in play
- [ ] Death messages from any dimension broadcast to every player, no duplicates
- [ ] `/shrug` and `/tableflip` send their emoticons as the player's chat line, visible to everyone (fixes the Portal's no-op)
- [ ] Status response advertises secure chat honestly
- [ ] Gametests: chat format and cross-World visibility, join/leave wording, death broadcast, both emotes
