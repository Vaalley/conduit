package eu.mctraveler.store

object StoreLadder {
    val QUANTITIES = intArrayOf(1, 2, 4, 8, 10, 16, 32, 48, 64)

    fun priceFor(pricePerItem: Long, quantity: Int): Long = pricePerItem * quantity

    fun maxStock(rows: Int): Int = rows * 9 * 64
}
