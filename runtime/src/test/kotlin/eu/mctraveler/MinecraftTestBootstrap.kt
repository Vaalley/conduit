package eu.mctraveler

import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.server.Bootstrap

/**
 * Brings vanilla far enough up for unit tests that build [net.minecraft.world.item.ItemStack]s.
 *
 * `Bootstrap.bootStrap()` alone fills the registries but leaves every item's
 * default components *unbound* — on a real server they are baked during the
 * datapack reload (`ReloadableServerResources`), so constructing an ItemStack
 * before that throws "Components not bound yet". Running the same initializers
 * against the built-in lookup is the headless equivalent.
 *
 * Idempotent, and cheap after the first call.
 */
object MinecraftTestBootstrap {
    private var done = false

    @Synchronized
    fun ensure() {
        if (done) return
        done = true
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
            .forEach { it.apply() }
    }
}
