package eu.mctraveler.importer

import eu.mctraveler.worlds.DimensionRole
import java.nio.file.Path
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level

/**
 * One chunk of Secondary holding one of every coordinate-bearing thing a
 * player's base is made of (merge spec, "Testing Decisions"; ticket 03).
 *
 * [SyntheticChunks] writes the smallest thing that is still a chunk, which is all
 * the relocation itself needs proving against. The audit needs the opposite: a
 * chunk with a coordinate in every place a coordinate can hide, so that a walk
 * which misses one is a red test rather than a quiet one. Everything here is
 * written into the region files [SyntheticChunks] already laid out, at
 * [AT] — Secondary's overworld chunk (5, 3), blocks x 80…95 and z 48…63 — so a
 * save built this way relocates exactly as the other merge tests' does and every
 * number below can be worked out by hand.
 *
 * The parts are separate so a test composes the fixture it means to make a claim
 * about. [writeInto] is the chunk every test starts from;
 * [addTheInlineBlockPositions] and [addVillager] add the spellings 26.2 writes,
 * which the merge's patched relocation tool moves and the stock one did not (ticket
 * 16); [addBee] adds the one this fixture knows of that is *still* left behind, so
 * that a test proving the merge refuses has something honest to refuse over.
 */
object CoordinateBearingChunks {

    /** The chunk everything here stands in. Inside Secondary's region file (0, 0). */
    val AT = ChunkPos(5, 3)

    /** A block position, so a test can state where something should land rather than compute it. */
    data class Block(val x: Int, val y: Int, val z: Int) {
        fun merged(offset: MergeOffset, role: DimensionRole) =
            Block(offset.mergedX(x, role), y, offset.mergedZ(z, role))

        fun toList(): List<Int> = listOf(x, y, z)
    }

    val CHEST = Block(80, 64, 48)
    val COMPASS_TARGET = Block(81, 64, 49)

    /** The lodestone a compass inside a chest inside a shulker box inside the chest points at. */
    val NESTED_COMPASS_TARGET = Block(82, 64, 50)
    val COMMAND_BLOCK = Block(83, 64, 51)
    val BLOCK_TICK = Block(84, 64, 52)
    val FLUID_TICK = Block(85, 64, 53)

    /** The fence the cow is tied to — a place, unlike the mob a leash usually names. */
    val LEASH_KNOT = Block(86, 64, 54)
    val ITEM_FRAME = Block(90, 64, 58)
    val PAINTING = Block(91, 64, 59)
    val FRAMED_COMPASS_TARGET = Block(92, 64, 60)

    /** The three points of interest a villager claims: a bed, a workstation and a bell. */
    val BED = Block(80, 64, 48)
    val WORKSTATION = Block(81, 64, 49)
    val MEETING_POINT = Block(82, 64, 50)

    val COW = Triple(88.0, 64.0, 56.0)
    val VILLAGER = Triple(89.0, 64.0, 57.0)
    val BEE = Triple(87.0, 64.0, 55.0)

    /** The structure the chunk is part of, and the piece of it standing here. */
    val STRUCTURE_BOX = intArrayOf(80, 64, 48, 95, 79, 63)
    const val STRUCTURE = "minecraft:village"

    /** Written in 2019 and never touched since, which is the whole reason it is only reported. */
    const val COMMAND = "/tp @p 85 64 53"

    /**
     * The chunk as MCA Selector relocates it faithfully: the block entities and
     * their contents, the scheduled ticks, the structure it belongs to, the
     * entities standing in it and the points of interest they claim.
     *
     * The item frame and the painting carry their tile positions in the spelling
     * that was on disk before 1.21.5 inlined it. Since chunk data is only upgraded
     * when it is *loaded*, that is the one a Secondary chunk nobody has visited
     * since the Portal cutover still has, and it has to keep relocating however
     * well the merge learns the new one. What 26.2 writes instead is
     * [addTheInlineBlockPositions], which a test adds on top rather than instead.
     */
    fun writeInto(levelDir: Path, dimension: ResourceKey<Level>) {
        val storage = Footprint.storageFolder(levelDir, dimension)
        SyntheticChunks.write(storage.resolve("region"), "chunk", dimension, mapOf(AT to terrain()))
        SyntheticChunks.write(storage.resolve("entities"), "entities", dimension, mapOf(AT to entities()))
        SyntheticChunks.write(storage.resolve("poi"), "poi", dimension, mapOf(AT to pointsOfInterest()))
    }

