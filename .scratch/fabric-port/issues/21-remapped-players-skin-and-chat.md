# 21 — Remapped players lose their skin and their chat

**What to build:** The two aliased players (`AlsoJames` → `iElmo`, `DemonicNoodle` → `travelcraft2012`) get their skin and their chat back, without giving up signed chat for everyone else.

**Blocked by:** 16 (Identity remaps). **Live production bug** — reported by an aliased player minutes after cutover.

**Status:** done

## The symptoms

On `play.mctraveler.eu`, logged in as `AlsoJames` (remapped to `iElmo`):

1. **No skin** — the player renders as the default model.
2. **No chat** — the client shows `Chat disabled due to missing profile public key. Please try reconnecting.`

## Why

`ServerLoginPacketListenerImplMixin` swaps the authenticated `GameProfile` for a fresh one carrying the alias's name and UUID. That profile is built with an **empty property map**, so the Mojang-signed `textures` property is dropped — symptom 1, which ticket 16 predicted and accepted.

Symptom 2 was not predicted. Signed chat (spec deviation 6) works from a profile public key the client fetches from Mojang, whose signature covers the player's **real** UUID. The server validates that key against the UUID of the profile it holds; after the swap those differ, validation fails, the chat session is refused, and the client reports a missing key. The Portal never met this because its chat was unsigned system chat.

> **This diagnosis is wrong** — corrected in `## Comments`. The key never reaches the server at all, so `ProfilePublicKey.createValidated` is never called. The failure is on the client, and it changes which fix is possible.

## What to build

- [x] An aliased player's skin renders again: the authenticated profile's properties (notably `textures`) ride across the remap onto the aliased profile.
- [x] An aliased player can chat again, with everyone else's chat still signed and reportable. Preferred: validate their profile public key against the identity they authenticated as, so their messages stay genuinely signed. If that proves impossible, exempt only remapped players from secure-profile enforcement — never disable enforcement globally. *(The preferred shape is impossible in 26.2 — evidence in Comments — so the exemption route was taken, scoped to the two aliases.)*
- [x] `enforce-secure-profile` stays `true` in production; no change that makes every player's chat unsigned.
- [x] The original (pre-remap) identity is available wherever it is needed, without leaking into anything that keys player data — playerdata, regions, tab list and the name cache must keep using the **aliased** UUID exactly as they do now. *(Nothing needs it, so nothing stores it — see Comments.)*
- [x] Tests: the remap preserves properties; the key/session path accepts a remapped player; a non-remapped player is unaffected. Note the gametest limit ticket 16 recorded (fake players bypass login) and cover what is reachable, saying plainly what is verified by inspection.

## Notes for the implementer

- Verify every signature against the mapped 26.2 jar rather than assuming: the relevant machinery is around `ServerLoginPacketListenerImpl`, `ServerCommonPacketListenerImpl`/`ServerGamePacketListenerImpl` chat-session handling, `RemoteChatSession.Data`, and `ProfilePublicKey.createValidated`.
- A skin texture is signed over its own payload, not over the profile it is attached to, so copying the property across is expected to render — confirm rather than assume, and say which it was.
- Keep the remap table (`IdentityRemaps.REMAPS`) the single source of truth; the importer imports it.
- This is live: prefer the smallest correct change, and note in `## Comments` exactly what an operator must restart for it to take effect.

## Comments

### The root cause, corrected

The ticket's diagnosis of symptom 2 does not survive contact with the mapped 26.2 jar. The chat session is not refused by the server — **it is never offered**. Read in order:

1. `ServerLoginPacketListenerImpl.finishLoginAndWaitForClient` sends `ClientboundLoginFinishedPacket(gameProfile, …)` with the profile the mixin swapped: the **alias**.
2. The client stores that as `cookie.localGameProfile` (`ClientHandshakePacketListenerImpl`: `localGameProfile = packet.gameProfile()`).
3. `ClientPacketListener.setKeyPair` is guarded by `if (this.minecraft.isLocalPlayer(this.localGameProfile.id()))`, and `Minecraft.isLocalPlayer(id)` is `id.equals(this.getUser().getProfileId())` — the **real** account id. For an aliased player that is `false`.
4. So the client never builds a `LocalChatSession`, never sends `ServerboundChatSessionUpdatePacket`, and `signedMessageEncoder` stays `Encoder.UNSIGNED`.
5. Server side, `signedMessageDecoder` therefore stays what the constructor set: `SignedMessageChain.Decoder.unsigned(player.getUUID(), server::enforceSecureProfile)`. With enforcement on, `unpack` throws `MISSING_PROFILE_KEY` — translation key `chat.disabled.missingProfileKey`, the exact line the player reported — and `handleMessageDecodeFailure` echoes it back in red.

