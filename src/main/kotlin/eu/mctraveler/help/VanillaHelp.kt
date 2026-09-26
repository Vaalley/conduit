package eu.mctraveler.help

/**
 * What `/help` says about Minecraft's own commands, worded from the descriptions on
 * minecraft.wiki (https://minecraft.wiki/w/Commands), and which of them it leaves out.
 *
 * Commands the server replaces itself (`msg`, `ban`, `kick`, ...) are described in
 * [HelpCatalog] instead. Aliases (`xp`, `tp`, ...) need nothing here: `/help` finds
 * them from the command tree.
 */
object VanillaHelp {

    /**
     * Commands the help menu never lists, for anyone — not an access change: an admin
     * can still run them and they still tab-complete. `advancement` is the vanilla
     * spelling of `advancements`; both are hidden.
     */
    val HIDDEN: Set<String> = setOf(
        "advancement", "advancements", "attribute", "bossbar", "clear", "damage", "datapack",
        "defaultgamemode", "enchant", "execute", "jfr", "loot", "particle", "place", "playsound",
        "posteffect", "random", "ride", "rotate", "scoreboard", "seed", "setblock", "spreadplayers",
        "stopsound", "stopwatch", "summon", "swing", "tellraw", "tick", "title", "transfer",
        "trigger", "worldborder",
    )

    val ENTRIES: Map<String, CommandHelp> = mapOf(
        "ban-ip" to CommandHelp(
            "Bans an IP address, or the address a player who is online is connected from, so nobody can " +
                "join from it. Undo it with /pardon-ip.",
        ),
        "clone" to CommandHelp(
            "Copies a box of blocks to another place. It can also move the blocks, or copy only some of them " +
                "(replace, masked or filtered).",
        ),
        "compute" to CommandHelp(
            "Evaluates a number provider, the kind of value formula loot tables and other data use, and shows " +
                "the result.",
        ),
        "data" to CommandHelp(
            "Reads, merges, changes or removes the stored data (NBT) of a block, an entity or command storage.",
        ),
        "debug" to CommandHelp("Starts or stops a debugging session that records what the server is doing."),
        "deop" to CommandHelp("Takes operator (admin) status away from a player."),
        "dialog" to CommandHelp("Shows a dialog window to players, or clears the one they have open."),
        "difficulty" to CommandHelp(
            "Shows the difficulty of the world, or changes it to peaceful, easy, normal or hard.",
        ),
        "effect" to CommandHelp(
            "Gives players or other entities a status effect for a set time, or clears their effects.",
        ),
        "experience" to CommandHelp(
            "Adds, removes or sets a player's experience, in points or in levels, or shows how much they have.",
        ),
        "fetchprofile" to CommandHelp("Looks up a player's profile (name, UUID and skin) by name or UUID."),
        "fill" to CommandHelp(
            "Fills a box of blocks with one kind of block. It can replace only some blocks, keep what is " +
                "there, or make the box hollow or only an outline.",
        ),
        "fillbiome" to CommandHelp("Changes the biome of every position inside a box of blocks."),
        "forceload" to CommandHelp(
            "Keeps chunks loaded all the time, or stops doing so, and lists the chunks that are kept loaded.",
        ),
        "function" to CommandHelp("Runs a data pack function, or every function in a function tag."),
        "gamemode" to CommandHelp(
            "Changes a player's game mode to survival, creative, adventure or spectator.",
        ),
        "gamerule" to CommandHelp(
            "Shows or changes a game rule, one of the world settings such as whether players keep their " +
                "inventory when they die.",
        ),
        "give" to CommandHelp("Gives an item to players, in the amount you choose."),
        "item" to CommandHelp(
            "Replaces or changes the items in the slots of a block's or an entity's inventory.",
        ),
        "kill" to CommandHelp("Kills the entities you name, or yourself if you name none."),
        "list" to CommandHelp(
            "Lists the players who are online. /list uuids shows their UUIDs as well.",
        ),
        "locate" to CommandHelp(
            "Finds the nearest structure, biome or point of interest and tells you its coordinates.",
        ),
        "op" to CommandHelp("Makes a player an operator (admin)."),
        "pardon-ip" to CommandHelp("Removes an IP address from the ban list."),
        "perf" to CommandHelp(
            "Records performance information about the server for 10 seconds and saves it to a report.",
        ),
        "recipe" to CommandHelp(
            "Gives players recipes, unlocking them in their recipe book, or takes recipes away.",
        ),
        "reload" to CommandHelp(
            "Reloads data packs from disk (loot tables, advancements, recipes, functions and tags) without " +
                "restarting the server.",
        ),
        "return" to CommandHelp("Stops a function early and sets the value it returns. Only useful inside functions."),
        "save-all" to CommandHelp(
            "Saves the world to disk right now. Add flush to force everything to be written immediately.",
        ),
        "save-off" to CommandHelp("Turns off automatic saving of the world. Turn it back on with /save-on."),
        "save-on" to CommandHelp("Turns automatic saving of the world back on."),
        "say" to CommandHelp("Sends a message to everyone on the server."),
        "schedule" to CommandHelp("Runs a function after a delay, or cancels a function that was scheduled."),
        "setidletimeout" to CommandHelp(
            "Sets how many minutes a player can sit idle before being kicked. 0 turns the kick off.",
        ),
        "setworldspawn" to CommandHelp("Sets the spawn point of the world, and the way new players face."),
        "spawnpoint" to CommandHelp("Sets where a player respawns."),
        "spectate" to CommandHelp(
            "Makes a player in spectator mode watch through an entity's eyes, or stops them.",
        ),
        "stop" to CommandHelp("Saves the world and stops the server."),
        "tag" to CommandHelp("Adds, removes or lists the custom tags on an entity."),
        "team" to CommandHelp(
            "Creates and manages teams: their colour, prefix and suffix, friendly fire, name tag visibility " +
                "and members.",
        ),
        "teleport" to CommandHelp(
            "Teleports entities to a position or to another entity, and can turn them to face something.",
        ),
        "test" to CommandHelp("Runs and manages GameTests, Minecraft's built-in automated test structures."),
        "time" to CommandHelp("Shows or changes the time of day and the game time."),
        "version" to CommandHelp("Shows the Minecraft version the server runs."),
        "waypoint" to CommandHelp(
            "Lists waypoints and changes how one shows on the locator bar, its colour and its icon style.",
        ),
        "weather" to CommandHelp("Changes the weather to clear, rain or thunder, optionally for a set time."),
        "whitelist" to CommandHelp(
            "Manages the whitelist: turn it on or off, add or remove players, list them or reload the list.",
        ),
    )
}
