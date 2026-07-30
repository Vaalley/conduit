package eu.mctraveler.importer

import com.google.gson.JsonParser
import eu.mctraveler.persistence.JsonPlayerStore
import eu.mctraveler.persistence.NameCache
import eu.mctraveler.persistence.PlayerStore
import eu.mctraveler.persistence.PortalJson
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionStore
import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import java.util.UUID
import kotlin.io.path.name
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.dimension.DimensionType

/** What to migrate, and where to. */
/**
 * How the worlds — the only large thing a migration handles — reach the new save.
 *
 * [COPY] is the safe default: the Portal's levels are left exactly as they were, so a
 * failed or unwanted migration costs nothing, but the host needs free space for a second
 * copy of every world. [MOVE] renames the chunk data into the new save instead: instant
 * and free of extra space, at the price of the all-or-nothing guarantee — once the worlds
 * have moved, the Portal's levels are no longer complete and only a backup can undo it.
 */
enum class WorldTransfer { COPY, MOVE }

data class ImportPlan(
    /** The Portal's own working directory: `players/`, `uuid-cache.json`, `regions.json`. */
    val portalDir: Path,
    /** The Primary backend's server directory (`ops.json` and its level beside it). */
    val primaryServerDir: Path,
    /** The Secondary backend's server directory. */
    val secondaryServerDir: Path,
    /** The Fabric server's run directory, which must not hold a migrated save already. */
    val targetDir: Path,
    /** The level directory to write, matching the new server's `level-name`. */
    val levelName: String = "world",
    /** Operator-supplied usernames → Mojang UUIDs, for players the Portal's cache never saw. */
    val identitiesFile: Path? = null,
    /** Leave saves whose player cannot be identified behind (reported) instead of refusing. */
    val skipUnidentified: Boolean = false,
    /**
     * How the bulk chunk data reaches the new save. [WorldTransfer.COPY] keeps the
     * Portal's worlds intact and needs room for a second copy of them;
     * [WorldTransfer.MOVE] renames them into place, which is instant and needs no
     * extra space but leaves the Portal's levels incomplete. See [WorldTransfer].
     */
    val worldTransfer: WorldTransfer = WorldTransfer.COPY,
)

/**
 * A migration the importer declined to perform — an already-migrated target,
 * or data it will not guess about. Nothing has been written when this is
 * thrown, and the operator's answer is a decision, not a debugging session.
 */
class MigrationRefused(message: String) : IllegalStateException(message)

/** What a migration did, for the operator to check before cutting over. */
data class ImportReport(
    val playersMigrated: Int,
    val bucketsSeeded: Int,
    val playerRecords: Int,
    val namesCached: Int,
    val operators: Int,
    val regions: Int,
    /** Save files parked in the [SaveQuarantine] for their owner to claim at login. */
    val quarantinedSaves: Int,
    /** Who the quarantine holds, named where a backend's profile cache still knows them. */
    val unidentifiedSaves: List<String>,
    val unidentifiedOperators: List<String>,
) {
    /**
     * Quarantined saves are counted, not listed: on the real deployment there
     * are thousands of them and nothing is asked of the operator — their owners
     * claim them by logging in. What is genuinely unmigratable — an operator no
     * identity answers for — is still named line by line.
     */
    fun lines(): List<String> = listOf(
        "players migrated       : $playersMigrated",
        "per-World buckets      : $bucketsSeeded",
        "quarantined saves      : $quarantinedSaves",
        "Portal player records  : $playerRecords",
        "name cache entries     : $namesCached",
        "operators              : $operators",
        "regions                : $regions",
    ) + unidentifiedOperators.map { "LEFT BEHIND operator   : $it" }
}

/**
 * The one-time cutover (spec User Stories 43–44): a live Portal deployment —
 * two backend server directories plus the Portal's own data files — becomes a
 * single Fabric server run directory, ready to boot.
 *
 * The migration is all-or-nothing. Everything is read, resolved and checked
 * before a single byte is written; the output is then built in a staging
 * directory inside the target and moved into place at the end, so a failure
 * anywhere leaves the target exactly as it was. A target that already holds a
 * migrated save is refused, which is what makes a rehearsal safe to repeat.
 *
 * What the version jump needs is deliberately *not* done here: the Primary
 * level is handed over in the layout its own server wrote, and the dedicated
 * server's own file fixer and data fixers upgrade it on first boot. Only
 * Secondary's chunk data is placed by hand, into the dimension folders the
 * merged server keeps its extra dimensions in — no vanilla upgrade knows about
 * a second overworld.
 */
