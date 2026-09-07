package eu.mctraveler.importer

import eu.mctraveler.crystal.CrystalEnergy
import eu.mctraveler.embassy.EmbassiesFeature
import eu.mctraveler.persistence.JsonPlayerStore
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import eu.mctraveler.region.RegionWorlds
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.name
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.dimension.DimensionType

/** What to import from the retired Nucleus server, and into which run directory. */
data class EmbassyImportPlan(
    /** The Nucleus server directory: `embassies/`, `plugins/MCTravelerNucleus/`, `world/playerdata/`. */
    val oldDir: Path,
    /** The already-migrated Fabric run directory, with its server stopped. */
    val targetDir: Path,
    /** The level directory to write into, matching the server's `level-name`. */
    val levelName: String = "world",
    /**
     * How the embassies chunk data reaches the new save. [WorldTransfer.COPY] leaves the
     * Nucleus directory intact; [WorldTransfer.MOVE] renames the chunk folders out of it.
     */
    val worldTransfer: WorldTransfer = WorldTransfer.COPY,
)

/** What an import did, for the operator to check before booting. */
data class EmbassyImportReport(
    val regions: Int,
    val playersImported: Int,
    /** Players whose save carried the tags but who already have energy here — left alone. */
    val playersSkipped: Int,
    val chunkFiles: Int,
    val worldBytes: Long,
    /** Which Bukkit container compound the energy tags were found under, or null if none were. */
    val container: String?,
    /** Chunk folders the Nucleus embassies world simply did not have. */
    val missingChunkDirectories: List<String>,
    /** Imported embassies whose stored destination names a world this server does not have. */
    val unknownDestinationWorlds: List<String>,
    /** Playerdata files whose name is not a uuid; left untouched. */
    val unnamedFiles: List<String>,
    /** Saves whose stored energy was outside 0..3 and was clamped on the way in. */
    val clampedEnergies: List<String>,
) {
    fun lines(): List<String> = listOf(
        "embassy regions imported : $regions",
        "players' energy imported : $playersImported${container?.let { " (from \"$it\")" } ?: ""}",
        "players already set here : $playersSkipped",
        "chunk files transferred  : $chunkFiles",
        "world bytes transferred  : $worldBytes",
    ) +
        missingChunkDirectories.map { "NOT IN THE OLD WORLD   : $it" } +
        unknownDestinationWorlds.map { "destination world gone : $it" } +
        clampedEnergies.map { "energy clamped to 0..3 : $it" } +
        unnamedFiles.take(5).map { "ignored non-uuid file  : $it" }
}

/**
 * The post-cutover Nucleus import (spec User Stories 38–39): the twenty
 * embassies of the retired Bukkit server — their plots, their regions and their
 * owners' crystal energy — land in a Fabric run directory the Portal migration
 * has already produced.
 *
 * It is the second and last step of the cutover, and it runs against a **stopped
 * server, before the new build's first boot**: the embassies dimension folder
 * has to be on disk the first time that dimension loads, or vanilla creates an
 * empty one and the plots are gone.
 *
 * Like the Portal migration ([PortalImport]) it is all-or-nothing: everything is
 * read, converted and checked before a byte is written, the output is built in a
 * staging directory inside the target, and only a complete import is moved into
 * place. Unlike that migration, the target is not empty — it is a live server's
 * run directory — so the commit replaces named files rather than claiming the
 * whole directory, and every refusal below exists to make sure it is the right
 * directory and that this import has not already run.
 *
 * The chunk data is copied as bytes. The plots are 1.21-era and are upgraded by
 * vanilla's own DataFixerUpper when the dimension first loads (deviation 14) —
 * there is nothing for the importer to convert.
 */
class EmbassyImport(private val plan: EmbassyImportPlan) {

    private val staging: Path = plan.targetDir.resolve(STAGING_DIRECTORY)

    private val levelDir: Path = plan.targetDir.resolve(plan.levelName)
    private val embassiesDimension: Path =
        DimensionType.getStorageFolder(EmbassiesFeature.DIMENSION, levelDir)
    private val regionsFile: Path = plan.targetDir.resolve(REGIONS_FILE)
    private val playersDir: Path = plan.targetDir.resolve("$MOD_DIRECTORY/$PLAYERS_DIRECTORY")

    private val sourceWorld: Path = plan.oldDir.resolve(EMBASSIES_WORLD)
    private val sourceRegions: Path = plan.oldDir.resolve(NUCLEUS_REGIONS_FILE)
    private val sourcePlayerdata: Path = plan.oldDir.resolve(NUCLEUS_PLAYERDATA_DIRECTORY)

