# Spec: region interactables — a finer interaction verdict

Status: ready-for-human

From GitHub issue `https://github.com/Vaalley/conduit/issues/40` ("Enchantment
tables in regions", expanded by the reporter into the full list below). Phase 2.
Companion docs: `../fabric-port/spec.md` (house conventions), `CONTEXT.md`,
`docs/research/portal-feature-inventory.md` §2.8, and the ticket-14/15 notes in
`../fabric-port/issues/14-region-player-protection.md` and `15-…`.

## Problem statement

Today `RegionProtection` judges a non-member's right-click coarsely: any item
applied to any block in a foreign region is refused with
`This area is protected by <name>`, and any non-exempt item used at the player's
feet is refused the same way. So a stranger standing on someone's land cannot
right-click a crafting table while holding a pickaxe, cannot open a furnace to
look inside while holding coal, and is told off for raising a spyglass. The
region owner's intent — "don't *change* my place" — has hardened into "don't
touch anything."

The reporter wants the verdict to follow what the interaction actually does:
workstations and read-only blocks open for anyone and say nothing; the blocks
that change a region's contents stay refused; a right-click that changes nothing
never shows an error.

## Solution

Classify every block, entity and item interaction by whether it modifies the
region, and refuse (with the existing message) only the ones that do. Keep the
existing container-session model (open to view, clicks refused) for the blocks
whose GUI holds someone's items. Add two opt-in flags, `BOAT` and `MINECART`,
for the vehicles a region owner wants left alone.

**Existing rules are unchanged unless named here.** Digging, placing, sign
editing, the container-click refusal, the `DISABLE_*` flags, `PUBLIC`,
`ENABLE_PUBLIC_CONTAINERS`, admin-is-still-a-stranger, and the refusal-message
throttle all stay exactly as they are.

## Definitions

- **Member** — a resident of the region, or any player when the region is
  `PUBLIC`. `canModifyRegion` already answers this. Everything below is about
  **non-members**; a member's interactions are never touched.
- **The message** — the existing throttled action-bar refusal
  `This area is protected by <red name>` (`RegionProtection.refuse`).
- **Silent refuse** — cancel the interaction, send nothing. Used only where the
  spec explicitly asks for it (currently: nowhere — every refusal below carries
  the message; kept as a named concept for the classifier).

## Block interaction classes

When a non-member right-clicks a block in a region, the block falls into one
class. Holding an item does **not** change the class — the item's own effect is
judged separately (see "Items applied to a block").

### FREE — open for anyone, no message, ever

The block's own right-click behaviour runs normally.

| Block | Class match |
|---|---|
| Crafting table | `CraftingTableBlock` |
| Cartography table | `CartographyTableBlock` |
| Smithing table | `SmithingTableBlock` |
| Grindstone | `GrindstoneBlock` |
| Loom | `LoomBlock` |
| Enchanting table | `EnchantingTableBlock` |
| Lodestone | `LodestoneBlock` (compass linking modifies the compass, not the region) |
| Bell | `BellBlock` |

### CONTAINER_VIEW — GUI opens, slot clicks refused

The open is allowed so a non-member can look inside; `RegionContainerClickMixin`
already refuses every slot click (that refusal carries the message). Today the
open is *also* refused whenever the player holds an item — this class removes
that.

The container is judged by the region of **the block it is**, not the opener's
feet, so reaching one from outside the region does not unlock it (ticket 01a).
A non-member opening one sees the screen titled **Region protected** (grey,
bold) — every container a non-member cannot modify, not only this class.

| Block | Class match |
|---|---|
| Furnace / Smoker / Blast furnace | `AbstractFurnaceBlock` |
| Dropper / Dispenser | `DispenserBlock` (Dropper extends it) |
| Brewing stand | `BrewingStandBlock` |
| Lectern | `LecternBlock` — reading turns pages; taking the book is a button, refused by the block-use hook |
| Beacon | `BeaconBlock` — viewing the pyramid is fine; choosing an effect and paying is the modify, refused by the block-use hook |

### REQUIRES_MEMBERSHIP — refused with the message

| Block | Class match | What it would change |
|---|---|---|
| Crafter | `CrafterBlock` | its ingredients are the region's and its slot layout is part of the build; a non-member does not open it (the toggle-a-slot packet rides its own path the container hooks don't see, so "view only" is not enforceable here anyway) |
| Composter | `ComposterBlock` | fill; a full composter cannot be emptied either |
| Cauldron (all four) | `AbstractCauldronBlock` | fill / empty / dye / wash |
| Chiselled bookshelf | `ChiseledBookShelfBlock` | insert / take a book |
| Decorated pot | `DecoratedPotBlock` | insert an item |
| Jukebox | `JukeboxBlock` | insert / eject a disc |
| Daylight detector | `DaylightDetectorBlock` | flip day/night mode |
| Note block | `NoteBlock` | retune |
| Anvil (all three) | `AnvilBlock` | opens a working menu; costs the user XP and items |
| Flower pot (all) | `FlowerPotBlock` | pot / unpot a plant |
| Bee nest / beehive | `BeehiveBlock` | (shears/bottle harvest is an item, see below; the block right-click has no vanilla behaviour, so this row is only reached by a future one) |
| Any wooden shelf | `ShelfBlock` | place / take an item on the shelf |
| Dragon egg | `DragonEggBlock` | **and** the egg must not teleport — the right-click is cancelled before `DragonEggBlock.teleport` runs |
| Respawn anchor | `RespawnAnchorBlock` | charge / set spawn / **explode** — see ticket 05 for the never-explode half |

