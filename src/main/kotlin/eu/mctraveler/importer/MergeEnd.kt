package eu.mctraveler.importer

import com.google.gson.JsonParser
import eu.mctraveler.embassy.EmbassyDestination
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import eu.mctraveler.region.RegionStore
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.name
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.storage.LevelData

/**
 * A Region that stood in Secondary's End, named for the people whose it was.
 *
 * The members are named rather than counted because the operator's next action
 * after reading this is to go and tell them, and a list of uuids is not a list
 * of people. A member the server's own profile cache cannot name comes out as
 * their uuid, which is still enough to look them up.
 */
data class EndRegion(val title: String, val members: List<String>) {
    fun describe(): String = "the Region \"$title\"" + when {
        members.isEmpty() -> ", which has no members"
        else -> " — ${members.joinToString(", ")}"
    }
}

/**
 * Where one player who was standing in Secondary's End is put down instead.
 *
 * Stated *before* the operator accepts the loss, because afterwards there is
 * nothing left to decide: this is the whole of what the merge is about to do to
 * them (merge spec, User Story 43).
 */
data class EndLanding(
    val uuid: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    /** True when this is the player's own banked Secondary base, false when it is the spawn. */
    val ownBase: Boolean,
) {
    /** The group this landing belongs to, which is what the operator counts by. */
    val where: String get() = if (ownBase) "their own Secondary base" else "the relocated Secondary spawn"

    fun describe(): String = "$uuid → $where, x $x, y $y, z $z"
}

/**
 * What the merge destroyed along with Secondary's End, so that the operator has
 * a record of what to tell people afterwards (ticket 07).
 *
 * The section is present on every merge, including the ones with nothing
 * anchored: "the End was discarded and nothing was in it" is an answer the
 * operator wants stated rather than inferred from a missing line.
 */
data class MergeEndReport(
    val regionsDeleted: List<EndRegion>,
    /** Embassy Regions whose saved destination pointed into Secondary's End, by title. */
    val destinationsCleared: List<String>,
    val landed: List<EndLanding>,
) : MergeSection {

    val anythingLost: Boolean
        get() = regionsDeleted.isNotEmpty() || destinationsCleared.isNotEmpty() || landed.isNotEmpty()

    override fun lines(): List<String> {
        if (!anythingLost) {
            return listOf(reportLine("Secondary's End", "discarded — nothing was anchored in it"))
        }
        return listOf(
            reportLine("Secondary's End", "discarded, and the loss accepted with ${MergeEnd.OPT_IN}"),
        ) +
            regionsDeleted.map { reportLine("  Region deleted", it.describe()) } +
            destinationsCleared.map { reportLine("  destination cleared", "the Embassy Region \"$it\"") } +
            landed.map { reportLine("  player landed", it.describe()) }
    }
}

/**
 * The gate on Secondary's End (merge spec, "The End"; User Stories 41–44).
 *
 * Everything else the merge does is a move: chunks, Regions, positions, all of
 * it arrives somewhere. This is the one place it *destroys* something, and what
 * it destroys is other people's builds. So the merge refuses by default the
 * moment anything is still anchored there and only proceeds when the operator
 * says, in as many words, that they accept the loss — the same stance
 * `--skip-unidentified` takes in `migrate` (see `docs/migration.md`), where the
 * refusal is the default and the flag is how an operator says they understand.
 *
 * **The refusal has to be worth acting on, because afterwards there is nothing
 * to act on.** A count would tell the operator that something is wrong without
 * telling them what to do about it, so instead: every Region is named by title
 * *and* by its members' names, which is who to warn; every Embassy whose
 * destination points into the End is named, because its plot stops working;
 * and every player standing there is counted with their landing worked out in
 * advance, so "what will happen to them" is answered before rather than after.
 *
 * With the opt-in the loss is carried out rather than merely permitted. The End
 * Regions go, the destinations that pointed into the End are cleared instead of
 * being left aiming at nothing, and each player standing there is put down at
 * their own banked Secondary overworld position if they have one and at the
 * relocated Secondary spawn if they do not — somewhere they recognise first,
 * somewhere that exists second.
 *
 * **Every save is scrubbed of the End, not only the ones standing in it.** A
 * player who never left Primary can still hold a compass bound to a lodestone
 * in Secondary's End, or a death location there, and after the merge those name
 * a dimension the server will not have. Anything naming it is dropped: a
 * respawn point in the End becomes no respawn point, which is the only honest
 * answer once the bed is gone, and a dead compass is what a lodestone compass
 * becomes when its lodestone is destroyed. That happens whether or not anything
 * was anchored, because it is not a loss anyone has to accept — the dimension
 * is discarded either way (merge spec, "Out of Scope").
 *
 * It runs after the Regions and player sweeps because both of them are where
 * its inputs come from, and it reads what they staged rather than what they
 * read, so its deletions land on top of their rewrites. Nothing is committed
 * until every phase has finished, so a refusal here still leaves the run
 * directory exactly as it was found — including the chunk relocation, which by
 * then has already been staged and is thrown away with everything else.
 */
