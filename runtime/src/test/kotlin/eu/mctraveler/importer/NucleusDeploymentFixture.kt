package eu.mctraveler.importer

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo

/**
 * A miniature cutover night on disk: the retired Nucleus server directory
 * beside the Fabric run directory the Portal migration already produced.
 *
 * Every path mirrors the real dedi — `embassies/` as a Bukkit world folder,
 * `plugins/MCTravelerNucleus/regions.json`, `world/playerdata/<uuid>.dat` on
 * the old side; `world/`, `regions.json` and `mctraveler/players/` on the new.
 */
class NucleusDeploymentFixture(val root: Path) {

    val oldDir: Path = root.resolve("nucleus")
    val targetDir: Path = root.resolve("run")

    val regionsFile: Path get() = targetDir.resolve("regions.json")
    val playersDir: Path get() = targetDir.resolve("mctraveler/players")
    val embassiesDimension: Path get() = targetDir.resolve("world/dimensions/mctraveler/embassies")
    val sourceWorld: Path get() = oldDir.resolve("embassies")

    /** Both sides as every test starts them: a populated old world, a migrated target. */
    fun build(): NucleusDeploymentFixture {
        chunks("region", "r.0.0.mca")
        chunks("region", "r.-1.0.mca")
        chunks("entities", "r.0.0.mca")
        chunks("poi", "r.0.0.mca")
        nucleusRegions(NUCLEUS_REGIONS)
        Files.createDirectories(oldDir.resolve("world/playerdata"))
        migratedTarget()
        return this
    }

    fun plan(worldTransfer: WorldTransfer = WorldTransfer.COPY) =
        EmbassyImportPlan(oldDir = oldDir, targetDir = targetDir, worldTransfer = worldTransfer)

    /** The run directory as `migrate` leaves it: a level, a region file, a mod directory. */
    fun migratedTarget() {
        Files.createDirectories(targetDir.resolve("world/dimensions/mctraveler/secondary/region"))
        Files.createDirectories(playersDir)
        write(regionsFile, TARGET_REGIONS)
    }

    /** A chunk file in the Nucleus embassies world (contents are opaque bytes). */
    fun chunks(folder: String, name: String) =
        write(sourceWorld.resolve("$folder/$name"), "chunk bytes of embassies/$folder/$name")

    fun nucleusRegions(json: String) =
        write(oldDir.resolve("plugins/MCTravelerNucleus/regions.json"), json)

    fun targetRegions(json: String) = write(regionsFile, json)

    fun targetPlayer(uuid: UUID, json: String) = write(playersDir.resolve("$uuid.json"), json)

    /**
     * A Nucleus save carrying the crystal tags in a Bukkit persistent data
     * container — [container] chooses which of the two spellings writes them.
     */
    fun playerdata(
        uuid: UUID,
        energy: Int? = null,
        nextRegenAt: Int? = null,
        container: String = "BukkitValues",
    ) = savePlayerdata(
        uuid,
        CompoundTag().apply {
            putString("Dimension", "minecraft:overworld")
            put(
                container,
                CompoundTag().apply {
                    energy?.let { putInt(NucleusPlayerdata.ENERGY_KEY, it) }
                    nextRegenAt?.let { putInt(NucleusPlayerdata.NEXT_REGEN_AT_KEY, it) }
                    putString("mctravelernucleus:username", "someone")
                },
            )
        },
    )

    /** A save from a player who never touched a crystal — a container, but not those keys. */
    fun playerdataWithoutTags(uuid: UUID) = savePlayerdata(
        uuid,
        CompoundTag().apply {
            putString("Dimension", "minecraft:overworld")
            put("BukkitValues", CompoundTag().apply { putString("mctravelernucleus:username", "someone") })
        },
    )

    fun savePlayerdata(uuid: UUID, tag: CompoundTag) {
        val file = oldDir.resolve("world/playerdata/$uuid.dat")
        Files.createDirectories(file.parent)
        NbtIo.writeCompressed(tag, file)
    }

