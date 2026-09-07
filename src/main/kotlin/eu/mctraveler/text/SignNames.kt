package eu.mctraveler.text

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.NbtOps
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.entity.BlockEntityTypes
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText

/**
 * The `<name>` sign-markdown token: it renders as the reading player's own name,
 * per viewer, so player A near a sign sees "A" where player B sees "B".
 *
 * `<name>` cannot be baked in at edit time the way the rest of the markdown is —
 * a sign carries one text to every client — so [Markdown.parse] only leaves a
 * marker: a run of the literal text `<name>` whose [net.minecraft.network.chat.Style]
 * carries [SIGN_NAME_SENTINEL] as its `insertion`. `insertion` round-trips
 * through sign NBT serialization, is otherwise unused on signs, and is invisible
 * to players; a client that somehow receives an un-rewritten sign just shows the
 * unambiguous literal `<name>`.
 *
 * The rewrite happens on the way out, at the one seam that knows both the packet
 * and its recipient ([eu.mctraveler.mixin.OutboundPacketMixin]): every sign
 * block-entity packet leaves wearing that viewer's name, and the stored sign is
 * never touched.
 *
 * A live [tokenChunks] registry keeps the chunk-load path free whenever the
 * feature is unused — the common case.
 */
object SignNames {

    const val SIGN_NAME_SENTINEL = "mctraveler:sign-name"

    /** `ChunkPos.pack` of every loaded chunk known to hold a `<name>` sign. */
    private val tokenChunks = ConcurrentHashMap.newKeySet<Long>()

    fun register() {
        ServerLifecycleEvents.SERVER_STOPPED.register { clear() }
    }

    // ---- Token detection -------------------------------------------------

    /** Whether any of [text]'s (up to four) lines carries a `<name>` sentinel run. */
    fun hasToken(text: SignText): Boolean =
        (0 until SignText.LINES).any { i ->
            containsSentinel(text.getMessage(i, false)) || containsSentinel(text.getMessage(i, true))
        }

    private fun containsSentinel(component: Component): Boolean =
        component.style.insertion == SIGN_NAME_SENTINEL || component.siblings.any(::containsSentinel)

    // ---- Per-viewer substitution --------------------------------------

    /**
     * [text] with every `<name>` sentinel run replaced by [viewerName] (carrying
     * the run's own style, minus the sentinel), or null when [text] carries no
     * sentinel and so is identical for everyone.
     */
    fun personalize(text: SignText, viewerName: String): SignText? {
        var result = text
        var changed = false
        for (i in 0 until SignText.LINES) {
            val raw = text.getMessage(i, false)
            val filtered = text.getMessage(i, true)
            val newRaw = personalizeComponent(raw, viewerName)
            val newFiltered = if (filtered === raw) newRaw else personalizeComponent(filtered, viewerName)
            if (newRaw != null || newFiltered != null) {
                result = result.setMessage(i, newRaw ?: raw, newFiltered ?: filtered)
                changed = true
            }
        }
        return if (changed) result else null
    }

    private fun personalizeComponent(component: Component, viewerName: String): MutableComponent? {
        var changed = false
        val self: MutableComponent = if (component.style.insertion == SIGN_NAME_SENTINEL) {
            changed = true
            Component.literal(viewerName).setStyle(component.style.withInsertion(null))
        } else {
            MutableComponent.create(component.contents).setStyle(component.style)
        }
        for (sibling in component.siblings) {
            val personalized = personalizeComponent(sibling, viewerName)
            if (personalized != null) {
                changed = true
                self.append(personalized)
            } else {
                self.append(sibling)
            }
        }
        return if (changed) self else null
    }

    // ---- Loaded-sign registry -----------------------------------------

    /** True when at least one loaded chunk holds a `<name>` sign. */
    fun hasTokenChunks(): Boolean = tokenChunks.isNotEmpty()

    fun clear() = tokenChunks.clear()

