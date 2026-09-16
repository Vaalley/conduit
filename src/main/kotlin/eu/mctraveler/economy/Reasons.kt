package eu.mctraveler.economy

import java.util.UUID

object Reasons {
    const val JOIN_BONUS = "join-bonus"

    fun pay(other: UUID): String = "pay:$other"

    fun store(frame: UUID): String = "store:$frame"

    fun stamp(id: String): String = "stamp:$id"

    fun admin(sender: UUID): String = "admin:$sender"

    fun anniversary(year: Int): String = "anniversary:$year"

    fun isTransfer(reason: String): Boolean =
        reason.startsWith("pay:") ||
            reason.startsWith("store:") ||
            reason.startsWith("buy-order:")
}
