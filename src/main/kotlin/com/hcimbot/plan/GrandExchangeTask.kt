package com.hcimbot.plan

import com.hcimbot.game.QuestItem
import com.hcimbot.guide.Tile

/**
 * Gets what Quest Helper lists for a quest, needed and recommended: takes what the bank has, and buys the rest from the Grand Exchange.
 * For an account that may trade (never an ironman).
 *
 * In turn: read the list from Quest Helper, open the bank and take what it holds of each item (and coins to pay with), then, for what is
 * still short, walk to the Grand Exchange, put in a buy offer for each, wait for them to fill and collect. Done when nothing is short.
 * If Quest Helper's list cannot be read, or the Grand Exchange cannot be driven, it pauses and says why.
 */
class GrandExchangeTask(
    private val questKey: String,
    /** Needed items always; the recommended ones too when this is true. */
    private val includeRecommended: Boolean = true,
) : Task {
    override val description = "Buy $questKey's items at the Grand Exchange"

    private enum class Phase { Read, Bank, Walk, Open, Offers, Collect, Done }

    private var phase = Phase.Read
    private var wanted: List<QuestItem> = emptyList()
    private val fromBank = HashMap<Int, Int>()
    private var bankLooked = false
    private var walk: WalkTask? = null
    private var waited = 0
    private var settling = 0
    private var placed = HashSet<Int>()

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy && phase != Phase.Offers) return TaskResult.Running
        return when (phase) {
            Phase.Read -> read(ctx)
            Phase.Bank -> bank(ctx)
            Phase.Walk -> walkToExchange(ctx)
            Phase.Open -> open(ctx)
            Phase.Offers -> offers(ctx)
            Phase.Collect -> collect(ctx)
            Phase.Done -> TaskResult.Done
        }
    }

    private fun held(ctx: TaskContext, item: QuestItem): Int = item.ids.sumOf { ctx.game.inventory()[it] ?: 0 }

    /** How many of [item] are still wanted, counting what is in the bag. */
    private fun short(ctx: TaskContext, item: QuestItem): Int = (item.quantity - held(ctx, item)).coerceAtLeast(0)

    private fun read(ctx: TaskContext): TaskResult {
        val items = ctx.game.questItems(questKey)
            ?: return TaskResult.Failed("could not read Quest Helper's item list for $questKey (${ctx.game.helperProblem() ?: "the plugin does not show it"})")
        wanted = items.filter { it.ids.isNotEmpty() && (includeRecommended || !it.recommended) }
        ctx.log("Quest Helper lists ${wanted.joinToString { "${it.quantity}x ${it.name}${if (it.recommended) " (recommended)" else ""}" }}")
        phase = if (wanted.none { short(ctx, it) > 0 }) Phase.Done else Phase.Bank
        return TaskResult.Running
    }

    /** What the bank holds is taken first, and so are coins, for the offers. */
    private fun bank(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (!game.isBankOpen) {
            if (bankLooked) { phase = Phase.Walk; return TaskResult.Running }
            return if (ctx.actions.openNearestBank()) TaskResult.Running else TaskResult.Running.also { if (++waited > OPEN_TICKS) return TaskResult.Failed("could not open a bank") }
        }
        bankLooked = true
        for (item in wanted) {
            val need = short(ctx, item)
            if (need <= 0) continue
            val id = item.ids.firstOrNull { game.bankCount(it) > 0 } ?: continue
            val take = minOf(need, game.bankCount(id))
            if ((fromBank[id] ?: 0) >= take) continue
            fromBank[id] = take
            ctx.actions.withdraw(id, take)
            settling = 2
            return TaskResult.Running
        }
        val coins = game.inventory()[COINS] ?: 0
        if (coins < COINS_WANTED && game.bankCount(COINS) > 0 && COINS !in fromBank) {
            fromBank[COINS] = 0
            ctx.actions.withdraw(COINS, 0)
            settling = 2
            return TaskResult.Running
        }
        ctx.actions.closeInterface()
        settling = 2
        phase = if (wanted.none { short(ctx, it) > 0 }) Phase.Done else Phase.Walk
        return TaskResult.Running
    }

    private fun walkToExchange(ctx: TaskContext): TaskResult {
        val goTo = walk ?: WalkTask(EXCHANGE, radius = 6).also { walk = it }
        return when (val result = goTo.tick(ctx)) {
            TaskResult.Done -> { phase = Phase.Open; waited = 0; TaskResult.Running }
            else -> result
        }
    }

    private fun open(ctx: TaskContext): TaskResult {
        if (ctx.actions.isGrandExchangeOpen) { phase = Phase.Offers; return TaskResult.Running }
        if (++waited > OPEN_TICKS) return TaskResult.Failed("could not open the Grand Exchange${ctx.actions.grandExchangeProblem?.let { ": $it" } ?: ""}")
        if (waited % 4 == 1) ctx.actions.openGrandExchange()
        return TaskResult.Running
    }

    private fun offers(ctx: TaskContext): TaskResult {
        if (!ctx.actions.isGrandExchangeOpen) { phase = Phase.Open; return TaskResult.Running }
        val item = wanted.firstOrNull { short(ctx, it) > 0 && it.ids.first() !in placed }
        if (item == null) { phase = Phase.Collect; waited = 0; return TaskResult.Running }
        val need = short(ctx, item)
        val ok = ctx.actions.grandExchangeBuy(item.name, item.ids.first(), need)
        if (!ok) return TaskResult.Failed("could not put in an offer for $need ${item.name}${ctx.actions.grandExchangeProblem?.let { ": $it" } ?: ""}")
        ctx.log("Offer in for $need ${item.name}")
        placed += item.ids.first()
        settling = 3
        return TaskResult.Running
    }

    /** Offers fill within moments at a generous price: collected until the bag has everything, or a time limit. */
    private fun collect(ctx: TaskContext): TaskResult {
        if (wanted.none { short(ctx, it) > 0 }) {
            ctx.actions.closeInterface()
            phase = Phase.Done
            return TaskResult.Running
        }
        if (++waited > COLLECT_TICKS) {
            val missing = wanted.filter { short(ctx, it) > 0 }.joinToString { "${short(ctx, it)}x ${it.name}" }
            return TaskResult.Failed("the offers did not fill in time, still short of $missing")
        }
        if (waited % 5 == 1) ctx.actions.collectGrandExchange()
        return TaskResult.Running
    }

    private companion object {
        val EXCHANGE = Tile(3164, 3487, 0)
        const val COINS = 995
        const val COINS_WANTED = 1_000
        const val OPEN_TICKS = 40
        const val COLLECT_TICKS = 150
    }
}
