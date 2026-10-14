package com.hcimbot.plan

import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.ItemRef
import com.hcimbot.guide.Tile

/*
 * Tasks used by the hand written step scripts in StepScripts. They follow the
 * same rules as Tasks.kt: at most one action per tick, wait while busy, and
 * fail with a reason instead of guessing.
 */

/** Drops every one of these items. */
class DropTask(private val itemIds: List<Int>) : BaseTask() {
    override val description = "Drop $itemIds"

    override fun tick(ctx: TaskContext): TaskResult {
        if (ctx.game.isBusy) return TaskResult.Running
        val next = itemIds.firstOrNull { (ctx.game.inventory()[it] ?: 0) > 0 } ?: return TaskResult.Done
        return attempt(ctx.actions.itemAction(next, "Drop"), "drop item $next")
    }
}

/** Uses an item's menu option, then answers any chat with [dialogue], e.g. "Destroy" then "Yes", or "Read". */
class ItemActionTask(
    private val itemId: Int,
    private val action: String,
    private val dialogue: DialoguePlan = DialoguePlan.None,
) : BaseTask() {
    private var used = false
    private var settled = 0
    override val description = "$action item $itemId"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (game.canContinueDialog) return attempt(ctx.actions.continueDialog(), "continue the dialogue")
        val options = game.dialogOptions()
        if (options.isNotEmpty()) {
            return when (val choice = dialogue.choose(options)) {
                is DialoguePlan.Choice.ByIndex -> attempt(ctx.actions.chooseDialogOption(choice.index), "pick option ${choice.index}")
                is DialoguePlan.Choice.ByText -> attempt(ctx.actions.chooseDialogOption(choice.text), "pick \"${choice.text}\"")
                is DialoguePlan.Choice.Stuck -> TaskResult.Failed(choice.reason)
            }
        }
        if (game.isBusy) return TaskResult.Running
        if (used) return if (++settled >= SETTLE) TaskResult.Done else TaskResult.Running
        if (itemId !in game.inventory() && itemId !in game.equipment()) return TaskResult.Failed("item $itemId is not held")
        used = ctx.actions.itemAction(itemId, action)
        return attempt(used, "$action item $itemId")
    }

    private companion object {
        /** A couple of ticks for a chat box or interface to appear after the click. */
        const val SETTLE = 2
    }
}

/** Opens a shop and sells each of [itemIds] until none are left in the inventory. */
/** Walks onto [tile] and digs there with a spade, for a clue or quest dig. */
class DigTask(val tile: Tile) : BaseTask() {
    private var dig: ItemActionTask? = null
    override val description = "Dig at $tile"

    override fun tick(ctx: TaskContext): TaskResult {
        dig?.let { return it.tick(ctx) }
        if (SPADE !in ctx.game.inventory()) return TaskResult.Failed("a spade is needed to dig at $tile")
        val at = ctx.game.playerTile ?: return TaskResult.Running
        if (ctx.game.isBusy) return TaskResult.Running
        return when (at.distanceTo(tile)) {
            0 -> ItemActionTask(SPADE, "Dig").also { dig = it }.tick(ctx)
            in 1..CLOSE -> attempt(ctx.actions.stepOnto(tile), "step onto $tile")
            else -> attempt(ctx.actions.walkTo(tile), "walk to $tile")
        }
    }

    companion object {
        const val SPADE = 952
        private const val CLOSE = 6
    }
}

/**
 * Goes looking when no target is in sight: walks to the nearest place the NPC
 * spawns, waits a little, then tries the next nearest. Only spots close by and
 * outside the Wilderness count, so it never wanders off across the map.
 */
/**
 * Whether [npc] still lives: the game's own answer when it has one, otherwise
 * whether something is still fighting the player. Not "one of its kind is
 * still around": others of its kind usually are, and the fight would never end.
 */
private fun alive(ctx: TaskContext, npc: SceneEntity): Boolean = ctx.game.npcAlive(npc) ?: ctx.game.isInCombat

