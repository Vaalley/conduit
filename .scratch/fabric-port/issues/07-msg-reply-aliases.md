# 07 — /msg, /reply and vanilla aliases

**What to build:** Private messaging exactly as the Portal does it: `/msg <player> <message>` with the sender→target arrow format shown identically to both parties, `/reply` (`/r`) answering the last person who messaged you, and `/tell` + `/w` behaving as aliases of `/msg`.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** ready-for-agent

See `../spec.md` (User Stories 12–14) and the ChatFeature section of `docs/research/portal-feature-inventory.md` for exact formats, errors, and reply-partner semantics (only /msg updates the reply map — preserve that).

- [ ] `/msg` refuses self-messaging with the exact error; otherwise both parties see the identical formatted line and reply partners are stored in both directions
- [ ] `/reply` errors exactly when there is no partner or the partner went offline; replying does not itself update the reply map
- [ ] `/tell` and `/w` alias `/msg`, including tab-completion of online player names
- [ ] Gametests: full msg/reply conversation flows, every error path, alias equivalence