class PortalImport(private val plan: ImportPlan) {

    private val staging: Path = plan.targetDir.resolve(STAGING_DIRECTORY)
    private val stagedLevel: Path = staging.resolve(plan.levelName)

    /** True once bulk data has been moved out of the Portal's tree — see the failure path in [run]. */
    private var portalDataMoved = false

    private val backends: Map<WorldTrio, Path> = mapOf(
        WorldLayout.PRIMARY to backendLevel(plan.primaryServerDir, "world"),
        WorldLayout.SECONDARY to backendLevel(plan.secondaryServerDir, "last"),
    )

    /** One backend's save for one player, under the uuid its own file is named after. */
    private data class BackendSave(val world: WorldTrio, val levelDir: Path, val uuid: UUID, val file: Path)

    /** A player's two saves, sorted into the live one and the one that becomes a bucket. */
    private data class PlayerSaves(
        val identity: PlayerIdentity,
        val live: BackendSave,
        val other: BackendSave?,
    )

    fun run(): ImportReport {
        refuseIfAlreadyMigrated()

        val identities = resolveIdentities()
        val saves = collectSaves(identities)
        val orphans = orphanedSaves(identities)
        // Described once: naming them re-reads both backends' profile caches, and on the
        // real deployment the list is thousands long.
        val orphanNames = describe(orphans)
        val ops = OpsImport.rekey(backendOps(), identities)
        val regions = readRegions()
        refuseIfUnidentified(orphanNames, ops.unresolved)

        Files.createDirectories(staging)
        try {
            // Copying leaves the sources untouched, so it can go first. Moving cannot be
            // undone, so it goes last — everything that can still fail happens before the
            // Portal's own files are disturbed.
            var quarantined = 0
            if (plan.worldTransfer == WorldTransfer.COPY) quarantined = stageTransfers(orphans)
            val store = JsonPlayerStore(staging.resolve("$MOD_DIRECTORY/players"))
            val records = stagePlayerRecords()
            val buckets = stageSaves(saves, store)
            val names = stageNameCache(identities)
            regions?.let { Files.writeString(staging.resolve(REGIONS_FILE), it.text) }
            Files.writeString(staging.resolve(OPS_FILE), OpsImport.serialize(ops.entries))
            if (plan.worldTransfer == WorldTransfer.MOVE) quarantined = stageTransfers(orphans)
            val report = ImportReport(
                playersMigrated = saves.size,
                bucketsSeeded = buckets,
                playerRecords = records,
                namesCached = names,
                operators = ops.entries.size,
                regions = regions?.count ?: 0,
                quarantinedSaves = quarantined,
                unidentifiedSaves = orphanNames,
                unidentifiedOperators = ops.unresolved,
            )
            writeMarker(report)
            commit()
            return report
        } catch (failure: Throwable) {
            // Never delete staging once it holds moved data: it is then the only copy of
            // chunk data and quarantined saves the Portal no longer has.
            if (portalDataMoved) {
                throw IllegalStateException(
                    "the migration failed after the worlds were MOVED into $staging. " +
                        "The Portal's levels are no longer complete, and $staging now holds " +
                        "the only on-disk copy of that chunk data (and of any quarantined " +
                        "player saves) — do NOT delete it. " +
                        "Move it back or restore from backup before retrying.",
                    failure,
                )
            }
            deleteRecursively(staging)
            throw failure
        }
    }

    // ---- refusals -----------------------------------------------------------

    private fun refuseIfAlreadyMigrated() {
        val marker = plan.targetDir.resolve(MARKER_FILE)
        if (Files.exists(marker)) {
            throw MigrationRefused("${plan.targetDir} has already been migrated: ${Files.readString(marker)}")
        }
        for (artifact in listOf(plan.levelName, MOD_DIRECTORY, REGIONS_FILE, OPS_FILE)) {
            if (Files.exists(plan.targetDir.resolve(artifact))) {
                throw MigrationRefused(
                    "${plan.targetDir} already contains \"$artifact\" — migrate into a fresh server run directory",
                )
            }
        }
        if (Files.exists(staging)) {
            throw MigrationRefused("$staging is left over from an interrupted migration; remove it and run again")
        }
    }

