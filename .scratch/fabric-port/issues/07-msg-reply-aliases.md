# 07 — /msg, /reply and vanilla aliases

**What to build:** Private messaging exactly as the Portal does it: `/msg <player> <message>` with the sender→target arrow format shown identically to both parties, `/reply` (`/r`) answering the last person who messaged you, and `/tell` + `/w` behaving as aliases of `/msg`.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** done

See `../spec.md` (User Stories 12–14) and the ChatFeature section of `docs/research/portal-feature-inventory.md` for exact formats, errors, and reply-partner semantics (only /msg updates the reply map — preserve that).

- [x] `/msg` refuses self-messaging with the exact error; otherwise both parties see the identical formatted line and reply partners are stored in both directions
- [x] `/reply` errors exactly when there is no partner or the partner went offline; replying does not itself update the reply map
- [x] `/tell` and `/w` alias `/msg`, including tab-completion of online player names
- [x] Gametests: full msg/reply conversation flows, every error path, alias equivalence

## Comments

- Implemented as `eu.mctraveler.chat.PrivateMessages` (Brigadier via `CommandRegistrationCallback`; reply partners in an in-memory UUID map; messages via Paint). Vanilla `/msg`, `/tell`, `/w` are **removed and replaced** — resolving the inventory §2.4 "On Fabric decide" point as replace-and-alias, so vanilla's selector/whisper behaviour cannot leak through. Removal needs reflection into Brigadier's root child maps (`eu.mctraveler.command.CommandTree`), since Brigadier has no removal API and re-registering merges instead of replacing. This is not the client-tree injection ADR 0002 retired, but it is adjacent — flagged for an ADR note if the orchestrator wants one.
- Reply-map lifecycle matches the Portal's per-connection WeakMap observably: your own entry dies with your session (rejoin ⇒ "no-one to reply to"); entries pointing at a leaver survive while their owner stays online (⇒ the gone-offline error). Consequence: every map key is an online player, so the map stays bounded.
- **Intent-parity deviation** (for the spec register, recorded here per worktree rules): if your reply partner disconnects and then *returns*, `/reply` reaches them again. The Portal held a stale session object, so once a partner disconnected `/reply` erred "no longer online" forever, even after they rejoined — the plain intent of that check is the partner's *current* onlineness. Pinned by the `replyReachesAPartnerWhoLeftAndReturned` gametest.
- Console senders get Brigadier's "player required" error (the Portal had no console concept); malformed invocations get Brigadier usage errors per deviation 5.
- Test infrastructure for later tickets: `eu.mctraveler.gametest.FakePlayer` joins fake players through the real `placeNewPlayer` login path over an embedded netty channel — they appear in the player list, run commands, capture the system-chat packets a client would receive, and disconnect through the real teardown (Fabric `DISCONNECT` event fires; note it hangs off the netty channel close, not `onDisconnect`). `TextRuns.runsOf` flattens components to rendered runs for exact text/color/bold assertions.