    /**
     * The same chunk, plus the three places 26.2 records as an inline block
     * position: a leash tied to a fence, and the tile an item frame and a painting
     * hang on.
     *
     * These are separate from [writeInto] so that a chunk can be made to carry the
     * old spellings, the new ones, or both. Both is the interesting case and the one
     * the audit suite uses: [writeInto] lays down `Leash` and `TileX`/`TileY`/`TileZ`
     * and this adds `leash` and `block_pos` beside them, so a relocation that learned
     * the new spelling by forgetting the old one is a red test rather than a quiet
     * one. The stock MCA Selector 2.8 moved only the old ones; the patched build the
     * merge pins moves both (ticket 16).
     */
    fun addTheInlineBlockPositions(levelDir: Path, dimension: ResourceKey<Level>) {
        edit(levelDir, dimension, "entities", "entities") { tag ->
            for (entity in tag.getListOrEmpty("Entities")) {
                if (entity !is CompoundTag) continue
                when (entity.getStringOr("id", "")) {
                    "minecraft:cow" -> entity.putIntArray("leash", LEASH_KNOT.toIntArray())
                    "minecraft:item_frame" -> entity.putIntArray("block_pos", ITEM_FRAME.toIntArray())
                    "minecraft:painting" -> entity.putIntArray("block_pos", PAINTING.toIntArray())
                }
            }
        }
    }