    private fun refuseIfUnidentified(saves: List<String>, operators: List<String>) {
        if (plan.skipUnidentified || (saves.isEmpty() && operators.isEmpty())) return
        val operatorNote = if (operators.isEmpty()) {
            ""
        } else {
            " Operators cannot be quarantined, though: an op list is read before anyone connects, " +
                "so an operator no identity answers for is simply dropped and has to be re-opped."
        }
        throw MigrationRefused(
            (saves + operators.map { "operator $it" }).joinToString(
                prefix = "cannot identify every player the Portal's data mentions:\n  ",
                separator = "\n  ",
                postfix = "\nGive --identities a file of \"username\": \"<mojang uuid>\" entries for them, " +
                    "or pass --skip-unidentified to quarantine their saves instead — a quarantined save " +
                    "is claimed by its owner the first time they log in, which is how the names nobody " +
                    "has on file finally resolve.$operatorNote",
            ),
        )
    }

    // ---- reading ------------------------------------------------------------

    private fun resolveIdentities(): PlayerIdentities =
        PlayerIdentities.resolve(supplied = suppliedIdentities(), uuidCache = portalUuidCache)

    private fun suppliedIdentities(): Map<String, UUID> {
        val file = plan.identitiesFile ?: return emptyMap()
        return JsonParser.parseString(Files.readString(file)).asJsonObject.entrySet()
            .associate { (name, uuid) -> name to UUID.fromString(uuid.asString) }
    }

    /** The Portal's `uuid-cache.json`: Mojang uuid → username. */
    private val portalUuidCache: Map<UUID, String> by lazy {
        val file = plan.portalDir.resolve(UUID_CACHE_FILE)
        if (Files.notExists(file)) {
            emptyMap()
        } else {
            PortalJson.parse(Files.readString(file)).entries.associate { (uuid, field) ->
                UUID.fromString(uuid) to PortalJson.decodeString(field.rawValue)
            }
        }
    }

    /** Every backend save, keyed by the offline uuid its file is named after. */
    private val backendSaves: Map<UUID, List<BackendSave>> by lazy {
        val saves = LinkedHashMap<UUID, MutableList<BackendSave>>()
        for ((world, levelDir) in backends) {
            val directory = levelDir.resolve(PLAYERDATA_DIRECTORY)
            if (Files.notExists(directory)) continue
            Files.newDirectoryStream(directory, "*$PLAYERDATA_SUFFIX").use { files ->
                for (file in files) {
                    val uuid = UUID.fromString(file.name.removeSuffix(PLAYERDATA_SUFFIX))
                    saves.getOrPut(uuid) { mutableListOf() }.add(BackendSave(world, levelDir, uuid, file))
                }
            }
        }
        saves
    }

    private fun collectSaves(identities: PlayerIdentities): List<PlayerSaves> {
        val portalRecords: PlayerStore = JsonPlayerStore(plan.portalDir.resolve(PLAYERS_DIRECTORY))
        return backendSaves.mapNotNull { (offlineUuid, saves) ->
            val identity = identities[offlineUuid]
                ?: alreadyMojangKeyed(offlineUuid)
                ?: return@mapNotNull null
            // The World they were last on holds their live state; where the
            // Portal has no answer (or no save there), the save we do have is
            // the live one — a player is only ever in one World at a time.
            val lastWorld = portalRecords.lastWorld(identity.uuid)
            val preferred = lastWorld?.let {
                checkNotNull(WorldLayout.byId(it)) {
                    "${identity.name} has an unknown lastServer \"$it\" in their Portal record"
                }
            } ?: WorldLayout.PRIMARY
            val live = saves.firstOrNull { it.world == preferred } ?: saves.first()
            PlayerSaves(identity, live, saves.firstOrNull { it != live })
        }
    }

    /**
     * A save whose file is *already* named after a real Mojang uuid needs no
     * identification: the merged server keys by exactly that uuid, so the save is
     * carried across as itself.
     *
     * Real production data (MCTraveler, July 2026) turned this up: the Primary
     * backend held 2,700 version-4-keyed saves beside 11,825 offline-keyed ones —
     * the offline-mode assumption held for most of the proxy era's files but not
     * all of them. Version is the reliable discriminator, since an offline uuid is
     * md5-derived (version 3) and a Mojang uuid is random (version 4). Treating a
     * version-4 file as an unresolvable offline uuid would have discarded every one
     * of those players' inventories.
     *
     * The username is only needed for the name cache, so a Portal record's absence
     * is not fatal here; [nameFor] fills it in when the Portal knows it.
     */
    private fun alreadyMojangKeyed(uuid: UUID): PlayerIdentity? {
        if (uuid.version() == OFFLINE_UUID_VERSION) return null
        return PlayerIdentity(name = portalUuidCache[uuid] ?: uuid.toString(), uuid = uuid, fileUuid = uuid)
    }

