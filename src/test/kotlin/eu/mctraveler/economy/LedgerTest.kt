package eu.mctraveler.economy

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LedgerTest {
    @TempDir
    lateinit var dir: Path

    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `append and read round trip while skipping malformed lines`() {
        val ledger = Ledger(dir)
        val entry = LedgerEntry(1L, first, 10L, 10L, "join-bonus")
        ledger.append(entry)
        Files.writeString(dir.resolve("$first.jsonl"), "{bad}\n", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(listOf(entry), ledger.read(first))
    }

    @Test
    fun `all spans player files`() {
        val ledger = Ledger(dir)
        ledger.append(LedgerEntry(1L, first, 10L, 10L, "join-bonus"))
        ledger.append(LedgerEntry(2L, second, 20L, 20L, "join-bonus"))
        assertEquals(2, ledger.all().count())
    }
}
