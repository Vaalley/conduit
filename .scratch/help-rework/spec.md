# Rework /help (issue #89)

`/help` lists the commands the reader can use, ten to a page, with clickable
command names and page numbers; `/help <command>` explains one command; `/help
markdown` shows the sign colour codes. Audience-gated commands disappear from
the client's `/` suggestions too.

## Colours

The issue used dye names (`light_gray` = `Paint.gray`, `gray` = `Paint.darkGray`); after review both required and optional parameters are `Paint.gray`.

- header, footer, hint: `Paint.yellow` (hint italic); struck-through runs are 12 spaces
- command names: `Paint.white`
- `<x>`, `[x]` and plain literals: `Paint.gray` (the brackets alone mark optional)
- header text `( Help menu )`; footer `[strike]( Page 1 2 3 )[strike]` (no spaces outside the parentheses), current
  page bold + underlined, the others `runs("/help N")`

## The list

- Every root command in the live dispatcher that the reader `canUse`: mod and
  vanilla alike (vanilla already hides op-only commands by permission level).
- Alias groups collapse to the primary name: a root that `redirect`s to another
  root is an alias (`tell`/`w` -> `msg`, `r` -> `reply`, `tm` -> `teammsg`),
  detected generically; separately registered alias trees are declared in the
  catalog (`region` declares `rg`, which stays its own tree).
- `/help` first, everything else alphabetical (case-insensitive), 10 per page.
- Vanilla's `/help` is removed (`CommandTree.removeRootCommands`) and replaced.
- Errors are `Paint.error`: unknown (or unusable, indistinguishably) command,
  out-of-range or invalid page ("Choose a page between 1 and N.").
- Argument suggestions offer only usable command names (aliases included), page
  numbers, and `markdown`.

## Syntax line

Derived from the Brigadier tree, one compact line: a chain of single children
renders as `<arg>` (or the literal itself); once the command is executable
before a child, that child and everything after it is `[optional]`. A node with
several children collapses to `<option>`, or `[option]` when the node is also
executable. Admin-only options (catalog) do not count for non-admins, so
`/rtp` reads `/rtp [end]` to them and `/rtp [option]` to an admin.

## Command page

Header; white `/region, /rg` (primary, then aliases); light-gray `Available
options are: ...` (literal children the viewer can use, minus catalog
`adminOnlyOptions` for non-admins); then the explanation. Commands without a
catalog entry (vanilla) show their Brigadier usage lines (max 10) and "No
description is available for this command."

## Catalog

`help/HelpCatalog.kt`: per mod command a description, explicit aliases and
`adminOnlyOptions`. Every mod command has one (`/spawnN` is derived from the
configured crystal spawns). Region caps come from `Rank.regionAreaCap` (5000,
10000 for Donators).

## /help markdown

Every `%` code `Markdown.parse` understands: `%0`-`%9`, `%a`-`%f` (named by
their `ChatFormatting` name), decorations `%k %l %m %n %o`, reset `%r`, each in
its own style, plus a note that sign markdown is for Donators and admins and
that `<name>` shows each reader their own name. Visible to everyone.

## Audience gating

`command/Audience`: `ALL`, `TRAVELER` (not Newbie, admins pass), `DONATOR`
(Donator, admins pass), `ADMIN` (`RegionsFeature.isAdmin`). `Audience.gate` is a
`requires` predicate that never throws (an unreadable rank reads as Traveler)
and lets non-player sources through. `requires` on a root hides it from the
client tree and makes it unknown to everyone outside the audience.

Gated roots (wholly admin-only): the 17 moderation commands (`ban`, `unban`,
`pardon`, `banlist`, `mute`, `unmute`, `kick`, `warn`, `warnings`, `unwarn`,
`history`, `note`, `whois`, `seen`, `invsee`, `inspect`, `lookup`), `vanish`,
`rank`, `embassy`, `economy` (its only subcommand is `stats`),
`set-teleportation-crystal-energy`. Their in-body `adminGate`s stay as a
second line.

Deliberately left public at the root, with the admin part gated in the body and
hidden from non-admins' option lists: `region`/`rg` (`bounds`, `locate`),
`balance` (`set`, `give`), `rtp` (`sign`), `dragonfight` (`reset`), `name`
(`reset <player>`). No root is restricted to Travelers or Donators today, so
those audiences are defined and unit-tested but unused; sign markdown is the
Donator perk and is not a command.

The client tree is re-sent (`sendCommands`) when a player's rank changes: after
`/rank set` for an online player (via `RankFeature.setRank`) and on the
Newbie -> Traveler promotion. Vanilla already re-syncs on `/op` and `/deop`.

## Tests

- unit: `src/test/kotlin/eu/mctraveler/help/HelpTest.kt` (stub dispatcher)
- gametest: `HelpGameTest` (listing, buttons, errors, `/help region`, markdown,
  client tree via `ClientboundCommandsPacket`, rank/promotion re-sync); existing
  crystal/embassy/rank tests now expect the unknown-command reply for
  non-admins on wholly admin-only roots.

## Command page layout (after review)

Header, then the names line (`/region, /rg`, white bold), the `Available options are: …`
line (white), a blank line, the explanation (gray), and a blank line that ends the page so
it does not run into the next chat message. `/help markdown` ends with the same blank line.
