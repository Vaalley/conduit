package eu.mctraveler.economy

import com.mojang.authlib.GameProfile
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import eu.mctraveler.MCTraveler
import eu.mctraveler.region.RegionProtection
import eu.mctraveler.region.RegionsFeature
import eu.mctraveler.text.Paint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile

/**
 * Economy commands and login payouts. This feature is registered after
 * [eu.mctraveler.rank.RankFeature] so its end-of-tick balance write cannot
 * prevent RankFeature from recognizing a genuinely new player.
 */
object EconomyFeature {
    private val players = SuggestionProvider<CommandSourceStack> { context, builder ->
        net.minecraft.commands.SharedSuggestionProvider.suggest(
            context.source.server.playerList.players.map { it.gameProfile.name },
            builder,
        )
    }

    private data class PendingJoin(val uuid: UUID, val missingBalance: Boolean)

    private val pendingJoins = ArrayDeque<PendingJoin>()
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            pendingJoins += PendingJoin(
                handler.player.uuid,
                MCTraveler.persistence?.players?.balance(handler.player.uuid)?.let { false } ?: true,
            )
        }
        ServerTickEvents.END_SERVER_TICK.register(::drainJoins)
        RegionProtection.exemptMenu { it is LeaderboardMenu }
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("balance")
                .executes { ctx -> reply(ctx) { ownBalance(it) } }
                .then(Commands.literal("top").executes { ctx -> top(ctx.source.playerOrException) })
                .then(
                    Commands.literal("history")
                        .executes { ctx -> history(ctx, null, 1) }
                        .then(
                            Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes { ctx ->
                                    history(
                                        ctx,
                                        null,
                                        IntegerArgumentType.getInteger(ctx, "page"),
                                    )
                                },
                        )
                        .then(
                            Commands.argument("player", GameProfileArgument.gameProfile())
                                .suggests(players)
                                .executes { ctx -> history(ctx, profile(ctx), 1) }
                                .then(
                                    Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes { ctx ->
                                            history(
                                                ctx,
                                                profile(ctx),
                                                IntegerArgumentType.getInteger(ctx, "page"),
                                            )
                                        },
                                ),
                        )
                )
                .then(
                    Commands.argument("player", GameProfileArgument.gameProfile())
                        .suggests(players)
                        .executes { ctx ->
                            reply(ctx) { sender ->
                                RegionsFeature.adminGate(sender)?.let { return@reply it }
                                val target = profile(ctx) ?: return@reply Paint.error("Player not found")
                                Paint.balance(target.name(), " has ", Economy.format(economy().balanceOf(target.id())))
                            }
                        },
                )
                .then(adminMutation("set", 0) { target, value, sender ->
                    economy().set(target, value, Reasons.admin(sender.uuid))
                })
                .then(adminMutation("give", 1) { target, value, sender ->
                    economy().deposit(target, value, Reasons.admin(sender.uuid))
                }),
        )

        dispatcher.register(
            Commands.literal("pay")
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests(players)
                        .then(
                            Commands.argument("amount", StringArgumentType.word())
                                .executes { ctx ->
                                    reply(ctx) {
                                        val value = amount(ctx, 1)
                                            ?: return@reply Paint.error("Amount must be a number like 12.50")
                                        pay(it, StringArgumentType.getString(ctx, "player"), value)
                                    }
                                },
                        ),
                ),
        )

        dispatcher.register(
            Commands.literal("economy")
                .then(Commands.literal("stats").executes { ctx -> stats(ctx.source.playerOrException) }),
        )
    }

    private fun adminMutation(
        literal: String,
        minimum: Long,
        mutation: (UUID, Long, ServerPlayer) -> Unit,
    ) = Commands.literal(literal).then(
        Commands.argument("player", GameProfileArgument.gameProfile())
            .suggests(players)
            .then(
                Commands.argument("amount", StringArgumentType.word())
                    .executes { ctx ->
                        reply(ctx) { sender ->
                            val target = profile(ctx) ?: return@reply Paint.error("Player not found")
                            RegionsFeature.adminGate(sender)?.let { return@reply it }
                            val value = amount(ctx, minimum)
                                ?: return@reply Paint.error("Amount must be a number like 12.50")
                            mutation(target.id(), value, sender)
                            if (literal == "set") {
                                Paint.success(target.name(), " now has ", Economy.format(value))
                            } else {
                                Paint.success(target.name(), " received ", Economy.format(value))
                            }
                        }
                    },
            ),
    )

    private fun drainJoins(server: MinecraftServer) {
        while (true) {
            val pending = pendingJoins.removeFirstOrNull() ?: break
            val uuid = pending.uuid
            val player = server.playerList.getPlayer(uuid) ?: continue
            val persistence = MCTraveler.persistence ?: continue
            try {
                if (pending.missingBalance || persistence.players.balance(uuid) == null) {
                    persistence.economy.deposit(uuid, Economy.dollars(100), Reasons.JOIN_BONUS)
                }
                val firstJoin = persistence.players.firstJoin(uuid)
                    ?: persistence.passports.get(uuid)?.firstJoin
                    ?: continue
                val now = System.currentTimeMillis()
                val years = Anniversary.years(firstJoin, now)
                val alreadyPaid = persistence.players.anniversaryPaid(uuid) ?: 0
                for (year in Anniversary.due(firstJoin, now, alreadyPaid)) {
                    val payout = Anniversary.payout(year)
                    persistence.economy.deposit(uuid, payout, Reasons.anniversary(year))
                    player.sendSystemMessage(
                        Paint.balance("Happy ", ordinal(year), " anniversary! +", Economy.format(payout)),
                    )
                }
                if (years > alreadyPaid) persistence.players.setAnniversaryPaid(uuid, years)
            } catch (failure: Exception) {
                MCTraveler.LOGGER.warn("Skipping economy payouts for $uuid because its player record could not be read", failure)
            }
        }
    }

    private fun top(player: ServerPlayer): Int {
        val contents = SimpleContainer(54)
        val entries = economy().top()
        entries.take(45).forEachIndexed { index, (uuid, balance) ->
            contents.setItem(index, head(player, uuid, "#${index + 1} ${name(uuid, player)}", balance))
        }
        val ownRank = entries.indexOfFirst { it.first == player.uuid }
        val rank = if (ownRank < 0) "unranked" else "#${ownRank + 1}"
        contents.setItem(49, head(player, player.uuid, "$rank ${name(player.uuid, player)}", economy().balanceOf(player.uuid)))
        player.openMenu(
            SimpleMenuProvider(
                { id, inventory, _ -> LeaderboardMenu(id, inventory, contents) },
                Component.literal("Richest players"),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun history(context: CommandContext<CommandSourceStack>, target: NameAndId?, page: Int): Int {
        val sender = context.source.playerOrException
        val actual = target ?: NameAndId(sender.uuid, sender.gameProfile.name)
        if (actual.id() != sender.uuid) {
            RegionsFeature.adminGate(sender)?.let {
                sender.sendSystemMessage(it)
                return Command.SINGLE_SUCCESS
            }
        }
        val entries = ledger().read(actual.id()).asReversed()
        if (entries.isEmpty()) {
            sender.sendSystemMessage(Paint.balance("No transactions yet"))
            return Command.SINGLE_SUCCESS
        }
        val pages = (entries.size + 9) / 10
        val selectedPage = page.coerceAtMost(pages)
        sender.sendSystemMessage(
            Paint.balance("History of ", name(actual.id(), sender), " (page ", selectedPage, "/", pages, ")"),
        )
        entries.drop((selectedPage - 1) * 10).take(10).forEach { entry ->
            val delta = if (entry.delta >= 0) Paint.green("+${Economy.format(entry.delta)}")
            else Paint.red("-${Economy.format(-entry.delta)}")
            sender.sendSystemMessage(
                Paint.gray(
                    dateFormat.format(Instant.ofEpochMilli(entry.at)),
                    " ",
                    delta,
                    " → ",
                    Economy.format(entry.balance),
                    " ",
                    entry.reason,
                ),
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun stats(player: ServerPlayer): Int {
        RegionsFeature.adminGate(player)?.let {
            player.sendSystemMessage(it)
            return Command.SINGLE_SUCCESS
        }
        val stats = EconomyStats.of(ledger().all(), System.currentTimeMillis())
        player.sendSystemMessage(Paint.balance("Total supply ", Economy.format(economy().top().sumOf { it.second })))
        sendPeriod(player, "All time", stats.allTime)
        sendPeriod(player, "Last 7 days", stats.lastSevenDays)
        return Command.SINGLE_SUCCESS
    }

    private fun sendPeriod(player: ServerPlayer, label: String, period: EconomyPeriod) {
        player.sendSystemMessage(
            Paint.gray(
                "$label: created ${Economy.format(period.created)}, destroyed ${Economy.format(period.destroyed)}, net ${Economy.format(period.net)}",
            ),
        )
        period.byCategory.entries.sortedByDescending { it.value.created + it.value.destroyed }.forEach { (category, values) ->
            player.sendSystemMessage(
                Paint.gray(
                    "  ",
                    category,
                    ": created ",
                    Economy.format(values.created),
                    ", destroyed ",
                    Economy.format(values.destroyed),
                ),
            )
        }
        period.topFaucet?.let { player.sendSystemMessage(Paint.gray("  top faucet: ", it.first, " ", Economy.format(it.second))) }
        period.topSink?.let { player.sendSystemMessage(Paint.gray("  top sink: ", it.first, " ", Economy.format(it.second))) }
        player.sendSystemMessage(Paint.gray("  player-to-player volume: ", Economy.format(period.transferVolume)))
    }

    private fun head(viewer: ServerPlayer, uuid: UUID, title: String, balance: Long): ItemStack {
        val profile = viewer.level().server.playerList.getPlayer(uuid)?.gameProfile
            ?: GameProfile(uuid, name(uuid, viewer))
        return ItemStack(Items.PLAYER_HEAD).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal(title).setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withItalic(false)))
            set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
            set(
                DataComponents.LORE,
                ItemLore(
                    listOf(Component.literal(Economy.format(balance)).setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withItalic(false))),
                ),
            )
        }
    }

    private fun name(uuid: UUID, viewer: ServerPlayer): String =
        MCTraveler.persistence?.names?.usernameFor(uuid)
            ?: viewer.level().server.playerList.getPlayer(uuid)?.gameProfile?.name
            ?: uuid.toString()

    private fun ownBalance(player: ServerPlayer): Component =
        Paint.balance("Your balance is ", Economy.format(economy().balanceOf(player.uuid)))

    private fun pay(sender: ServerPlayer, targetName: String, amount: Long): Component {
        val target = sender.level().server.playerList.getPlayerByName(targetName)
            ?: return Paint.error("Player ", Paint.red(targetName), " is not online")
        if (target.uuid == sender.uuid) return Paint.error("You can't pay yourself")
        if (!economy().withdraw(sender.uuid, amount, Reasons.pay(target.uuid))) {
            return Paint.error("You don't have enough money")
        }
        economy().deposit(target.uuid, amount, Reasons.pay(sender.uuid))
        target.sendSystemMessage(Paint.balance(sender.gameProfile.name, " paid you ", Economy.format(amount)))
        return Paint.success("Paid ", target.gameProfile.name, " ", Economy.format(amount))
    }

    private fun profile(context: CommandContext<CommandSourceStack>): NameAndId? =
        runCatching { GameProfileArgument.getGameProfiles(context, "player").firstOrNull() }.getOrNull()

    private fun amount(context: CommandContext<CommandSourceStack>, minimum: Long): Long? =
        Economy.parseAmount(StringArgumentType.getString(context, "amount"), minimum)

    private fun ordinal(year: Int): String =
        when {
            year % 100 in 11..13 -> "${year}th"
            year % 10 == 1 -> "${year}st"
            year % 10 == 2 -> "${year}nd"
            year % 10 == 3 -> "${year}rd"
            else -> "${year}th"
        }

    private inline fun reply(
        context: CommandContext<CommandSourceStack>,
        handler: (ServerPlayer) -> Component,
    ): Int {
        val player = context.source.playerOrException
        player.sendSystemMessage(handler(player))
        return Command.SINGLE_SUCCESS
    }

    private fun economy(): Economy =
        checkNotNull(MCTraveler.persistence) { "the Economy needs the Persistence service" }.economy

    private fun ledger(): Ledger =
        checkNotNull(MCTraveler.persistence) { "the Economy needs the Persistence service" }.ledger

    class LeaderboardMenu(
        containerId: Int,
        inventory: Inventory,
        private val contents: Container,
    ) : ChestMenu(MenuType.GENERIC_9x6, containerId, inventory, contents, 6) {
        override fun clicked(slot: Int, button: Int, input: ContainerInput, player: Player) = Unit

        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
    }
}
