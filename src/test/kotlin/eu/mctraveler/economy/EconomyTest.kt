package eu.mctraveler.economy

import eu.mctraveler.persistence.JsonPlayerStore
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EconomyTest {
    @TempDir
    lateinit var dir: Path

    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    @Test
    fun `mutations write balances and ledger entries`() {
        val store = JsonPlayerStore(dir.resolve("players"))
        val ledger = Ledger(dir.resolve("ledger"))
        val economy = Economy(store, ledger) { 1234L }

        economy.deposit(uuid, 40, "join-bonus")
        assertTrue(economy.withdraw(uuid, 10, "pay:other"))
        economy.set(uuid, 50, "admin:$uuid")

        assertEquals(50L, economy.balanceOf(uuid))
        assertEquals(
            listOf(
                LedgerEntry(1234L, uuid, 40, 40, "join-bonus"),
                LedgerEntry(1234L, uuid, -10, 30, "pay:other"),
                LedgerEntry(1234L, uuid, 20, 50, "admin:$uuid"),
            ),
            ledger.read(uuid),
        )
    }

    @Test
    fun `insufficient withdrawal writes nothing`() {
        val store = JsonPlayerStore(dir.resolve("players"))
        val ledger = Ledger(dir.resolve("ledger"))
        val economy = Economy(store, ledger)
        economy.deposit(uuid, 30, "join-bonus")
        val before = java.nio.file.Files.readString(dir.resolve("players").resolve("$uuid.json"))

        assertFalse(economy.withdraw(uuid, 31, "pay:other"))
        assertEquals(before, java.nio.file.Files.readString(dir.resolve("players").resolve("$uuid.json")))
        assertEquals(1, ledger.read(uuid).size)
    }

    @Test
    fun `top sorts and refreshes after mutations`() {
        val store = JsonPlayerStore(dir.resolve("players"))
        val ledger = Ledger(dir.resolve("ledger"))
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        store.setBalance(first, 10)
        store.setBalance(second, 20)
        val economy = Economy(store, ledger)

        assertEquals(listOf(second to 20L, first to 10L), economy.top())
        economy.deposit(first, 20, "join-bonus")
        assertEquals(listOf(first to 30L, second to 20L), economy.top())
    }
}
