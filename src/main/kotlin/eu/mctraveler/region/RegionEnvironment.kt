package eu.mctraveler.region

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.vehicle.VehicleEntity
import net.minecraft.world.level.Level

/**
 * What a region stops the *world* doing (spec User Story 35, deviation 7): an
 * explosion, a fire, a piston reaching in from outside, a creature rearranging
 * blocks. New in the port — the Portal saw only packets, and a creeper sends
 * none.
 *
 * This is the ambient half of [RegionProtection]. Every rule here is decided
 * without a player and answers silently: there is nobody to send "This area is
 * protected by …" to, and a region owner would not want a message every time a
 * fire two hundred blocks away failed to spread into their wall.
 *
 * Two of the rules are opt-in: `ENABLE_EXPLOSIONS` and `ENABLE_FIRE_DAMAGE` put
 * a region back in the world's way. Pistons and creatures have no flag — they
 * are simply held off.
 *
 * **Cost.** These questions are asked from block ticks and explosion maths, so
 * every entry point starts from [RegionsFeature.regionAt], which is a scan of
 * the root regions of one World (a server with no regions pays one empty-list
 * check). Nothing here caches or reads a region's members, and nothing
 * allocates but the piston's own arithmetic about where a block would land.
 */
object RegionEnvironment {

    private const val EXPLOSIONS = "EXPLOSIONS"
    private const val FIRE_DAMAGE = "FIRE_DAMAGE"
    private const val WIND_CHARGES = "WIND_CHARGES"
    private const val PUBLIC = "PUBLIC"
    private const val POTIONS = "POTIONS"

    /**
     * Whether an explosion may destroy the block at [pos] — true outside every
     * region, and inside one flying `EXPLOSIONS`.
     *
     * The blast itself is untouched: what an explosion does to players, mobs
     * and items inside a region is vanilla, and only the region's blocks are
     * shielded.
     */
    @JvmStatic
    fun allowsExplosionDamage(level: Level, pos: BlockPos): Boolean =
        optedIn(level, pos, EXPLOSIONS)

    /**
     * Whether fire may take the block at [pos] — burn it away, char it into
     * more fire, or catch there in the first place. True outside every region,
     * and inside one flying `FIRE_DAMAGE`.
     *
     * The flag reads as one thing (this region burns), so both halves of what
     * fire does to blocks answer to it: a fire outside a protected region
     * neither eats into it nor spreads across the boundary. Fire already
     * *inside* is left burning — it is the blocks that are protected, not the
     * air — and a player still cannot strike a light there, because putting an
     * item to a block is [RegionProtection]'s business.
     */
    @JvmStatic
    fun allowsFireDamage(level: Level, pos: BlockPos): Boolean =
        optedIn(level, pos, FIRE_DAMAGE)

    /**
     * Whether an explosion's blast — knockback and damage, not the block list
     * (that is [allowsExplosionDamage]) — may reach [target].
     *
     * Wind charges never lose their block-triggering (buttons, targets, and
     * the like always work regardless of region — [WIND_CHARGES] governs
     * nothing about that), but a non-member's wind charge is kept off a
     * non-hostile mob or player inside a region unless it flies
     * `WIND_CHARGES`; a hostile target is always affected. Decorations (item
     * frames, paintings, armor stands) are protected from every explosion's
     * knockback unconditionally, by [allowsDecorationMove]'s own generic rule
     * on `Entity.move`/`push` — nothing here needs to repeat that.
     *
     * A creeper's blast leaves a name-tagged hostile alone unless the region
     * is `PUBLIC` — the same "a name tag makes even a hostile someone's" idea
     * [RegionProtection.isCullableHostile] already applies to a player's own
     * attacks, reached here from the explosion path instead.
     *
     * [source] is the explosion's own source entity (the wind charge, the
     * creeper) — null for a source-less explosion (TNT, a bed), which this
     * never restricts.
     */
    @JvmStatic
    fun allowsExplosionEntityEffect(level: Level, source: Entity?, target: Entity): Boolean {
        val region = RegionsFeature.regionAt(level, target.blockPosition()) ?: return true

        // Every boat and every minecart inside a region is shielded from every
        // explosion — TNT, creeper, anything — unless the region opted into
        // explosions, exactly as its blocks are.
        if (target is VehicleEntity) return EXPLOSIONS in region.flags

        if (source == null) return true
        val living = target as? LivingEntity ?: return true

        if (isWindCharge(source)) {
            if (living is Enemy) return true
            val shooter = shooterOf(source)
            if (shooter is ServerPlayer && RegionProtection.canModifyRegion(shooter, region)) return true
            return WIND_CHARGES in region.flags
        }

        // A creeper's blast leaves villagers, animals, golems and name-tagged
        // hostiles alone unless the region is PUBLIC — only players and plain
        // (un-named) hostiles still take creeper damage inside a region.
        if (source is Creeper && living !is Player && (living !is Enemy || living.hasCustomName())) {
            return PUBLIC in region.flags
        }

        return true
    }