    /** A file in `playerdata/` whose name is not a uuid — the live deployment had 93 of them. */
    fun strayPlayerdata(name: String) = write(oldDir.resolve("world/playerdata/$name"), "not a save")

    private fun write(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    companion object {
        val JAM: UUID = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val NOMAD: UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

        /**
         * `kotlinx.serialization`'s rendering of Nucleus's own `RegionData` — a
         * top-level array, defaults omitted — with two embassies and one
         * ordinary region of the main world that must be left where it is.
         */
        val NUCLEUS_REGIONS = """
            [
              {"title":"Jam's Embassy","start":{"x":3,"z":3,"y":320},"end":{"x":13,"z":13,"y":-64},
               "world":"embassies","members":["$JAM"],"flags":["EMBASSY"],"regions":[],
               "metadata":{"embassy-destination":{"x":123.5,"y":64.0,"z":-87.25,"yaw":90.0,"pitch":0.0,
               "world":"world"}}},
              {"title":"Spawn Town","start":{"x":-10,"z":-10,"y":320},"end":{"x":10,"z":10,"y":-64},
               "world":"world","members":[],"regions":[]},
              {"title":"Nomad's Embassy","start":{"x":19,"z":3,"y":320},"end":{"x":29,"z":13,"y":-64},
               "world":"embassies","members":["$NOMAD"],"flags":["EMBASSY"],"regions":[],
               "metadata":{"embassy-destination":{"x":-40.5,"y":72.0,"z":8.5,"yaw":-179.5,"pitch":12.25,
               "world":"last_nether"}}}
            ]
        """.trimIndent()

        /** The run directory's own `regions.json`, in the live store's exact formatting. */
        val TARGET_REGIONS = """
            {
              "regions": {
                "0": {
                  "title": "Wanderer's Keep",
                  "start-x": -10,
                  "start-z": -10,
                  "end-x": 10,
                  "end-z": 10,
                  "world": "last_nether",
                  "members": [
                    "11111111-2222-4333-8444-555555555555"
                  ]
                }
              }
            }
        """.trimIndent()

        /**
         * [TARGET_REGIONS] after the import: the pre-existing entry byte for
         * byte, then the two embassies as the live store writes them — y bounds
         * omitted because Nucleus's 320/−64 are exactly its defaults, and the
         * destination's numbers still spelled the way Nucleus spelled them.
         */
        val EXPECTED_REGIONS = """
            {
              "regions": {
                "0": {
                  "title": "Wanderer's Keep",
                  "start-x": -10,
                  "start-z": -10,
                  "end-x": 10,
                  "end-z": 10,
                  "world": "last_nether",
                  "members": [
                    "11111111-2222-4333-8444-555555555555"
                  ]
                },
                "1": {
                  "title": "Jam's Embassy",
                  "start-x": 3,
                  "start-z": 3,
                  "end-x": 13,
                  "end-z": 13,
                  "world": "embassies",
                  "members": [
                    "$JAM"
                  ],
                  "flags": [
                    "EMBASSY"
                  ],
                  "metadata": {
                    "embassy-destination": {
                      "x": 123.5,
                      "y": 64.0,
                      "z": -87.25,
                      "yaw": 90.0,
                      "pitch": 0.0,
                      "world": "world"
                    }
                  }
                },
                "2": {
                  "title": "Nomad's Embassy",
                  "start-x": 19,
                  "start-z": 3,
                  "end-x": 29,
                  "end-z": 13,
                  "world": "embassies",
                  "members": [
                    "$NOMAD"
                  ],
                  "flags": [
                    "EMBASSY"
                  ],
                  "metadata": {
                    "embassy-destination": {
                      "x": -40.5,
                      "y": 72.0,
                      "z": 8.5,
                      "yaw": -179.5,
                      "pitch": 12.25,
                      "world": "last_nether"
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
