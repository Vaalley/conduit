package eu.mctraveler.importer

import eu.mctraveler.embassy.EmbassyDestination
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import eu.mctraveler.region.RegionStore
import eu.mctraveler.region.RegionWorlds
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What the Regions sweep did, for the operator to check against what they expected. */
data class MergeRegionsReport(
    /** Regions rewritten onto Primary, counted by the dimension each moved in. */
    val moved: Map<DimensionRole, Int>,
    /** Regions left exactly as they were — Primary's own, the Embassies', and Secondary's End. */
    val untouched: Int,
    /** Embassy destinations that named one of Secondary's worlds and now name Primary's. */
    val destinationsRewritten: Int,
    /**
     * What is still anchored in Secondary's End, named for the operator. The
     * merge destroys that dimension, so this is the list the End gate turns into
     * a refusal (merge spec, "The End"); the sweep itself only reports it.
     */
    val endAnchored: List<String>,
    /** False when there was nothing to change and `regions.json` was left untouched. */
    val rewroteFile: Boolean,
) {
    val movedCount: Int get() = moved.values.sum()

    fun lines(): List<String> = listOf(
        reportLine(
            "Regions moved",
            if (movedCount == 0) {
                "none"
            } else {
                "$movedCount — " + MergeGeometry.RELOCATED_ROLES
                    .joinToString(", ") { "${it.id} ${moved[it] ?: 0}" }
            },
        ),
        reportLine("Regions left alone", "$untouched"),
        reportLine(
            "Embassy destinations",
            if (destinationsRewritten == 0) "none moved" else "$destinationsRewritten moved to Primary",
        ),
        reportLine("regions.json", if (rewroteFile) "rewritten" else "left exactly as it was"),
    ) + endAnchored.map { reportLine("still in Secondary's End", it) }
}

/**
 * Secondary's Regions, moved onto Primary (merge spec, User Stories 21–24).
 *
 * A Region is a player-owned protected cuboid, and it records where it is the
 * way the Portal did: a legacy world string and a pair of un-normalised corners.
 * The merge does not change what a Region protects, only where that is — so
 * every Region recorded against `last`, `last_nether` (see [RegionWorlds]) comes
 * out naming Primary's `world`, `world_nether` and standing at the relocated
 * coordinates, and everything else about it is carried across untouched.
 *
 * Three properties do the work, and each is load-bearing:
 *
 * - **One offset for the whole nest.** Sub-regions are swept with the same
 *   [MergeOffset] as their parent, so a nest that was layered stays layered and
 *   a sub-region that was inside its parent still is. Nothing here has to check
 *   containment, because adding one vector to every corner cannot break it.
 * - **Y is never touched.** A merge offset is horizontal ([MergeGeometry]), and
 *   a Region's vertical bounds are what its owner chose. The corners are also
 *   left un-normalised — the same shift on `start` and `end` keeps whichever
 *   order the creator captured them in, which is what [RegionStore] writes back.
 * - **Untouched means untouched.** A Region already in Primary, and each of the
 *   twenty imported Embassies' own regions, is carried across as the very object
 *   the live store parsed. [RegionStore] reproduces the Portal's formatting
 *   exactly, so those come back byte-for-byte — the guarantee `migrate` and
 *   `importNucleus` already make, and the reason this sweep goes through the
 *   live store rather than editing the file's text.
 *
 * The Embassies come along by a different route. An Embassy's own region lives
 * in the out-of-trio `embassies` world (ADR 0003) and never moves, but its
 * anchor remembers a destination in exactly the legacy form a Region records —
 * a world string and a position — and that destination may well name Secondary.
 * So the destination is swept for every Region that carries one, wherever the
 * Region itself is, and the plot keeps sending visitors to the same build.
 *
 * **Secondary's End is left alone here.** The merge discards that dimension, and
 * [MergeGeometry] refuses to invent an offset for it, so a Region or destination
 * anchored there is carried across verbatim and named in the report instead. The
 * End gate is the ticket that decides what happens to them; until it lands, this
 * sweep's answer is "unchanged, and said out loud".
 */
