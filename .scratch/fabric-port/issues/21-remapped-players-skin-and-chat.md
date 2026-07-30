# 21 — Remapped players lose their skin and their chat

**What to build:** The two aliased players (`AlsoJames` → `iElmo`, `DemonicNoodle` → `travelcraft2012`) get their skin and their chat back, without giving up signed chat for everyone else.

**Blocked by:** 16 (Identity remaps). **Live production bug** — reported by an aliased player minutes after cutover.

**Status:** ready-for-agent

## The symptoms

On `play.mctraveler.eu`, logged in as `AlsoJames` (remapped to `iElmo`):

1. **No skin** — the player renders as the default model.
2. **No chat** — the client shows `Chat disabled due to missing profile public key. Please try reconnecting.`

## Why

`ServerLoginPacketListenerImplMixin` swaps the authenticated `GameProfile` for a fresh one carrying the alias's name and UUID. That profile is built with an **empty property map**, so the Mojang-signed `textures` property is dropped — symptom 1, which ticket 16 predicted and accepted.

Symptom 2 was not predicted. Signed chat (spec deviation 6) works from a profile public key the client fetches from Mojang, whose signature covers the player's **real** UUID. The server validates that key against the UUID of the profile it holds; after the swap those differ, validation fails, the chat session is refused, and the client reports a missing key. The Portal never met this because its chat was unsigned system chat.

## What to build

- [ ] An aliased player's skin renders again: the authenticated profile's properties (notably `textures`) ride across the remap onto the aliased profile.
- [ ] An aliased player can chat again, with everyone else's chat still signed and reportable. Preferred: validate their profile public key against the identity they authenticated as, so their messages stay genuinely signed. If that proves impossible, exempt only remapped players from secure-profile enforcement — never disable enforcement globally.
- [ ] `enforce-secure-profile` stays `true` in production; no change that makes every player's chat unsigned.
- [ ] The original (pre-remap) identity is available wherever it is needed, without leaking into anything that keys player data — playerdata, regions, tab list and the name cache must keep using the **aliased** UUID exactly as they do now.
- [ ] Tests: the remap preserves properties; the key/session path accepts a remapped player; a non-remapped player is unaffected. Note the gametest limit ticket 16 recorded (fake players bypass login) and cover what is reachable, saying plainly what is verified by inspection.

## Notes for the implementer

- Verify every signature against the mapped 26.2 jar rather than assuming: the relevant machinery is around `ServerLoginPacketListenerImpl`, `ServerCommonPacketListenerImpl`/`ServerGamePacketListenerImpl` chat-session handling, `RemoteChatSession.Data`, and `ProfilePublicKey.createValidated`.
- A skin texture is signed over its own payload, not over the profile it is attached to, so copying the property across is expected to render — confirm rather than assume, and say which it was.
- Keep the remap table (`IdentityRemaps.REMAPS`) the single source of truth; the importer imports it.
- This is live: prefer the smallest correct change, and note in `## Comments` exactly what an operator must restart for it to take effect.
