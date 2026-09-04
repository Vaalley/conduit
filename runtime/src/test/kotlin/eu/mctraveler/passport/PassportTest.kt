package eu.mctraveler.passport

import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
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
        val passport = store.getOrCreate(uuid, 1234L)
        passport.biomes["minecraft:plains"] = 2000L
        passport.distance.walk = 12.5
        passport.deaths = 2
        store.markDirty(uuid)
        store.flushDirty()

        val loaded = PassportStore(dir).get(uuid)
        assertEquals(passport, loaded)
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
}
