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
    fun `formats cents with two decimal places`() {
        assertEquals("$100.00", Economy.format(10_000))
        assertEquals("$0.05", Economy.format(5))
        assertEquals("$1.50", Economy.format(150))
        assertEquals("-$1.50", Economy.format(-150))
    }

    @Test
    fun `parses decimal dollar amounts as cents`() {
        assertEquals(5L, Economy.parseAmount("0.05"))
        assertEquals(10_000L, Economy.parseAmount("100"))
        assertEquals(101L, Economy.parseAmount("1.005"))
        assertEquals(null, Economy.parseAmount("abc"))
    }

    @Test
    fun `mutations write balances and ledger entries`() {
        val store = JsonPlayerStore(dir.resolve("players"))
        val ledger = Ledger(dir.resolve("ledger"))
        val economy = Economy(store, ledger) { 1234L }

        economy.deposit(uuid, Economy.dollars(40), "join-bonus")
        assertTrue(economy.withdraw(uuid, Economy.dollars(10), "pay:other"))
        economy.set(uuid, Economy.dollars(50), "admin:$uuid")

        assertEquals(Economy.dollars(50), economy.balanceOf(uuid))
        assertEquals(
            listOf(
                LedgerEntry(1234L, uuid, Economy.dollars(40), Economy.dollars(40), "join-bonus"),
                LedgerEntry(1234L, uuid, -Economy.dollars(10), Economy.dollars(30), "pay:other"),
                LedgerEntry(1234L, uuid, Economy.dollars(20), Economy.dollars(50), "admin:$uuid"),
            ),
            ledger.read(uuid),
        )
    }

    @Test
    fun `insufficient withdrawal writes nothing`() {
        val store = JsonPlayerStore(dir.resolve("players"))
        val ledger = Ledger(dir.resolve("ledger"))
        val economy = Economy(store, ledger)
        economy.deposit(uuid, Economy.dollars(30), "join-bonus")
        val before = java.nio.file.Files.readString(dir.resolve("players").resolve("$uuid.json"))

        assertFalse(economy.withdraw(uuid, Economy.dollars(31), "pay:other"))
        assertEquals(before, java.nio.file.Files.readString(dir.resolve("players").resolve("$uuid.json")))
        assertEquals(1, ledger.read(uuid).size)
    }

    @Test
    fun `top sorts and refreshes after mutations`() {
        val store = JsonPlayerStore(dir.resolve("players"))
        val ledger = Ledger(dir.resolve("ledger"))
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        store.setBalance(first, Economy.dollars(10))
        store.setBalance(second, Economy.dollars(20))
        val economy = Economy(store, ledger)

        assertEquals(listOf(second to Economy.dollars(20), first to Economy.dollars(10)), economy.top())
        economy.deposit(first, Economy.dollars(20), "join-bonus")
        assertEquals(listOf(first to Economy.dollars(30), second to Economy.dollars(20)), economy.top())
    }
}
