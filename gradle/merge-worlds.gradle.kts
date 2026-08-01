import java.security.MessageDigest

// The one-time merge of Secondary into Primary (merge spec User Stories 1–7).
//
// The third sibling of gradle/migrate.gradle.kts and gradle/import-nucleus.gradle.kts,
// kept the same way so the mod's build file gains a single line:
//   ./gradlew mergeWorlds --args="--target <server run dir>"

val sourceSets = the<SourceSetContainer>()

// ---- MCA Selector, the relocation tool -------------------------------------
//
// The merge does not relocate chunks itself: MCA Selector has done that job for a
// decade and tracks the current Minecraft version, and 2.8 is the release whose
// notes state "Updated mappings for Minecraft 26.2" (merge spec, "Relocation").
//
// It is a tool we RUN, not a library we LINK. Its own tree — JavaFX, Groovy,
// log4j, LevelDB — has no business on the mod's compile classpath, so it is
// resolved into a configuration of its own that nothing else extends, and reached
// only as a subprocess. `mcaSelector` is deliberately absent from every
// `implementation`/`testImplementation` chain in build.gradle.kts.
//
// Upstream publishes two artifacts per release. The JitPack coordinate
// (`com.github.Querz:mcaselector:2.8`) resolves, but it is the *library* jar and
// drags fifteen runtime dependencies behind it, JavaFX among them, with
// platform-specific natives we would be resolving for a process we only ever
// exec. The GitHub release jar is the self-contained one upstream ships for
// running, so that is what we pin — as a real resolved artifact through an Ivy
// repository laid out over the releases URL, which buys Gradle's own caching and
// makes the version a single coordinate rather than a hand-rolled download.
val mcaSelectorVersion = "2.8"

// sha256 of mcaselector-2.8.jar as published on 2026-06-15. The release URL is
// mutable in principle — a tag can be re-cut — and this is a tool that rewrites
// every chunk of the map, so it is verified rather than trusted. Re-check with
//   shasum -a 256 <jar>
// whenever mcaSelectorVersion moves, and never take the new value from the
// download that just failed this check.
val mcaSelectorSha256 = "64505f39edf9c9b5d47e666981f81e3c3a889d4f122b3065af7e269f48e53423"

val mcaSelector = configurations.create("mcaSelector") {
    isCanBeConsumed = false
    isCanBeResolved = true
    // The fat jar carries everything it needs; anything else the POM-less
    // coordinate might imply would be noise.
    isTransitive = false
}

repositories {
    // Scoped with exclusiveContent so no other dependency in the build can ever be
    // served from GitHub releases by accident.
    exclusiveContent {
        forRepository {
            ivy {
                name = "MCA Selector releases"
                setUrl("https://github.com/Querz/mcaselector/releases/download")
                patternLayout { artifact("[revision]/[module]-[revision].[ext]") }
                // Releases carry no POM; the artifact is the whole of the metadata.
                metadataSources { artifact() }
            }
        }
        filter { includeModule("net.querz", "mcaselector") }
    }
}

dependencies {
    mcaSelector("net.querz:mcaselector:$mcaSelectorVersion@jar")
}

/**
 * The verified tool, at a path that does not move when the version does.
 *
 * The checksum is proved here, once, rather than at every call site, and the
 * copy is what everything downstream runs — so a jar that fails the check is
 * never the jar a merge executes. An operator never fetches anything by hand
 * (ticket 02); `./gradlew mergeWorlds` resolves it like any other dependency.
 */
val mcaSelectorJar = layout.buildDirectory.file("tools/mcaselector-$mcaSelectorVersion.jar")

val provideMcaSelector = tasks.register("provideMcaSelector") {
    group = "migration"
    description = "Resolves MCA Selector at its pinned version and verifies it against its checksum."
    val resolved: FileCollection = mcaSelector
    val expected = mcaSelectorSha256
    val version = mcaSelectorVersion
    val destination = mcaSelectorJar
    inputs.files(resolved).withPropertyName("mcaSelector")
    inputs.property("sha256", expected)
    outputs.file(destination)
    doLast {
        val source = resolved.singleFile
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException(
                "MCA Selector $version does not match its pinned checksum.\n" +
                    "  expected sha256 $expected\n" +
                    "  actual   sha256 $actual\n" +
                    "  at $source\n" +
                    "The merge rewrites every chunk of the map with this tool. Do not run it until " +
                    "you know why the artifact changed.",
            )
        }
        val file = destination.get().asFile
        file.parentFile.mkdirs()
        source.copyTo(file, overwrite = true)
    }
}

// ---- the command and its tests ---------------------------------------------

// Both the command and the tests run the *same* verified jar, which is what makes
// the merge tests evidence: no test stubs the relocation (merge spec, "Testing
// Decisions").
val mcaSelectorProperty = "mctraveler.mcaSelectorJar"

tasks.register<JavaExec>("mergeWorlds") {
    group = "migration"
    description = "Merges Secondary's landmass into Primary in a stopped server's run directory."
    // The mod's own runtime classpath: the merge reuses the live region service,
    // player store and World layout rather than reimplementing any of them.
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "eu.mctraveler.importer.WorldMergeMain"
    inputs.files(provideMcaSelector)
    systemProperty(mcaSelectorProperty, mcaSelectorJar.get().asFile.absolutePath)
}

tasks.named<Test>("test") {
    inputs.files(provideMcaSelector)
    systemProperty(mcaSelectorProperty, mcaSelectorJar.get().asFile.absolutePath)
}
