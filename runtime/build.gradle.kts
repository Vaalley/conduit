import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// The hot-reloadable half of the mod (docs/hot-reload.md): every feature lives here.
// This jar is NOT a Fabric mod — the :bootstrap RuntimeLoader side-loads it from the
// watched directory (production) or the classpath (dev runs) and swaps it on change.

plugins {
    id("net.fabricmc.fabric-loom")
    id("org.jetbrains.kotlin.jvm")
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String
val flkVersion = property("flk_version") as String

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName = "mctraveler-runtime"
}

repositories {
    mavenCentral()
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$flkVersion")

    // The bridge API and the tab-list packet accessor the features implement/use.
    // (Plain project dependency: loom 1.17 resolves sibling loom projects
    // through standard variants; the old namedElements configuration is gone.)
    implementation(project(":bootstrap"))

    // Lodeway's map plugin API — the optional web-map integration compiles
    // against it and never ships it (docs/lodeway-map.md, libs/README.md). A
    // vendored jar rather than a coordinate because the API is pre-release.
    val lodewayApi = files("../libs/lodeway-pluginapi-0.1.0.jar")
    compileOnly(lodewayApi)
    testImplementation(lodewayApi)

    testImplementation("net.fabricmc:fabric-loader-junit:$loaderVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

apply(from = "../gradle/migrate.gradle.kts") // the one-time Portal migration tool (docs/migration.md)
apply(from = "../gradle/import-nucleus.gradle.kts") // the Nucleus embassy import (docs/nucleus-import.md)
apply(from = "../gradle/merge-worlds.gradle.kts") // the one-time merge of Secondary into Primary
