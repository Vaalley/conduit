package eu.mctraveler.lodeway

import app.lodeway.api.map.Layer
import app.lodeway.api.map.Lodeway
import app.lodeway.api.map.LodewayMap
import eu.mctraveler.MCTraveler
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.region.RegionsFeature
import java.util.UUID
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer

/**
 * Every region, on the Lodeway map: one layer, one area per region and per
 * sub-region, redrawn whenever the region tree is written.
 *
 * **Never loaded on a server without Lodeway.** [LodewayFeature] is the guard;
 * this class is the only one in the runtime that names an
 * `app.lodeway.api.map` type, so it is also the only one that could fail to
 * load without one.
 *
 * The trigger is [eu.mctraveler.region.RegionService.onChange], which fires
 * after every whole-file save — and since every create, delete, rename, resize,
 * flag toggle and membership change saves, that one hook is the complete list
 * of ways the map can go stale.
 *
 * Publishing is a diff rather than a rebuild: each region is upserted by its
 * [path] id and the ids that are no longer in the tree are removed. A
 * clear-then-repopulate would be simpler, but Lodeway reads the model on its
 * own thread, and it would sometimes read it halfway through — an empty layer
 * on somebody's map for one poll.
 */
object LodewayRegions {

    /** Prefixed with the mod id: layer ids are shared with every other plugin. */
    private const val LAYER_ID = "mctraveler.regions"

    /** A restrained claim outline: thin, and a fill you can still read the map through. */
    private const val REGION_STROKE = "#2e7d3a"
    private const val REGION_FILL = "#2e7d3a1f"

    /** Embassies are regions too, and worth telling apart at a glance (ADR 0003). */
    private const val EMBASSY_STROKE = "#c98a2e"
    private const val EMBASSY_FILL = "#c98a2e1f"

    private const val STROKE_WIDTH = 1

    /** How many members a popup names before it starts counting them instead. */
    private const val MAX_MEMBERS_SHOWN = 10

    /** The live map, once Lodeway has one; null while no Lodeway runtime is attached. */
    private var map: LodewayMap? = null

    /** The running server, for member-name lookups; null between stop and start. */
    private var server: MinecraftServer? = null

    /** The marker ids the layer currently holds, so the next publish can drop the rest. */
    private var drawn: Set<String> = emptySet()

    fun register() {
        // Fires now if Lodeway is already up, and again after each of its own hot
        // swaps — the layer we registered has to be re-registered on the map that
        // replaced it, which is exactly what the callback running again means.
        Lodeway.ready { live ->
            map = live
            drawn = emptySet()
            live.layer(LAYER_ID).clear()
            publish()
        }
        ServerLifecycleEvents.SERVER_STARTED.register { started ->
            server = started
            // A fresh RegionService is built at every SERVER_STARTING, so the
            // subscription is renewed here rather than held across starts.
            RegionsFeature.requireService().onChange(::publish)
            publish()
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            server = null
            // Our reason for calling a deprecated hook: Lodeway holds the ready
            // callback in a process-wide static, and the shim that would forget it
            // for us runs on Bukkit-family loaders, not on Fabric. Without this the
            // callback outlives a runtime hot reload and pins its classloader —
            // the leak every event registration in this module is shaped to avoid.
            @Suppress("DEPRECATION")
            Lodeway.forget(LodewayRegions::class.java.classLoader)
            map = null
            drawn = emptySet()
        }
    }

    /**
     * Redraws the layer from the live region tree.
     *
     * Called from command handlers by way of the save hook, so it swallows what
     * it throws: a map that failed to update is not a reason for `/rg rename` to
     * report a failure to the player who ran it.
     */
    private fun publish() {
        val live = map ?: return
        val running = server ?: return
        val service = RegionsFeature.service ?: return
        try {
            drawn = publishTo(
                live,
                service.roots,
                worldName = { world -> worldName(world) },
                memberName = { uuid -> RegionsFeature.usernameFor(running, uuid) },
                previous = drawn,
            )
        } catch (failed: RuntimeException) {
            MCTraveler.LOGGER.warn("could not publish regions to the Lodeway map", failed)
        }
    }

