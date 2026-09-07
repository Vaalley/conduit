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

    fun register() {
        if (!apiPresent()) return
        try {
            LodewayRegions.register()
            MCTraveler.LOGGER.info("Lodeway found: publishing regions to the web map")
        } catch (unavailable: NoClassDefFoundError) {
            MCTraveler.LOGGER.warn("Lodeway's map API is incomplete; not publishing regions", unavailable)
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
}
