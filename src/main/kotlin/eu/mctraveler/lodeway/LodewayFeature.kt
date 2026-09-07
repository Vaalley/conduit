package eu.mctraveler.lodeway

import eu.mctraveler.MCTraveler

/**
 * The optional Lodeway web-map integration (docs/lodeway-map.md): the regions
 * this server protects, drawn as one layer on `<name>.lodeway.app`.
 *
 * Everything past the guard below lives in [LodewayRegions], and that split is
 * the whole design. Lodeway supplies `app.lodeway.api.map` from its own
 * download; a server that does not run Lodeway has no such package, so this
 * class must be loadable — and must decide — without ever naming a type from
 * it. [register] therefore probes for the API by name and only then touches the
 * class that mentions it, which keeps a Lodeway-less server on exactly the code
 * path it had before this feature existed.
 *
 * The catch is the belt to that braces: class verification can resolve a
 * method's types earlier than its first call, so a `NoClassDefFoundError` from
 * a surprising place still degrades to "no map integration" rather than to a
 * runtime that fails to start.
 */
object LodewayFeature {

    private const val API_CLASS = "app.lodeway.api.map.Lodeway"
    private const val VANISHED_API_CLASS = "app.lodeway.api.map.Vanished"
    private const val VANISH_SOURCE = "mctraveler:vanish"

    /**
     * Lodeway added player visibility after the first marker API release this
     * project compiled against. Resolve it by name so an older Lodeway install
     * still starts normally; updating the optional map must never become a hard
     * requirement for running Conduit.
     */
    private var assertInvisible: java.lang.reflect.Method? = null

    fun register() {
        if (!apiPresent()) return
        try {
            assertInvisible = visibilityMethod()
            LodewayRegions.register()
            MCTraveler.LOGGER.info("Lodeway found: publishing regions to the web map")
            if (assertInvisible == null) {
                MCTraveler.LOGGER.warn("Lodeway's player visibility API is unavailable; /vanish cannot hide players from the web map")
            }
        } catch (unavailable: NoClassDefFoundError) {
            MCTraveler.LOGGER.warn("Lodeway's map API is incomplete; not publishing regions", unavailable)
        }
    }

    /** Holds or releases Conduit's own invisibility claim for [playerName]. */
    fun setPlayerInvisible(playerName: String, invisible: Boolean) {
        val method = assertInvisible ?: return
        try {
            method.invoke(null, VANISH_SOURCE, playerName, invisible)
        } catch (failed: ReflectiveOperationException) {
            MCTraveler.LOGGER.warn("could not update $playerName's Lodeway visibility", failed)
        }
    }

    /** Whether a Lodeway download on this server offers the map API. */
    private fun apiPresent(): Boolean =
        try {
            Class.forName(API_CLASS, false, LodewayFeature::class.java.classLoader)
            true
        } catch (missing: ClassNotFoundException) {
            false
        }

    private fun visibilityMethod(): java.lang.reflect.Method? =
        try {
            Class.forName(VANISHED_API_CLASS, false, LodewayFeature::class.java.classLoader)
                .getMethod(
                    "assertInvisible",
                    String::class.java,
                    String::class.java,
                    java.lang.Boolean.TYPE,
                )
        } catch (missing: ReflectiveOperationException) {
            null
        } catch (incompatible: LinkageError) {
            null
        }
}
