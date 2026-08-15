package eu.mctraveler

import eu.mctraveler.bootstrap.ConduitRuntime
import eu.mctraveler.bootstrap.MixinHooks
import eu.mctraveler.bootstrap.RuntimeContext

/**
 * The runtime jar's front door, discovered by the bootstrap via ServiceLoader
 * (`META-INF/services/eu.mctraveler.bootstrap.ConduitRuntime`).
 */
class ConduitRuntimeImpl : ConduitRuntime {
    override fun start(context: RuntimeContext): MixinHooks {
        Conduit.context = context
        MCTraveler.start()
        return MixinHooksImpl
    }
}
