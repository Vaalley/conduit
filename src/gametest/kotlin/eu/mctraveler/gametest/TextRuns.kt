package eu.mctraveler.gametest

import net.minecraft.network.chat.Component

/**
 * One rendered run of styled text — what a player visually sees — with the style fields
 * the Portal's message language uses (color and bold).
 */
data class Run(val text: String, val color: String? = null, val bold: Boolean = false)

/** Flattens a component into its rendered runs, resolving style inheritance like the client does. */
fun runsOf(component: Component): List<Run> =
    component.toFlatList(component.style).map {
        Run(it.string, it.style.color?.serialize(), it.style.isBold)
    }