    /** Called from a mixin once a sign is in the world, and after markdown is applied on edit. */
    fun onSignLoadedOrChanged(level: Level, pos: BlockPos, sign: SignBlockEntity) {
        val key = ChunkPos.pack(pos)
        if (hasToken(sign.frontText) || hasToken(sign.backText)) {
            tokenChunks.add(key)
        } else {
            recompute(level, pos, key)
        }
    }

    /** Called from a mixin when a sign block entity is removed. */
    fun onSignRemoved(level: Level, pos: BlockPos) {
        recompute(level, pos, ChunkPos.pack(pos))
    }

    /**
     * Drops [key] from the registry when no resident sign in that chunk carries a
     * token any more. A chunk that is no longer loaded is left as-is — the send
     * path re-scans, so a stale entry only costs a redundant iteration.
     */
    private fun recompute(level: Level, pos: BlockPos, key: Long) {
        if (key !in tokenChunks) return
        val serverLevel = level as? ServerLevel ?: return
        val chunk = serverLevel.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) ?: return
        val stillHasToken = chunk.blockEntities.values.any { be ->
            be is SignBlockEntity && (hasToken(be.frontText) || hasToken(be.backText))
        }
        if (!stillHasToken) tokenChunks.remove(key)
    }

    // ---- Packet rewriting -------------------------------------------------

    /**
     * [packet] as [viewer] should see it:
     *  - a sign block-entity packet is re-serialized with [viewer]'s name where a
     *    `<name>` token stands, or returned untouched when the live sign carries none;
     *  - a chunk packet for a chunk known to hold a `<name>` sign schedules a
     *    follow-up block-entity packet per token sign (the viewer sees the literal
     *    `<name>` for one tick until it lands), and is itself returned untouched;
     *  - everything else passes straight through.
     */
    @JvmStatic
    fun personalizeSignsForViewer(viewer: ServerPlayer, packet: Packet<*>): Packet<*> {
        when (packet) {
            is ClientboundBlockEntityDataPacket -> {
                if (tokenChunks.isEmpty()) return packet
                if (!isSignType(packet.type)) return packet
                val sign = viewer.level().getBlockEntity(packet.pos) as? SignBlockEntity ?: return packet
                return personalizedPacket(sign, viewer) ?: packet
            }

            is ClientboundLevelChunkWithLightPacket -> {
                if (tokenChunks.isEmpty()) return packet
                val key = ChunkPos.pack(packet.x, packet.z)
                if (key !in tokenChunks) return packet
                val level = viewer.level()
                level.server.execute {
                    val chunk = level.chunkSource.getChunkNow(packet.x, packet.z) ?: return@execute
                    for (be in chunk.blockEntities.values) {
                        if (be is SignBlockEntity && (hasToken(be.frontText) || hasToken(be.backText))) {
                            personalizedPacket(be, viewer)?.let { viewer.connection.send(it) }
                        }
                    }
                }
                return packet
            }

            else -> return packet
        }
    }

    private fun isSignType(type: BlockEntityType<*>): Boolean =
        type == BlockEntityTypes.SIGN || type == BlockEntityTypes.HANGING_SIGN

    /**
     * A block-entity update packet for [sign] carrying [viewer]'s name, or null
     * when neither side of the sign holds a `<name>` token.
     */
    private fun personalizedPacket(sign: SignBlockEntity, viewer: ServerPlayer): ClientboundBlockEntityDataPacket? {
        val viewerName = viewer.gameProfile.name
        val front = personalize(sign.frontText, viewerName)
        val back = personalize(sign.backText, viewerName)
        if (front == null && back == null) return null
        return ClientboundBlockEntityDataPacket.create(
            sign,
            BiFunction { blockEntity, registryAccess: RegistryAccess ->
                val tag = blockEntity.getUpdateTag(registryAccess)
                val ops = registryAccess.createSerializationContext(NbtOps.INSTANCE)
                front?.let { tag.put("front_text", SignText.DIRECT_CODEC.encodeStart(ops, it).orThrow) }
                back?.let { tag.put("back_text", SignText.DIRECT_CODEC.encodeStart(ops, it).orThrow) }
                tag
            },
        )
    }
}