    /**
     * A villager remembering the three places it has claimed.
     *
     * The positions are given rather than assumed so that a test can put a memory
     * somewhere the point-of-interest records do not claim, which is the only way
     * to ask the cross-check across the two files anything. Ordinarily they are
     * Secondary's own coordinates and the relocation is what moves them; the
     * memories go inside `value`, as `ExpirableValue` writes them, and
     * [addVillagerRememberingFlatly] is the older shape that must keep working too.
     */
    fun addVillager(
        levelDir: Path,
        dimension: ResourceKey<Level>,
        home: Block,
        jobSite: Block,
        meetingPoint: Block,
        remembers: String = "minecraft:overworld",
    ) {
        edit(levelDir, dimension, "entities", "entities") { tag ->
            tag.getListOrEmpty("Entities").add(
                CompoundTag().apply {
                    putString("id", "minecraft:villager")
                    put("Pos", vec3(VILLAGER))
                    putIntArray("UUID", intArrayOf(9, 9, 9, 9))
                    put(
                        "Brain",
                        CompoundTag().apply {
                            put(
                                "memories",
                                CompoundTag().apply {
                                    put("minecraft:home", memory(remembers, home))
                                    put("minecraft:job_site", memory(remembers, jobSite))
                                    put("minecraft:meeting_point", memory(remembers, meetingPoint))
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    /**
     * A villager whose memories are written flat — the global position sitting on
     * the memory itself rather than inside `value`.
     *
     * `ExpirableValue` has wrapped a memory in `value` for many versions, but a
     * chunk is only rewritten when something loads it, so Secondary still holds
     * chunks from before it. This is what proves the relocation's fix for the
     * wrapped shape was additive: both have to move, because the same save has
     * both.
     */
    fun addVillagerRememberingFlatly(levelDir: Path, dimension: ResourceKey<Level>, home: Block) {
        edit(levelDir, dimension, "entities", "entities") { tag ->
            tag.getListOrEmpty("Entities").add(
                CompoundTag().apply {
                    putString("id", "minecraft:villager")
                    put("Pos", vec3(VILLAGER))
                    putIntArray("UUID", intArrayOf(7, 7, 7, 7))
                    put(
                        "Brain",
                        CompoundTag().apply {
                            put(
                                "memories",
                                CompoundTag().apply {
                                    put("minecraft:home", globalPos(SECONDARY_OVERWORLD, home))
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    /**
     * A bee remembering the hive it came out of.
     *
     * Kept apart from [writeInto] because this one really is left behind. The
     * relocation tool moves an entity's positions from a list of the entity types
     * that have them, and it has no case for a bee, so `hive_pos` and `flower_pos`
     * arrive still naming Secondary. That is the same shape of defect as the leash
     * and the tile positions ticket 16 fixed, and it is still open — which is why
     * this is what `WorldMergeAuditTest` leaves stale on purpose to prove the merge
     * refuses. Fixing it upstream means finding a new one for that test.
     */
    fun addBee(levelDir: Path, dimension: ResourceKey<Level>, hive: Block) {
        edit(levelDir, dimension, "entities", "entities") { tag ->
            tag.getListOrEmpty("Entities").add(
                CompoundTag().apply {
                    putString("id", "minecraft:bee")
                    put("Pos", vec3(BEE))
                    putIntArray("UUID", intArrayOf(8, 8, 8, 8))
                    putIntArray("hive_pos", hive.toIntArray())
                },
            )
        }
    }

    /** [change] applied to the chunk at [AT], in place. */
    fun edit(
        levelDir: Path,
        dimension: ResourceKey<Level>,
        folder: String,
        type: String,
        change: (CompoundTag) -> Unit,
    ) {
        val at = Footprint.storageFolder(levelDir, dimension).resolve(folder)
        val tag = SyntheticChunks.read(at, type, dimension).getValue(AT)
        change(tag)
        SyntheticChunks.write(at, type, dimension, mapOf(AT to tag))
    }

    // ---- the chunks themselves ----------------------------------------------

    /**
     * The terrain: a chest with a lodestone compass in it and another buried
     * inside a shulker box inside a chest inside it, a command block nobody has
     * read since 2019, a scheduled block tick and fluid tick, and the village this
     * chunk is a piece of.
     */
    private fun terrain(): CompoundTag = CompoundTag().apply {
        putInt("DataVersion", SyntheticChunks.dataVersion)
        putInt("xPos", AT.x)
        putInt("yPos", MIN_SECTION)
        putInt("zPos", AT.z)
        putString("Status", SyntheticChunks.FULL)
        putLong("LastUpdate", 0L)
        putLong("InhabitedTime", 0L)
        put("sections", ListTag().apply { add(stone()) })
        put(
            "block_entities",
            ListTag().apply {
                add(
                    blockEntity("minecraft:chest", CHEST).apply {
                        put(
                            "Items",
                            ListTag().apply {
                                add(compass(COMPASS_TARGET).apply { putInt("Slot", 0) })
                                add(
                                    container(
                                        "minecraft:shulker_box",
                                        container("minecraft:chest", compass(NESTED_COMPASS_TARGET)),
                                    ).apply { putInt("Slot", 1) },
                                )
                            },
                        )
                    },
                )
                add(
                    blockEntity("minecraft:command_block", COMMAND_BLOCK).apply {
                        putString("Command", COMMAND)
                        putBoolean("auto", false)
                    },
                )
            },
        )
        put("block_ticks", ListTag().apply { add(tick("minecraft:water", BLOCK_TICK)) })
        put("fluid_ticks", ListTag().apply { add(tick("minecraft:lava", FLUID_TICK)) })
        put("Heightmaps", CompoundTag())
        put(
            "structures",
            CompoundTag().apply {
                put(
                    "starts",
                    CompoundTag().apply {
                        put(
                            STRUCTURE,
                            CompoundTag().apply {
                                putString("id", STRUCTURE)
                                putInt("ChunkX", AT.x)
                                putInt("ChunkZ", AT.z)
                                putInt("references", 1)
                                put(
                                    "Children",
                                    ListTag().apply {
                                        add(
                                            CompoundTag().apply {
                                                putString("id", "minecraft:jigsaw")
                                                putIntArray("BB", STRUCTURE_BOX)
                                                putInt("GD", 0)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "References",
                    CompoundTag().apply {
                        putLongArray(STRUCTURE, longArrayOf(packed(AT.x, AT.z)))
                    },
                )
            },
        )
    }

    /** A leashed cow, an item frame with a compass in it, and a painting. */
    private fun entities(): CompoundTag = CompoundTag().apply {
        putInt("DataVersion", SyntheticChunks.dataVersion)
        putIntArray("Position", intArrayOf(AT.x, AT.z))
        put(
            "Entities",
            ListTag().apply {
                add(
                    CompoundTag().apply {
                        putString("id", "minecraft:cow")
                        put("Pos", vec3(COW))
                        // A velocity, and the one thing here that must not move.
                        put("Motion", vec3(Triple(0.1, 0.0, -0.2)))
                        putIntArray("UUID", intArrayOf(1, 2, 3, 4))
                        put(
                            "Leash",
                            CompoundTag().apply {
                                putInt("X", LEASH_KNOT.x)
                                putInt("Y", LEASH_KNOT.y)
                                putInt("Z", LEASH_KNOT.z)
                            },
                        )
                    },
                )
                add(
                    hanging("minecraft:item_frame", ITEM_FRAME).apply {
                        putByte("Facing", 1)
                        put("Item", compass(FRAMED_COMPASS_TARGET))
                    },
                )
                add(hanging("minecraft:painting", PAINTING))
            },
        )
    }

    /** The bed, the workstation and the bell the villager's memories claim. */
    private fun pointsOfInterest(): CompoundTag = CompoundTag().apply {
        putInt("DataVersion", SyntheticChunks.dataVersion)
        put(
            "Sections",
            CompoundTag().apply {
                put(
                    SECTION_KEY,
                    CompoundTag().apply {
                        putBoolean("Valid", true)
                        put(
                            "Records",
                            ListTag().apply {
                                add(record(BED, "minecraft:home", 1))
                                add(record(WORKSTATION, "minecraft:farmer", 1))
                                add(record(MEETING_POINT, "minecraft:meeting", 6))
                            },
                        )
                    },
                )
            },
        )
    }

    // ---- the pieces ---------------------------------------------------------

    private fun stone(): CompoundTag = CompoundTag().apply {
        putByte("Y", MIN_SECTION.toByte())
        put(
            "block_states",
            CompoundTag().apply {
                put(
                    "palette",
                    ListTag().apply { add(CompoundTag().also { it.putString("Name", "minecraft:stone") }) },
                )
            },
        )
    }

    private fun blockEntity(id: String, at: Block): CompoundTag = CompoundTag().apply {
        putString("id", id)
        putInt("x", at.x)
        putInt("y", at.y)
        putInt("z", at.z)
    }

    private fun tick(what: String, at: Block): CompoundTag = CompoundTag().apply {
        putString("i", what)
        putInt("x", at.x)
        putInt("y", at.y)
        putInt("z", at.z)
        putInt("t", 0)
        putInt("p", 0)
    }

    /** An entity hung on a block, in the spelling MCA Selector still relocates. */
    private fun hanging(id: String, at: Block): CompoundTag = CompoundTag().apply {
        putString("id", id)
        put("Pos", vec3(Triple(at.x + 0.5, at.y.toDouble(), at.z + 0.5)))
        putIntArray("UUID", intArrayOf(id.hashCode(), 2, 3, 4))
        putInt("TileX", at.x)
        putInt("TileY", at.y)
        putInt("TileZ", at.z)
    }

    private fun record(at: Block, type: String, freeTickets: Int): CompoundTag = CompoundTag().apply {
        putIntArray("pos", at.toIntArray())
        putString("type", type)
        putInt("free_tickets", freeTickets)
    }

    /** A compass bound to a lodestone at [at], in whichever World [dimension] names. */
    fun compass(at: Block, dimension: String = SECONDARY_OVERWORLD): CompoundTag = CompoundTag().apply {
        putString("id", "minecraft:compass")
        putInt("count", 1)
        put(
            "components",
            CompoundTag().apply {
                put(
                    "minecraft:lodestone_tracker",
                    CompoundTag().apply {
                        put("target", globalPos(dimension, at))
                        putBoolean("tracked", true)
                    },
                )
            },
        )
    }

    /** A container item of [id] holding [item] in its first slot. */
    fun container(id: String, item: CompoundTag): CompoundTag = CompoundTag().apply {
        putString("id", id)
        putInt("count", 1)
        put(
            "components",
            CompoundTag().apply {
                put(
                    "minecraft:container",
                    ListTag().apply {
                        add(CompoundTag().apply { putInt("slot", 0); put("item", item) })
                    },
                )
            },
        )
    }

    /**
     * A brain memory as 26.2 writes it: `ExpirableValue` wraps whatever it holds
     * in `value`, so a villager's home is a global position one level down.
     */
    private fun memory(dimension: String, at: Block): CompoundTag =
        CompoundTag().apply { put("value", globalPos(dimension, at)) }

    fun globalPos(dimension: String, at: Block): CompoundTag = CompoundTag().apply {
        putString("dimension", dimension)
        putIntArray("pos", at.toIntArray())
    }

    private fun vec3(at: Triple<Double, Double, Double>) = ListTag().apply {
        add(DoubleTag.valueOf(at.first))
        add(DoubleTag.valueOf(at.second))
        add(DoubleTag.valueOf(at.third))
    }

    private fun Block.toIntArray() = intArrayOf(x, y, z)

    /** A chunk position as a structure reference packs it: x in the low word, z in the high one. */
    private fun packed(x: Int, z: Int): Long = (x.toLong() and 0xFFFFFFFFL) or (z.toLong() shl Int.SIZE_BITS)

    const val SECONDARY_OVERWORLD = "mctraveler:secondary"

    /** Section y 4, which is blocks 64…79 — where everything above stands. */
    private const val SECTION_KEY = "4"

    /** The bottom section of a 26.2 overworld chunk, in sections. */
    private const val MIN_SECTION = -4
}
