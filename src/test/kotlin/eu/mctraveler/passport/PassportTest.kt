package eu.mctraveler.passport

import com.google.gson.Gson
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.stats.Stats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PassportTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `distance buckets prioritize faster travel modes`() {
        assertEquals(Distance.Bucket.FLY, Distance.bucketFor(flying = true, riding = true, swimming = true))
        assertEquals(Distance.Bucket.RIDE, Distance.bucketFor(flying = false, riding = true, swimming = true))
        assertEquals(Distance.Bucket.SWIM, Distance.bucketFor(flying = false, riding = false, swimming = true))
        assertEquals(Distance.Bucket.WALK, Distance.bucketFor(flying = false, riding = false, swimming = false))
    }

    @Test
    fun `passport round trips through json store`() {
        val uuid = UUID.randomUUID()
        val store = PassportStore(dir)
        val passport = store.getOrCreate(uuid) { 1234L }
        passport.biomes["minecraft:plains"] = 2000L
        passport.distance.walk = 12.5
        passport.deaths = 2
        passport.blocksMined = 42
        store.markDirty(uuid)
        store.flushDirty()

        val loaded = PassportStore(dir).get(uuid)
        assertEquals(passport, loaded)
    }

    @Test
    fun `legacy passport json uses new field defaults`() {
        val passport = Gson().fromJson(
            """{"firstJoin":1234,"biomes":{},"dimensions":{},"regions":{},"distance":{},"deaths":2}""",
            Passport::class.java,
        )

        assertEquals(0, passport.crystalTrips)
        assertEquals(0, passport.postcards)
        assertEquals(0, passport.blocksMined)
        assertTrue(passport.stamps.isEmpty())
    }

    @Test
    fun `corrupt passport files are skipped`() {
        Files.writeString(dir.resolve("not-a-uuid.json"), "{}")
        Files.writeString(dir.resolve("${UUID.randomUUID()}.json"), "{")

        assertTrue(PassportStore(dir).all().isEmpty())
    }

    @Test
    fun `sub-region stamps itself and ancestors`() {
        val service = RegionService(dir.resolve("regions.json"))
        val root = Region("Root", "world", 0, 0, 20, 20)
        val sibling = Region("Sibling", "world", 30, 30, 40, 40)
        val child = Region("Child", "world", 2, 2, 4, 4)
        service.add(root, null)
        service.add(sibling, root)
        service.add(child, root)
        val passport = Passport(0L)

        val childId = service.stableIdOf(child)
        val rootId = service.stableIdOf(root)
        PassportFeature.stampRegions(passport, child, service, 99L)

        assertEquals(linkedMapOf(childId to 99L, rootId to 99L), passport.regions)
    }

    @Test
    fun `orphaned regions are skipped from summaries`() {
        val service = RegionService(dir.resolve("regions.json"))
        val region = Region("Gone", "world", 0, 0, 2, 2)
        service.add(region, null)
        val regionId = service.stableIdOf(region)
        val passport = Passport(0L, regions = linkedMapOf(regionId to 1L, "orphan" to 2L))
        service.remove(region)

        val summary = PassportJson.summary(
            UUID.randomUUID(),
            "Traveler",
            passport,
            service,
            { "owner" },
        )

        assertEquals(listOf<String>(), summary.regions.map { it.id })
        assertTrue(summary.biomes.isEmpty())
    }

    @Test
    fun `stable region ids survive reload`() {
        val file = dir.resolve("stable-regions.json")
        val service = RegionService(file)
        val region = Region("Stable", "world", 0, 0, 2, 2)
        service.add(region, null)

        val id = service.stableIdOf(region)
        val reloaded = RegionService(file)

        assertEquals(region.title, reloaded.byStableId(id)?.title)
        assertEquals(id, reloaded.stableIdOf(reloaded.roots.single()))
    }

    @Test
    fun `regions without metadata remain byte identical when saved`() {
        val file = dir.resolve("empty-metadata.json")
        val service = RegionService(file)
        service.add(Region("Plain", "world", 0, 0, 2, 2), null)
        val before = Files.readString(file)

        service.save()

        assertEquals(before, Files.readString(file))
    }

    @Test
    fun `distance formatting uses meters below one kilometer`() {
        assertEquals("999 m", PassportFormatting.formatDistance(999.0))
        assertEquals("12.4 km", PassportFormatting.formatDistance(12400.0))
    }

    @Test
    fun `stamps unlock thresholds and are idempotent`() {
        val passport = Passport(0L)
        passport.distance.walk = 999.0
        val before = StampContext(passport, { 0 }, 0, 1000L)
        assertTrue(Stamps.evaluate(before).isEmpty())

        passport.distance.walk = 1000.0
        val unlocked = Stamps.evaluate(StampContext(passport, { 0 }, 0, 1000L))
        assertEquals(listOf("first_steps"), unlocked.map { it.id })
        assertTrue(Stamps.evaluate(StampContext(passport, { 0 }, 0, 1001L)).isEmpty())
    }

    @Test
    fun `stamps unlock three worlds and veteran`() {
        val passport = Passport(0L)
        passport.dimensions.putAll(
            mapOf(
                "minecraft:overworld" to 1L,
                "minecraft:the_nether" to 2L,
                "minecraft:the_end" to 3L,
            ),
        )
        val context = StampContext(
            passport,
            embassies = { 0 },
            overworldBiomeTotal = 0,
            now = 365L * 24 * 60 * 60 * 1000,
        )

        assertEquals(
            setOf("three_worlds", "veteran"),
            Stamps.evaluate(context).map { it.id }.toSet(),
        )
    }

    @Test
    fun `stamps unlock distance buckets, counters and stats`() {
        val passport = Passport(0L)
        passport.distance.walk = 43_000.0
        passport.distance.ride = 26_000.0
        passport.distance.swim = 11_000.0
        passport.deaths = 1
        passport.rtpUses = 25
        passport.blocksMined = 10_000
        val context = StampContext(
            passport,
            embassies = { 0 },
            overworldBiomeTotal = 0,
            now = 1000L,
            regions = { 100 },
        ) { id -> if (id == Stats.JUMP) 10_000 else 0 }

        val unlocked = Stamps.evaluate(context).map { it.id }.toSet()
        assertEquals(
            setOf(
                "first_steps", "wanderer", "marathon", "pony_express", "channel_swimmer",
                "sightseer", "trespasser", "wormhole", "roulette_regular", "digger", "excavator",
                "first_blood", "leg_day",
            ),
            unlocked,
        )
    }

    @Test
    fun `event stamps are granted once and never evaluate`() {
        val passport = Passport(0L)
        assertEquals("storm_chaser", Stamps.grant(passport, "storm_chaser", 7L)?.id)
        assertNull(Stamps.grant(passport, "storm_chaser", 8L))
        assertNull(Stamps.grant(passport, "unknown_stamp", 9L))
        assertEquals(7L, passport.stamps["storm_chaser"])

        val context = StampContext(passport, { 0 }, 0, 1000L, regions = { 1000 }) { 10_000_000 }
        assertTrue(Stamps.evaluate(context).none { it.id in setOf("ashes_to_ashes", "into_the_void", "terminal_velocity", "storm_chaser") })
    }

    @Test
    fun `passport events filter and cap`() {
        PassportEvents.clear()
        val now = System.currentTimeMillis()
        repeat(101) { index ->
            PassportEvents.record(
                StampEvent(
                    at = now + index,
                    player = "Player",
                    stamp = StampRef("id$index", "Title", "Description", "🏅"),
                ),
            )
        }

        val events = PassportEvents.poll(0)
        assertEquals(100, events.size)
        assertEquals(now + 1, (events.first() as StampEvent).at)
        assertTrue(PassportEvents.poll(now + 100).isEmpty())
        PassportEvents.clear()
    }

    @Test
    fun `passport events serialize their runtime fields`() {
        val gson = Gson()
        val stampJson = gson.toJson(
            StampEvent(
                at = 1L,
                player = "Player",
                stamp = StampRef("first_steps", "First Steps", "Travelled 1 km", "👣"),
            ),
        )
        val postcardJson = gson.toJson(
            PostcardEvent(
                at = 1L,
                player = "Player",
                dimension = "minecraft:overworld",
                biome = "minecraft:plains",
                region = null,
                x = 0,
                y = 0,
                z = 0,
                dayTime = 0,
                raining = false,
                thundering = false,
                caption = null,
            ),
        )

        assertTrue(stampJson.contains(""""type":"stamp""""))
        assertTrue(stampJson.contains(""""title":"First Steps""""))
        assertTrue(postcardJson.contains(""""type":"postcard""""))
        assertTrue(postcardJson.contains(""""biome":"minecraft:plains""""))
    }
}
