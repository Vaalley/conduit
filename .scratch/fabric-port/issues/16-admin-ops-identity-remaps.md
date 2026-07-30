# 16 — Identity remaps

**What to build:** The two aliased identities: DemonicNoodle and AlsoJames log in as travelcraft2012 and iElmo (name and UUID both), with all game and mod state keyed to the remapped identity.

Scope note: this ticket originally also carried custom `/op` `/deop` commands and a mod-side admin flag — that was cut. Admin status is vanilla operator status, managed by vanilla `/op` `/deop`; admin-gated features check vanilla permission level.

**Blocked by:** 01 (Scaffold).

**Status:** done

See `../spec.md` (User Story 42; deviation register 8) and the TravelPatchFeature section of `docs/research/portal-feature-inventory.md` for the remap constants.

- [x] The two GameProfile remaps apply at login (case-sensitive, name and UUID both), logged with the Portal's remap log line
- [x] All state (playerdata, regions, tab list, name cache) keys to the remapped identity
- [x] Gametests: remapped login identity for both entries; unaffected names pass through untouched

## Comments

- **Hook point:** `ServerLoginPacketListenerImplMixin` (Java, ADR 0002) `@ModifyVariable`s the `GameProfile` argument of `ServerLoginPacketListenerImpl.startClientVerification` — the one funnel every login path passes through. The Mojang auth thread calls it with the authenticated profile (so the swap is post-auth), and the offline/singleplayer paths call it from `handleHello`. The stored `authenticatedProfile` is what the ServerPlayer, its playerdata file, the tab-list entry, region membership checks, and the name cache are all built from, so downstream keying to the remapped identity is structural rather than per-consumer.
- **Remap table for ticket 18:** `IdentityRemaps.REMAPS` in `src/main/kotlin/eu/mctraveler/identity/IdentityRemaps.kt` — a public `Map<String, GameProfile>` (case-sensitive username → aliased profile), importable directly by the importer; `IdentityRemaps.remap(profile)` is the pure swap function (logs the Portal's `Remapping <old> -> <new>` line).
- **Gametest limit:** fake gametest players are placed directly into the world and never pass through login, so `IdentityRemapGameTest` drives a real `ServerLoginPacketListenerImpl` through `handleHello` on the live (offline) server and asserts the resulting `authenticatedProfile` (read reflectively — vanilla has no accessor before the ServerPlayer exists; the name is stable under the no-remap Mojang-mappings toolchain). The remaining hop — vanilla building all downstream state from that field — is vanilla's own behaviour, verified by inspection.
- **Properties note (superseded by ticket 21 — this paragraph was wrong):** the swapped profile carried no property map (skin textures), which was reasoned as "matching Portal reality — a signed texture set would not validate against the aliased UUID anyway". Both halves were wrong. The Portal *did* carry the authenticated profile's properties (its `SetProfileProperties` hook fed `TabListModule.profilePropertiesMap`), and Mojang's `textures` signature covers the payload alone, never the profile the property hangs on — so it validates perfectly well on the alias. Ticket 21 carries the property map across; the two aliased players render with their real skins. The dropped properties also cost them their chat, which is ticket 21's other half.
