package eu.mctraveler.perf

import eu.mctraveler.MinecraftTestBootstrap
import eu.mctraveler.passport.Passport
import eu.mctraveler.passport.StampContext
import eu.mctraveler.passport.Stamps
import eu.mctraveler.persistence.JsonPlayerStore
import eu.mctraveler.region.Region
import eu.mctraveler.region.RegionService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Before/after microbenchmarks for the perf pass, run as an ordinary JUnit
 * suite. Each case times the *previous* implementation — kept here verbatim as
 * a private reference — against the shipped one, so the numbers measure this
 * change rather than two different codebases. Results append to
 * `build/reports/perf-benchmarks.txt`; every timed loop consumes its results
 * into [sink] so the JIT cannot erase the work.
 *
 * Nothing asserts on wall time — CI machines vary — but the correctness
 * assertions are real and the timings are printed for the record.
 */
class PerfBenchmarkTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = MinecraftTestBootstrap.ensure()

        /** The consumer every timed loop feeds, so its work survives JIT. */
        @Volatile
        var sink: Long = 0

        private val report: Path by lazy {
            Path.of("build", "reports", "perf-benchmarks.txt").also { Files.createDirectories(it.parent) }
        }

        @Synchronized
        fun report(line: String) {
            Files.writeString(
                report, line + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
            println(line)
        }
    }

    @TempDir
    lateinit var dir: Path

    // ---- the reference implementations, verbatim from before this change ----

    /** The old [RegionService.regionAt]: a linear scan of every root region. */
    private fun oldRegionAt(roots: List<Region>, world: String, x: Int, y: Int, z: Int): Region? {
        var found: Region = roots.firstOrNull { it.world == world && it.contains(x, y, z) } ?: return null
        while (true) {
            found = found.subRegions.firstOrNull { it.contains(x, y, z) } ?: return found
        }
    }

    /** The old [RegionService.byStableId]: a depth-first walk of the whole tree. */
    private fun oldByStableId(roots: List<Region>, id: String): Region? {
        fun find(regions: List<Region>): Region? {
            for (region in regions) {
                if (region.metadata["passport-id"]
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString == id
                ) {
                    return region
                }
                find(region.subRegions)?.let { return it }
            }
            return null
        }
        return find(roots)
    }

    /** The old [JsonPlayerStore.read]: two stats per call, cached record or not. */
    private fun oldStoreRead(playersDir: Path, uuid: UUID): Boolean {
        val file = playersDir.resolve("$uuid.json")
        if (Files.notExists(file)) return false
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
        return attrs.size() > 0
    }

    // ---- fixtures ----

    private fun makeService(regionCount: Int, subCount: Int): RegionService {
        val service = RegionService(dir.resolve("bench-regions-${UUID.randomUUID()}.json"))
        val rng = Random(42)
        val worlds = listOf("world", "world_nether", "world_the_end")
        for (i in 0 until regionCount) {
            val x = rng.nextInt(-50_000, 50_000)
            val z = rng.nextInt(-50_000, 50_000)
            val region = Region("R$i", worlds[i % worlds.size], x, z, x + rng.nextInt(8, 200), z + rng.nextInt(8, 200))
            service.roots.add(region)
            repeat(subCount) { s ->
                val sub = Region("R$i-$s", region.world, x + 1, z + 1, x + 4, z + 4)
                sub.parent = region
                region.subRegions.add(sub)
            }
        }
        return service
    }

    @Test
    fun `regionAt indexed answers identically to the linear scan`() {
        val service = makeService(regionCount = 300, subCount = 3)
        val rng = Random(7)
        val worlds = listOf("world", "world_nether", "world_the_end")
        repeat(20_000) {
            val w = worlds.random(rng)
            val x = rng.nextInt(-60_000, 60_000)
            val z = rng.nextInt(-60_000, 60_000)
            val y = rng.nextInt(-64, 320)
            assertEquals(
                oldRegionAt(service.roots, w, x, y, z)?.title,
                service.regionAt(w, x, y, z)?.title,
                "divergence at $w $x $y $z",
            )
        }
    }

    @Test
    fun `direct roots and subRegions mutation stays visible to regionAt`() {
        val service = makeService(regionCount = 10, subCount = 0)
        service.regionAt("world", 0, 64, 0) // force index build
        val late = Region("Late", "world", -5, -5, 5, 5)
        service.roots.add(late) // the gametest seam: no add(), no save()
        assertEquals("Late", service.regionAt("world", 0, 64, 0)?.title)

        // A sub-region added in place on a live region must also be seen.
        val sub = Region("LateSub", "world", 0, 0, 2, 2)
        sub.parent = late
        late.subRegions.add(sub)
        assertEquals("LateSub", service.regionAt("world", 1, 64, 1)?.title)
    }

    @Test
    fun `bench regionAt index vs linear scan`() {
        val service = makeService(regionCount = 300, subCount = 3)
        val rng = Random(11)
        val worlds = listOf("world", "world_nether", "world_the_end")
        val queries = (0 until 40_000).map {
            intArrayOf(worlds.indexOf(worlds.random(rng)), rng.nextInt(-60_000, 60_000), rng.nextInt(-64, 320), rng.nextInt(-60_000, 60_000))
        }
        val roots = service.roots.toList()
        service.regionAt("world", 0, 64, 0) // build the index outside the timed loop

        // Warm both paths.
        var acc = 0L
        repeat(2) {
            for (q in queries) { if (oldRegionAt(roots, worlds[q[0]], q[1], q[2], q[3]) != null) acc++ }
            for (q in queries) { if (service.regionAt(worlds[q[0]], q[1], q[2], q[3]) != null) acc++ }
        }

        val oldNs = measureNanoTime {
            for (q in queries) { if (oldRegionAt(roots, worlds[q[0]], q[1], q[2], q[3]) != null) acc++ }
        }
        val newNs = measureNanoTime {
            for (q in queries) { if (service.regionAt(worlds[q[0]], q[1], q[2], q[3]) != null) acc++ }
        }
        sink += acc
        report("regionAt x${queries.size} (300 roots, 3 subs each, ~1200 regions): linear=${oldNs / 1e6}ms indexed=${newNs / 1e6}ms speedup=${"%.1f".format(oldNs.toDouble() / newNs)}x")
    }

    @Test
    fun `bench byStableId index vs tree walk`() {
        val service = makeService(regionCount = 300, subCount = 3)
        val ids = service.roots.take(150).map { service.stableIdOf(it) } + listOf("missing")
        val roots = service.roots.toList()

        var acc = 0L
        val oldNs = measureNanoTime {
            repeat(500) { for (id in ids) { if (oldByStableId(roots, id) != null) acc++ } }
        }
        val newNs = measureNanoTime {
            repeat(500) { for (id in ids) { if (service.byStableId(id) != null) acc++ } }
        }
        sink += acc
        report("byStableId x${ids.size * 500} (1200 regions): walk=${oldNs / 1e6}ms indexed=${newNs / 1e6}ms speedup=${"%.1f".format(oldNs.toDouble() / newNs)}x")
    }

    @Test
    fun `bench unlockStamps for a mid-game passport`() {
        val service = makeService(regionCount = 200, subCount = 0)
        // A player who has visited many regions and already earned the
        // region/embassy stamps — the state every busy-server passport reaches.
        val passport = Passport(0L)
        service.roots.take(150).forEach { passport.regions[service.stableIdOf(it)] = 1L }
        repeat(50) { passport.regions["orphan-$it"] = 1L }
        passport.stamps["sightseer"] = 1L
        passport.stamps["trespasser"] = 1L
        passport.stamps["diplomat"] = 1L
        passport.stamps["ambassador"] = 1L

        var acc = 0L
        // Old: embassies + regions counted eagerly via two DFS walks per id,
        // every call — even though the stamps that read them are unlocked.
        val oldNs = measureNanoTime {
            repeat(500) {
                val embassies = passport.regions.keys.count {
                    oldByStableId(service.roots, it)?.let { r -> Region.EMBASSY_FLAG in r.flags } == true
                }
                val regions = passport.regions.keys.count { oldByStableId(service.roots, it) != null }
                acc += embassies + regions
                acc += Stamps.ALL.count { it.unlocked(
                    StampContext(passport, { embassies }, 0, 1L, { regions }) { 0 }) &&
                    passport.stamps.putIfAbsent(it.id, 1L) == null }
            }
        }
        // New: evaluatable check, lazy suppliers, unlocked stamps skipped.
        val newNs = measureNanoTime {
            repeat(500) {
                if (!Stamps.allEvaluatableUnlocked(passport)) {
                    acc += Stamps.evaluate(
                        StampContext(
                            passport = passport,
                            embassies = {
                                passport.regions.keys.count {
                                    service.byStableId(it)?.let { r -> Region.EMBASSY_FLAG in r.flags } == true
                                }
                            },
                            overworldBiomeTotal = 0,
                            now = 1L,
                            regions = { passport.regions.keys.count { service.byStableId(it) != null } },
                        ) { 0 },
                    ).size
                }
            }
        }
        sink += acc
        report("unlockStamps x500 (200 visited ids, region stamps done): old=${oldNs / 1e6}ms new=${newNs / 1e6}ms speedup=${"%.1f".format(oldNs.toDouble() / newNs)}x")
    }

    @Test
    fun `bench player record read cached vs stat-per-read`() {
        val players = dir.resolve("players-${UUID.randomUUID()}")
        Files.createDirectories(players)
        val uuid = UUID.randomUUID()
        val store = JsonPlayerStore(players)
        store.setRank(uuid, "donator")

        var acc = 0L
        val oldNs = measureNanoTime {
            repeat(20_000) { if (oldStoreRead(players, uuid)) acc++ }
        }
        val newNs = measureNanoTime {
            repeat(20_000) { if (store.rank(uuid) == "donator") acc++ }
        }
        sink += acc
        report("player-store read x20000: stat-per-read=${oldNs / 1e6}ms cached=${newNs / 1e6}ms speedup=${"%.1f".format(oldNs.toDouble() / newNs)}x")
    }
}