    /** True once chunk data has been moved out of the Nucleus tree — see the failure path in [run]. */
    private var nucleusDataMoved = false

    private var chunkFiles = 0
    private var worldBytes = 0L
    private val unnamedFiles = mutableListOf<String>()
    private val clampedEnergies = mutableListOf<String>()

    /** One player's Nucleus energy, and the uuid their save file is named after. */
    private data class ImportedEnergy(val uuid: UUID, val energy: NucleusPlayerdata.Energy)

    fun run(): EmbassyImportReport {
        refuseUnlessMigratedTarget()
        refuseIfAlreadyImported()
        refuseUnlessNucleusSources()

        val embassies = readEmbassyRegions()
        val energies = readEnergies()
        val (toImport, skipped) = splitByExistingEnergy(energies)
        val missingChunkDirectories = CHUNK_DIRECTORIES.filterNot { Files.isDirectory(sourceWorld.resolve(it)) }

        Files.createDirectories(staging)
        try {
            // A copy leaves the Nucleus directory untouched, so it can go first; a move
            // cannot be undone, so it goes last — after everything that can still fail.
            if (plan.worldTransfer == WorldTransfer.COPY) stageWorld()
            stageRegions(embassies)
            stagePlayers(toImport)
            if (plan.worldTransfer == WorldTransfer.MOVE) stageWorld()
            val report = EmbassyImportReport(
                regions = embassies.size,
                playersImported = toImport.size,
                playersSkipped = skipped,
                chunkFiles = chunkFiles,
                worldBytes = worldBytes,
                container = energies.firstOrNull()?.energy?.container,
                missingChunkDirectories = missingChunkDirectories,
                unknownDestinationWorlds = NucleusRegions.unknownDestinationWorlds(embassies),
                unnamedFiles = unnamedFiles.toList(),
                clampedEnergies = clampedEnergies.toList(),
            )
            commit()
            return report
        } catch (failure: Throwable) {
            // Never delete staging once it holds moved data: it is then the only copy of
            // chunk data the Nucleus directory no longer has.
            if (nucleusDataMoved) {
                throw IllegalStateException(
                    "the import failed after the embassies chunk data was MOVED into $staging. " +
                        "$sourceWorld is no longer complete, and $staging now holds the only " +
                        "on-disk copy of those chunks — do NOT delete it. Move it back or " +
                        "restore from backup before retrying.",
                    failure,
                )
            }
            deleteRecursively(staging)
            throw failure
        }
    }

    // ---- refusals -----------------------------------------------------------

    /**
     * The target has to be the run directory the Portal migration produced and
     * the new server has booted from — not an empty directory, and not the
     * Nucleus server. Nothing here can be created on the operator's behalf: a
     * missing `regions.json` means either the wrong path or a migration that
     * never ran, and both are decisions, not defaults.
     */
    private fun refuseUnlessMigratedTarget() {
        if (!Files.isDirectory(plan.targetDir)) {
            throw MigrationRefused("${plan.targetDir} is not a directory")
        }
        for (artifact in listOf(plan.levelName, MOD_DIRECTORY, REGIONS_FILE)) {
            if (Files.notExists(plan.targetDir.resolve(artifact))) {
                throw MigrationRefused(
                    "${plan.targetDir} has no \"$artifact\" — import into the migrated server run " +
                        "directory (see docs/migration.md), not a fresh one",
                )
            }
        }
    }

    private fun refuseIfAlreadyImported() {
        if (Files.exists(embassiesDimension)) {
            throw MigrationRefused(
                "$embassiesDimension already exists — the embassies have been imported already",
            )
        }
        // The whole tree, not just the roots: an embassy is always a root, but
        // this guard is the only thing standing between a slip and a doubled
        // region file, and a scan costs nothing.
        val existing = RegionService(regionsFile).roots.flatMap(::selfAndDescendants)
            .filter { it.world == RegionWorlds.EMBASSIES }
        if (existing.isNotEmpty()) {
            throw MigrationRefused(
                "$regionsFile already holds ${existing.size} region(s) in world " +
                    "\"${RegionWorlds.EMBASSIES}\" (first: \"${existing.first().title}\") — " +
                    "the embassy regions have been imported already",
            )
        }
        if (Files.exists(staging)) {
            throw MigrationRefused("$staging is left over from an interrupted import; remove it and run again")
        }
    }

