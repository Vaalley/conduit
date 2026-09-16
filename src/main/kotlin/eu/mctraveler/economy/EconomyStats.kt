package eu.mctraveler.economy

data class EconomyPeriod(
    val created: Long,
    val destroyed: Long,
    val net: Long,
    val byCategory: Map<String, EconomyCategory>,
    val topFaucet: Pair<String, Long>?,
    val topSink: Pair<String, Long>?,
    val transferVolume: Long,
)

data class EconomyCategory(
    val created: Long = 0,
    val destroyed: Long = 0,
)

data class EconomyStats(
    val allTime: EconomyPeriod,
    val lastSevenDays: EconomyPeriod,
) {
    companion object {
        fun of(entries: Sequence<LedgerEntry>, now: Long): Stats {
            val all = entries.toList()
            return EconomyStats(
                period(all),
                period(all.filter { it.at >= now - SEVEN_DAYS_MILLIS }),
            )
        }

        private fun period(entries: List<LedgerEntry>): EconomyPeriod {
            val categories = linkedMapOf<String, EconomyCategory>()
            var created = 0L
            var destroyed = 0L
            var transferVolume = 0L
            for (entry in entries) {
                val category = entry.reason.substringBefore(':')
                if (Reasons.isTransfer(entry.reason)) {
                    if (entry.delta > 0) transferVolume += entry.delta
                    continue
                }
                val current = categories[category] ?: EconomyCategory()
                categories[category] = if (entry.delta >= 0) {
                    created += entry.delta
                    current.copy(created = current.created + entry.delta)
                } else {
                    destroyed += -entry.delta
                    current.copy(destroyed = current.destroyed - entry.delta)
                }
            }
            return EconomyPeriod(
                created = created,
                destroyed = destroyed,
                net = created - destroyed,
                byCategory = categories,
                topFaucet = categories.entries
                    .filter { it.value.created > 0 }
                    .maxWithOrNull(compareBy<Map.Entry<String, EconomyCategory>> { it.value.created }.thenByDescending { it.key })
                    ?.let { it.key to it.value.created },
                topSink = categories.entries
                    .filter { it.value.destroyed > 0 }
                    .maxWithOrNull(compareBy<Map.Entry<String, EconomyCategory>> { it.value.destroyed }.thenByDescending { it.key })
                    ?.let { it.key to it.value.destroyed },
                transferVolume = transferVolume,
            )
        }

        private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

typealias Stats = EconomyStats
