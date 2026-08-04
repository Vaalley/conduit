package eu.mctraveler.importer

import java.nio.file.Path
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The player sweep, driven through the merge command end to end against the same
 * synthetic run directory [WorldMergeTest] plans against (merge spec, "Testing
 * Decisions"; ticket 06). A sibling of that class rather than more of it: the
 * seam is the same one — [WorldMerge.run] over a [MergedDeploymentFixture] — and
 * the sweep is fiddly enough to be worth reading on its own.
 *
 * Every test passes the offset explicitly, and passes one whose two axes differ,
 * whose Z is negative and whose nether eighth is not its overworld value. That is
 * deliberate: the searched offset for this fixture happens to be `x +8192, z +0`,
 * and against a zero Z a sweep that lost the Z shift, flipped its sign or swapped
 * the axes would pass every assertion here. The numbers below are all worked out
 * by hand from `x +8192, z -4096` in the overworld and its eighth, `x +1024,
 * z -512`, in the nether.
 */
class WorldMergePlayerSweepTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /**
         * A clear slot for this fixture with both axes in play. Secondary's
         * overworld lands 16 region files east and 8 north of where it is, its
         * nether 2 east and 1 north, and neither footprint plus its ring reaches
         * Primary's — so this offset passes exactly the check a searched one does.
         */
        private val OFFSET = MergeOffset(8192, -4096)

        /** The merge stamps its records with the moment it ran; nothing else here is a timestamp. */
        private val WHEN_IT_RAN = Regex("\"\\d{4}-\\d{2}-\\d{2}T[^\"]+\"")

        private val ALICE = UUID.fromString("11111111-2222-4333-8444-555555555555")
        private val BOB = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")
    }

    @TempDir
    lateinit var dir: Path

    private lateinit var deployment: MergedDeploymentFixture

    @BeforeEach
    fun buildDeployment() {
        deployment = MergedDeploymentFixture(dir).build()
    }

    /**
     * [acceptEndLoss] is only ever passed by the test that leaves a player in
     * Secondary's End: the End gate refuses the whole merge over one otherwise,
     * and what this suite is about is what the sweep did before that gate ran.
     */
    private fun merge(acceptEndLoss: Boolean = false): MergeReport =
        WorldMerge(deployment.plan(offset = OFFSET, planOnly = false, acceptEndLoss = acceptEndLoss)).run()

    // ---- the two mirrors ----------------------------------------------------

    @Test
    fun `a player live in Secondary is moved, and the Primary position they banked is not`() {
        deployment.playerSave(
            ALICE,
            save("mctraveler:secondary", x = 100.5, y = 64.0, z = -200.25, yaw = 90f, pitch = -10f),
        )
        deployment.playerRecord(
            ALICE,
            """{"lastServer":"secondary","worlds":{"primary":""" +
                """{"dimension":"overworld","x":1.5,"y":70.0,"z":2.5,"yaw":0.0,"pitch":0.0}}}""",
        )

        merge()

        val live = deployment.savedPlayer(ALICE)
        assertEquals("minecraft:overworld", live.getStringOr("Dimension", ""))
        // 100.5 + 8192, unchanged, -200.25 - 4096
        assertEquals(listOf(8292.5, 64.0, -4296.25), positionIn(live))
        assertEquals(90f, live.getListOrEmpty("Rotation").getFloatOr(0, 0f))
        assertEquals(-10f, live.getListOrEmpty("Rotation").getFloatOr(1, 0f))
        // The banked bucket is Primary's, and nothing in Primary moved. The
        // stamp keeps the World this record named before the sweep rewrote it,
        // which is the only place that answer survives; see MergeStamp.wasLastIn.
        assertEquals(
            """{"lastServer":"primary","worlds":{"primary":""" +
                """{"dimension":"overworld","x":1.5,"y":70.0,"z":2.5,"yaw":0.0,"pitch":0.0}},""" +
                """"merge":{"at":"<when>","offset":{"x":8192,"z":-4096},"wasLastIn":"secondary"}}""",
            recordOf(ALICE),
        )
    }

    @Test
    fun `a player live in Primary is not moved, and the Secondary position they banked is`() {
        deployment.playerSave(BOB, save("minecraft:overworld", x = 10.5, y = 64.0, z = 20.5))
        deployment.playerRecord(
            BOB,
            """{"lastServer":"primary","worlds":{"primary":""" +
                """{ "dimension":"overworld", "x":9.5, "y":65.0, "z":-3.5, "yaw":1.0, "pitch":2.0 },""" +
                """"secondary":{"dimension":"nether","x":16.0,"y":70.0,"z":-8.0,"yaw":0.0,"pitch":0.0,""" +
                """"respawn":{"dimension":"nether","x":16,"y":70,"z":-8,""" +
                """"yaw":0.0,"pitch":0.0,"forced":false}}}}""",
        )

        merge()

        val live = deployment.savedPlayer(BOB)
        assertEquals("minecraft:overworld", live.getStringOr("Dimension", ""))
        assertEquals(listOf(10.5, 64.0, 20.5), positionIn(live))
        // The Secondary bucket is in the nether, so it moves by the eighth:
        // 16 + 1024 and -8 - 512, its bed with it. Primary's bucket comes back
        // out with the spaces it went in with — never re-encoded at all.
        assertEquals(
            """{"lastServer":"primary","worlds":{"primary":""" +
                """{ "dimension":"overworld", "x":9.5, "y":65.0, "z":-3.5, "yaw":1.0, "pitch":2.0 },""" +
                """"secondary":{"dimension":"nether","x":1040.0,"y":70.0,"z":-520.0,""" +
                """"yaw":0.0,"pitch":0.0,""" +
                """"respawn":{"dimension":"nether","x":1040,"y":70,"z":-520,""" +
                """"yaw":0.0,"pitch":0.0,"forced":false}}},""" +
                """"merge":{"at":"<when>","offset":{"x":8192,"z":-4096},"wasLastIn":"primary"}}""",
            recordOf(BOB),
        )
    }

    @Test
    fun `both mirrors bank the position the player is not standing in`() {
        deployment.playerSave(ALICE, save("mctraveler:secondary"))
        deployment.playerRecord(
            ALICE,
            """{"lastServer":"secondary","worlds":{"primary":""" +
                """{"dimension":"overworld","x":1.5,"y":70.0,"z":2.5,"yaw":0.0,"pitch":0.0}}}""",
        )
        deployment.playerSave(BOB, save("minecraft:overworld"))
        deployment.playerRecord(
            BOB,
            """{"lastServer":"primary","worlds":{"secondary":""" +
                """{"dimension":"nether","x":16.0,"y":70.0,"z":-8.0,"yaw":0.0,"pitch":0.0}}}""",
        )

        merge()

        assertEquals(
            """
            |{
            |  "mergedAt": "<when>",
            |  "offset": {"x": 8192, "z": -4096},
            |  "players": {
            |    "11111111-2222-4333-8444-555555555555": {"world":"primary","dimension":"minecraft:overworld","x":1.5,"y":70.0,"z":2.5},
            |    "66666666-7777-4888-8999-aaaaaaaaaaaa": {"world":"secondary","dimension":"minecraft:the_nether","x":1040.0,"y":70.0,"z":-520.0}
            |  }
            |}
            |
            """.trimMargin(),
            withoutTheHour(checkNotNull(deployment.bankedPositions())),
        )
    }

    // ---- everything else a player remembers ---------------------------------

    @Test
    fun `their bed, their last death and the nether they entered from all move`() {
        deployment.playerSave(
            ALICE,
            save("mctraveler:secondary_nether", x = 1.0, y = 40.0, z = 2.0) {
                put("respawn", respawn("mctraveler:secondary", 10, 70, 20))
                put("LastDeathLocation", globalPos("mctraveler:secondary_nether", 5, 60, -5))
                put("entered_nether_pos", vec3(3.0, 64.0, 7.0))
            },
        )

        merge()

        val live = deployment.savedPlayer(ALICE)
        // Standing in the nether, so the position itself takes the eighth.
        assertEquals("minecraft:the_nether", live.getStringOr("Dimension", ""))
        assertEquals(listOf(1025.0, 40.0, -510.0), positionIn(live))
        // The bed stands in Secondary's overworld and takes the whole shift.
        val bed = live.getCompoundOrEmpty("respawn")
        assertEquals("minecraft:overworld", bed.getStringOr("dimension", ""))
        assertEquals(listOf(8202, 70, -4076), bed.getIntArray("pos").orElseThrow().toList())
        assertEquals(45f, bed.getFloatOr("yaw", 0f))
        // They died in Secondary's nether: the eighth again.
        val death = live.getCompoundOrEmpty("LastDeathLocation")
        assertEquals("minecraft:the_nether", death.getStringOr("dimension", ""))
        assertEquals(listOf(1029, 60, -517), death.getIntArray("pos").orElseThrow().toList())
        // The point they entered the nether from is an *overworld* position even
        // though they are standing in the nether, so it takes the whole shift.
        assertEquals(listOf(8195.0, 64.0, -4089.0), live.getListOrEmpty("entered_nether_pos").doubles())
    }

    @Test
    fun `a player logged out in a boat arrives still in it, and so does what was riding with them`() {
        deployment.playerSave(
            ALICE,
            save("mctraveler:secondary", x = 1.5, y = 63.0, z = 2.5) {
                put(
                    "RootVehicle",
                    CompoundTag().apply {
                        putIntArray("Attach", intArrayOf(1, 2, 3, 4))
                        put(
                            "Entity",
                            CompoundTag().apply {
                                putString("id", "minecraft:oak_boat")
                                put("Pos", vec3(1.5, 63.0, 2.5))
                                put("Motion", vec3(0.1, 0.0, -0.2))
                                putIntArray("leash", intArrayOf(4, 65, 6))
                                put(
                                    "Passengers",
                                    ListTag().apply {
                                        add(
                                            CompoundTag().apply {
                                                putString("id", "minecraft:cow")
                                                put("Pos", vec3(1.5, 63.0, 2.5))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        merge()

        val vehicle = deployment.savedPlayer(ALICE).getCompoundOrEmpty("RootVehicle")
        // The seat the player was in is a uuid — an int array of four, not a place.
        assertEquals(listOf(1, 2, 3, 4), vehicle.getIntArray("Attach").orElseThrow().toList())
        val boat = vehicle.getCompoundOrEmpty("Entity")
        assertEquals(listOf(8193.5, 63.0, -4093.5), boat.getListOrEmpty("Pos").doubles())
        // A velocity is not a place.
        assertEquals(listOf(0.1, 0.0, -0.2), boat.getListOrEmpty("Motion").doubles())
        // The fence it is tied to moved with everything else.
        assertEquals(listOf(8196, 65, -4090), boat.getIntArray("leash").orElseThrow().toList())
        assertEquals(
            listOf(8193.5, 63.0, -4093.5),
            boat.getListOrEmpty("Passengers").getCompoundOrEmpty(0).getListOrEmpty("Pos").doubles(),
        )
    }

    @Test
    fun `a lodestone compass is retargeted however deeply it is buried`() {
        deployment.playerSave(
            ALICE,
            save("mctraveler:secondary") {
                put(
                    "Inventory",
                    ListTag().apply {
                        add(
                            container(
                                "minecraft:shulker_box",
                                container("minecraft:chest", compass("mctraveler:secondary", 1, 2, 3)),
                            ),
                        )
                    },
                )
            },
        )

        merge()

        val outerBox = deployment.savedPlayer(ALICE).getListOrEmpty("Inventory").getCompoundOrEmpty(0)
        val target = itemIn(itemIn(outerBox))
            .getCompoundOrEmpty("components")
            .getCompoundOrEmpty("minecraft:lodestone_tracker")
            .getCompoundOrEmpty("target")
        assertEquals("minecraft:overworld", target.getStringOr("dimension", ""))
        assertEquals(listOf(8193, 2, -4093), target.getIntArray("pos").orElseThrow().toList())
    }

    @Test
    fun `a player who never left Primary still has their Secondary compass retargeted`() {
        // "Not moved, and neither is anything they own" means nothing of theirs
        // that was already in Primary. A compass bound to a Secondary lodestone is
        // not: which side a place is on is the place's answer, not its owner's.
        deployment.playerSave(
            BOB,
            save("minecraft:overworld", x = 10.5, y = 64.0, z = 20.5) {
                put("respawn", respawn("minecraft:overworld", 1, 64, 2))
                put(
                    "EnderItems",
                    ListTag().apply {
                        add(compass("mctraveler:secondary_nether", 8, 70, 16))
                        add(compass("minecraft:overworld", 4, 64, 4))
                    },
                )
            },
        )

        merge()

        val live = deployment.savedPlayer(BOB)
        assertEquals(listOf(10.5, 64.0, 20.5), positionIn(live))
        assertEquals("minecraft:overworld", live.getCompoundOrEmpty("respawn").getStringOr("dimension", ""))
        assertEquals(listOf(1, 64, 2), live.getCompoundOrEmpty("respawn").getIntArray("pos").orElseThrow().toList())
        val chest = live.getListOrEmpty("EnderItems")
        // 8 + 1024 and 16 - 512: the lodestone stands in Secondary's nether.
        assertEquals(listOf(1032, 70, -496), targetOf(chest.getCompoundOrEmpty(0)))
        // The Primary one is left exactly as it was.
        assertEquals(listOf(4, 64, 4), targetOf(chest.getCompoundOrEmpty(1)))
    }

    @Test
    fun `the positions a save records with no dimension of their own move with the player`() {
        deployment.playerSave(
            ALICE,
            save("mctraveler:secondary", x = 1.0, y = 64.0, z = 2.0) {
                putIntArray("sleeping_pos", intArrayOf(3, 64, 4))
                putIntArray("raid_omen_position", intArrayOf(5, 64, 6))
                put("last_explosion_impact_pos", vec3(7.0, 64.0, 8.0))
                put("current_explosion_impact_pos", vec3(9.0, 64.0, 10.0))
                put(
                    "ShoulderEntityLeft",
                    CompoundTag().apply {
                        putString("id", "minecraft:parrot")
                        put("Pos", vec3(11.0, 64.0, 12.0))
                    },
                )
            },
        )

        merge()

        val live = deployment.savedPlayer(ALICE)
        assertEquals(listOf(8195, 64, -4092), live.getIntArray("sleeping_pos").orElseThrow().toList())
        assertEquals(listOf(8197, 64, -4090), live.getIntArray("raid_omen_position").orElseThrow().toList())
        assertEquals(listOf(8199.0, 64.0, -4088.0), live.getListOrEmpty("last_explosion_impact_pos").doubles())
        assertEquals(listOf(8201.0, 64.0, -4086.0), live.getListOrEmpty("current_explosion_impact_pos").doubles())
        assertEquals(
            listOf(8203.0, 64.0, -4084.0),
            live.getCompoundOrEmpty("ShoulderEntityLeft").getListOrEmpty("Pos").doubles(),
        )
    }

    @Test
    fun `an ender pearl still in the air over Secondary lands over the relocated landmass`() {
        deployment.playerSave(
            ALICE,
            save("minecraft:overworld") {
                put(
                    "ender_pearls",
                    ListTag().apply {
                        add(
                            CompoundTag().apply {
                                putString("id", "minecraft:ender_pearl")
                                put("Pos", vec3(11.5, 80.0, 12.5))
                                putString("ender_pearl_dimension", "mctraveler:secondary")
                            },
                        )
                    },
                )
            },
        )

        merge()

        val pearl = deployment.savedPlayer(ALICE).getListOrEmpty("ender_pearls").getCompoundOrEmpty(0)
        assertEquals("minecraft:overworld", pearl.getStringOr("ender_pearl_dimension", ""))
        assertEquals(listOf(8203.5, 80.0, -4083.5), pearl.getListOrEmpty("Pos").doubles())
    }

    @Test
    fun `a spawn point still in its pre-1_21_5 spelling moves too`() {
        // Playerdata is only upgraded when it is loaded, so a player who has not
        // logged in since the Portal cutover still has the flat form on disk.
        deployment.playerSave(
            ALICE,
            save("mctraveler:secondary") {
                putString("SpawnDimension", "mctraveler:secondary")
                putInt("SpawnX", 10)
                putInt("SpawnY", 70)
                putInt("SpawnZ", 20)
            },
        )

        merge()

        val live = deployment.savedPlayer(ALICE)
        assertEquals("minecraft:overworld", live.getStringOr("SpawnDimension", ""))
        assertEquals(8202, live.getIntOr("SpawnX", 0))
        assertEquals(70, live.getIntOr("SpawnY", 0))
        assertEquals(-4076, live.getIntOr("SpawnZ", 0))
    }

    @Test
    fun `vanilla's backup of a save is swept beside the save itself`() {
        // PlayerDataStorage falls back to it when the live save will not read, and
        // an unswept backup would strand its owner on exactly that day.
        deployment.playerSave(ALICE, save("mctraveler:secondary", x = 1.0, y = 64.0, z = 2.0))
        deployment.playerSaveBackup(ALICE, save("mctraveler:secondary", x = 3.0, y = 64.0, z = 4.0))

        merge()

        val backup = deployment.savedPlayerBackup(ALICE)
        assertEquals("minecraft:overworld", backup.getStringOr("Dimension", ""))
        assertEquals(listOf(8195.0, 64.0, -4092.0), backup.getListOrEmpty("Pos").doubles())
    }

    // ---- what the record keeps ----------------------------------------------

    @Test
    fun `every other field in a record survives the sweep byte for byte`() {
        deployment.playerRecord(
            ALICE,
            """{"lastServer":"secondary","balance":1234.50,"isAdmin":true,""" +
                """"geoLocation":{"country":"NL",  "city":"Amsterdam"},""" +
                """"notepad":["page \"one\""],"fromSomeFutureVersion":[1,2,{"a":null}]}""",
        )

        merge()

        // Not re-serialized, re-emitted: 1234.50 has not become 1234.5, the
        // escape is untouched, and the spaces inside geoLocation are still there.
        assertEquals(
            """{"lastServer":"primary","balance":1234.50,"isAdmin":true,""" +
                """"geoLocation":{"country":"NL",  "city":"Amsterdam"},""" +
                """"notepad":["page \"one\""],"fromSomeFutureVersion":[1,2,{"a":null}],""" +
                """"merge":{"at":"<when>","offset":{"x":8192,"z":-4096},"wasLastIn":"secondary"}}""",
            recordOf(ALICE),
        )
    }

    @Test
    fun `the stamp keeps the World a record named before the sweep rewrote it`() {
        // The claim path's only source for it (ticket 14). `lastServer` is
        // rewritten to Primary for everyone — there is one World afterwards — but
        // it is also what decides which of a returning player's two quarantined
        // saves becomes their live one. Once the sweep has been over it, this
        // stamp is the only thing left that remembers which World that was.
        deployment.playerSave(ALICE, save("mctraveler:secondary"))
        deployment.playerRecord(ALICE, """{"lastServer":"secondary"}""")
        deployment.playerSave(BOB, save("minecraft:overworld"))
        deployment.playerRecord(
            BOB,
            """{"lastServer":"primary","worlds":{"secondary":""" +
                """{"dimension":"nether","x":16.0,"y":70.0,"z":-8.0,"yaw":0.0,"pitch":0.0}}}""",
        )

        merge()

        assertEquals(WorldLayout.SECONDARY, MergeStamp.wasLastIn(deployment.recordFile(ALICE)))
        assertEquals(
            """{"lastServer":"primary","merge":{"at":"<when>",""" +
                """"offset":{"x":8192,"z":-4096},"wasLastIn":"secondary"}}""",
            recordOf(ALICE),
            "the field it answers with now says Primary, and the stamp says what it said before",
        )
        // Both mirrors, because the claim path prefers the stamp whichever World
        // it names: a record whose answer the sweep happened not to change still
        // carries that answer, so nothing downstream has to know which case it is.
        assertEquals(WorldLayout.PRIMARY, MergeStamp.wasLastIn(deployment.recordFile(BOB)))
    }

    @Test
    fun `a player the merge had nothing to do for is never written to at all`() {
        deployment.playerSave(BOB, save("minecraft:overworld", x = 10.5, y = 64.0, z = 20.5))
        deployment.playerRecord(BOB, """{"lastServer":"primary","balance":10}""")
        // Bob's own files, not the whole run directory: the merge around him
        // legitimately relocates chunks and stamps the save, and the claim this
        // test makes is about him — nothing of his is opened for writing at all.
        val before = deployment.contents().filterKeys { BOB.toString() in it }

        val report = merge()

        assertEquals(before, deployment.contents().filterKeys { BOB.toString() in it })
        assertEquals("""{"lastServer":"primary","balance":10}""", deployment.savedRecord(BOB))
        assertEquals(0, report.players.swept)
        assertEquals(1, report.players.leftAlone)
        assertNull(deployment.bankedPositions())
    }

    // ---- Secondary's End ----------------------------------------------------

    @Test
    fun `a player standing in Secondary's End is left where they are, and named`() {
        deployment.withWorldSpawn(0, 64, 0)
        deployment.playerSave(ALICE, save("mctraveler:secondary_end", x = 1.0, y = 50.0, z = 2.0))
        deployment.playerRecord(ALICE, """{"lastServer":"secondary"}""")

        // The End is discarded rather than relocated, so it has no offset and
        // there is nowhere for this sweep to move them to: it names them and
        // leaves them exactly as they are. Where they end up is the End gate's
        // decision and is asserted in WorldMergeEndGateTest — which is also why
        // the loss has to be accepted here, or that gate stops the whole merge
        // over this one player.
        val report = merge(acceptEndLoss = true)

        assertEquals(listOf(ALICE), report.players.anchoredInSecondaryEnd)
        assertTrue(
            report.lines().contains(
                "left in Secondary's End  : 1 — not moved; the End is discarded, not relocated",
            ),
            "the report says nothing about the players it left in the End: ${report.lines()}",
        )
    }

    @Test
    fun `a bucket banked in Secondary's End is not offered to the signpost`() {
        deployment.playerSave(BOB, save("minecraft:overworld"))
        deployment.playerRecord(
            BOB,
            """{"lastServer":"primary","worlds":{"secondary":""" +
                """{"dimension":"end","x":1.0,"y":50.0,"z":2.0,"yaw":0.0,"pitch":0.0}}}""",
        )

        val report = merge()

        assertEquals(emptyList<BankedPosition>(), report.players.banked)
        assertNull(deployment.bankedPositions())
        assertEquals("""{"lastServer":"primary","worlds":{"secondary":""" +
            """{"dimension":"end","x":1.0,"y":50.0,"z":2.0,"yaw":0.0,"pitch":0.0}}}""",
            deployment.savedRecord(BOB),
        )
    }

    // ---- the report and the staging discipline ------------------------------

    @Test
    fun `the report counts who moved, who did not, and how many were told where their base went`() {
        deployment.playerSave(ALICE, save("mctraveler:secondary"))
        deployment.playerRecord(
            ALICE,
            """{"lastServer":"secondary","worlds":{"primary":""" +
                """{"dimension":"overworld","x":1.5,"y":70.0,"z":2.5,"yaw":0.0,"pitch":0.0}}}""",
        )
        deployment.playerSave(BOB, save("minecraft:overworld"))

        val report = merge()

        assertEquals(
            listOf(
                "players swept            : 1",
                "players left alone       : 1",
                "banked positions         : 1",
            ),
            report.players.lines(),
        )
        // The placement the operator has to see either way is still first.
        assertEquals("offset                   : x +8192, z -4096  (nether x +1024, z -512)", report.lines()[0])
    }

    @Test
    fun `a sweep that fails part way through leaves the run directory exactly as it was`() {
        deployment.playerSave(ALICE, save("mctraveler:secondary"))
        deployment.playerRecord(ALICE, """{"lastServer":"secondary"}""")
        deployment.playerRecord(BOB, """{"lastServer":}""")
        val before = deployment.contents()

        val failure = assertThrows(IllegalStateException::class.java) { merge() }

        assertEquals("could not sweep player $BOB: expected a JSON value", failure.message)
        assertEquals(before, deployment.contents())
        assertEquals("""{"lastServer":"secondary"}""", deployment.savedRecord(ALICE))
    }

    // ---- fixtures -----------------------------------------------------------

    /** A player save as a 26.2 server writes it, with the tags every save carries. */
    private fun save(
        dimension: String,
        x: Double = 100.5,
        y: Double = 64.0,
        z: Double = -200.25,
        yaw: Float = 0f,
        pitch: Float = 0f,
        extras: CompoundTag.() -> Unit = {},
    ) = CompoundTag().apply {
        putString("Dimension", dimension)
        put("Pos", vec3(x, y, z))
        put("Motion", vec3(0.0, -0.0784, 0.0))
        put(
            "Rotation",
            ListTag().apply {
                add(FloatTag.valueOf(yaw))
                add(FloatTag.valueOf(pitch))
            },
        )
        putInt("XpLevel", 30)
        putInt("DataVersion", 4536)
        extras()
    }

    private fun vec3(x: Double, y: Double, z: Double) = ListTag().apply {
        add(DoubleTag.valueOf(x))
        add(DoubleTag.valueOf(y))
        add(DoubleTag.valueOf(z))
    }

    private fun globalPos(dimension: String, x: Int, y: Int, z: Int) = CompoundTag().apply {
        putString("dimension", dimension)
        putIntArray("pos", intArrayOf(x, y, z))
    }

    private fun respawn(dimension: String, x: Int, y: Int, z: Int) =
        globalPos(dimension, x, y, z).apply {
            putFloat("yaw", 45f)
            putFloat("pitch", 0f)
            putBoolean("forced", false)
        }

    /** A compass bound to a lodestone in [dimension]. */
    private fun compass(dimension: String, x: Int, y: Int, z: Int) = CompoundTag().apply {
        putString("id", "minecraft:compass")
        put(
            "components",
            CompoundTag().apply {
                put(
                    "minecraft:lodestone_tracker",
                    CompoundTag().apply {
                        put("target", globalPos(dimension, x, y, z))
                        putBoolean("tracked", true)
                    },
                )
            },
        )
    }

    /** A container item of [id] holding [item] in its first slot. */
    private fun container(id: String, item: CompoundTag) = CompoundTag().apply {
        putString("id", id)
        put(
            "components",
            CompoundTag().apply {
                put(
                    "minecraft:container",
                    ListTag().apply {
                        add(
                            CompoundTag().apply {
                                putInt("slot", 0)
                                put("item", item)
                            },
                        )
                    },
                )
            },
        )
    }

    private fun itemIn(container: CompoundTag): CompoundTag =
        container.getCompoundOrEmpty("components")
            .getListOrEmpty("minecraft:container")
            .getCompoundOrEmpty(0)
            .getCompoundOrEmpty("item")

    private fun targetOf(compass: CompoundTag): List<Int> =
        compass.getCompoundOrEmpty("components")
            .getCompoundOrEmpty("minecraft:lodestone_tracker")
            .getCompoundOrEmpty("target")
            .getIntArray("pos")
            .orElseThrow()
            .toList()

    private fun positionIn(save: CompoundTag): List<Double> = save.getListOrEmpty("Pos").doubles()

    private fun ListTag.doubles(): List<Double> = (0 until size).map { getDoubleOr(it, Double.NaN) }

    private fun recordOf(uuid: UUID): String = withoutTheHour(deployment.savedRecord(uuid))

    /** [text] with the moment the merge ran replaced, so the rest of it can be asserted whole. */
    private fun withoutTheHour(text: String): String = WHEN_IT_RAN.replace(text, "\"<when>\"")
}
