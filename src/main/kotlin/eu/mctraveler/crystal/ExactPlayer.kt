package eu.mctraveler.crystal

import eu.mctraveler.text.Paint
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * The online player called exactly [name], or null — Nucleus's
 * `Bukkit.getPlayerExact`.
 *
 * Vanilla has no exact-case lookup: both `PlayerList.getPlayer(String)` and
 * `getPlayerByName` compare with `equalsIgnoreCase`, and the rest of this mod
 * uses them (`/msg`, the region commands) precisely because being forgiving
 * about case is friendlier. The crystal's two name arguments do not get that
 * licence: `/set-teleportation-crystal-energy` and
 * `/teleportation-crystal-accept` both took an exact name in Nucleus, and the
 * second is not typed by hand at all — it arrives from the click event of a
 * message this server wrote, carrying a name this server spelled.
 */
internal fun exactPlayer(server: MinecraftServer, name: String): ServerPlayer? =
    server.playerList.players.firstOrNull { it.gameProfile.name == name }

/**
 * What both of the crystal's name arguments answer when [exactPlayer] finds
 * nobody — Nucleus's `sendNotOnline`.
 *
 * One copy, next to the lookup it is the failure of: it is a player-facing
 * string, and two of them would drift.
 */
internal fun notOnline(name: String): MutableComponent =
    Paint.error(Paint.red(name), " is not online")
