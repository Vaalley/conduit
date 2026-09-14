package eu.mctraveler.economy

import eu.mctraveler.persistence.PlayerStore
import java.util.UUID

/** Integer per-player balances backed by the existing player record format. */
object Economy {
    fun balanceOf(store: PlayerStore, uuid: UUID): Long = store.balance(uuid) ?: 0L

    fun deposit(store: PlayerStore, uuid: UUID, amount: Long) {
        require(amount >= 0) { "amount must be non-negative" }
        store.setBalance(uuid, balanceOf(store, uuid) + amount)
    }

    fun withdraw(store: PlayerStore, uuid: UUID, amount: Long): Boolean {
        require(amount >= 0) { "amount must be non-negative" }
        val balance = balanceOf(store, uuid)
        if (balance < amount) return false
        store.setBalance(uuid, balance - amount)
        return true
    }

    fun format(amount: Long): String = "$$amount"
}
