package eu.mctraveler.store

import com.google.gson.JsonElement
import java.util.UUID
import net.minecraft.core.BlockPos

data class StoreRecord(
    val frameId: UUID,
    val owner: UUID,
    val dimension: String,
    val pos: BlockPos,
    val item: JsonElement,
    val pricePerItem: Long,
    var stock: Int,
)