### CAMPFIRE — `CampfireBlock` (and soul campfire)

- Placing a **cookable food item** on the campfire → allowed, no message.
- Any interaction that would extinguish or relight it (a water bottle, a shovel
  if this version supports it, flint & steel, a fire charge) → refused with the
  message.

## Items applied to a block (`ItemEvents.USE_ON`)

`ItemEvents.USE_ON` fires only after the block declined the click, so its firing
means the *item* is about to act. Refuse it for a non-member when the item is
**region-modifying**:

- any `BlockItem` (placement)
- `BucketItem`, `MobBucketItem`, `PowderSnowBucketItem`
- `BoneMealItem` — **and** bonemeal never works for a non-member anywhere in a
  region even where it would (this is the one place the refusal is by item, not
  by target block)
- `FlintAndSteelItem`, `FireChargeItem`
- `HoeItem`, `ShovelItem`, `AxeItem`, `ShearsItem`
- entity-placing items: `ArmorStandItem`, `ItemFrame`/`GlowItemFrame`,
  `PaintingItem`, `EndCrystalItem`, `SpawnEggItem` (all), `LeadItem`
  (fence knot). Boats and minecarts are ticket 03.
- `GlassBottleItem`, `HoneycombItem`, `GlowInkSacItem`, `InkSacItem`
- the existing `exemptUseChangesBlock` cases (water potion → mud, ender eye →
  end portal frame)

Every other item that reaches `USE_ON` (a sword, a spyglass, a map, a stick, a
compass, an empty hand) does nothing to the block → **pass, no message.**

Signs are dyed / glow-inked through the block's own `useItemOn`, so a
`SignBlock` is REQUIRES_MEMBERSHIP for the block-use hook (a non-member editing
a sign is already refused; this closes recolouring).

## Blocks used with an item (`BlockEvents.USE_ITEM_ON`)

New hook. Same classifier as the empty-hand block hook, plus the SignBlock and
CampfireBlock rows above. This is where "holding coal, right-click furnace" is
allowed to open the furnace, and "holding a water bottle, right-click campfire"
is refused.

## Items used at the player's feet (`UseItemCallback` / `ItemEvents.USE`) — ticket 02

Flip the model. Today a non-member is refused for any non-exempt item. Instead:
**allow every item whose use does not affect the region**, refuse the rest with
the message.

- Always allowed: any food (`DataComponents.FOOD`), potions / milk / honey /
  golden apples (existing), firework rockets (elytra boost), and anything with
  no world effect at the feet — spyglass, maps, compass, clock, bundle, goat
  horn, written/writable book, brush, empty bucket held in air, name tag,
  spawn eggs used in air (they do nothing), etc.
- Still refused: `EnderpearlItem` and `EnderEyeItem` are **removed** from the
  exempt list only if they turn out to affect the region — pearls teleport the
  thrower, not the land, so they stay allowed; keep as-is unless a gametest says
  otherwise.
