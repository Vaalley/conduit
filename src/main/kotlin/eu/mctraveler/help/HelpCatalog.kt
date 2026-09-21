package eu.mctraveler.help

import eu.mctraveler.cosmetic.CosmeticFees
import eu.mctraveler.crystal.CrystalSpawns
import eu.mctraveler.economy.Economy
import eu.mctraveler.rank.Rank
import eu.mctraveler.store.StoreFees

/**
 * What `/help <command>` says about one command.
 *
 * @property description the explanation, one paragraph per line (`\n`).
 * @property aliases names of separately registered command trees that are the
 *   same command under another name (`rg` for `region`). A Brigadier `redirect`
 *   alias (`tell` for `msg`) needs no declaration: `/help` detects it.
 * @property adminOnlyOptions subcommands that stay in-body-gated to admins; they
 *   are left out of the "Available options" list for everyone else.
 */
data class CommandHelp(
    val description: String,
    val aliases: List<String> = emptyList(),
    val adminOnlyOptions: Set<String> = emptySet(),
)

/**
 * The per-command help text, keyed by primary command name. Commands without an
 * entry fall back to their Brigadier usage in the help pages. [hidden] names are
 * left out of the menu altogether.
 */
class HelpCatalog(
    private val entries: Map<String, CommandHelp>,
    private val fallback: (String) -> CommandHelp? = { null },
    /** Root commands the help menu never lists (see [VanillaHelp.HIDDEN]). */
    val hidden: Set<String> = emptySet(),
) {
    fun of(name: String): CommandHelp? = entries[name] ?: fallback(name)

    /** Every declared alias name, mapped to the primary command it belongs to. */
    val declaredAliases: Map<String, String> =
        entries.flatMap { (primary, help) -> help.aliases.map { it to primary } }.toMap()

    companion object {
        val EMPTY = HelpCatalog(emptyMap())

        /** The catalog of every command the mod registers, and of the vanilla ones it keeps in the menu. */
        val DEFAULT: HelpCatalog by lazy {
            HelpCatalog(defaultEntries() + VanillaHelp.ENTRIES, ::spawnHelp, VanillaHelp.HIDDEN)
        }

        private val spawnCommand = Regex("spawn(\\d+)")

        /** `/spawn1`, `/spawn2`, ...: one command per configured crystal spawn. */
        private fun spawnHelp(name: String): CommandHelp? {
            val index = spawnCommand.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val place = CrystalSpawns.definitions().getOrNull(index - 1)?.name ?: return null
            return CommandHelp(
                "Teleports you to $place after a short countdown. It is free and does not use any " +
                    "Teleportation Crystal energy. Moving during the countdown cancels the trip.",
            )
        }

        private fun defaultEntries(): Map<String, CommandHelp> {
            val travelerCap = Rank.TRAVELER.regionAreaCap
            val donatorCap = Rank.DONATOR.regionAreaCap
            return mapOf(
                "help" to CommandHelp(
                    "Lists the commands you can use, 10 at a time. Click a command to read about it, or a page " +
                        "number to jump to that page.\n" +
                        "Use /help <page> for a page, /help <command> for one command, and /help markdown for " +
                        "the colour codes you can use on signs.",
                ),
                "away" to CommandHelp(
                    "Marks you as away straight away and tells everyone. You are also marked away after 5 idle " +
                        "minutes, and moving or doing anything brings you back. Right after coming back you have to " +
                        "wait a few seconds before you can use /away again.",
                ),
                "chat" to CommandHelp(
                    "Chooses who hears the messages you type.\n" +
                        "/chat sends them to everyone again, /chat server keeps them on the server (they are not " +
                        "mirrored to Discord), /chat region sends them only to the members of the region you are " +
                        "standing in, and /chat <players> sends them privately to the players you name " +
                        "(separate several names with commas).",
                ),
                "shrug" to CommandHelp("Says ¯\\_(ツ)_/¯ in chat, as if you had typed it yourself."),
                "tableflip" to CommandHelp(
                    "Says (╯°□°）╯︵ ┻━┻ in chat, as if you had " +
                        "typed it yourself.",
                ),
                "msg" to CommandHelp(
                    "Sends a private message to a player who is online. Use /reply to answer the last player " +
                        "you talked with.",
                ),
                "reply" to CommandHelp("Sends a private message to the last player you exchanged messages with."),
                "name" to CommandHelp(
                    "Personalises how your name looks in chat and the tab list. Each change is paid for from your " +
                        "balance.\n" +
                        "/name tag <character> adds a single character or emoji next to your name " +
                        "(${Economy.format(CosmeticFees.TAG)}), /name color <hex> colours your name with a hex " +
                        "colour such as #ff8800 (${Economy.format(CosmeticFees.COLOR)}), and /name gradient <from> " +
                        "<to> fades your name between two hex colours (${Economy.format(CosmeticFees.GRADIENT)}).\n" +
                        "/name reset removes all of it for free.",
                ),
                "balance" to CommandHelp(
                    "Shows how much money you have.\n" +
                        "/balance top opens the list of the richest players, and /balance history [page] shows " +
                        "your recent transactions, ten per page.",
                    adminOnlyOptions = setOf("set", "give"),
                ),
                "pay" to CommandHelp(
                    "Sends money from your balance to a player who is online, for example /pay Steve 12.50. " +
                        "Amounts can have cents.",
                ),
                "store" to CommandHelp(
                    "Turns an item frame into a shop. Put an item in a frame, look at it, and run a command.\n" +
                        "/store create <price> makes the frame sell that item for the price you set each " +
                        "(${Economy.format(StoreFees.CREATE)}). /store buy <price> [max] makes it buy that item from " +
                        "other players instead, optionally up to a maximum amount.\n" +
                        "/store upgrade gives a store more room (${Economy.format(StoreFees.UPGRADE)}), and " +
                        "/store delete removes it and hands back its stock. Only the owner can upgrade or delete a " +
                        "store.\n" +
                        "Right-click your own store to manage its stock; right-click someone else's to trade.",
                ),
                "map" to CommandHelp("Sends you a link to the live web map, pointing at where you are standing."),
                "notepad" to CommandHelp(
                    "Opens your private notepad, a book that is with you everywhere. What you write in it is saved " +
                        "for next time.",
                ),
                "passport" to CommandHelp(
                    "Opens your passport, which records your journey on the server: the stamps you earned, your " +
                        "regions and embassies, and the postcards you sent.\n" +
                        "/passport <player> opens someone else's passport, and /passport text prints a passport in " +
                        "chat instead.",
                ),
                "postcard" to CommandHelp(
                    "Sends a postcard from where you are standing and adds it to your passport. You can add a " +
                        "caption, for example /postcard What a view!\n" +
                        "You can send one postcard every 5 minutes.",
                ),
                "rtp" to CommandHelp(
                    "Teleports you to a random safe spot in the overworld, outside of any region, after a short " +
                        "countdown. It only works in the overworld and has a cooldown between uses.\n" +
                        "Once you have freed the End (see /dragonfight), /rtp end does the same in the End.",
                    adminOnlyOptions = setOf("sign"),
                ),
                "dragonfight" to CommandHelp(
                    "Fight the Ender Dragon in a private End of your own. You must prove yourself first by " +
                        "killing enough endermen and blazes; /dragonfight tells you how far along you are.\n" +
                        "/dragonfight starts your fight (or takes you back into it) and /dragonfight confirm " +
                        "confirms the start. /dragonfight leave takes you out, /dragonfight invite <player> lets a " +
                        "friend in, and /dragonfight join <player> joins a fight you were invited to.\n" +
                        "Leaving through the exit portal after winning ends the fight for good and unlocks /rtp end.",
                    adminOnlyOptions = setOf("reset"),
                ),
                "norain" to CommandHelp(
                    "Hides rain and thunder for you only. Other players still see the weather. Run it again to " +
                        "see the weather again.",
                ),
                "region" to CommandHelp(
                    "A region protects a piece of land so other players cannot access it. You can add and remove " +
                        "friends while others cannot modify your area.\n" +
                        "Stand at one corner and run /region start, then walk to the opposite corner and run " +
                        "/region end to create it. Stand inside a region to rename it, add or remove friends, " +
                        "resize it, change its flags or delete it. Run /region on its own for the list of commands.\n" +
                        "A region can cover up to $travelerCap blocks ($donatorCap for Donators). " +
                        "Do you need a region that exceeds $travelerCap blocks? Call for an admin to expand it for you.",
                    aliases = listOf("rg"),
                    adminOnlyOptions = setOf("bounds", "locate"),
                ),
                // Admin-only commands: hidden from everyone else, so only admins ever read these.
                "rank" to CommandHelp(
                    "Sets a player's rank: newbie, traveler or donator. Works on players who are offline too.",
                ),
                "economy" to CommandHelp("Shows the total money supply and how much moved in the last 7 days."),
                "embassy" to CommandHelp(
                    "Builds and removes embassy plots. /embassy create makes a new plot and takes you to it; " +
                        "/embassy delete removes the embassy you are standing in once you type its title back.",
                ),
                "set-teleportation-crystal-energy" to CommandHelp(
                    "Sets how much energy (0 to 5) a player's Teleportation Crystals have. Sets your own when " +
                        "no player is given.",
                ),
                "vanish" to CommandHelp(
                    "Hides you from everyone who is not an admin, and puts you in spectator mode. Run it again to " +
                        "come back.",
                ),
                "ban" to CommandHelp(
                    "Bans a player, optionally for a duration and with a reason, for example /ban Steve 7d griefing.",
                ),
                "unban" to CommandHelp("Lifts a player's active ban."),
                "pardon" to CommandHelp("Lifts a player's active ban, like /unban."),
                "banlist" to CommandHelp("Lists the players who are banned, a page at a time."),
                "mute" to CommandHelp(
                    "Mutes a player, optionally for a duration and with a reason. A muted player cannot chat or " +
                        "send private messages.",
                ),
                "unmute" to CommandHelp("Lifts a player's active mute."),
                "kick" to CommandHelp("Removes a player who is online from the server, with an optional reason."),
                "warn" to CommandHelp("Records a warning against a player and tells them about it."),
                "warnings" to CommandHelp("Lists a player's active warnings with their ids."),
                "unwarn" to CommandHelp("Lifts one warning by its id (see /warnings)."),
                "history" to CommandHelp("Shows a player's moderation history: bans, mutes, kicks and warnings."),
                "note" to CommandHelp("Adds a private staff note to a player's record."),
                "whois" to CommandHelp("Shows what the server knows about a player."),
                "seen" to CommandHelp("Shows whether a player is online, or when they were last on."),
                "invsee" to CommandHelp("Opens the inventory of a player who is online, live."),
                "inspect" to CommandHelp(
                    "Turns inspect mode on or off. While it is on, clicking a block shows who placed, broke or " +
                        "opened things there recently.",
                ),
                "lookup" to CommandHelp(
                    "Searches the block log: /lookup <player|r:region> [duration] [place|break|container].",
                ),
            )
        }
    }
}