    private fun selfAndDescendants(region: Region): List<Region> =
        listOf(region) + region.subRegions.flatMap(::selfAndDescendants)

    private fun refuseUnlessNucleusSources() {
        if (!Files.isDirectory(plan.oldDir)) {
            throw MigrationRefused("${plan.oldDir} is not a directory")
        }
        // The chunk folders themselves are checked one level down: `entities/` and
        // `poi/` are legitimately absent from a world nothing has ever needed them
        // in, and are reported rather than refused. `region/` is the world.
        for (source in listOf(sourceWorld, sourceWorld.resolve("region"), sourceRegions, sourcePlayerdata)) {
            if (Files.notExists(source)) {
                throw MigrationRefused("$source does not exist — is ${plan.oldDir} the Nucleus server directory?")
            }
        }
    }

    // ---- reading ------------------------------------------------------------

    private fun readEmbassyRegions(): List<Region> =
        NucleusRegions.regionsIn(Files.readString(sourceRegions), RegionWorlds.EMBASSIES)

    /**
     * Every Nucleus save that carries the crystal tags, keyed by the uuid its
     * file is named after. Nucleus ran online-mode, so those are already the
     * Mojang uuids the merged server keys by — there is no identity step here,
     * unlike the Portal migration's.
     */
    private fun readEnergies(): List<ImportedEnergy> {
        val found = mutableListOf<ImportedEnergy>()
        Files.newDirectoryStream(sourcePlayerdata, "*$PLAYERDATA_SUFFIX").use { files ->
            for (file in files.sortedBy { it.name }) {
                val uuid = runCatching { UUID.fromString(file.name.removeSuffix(PLAYERDATA_SUFFIX)) }.getOrNull()
                if (uuid == null) {
                    unnamedFiles += file.name
                    continue
                }
                val energy = try {
                    NucleusPlayerdata.energyOf(read(file))
                } catch (unreadable: Exception) {
                    throw IllegalStateException("could not read $file: ${unreadable.message}", unreadable)
                } ?: continue
                found += ImportedEnergy(uuid, clamp(uuid, energy))
            }
        }
        return found
    }

    /** Nucleus clamped on write, so an out-of-range value is damaged data — reported, not trusted. */
    private fun clamp(uuid: UUID, energy: NucleusPlayerdata.Energy): NucleusPlayerdata.Energy {
        val points = energy.energy ?: return energy
        val clamped = points.coerceIn(0, CrystalEnergy.MAX_ENERGY)
        if (clamped == points) return energy
        clampedEnergies += "$uuid had $points"
        return energy.copy(energy = clamped)
    }

    /**
     * The saves to import, and how many were left alone. A player who already
     * has energy on this server has played since the cutover, and what they have
     * now is newer than anything Nucleus remembers — the import never overwrites
     * it. The threshold is checked alongside the energy so a half-written record
     * cannot be half-overwritten either.
     */
    private fun splitByExistingEnergy(energies: List<ImportedEnergy>): Pair<List<ImportedEnergy>, Int> {
        val store = JsonPlayerStore(playersDir)
        val toImport = energies.filter {
            store.crystalEnergy(it.uuid) == null && store.crystalNextRegenAt(it.uuid) == null
        }
        return toImport to (energies.size - toImport.size)
    }

    // ---- staging ------------------------------------------------------------

    /** The plots, as bytes. Vanilla's data fixers do the version jump when the dimension first loads. */
    private fun stageWorld() {
        val stagedDimension = DimensionType.getStorageFolder(
            EmbassiesFeature.DIMENSION,
            staging.resolve(plan.levelName),
        )
        for (folder in CHUNK_DIRECTORIES) {
            val from = sourceWorld.resolve(folder)
            if (Files.isDirectory(from)) transferDirectory(from, stagedDimension.resolve(folder))
        }
    }

    /**
     * The embassies appended to the target's own `regions.json`, through the
     * live [RegionService] — so what lands is exactly what the running server
     * would have written, and the regions already in the file are re-serialised
     * by the same codec that wrote them and come out byte-identical.
     */
    private fun stageRegions(embassies: List<Region>) {
        val staged = staging.resolve(REGIONS_FILE)
        Files.createDirectories(staged.parent)
        Files.copy(regionsFile, staged)
        val regions = RegionService(staged)
        embassies.forEach { regions.add(it, parent = null) }
    }