class MergeRegions(
    targetDir: Path,
    staging: Path,
    private val offset: MergeOffset,
) {

    private val regionsFile: Path = targetDir.resolve(WorldMerge.REGIONS_FILE)
    private val staged: Path = staging.resolve(WorldMerge.REGIONS_FILE)

    private val moved = linkedMapOf<DimensionRole, Int>()
    private var untouched = 0
    private var destinationsRewritten = 0
    private val endAnchored = mutableListOf<String>()

    /**
     * The whole sweep: read the live file, move what belongs to Secondary, and
     * write the result back only if there was something to write.
     *
     * The overlap check happens before a single byte is staged, so the refusal it
     * can raise leaves the run directory exactly as it was found.
     */
    fun sweep(): MergeRegionsReport {
        // The live service, read the way the running server reads it — and never
        // saved, so the file it came from is only ever replaced by the staged
        // copy below. It doubles as the thing the overlap check is asked of.
        val live = RegionService(regionsFile)
        val swept = live.roots.map { sweptRegion(it, parent = null, live = live) }

        val rewrite = movedCount() > 0 || destinationsRewritten > 0
        if (rewrite) commit(RegionStore.serialize(swept))
        return MergeRegionsReport(
            moved = moved.toMap(),
            untouched = untouched,
            destinationsRewritten = destinationsRewritten,
            endAnchored = endAnchored.toList(),
            rewroteFile = rewrite,
        )
    }

    // ---- sweeping -----------------------------------------------------------

    /**
     * [region] as the merged World records it, and every Region nested inside it.
     *
     * A Region that does not move is returned as the very object it arrived as,
     * which is what makes "byte for byte" a property of the code rather than a
     * hope: there is nothing about it for the sweep to get wrong.
     */
    private fun sweptRegion(region: Region, parent: Region?, live: RegionService): Region {
        val role = SECONDARY_ROLES[region.world]
        val landed = when {
            role == null -> region.also { untouched++ }
            role !in MergeGeometry.RELOCATED_ROLES -> region.also {
                untouched++
                endAnchored += "the Region \"${it.title}\""
            }
            else -> relocate(region, role, live)
        }
        landed.parent = parent
        // Materialised before the list is cleared, which matters when `landed` is
        // `region` itself and the two lists are the same one.
        val nested = region.subRegions.map { sweptRegion(it, parent = landed, live = live) }
        landed.subRegions.clear()
        landed.subRegions.addAll(nested)
        sweepDestination(landed)
        return landed
    }

    /**
     * [region] rebuilt where the merge puts it: Primary's world string for the
     * same trio role, and both corners moved by [offset] as [role] measures it —
     * one eighth as far in the nether, so a Region around a portal still sits
     * around it.
     *
     * A new [Region] rather than an edited one because a Region's world is the
     * one thing about it that cannot change: it is what every lookup keys by, so
     * the model makes it final and the sweep builds the moved Region instead.
     */
    private fun relocate(region: Region, role: DimensionRole, live: RegionService): Region {
        val landed = Region(
            title = region.title,
            world = PRIMARY_WORLDS.getValue(role),
            startX = offset.mergedX(region.startX, role),
            startZ = offset.mergedZ(region.startZ, role),
            endX = offset.mergedX(region.endX, role),
            endZ = offset.mergedZ(region.endZ, role),
            startY = region.startY,
            endY = region.endY,
        )
        refuseIfItLandsOnPrimary(landed, live)
        landed.members.addAll(region.members)
        landed.flags.addAll(region.flags)
        // The values are the JSON Gson parsed out of the file and are re-emitted
        // from it, so a destination's `64.0` is still `64.0` afterwards.
        landed.metadata.putAll(region.metadata)
        moved[role] = (moved[role] ?: 0) + 1
        return landed
    }

    /**
     * [region]'s Embassy destination moved onto Primary, if it named one of
     * Secondary's worlds (merge spec, User Story 23).
     *
     * The destination is rewritten in place in the metadata map, so it keeps the
     * position it had among whatever other keys a Region carries — the file's key
     * order is part of what a load/save cycle has to reproduce.
     */
    private fun sweepDestination(region: Region) {
        val destination = destinationOf(region) ?: return
        val role = SECONDARY_ROLES[destination.world] ?: return
        if (role !in MergeGeometry.RELOCATED_ROLES) {
            endAnchored += "the destination of the Embassy Region \"${region.title}\""
            return
        }
        region.metadata[EmbassyDestination.KEY] = destination.copy(
            world = PRIMARY_WORLDS.getValue(role),
            x = offset.mergedX(destination.x, role),
            z = offset.mergedZ(destination.z, role),
        ).toJson()
        destinationsRewritten++
    }

    /**
     * What [region]'s anchor sends visitors to, or null when it has none.
     *
     * [EmbassyDestination.of] reads strictly, and a destination it cannot read is
     * a hand-edited file rather than anything the merge can transform — so it is
     * named and refused here rather than allowed to fail somewhere less obvious.
     */
    private fun destinationOf(region: Region): EmbassyDestination? = try {
        EmbassyDestination.of(region)
    } catch (malformed: RuntimeException) {
        throw MigrationRefused(
            "the Region \"${region.title}\" carries an \"${EmbassyDestination.KEY}\" the merge cannot " +
                "read (${malformed.message}) — fix it in $regionsFile, then run the merge again",
        )
    }

    // ---- refusals -----------------------------------------------------------

    /**
     * Regions are the one thing the merge can land on top of, so it checks rather
     * than assumes (merge spec, User Story 24). The test is
     * [RegionService.firstIntersecting] — the same full-intersection rule `/rg`
     * refuses an overlapping claim by, asked of the file as it stands.
     *
     * Asking the *unswept* tree is what makes the question the right one: while
     * Secondary's Regions still say `last`, a query against Primary's world
     * string can only ever match a Region that was already in Primary. Two
     * relocated Regions that overlap each other overlapped before the merge too,
     * and moving them together is not what this is about.
     */
    private fun refuseIfItLandsOnPrimary(landed: Region, live: RegionService) {
        val existing = live.firstIntersecting(
            landed.world,
            landed.minX,
            landed.maxX,
            landed.minZ,
            landed.maxZ,
        ) ?: return
        throw MigrationRefused(
            "Secondary's Region \"${landed.title}\" lands on x ${landed.minX}…${landed.maxX}, " +
                "z ${landed.minZ}…${landed.maxZ} in \"${landed.world}\", where Primary's Region " +
                "\"${existing.title}\" already covers x ${existing.minX}…${existing.maxX}, " +
                "z ${existing.minZ}…${existing.maxZ} — two owners cannot share a cuboid, so choose " +
                "another offset, or move one of the two before merging",
        )
    }

    // ---- writing ------------------------------------------------------------

    /**
     * The swept file into the live run directory, staged first and moved into
     * place after, as the merge stages everything else (merge spec, "Staging").
     * The staging directory is left empty rather than left behind: a later run
     * refuses over one, and by here it holds nothing worth keeping.
     *
     * Nothing gets here unless a Region or a destination actually moved. The
     * merge is not the thing that normalises a file it has no change to make to,
     * so a save whose Regions were all in Primary keeps its `regions.json` down
     * to the last byte — and a rehearsal's diff says only what the merge did.
     */
    private fun commit(text: String) {
        Files.createDirectories(staged.parent)
        Files.writeString(staged, text)
        Files.move(staged, regionsFile, StandardCopyOption.REPLACE_EXISTING)
        deleteIfEmpty(staged.parent)
    }

    private fun deleteIfEmpty(directory: Path) {
        Files.newDirectoryStream(directory).use { entries ->
            if (entries.iterator().hasNext()) return
        }
        Files.delete(directory)
    }

    private fun movedCount(): Int = moved.values.sum()

    private companion object {
        /**
         * Secondary's legacy world strings, by the trio role each one names.
         * Derived from [RegionWorlds] and [WorldLayout] rather than spelled out,
         * so the strings the sweep matches on are the strings the live server
         * writes — there is only ever one statement of that mapping.
         */
        val SECONDARY_ROLES: Map<String, DimensionRole> = DimensionRole.entries.associate {
            RegionWorlds.legacyName(WorldLayout.SECONDARY.dimension(it)) to it
        }

        /** Primary's legacy world string for each trio role — what a relocated Region comes to say. */
        val PRIMARY_WORLDS: Map<DimensionRole, String> = DimensionRole.entries.associateWith {
            RegionWorlds.legacyName(WorldLayout.PRIMARY.dimension(it))
        }
    }
}
