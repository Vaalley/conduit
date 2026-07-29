package eu.mctraveler.worlds

import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.portal.TeleportTransition

/**
 * What the respawn and portal mixins call: the rules that keep a player inside
 * the World they are in (spec stories 22-23), expressed against the live
 * [Worlds] service.
 *
 * Vanilla routes deaths and portals by hardcoded dimension keys — the
 * `minecraft:overworld`/`the_nether`/`the_end` trio — which is Primary and only
 * Primary. Two translations make that same code serve every World:
 * [asVanillaTrio] lets vanilla's comparisons read a dimension's *role* rather
 * than its identity, and [withinTrio] sends the key it picks back into the
 * traveller's own trio. Before the server starts (and outside every trio) both
 * are the identity, so vanilla is left exactly as it was.
 */
object WorldRouting {

    /**
     * The vanilla trio's dimension for [dimension]'s role — Secondary's nether
     * read as `minecraft:the_nether`, and so on. Feeds vanilla's hardcoded
     * "which end of the portal am I standing at" tests.
     */
    @JvmStatic
    fun asVanillaTrio(dimension: ResourceKey<Level>): ResourceKey<Level> =
        WorldsFeature.worlds?.roleOf(dimension)?.vanilla ?: dimension

    /** [target]'s role, resolved in the trio [origin] belongs to. */
    @JvmStatic
    fun withinTrio(origin: ResourceKey<Level>, target: ResourceKey<Level>): ResourceKey<Level> =
        WorldsFeature.worlds?.withinTrioOf(origin, target) ?: target

    /**
     * [transition] with the guarantee story 22 asks for: a player respawning
     * lands in the World they died in. A respawn point in that World is honoured
     * as vanilla found it; anything else — no bed, or a bed standing in another
     * World — becomes that World's own spawn.
     */
    @JvmStatic
    fun withinDeathWorld(player: ServerPlayer, transition: TeleportTransition): TeleportTransition {
        val worlds = WorldsFeature.worlds ?: return transition
        val world = worlds.worldOf(player) ?: return transition
        if (worlds.worldOf(transition.newLevel().dimension()) == world) return transition
        return worlds.spawnTransition(player, world, transition)
    }
}