    /**
     * Backend saves that belong to nobody we can name a Mojang uuid for — the
     * ones the migration quarantines for their owner to claim at login.
     *
     * Only offline-keyed files can land here: a version-4 name is already the
     * uuid the merged server keys by, and [alreadyMojangKeyed] resolves those.
     */
    private fun orphanedSaves(identities: PlayerIdentities): List<BackendSave> =
        backendSaves
            .filterKeys { identities[it] == null && it.version() == OFFLINE_UUID_VERSION }
            .values
            .flatten()

    /** [orphans] as the operator reads them: named where a backend's own profile cache still knows them. */
    private fun describe(orphans: List<BackendSave>): List<String> {
        if (orphans.isEmpty()) return emptyList()
        val known = backendUserCache()
        return orphans.map { save ->
            "${known[save.uuid] ?: "unknown player"} (${save.uuid}, ${save.world.id})"
        }
    }

    /** offline uuid → username, as the backends' own profile caches remember them. */
    private fun backendUserCache(): Map<UUID, String> =
        listOf(plan.primaryServerDir, plan.secondaryServerDir)
            .map { it.resolve(USER_CACHE_FILE) }
            .filter(Files::exists)
            .flatMap { file ->
                JsonParser.parseString(Files.readString(file)).asJsonArray.map { entry ->
                    val profile = entry.asJsonObject
                    UUID.fromString(profile.get("uuid").asString) to profile.get("name").asString
                }
            }
            .toMap()

    private fun backendOps(): List<OpEntry> =
        listOf(plan.primaryServerDir, plan.secondaryServerDir)
            .map { it.resolve(OPS_FILE) }
            .filter(Files::exists)
            .flatMap { OpsImport.parse(Files.readString(it)) }

    private class MigratedRegions(val text: String, val count: Int)

    private fun readRegions(): MigratedRegions? {
        val file = plan.portalDir.resolve(REGIONS_FILE)
        if (Files.notExists(file)) return null
        val text = RegionImport.migrate(Files.readString(file))
        return MigratedRegions(text, RegionStore.parse(text).sumOf(::countRegions))
    }

    private fun countRegions(region: Region): Int = 1 + region.subRegions.sumOf(::countRegions)

    // ---- staging ------------------------------------------------------------

    /**
     * Everything that reaches the new save by copy or by move, per
     * [ImportPlan.worldTransfer] — the levels and the quarantine — and so the one
     * phase that can disturb the Portal's own files. That is why [run] calls it
     * first under [WorldTransfer.COPY] and last under [WorldTransfer.MOVE].
     * Returns the number of quarantined save files.
     */
    private fun stageTransfers(orphans: List<BackendSave>): Int {
        stageLevel()
        return stageQuarantine(orphans)
    }

    /**
     * The Primary level as its own server wrote it — the version jump is the
     * new server's job — plus Secondary's chunk data in the dimension folders
     * the merged server reads its extra dimensions from. The per-player trees
     * are left out: they are rebuilt under Mojang uuids by [stageSaves].
     */
    private fun stageLevel() {
        val primaryLevel = backends.getValue(WorldLayout.PRIMARY)
        check(Files.isDirectory(primaryLevel)) { "no Primary level directory at $primaryLevel" }
        transferDirectory(
            primaryLevel,
            stagedLevel,
            skip = setOf(PLAYERDATA_DIRECTORY, ADVANCEMENTS_DIRECTORY, STATS_DIRECTORY, "session.lock"),
        )
        val secondaryLevel = backends.getValue(WorldLayout.SECONDARY)
        check(Files.isDirectory(secondaryLevel)) { "no Secondary level directory at $secondaryLevel" }
        for (role in DimensionRole.entries) {
            val from = backendDimension(secondaryLevel, role)
            val to = DimensionType.getStorageFolder(WorldLayout.SECONDARY.dimension(role), stagedLevel)
            for (folder in CHUNK_DIRECTORIES) {
                if (Files.isDirectory(from.resolve(folder))) {
                    transferDirectory(from.resolve(folder), to.resolve(folder))
                }
            }
        }
    }