- Fishing rod, bow, crossbow, trident, snowball, egg, splash/lingering potion:
  these launch projectiles. They do not modify blocks; leave them allowed. (A
  region does not stop a stranger fishing off someone's dock.)

The net effect: the only feet-level item uses a region still refuses are ones
that place or change something, which in practice `UseItemCallback` rarely sees
(those go through `USE_ON`). Ticket 02 confirms the list against gametests.

## Vehicles — `BOAT` and `MINECART` flags — ticket 03

Two new entries in `RegionCommands.VALID_FLAGS`, after `DISABLE_GATES`'s group,
admin-toggled like every other flag.

**Default (flag not set):** a non-member may place, ride, and break boats and
minecarts in the region freely — they are the one kind of "block" a region does
not protect by default.

**Flag set:** the vehicle type is fully protected for non-members —
- cannot be placed (`BoatItem` / `MinecartItem` join the region-modifying item
  list, gated on the flag)
- cannot be broken (attack is refused — `allowsEntityAttack`)
- cannot be ridden or otherwise interacted with (`allowsEntityInteract`)

**Chest boat and chest minecart** — the container is always view-only for a
non-member (open the GUI, clicks refused), with or without the flag. With the
flag, the ride / place / break protection applies on top.

`Minecart with Hopper`, `Minecart with Furnace`, and `Minecart with TNT` follow
the `MINECART` flag for ride/place/break; the hopper minecart's container is
view-only; the TNT minecart also never detonates (ticket 05).

## Mobs — ticket 04

Rework `allowsEntityAttack` / `allowsEntityInteract` for non-members:

- **Hostile mob, no name tag** → may be attacked and killed. `Enemy` marker
  interface, `customName == null`.
- **Hostile mob with a name tag** → protected (attack refused).
- **Friendly / passive mob** (not `Enemy`) → protected: attack refused, and
  held-item / breeding / leashing interaction refused (empty-hand interaction
  that does nothing stays allowed and silent).
- **Horse / other rideable equine without a chest** → a non-member may mount and
  ride it (right-click to ride is allowed); attacking it is still refused.
- **Donkey / mule with a chest** → crouch-right-click opens the chest GUI
  (view-only, clicks refused); it may not be ridden and its inventory may not be
  changed.
- Item frames and armor stands stay fully protected (existing).

## Never-detonate, never-teleport — ticket 05

Inside a region, regardless of who or what triggered it:

- **End crystal** never explodes — not from a player hit (already refused), not
  from a non-player projectile (arrow, snowball, egg, …), not from another
  explosion. The crystal simply cannot be detonated while it stands in a region.
- **Respawn anchor** never explodes — a non-member cannot charge or trigger it
  (REQUIRES_MEMBERSHIP), and even a member's over-charge / wrong-dimension use
  does not blow up inside a region.
- **TNT minecart** never detonates inside a region — no activator rail, no fire,
  no entity thrown at it — and its item cannot be moved by a non-member in any
  way (place / break / push all refused when `MINECART` is set; the container
  n/a).
- **Dragon egg** never teleports on right-click inside a region (the FREE-vs-
  REQUIRES classifier already cancels the non-member click; this covers the
  member case and any redstone/piston nudge — the egg stays put).

Mostly small `:bootstrap` mixins (`RegionEndCrystalMixin`,
`RegionRespawnAnchorMixin`, `RegionTntMinecartMixin`, `RegionDragonEggMixin`)
handing a `pos`/`entity` to a new `RegionEnvironment` predicate, in the style of
`RegionExplosionMixin` and `RegionFarmlandMixin`.

## Out of scope / deviations

- **No new message strings.** Every refusal uses the one existing message.
- **The Portal had none of this** — it saw packets, not block types. The whole
  classifier is new; it is recorded here rather than in a deviation register
  against the Portal, per the worktree rule for port-era additions.
- **"Any item that does not affect the region should be allowed"** is a
  principle, not an enumerable set. The item lists above are the working cut;
  gametests pin the ones that matter and the classifier's default (pass) covers
  the rest.
- Bonemeal for **members** is unchanged — they may still fertilise their own
  crops.

## Tickets

1. **Block interaction classifier** — the FREE / CONTAINER_VIEW /
   REQUIRES_MEMBERSHIP / CAMPFIRE tables; rewire `ItemEvents.USE_ON` to the
   region-modifying-item list; add the `BlockEvents.USE_ITEM_ON` hook; bonemeal
   non-member refusal. Silent pass for everything else. Gametests per class.
2. **Harmless item-use at the feet** — flip `UseItemCallback` / `ItemEvents.USE`
   to allow-unless-region-affecting.
3. **`BOAT` and `MINECART` flags** — flags, default-open vehicles, full
   protection when set, chest-vehicle view-only.
4. **Mob interaction rules** — hostile-no-nametag killable, friendly/nametagged
   protected, horse ride, donkey-chest view-only.
5. **Never-detonate / never-teleport** — end crystal, respawn anchor, TNT
   minecart, dragon egg.
6. **Parity sweep + docs** — full table vs gametests; `CONTEXT.md` and
   `docs/` for the two new flags; final `/code-review` against this spec.
