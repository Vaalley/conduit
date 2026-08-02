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
// decade and tracks the current Minecraft version, and its per-version relocation
// chain is what copes with Secondary's chunks being a mixture of DataVersions
// (merge spec, "Relocation").
//
// It is a tool we RUN, not a library we LINK. Its own tree — JavaFX, Groovy,
// log4j, LevelDB — has no business on the mod's compile classpath, so it is never
// a dependency of anything here and is reached only as a subprocess.
//
// **This is not the released 2.8.** The released one is unusable for this merge,
// in ways tickets 16 and 17 found and fixed at source rather than routed around:
//
//   - `--mode select` races. `Selection.merge` mutates a non-thread-safe fastutil
//     map from every per-region-file job at once, so about one run in twenty
//     silently returned an entire region file's worth of chunks fewer than it
//     matched, and exited 0.
//   - the relocation is incomplete for 26.2. A leash, an item frame's and a
//     painting's tile position and every villager's memories arrived in Primary
//     still naming Secondary — the first three because 1.21.5's
//     `InlineBlockPosFormatFix` renames were never followed, the last because a
//     static field the entity relocation dereferences for *every* entity was left
//     null, so each one was abandoned partway through.
//   - and the same renames were unfollowed far more widely than those three.
//     Upstream moves an entity's positions from a hand-written switch over entity
//     ids that still speaks only the pre-1.21.5 spellings, so a bee's hive, a
//     phantom's anchor, a vex's bound origin, a mob's home, anything asleep in a
//     bed, an end crystal's beam target, a wandering trader's target and every
//     patrolling raider's arrived naming Secondary too — as did a glow item frame's
//     and a leash knot's tile, which upstream never listed in either spelling.
//     Ticket 17 enumerated these from 26.2's own entity classes and keyed them by
//     name instead, so the list no longer has to be crossed with the entity ids
//     that carry them.
//
// The fixes are gradle/mcaselector/2.8-mctraveler1.patch, kept in this repo so
// they are reviewable in our own history and so a lost jar costs a clone, an
// apply and a build rather than a reconstruction. They are additive throughout —
// every old spelling still relocates exactly as it did, because a chunk nobody has
// visited since before the Portal cutover still carries it.
val mcaSelectorVersion = "2.8-mctraveler1"

// sha256 of the jar that patch builds. Upstream's shadowJar is made reproducible
// by the same patch, so this is a property of the source rather than of the moment
// it was built: anyone who applies the patch to the 2.8 tag and builds gets these
// bytes. Re-check with
//   shasum -a 256 <jar>
// whenever the patch changes, and never take the new value from a jar that just
// failed this check.
val mcaSelectorSha256 = "f7d088d34019803ccf978a4e978176b0ddbc95d5d96d2e6cfd85997b54b041b1"

// Where the built jar is expected to live. Outside the repo, because it is 18 MB
// of somebody else's build output; durable, because rebuilding it is a minute of
// an operator's downtime window that they should not have to spend. Override with
//   ./gradlew mergeWorlds -PmcaSelectorJar=/somewhere/else/mcaselector.jar
val mcaSelectorSource = file(
    (findProperty("mcaSelectorJar") as String?)
        ?: "${System.getProperty("user.home")}/.mctraveler/tools/mcaselector-$mcaSelectorVersion.jar",
)

/**
 * The verified tool, at a path that does not move when the version does.
 *
 * The checksum is proved here, once, rather than at every call site, and the
 * copy is what everything downstream runs — so a jar that fails the check is
 * never the jar a merge executes.
 */
val mcaSelectorJar = layout.buildDirectory.file("tools/mcaselector-$mcaSelectorVersion.jar")

