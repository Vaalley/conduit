package eu.mctraveler.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode

/**
 * Brigadier tree surgery. The Portal owned several vanilla command names outright
 * (it removed vanilla /msg, /tell and /w in favour of its own), and Brigadier has no
 * removal API — so dropping a vanilla root command means reaching into the node's
 * internal child maps.
 *
 * This is not the client-tree injection ADR 0002 retired: features still register
 * plain Brigadier commands. Removal is the one surgical act left, needed because
 * re-registering an existing literal *merges* with vanilla's node (leaving its
 * selector branch live) instead of replacing it.
 */
object CommandTree {
    private val childrenField = CommandNode::class.java.getDeclaredField("children").apply { isAccessible = true }
    private val literalsField = CommandNode::class.java.getDeclaredField("literals").apply { isAccessible = true }

    /** Removes top-level commands by name; names that are not registered are ignored. */
    fun removeRootCommands(dispatcher: CommandDispatcher<*>, vararg names: String) {
        val children = childrenField.get(dispatcher.root) as MutableMap<*, *>
        val literals = literalsField.get(dispatcher.root) as MutableMap<*, *>
        for (name in names) {
            children.remove(name)
            literals.remove(name)
        }
    }
}
