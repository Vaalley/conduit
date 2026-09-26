package eu.mctraveler.region

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * A straw bed (26.3) breaks itself on use — a genuine vanilla mechanic
 * (`BedRule.destroyOnUse`/`destroyOnLeave`, read from the dimension's
 * environment attributes), not a new one this mod builds. Left alone, a
 * stranger sleeping in someone else's straw bed inside a region would
 * destroy it exactly as if they owned it — this closes that gap the same
 * way [RegionChorusFlowerMixin] closes chorus flowers': the underlying
 * mechanic stays, only who may trigger it inside a region changes.
 *
 * Neither `destroyOnUse` (fires on the click itself, before any sleep) nor
 * `destroyOnLeave` (fires when the sleeper gets up) receives the player who
 * triggered it — `destroyOnUse` gets one as a parameter, but `destroyOnLeave`
 * does not, so this records who last successfully started sleeping at a
 * straw bed's position, for `destroyOnLeave` to look back up when it fires.
 */
object RegionStrawBeds {

    /** The last player to start sleeping at a straw bed, keyed by its packed position. */
    private val sleepers = ConcurrentHashMap<Long, UUID>()

    /** Called once a straw bed's `startSleepInBed` has genuinely begun a sleep. */
    @JvmStatic
    fun recordSleep(pos: BlockPos, player: ServerPlayer) {
        sleepers[pos.asLong()] = player.uuid
    }

    /**
     * Whether the straw bed at [pos] may destroy itself as [player] leaves it
     * — true when [player] could otherwise change blocks there (a resident,
     * an admin, or a `PUBLIC` region), matching every other block-change rule
     * in this file. Consumes the recorded sleeper either way, so a stale
     * entry never lingers past the one leave it was recorded for.
     */
    @JvmStatic
    fun allowsDestroyOnLeave(level: Level, pos: BlockPos): Boolean {
        val sleeper = sleepers.remove(pos.asLong()) ?: return true
        val server = (level as? ServerLevel)?.server ?: return true
        val player = server.playerList.getPlayer(sleeper) ?: return true
        return RegionProtection.allowsBlockChange(player, level, pos)
    }
}
