# 14 — Region player-action protection

**What to build:** Regions actually protect against players: non-members can't dig, place, edit signs, use containers, use items, or harm entities in a region, each refusal carrying the Portal's exact error — with the Portal's nuanced entity and container rules, and region tracking that now also catches teleport and portal arrivals.

**Blocked by:** 12 (Region core), 13 (Membership + scoreboard).

**Status:** done

See `../spec.md` (User Stories 34, 38–39; deviation register 9) and the RegionFeature protection subsection of `docs/research/portal-feature-inventory.md` for exact rules. Enforcement is server-side event cancellation (no fake gamemode). Admins bypass management, never protection.

- [x] Dig, place, and sign-edit attempts by non-members are cancelled at the target block's region with `This area is protected by <name>`
- [x] Container rule uses the region captured at container-open time; PUBLIC and ENABLE_PUBLIC_CONTAINERS open access
- [x] Item use blocked in a non-modifiable region; entity rules: attack always blocked, held-item interaction blocked unless ENABLE_PUBLIC_VILLAGER_TRADING, empty-hand interaction allowed, DISABLE_ANIMAL_PROTECTION bypasses
- [x] Current-region tracking updates on movement, teleports, portals, and Travel; membership changes re-evaluate protection immediately
- [x] Gametests: each action type allowed/refused across member, non-member, PUBLIC, and admin cases, including teleport-entry

## Comments

Implemented as `eu.mctraveler.region.RegionProtection` plus three small Java mixins. `./gradlew build` green: unit tier plus **157 headless gametests** (131 pre-existing, 26 new in `RegionProtectionGameTest`).

**The seam ticket 15 extends.** `RegionProtection` is the one place a region says no, and it holds the two predicates the environmental ticket needs:

- `RegionProtection.canModifyRegion(player, region)` — resident **or** `PUBLIC`; a null region answers true. Admins are deliberately absent (operator status bypasses management, never protection — `anAdminIsStillAStrangerToProtection` pins it).
- `RegionsFeature.regionAt(level, pos)` — the **block-shaped** lookup (deepest match, legacy world string resolved via `RegionWorlds`). Ticket 15's rules mostly have no player at all — an explosion, a fire, a piston, a creeper — so this is the lookup they want; `RegionTracker.regionOf(player)` stays the player-shaped one.
- `RegionProtection.allowsBlockChange(player, level, pos)` answers *and* sends the refusal, so "every refusal carries the message" is true by construction. Anything ticket 15 adds that a player performs should go through it.

**Hook points, one per action** (all registered from `RegionProtection.register()`, called by `RegionsFeature.register()`):

| Action | Hook | Region consulted |
| --- | --- | --- |
| Dig (start) | `AttackBlockCallback` | target block |
| Dig (break) | `PlayerBlockBreakEvents.BEFORE` | target block |
| Place / apply an item to a block | `ItemEvents.USE_ON` | clicked block |
| Sign edit | `RegionSignEditMixin` → `ServerGamePacketListenerImpl.updateSignText` | sign block |
| Container open / close | `RegionContainerSessionMixin` → `ServerPlayer.initMenu` / `doCloseContainer` | captures the player's region |
| Container click | `RegionContainerClickMixin` → `AbstractContainerMenu.clicked` | the captured one |
| Item use | `UseItemCallback` | player's region |
| Entity attack | `AttackEntityCallback` | player's region |
| Entity interact | `UseEntityCallback` | player's region |

Tracking (`RegionTracker`) gained `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL` and `ServerPlayerEvents.AFTER_RESPAWN` alongside the per-tick sweep, so portals, Travel and respawns register the arrival in the same breath rather than at the end of the tick (deviation 9).

**Deviations / interpretations recorded here:**

- **"Block place" means an item applied to a block, not every right-click.** The Portal's block-place hook was the raw `use_item_on` packet, which also carries opening a chest, pressing a button and opening a door. Ported literally it would make `ENABLE_PUBLIC_CONTAINERS` unreachable (the chest could never be opened to begin with) and leave nothing for ticket 15's `DISABLE_GATES` / `DISABLE_PUBLIC_REDSTONE_TRIGGERS` to disable — both flags only make sense if non-members can use those blocks by default. The port therefore splits what a server can see and a proxy could not: the item acting on the block (`ItemEvents.USE_ON` — placing, tilling, striking a light) is refused; the block's own right-click behaviour is left alone, governed by the container rule and, from ticket 15, those two flags.
- **"Holding an item" is the acting hand's item.** The Portal's `HeldItemModule` only ever knew the selected hotbar slot, because a proxy cannot see inventories; the server can, so an off-hand use is an item use and an off-hand interaction is a held-item interaction. Same rule, no longer half-blind.
- **Attacks are refused for every entity, not just animals.** The Portal's rule made no distinction — only the flag's name (`DISABLE_ANIMAL_PROTECTION`) mentions animals — so hitting another player inside a region you cannot modify is refused too.
- **No fake gamemode, no dig acknowledgement.** Both were proxy workarounds (spec: out of scope). Fabric's own events resync the client after a refused dig or place, so nothing ghosts. The visible cost is the Portal's nicety of the client refusing first: a stranger now sees the break animation play and the block reappear.
- **Membership changes need no re-evaluation step** — the debt ticket 13 recorded is discharged by construction rather than by code. Protection caches nothing: every decision reads the live region and its live member set, so `/rg add` and `/rg remove` are in force for the target's very next action (`aNewMemberMayBuildAtOnce`, `aRemovedMemberIsRefusedAtOnce`). The single piece of state in the whole module is the container-open capture, which is deliberate and still checks membership live.
- **A dig is guarded twice** — at the first packet (`AttackBlockCallback`, which is also the whole of an instant break) and at the break itself (`PlayerBlockBreakEvents.BEFORE`, the authoritative one). Only one of them fires per attempt in practice: cancelling the start stops the break ever being reached.
- **Trap for the next reader:** `ItemEvents` (unlike `AttackBlockCallback`, `UseItemCallback`, `UseEntityCallback`, `AttackEntityCallback`) treats **null**, not `InteractionResult.PASS`, as "no listener handled this". Returning `PASS` from it silently cancels the interaction with a passing result — which is exactly how the first cut of block placement failed.
- **Gametest harness:** `MessageCapturingPlayer.join` now acks the world load (`ServerboundPlayerLoadedPacket`) and puts the player in **survival**. The gametest server's default gamemode is creative, which quietly changes what items do — buckets are not filled, stacks are not consumed, blocks break instantly — so protection tests were passing and failing for the wrong reasons. MCTraveler is a survival server; the mock players now match it.