    /**
     * The saves nobody could be identified from, parked in the [SaveQuarantine] with their
     * advancements and statistics, keyed by the offline uuid their files already carry —
     * the only handle their owner's username will hash to at login. Both Worlds' sidecars
     * come along: which World is the live one depends on a Portal record we cannot read
     * without the Mojang uuid we do not have yet, so that choice belongs to the claim.
     */
    private fun stageQuarantine(orphans: List<BackendSave>): Int {
        val quarantine = SaveQuarantine.under(staging.resolve(MOD_DIRECTORY))
        for (save in orphans) {
            transferFile(save.file, quarantine.save(save.world.id, save.uuid))
            transferSidecar(
                save.levelDir.resolve("$ADVANCEMENTS_DIRECTORY/${save.uuid}.json"),
                quarantine.advancements(save.world.id, save.uuid),
            )
            transferSidecar(
                save.levelDir.resolve("$STATS_DIRECTORY/${save.uuid}.json"),
                quarantine.stats(save.world.id, save.uuid),
            )
        }
        return orphans.size
    }

    private fun transferSidecar(from: Path, to: Path) {
        if (Files.exists(from)) transferFile(from, to)
    }

    /**
     * Copies or moves a tree according to [ImportPlan.worldTransfer]. A move renames whole
     * top-level entries where the filesystem allows it — the cheap case this exists for —
     * and falls back to a copy when the rename crosses devices.
     */
    private fun transferDirectory(from: Path, to: Path, skip: Set<String> = emptySet()) {
        if (plan.worldTransfer == WorldTransfer.COPY) return copyDirectory(from, to, skip)
        Files.createDirectories(to)
        Files.newDirectoryStream(from).use { entries ->
            for (entry in entries) {
                if (entry.name in skip) continue
                portalDataMoved = true
                try {
                    Files.move(entry, to.resolve(entry.name))
                } catch (crossDevice: java.nio.file.AtomicMoveNotSupportedException) {
                    fallbackTransfer(entry, to.resolve(entry.name))
                } catch (crossDevice: java.nio.file.FileSystemException) {
                    fallbackTransfer(entry, to.resolve(entry.name))
                }
            }
        }
    }

    /**
     * One file copied or moved according to [ImportPlan.worldTransfer]. Needs none of
     * [transferDirectory]'s cross-device fallback: a plain `Files.move` of a *file* on the
     * default provider degrades to copy-and-delete by itself — only a directory rename can't.
     */
    private fun transferFile(from: Path, to: Path) {
        Files.createDirectories(to.parent)
        if (plan.worldTransfer == WorldTransfer.COPY) {
            Files.copy(from, to)
        } else {
            portalDataMoved = true
            Files.move(from, to)
        }
    }

    /** A move the filesystem refused: copy the tree, then drop the source. */
    private fun fallbackTransfer(from: Path, to: Path) {
        if (Files.isDirectory(from)) {
            copyDirectory(from, to)
            deleteRecursively(from)
        } else {
            Files.createDirectories(to.parent)
            Files.copy(from, to)
            Files.deleteIfExists(from)
        }
    }

    /** The Portal's player records, verbatim — legacy fields included, whether we understand them or not. */
    private fun stagePlayerRecords(): Int {
        val from = plan.portalDir.resolve(PLAYERS_DIRECTORY)
        if (Files.notExists(from)) return 0
        val to = staging.resolve("$MOD_DIRECTORY/players")
        copyDirectory(from, to)
        Files.newDirectoryStream(to, "*.json").use { return it.count() }
    }