    /**
     * The publish itself, over data rather than over a server: [roots] as the
     * region tree, [worldName] as the dimension a legacy world string names and
     * [memberName] as the username behind a member uuid. Returns the ids now on
     * the map, which is what the next call passes back as [previous].
     */
    internal fun publishTo(
        map: LodewayMap,
        roots: List<Region>,
        worldName: (String) -> String,
        memberName: (UUID) -> String?,
        previous: Set<String>,
    ): Set<String> {
        val layer = map.layer(LAYER_ID).label("Regions").priority(20)
        val live = LinkedHashSet<String>()
        draw(layer, roots, prefix = "", worldName, memberName, live)
        for (stale in previous) {
            if (stale !in live) layer.remove(stale)
        }
        return live
    }

    private fun draw(
        layer: Layer,
        regions: List<Region>,
        prefix: String,
        worldName: (String) -> String,
        memberName: (UUID) -> String?,
        live: MutableSet<String>,
    ) {
        regions.forEachIndexed { index, region ->
            val id = "$prefix$index"
            live.add(id)
            val embassy = Region.EMBASSY_FLAG in region.flags
            layer.area(id)
                .world(worldName(region.world))
                .label(region.title)
                .detail(detail(region, memberName))
                // Block coordinates are inclusive on both ends, so the far edge of
                // the far block is one past it: a 1x1 region is a square, not a dot.
                .points(
                    doubleArrayOf(
                        region.minX.toDouble(), region.maxX + 1.0,
                        region.maxX + 1.0, region.minX.toDouble(),
                    ),
                    doubleArrayOf(
                        region.minZ.toDouble(), region.minZ.toDouble(),
                        region.maxZ + 1.0, region.maxZ + 1.0,
                    ),
                )
                .stroke(if (embassy) EMBASSY_STROKE else REGION_STROKE, STROKE_WIDTH)
                .fill(if (embassy) EMBASSY_FILL else REGION_FILL)
                .also { area ->
                    // Full-height regions are the default and say nothing worth
                    // drawing; a region with real y bounds is a floor in a tower.
                    if (hasCustomHeight(region)) area.height(region.minY.toDouble(), region.maxY + 1.0)
                }
            draw(layer, region.subRegions, "$id.", worldName, memberName, live)
        }
    }

    /**
     * The popup: who lives here, and what kind of region it is. Plain text.
     *
     * Deliberately short. The label above it already names the region and the
     * shape below it already says where it is and how big — a card that
     * repeated the coordinates would be spending its whole height on what the
     * map is drawing anyway. What the map cannot draw is the member list, so
     * that is what the card is for.
     */
    private fun detail(region: Region, memberName: (UUID) -> String?): String {
        val lines = mutableListOf<String>()
        if (Region.EMBASSY_FLAG in region.flags) lines += "Embassy"
        // The embassy flag is what the line above already said.
        val flags = region.flags.filterNot { it == Region.EMBASSY_FLAG }
        if (flags.isNotEmpty()) lines += "Flags: ${flags.joinToString(", ")}"
        // An unresolvable member is left out rather than shown as a uuid, which is
        // how `/rg locate` and the sidebar treat one.
        val members = region.members.mapNotNull(memberName)
        if (members.isNotEmpty()) {
            lines += "Members:"
            lines += members.take(MAX_MEMBERS_SHOWN)
            // A region may hold 99 members, and a card that listed all of them
            // would be taller than the map it sits on.
            if (members.size > MAX_MEMBERS_SHOWN) lines += "and ${members.size - MAX_MEMBERS_SHOWN} more"
        }
        return lines.joinToString("\n")
    }

    private fun hasCustomHeight(region: Region): Boolean =
        region.minY != Region.DEFAULT_END_Y || region.maxY != Region.DEFAULT_START_Y

    /**
     * The world Lodeway should draw a region in: the dimension its legacy world
     * string names, by its full id. Lodeway matches world names leniently, but a
     * dimension id is the one spelling that cannot be confused with somebody
     * else's `world`.
     */
    private fun worldName(world: String): String =
        RegionWorlds.dimensionFor(world)?.identifier()?.toString() ?: world
}
