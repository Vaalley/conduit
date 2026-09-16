package eu.mctraveler.economy

import java.util.UUID
import eu.mctraveler.persistence.PlayerStore

class Economy(
    private val players: PlayerStore,
    private val ledger: Ledger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var balances: MutableMap<UUID, Long>? = null

    fun balanceOf(uuid: UUID): Long = players.balance(uuid) ?: 0L

    fun deposit(uuid: UUID, amount: Long, reason: String) {
        require(amount >= 0) { "amount must be non-negative" }
        mutate(uuid, amount, reason)
    }

    fun withdraw(uuid: UUID, amount: Long, reason: String): Boolean {
        require(amount >= 0) { "amount must be non-negative" }
        val balance = balanceOf(uuid)
        if (balance < amount) return false
        mutate(uuid, -amount, reason)
        return true
    }

    fun set(uuid: UUID, amount: Long, reason: String) {
        require(amount >= 0) { "amount must be non-negative" }
        mutate(uuid, amount - balanceOf(uuid), reason)
    }

    fun top(): List<Pair<UUID, Long>> {
        val current = balances ?: players.allBalances().toMutableMap().also { balances = it }
        return current.entries
            .sortedWith(compareByDescending<Map.Entry<UUID, Long>> { it.value }.thenBy { it.key.toString() })
            .map { it.key to it.value }
    }

    private fun mutate(uuid: UUID, delta: Long, reason: String) {
        val balance = balanceOf(uuid) + delta
        players.setBalance(uuid, balance)
        ledger.append(LedgerEntry(clock(), uuid, delta, balance, reason))
        balances?.set(uuid, balance)
    }

    companion object {
        fun format(amount: Long): String = "$$amount"
    }
}