private const val RECAST_TICKS = 5
private const val MAX_CASTS = 20

class Hunt(private val names: List<String>, private val lookFirst: List<Tile> = emptyList()) {
    private val tried = mutableListOf<Tile>()
    private var walk: WalkTask? = null
    private var looking = 0

    fun found() {
        walk = null
        looking = 0
    }

    fun tick(ctx: TaskContext): TaskResult {
        val what = names.firstOrNull() ?: "target"
        val at = ctx.game.playerTile ?: return TaskResult.Running
        walk?.let { going ->
            when (val walked = going.tick(ctx)) {
                TaskResult.Done -> Unit
                else -> return walked
            }
            if (++looking < LOOK_TICKS) return TaskResult.Running
            walk = null
            looking = 0
        }
        if (tried.size >= MAX_SPOTS) return TaskResult.Failed("looked at ${tried.size} spots where $what spawns and found none")
        val untried = { spot: Tile -> tried.none { it.distanceTo(spot) <= SAME_SPOT } }
        val spot = lookFirst.firstOrNull { !it.inWilderness && untried(it) }
            ?: names.flatMap { ctx.game.npcSpawns(it) }
                .filter { it.plane == at.plane && !it.inWilderness && it.distanceTo(at) <= RANGE }
                .filter(untried)
                .minByOrNull { it.distanceTo(at) }
            ?: return TaskResult.Failed(
                if (tried.isEmpty()) "no $what nearby, and no known spawn spot within $RANGE tiles"
                else "looked at ${tried.size} spots where $what spawns and found none",
            )
        tried += spot
        ctx.log("No $what in sight, going to look at $spot")
        walk = WalkTask(spot, radius = 3)
        return TaskResult.Running
    }

    companion object {
        /** How far to go looking. Lumbridge castle to the swamp is about 40. */
        const val RANGE = 60
        /** Spots this close together are one place to look. */
        const val SAME_SPOT = 8
        const val LOOK_TICKS = 5
        const val MAX_SPOTS = 8
    }
}

class SellTask(
    private val shopkeeperIds: List<Int>,
    private val shopkeeperNames: List<String>,
    private val near: Tile?,
    private val itemIds: List<Int>,
) : BaseTask() {
    private val open = InteractTask(InteractTask.Kind.NPC, shopkeeperIds, shopkeeperNames, listOfNotNull(near), "Trade", near = near)
    private var waiting = 0
    override val description = "Sell $itemIds"

    override fun tick(ctx: TaskContext): TaskResult {
        val held = itemIds.filter { (ctx.game.inventory()[it] ?: 0) > 0 }
        if (!ctx.game.isShopOpen) {
            if (held.isEmpty()) return TaskResult.Done
            return when (val opening = open.tick(ctx)) {
                TaskResult.Done -> if (++waiting < ARRIVAL_TICKS) TaskResult.Running else TaskResult.Failed("no shop opened")
                else -> opening
            }
        }
        if (ctx.game.isBusy) return TaskResult.Running
        // Leave the shop open: a buy usually follows.
        val next = held.firstOrNull() ?: return TaskResult.Done
        return attempt(ctx.actions.sell(next, SELL_LOT), "sell item $next")
    }

    private companion object {
        /** The guide only sells starter items one at a time, and a lot of 5 sells all of one kind. */
        const val SELL_LOT = 5
    }
}

/** Opens the nearest bank, deposits each of [itemIds], and closes it. */
class DepositItemsTask(private val itemIds: List<Int>) : BaseTask() {
    override val description = "Deposit $itemIds"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (game.isBusy) return TaskResult.Running
        val held = itemIds.filter { (game.inventory()[it] ?: 0) > 0 }
        if (!game.isBankOpen) {
            return if (held.isEmpty()) TaskResult.Done else attempt(ctx.actions.openNearestBank(), "open a bank")
        }
        val next = held.firstOrNull() ?: return attempt(ctx.actions.closeInterface(), "close the bank")
        return attempt(ctx.actions.depositItem(next), "deposit item $next")
    }
}

