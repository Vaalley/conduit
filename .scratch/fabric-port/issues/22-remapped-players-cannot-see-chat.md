# 22 — Remapped players send chat but cannot see anyone else's

**What to build:** The two aliased players (`AlsoJames` → `iElmo`, `DemonicNoodle` → `travelcraft2012`) see everyone's chat again, without giving up signed chat for everyone else.

**Blocked by:** 21 (their send side, shipped). **Live production bug** — reported after ticket 21's cutover.

**Status:** done

## The symptoms

On `play.mctraveler.eu`, as `iElmo` and `travelcraft2012`:

1. Their own lines reach everyone — ticket 21's send-side exemptions work.
2. Private messages (`/msg`, `/reply`) render for them — reported explicitly: "PMs work fine".
3. Presence lines (join/leave) render for them.
4. **No one else's chat renders for them.** No error line reported, just absence.

## The diagnosis

Everything they *can* see travels as **system chat** (`ClientboundSystemChatPacket` — the mod's PMs are `sendSystemMessage`, so are presence lines). Everything they *cannot* see travels **player-voiced** — signed player chat (`ClientboundPlayerChatPacket`) or disguised chat (`ClientboundDisguisedChatPacket`). That split is the whole diagnosis.

Read against the mapped 26.2 jar, the server-side per-recipient delivery of player chat is identical for every connection — `PlayerList.broadcastChatMessage` → `ServerPlayer.sendChatMessage` → `sendPlayerChatMessage` packs per-connection state (`nextChatIndex`, `MessageSignatureCache`, pending acknowledgements) that provably cannot diverge without a disconnect, and the client-side validation of a *sessioned* sender's signed message uses only recipient-independent inputs (the sender's broadcast session, the signature over link+body, full-signature fallback for cache misses). None of that can fail selectively for one recipient. What *can* — and does — differ per recipient is the set of gates a vanilla 26.2 client applies to player-voiced chat, and only to player-voiced chat, all invisible to the server:

- **Friends-only chat** (`UserFlag.CHAT_FRIENDS_ONLY`, the Microsoft account privacy setting): `ChatListener.showMessageToPlayer` returns `false` for any sender failing `Minecraft.isFriendOnlyRestricted` — silently, no error line. The system-message path checks the *guessed* `<name>` uuid only, which the mod's formats never match, so system chat passes.
- **Profile/options chat restrictions** (`ChatAbilities`): removing `CHAT_RECEIVE_PLAYER_MESSAGES` makes the chat HUD filter the whole PLAYER message source at render time (`setVisibleMessageFilter`), hiding player *and* disguised chat while SYSTEM_SERVER stays visible. Sending is never gated — `ChatScreen.handleChatInput` sends without consulting `canSendMessages()`.
- **Commands-only chat visibility**: server-side `acceptsChatMessages()` requires `FULL`, so player-voiced chat is never sent, while plain sends still broadcast (only `HIDDEN` blocks them in `tryHandleChat`) and system messages still arrive.

Which gate holds on the two players' actual clients cannot be determined from the server, and the remap makes their client state unusual in ways vanilla never exercises (a local profile id that is not the authenticated id). What is certain: **every candidate gate passes the system channel**, and the system channel demonstrably works for exactly these two players (symptoms 2–3).

## What was built

`AliasedPlayerChatDeliveryMixin` (`ServerPlayer.sendChatMessage`, the one seam both `OutgoingChatMessage` variants pass through, ahead of the chat-visibility gate): for a recipient with an aliased uuid — `IdentityRemaps.isAliased`, the same predicate as ticket 21 — the delivery is cancelled and the line sent with `sendSystemMessage`, decorated server-side with the same `ChatType.Bound` the client would have applied (`Bound.decorate`). The per-recipient filter flag and the filter mask are applied exactly as `OutgoingChatMessage.Player.sendToPlayer` and the client's `ChatListener` would, so a fully-filtered message still shows nothing. Rendering is pixel-identical — the mod's chat type is a plain `"%s %s"` format that decorates the same on either channel.

Every other player is untouched: their chat stays signed player chat, reportable, exactly as ticket 21 left it.

- [x] An aliased player sees everyone's chat again: ordinary players', the other aliased player's, and their own echo.
- [x] Everyone else's chat stays signed and reportable; `enforce-secure-profile` stays `true`; the outbound disguised-chat conversion (deviation 57) is untouched.
- [x] Scoped to exactly the aliased uuids, keyed off `IdentityRemaps.isAliased` — the remap table stays the single source of truth.
- [x] Tests: gametest `anAliasedPlayerReceivesChatAsSystemMessages` — an ordinary line reaches the aliased recipient as the decorated system line, their own echo comes back, nothing player-voiced is sent to them, and an ordinary recipient keeps the signed player-chat path (control).

## The cost, stated plainly

Mirror of deviation 57's blast radius, now on the inbound side: chat these two players **receive** arrives on their client without a signature, so *they* cannot chat-report what they are shown (everyone else's copy of the same lines stays signed and reportable, and the server log keeps everything). If a client-side gate was indeed hiding player chat for them, they may also have chosen that state — commands-only visibility is a deliberate setting — and this fix overrides it for these two; both players asked for their chat back, so that is the right default. Recorded as deviation register entry 58.

## Verified by test vs. by inspection

Tested — green `./gradlew build`:

- `IdentityRemapGameTest.anAliasedPlayerReceivesChatAsSystemMessages`, as above, through the real `placeNewPlayer` join and the real broadcast pipeline.
- The existing ticket-21 gametest still proves the outbound conversion for ordinary recipients.

Inspection only, stated plainly:

- **That a real aliased client renders the rerouted lines.** The system channel is the channel PMs and presence lines already reach them on (reported working in production); the gametest proves the packets, not a vanilla client's screen.
- **The three candidate client-side gates** are read from the mapped 26.2 client (`ChatListener`, `ChatAbilities`, `ChatScreen`, `Minecraft.isFriendOnlyRestricted`), not observed on the players' machines. The fix does not depend on which one holds.

## What the operator must do

1. Build the jar: `./gradlew build` (JAVA_HOME on JDK 25).
2. Replace the mod jar in the server's `mods/` directory.
3. **Restart the server** — a mixin into `ServerPlayer`, loaded at startup; `/reload` will not do it.

No config change, no data migration. The two aliased players do not need to reconnect — the reroute applies per delivery. If they also want their *client* state clean, they can check Chat Settings → Chat: Shown, and the Microsoft account's "friends only" communication setting — but nothing in this fix requires it.
