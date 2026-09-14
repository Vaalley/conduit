package eu.mctraveler.store

import com.google.gson.JsonElement
import com.mojang.serialization.JsonOps
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

internal object StoreCodec {
    fun encode(player: ServerPlayer, stack: ItemStack): JsonElement =
        ItemStack.CODEC.encodeStart(
            player.registryAccess().createSerializationContext(JsonOps.INSTANCE),
            stack.copyWithCount(1),
        ).result().orElseThrow()

    fun decode(player: ServerPlayer, json: JsonElement): ItemStack =
        ItemStack.CODEC.parse(
            player.registryAccess().createSerializationContext(JsonOps.INSTANCE),
            json,
        ).resultOrPartial { error -> throw IllegalArgumentException("invalid store item: $error") }
            .orElse(ItemStack.EMPTY)
}
