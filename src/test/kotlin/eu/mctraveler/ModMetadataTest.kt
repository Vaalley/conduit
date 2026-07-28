package eu.mctraveler

import com.google.gson.JsonParser
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tier (fabric-loader-junit). Pins the scaffold's platform contract: the shipped
 * mod metadata declares the mctraveler id and loads on the server environment only,
 * so vanilla clients connect with nothing installed.
 */
class ModMetadataTest {
    @Test
    fun `mod metadata declares mctraveler as server-environment-only`() {
        val path = checkNotNull(System.getProperty("mctraveler.fabricModJson")) {
            "mctraveler.fabricModJson system property not set by the build"
        }
        val file = File(path)
        check(file.isFile) { "fabric.mod.json is missing from the mod's resources: $path" }
        val json = file.reader().use { JsonParser.parseReader(it).asJsonObject }

        assertEquals("mctraveler", json["id"].asString)
        assertEquals("server", json["environment"].asString)
    }
}