val provideMcaSelector = tasks.register("provideMcaSelector") {
    group = "migration"
    description = "Verifies the patched MCA Selector against its checksum and stages it for the merge."
    val source = mcaSelectorSource
    val expected = mcaSelectorSha256
    val version = mcaSelectorVersion
    val destination = mcaSelectorJar
    val patch = layout.projectDirectory.file("gradle/mcaselector/2.8-mctraveler1.patch").asFile
    inputs.files(provider { if (source.isFile) files(source) else files() }).withPropertyName("mcaSelector")
    inputs.property("sha256", expected)
    inputs.property("source", source.path)
    outputs.file(destination)
    doLast {
        if (!source.isFile) {
            throw GradleException(
                "MCA Selector $version is not at $source, and the merge will not run without it.\n" +
                    "\n" +
                    "This is a patched build, not a download: the released 2.8 loses chunks to a race\n" +
                    "and leaves most of what 26.2 records inside an entity behind when it relocates.\n" +
                    "Build it with\n" +
                    "\n" +
                    "  git clone --branch 2.8 https://github.com/Querz/mcaselector.git \\\n" +
                    "      ~/.mctraveler/src/mcaselector\n" +
                    "  git -C ~/.mctraveler/src/mcaselector apply $patch\n" +
                    "  ~/.mctraveler/src/mcaselector/gradlew -p ~/.mctraveler/src/mcaselector shadowJar\n" +
                    "  mkdir -p ${source.parent}\n" +
                    "  cp ~/.mctraveler/src/mcaselector/build/libs/mcaselector-2.8-all.jar $source\n" +
                    "\n" +
                    "It needs a JDK 21 and it will download JavaFX, which its build needs even though\n" +
                    "the merge only ever runs it headless. The build is reproducible, so the jar you\n" +
                    "get will match the checksum this build expects.\n" +
                    "\n" +
                    "If you already have it somewhere else, pass -PmcaSelectorJar=<path> instead.",
            )
        }
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
                    "you know why the jar changed — rebuilding it from $patch against the 2.8 tag " +
                    "reproduces the expected bytes exactly.",
            )
        }
        val file = destination.get().asFile
        file.parentFile.mkdirs()
        source.copyTo(file, overwrite = true)
    }
}

// ---- the command and its tests ---------------------------------------------

// The command and *both* test tiers run the same verified jar, which is what makes
// the merge tests evidence: no test stubs the relocation (merge spec, "Testing
// Decisions"). The gametest tier is here for the same reason as the unit one — it
// runs a real merge and then boots this server on what came out of it (ticket 11),
// so a stand-in there would prove the game reads a fixture rather than the merge's
// own output.
val mcaSelectorProperty = "mctraveler.mcaSelectorJar"

/** How many times McaSelectorSelectionTest asks for the same selection. */
val selectionRunsProperty = "mctraveler.selectionRuns"

tasks.register<JavaExec>("mergeWorlds") {
    group = "migration"
    description = "Moves Secondary's landmass into Primary in a stopped server's run directory, " +
        "sweeping everything that named a place in it."
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
    // McaSelectorSelectionTest proves the selection deterministic by repeating it, at
    // a count small enough for every build. Forwarded so that the longer look it
    // documents — ./gradlew test -Dmctraveler.selectionRuns=400 — is a thing an
    // operator can actually ask for rather than an instruction that quietly does
    // nothing, since a Gradle -D reaches the daemon and not the test JVM.
    providers.systemProperty(selectionRunsProperty).orNull?.let {
        systemProperty(selectionRunsProperty, it)
    }
}

// WorldMergeGameTest runs a whole merge inside the booted server and then reads its
// output back through the live code, so the gametest JVM needs the tool exactly as
// the unit tier and the command do. Without this the gametest would have to stand in
// for the relocation, and a merge gametest driving a stub is a gametest about a stub.
tasks.named<JavaExec>("runGameTest") {
    inputs.files(provideMcaSelector)
    systemProperty(mcaSelectorProperty, mcaSelectorJar.get().asFile.absolutePath)
}

// A diagnostic, not part of the merge: prints what one chunk says about itself,
// read through the same RegionFile the merge reads it through. Added when a
// rehearsal found MCA Selector and SampledDiff disagreeing about whether a chunk
// was finished, which is not a thing that can be settled by reading either one's
// source.
//   ./gradlew chunkProbe --args="<region folder> <chunkX> <chunkZ>"
tasks.register<JavaExec>("chunkProbe") {
    group = "migration"
    description = "Prints one chunk's DataVersion, status and root keys, as the merge reads them."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "eu.mctraveler.importer.ChunkProbeMain"
}