    private fun stageSaves(saves: List<PlayerSaves>, store: PlayerStore): Int {
        var buckets = 0
        for (player in saves) {
            val identity = player.identity
            try {
                writeLiveSave(player)
                copyPlayerFile(player.live.levelDir, ADVANCEMENTS_DIRECTORY, identity)
                copyPlayerFile(player.live.levelDir, STATS_DIRECTORY, identity)
                player.other?.let { other ->
                    store.setBucket(identity.uuid, other.world.id, PlayerdataImport.bucket(read(other.file)))
                    buckets++
                }
                if (store.lastWorld(identity.uuid) != player.live.world.id) {
                    store.setLastWorld(identity.uuid, player.live.world.id)
                }
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "could not migrate ${identity.name} (${identity.uuid}): ${failure.message}",
                    failure,
                )
            }
        }
        return buckets
    }

    private fun writeLiveSave(player: PlayerSaves) {
        val file = stagedLevel.resolve("$PLAYERDATA_DIRECTORY/${player.identity.uuid}$PLAYERDATA_SUFFIX")
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(PlayerdataImport.live(read(player.live.file), player.live.world), file)
    }

    /** A per-player side file (advancements, statistics) of the live save, re-keyed. */
    private fun copyPlayerFile(levelDir: Path, directory: String, identity: PlayerIdentity) {
        val from = levelDir.resolve("$directory/${identity.fileUuid}.json")
        if (Files.notExists(from)) return
        val to = stagedLevel.resolve("$directory/${identity.uuid}.json")
        Files.createDirectories(to.parent)
        Files.copy(from, to)
    }

    /**
     * The name cache the Portal only ever filled from `/op`, seeded with its
     * entries *and* every identity this migration resolved — so member lists
     * and `/rg locate` know everyone from the first boot.
     */
    private fun stageNameCache(identities: PlayerIdentities): Int {
        val names = NameCache(staging.resolve("$MOD_DIRECTORY/$UUID_CACHE_FILE"))
        val recorded = LinkedHashMap<UUID, String>()
        portalUuidCache.forEach { (uuid, name) -> recorded[uuid] = name }
        identities.all.forEach { identity -> recorded[identity.uuid] = identity.name }
        recorded.forEach { (uuid, name) -> names.record(uuid, name) }
        return recorded.size
    }

    /** The record that makes a second run refuse, and tells the operator what the first one did. */
    private fun writeMarker(report: ImportReport) {
        val marker = staging.resolve(MARKER_FILE)
        Files.createDirectories(marker.parent)
        Files.writeString(
            marker,
            """
            {
              "migratedAt": ${quote(Instant.now().toString())},
              "portal": ${quote(plan.portalDir.toAbsolutePath().toString())},
              "primary": ${quote(plan.primaryServerDir.toAbsolutePath().toString())},
              "secondary": ${quote(plan.secondaryServerDir.toAbsolutePath().toString())},
              "players": ${report.playersMigrated},
              "regions": ${report.regions}
            }
            """.trimIndent(),
        )
    }

    private fun quote(value: String): String = PortalJson.encodeString(value)

    /** Moves the finished migration into the run directory, one top-level entry at a time. */
    private fun commit() {
        Files.newDirectoryStream(staging).use { entries ->
            for (entry in entries) Files.move(entry, plan.targetDir.resolve(entry.name))
        }
        Files.delete(staging)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun read(playerdata: Path): CompoundTag =
        NbtIo.readCompressed(playerdata, NbtAccounter.unlimitedHeap())

    /** A backend's level directory, named by its own `server.properties`. */
    private fun backendLevel(serverDir: Path, fallbackName: String): Path {
        val properties = serverDir.resolve("server.properties")
        if (Files.notExists(properties)) return serverDir.resolve(fallbackName)
        val configured = Properties().apply {
            Files.newInputStream(properties).use(::load)
        }.getProperty("level-name")
        return serverDir.resolve(configured ?: fallbackName)
    }

    /** Where a vanilla backend kept [role]'s chunk data, relative to its level directory. */
    private fun backendDimension(levelDir: Path, role: DimensionRole): Path = when (role) {
        DimensionRole.OVERWORLD -> levelDir
        DimensionRole.NETHER -> levelDir.resolve("DIM-1")
        DimensionRole.END -> levelDir.resolve("DIM1")
    }

    private fun copyDirectory(from: Path, to: Path, skip: Set<String> = emptySet()) {
        Files.walk(from).use { paths ->
            for (path in paths) {
                val relative = from.relativize(path)
                if (relative.nameCount > 0 && relative.getName(0).toString() in skip) continue
                val destination = to.resolve(relative.toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
                }
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
        const val STAGING_DIRECTORY = ".mctraveler-import"
        const val MOD_DIRECTORY = "mctraveler"
        const val MARKER_FILE = "$MOD_DIRECTORY/import.json"
        const val PLAYERS_DIRECTORY = "players"
        const val UUID_CACHE_FILE = "uuid-cache.json"
        const val REGIONS_FILE = "regions.json"
        const val OPS_FILE = "ops.json"
        const val USER_CACHE_FILE = "usercache.json"
        const val PLAYERDATA_DIRECTORY = "playerdata"
        const val PLAYERDATA_SUFFIX = ".dat"

        /** Version 3 — md5-derived, i.e. an offline-mode uuid. A Mojang uuid is version 4. */
        const val OFFLINE_UUID_VERSION = 3
        const val ADVANCEMENTS_DIRECTORY = "advancements"
        const val STATS_DIRECTORY = "stats"
        val CHUNK_DIRECTORIES = listOf("region", "entities", "poi")
    }
}