That the reported symptom is *"Chat disabled…"* rather than a disconnect is itself the tell: a genuine `ProfilePublicKey.ValidationException` in `handleChatSessionUpdate` calls `this.disconnect(...)` with `multiplayer.disconnect.invalid_public_key_signature`. The player stayed connected, so validation never ran.

### Option (a) was rejected because it is impossible, not because it is hard

Ticket 21 preferred validating the key against the identity they authenticated as. Two independent reasons that cannot work in 26.2, neither fixable server-side:

- **There is no key.** Per step 4, an aliased client sends no `ServerboundChatSessionUpdatePacket`. `ProfilePublicKey.createValidated` is never reached. Changing which UUID `RemoteChatSession.Data.validate(profile, …)` hands it changes the behaviour of a code path that is never entered.
- **Every other client would reject it anyway.** Granting a hypothetical key, `ClientPacketListener.initializeChatSession` re-validates each broadcast session on the *receiving* client: `chatSessionData.validate(info.getProfile(), signatureValidator)`, where `info.getProfile()` is the aliased profile out of the player-info packet. That throws, `info.clearChatSession(enforcesSecureChat)` runs, and the sender lands on `SignedMessageValidator.REJECT_ALL`. Fixing this needs a modified client; ours are vanilla.

So: **option (b)**, exempting only the remapped players. Scoped as narrowly as the seams allow, all three keyed off `IdentityRemaps.isAliased` — the remap table stays the single source of truth:

- **Inbound chat** — `AliasedPlayerSecureChatMixin` re-creates `signedMessageDecoder` with a `() -> false` enforcement supplier at the tail of the `ServerGamePacketListenerImpl` constructor, for aliased players only.
- **Inbound commands** — `performUnsignedChatCommand`'s `server.enforceSecureProfile()` call is redirected to `enforceSecureProfile() && !isAliased(player)`, so their `/me` is not refused with `INVALID_COMMAND_SIGNATURE`. The mod's own `/msg` family takes `StringArgumentType`, not `MessageArgument`, so it was never affected.
- **Outbound chat** — `sendPlayerChatMessage` sends an aliased player's unsigned line via `sendDisguisedChatMessage(message.decoratedContent(), chatType)` instead. This is necessary, not decorative: with `enforce-secure-profile=true` the server advertises `enforcesSecureChat` in `ClientboundLoginPacket`, and every recipient's `PlayerInfo` falls back to `SignedMessageValidator.REJECT_ALL` for a sender with no session — their line would have been dropped unseen by everyone else. Disguised chat is the path vanilla already uses for the emote commands (deviation 20) and renders identically, carrying the same `ChatType.Bound`. A *signed* message is never touched, so if the real account that owns an alias logs in, its chat stays on the signed path.

**`enforce-secure-profile` is untouched, and so is the advertised `enforcesSecureChat`** (deviation 13). Every player who can sign still signs, the server still verifies, and chat reporting still works for all of them. Two players' lines are unsigned and unreportable; that is the whole blast radius. Recorded as deviation register entry 57.

**Rejected alternative:** advertising `enforcesSecureChat=false` so recipients accept unsigned player chat. It would have worked — it makes nobody's chat unsigned, it only changes the *fallback* for session-less senders — but it costs every player the "insecure server" toast on join and weakens a server-wide protocol claim to fix a two-player problem. The disguised-chat route is strictly narrower.

### The skin

`IdentityRemaps.remap` now builds the aliased profile with `profile.properties()` — the whole authenticated property map, `textures` included. Same intent as the Portal's own fix (`SetProfileProperties` feeding `TabListModule.profilePropertiesMap`), reached without any packet rewriting because vanilla's `ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER` already writes `profile.properties()`.

Verified in the jar rather than assumed: `YggdrasilMinecraftSessionService.unpackTextures(Property)` base64-decodes the payload and checks the signature against the services key set — **it never reads the payload's embedded `profileId` or `profileName`, and never compares them to the profile the property hangs on**. So a blob signed for AlsoJames's real uuid validates while hanging on `be9482bb-…` and comes back `SignatureState.SIGNED`. The client's `SkinManager.createLookup(profile, requireSecure)` filters on exactly `skin.secure()`, which `registerTextures` sets from `signatureState() == SIGNED`. `requireSecure` is `true` here — it is `!minecraft.isLocalPlayer(profile.id())`, which for an aliased profile holds even in that player's own client — so the signature genuinely has to hold, and it does. Ticket 16's "Properties note" was wrong on both halves and is corrected in place; deviation 52 is rewritten.

### The duplicate-UUID warning: vanilla's own, not the remap's