/** Hops to another world once, then waits for the client to settle. */
class HopTask : BaseTask() {
    private var hopped = false
    private var waited = 0
    override val description = "Hop worlds"

    override fun tick(ctx: TaskContext): TaskResult {
        if (!hopped) {
            if (ctx.game.isBusy || ctx.game.isShopOpen || ctx.game.isBankOpen) {
                if (ctx.game.isShopOpen || ctx.game.isBankOpen) ctx.actions.closeInterface()
                return TaskResult.Running
            }
            hopped = ctx.actions.hopWorld()
            return attempt(hopped, "hop worlds")
        }
        return if (++waited >= HOP_TICKS) TaskResult.Done else TaskResult.Running
    }

    private companion object {
        const val HOP_TICKS = 10
    }
}

/** Changes a game setting, e.g. turning off level up messages. */
class SettingTask(private val setting: String) : BaseTask() {
    private var done = false
    override val description = "Setting: $setting"

    override fun tick(ctx: TaskContext): TaskResult {
        if (done) return TaskResult.Done
        if (ctx.game.isBusy) return TaskResult.Running
        done = ctx.actions.applySetting(setting)
        return attempt(done, "change setting $setting")
    }
}

/** Uses an inventory item on a nearby NPC, e.g. bones on the Stray Dog. */
class UseItemOnNpcTask(private val itemId: Int, private val npcIds: List<Int>) : BaseTask() {
    private var used = false
    override val description = "Use item $itemId on NPC $npcIds"

    override fun tick(ctx: TaskContext): TaskResult {
        if (ctx.game.isBusy) return TaskResult.Running
        if (used) return TaskResult.Done
        if (itemId !in ctx.game.inventory()) return TaskResult.Failed("item $itemId is not in the inventory")
        val npc = ctx.game.nearestNpc(npcIds) ?: return TaskResult.Failed("none of NPC $npcIds is nearby")
        used = ctx.actions.useItemOnNpc(itemId, npc)
        return attempt(used, description)
    }
}

/**
 * Kills one of these NPCs and picks up [loot], again and again until the loot
 * is in the inventory ("Kill a Chicken. Make sure you get a feather").
 * [spell] casts that spell on the target instead of attacking in melee.
 * Hitpoints stay under the runner's safety checks throughout.
 */
class KillTask(
    private val npcIds: List<Int>,
    private val npcNames: List<String>,
    private val loot: List<ItemRef>,
    private val spell: String? = null,
    private val maxKills: Int = 5,
    /** Where to look first when none is in sight, before the spawn data's nearest spots. */
    lookFirst: Tile? = null,
) : BaseTask() {
    private var kills = 0
    private var fighting = false
    private var target: SceneEntity? = null
    private var sinceCast = 0
    private var casts = 0
    private var take: TakeItemTask? = null
    private val hunt = Hunt(npcNames, listOfNotNull(lookFirst))
    override val description = "Kill ${npcNames.firstOrNull() ?: npcIds} for ${loot.joinToString { it.name }}"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val missing = loot.filter { ctx.conditions.count(it.ids) < (it.count ?: 1) }
        take?.let { picking ->
            return when (val result = picking.tick(ctx)) {
                TaskResult.Running -> result
                // The kill did not drop it, or it is gone. Kill another.
                else -> { take = null; TaskResult.Running }
            }
        }
        if (missing.isEmpty() && !fighting) return TaskResult.Done
        if (fighting) {
            val fought = target
            // Counted every tick: being hit plays a block animation, which must not hold up the next cast.
            sinceCast++
            if (spell == null && game.isBusy) return TaskResult.Running
            if (spell != null && sinceCast < RECAST_TICKS) return TaskResult.Running
            if (fought != null && alive(ctx, fought)) {
                // A melee fight carries on by itself, but without autocast every spell is a click.
                val spellName = spell ?: return TaskResult.Running
                if (++casts > MAX_CASTS) return TaskResult.Failed("cast $casts times and the ${fought.name} is still alive")
                sinceCast = 0
                val clicked = ctx.actions.castOnNpc(spellName, fought)
                ctx.log(if (clicked) "${fought.name} still alive, cast $spellName again ($casts)" else "could not cast $spellName on ${fought.name}")
                return attempt(clicked, "cast $spellName on ${fought.name}")
            }
            ctx.log("${fought?.name ?: "Target"} is dead after $casts ${if (spell != null) "casts" else "attacks"}")
            fighting = false
            target = null
            kills++
            take = TakeItemTask(missing)
            return TaskResult.Running
        }
        if (kills >= maxKills) return TaskResult.Failed("killed $kills and still have no ${missing.joinToString { it.name }}")
        if (game.isBusy) return TaskResult.Running
        val target = game.nearestNpc(npcIds, npcNames) ?: return hunt.tick(ctx)
        hunt.found()
        val started = if (spell != null) ctx.actions.castOnNpc(spell, target) else ctx.actions.interactNpc(target, "Attack")
        ctx.log(if (started) "Attacking ${target.name}${spell?.let { " with $it" } ?: ""}" else "could not attack ${target.name}")
        if (started) {
            fighting = true
            this.target = target
            sinceCast = 0
            casts = 1
        }
        return attempt(started, "attack ${target.name}")
    }
}