    /**
     * Each imported player's record, rewritten through the live store so every
     * field it does not own passes through byte-for-byte. The existing record is
     * staged first and edited there, which is what makes the whole import
     * abandonable up to the moment it commits.
     */
    private fun stagePlayers(energies: List<ImportedEnergy>) {
        if (energies.isEmpty()) return
        val stagedPlayers = staging.resolve("$MOD_DIRECTORY/$PLAYERS_DIRECTORY")
        Files.createDirectories(stagedPlayers)
        val store = JsonPlayerStore(stagedPlayers)
        for ((uuid, energy) in energies) {
            val existing = playersDir.resolve("$uuid.json")
            if (Files.exists(existing)) Files.copy(existing, stagedPlayers.resolve("$uuid.json"))
            energy.energy?.let { store.setCrystalEnergy(uuid, it) }
            energy.nextRegenAt?.let { store.setCrystalNextRegenAt(uuid, it) }
        }
    }

    // ---- committing ---------------------------------------------------------

    /**
     * The finished import into the live run directory: the dimension folder as
     * one rename (nothing is there to merge with — [refuseIfAlreadyImported]
     * saw to that), then the two kinds of file that replace an existing one.
     */
    private fun commit() {
        val stagedDimension = DimensionType.getStorageFolder(
            EmbassiesFeature.DIMENSION,
            staging.resolve(plan.levelName),
        )
        if (Files.exists(stagedDimension)) {
            Files.createDirectories(embassiesDimension.parent)
            Files.move(stagedDimension, embassiesDimension)
        }
        Files.move(staging.resolve(REGIONS_FILE), regionsFile, StandardCopyOption.REPLACE_EXISTING)
        val stagedPlayers = staging.resolve("$MOD_DIRECTORY/$PLAYERS_DIRECTORY")
        if (Files.isDirectory(stagedPlayers)) {
            Files.createDirectories(playersDir)
            Files.newDirectoryStream(stagedPlayers, "*.json").use { records ->
                for (record in records) {
                    Files.move(record, playersDir.resolve(record.name), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        deleteRecursively(staging)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun read(playerdata: Path): CompoundTag =
        NbtIo.readCompressed(playerdata, NbtAccounter.unlimitedHeap())

    /**
     * Copies or moves a tree according to [EmbassyImportPlan.worldTransfer],
     * counting what it carried. A move renames whole top-level entries where the
     * filesystem allows it and falls back to a copy when the rename crosses
     * devices — [PortalImport.transferDirectory]'s shape, over one world.
     */
    private fun transferDirectory(from: Path, to: Path) {
        if (plan.worldTransfer == WorldTransfer.COPY) return copyDirectory(from, to)
        Files.createDirectories(to)
        Files.newDirectoryStream(from).use { entries ->
            for (entry in entries) {
                measure(entry)
                nucleusDataMoved = true
                try {
                    Files.move(entry, to.resolve(entry.name))
                } catch (crossDevice: java.nio.file.FileSystemException) {
                    copyDirectory(entry, to.resolve(entry.name), count = false)
                    deleteRecursively(entry)
                }
            }
        }
    }

    private fun copyDirectory(from: Path, to: Path, count: Boolean = true) {
        Files.walk(from).use { paths ->
            for (path in paths) {
                val destination = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
                    if (count) {
                        chunkFiles++
                        worldBytes += Files.size(path)
                    }
                }
            }
        }
    }

    /** Counts a tree (or file) about to be moved, since a move leaves nothing to measure afterwards. */
    private fun measure(entry: Path) {
        if (!Files.isDirectory(entry)) {
            chunkFiles++
            worldBytes += Files.size(entry)
            return
        }
        Files.walk(entry).use { paths ->
            paths.filter(Files::isRegularFile).forEach {
                chunkFiles++
                worldBytes += Files.size(it)
            }
        }
    }

    private fun deleteRecursively(directory: Path) {
        if (Files.notExists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val STAGING_DIRECTORY = ".mctraveler-embassy-import"
        const val MOD_DIRECTORY = "mctraveler"
        const val PLAYERS_DIRECTORY = "players"
        const val REGIONS_FILE = "regions.json"
        const val PLAYERDATA_SUFFIX = ".dat"

        /** The Bukkit world folder the embassies lived in, named after the world itself. */
        const val EMBASSIES_WORLD = "embassies"
        const val NUCLEUS_REGIONS_FILE = "plugins/MCTravelerNucleus/regions.json"

        /** Nucleus's main world, whose `playerdata/` holds every player's persistent data container. */
        const val NUCLEUS_PLAYERDATA_DIRECTORY = "world/playerdata"

        val CHUNK_DIRECTORIES = listOf("region", "entities", "poi")
    }
}