Asked separately, answered from the jar: **(a) — the remap does not defeat vanilla's duplicate-login handling.** The check and the ServerPlayer are built from the same identity.

`ServerLoginPacketListenerImpl.startClientVerification(GameProfile)` is where `this.authenticatedProfile` is first assigned, and the ticket-16 mixin's `@ModifyVariable` sits at its `HEAD` — so the field holds the **alias** from the very first instruction that stores it. Every later vanilla read is of that field:

- `verifyLoginAndFinishConnectionSetup(profile)` → `canPlayerLogin(addr, new NameAndId(profile))`, the `connection.getIntendedProfileId()` comparison, and `playerList.disconnectAllPlayersWithProfile(profile.id())` — all **alias**.
- `tick()`'s `isPlayerAlreadyInWorld(authenticatedProfile)` → `getPlayer(alias)`, the gate that holds `WAITING_FOR_DUPE_DISCONNECT` until the old session is gone.
- `handleLoginAcknowledgement` → `CommonListenerCookie.createInitial(authenticatedProfile, …)`, from which the `ServerPlayer` — and so `ServerLevel.addPlayer`'s `getEntity(player.getUUID())` — is built. **Alias.**

Nothing in the login path sees the real uuid after the swap; the only pre-swap capture is `requestedUsername`, which feeds Mojang auth and the log lines. `Force-added player with duplicate UUID` comes from `ServerLevel.addPlayer`, vanilla's own belt-and-braces force-remove on a fast reconnect, and keys off `player.getUUID()` — the alias — on both sides. Left alone.

One consequence worth stating, inherent to remapping and not new: the server cannot distinguish an aliased player from the **real** account that owns the alias uuid. If both connect, vanilla duplicate-kicks one, exactly as it would for one account connecting twice.

### Nothing needs the pre-remap identity

The acceptance list asked for the original identity to be available "wherever it is needed". With option (b) nothing needs it — the exemption is a question about the uuid the `ServerPlayer` already holds, not about the account behind it — so nothing stores it, and there is no second identity to leak. Playerdata, regions, tab list and the name cache are untouched by this ticket and keep keying to the **aliased** uuid exactly as ticket 16 left them. The one new thing the aliased profile carries is a property map, which nothing keys anything on.

### Verified by test vs. by inspection

Tested — 206 gametests + 171 unit tests, one green `./gradlew build`:

- `IdentityRemapsTest` — properties ride across `remap`; `isAliased` recognises both aliases and nobody else; the uuid a remapped player authenticated with is not itself aliased.
- `IdentityRemapGameTest.anAliasedLoginKeepsTheAuthenticatedProfilesTextures` — drives the real `startClientVerification` (the swap's own hook point) with a property-bearing profile and asserts the stored profile keeps the payload *and* its signature.
- `IdentityRemapGameTest.anAliasedPlayersChatReachesEveryoneAsDisguisedChat` — a test player joined **with an alias uuid** through the real `placeNewPlayer`, so the real chat listener runs. Asserts the line arrives as disguised chat on the `mctraveler:chat` type with the green username, does *not* also arrive as player chat, and that an ordinary player alongside them keeps the signed player-chat path. Proven red by disabling the hook.

Inspection only, stated plainly:

- **The inbound decoder exemption.** `MinecraftServer.enforceSecureProfile()` returns `false` and only `DedicatedServer` overrides it, so on `GameTestServer` the gate is never shut and the exemption cannot be observed. Ticket 16's limit compounds it: fake players bypass login entirely.
- **The command-signing redirect**, for the same reason.
- **That the carried `textures` property actually renders.** The property surviving the swap is tested; the client chain `PlayerInfo.getSkin` → `SkinManager.createLookup` → `unpackTextures` is read, not run — there is no client in a headless gametest.
- **That other clients accept disguised chat unconditionally.** `ClientPacketListener.handleDisguisedChat` hands straight to the chat listener with no validator; read, not run.

### What the operator must do

Production is currently running the stopgap `enforce-secure-profile=false`. To land this fix and get signed chat back:

1. Build the jar: `./gradlew build` (with `JAVA_HOME` on JDK 25).
2. Replace the mod jar in the server's `mods/` directory.
3. **Set `enforce-secure-profile=true` in `server.properties`**, reverting the stopgap. Without this the fix still works, but everyone's chat stays optional-unsigned and the community keeps seeing the insecure-server toast.
4. **Restart the server.** Both halves are mixins into classes loaded at startup (`ServerLoginPacketListenerImpl`, `ServerGamePacketListenerImpl`); nothing here is reloadable and `/reload` will not do it.

No data migration, no importer re-run, nothing to back out. The two aliased players must reconnect after the restart to pick up their skin — the property rides in on the login packet.