    /**
     * Whether a splash or lingering potion's effect, thrown or set by
     * [thrower], may land on [target] — "Allows use of potions by others".
     * Silent, like every other rule in this file: refused only for a
     * non-member's potion reaching someone else's region without `POTIONS`.
     *
     * A non-player-attributable potion (there is none in vanilla today, but
     * the pattern matches every other cause here) is left unaffected.
     */
    @JvmStatic
    fun allowsPotionEffect(level: Level, thrower: Entity?, target: LivingEntity): Boolean {
        val player = thrower as? ServerPlayer ?: return true
        val region = RegionsFeature.regionAt(level, target.blockPosition()) ?: return true
        return RegionProtection.canModifyRegion(player, region) || POTIONS in region.flags
    }

    private fun isWindCharge(source: Entity): Boolean =
        source.type == EntityTypes.WIND_CHARGE || source.type == EntityTypes.BREEZE_WIND_CHARGE

    /** The player behind an explosion's source entity: a projectile's owner, or the entity itself. */
    private fun shooterOf(source: Entity): Entity? = when (source) {
        is Projectile -> source.owner
        else -> source
    }

    /**
     * Whether the region covering [pos], if there is one, has asked for this
     * kind of harm with [flag]. Unclaimed ground always has.
     */
    private fun optedIn(level: Level, pos: BlockPos, flag: String): Boolean {
        val region = RegionsFeature.regionAt(level, pos) ?: return true
        return flag in region.flags
    }

    /**
     * Whether a placed decoration at [pos] may be shoved — by an explosion's
     * knockback, a piston, a boat. False inside any region: a hanging entity
     * breaks the instant it is moved, so "do not move it" is "do not break it".
     * A member still removes their own by hitting it, which is a different path.
     */
    @JvmStatic
    fun allowsDecorationMove(level: Level, pos: BlockPos): Boolean =
        RegionsFeature.regionAt(level, pos) == null

    /**
     * Whether a fluid at [from] may spread into [to]. A fluid flows freely
     * within one region and across unclaimed ground, but does not cross into a
     * region from outside it — so water or lava a stranger pours just past the
     * border stops at it (issue #49). Like the piston rule, there is no flag: a
     * region owner cannot ask to be flooded.
     *
     * [to] carrying no region is always fine (a region's own water may flow out
     * onto open ground); otherwise the fluid must already be inside that same
     * region.
     */
    @JvmStatic
    fun allowsFluidSpread(level: Level, from: BlockPos, to: BlockPos): Boolean {
        val target = RegionsFeature.regionAt(level, to) ?: return true
        return RegionsFeature.regionAt(level, from) === target
    }

    /**
     * Whether a piston may make the move it has worked out: true only while
     * every block it would take, land on or destroy belongs to the same region
     * as the piston itself.
     *
     * That one sentence is both halves of the rule. A piston outside cannot
     * push into a region (the landing square is somebody's), and cannot pull a
     * block out of one (the block it grabs is). A piston *inside* a region
     * still works normally, and may still push out onto unclaimed ground —
     * what is protected is the region, not the machine. A sub-region counts as
     * somebody else, because it is.
     *
     * [headPos] is where the arm itself will end up, or null when the piston is
     * retracting and the arm is coming home. There is no flag: a region owner
     * cannot ask to be pushed around.
     */
    @JvmStatic
    fun allowsPistonMove(
        level: Level,
        pistonPos: BlockPos,
        headPos: BlockPos?,
        toPush: List<BlockPos>,
        toDestroy: List<BlockPos>,
        pushDirection: Direction,
    ): Boolean {
        val own = RegionsFeature.regionAt(level, pistonPos)
        if (headPos != null && !isPistonsOwnGround(level, own, headPos)) return false
        for (pos in toPush) {
            if (!isPistonsOwnGround(level, own, pos)) return false
            if (!isPistonsOwnGround(level, own, pos.relative(pushDirection))) return false
        }
        for (pos in toDestroy) {
            if (!isPistonsOwnGround(level, own, pos)) return false
        }
        return true
    }

    /** Whether [pos] is unclaimed ground, or claimed by the piston's own region. */
    private fun isPistonsOwnGround(level: Level, own: Region?, pos: BlockPos): Boolean {
        val region = RegionsFeature.regionAt(level, pos)
        return region == null || region === own
    }

    /**
     * Whether [creature] may change the block at [pos] — false for anything
     * alive that is not a player, anywhere inside a region.
     *
     * This is the mob-griefing rule: a creeper, a ravager, a wither, a zombie
     * at a door, a villager harvesting, an enderman helping itself. There is no
     * flag; a region owner cannot invite the mobs in.
     *
     * Two things are deliberately *not* creatures here. A player is judged by
     * [RegionProtection] instead, which knows about membership and answers with
     * a message. And a change nobody is making — a block losing its support, a
     * plant losing its light, a piston's own tidying — is the world's own
     * physics, which a region must keep, or its residents could never build.
     *
     * A thrown thing is whoever threw it, so a splash of water still puts out a
     * fire when a player threw it and is still griefing when a witch did.
     */
    @JvmStatic
    fun allowsCreatureBlockChange(level: Level, pos: BlockPos, creature: Entity?): Boolean {
        val responsible = if (creature is Projectile) creature.owner else creature
        if (responsible == null || responsible is Player) return true
        return RegionsFeature.regionAt(level, pos) == null
    }
}
