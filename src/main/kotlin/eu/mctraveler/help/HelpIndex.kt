package eu.mctraveler.help

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode

/** One line of the help menu: a command the viewer can use, with the aliases it is also known by. */
class HelpEntry<S>(
    val name: String,
    val aliases: List<String>,
    val node: CommandNode<S>,
    val info: CommandHelp?,
) {
    /** `/region, /rg` */
    val namesLine: String get() = (listOf(name) + aliases).joinToString(", ") { "/$it" }
}

/**
 * The commands a particular viewer can use, worked out from the live Brigadier
 * dispatcher: pure logic over a stub-able dispatcher, no Minecraft types.
 *
 * - Every root that the [source] `canUse` is listed, mod and vanilla alike.
 * - Alias groups collapse to one entry, the primary name: a root that
 *   `redirect`s to another root is an alias of it (found generically), and the
 *   [catalog] declares aliases of separately registered trees (`rg` for `region`).
 * - `help` sorts to the top, then everything else alphabetically, ignoring case.
 */
class HelpIndex<S> private constructor(
    private val dispatcher: CommandDispatcher<S>,
    private val source: S,
    private val viewerIsAdmin: Boolean,
    val entries: List<HelpEntry<S>>,
) {
    private val byName: Map<String, HelpEntry<S>> = buildMap {
        for (entry in this@HelpIndex.entries) {
            put(entry.name.lowercase(), entry)
            for (alias in entry.aliases) put(alias.lowercase(), entry)
        }
    }

    val pageCount: Int get() = maxOf(1, (entries.size + PAGE_SIZE - 1) / PAGE_SIZE)

    /** The entries on [page] (1-based); empty when out of range. */
    fun page(page: Int): List<HelpEntry<S>> =
        if (page !in 1..pageCount) emptyList() else entries.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)

    /** Resolves a primary command or alias name, with or without the leading slash. */
    fun find(raw: String): HelpEntry<S>? = byName[raw.trim().removePrefix("/").lowercase()]

    /** Every name (primary and alias) the viewer may ask `/help` about. */
    fun names(): List<String> = entries.flatMap { listOf(it.name) + it.aliases }

    /**
     * The subcommand literals of [entry] the viewer can use, in registration
     * order: what "Available options are:" lists.
     */
    fun options(entry: HelpEntry<S>): List<String> =
        visibleChildren(entry.node, atRoot = true).filterIsInstance<LiteralCommandNode<S>>().map { it.name }

    /**
     * One compact syntax line, e.g. `/msg <target> <message>`, `/rtp [option]`.
     * Required parts are `<x>`, optional ones `[x]`. A node that fans out into
     * several subcommands collapses to `<option>`, or `[option]` when the command
     * also works with nothing after it; we never dump every subcommand's usage.
     */
    fun syntax(entry: HelpEntry<S>): String {
        val tokens = mutableListOf<String>()
        var node = entry.node
        var optional = false
        while (true) {
            val children = visibleChildren(node, atRoot = node === entry.node)
            if (children.isEmpty()) break
            optional = optional || node.command != null
            if (children.size > 1) {
                tokens += if (optional) "[option]" else "<option>"
                break
            }
            val child = children.single()
            tokens += when {
                child is LiteralCommandNode<S> -> if (optional) "[${child.name}]" else child.name
                optional -> "[${child.name}]"
                else -> "<${child.name}>"
            }
            node = child
        }
        return ("/${entry.name} " + tokens.joinToString(" ")).trimEnd()
    }

    /**
     * Brigadier's own usage lines for [entry], for commands the catalog knows
     * nothing about. Each is relative to the command name.
     */
    fun usageLines(entry: HelpEntry<S>): List<String> =
        dispatcher.getAllUsage(entry.node, source, true).toList()

    private fun visibleChildren(node: CommandNode<S>, atRoot: Boolean): List<CommandNode<S>> =
        node.children.filter { child ->
            child.canUse(source) &&
                !(atRoot && !viewerIsAdmin && child is LiteralCommandNode<S> && child.name in adminOnly(node))
        }

    private fun adminOnly(rootNode: CommandNode<S>): Set<String> =
        entries.firstOrNull { it.node === rootNode }?.info?.adminOnlyOptions.orEmpty()

    companion object {
        const val PAGE_SIZE = 10
        const val HELP = "help"

        /** Builds the index of what [source] can use; [viewerIsAdmin] also reveals admin-only options. */
        fun <S> build(
            dispatcher: CommandDispatcher<S>,
            source: S,
            viewerIsAdmin: Boolean,
            catalog: HelpCatalog,
        ): HelpIndex<S> {
            val roots = dispatcher.root.children.filterIsInstance<LiteralCommandNode<S>>()
            val rootNames = roots.map { it.name }.toSet()

            // alias name -> primary name
            val aliasOf = mutableMapOf<String, String>()
            for (root in roots) {
                val target = root.redirect as? LiteralCommandNode<S> ?: continue
                if (target.name != root.name && target.name in rootNames) aliasOf[root.name] = target.name
            }
            for ((alias, primary) in catalog.declaredAliases) {
                if (alias in rootNames && primary in rootNames) aliasOf[alias] = primary
            }
            fun primaryOf(name: String): String {
                var current = name
                repeat(8) { current = aliasOf[current] ?: return current }
                return current
            }

            val aliasesByPrimary = roots
                .filter { it.name in aliasOf && it.canUse(source) }
                .groupBy({ primaryOf(it.name) }, { it.name })

            val entries = roots
                .filter { it.name !in aliasOf && it.canUse(source) }
                .map { root ->
                    HelpEntry(
                        name = root.name,
                        aliases = aliasesByPrimary[root.name].orEmpty().sortedWith(String.CASE_INSENSITIVE_ORDER),
                        node = root,
                        info = catalog.of(root.name),
                    )
                }
                .sortedWith(
                    compareBy<HelpEntry<S>>({ it.name != HELP }, { it.name.lowercase() }, { it.name }),
                )
            return HelpIndex(dispatcher, source, viewerIsAdmin, entries)
        }
    }
}
