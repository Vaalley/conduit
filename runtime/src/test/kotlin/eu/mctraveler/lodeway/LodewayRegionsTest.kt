package eu.mctraveler.lodeway

import app.lodeway.api.map.Area
import app.lodeway.api.map.Lodeway
import eu.mctraveler.region.Region
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tier for the Lodeway map layer: the geometry, the popup text and the
 * upsert/remove diff, against Lodeway's own in-process model (the plugin API
 * jar carries it, so no map, no server and no network are involved).
 *
 * The wiring — the classpath guard, the save-hook subscription — is not
 * testable here by construction: this test class only runs at all because the
 * API is on the test classpath, which is the condition the guard exists to
 * detect the absence of.
 */
class LodewayRegionsTest {
    companion object {
        // The popup names the dimension through `RegionWorlds`, whose vanilla
        // `Level` constants need the game bootstrapped even in the unit tier.
        @JvmStatic
        @BeforeAll
        fun bootstrapGame() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private val alice = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val bob = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private val names = mapOf(alice to "Alice", bob to "Bob")

    /** Lodeway's model is a process-wide static, so each test puts it back. */
    @AfterEach
    fun clearTheMap() {
        Lodeway.map().layers().forEach { it.remove() }
    }

    private fun publish(roots: List<Region>, previous: Set<String> = emptySet()): Set<String> =
        LodewayRegions.publishTo(
            Lodeway.map(),
            roots,
            worldName = { world -> if (world == "world") "minecraft:overworld" else world },
            memberName = { uuid -> names[uuid] },
            previous = previous,
        )

    private fun areas(): List<Area> = Lodeway.map().layer("mctraveler.regions").areas()

    private fun region(
        title: String = "Spawn Commons",
        world: String = "world",
        startX: Int = -20,
        startZ: Int = -20,
        endX: Int = 40,
        endZ: Int = 60,
    ) = Region(title, world, startX, startZ, endX, endZ)

    @Test
    fun `a region becomes one labelled area on its own layer`() {
        val home = region()
        home.members.add(alice)
        publish(listOf(home))

        val layer = Lodeway.map().layer("mctraveler.regions")
        assertEquals("Regions", layer.label())
        val area = areas().single()
        assertEquals("Spawn Commons", area.label())
        assertEquals("minecraft:overworld", area.world())
        // The far edge of the far block, not its corner: 61 blocks wide.
        assertEquals(listOf(-20.0, 41.0, 41.0, -20.0), area.xs().toList())
        assertEquals(listOf(-20.0, -20.0, 61.0, 61.0), area.zs().toList())
        assertTrue(area.detail().contains("Region in the overworld."))
        assertTrue(area.detail().contains("(61 x 81 blocks)"))
        assertTrue(area.detail().contains("Members: Alice"))
    }

    /** Full build height is every region's default and says nothing worth drawing. */
    @Test
    fun `only a region with real y bounds carries a height`() {
        publish(listOf(region()))
        assertFalse(areas().single().hasHeight())

        val floor = region(title = "Tower Floor 2")
        floor.startY = 96
        floor.endY = 64
        publish(listOf(floor), previous = setOf("0"))
        val area = areas().single()
        assertTrue(area.hasHeight())
        assertEquals(64.0, area.minY())
        assertEquals(97.0, area.maxY())
        assertTrue(area.detail().contains("Height: Y 64 to 96"))
    }

    @Test
    fun `sub-regions are drawn too, and named after their parent`() {
        val town = region(title = "Riverside")
        val plot = region(title = "Alice's Plot", startX = 0, startZ = 0, endX = 10, endZ = 10)
        town.subRegions.add(plot)
        plot.parent = town
        publish(listOf(town))

        assertEquals(listOf("Riverside", "Alice's Plot"), areas().map { it.label() })
        val parent = areas().first { it.label() == "Riverside" }
        val child = areas().first { it.label() == "Alice's Plot" }
        assertTrue(parent.detail().contains("Sub-regions: 1"))
        assertTrue(child.detail().contains("Inside: Riverside"))
    }

    /** An embassy is a region with a flag, and reads as one on the map. */
    @Test
    fun `an embassy is styled and described apart`() {
        val plain = region(title = "Plain")
        val embassy = region(title = "Embassy", startX = 100, startZ = 100, endX = 120, endZ = 120)
        embassy.flags.add(Region.EMBASSY_FLAG)
        embassy.flags.add("PUBLIC")
        publish(listOf(plain, embassy))

        val drawn = areas().associateBy { it.label() }
        assertTrue(drawn.getValue("Embassy").detail().startsWith("Embassy in the overworld."))
        // The embassy flag is the first line; the others are still worth listing.
        assertTrue(drawn.getValue("Embassy").detail().contains("Flags: PUBLIC"))
        assertTrue(drawn.getValue("Plain").stroke() != drawn.getValue("Embassy").stroke())
    }

    @Test
    fun `a renamed or resized region is updated in place`() {
        val home = region()
        val drawn = publish(listOf(home))

        home.title = "Spawn Market"
        home.endX = 100
        val again = publish(listOf(home), previous = drawn)

        assertEquals(drawn, again)
        val area = areas().single()
        assertEquals("Spawn Market", area.label())
        assertEquals(101.0, area.xs()[1])
    }

    @Test
    fun `a deleted region is taken off the map`() {
        val first = region(title = "First")
        val second = region(title = "Second", startX = 200, startZ = 200, endX = 210, endZ = 210)
        val drawn = publish(listOf(first, second))
        assertEquals(2, areas().size)

        val again = publish(listOf(first), previous = drawn)
        assertEquals(setOf("0"), again)
        assertEquals(listOf("First"), areas().map { it.label() })
    }

    /** A member whose name nobody knows is left out, as `/rg locate` leaves them out. */
    @Test
    fun `an unresolvable member is not shown`() {
        val home = region()
        home.members.add(alice)
        home.members.add(UUID.fromString("33333333-3333-3333-3333-333333333333"))
        publish(listOf(home))

        assertEquals("Members: Alice", areas().single().detail().lines().first { it.startsWith("Members") })
        assertNull(areas().single().detail().lines().firstOrNull { it.contains("3333") })
    }
}
