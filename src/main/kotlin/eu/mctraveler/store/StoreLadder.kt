package eu.mctraveler.store

object StoreLadder {
    val QUANTITIES = intArrayOf(1, 2, 4, 8, 10, 16, 32, 48, 64)
    const val MAX_STOCK = 27 * 64

    fun priceFor(pricePerItem: Long, quantity: Int): Long = pricePerItem * quantity
}
