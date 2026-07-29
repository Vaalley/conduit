package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo

/**
 * A miniature Portal deployment on disk — the fixture the importer tests (and
 * the migration gametest) run against. Every path and file format mirrors the
 * real thing (feature inventory §4): the Portal's own working directory beside
 * two vanilla backend server directories, `world` for Primary and `last` for
 * Secondary.
 */
class PortalDeploymentFixture(val root: Path) {

    val portalDir: Path = root.resolve("portal")
    val primaryServerDir: Path = portalDir.resolve("minecraft-server/primary")
    val secondaryServerDir: Path = portalDir.resolve("minecraft-server/secondary")
    private val primaryLevel: Path = primaryServerDir.resolve("world")
    private val secondaryLevel: Path = secondaryServerDir.resolve("last")

    /** The deployment as the tests know it, minus anything a test adds itself. */
    fun build(): PortalDeploymentFixture {
        writeLevel(primaryLevel)
        writeLevel(secondaryLevel)
        return this
    }

    fun plan(target: Path) = ImportPlan(
        portalDir = portalDir,
        primaryServerDir = primaryServerDir,
        secondaryServerDir = secondaryServerDir,
        targetDir = target,
    )

    /** A player record in the Portal's own format, legacy fields and all. */
    fun portalPlayer(uuid: UUID, json: String) =
        write(portalDir.resolve("players/$uuid.json"), json)

    fun uuidCache(entries: Map<UUID, String>) = write(
        portalDir.resolve("uuid-cache.json"),
        entries.entries.joinToString(",", "{", "}") { (uuid, name) -> "\"$uuid\":\"$name\"" },
    )

    fun regions(json: String) = write(portalDir.resolve("regions.json"), json)

    fun ops(vararg names: String) {
        val entries = names.joinToString(",", "[", "]") { name ->
            """{"uuid":"${OfflineUuid.of(name)}","name":"$name","level":4,"bypassesPlayerLimit":false}"""
        }
        write(primaryServerDir.resolve("ops.json"), entries)
        write(secondaryServerDir.resolve("ops.json"), entries)
    }

    /** Names the backends' own profile caches remember, by offline uuid. */
    fun userCache(vararg names: String) {
        val entries = names.joinToString(",", "[", "]") { name ->
            """{"name":"$name","uuid":"${OfflineUuid.of(name)}","expiresOn":"2027-01-01 00:00:00 +0000"}"""
        }
        write(primaryServerDir.resolve("usercache.json"), entries)
        write(secondaryServerDir.resolve("usercache.json"), entries)
    }

    /** A backend save for [name], as that backend's own vanilla server wrote it. */
    fun playerdata(
        world: String,
        name: String,
        dimension: String = "minecraft:overworld",
        pos: Triple<Double, Double, Double> = Triple(1.5, 64.0, -2.5),
        rotation: Pair<Float, Float> = 0f to 0f,
        respawn: Triple<Int, Int, Int>? = null,
        extras: CompoundTag.() -> Unit = {},
    ) {
        val tag = CompoundTag().apply {
            putString("Dimension", dimension)
            put("Pos", ListTag().apply {
                add(DoubleTag.valueOf(pos.first))
                add(DoubleTag.valueOf(pos.second))
                add(DoubleTag.valueOf(pos.third))
            })
            put("Rotation", ListTag().apply {
                add(FloatTag.valueOf(rotation.first))
                add(FloatTag.valueOf(rotation.second))
            })
            respawn?.let { (x, y, z) ->
                put("respawn", CompoundTag().apply {
                    putIntArray("pos", intArrayOf(x, y, z))
                    putFloat("angle", 0f)
                    putString("dimension", dimension)
                    putBoolean("forced", false)
                })
            }
            extras()
        }
        val file = level(world).resolve("playerdata/${OfflineUuid.of(name)}.dat")
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(tag, file)
    }

    fun advancements(world: String, name: String, json: String) =
        write(level(world).resolve("advancements/${OfflineUuid.of(name)}.json"), json)

    fun stats(world: String, name: String, json: String) =
        write(level(world).resolve("stats/${OfflineUuid.of(name)}.json"), json)

    /** A chunk file in one of a backend's three dimensions (contents are opaque bytes). */
    fun chunks(world: String, dimensionDir: String, name: String = "r.0.0.mca") =
        write(level(world).resolve(dimensionDir).resolve("region/$name"), "chunk bytes of $world/$dimensionDir")

    private fun level(world: String) = if (world == "primary") primaryLevel else secondaryLevel

    private fun writeLevel(level: Path) {
        Files.createDirectories(level)
        NbtIo.writeCompressed(CompoundTag().apply { putInt("DataVersion", 4536) }, level.resolve("level.dat"))
        write(level.resolve("session.lock"), "lock")
    }

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