class MergeEnd(
    private val plan: MergePlan,
    private val staging: MergeStaging,
    private val offset: MergeOffset,
    /**
     * Players whose save leaves them standing in Secondary's End — the list the
     * player sweep hands over, having deliberately moved nothing of theirs
     * because there is no offset for a dimension that is being deleted.
     */
    private val anchored: List<UUID>,
) {

    private val regionsFile: Path = plan.targetDir.resolve(WorldMerge.REGIONS_FILE)
    private val levelDir: Path = plan.targetDir.resolve(plan.levelName)
    private val playerdataDir: Path = levelDir.resolve(PLAYERDATA_DIRECTORY)
    private val recordsDir: Path = plan.targetDir.resolve(RECORDS_DIRECTORY)

    /** Worked out once per player, because the refusal states it and then the landing applies it. */
    private val landings = mutableMapOf<UUID, EndLanding>()

    fun close(): MergeEndReport {
        val regions = RegionService(staging.latest(regionsFile))
        val everyRegion = regions.roots.flatMap(::selfAndNested)
        val inTheEnd = everyRegion.filter { it.world == END_WORLD }
        // A destination the strict reader cannot parse has already stopped the
        // merge in the Regions sweep, which read this same file first, so
        // everything reaching here is readable.
        val pointingAtIt = everyRegion.filter { EmbassyDestination.of(it)?.world == END_WORLD }
        val landed = anchored.map(::landingFor)

        if (inTheEnd.isNotEmpty() || pointingAtIt.isNotEmpty() || landed.isNotEmpty()) {
            if (!plan.acceptEndLoss) throw MigrationRefused(refusal(inTheEnd, pointingAtIt, landed))
            for (region in pointingAtIt) region.metadata.remove(EmbassyDestination.KEY)
            if (inTheEnd.isNotEmpty() || pointingAtIt.isNotEmpty()) {
                deleteFromTheEnd(regions.roots)
                Files.writeString(staging.replacing(regionsFile), RegionStore.serialize(regions.roots))
            }
        }

        scrubSaves()
        return MergeEndReport(
            regionsDeleted = inTheEnd.map { EndRegion(it.title, it.members.map(::nameOf)) },
            destinationsCleared = pointingAtIt.map(Region::title),
            landed = landed,
        )
    }

    // ---- the refusal --------------------------------------------------------

    /**
     * The refusal, in the shape of an action list: who to warn, which plots stop
     * working, and where each player will wake up if the operator goes ahead.
     */
    private fun refusal(
        inTheEnd: List<Region>,
        pointingAtIt: List<Region>,
        landed: List<EndLanding>,
    ): String = buildString {
        append(
            "Secondary's End is destroyed by this merge, and it is the one thing here that cannot be " +
                "undone. Something is still anchored in it:\n",
        )
        for (region in inTheEnd) {
            append("  ${EndRegion(region.title, region.members.map(::nameOf)).describe()}\n")
        }
        for (region in pointingAtIt) {
            append("  the Embassy Region \"${region.title}\", whose destination points into it\n")
        }
        if (landed.isNotEmpty()) {
            append("  ${landed.size} player${if (landed.size == 1) "" else "s"} standing in it, who would land at:\n")
            for ((where, group) in landed.groupBy(EndLanding::where)) {
                append("    ${group.size} at $where\n")
                for (landing in group) append("      ${landing.describe()}\n")
            }
        }
        append(
            "\nTell the people named above before you run this: afterwards their builds in Secondary's " +
                "End are gone and there is nothing left to show them. When you have, pass $OPT_IN to " +
                "say that you accept the loss — those Regions are deleted, those destinations are " +
                "cleared rather than left aiming at nothing, and each of those players is put down " +
                "where this says. Nothing has been written.",
        )
    }

    // ---- what the opt-in destroys -------------------------------------------

    /**
     * Every Region in Secondary's End dropped, at any depth.
     *
     * Dropping a Region takes its whole nest with it, because a sub-region is
     * held by its parent and nothing outside it: a nest in the End was one
     * protected place and it stops being one all at once.
     */
    private fun deleteFromTheEnd(regions: MutableList<Region>) {
        regions.removeAll { it.world == END_WORLD }
        regions.forEach { deleteFromTheEnd(it.subRegions) }
    }

    /**
     * Where [uuid] is put down instead of Secondary's End: the Secondary
     * overworld position they banked if they have one, and the relocated
     * Secondary spawn if they do not (merge spec, "The End").
     *
     * The banked position is read from the record *as the merge now has it*, so
     * it is already in merged coordinates — the player sweep moved every
     * Secondary bucket before this ran, and a bucket in Secondary's overworld
     * is by definition one it changed and therefore staged. A bucket in
     * Secondary's nether is not offered: coming out of a deleted dimension into
     * a lava-lit cave is worse than arriving at a spawn everybody knows.
     */
    private fun landingFor(uuid: UUID): EndLanding = landings.getOrPut(uuid) {
        val record = staging.latest(recordsDir.resolve("$uuid$RECORD_SUFFIX"))
        val banked = PerWorldBuckets.of(record, WorldLayout.SECONDARY.id)
        val base = banked?.takeIf { DimensionRole.fromId(it.dimension) == DimensionRole.OVERWORLD }
        if (base != null) {
            EndLanding(uuid, base.x, base.y, base.z, ownBase = true)
        } else {
            EndLanding(uuid, relocatedSpawn[0], relocatedSpawn[1], relocatedSpawn[2], ownBase = false)
        }
    }

    /**
     * Secondary's spawn, where the merge has put it.
     *
     * The Portal's two Worlds shared one spawn — there is one `level.dat`, and
     * Travelling to Secondary landed a first-time visitor at that position in
     * *Secondary's* overworld — so Secondary's spawn is the save's own spawn, and
     * the merge moves it like everything else in Secondary. It is read through the
     * game's own codec rather than key by key, so the shape it is read at is the
     * shape the server writes.
     *
     * The Worlds service that did that Travelling no longer exists (ADR 0004);
     * this is a statement about the save the merge reads, not about the running
     * server.
     */
    private val relocatedSpawn: DoubleArray by lazy {
        val file = levelDir.resolve(LEVEL_DATA_FILE)
        if (Files.notExists(file)) {
            throw MigrationRefused(
                "there is no $file, and the merge needs the world spawn out of it to put down the " +
                    "players standing in Secondary's End",
            )
        }
        val spawn = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            .getCompoundOrEmpty(LEVEL_DATA)
            .getCompound(SPAWN)
            .orElseThrow {
                MigrationRefused(
                    "$file records no world spawn under \"$LEVEL_DATA\"/\"$SPAWN\", and the merge " +
                        "needs it to put down the players standing in Secondary's End",
                )
            }
        val pos = LevelData.RespawnData.CODEC.parse(NbtOps.INSTANCE, spawn)
            .getOrThrow { MigrationRefused("$file's world spawn cannot be read: $it") }
            .globalPos()
            .pos()
        doubleArrayOf(
            offset.mergedX(pos.x + HALF_A_BLOCK, DimensionRole.OVERWORLD),
            pos.y.toDouble(),
            offset.mergedZ(pos.z + HALF_A_BLOCK, DimensionRole.OVERWORLD),
        )
    }

    // ---- the saves ----------------------------------------------------------

    /**
     * Every player save with Secondary's End taken out of it, and every player
     * standing in it put down somewhere that will exist.
     *
     * Vanilla's backup save is swept beside the live one for the same reason the
     * player sweep sweeps it: `PlayerDataStorage.load` falls back to it, and a
     * backup still naming a dimension the server does not have would fail to
     * load on exactly the day its owner needed it.
     */
    private fun scrubSaves() {
        for ((live, uuid) in saveFiles()) {
            val tag = NbtIo.readCompressed(staging.latest(live), NbtAccounter.unlimitedHeap())
            val scrubbed = withoutTheEnd(tag, uuid)
            if (scrubbed == tag) continue
            NbtIo.writeCompressed(scrubbed, staging.replacing(live))
        }
    }

    /**
     * Every player save, paired with whose it is.
     *
     * The file name is the only thing that records the owner, and a name that is
     * not exactly a uuid is not a player's save at all — the live deployment's
     * `playerdata/` holds files nothing can key to anybody (see [PortalImport]),
     * and the player sweep leaves those alone for the same reason.
     */
    private fun saveFiles(): List<Pair<Path, UUID>> {
        if (Files.notExists(playerdataDir)) return emptyList()
        return Files.list(playerdataDir).use { entries ->
            entries.toList().sortedBy(Path::toString).mapNotNull { file ->
                val suffix = SAVE_SUFFIXES.firstOrNull { file.name.endsWith(it) } ?: return@mapNotNull null
                runCatching { UUID.fromString(file.name.removeSuffix(suffix)) }.getOrNull()
                    ?.let { file to it }
            }
        }
    }

    /**
     * [tag] with every place in Secondary's End taken out of it, and — for the
     * player standing there — a position in a dimension that will still exist.
     *
     * A place in the End is *dropped* rather than moved. There is no offset for
     * a dimension being deleted, and pointing a bed or a compass at Primary's
     * End would invent somewhere its owner has never been; a player with no
     * respawn point respawns at the world spawn, and a compass with no target
     * spins, which is what both already do when the block behind them is broken.
     */
    private fun withoutTheEnd(tag: CompoundTag, uuid: UUID): CompoundTag {
        val save = tag.copy()
        dropPlacesInTheEnd(save)
        dropPearlsInTheEnd(save)
        dropLegacySpawnInTheEnd(save)
        if (save.getStringOr(DIMENSION, "") == END_DIMENSION) {
            val landing = landingFor(uuid)
            save.putString(DIMENSION, WorldLayout.PRIMARY.dimensionId(DimensionRole.OVERWORLD))
            save.put(
                POS,
                ListTag().apply {
                    add(DoubleTag.valueOf(landing.x))
                    add(DoubleTag.valueOf(landing.y))
                    add(DoubleTag.valueOf(landing.z))
                },
            )
            // Both name a place in the End, and both would drag their owner back
            // to it: vanilla remounts a logged-out vehicle at the vehicle's own
            // saved position, not the player's.
            save.remove(ROOT_VEHICLE)
            save.remove(SLEEPING_POS)
            LEGACY_SLEEPING.forEach(save::remove)
        }
        return save
    }

    /**
     * Every global position naming Secondary's End, removed from whatever holds
     * it, at any depth.
     *
     * The shape is the one the player sweep moves by — a compound with a
     * `dimension` string beside a `pos` block position — so a respawn point, a
     * last death location and a lodestone target nested arbitrarily deep inside
     * containers are all reached by one rule rather than by a list of keys.
     */
    private fun dropPlacesInTheEnd(tag: CompoundTag) {
        for (key in tag.keySet().toList()) {
            when (val value = tag.get(key)) {
                is CompoundTag -> if (isInTheEnd(value)) tag.remove(key) else dropPlacesInTheEnd(value)
                is ListTag -> dropPlacesInTheEnd(value)
                else -> Unit
            }
        }
    }

    private fun dropPlacesInTheEnd(list: ListTag) {
        for (index in list.size - 1 downTo 0) {
            val element = list.getCompound(index).orElse(null) ?: continue
            if (isInTheEnd(element)) list.removeAt(index) else dropPlacesInTheEnd(element)
        }
    }

    private fun isInTheEnd(tag: CompoundTag): Boolean =
        tag.getStringOr(GLOBAL_POS_DIMENSION, "") == END_DIMENSION &&
            tag.getIntArray(GLOBAL_POS).map { it.size == BLOCK_POS_LENGTH }.orElse(false)

    /** Ender pearls still in the air over Secondary's End, which have nowhere to come down. */
    private fun dropPearlsInTheEnd(save: CompoundTag) {
        val pearls = save.getList(ENDER_PEARLS).orElse(null) ?: return
        for (index in pearls.size - 1 downTo 0) {
            val pearl = pearls.getCompound(index).orElse(null) ?: continue
            if (pearl.getStringOr(ENDER_PEARL_DIMENSION, "") == END_DIMENSION) pearls.removeAt(index)
        }
    }

    /** A spawn point in the End still in its pre-1.21.5 spelling; see [MergedPlayerdata]. */
    private fun dropLegacySpawnInTheEnd(save: CompoundTag) {
        if (save.getStringOr(LEGACY_SPAWN_DIMENSION, "") != END_DIMENSION) return
        LEGACY_SPAWN.forEach(save::remove)
    }

    // ---- naming things ------------------------------------------------------

    private fun selfAndNested(region: Region): List<Region> =
        listOf(region) + region.subRegions.flatMap(::selfAndNested)

    /**
     * [uuid] as a person, from the server's own profile cache — the file vanilla
     * fills in as players log in, so it names everyone who has played since the
     * Portal cutover. A player it does not know comes out as their uuid, which
     * is still something the operator can look up.
     */
    private fun nameOf(uuid: UUID): String = names[uuid] ?: uuid.toString()

    private val names: Map<UUID, String> by lazy {
        val file = plan.targetDir.resolve(USER_CACHE_FILE)
        if (Files.notExists(file)) return@lazy emptyMap()
        JsonParser.parseString(Files.readString(file)).asJsonArray.mapNotNull { entry ->
            val profile = entry.asJsonObject
            val uuid = runCatching { UUID.fromString(profile.get("uuid").asString) }.getOrNull()
            uuid?.let { it to profile.get("name").asString }
        }.toMap()
    }

    companion object {
        /** How an operator says they accept the loss of everything in Secondary's End. */
        const val OPT_IN = "--accept-end-loss"

        /** Secondary's End as `regions.json` spells it, derived so it is never spelled twice. */
        val END_WORLD: String = WorldLayout.SECONDARY.legacyWorld(DimensionRole.END)

        /** Secondary's End as a player save spells it. */
        val END_DIMENSION: String = WorldLayout.SECONDARY.dimensionId(DimensionRole.END)

        private const val PLAYERDATA_DIRECTORY = "playerdata"
        private const val RECORDS_DIRECTORY = "${WorldMerge.MOD_DIRECTORY}/players"
        private const val RECORD_SUFFIX = ".json"

        /** Vanilla's live save and the backup `PlayerDataStorage.load` falls back to. */
        private val SAVE_SUFFIXES = listOf(".dat", ".dat_old")

        private const val USER_CACHE_FILE = "usercache.json"
        private const val LEVEL_DATA_FILE = "level.dat"
        private const val LEVEL_DATA = "Data"
        private const val SPAWN = "spawn"

        private const val DIMENSION = "Dimension"
        private const val POS = "Pos"
        private const val ROOT_VEHICLE = "RootVehicle"
        private const val SLEEPING_POS = "sleeping_pos"
        private val LEGACY_SLEEPING = listOf("SleepingX", "SleepingY", "SleepingZ")
        private const val ENDER_PEARLS = "ender_pearls"
        private const val ENDER_PEARL_DIMENSION = "ender_pearl_dimension"

        private const val GLOBAL_POS_DIMENSION = "dimension"
        private const val GLOBAL_POS = "pos"
        private const val BLOCK_POS_LENGTH = 3

        private const val LEGACY_SPAWN_DIMENSION = "SpawnDimension"
        private val LEGACY_SPAWN = listOf(
            LEGACY_SPAWN_DIMENSION, "SpawnX", "SpawnY", "SpawnZ", "SpawnAngle", "SpawnForced",
        )

        /** The middle of the block a spawn names, as vanilla puts a player down on it. */
        private const val HALF_A_BLOCK = 0.5
    }
}
