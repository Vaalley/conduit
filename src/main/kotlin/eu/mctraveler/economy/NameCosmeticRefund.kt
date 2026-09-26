package eu.mctraveler.economy

import eu.mctraveler.MCTraveler
import java.util.UUID
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

/**
 * The `/name` cosmetics (tag, colour, gradient) were removed after a community
 * vote, and everyone who paid for one is owed the money back.
 *
 * The ledger is the record of what was spent: every purchase left a negative
 * `fee:cosmetic-*` entry. On the first start after the removal each player is
 * paid back the sum of those fees, through the ledger as
 * [Reasons.REFUND_NAME_COSMETICS] — and because that entry is itself in the
 * ledger, [owed] nets it off, so a later start (or a crash halfway through the
 * first) never pays anyone twice.
 */
object NameCosmeticRefund {

    /** The reasons the removed `/name` commands charged under. */
    private val FEE_REASONS = setOf("fee:cosmetic-tag", "fee:cosmetic-color", "fee:cosmetic-gradient")

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { runOnce() }
    }

    /** What each player is still owed, in cents: fees paid, less refunds already made. */
    fun owed(entries: Sequence<LedgerEntry>): Map<UUID, Long> {
        val net = HashMap<UUID, Long>()
        for (entry in entries) {
            when (entry.reason) {
                in FEE_REASONS -> net.merge(entry.player, -entry.delta, Long::plus)
                Reasons.REFUND_NAME_COSMETICS -> net.merge(entry.player, -entry.delta, Long::plus)
            }
        }
        return net.filterValues { it > 0 }
    }

    private fun runOnce() {
        val persistence = MCTraveler.persistence ?: return
        for ((uuid, cents) in owed(persistence.ledger.all())) {
            persistence.economy.deposit(uuid, cents, Reasons.REFUND_NAME_COSMETICS)
            MCTraveler.LOGGER.info("Refunded {} for removed /name cosmetics: {}", uuid, Economy.format(cents))
        }
    }
}