/** Runs [task] again and again until [done] holds, e.g. searching crates until there are three logs. */
class RepeatUntil(
    private val label: String,
    private val done: (TaskContext) -> Boolean,
    private val make: () -> Task,
    private val maxRounds: Int = 10,
) : Task {
    private var current: Task? = null
    private var rounds = 0
    private var settle = 0
    override val description = label

    override fun tick(ctx: TaskContext): TaskResult {
        if (done(ctx)) return TaskResult.Done
        if (settle > 0) {
            settle--
            return TaskResult.Running
        }
        val task = current ?: make().also { current = it }
        return when (val result = task.tick(ctx)) {
            TaskResult.Done -> {
                current = null
                settle = SETTLE_TICKS
                if (++rounds >= maxRounds) TaskResult.Failed("$label: tried $rounds times") else TaskResult.Running
            }
            else -> result
        }
    }

    private companion object {
        /** Let the game update before checking [done] again. */
        const val SETTLE_TICKS = 2
    }
}

/** Runs [task], and if it cannot be done, moves on anyway. For things the guide says to do "whenever you find him". */
class Optional(private val task: Task) : Task {
    override val description get() = "${task.description} (if possible)"

    override fun tick(ctx: TaskContext): TaskResult = when (val result = task.tick(ctx)) {
        is TaskResult.Failed -> {
            ctx.log("Skipped optional ${task.description}: ${result.reason}")
            TaskResult.Done
        }
        else -> result
    }
}

/**
 * Picks up every stack of these items lying near the player, however many are
 * already carried, e.g. the runes dropped to claim more from the Magic combat
 * tutor. Done once none are left within [radius].
 */
class PickUpTask(private val itemIds: List<Int>, private val radius: Int = 8) : BaseTask() {
    private var waited = 0
    override val description = "Pick up $itemIds"

    override fun tick(ctx: TaskContext): TaskResult {
        if (ctx.game.isBusy) return TaskResult.Running
        val at = ctx.game.playerTile ?: return TaskResult.Running
        val stack = ctx.game.nearestGroundItem(itemIds)?.takeIf { it.tile.distanceTo(at) <= radius }
        if (stack == null) {
            // A moment for a stack that is still being picked up to leave the ground.
            return if (++waited >= SETTLE) TaskResult.Done else TaskResult.Running
        }
        waited = 0
        return attempt(ctx.actions.takeGroundItem(stack), "pick up ${stack.name}")
    }

    private companion object {
        const val SETTLE = 2
    }
}

