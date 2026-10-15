package com.hcimbot.plan

import com.hcimbot.game.SceneEntity
import com.hcimbot.game.canReachBeside
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
    /** For "Read" on a book: it stays open on screen, and clicks go to it, so close it when done. */
    private val closeAfter: Boolean = false,
) : BaseTask() {
    private var used = false
    private var settled = 0
    private var closes = 0
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
        if (used) {
            if (++settled < SETTLE) return TaskResult.Running
            if (closeAfter && closes < 2) {
                // Twice, a few ticks apart, in case the first click missed. Closing a closed window does nothing.
                if (settled % 3 == 0) {
                    ctx.actions.closeWindow()
                    closes++
                }
                return TaskResult.Running
            }
            return TaskResult.Done
        }
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
/**
 * Looks for a wandering NPC by walking a round of places it is known to be seen, and uses an item
 * on it once found ("Use the bones on the Stray Dog whenever you find him"). Any chat the use
 * starts is clicked through. Gives up after [rounds] full rounds without finding it.
 */
class FindAndUseTask(
    private val itemId: Int,
    private val npcName: String,
    private val round: List<Tile>,
    private val rounds: Int = 2,
) : BaseTask() {
    private var index = 0
    private var done = 0
    private var walk: WalkTask? = null
    private var used = false
    private var settle = 0
    override val description = "Find the $npcName and use item $itemId on it"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (game.canContinueDialog) return attempt(ctx.actions.continueDialog(), "continue the dialogue")
        if (game.dialogOptions().isNotEmpty()) {
            return TaskResult.Failed("the $npcName's chat asks to choose from ${game.dialogOptions()}")
        }
        if (used) return if (++settle >= SETTLE_TICKS) TaskResult.Done else TaskResult.Running
        if (game.isBusy && game.nearestNpc(emptyList(), listOf(npcName)) == null) return TaskResult.Running
        if (itemId !in game.inventory()) return TaskResult.Failed("item $itemId is not in the inventory")

        val npc = game.nearestNpc(emptyList(), listOf(npcName))
        if (npc != null) {
            ctx.log("Found the $npcName at ${npc.tile}")
            used = ctx.actions.useItemOnNpc(itemId, npc)
            return attempt(used, "use item $itemId on the $npcName")
        }
        if (round.isEmpty()) return TaskResult.Failed("no $npcName nearby and no places to look")
        val going = walk ?: WalkTask(round[index], radius = 4).also {
            walk = it
            ctx.log("No $npcName in sight, looking around ${round[index]}")
        }
        return when (val walked = going.tick(ctx)) {
            TaskResult.Done -> {
                walk = null
                index = (index + 1) % round.size
                if (index == 0 && ++done >= rounds) TaskResult.Failed("looked around $rounds times and did not find the $npcName") else TaskResult.Running
            }
            else -> walked
        }
    }

    private companion object {
        const val SETTLE_TICKS = 3
    }
}

/**
 * Lights logs lying on the ground by using a tinderbox on each, up to [count], waiting for each one to
 * burn away before the next. Done when [count] are lit, or none are left in sight after some were.
 */
class LightLogsTask(
    private val logIds: List<Int>,
    private val tinderboxId: Int,
    private val count: Int,
    private val around: Tile,
) : BaseTask() {
    private var lit = 0
    private var waiting = 0
    private var current: SceneEntity? = null
    private var walk: WalkTask? = null
    override val description = "Light $count logs"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val at = game.playerTile ?: return TaskResult.Running
        if (at.plane != around.plane || at.distanceTo(around) > ROOM) {
            val going = walk ?: WalkTask(around, radius = 3).also { walk = it }
            return when (val walked = going.tick(ctx)) {
                TaskResult.Done -> { walk = null; TaskResult.Running }
                else -> walked
            }
        }
        if (game.isBusy) return TaskResult.Running
        // The one just lit: wait for it to burn away, or for the lighting to fail.
        current?.let { lighting ->
            val stillThere = game.nearestGroundItem(logIds)?.takeIf { it.tile == lighting.tile } != null
            if (stillThere && ++waiting < BURN_TICKS) return TaskResult.Running
            current = null
            waiting = 0
        }
        if (lit >= count) return TaskResult.Done
        if (tinderboxId !in game.inventory()) return TaskResult.Failed("a tinderbox is needed to light the logs")
        val logs = game.nearestGroundItem(logIds)?.takeIf { it.tile.distanceTo(around) <= ROOM }
        if (logs == null) {
            return if (lit > 0) TaskResult.Done else TaskResult.Failed("no logs to light in sight")
        }
        current = logs
        lit++
        ctx.log("Lighting the logs at ${logs.tile} ($lit of $count)")
        return attempt(ctx.actions.useItemOnGroundItem(tinderboxId, logs), "light the logs")
    }

    private companion object {
        const val ROOM = 10
        const val BURN_TICKS = 12
    }
}

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



/**
 * Children of the Sun's tailing part: follow a guard through Varrock, standing still at each marked hiding
 * place until he has walked on, then moving to the next, so he never sees the player. Done at the last
 * place once he has gone. Pauses (rather than risking the quest) if the guard cannot be found for a
 * while, or the walk takes far longer than it should.
 */
class FollowGuardTask(private val guardIds: List<Int>, private val hides: List<Tile>) : BaseTask() {
    override val description = "Follow the guard, hiding at ${hides.size} places"
    private var index = 0
    private var ticks = 0
    private var unseen = 0
    private var announced = false
    private var walks = 0
    private var inCutscene = false
    private var clearTicks = 0
    private var waited = 0

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val me = game.playerTile ?: return TaskResult.Running
        if (++ticks > MAX_TICKS) return TaskResult.Failed("following the guard took over $MAX_TICKS ticks")
        // Clicks do nothing in a cutscene, so wait it out and carry on when the player has control again.
        val cutscene = game.varbit(CUTSCENE_VARBIT) != 0
        if (cutscene != inCutscene) {
            inCutscene = cutscene
            ctx.log(if (cutscene) "A cutscene is playing, waiting" else "The cutscene has ended, following the guard")
        }
        if (cutscene) { ticks--; return TaskResult.Running }
        val guard = game.nearestNpc(guardIds)
        if (guard == null) {
            // Out of sight behind a wall counts for the later stops: he has walked on.
            unseen++
            if (unseen == 1 || unseen % 10 == 0) ctx.log("Cannot see the guard (ids $guardIds), $unseen ticks, at stop ${index + 1} of ${hides.size}")
            if (unseen >= LOST_TICKS) return TaskResult.Failed("lost sight of the guard at stop ${index + 1} of ${hides.size}")
            if (index >= hides.size) return TaskResult.Done
            // Not seen yet, or out of sight: still get to the hiding place, so the bot is never left standing.
            val hide = hides[index]
            if (me.distanceTo(hide) > 0 && !game.isMoving) return attempt(ctx.actions.walkTo(hide), "walk to hiding place $hide")
            return TaskResult.Running
        }
        unseen = 0
        if (!announced) {
            announced = true
            ctx.log("Following the guard (ids $guardIds) at ${guard.tile}, stops ${hides.joinToString()}")
        }
        if (index >= hides.size) return TaskResult.Done

        val hide = hides[index]
        if (me.distanceTo(hide) > 0) {
            if (game.isMoving) return TaskResult.Running
            if (++walks % 5 == 1) ctx.log("Walking to hiding place ${index + 1} at $hide, guard at ${guard.tile}")
            return attempt(ctx.actions.walkTo(hide), "walk to hiding place $hide")
        }
        // Hidden: wait for the guard to move on. He has when he is clear of this place and nearer the
        // next one, or so far ahead that waiting longer would lose him.
        val next = hides.getOrNull(index + 1)
        val clear = guard.tile.distanceTo(me) >= CLEAR_OF_HIDE
        val ahead = next == null || guard.tile.distanceTo(next) < hide.distanceTo(next)
        val losing = guard.tile.distanceTo(me) >= TOO_FAR
        waited++
        // Twice in a row, so one tick of the guard turning does not set the bot off early.
        clearTicks = if (clear && ahead) clearTicks + 1 else 0
        if (clearTicks >= 2 || losing || waited >= MAX_WAIT) {
            index++
            waited = 0
            clearTicks = 0
            ctx.log("Guard at ${guard.tile}, moving on to stop ${index + 1} of ${hides.size}")
        }
        return TaskResult.Running
    }

    companion object {
        const val MAX_TICKS = 600
        /** VarbitID.CUTSCENE_STATUS: non zero while the game has taken control of the camera and player. */
        const val CUTSCENE_VARBIT = 542
        const val LOST_TICKS = 40
        const val CLEAR_OF_HIDE = 8
        /** Ticks to wait hidden before moving on regardless: the guard may be waiting for the player to come on. */
        const val MAX_WAIT = 30
        const val TOO_FAR = 12
    }
}


/**
 * Children of the Sun's guard marking in Varrock Square. Each guard to mark is told by its NPC id before
 * marking (the id goes up by one once marked), and the one marked by mistake is unmarked. Only an exact
 * id is ever clicked: another guard standing in for a missing one would be marked wrongly.
 */
class MarkGuardsTask(
    private val toMark: List<Int> = TO_MARK,
    private val markedWrongly: List<Int> = MARKED_WRONGLY,
) : BaseTask() {
    override val description = "Mark the guards"
    private var ticks = 0
    private var settling = 0
    private val attempts = mutableMapOf<Int, Int>()
    private var acted = false
    private var missing = 0
    private var walkLogs = 0

    private var report: InteractTask? = null

    private fun reportBack(helper: com.hcimbot.game.HelperStep, ctx: TaskContext): TaskResult {
        if (helper.npcIds.isEmpty() || !REPORT.containsMatchIn(helper.text)) return TaskResult.Done
        if (report == null) ctx.log("Quest Helper says: ${helper.text.take(80)} (npc ${helper.npcIds}, at ${helper.tile})")
        val talk = report ?: InteractTask(InteractTask.Kind.NPC, helper.npcIds, emptyList(), listOfNotNull(helper.tile ?: helper.npcIds.firstNotNullOfOrNull(KNOWN_PLACES::get)), "Talk-to").also { report = it }
        return talk.tick(ctx)
    }

    private fun exact(game: com.hcimbot.game.GameState, id: Int) = game.nearestNpc(listOf(id))?.takeIf { it.id == id }

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (++ticks > MAX_TICKS) return TaskResult.Failed("marking the guards took over $MAX_TICKS ticks")
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy) return TaskResult.Running
        val me = game.playerTile ?: return TaskResult.Running

        // Quest Helper points at the guard to mark, and later at the one to unmark: do what it shows.
        val helper = game.helperStep("childrenofthesun")
        val next: Pair<com.hcimbot.game.SceneEntity, String>? = if (helper != null) {
            val action = when {
                helper.text.contains("unmark", ignoreCase = true) -> "Unmark"
                helper.text.startsWith("Mark", ignoreCase = true) -> "Mark"
                // Nothing (more) to mark: Quest Helper has moved on, whether the bot or the player marked them.
                // The quest's own progress value stays put until the report, so do that here when it is next.
                else -> return reportBack(helper, ctx)
            }
            // The ids Quest Helper names are not always the ones the world shows for a guard that changes
            // with the quest (12677 against 12686), so fall back to the guard standing where it points.
            val found = helper.npcIds.firstNotNullOfOrNull { id -> exact(game, id) }
                ?: helper.tile?.let { at -> game.nearestNpc(emptyList(), listOf("Guard"), at)?.takeIf { it.tile.distanceTo(at) <= AT_POINT } }
            if (found == null) {
                // Not in the game's list of nearby NPCs: it is too far or out of view. Go to where Quest Helper points.
                val at = helper.tile
                if (at != null && me.distanceTo(at) > CLICK_RANGE) {
                    if (game.isMoving) return TaskResult.Running
                    if (++walkLogs % 5 == 1) ctx.log("Cannot see the guard ${helper.npcIds} yet, walking to $at")
                    return attempt(ctx.actions.walkTo(at), "walk to where Quest Helper points, $at")
                }
                return if (++missing >= LOST_TICKS) TaskResult.Failed("cannot see the guard Quest Helper points at: ${helper.npcIds} at ${helper.tile}") else TaskResult.Running
            }
            found to action
        } else {
            toMark.firstNotNullOfOrNull { id -> exact(game, id)?.let { it to "Mark" } }
                ?: markedWrongly.firstNotNullOfOrNull { id -> exact(game, id)?.let { it to "Unmark" } }
        }
        missing = 0
        if (next == null) {
            val unseen = toMark.filter { exact(game, it + 1) == null }
            return if (unseen.isEmpty()) TaskResult.Done
            else TaskResult.Failed("cannot see guards $unseen, marked or unmarked, to check they are marked")
        }
        val (guard, action) = next
        val id = guard.id
        if (guard.tile.distanceTo(me) > CLICK_RANGE) {
            return attempt(ctx.actions.walkTo(guard.tile), "walk to the guard $id")
        }
        if (attempts.merge(id, 1, Int::plus)!! > MAX_ATTEMPTS) return TaskResult.Failed("clicking $action on guard $id did not work")
        acted = true
        ctx.log("$action guard $id")
        // A click that did not happen (the guard is off screen behind a building) is answered by walking up to him.
        if (!ctx.actions.interactNpc(guard, action)) {
            ctx.log("Could not click $action on guard $id, walking closer")
            ctx.actions.walkTo(guard.tile)
        }
        settling = SETTLE
        return TaskResult.Running
    }

    companion object {
        /** Unmarked ids of the four guards the quest wants marked. */
        val TO_MARK = listOf(12668, 12671, 12677, 12674)

        /** The guard marked by mistake (its marked id), to unmark. Not known yet, so the bot leaves it to the player. */
        val MARKED_WRONGLY = emptyList<Int>()
        const val MAX_TICKS = 300
        const val LOST_TICKS = 40
        const val AT_POINT = 4

        /** Where an NPC Quest Helper names stands, for when it gives no point: Sergeant Tobyn in Varrock Square. */
        private val KNOWN_PLACES = mapOf(12648 to Tile(3211, 3437, 0))
        private val REPORT = Regex("^(report back|talk to)", RegexOption.IGNORE_CASE)
        const val MAX_ATTEMPTS = 4
        const val CLICK_RANGE = 4
        const val SETTLE = 4
    }
}


/**
 * Does what the Quest Helper plugin shows, one object at a time, for as long as its instruction matches
 * [wanted] ("Demolish the broken stool", "Remove the campbed"). The verb comes from its text. Quest Helper
 * moves on to the next piece when one is done, so this never has to know which is left, or what a piece
 * turns into (a removed piece becomes somewhere to build, and a left click on that would build).
 */
class HelperObjectsTask(private val wanted: Regex, private val quest: String, private val maxActions: Int = 40) : BaseTask() {
    override val description = "Do what Quest Helper shows"
    private var actions = 0
    private var settling = 0
    private var missing = 0
    private val tries = mutableMapOf<Int, Int>()

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy) return TaskResult.Running
        val shown = game.helperStep(quest) ?: return TaskResult.Failed("Quest Helper is not showing a step for $quest. Select it in the Quest Helper panel")
        if (!wanted.containsMatchIn(shown.text)) return TaskResult.Done
        if (actions >= maxActions) return TaskResult.Failed("did $maxActions things for \"${shown.text.take(50)}\" and Quest Helper still shows it")
        val verb = if (shown.text.contains("demolish", ignoreCase = true)) "Demolish" else "Remove"
        val at = game.playerTile ?: return TaskResult.Running
        val target = shown.objectIds.takeIf { it.isNotEmpty() }?.let { game.nearestObject(it, near = shown.tile) }
        if (target == null) {
            val place = shown.tile
            if (place != null && at.distanceTo(place) > 3 && !game.isMoving) return attempt(ctx.actions.walkTo(place), "walk to $place")
            return if (++missing >= 20) TaskResult.Failed("cannot see the object Quest Helper points at: ${shown.objectIds}") else TaskResult.Running
        }
        missing = 0
        if (tries.merge(target.id, 1, Int::plus)!! > 6) return TaskResult.Failed("$verb on object ${target.id} did not work")
        ctx.log("$verb object ${target.id}: ${shown.text.take(60)}")
        actions++
        ctx.actions.interactObject(target, verb)
        settling = 4
        return TaskResult.Running
    }
}


/**
 * Fletches [target] arrow shafts from logs chopped from the trees around the Lumber Yard, from outside its pen:
 * chops the nearest tree it can reach, and once it holds a good number of logs uses the knife on them and picks
 * All and the first item in the make window. With no tree in reach it hops worlds. Pauses if it makes no
 * progress for a long time.
 */
class FletchShaftsTask(
    private val target: Int = 2100,
    private val knife: Int = KNIFE,
    private val logs: Int = LOGS,
    private val shaft: Int = ARROW_SHAFT,
) : BaseTask() {
    override val description = "Fletch $target arrow shafts"

    /**
     * The guide step reads "Collect the Logs", which holding any logs already satisfies, so the runner would
     * call the step done before a single shaft is made. Until the target is reached this task is not over.
     */
    override fun canStopHere(ctx: TaskContext): Boolean = ctx.conditions.count(listOf(shaft)) >= target

    private var settling = 0
    private var noTree = 0
    private var hopWait = 0
    private var lastProgress = -1
    private var stalled = 0
    private var hops = 0
    private var fenceTries = 0
    private var checkInside = true

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val made = ctx.conditions.count(listOf(shaft)).toInt()
        if (made >= target) return TaskResult.Done
        // Progress is shafts made or logs gained.
        val held = game.inventory()[logs] ?: 0
        val progress = made * 100 + held
        if (progress != lastProgress) { lastProgress = progress; stalled = 0 } else if (++stalled > STALL_TICKS) {
            return TaskResult.Failed("no logs chopped or shafts made for $STALL_TICKS ticks, stuck at $made of $target")
        }
        if (hopWait > 0) { hopWait--; return TaskResult.Running }
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy || game.isBankOpen || game.isShopOpen) return TaskResult.Running
        val here = game.playerTile ?: return TaskResult.Running
        if (game.showsChoice("What would you like to make?")) {
            ctx.actions.makeAll()
            settling = 3
            return TaskResult.Running
        }
        if (knife !in game.inventory()) return TaskResult.Failed("no knife to fletch with")

        // A good number of logs at a time: every click on a tree gives one, and the make window is the slow part.
        if (held >= HANDFUL) {
            noTree = 0
            ctx.actions.useItemOnItem(knife, logs)
            settling = 3
            return TaskResult.Running
        }
        val at = here
        // Step 103 left the player inside the pen, by the dying tree. The trees to chop are outside it. The dying
        // trees are only inside the pen, so one that can be walked up to means the bot is inside: climb back
        // over the broken fence then, and never when it is already outside.
        if (checkInside) {
            checkInside = false
            val inside = PenCheck.inside(game, at)
            if (inside && fenceTries < MAX_FENCE_TRIES) {
                val fence = game.nearestObject(emptyList(), listOf("Broken fence"), at)
                if (fence != null) {
                    fenceTries++
                    ctx.log("Inside the pen, climbing back over the broken fence")
                    ctx.actions.interactObject(fence, "Climb-over")
                    settling = 4
                    checkInside = true
                    return TaskResult.Running
                }
            }
        }
        // The nearest tree that can be reached from where the bot stands: one inside the pen cannot. Nearest first,
        // and stop at the first that can: working out whether a tree can be reached is slow.
        // Only trees near the sawmill: the bot should not wander off across the countryside for one.
        val tree = game.objectsNear(TREES, SAWMILL, TREE_LEASH)
            .sortedBy { it.tile.distanceTo(at) }
            .firstOrNull { game.canReachBeside(it.tile) != false }
        if (tree != null) {
            noTree = 0
            return attempt(ctx.actions.interactObject(tree, "Chop down"), "chop down the tree at ${tree.tile}").also { settling = 1 }
        }
        // Nothing to chop in reach: use up what there is, else another world.
        if (held > 0) {
            ctx.actions.useItemOnItem(knife, logs)
            settling = 3
            return TaskResult.Running
        }
        if (++noTree < NO_TREE_TICKS) return TaskResult.Running
        noTree = 0
        hops++
        ctx.log("No tree in reach here, hopping worlds ($made of $target shafts, hop $hops)")
        ctx.actions.hopWorld()
        hopWait = HOP_TICKS
        checkInside = true
        return TaskResult.Running
    }

    companion object {
        const val KNIFE = 946
        const val LOGS = 1511
        const val ARROW_SHAFT = 52
        const val HANDFUL = 18
        const val TREE_RADIUS = 25
        val DYING_TREE = listOf("Dying tree")
        val SAWMILL = Tile(3302, 3492, 0)
        const val TREE_LEASH = 14
        const val MAX_FENCE_TRIES = 2
        val TREES = listOf("Tree")
        const val NO_TREE_TICKS = 6
        const val HOP_TICKS = 12
        const val STALL_TICKS = 400
    }
}


/**
 * Picks up logs lying inside the Lumber Yard until [count] are held, only ones that can be walked to: a pile behind
 * a crate or a fence stays where it is, and a pile that several clicks do not get is given up on.
 */
class TakeYardLogsTask(private val count: Int, private val logs: Int = 1511) : BaseTask() {
    override val description = "Take $count logs from the yard"
    private var settling = 0
    private val tries = mutableMapOf<Tile, Int>()
    private val given = mutableSetOf<Tile>()
    private var lastHeld = -1
    private var noPile = 0

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val held = game.inventory()[logs] ?: 0
        if (held >= count) return TaskResult.Done
        if (held != lastHeld) { lastHeld = held; tries.clear() }
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy) return TaskResult.Running
        val at = game.playerTile ?: return TaskResult.Running
        val pile = game.groundItemsNear(listOf(logs), CENTRE, RADIUS)
            .filter { it.tile !in given && !it.tile.inWilderness && game.canReach(it.tile) != false }
            .minByOrNull { it.tile.distanceTo(at) }
        if (pile == null) {
            return if (++noPile >= WAIT_TICKS) TaskResult.Failed("no log pile left in the yard that can be reached ($held of $count)") else TaskResult.Running
        }
        noPile = 0
        val n = tries.merge(pile.tile, 1, Int::plus)!!
        if (n > MAX_TRIES) {
            ctx.log("Giving up on the logs at ${pile.tile}, they cannot be taken")
            given += pile.tile
            return TaskResult.Running
        }
        settling = 1
        return attempt(ctx.actions.takeGroundItem(pile), "take the logs at ${pile.tile}")
    }

    companion object {
        val CENTRE = Tile(3302, 3497, 0)
        const val RADIUS = 13
        const val MAX_TRIES = 4
        const val WAIT_TICKS = 8
    }
}


/**
 * Climbs out of the Lumber Yard's pen over the broken fence, when inside it. The dying trees are only inside the
 * pen, so one that can be walked up to means the bot is inside. Done at once when outside.
 */
class LeavePenTask(private val maxTries: Int = 2) : BaseTask() {
    override val description = "Climb out of the pen"
    private var tries = 0
    private var settling = 0

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy) return TaskResult.Running
        val at = game.playerTile ?: return TaskResult.Running
        if (!PenCheck.inside(game, at)) return TaskResult.Done
        if (tries >= maxTries) {
            ctx.log("Still looks to be inside the pen after $maxTries climbs, carrying on")
            return TaskResult.Done
        }
        val fence = game.nearestObject(emptyList(), listOf("Broken fence"), at) ?: return TaskResult.Done
        tries++
        ctx.log("Climbing out of the pen over the broken fence")
        ctx.actions.interactObject(fence, "Climb-over")
        settling = 4
        return TaskResult.Running
    }
}


/**
 * Whether the player is inside the Lumber Yard's pen, by position. Walking maps say the dying tree can be reached
 * from both sides (the broken fence is a gap in them), so that cannot tell. The dying trees are only inside the pen,
 * and the broken fence is its way out: inside means closer to the dying tree than the fence is.
 */
object PenCheck {
    fun inside(game: com.hcimbot.game.GameState, at: Tile): Boolean {
        val tree = game.objectsNear(listOf("Dying tree"), at, RADIUS).minByOrNull { it.tile.distanceTo(at) } ?: return false
        val fence = game.nearestObject(emptyList(), listOf("Broken fence"), tree.tile) ?: return false
        return squared(at, tree.tile) < squared(fence.tile, tree.tile)
    }

    private fun squared(a: Tile, b: Tile): Int {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private const val RADIUS = 30
}


/**
 * Makes planks at the sawmill operator: [planks] planks from logs, and [waxwood] waxwood planks from waxwood logs.
 * "Buy-plank" on the operator opens a window of plank types, the plain plank first and the waxwood plank last, and
 * what each costs is taken from the coins. Done once the planks are held.
 */
class MakePlanksTask(private val planks: Int = 10, private val waxwood: Int = 3) : BaseTask() {
    override val description = "Make $planks planks and $waxwood waxwood planks"
    private var opener: InteractTask? = null
    private var opens = 0
    private var settling = 0
    private var clicks = 0
    private var lastProgress = -1
    private var announced = false
    private var startLogs = -1

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val plain = ctx.conditions.count(listOf(PLANK)).toInt()
        val wax = ctx.conditions.count(listOf(WAXWOOD_PLANK)).toInt()
        val window = game.showsChoice(WINDOW)
        val logsNow = game.inventory()[LOGS] ?: 0
        if (startLogs < 0) startLogs = logsNow
        // Ten logs used up means the ten planks were made, whatever the count of planks reads.
        val plainDone = plain >= planks || startLogs - logsNow >= planks
        if (!announced) {
            announced = true
            ctx.log("Making planks: holding $plain of $planks planks and $wax of $waxwood waxwood planks, " +
                "${game.inventory()[LOGS] ?: 0} logs, ${game.inventory()[WAXWOOD_LOG] ?: 0} waxwood logs")
        }
        if (plainDone && wax >= waxwood) {
            return if (window) attempt(ctx.actions.closeWindow(), "close the plank window") else TaskResult.Done
        }
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy) return TaskResult.Running
        if (!window) {
            val talk = opener ?: InteractTask(InteractTask.Kind.NPC, listOf(OPERATOR), listOf("Sawmill operator"), listOf(SAWMILL), "Buy-plank", ignoreReach = true).also { opener = it }
            return when (val result = talk.tick(ctx)) {
                TaskResult.Done -> {
                    opener = null
                    if (++opens > MAX_OPENS) TaskResult.Failed("Buy-plank did not open the plank window") else TaskResult.Running
                }
                else -> result
            }
        }
        val progress = plain * 1000 + wax + (startLogs - logsNow) * 100000
        if (progress != lastProgress) { lastProgress = progress; clicks = 0 }
        if (++clicks > MAX_CLICKS) return TaskResult.Failed("clicked in the plank window $MAX_CLICKS times and no more planks were made")
        val inventory = game.inventory()
        when {
            !plainDone -> {
                if ((inventory[LOGS] ?: 0) == 0) return TaskResult.Failed("no logs to make the planks from")
                ctx.actions.makePick("10", 1)
            }
            else -> {
                if ((inventory[WAXWOOD_LOG] ?: 0) == 0) return TaskResult.Failed("no waxwood logs to make the waxwood planks from")
                ctx.actions.makePick("All", WAXWOOD_ITEM)
            }
        }
        settling = 4
        return TaskResult.Running
    }

    companion object {
        const val WINDOW = "How many do you wish to make?"
        const val OPERATOR = 3101
        val SAWMILL = Tile(3302, 3492, 0)
        const val LOGS = 1511
        const val PLANK = 960
        const val WAXWOOD_LOG = 24938
        const val WAXWOOD_PLANK = 24939
        /** The waxwood plank is the last of the window's eight. */
        const val WAXWOOD_ITEM = 8
        const val MAX_OPENS = 4
        const val MAX_CLICKS = 6
    }
}


/**
 * Takes an item that has not appeared yet: waits near [around] for it to show up on the ground, then takes it. For the
 * ashes a fire leaves once its log has burnt away, which takes a while. Pauses after [maxTicks] with none.
 */
class WaitAndTakeTask(
    private val itemId: Int,
    private val around: Tile,
    private val maxTicks: Int = 300,
    private val radius: Int = 10,
) : BaseTask() {
    override val description = "Wait for item $itemId and take it"
    private var waited = 0
    private var settling = 0
    private var walk: WalkTask? = null

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (itemId in game.inventory()) return TaskResult.Done
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy) return TaskResult.Running
        val at = game.playerTile ?: return TaskResult.Running
        val item = game.groundItemsNear(listOf(itemId), around, radius).minByOrNull { it.tile.distanceTo(at) }
        if (item != null) {
            settling = 2
            return attempt(ctx.actions.takeGroundItem(item), "take item $itemId at ${item.tile}")
        }
        if (at.plane != around.plane || at.distanceTo(around) > radius) {
            val going = walk ?: WalkTask(around, radius = 3).also { walk = it }
            return when (val walked = going.tick(ctx)) {
                TaskResult.Done -> { walk = null; TaskResult.Running }
                else -> walked
            }
        }
        if (waited == 0) ctx.log("Waiting for item $itemId to appear")
        return if (++waited >= maxTicks) TaskResult.Failed("item $itemId did not appear within $maxTicks ticks") else TaskResult.Running
    }
}


/** Uses [action] on an inventory item known only by part of its name ("crate"). Pauses if there is none. */
class ItemByNameTask(private val part: String, private val action: String) : BaseTask() {
    override val description = "$action the $part"
    private var used = false
    private var settling = 0

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (game.canContinueDialog) return attempt(ctx.actions.continueDialog(), "continue the dialogue")
        if (game.isBusy) return TaskResult.Running
        if (used) return if (++settling >= SETTLE) TaskResult.Done else TaskResult.Running
        used = ctx.actions.itemActionByName(part, action)
        return if (used) TaskResult.Running else TaskResult.Failed("no $part in the bag to $action")
    }

    private companion object {
        const val SETTLE = 3
    }
}


/** Clicks the first window entry whose text is one of [texts] (a destination list), waiting up to [waitTicks] for it. */
class ClickChoiceTask(private val texts: List<String>, private val waitTicks: Int = 20) : BaseTask() {
    override val description = "Choose ${texts.first()}"
    private var waited = 0
    private var clicked = false

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (clicked) return TaskResult.Done
        if (game.isBusy) return TaskResult.Running
        val text = texts.firstOrNull { game.showsChoice(it) }
        if (text != null) {
            clicked = ctx.actions.clickChoice(text)
            return attempt(clicked, "click $text")
        }
        return if (++waited >= waitTicks) TaskResult.Failed("none of ${texts} is showing to choose") else TaskResult.Running
    }
}

/** Waits until [reached] holds, up to [maxTicks], for something that takes a while like a journey. */
/**
 * Waits until [reached] holds, up to [maxTicks], for something that takes a while like a journey. Any chat that opens in the
 * meantime is dealt with by [dialogue] (the talk that starts a journey can still be open when this begins).
 */
class WaitUntilTask(
    private val label: String,
    private val maxTicks: Int,
    private val dialogue: DialoguePlan = DialoguePlan.None,
    private val reached: (TaskContext) -> Boolean,
) : BaseTask() {
    override val description = label
    private var waited = 0

    override fun tick(ctx: TaskContext): TaskResult {
        if (reached(ctx)) return TaskResult.Done
        val game = ctx.game
        if (game.canContinueDialog) {
            waited = 0
            return attempt(ctx.actions.continueDialog(), "continue the dialogue")
        }
        val options = game.dialogOptions()
        if (options.isNotEmpty()) {
            waited = 0
            return when (val choice = dialogue.choose(options)) {
                is DialoguePlan.Choice.ByIndex -> attempt(ctx.actions.chooseDialogOption(choice.index), "pick option ${choice.index}")
                is DialoguePlan.Choice.ByText -> attempt(ctx.actions.chooseDialogOption(choice.text), "pick \"${choice.text}\"")
                is DialoguePlan.Choice.Stuck -> TaskResult.Failed(choice.reason)
            }
        }
        return if (++waited >= maxTicks) TaskResult.Failed("$label: not there after $maxTicks ticks") else TaskResult.Running
    }
}


/**
 * Climbs a ladder on the current floor other than the one the player arrived by: the nearest "Ladder" is the one just
 * climbed, and clicking it goes back down. Done once on a higher floor than it started on.
 */
class ClimbOtherLadderTask(private val option: String = "Climb-up", private val awayFrom: Int = 3) : BaseTask() {
    override val description = "Climb the other ladder"
    private var startPlane = -1
    private var start: Tile? = null
    private var settling = 0
    private var clicks = 0

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val at = game.playerTile ?: return TaskResult.Running
        if (startPlane < 0) { startPlane = at.plane; start = at }
        if (at.plane > startPlane) return TaskResult.Done
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy) return TaskResult.Running
        val from = start ?: at
        val ladder = game.objectsNear(listOf("Ladder"), from, 30)
            .filter { it.tile.distanceTo(from) > awayFrom && it.tile.plane == at.plane }
            .minByOrNull { it.tile.distanceTo(at) }
            ?: return TaskResult.Failed("no second ladder on this floor, away from the one just climbed (${at})")
        if (++clicks > MAX_CLICKS) return TaskResult.Failed("clicking the ladder at ${ladder.tile} did not take the player up")
        ctx.log("Climbing the other ladder at ${ladder.tile}")
        ctx.actions.interactObject(ladder, option)
        settling = 4
        return TaskResult.Running
    }

    private companion object {
        const val MAX_CLICKS = 5
    }
}


/**
 * Collects [count] of an item that spawns where the player stands (the Eclipse red bottles upstairs in the Hunter Guild),
 * hopping to another world after each one picked up, since the spawn is gone from this world once taken and is back on the
 * next. With none in sight it hops too, after a short wait. Pauses if it gets nowhere for a long time.
 */
class LootAndHopTask(private val itemId: Int, private val count: Int, private val reach: Int = 12) : BaseTask() {
    override val description = "Loot $count of item $itemId, hopping after each"
    private var lastHeld = -1L
    private var loadingFrom: Int? = null
    private var loadTicks = 0
    private var settling = 0
    private var noItem = 0
    private var idle = 0
    private var hops = 0
    /** World to when the bot was last on it, so a world is not hopped back to within a minute. */
    private val visited = HashMap<Int, Long>()

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val held = ctx.conditions.count(listOf(itemId))
        if (held >= count) return TaskResult.Done
        // After a hop, wait for the new world itself rather than for a set time: it is here once the world number has
        // changed and the player is back, and the item is gone for at once from then on.
        if (loadingFrom != null) {
            val arrived = game.isLoggedIn && game.playerTile != null && game.world > 0 && game.world != loadingFrom
            if (!arrived && ++loadTicks < MAX_LOAD_TICKS) return TaskResult.Running
            loadingFrom = null
            noItem = 0
        }
        if (settling > 0) { settling--; return TaskResult.Running }
        // Not while the world is still loading: no player, no tile, nothing to do yet.
        if (!game.isLoggedIn || game.playerTile == null) return TaskResult.Running
        if (game.isBusy || game.isBankOpen || game.isShopOpen) return TaskResult.Running
        if (lastHeld < 0) lastHeld = held
        // One more than before: it was just picked up, so go to another world for the next.
        if (held > lastHeld) {
            lastHeld = held
            idle = 0
            return hop(ctx, "picked up $held of $count")
        }
        if (++idle > MAX_IDLE_TICKS) return TaskResult.Failed("no progress after $MAX_IDLE_TICKS ticks, holding $held of $count")
        val at = game.playerTile ?: return TaskResult.Running
        val item = game.nearestGroundItem(listOf(itemId))?.takeIf { it.tile.plane == at.plane && it.tile.distanceTo(at) <= reach }
        if (item != null) {
            noItem = 0
            return attempt(ctx.actions.takeGroundItem(item), "take item $itemId at ${item.tile}")
        }
        // Nothing here: a moment for one that is about to show, then another world.
        if (++noItem < NO_ITEM_TICKS) return TaskResult.Running
        noItem = 0
        return hop(ctx, "none in sight")
    }

    private fun hop(ctx: TaskContext, why: String): TaskResult {
        hops++
        val now = System.currentTimeMillis()
        val here = ctx.game.world
        if (here > 0) visited[here] = now
        val avoid = visited.filter { now - it.value < AVOID_MS }.keys
        ctx.log("Hopping worlds ($why, hop $hops, not back to ${avoid.sorted()})")
        ctx.actions.hopWorldAvoiding(avoid)
        loadingFrom = if (here > 0) here else -2
        loadTicks = 0
        noItem = 0
        return TaskResult.Running
    }

    private companion object {
        // Short: the world takes its time to load on its own, and the item is looked for every tick from then on.
        /** Give up waiting for the world number to change after this many ticks, and look anyway. */
        const val MAX_LOAD_TICKS = 40
        /** A world just left is not gone back to for this long, whatever the bot finds there. */
        const val AVOID_MS = 60_000L
        // How long after a hop with nothing in sight before concluding it is not there and hopping again.
        const val NO_ITEM_TICKS = 6
        const val MAX_IDLE_TICKS = 400
    }
}


/**
 * Collects several items that lie about (a fishing rod, a pot, three iron bars...), taking whichever of those still needed is
 * in sight, nearest first, and hopping to another world when none is: a spawn taken here is back on the next world. Never
 * back to a world visited in the last minute, and it acts as soon as the new world has loaded.
 */
class LootManyAndHopTask(private val wants: Map<Int, Int>, private val reach: Int = 30) : BaseTask() {
    override val description = "Loot ${wants.keys}, hopping when none is in sight"
    private var loadingFrom: Int? = null
    private var loadTicks = 0
    private var noItem = 0
    private var idle = 0
    private var hops = 0
    private var lastTotal = -1L
    private val visited = HashMap<Int, Long>()

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val missing = wants.filter { (id, count) -> ctx.conditions.count(listOf(id)) < count }
        if (missing.isEmpty()) return TaskResult.Done
        if (loadingFrom != null) {
            val arrived = game.isLoggedIn && game.playerTile != null && game.world > 0 && game.world != loadingFrom
            if (!arrived && ++loadTicks < MAX_LOAD_TICKS) return TaskResult.Running
            loadingFrom = null
            noItem = 0
        }
        if (!game.isLoggedIn || game.playerTile == null) return TaskResult.Running
        if (game.isBusy || game.isBankOpen || game.isShopOpen) return TaskResult.Running
        val total = wants.keys.sumOf { ctx.conditions.count(listOf(it)) }
        if (total != lastTotal) { lastTotal = total; idle = 0 }
        if (++idle > MAX_IDLE_TICKS) return TaskResult.Failed("no progress after $MAX_IDLE_TICKS ticks, still needing ${missing.keys}")
        val at = game.playerTile ?: return TaskResult.Running
        val item = missing.keys.mapNotNull { id -> game.nearestGroundItem(listOf(id))?.takeIf { it.tile.plane == at.plane && it.tile.distanceTo(at) <= reach } }
            .minByOrNull { it.tile.distanceTo(at) }
        if (item != null) {
            noItem = 0
            return attempt(ctx.actions.takeGroundItem(item), "take ${item.name} at ${item.tile}")
        }
        if (++noItem < NO_ITEM_TICKS) return TaskResult.Running
        hops++
        val now = System.currentTimeMillis()
        val here = game.world
        if (here > 0) visited[here] = now
        val avoid = visited.filter { now - it.value < AVOID_MS }.keys
        ctx.log("Hopping worlds (still need ${missing.entries.joinToString { "${it.key}x${it.value}" }}, hop $hops, not back to ${avoid.sorted()})")
        ctx.actions.hopWorldAvoiding(avoid)
        loadingFrom = if (here > 0) here else -2
        loadTicks = 0
        noItem = 0
        return TaskResult.Running
    }

    private companion object {
        const val MAX_LOAD_TICKS = 40
        const val NO_ITEM_TICKS = 6
        const val MAX_IDLE_TICKS = 600
        const val AVOID_MS = 60_000L
    }
}

/**
 * Buys more than the inventory holds, in trips: fill the free slots from a shop, bank what was bought (the coins and the
 * rest of the bag stay), and go back, until [wanted] have all been bought. Each item is looked for in the [sellers] in
 * turn, and a seller that does not sell it, or cannot be paid, is not tried again for that item.
 */
class StockUpTask(
    private val wanted: List<Want>,
    private val sellers: List<ShopTask.Seller>,
    private val maxTrips: Int = 30,
) : Task {
    class Want(val name: String, val id: Int, val total: Int)

    private val stored = HashMap<Int, Int>()
    private val pending = HashMap<Int, Int>()
    private val refused = HashMap<Int, MutableSet<Int>>()
    private var buying: Task? = null
    private var buyingId = 0
    private var buyingSeller = 0
    private var banking = false
    private var trips = 0
    override val description = "Buy ${wanted.joinToString { "${it.total} ${it.name}" }}, banking between trips"

    private fun held(ctx: TaskContext, id: Int) = ctx.game.inventory()[id] ?: 0
    private fun remaining(ctx: TaskContext, w: Want) = w.total - (stored[w.id] ?: 0) - held(ctx, w.id)
    private fun freeSlots(ctx: TaskContext) = 28 - ctx.game.inventory().entries.sumOf { if (it.key == COINS) 1 else it.value }

    override fun tick(ctx: TaskContext): TaskResult {
        if (ctx.game.isBusy) return TaskResult.Running
        if (banking) return bank(ctx)
        buying?.let { task ->
            return when (val result = task.tick(ctx)) {
                TaskResult.Done -> { buying = null; TaskResult.Running }
                is TaskResult.Failed -> {
                    ctx.log("${sellers[buyingSeller].name} cannot sell ${wanted.first { it.id == buyingId }.name}: ${result.reason}")
                    refused.getOrPut(buyingId) { mutableSetOf() } += buyingSeller
                    buying = null
                    TaskResult.Running
                }
                else -> result
            }
        }
        val todo = wanted.filter { remaining(ctx, it) > 0 }
        if (todo.isEmpty()) return if (ctx.game.isShopOpen) { ctx.actions.closeInterface(); TaskResult.Running } else TaskResult.Done
        val free = freeSlots(ctx)
        val next = todo.firstOrNull { w -> sellers.indices.any { it !in refused[w.id].orEmpty() } && free > 0 }
        if (next == null) {
            val stuck = todo.firstOrNull { w -> sellers.indices.all { it in refused[w.id].orEmpty() } }
            if (stuck != null) return TaskResult.Failed("no shop here sells ${stuck.name}")
            // The bag is full: bank it.
            if (++trips > maxTrips) return TaskResult.Failed("still short after $maxTrips trips")
            ctx.log("Bag full, banking (trip $trips)")
            banking = true
            return TaskResult.Running
        }
        val seller = sellers.indices.first { it !in refused[next.id].orEmpty() }
        val amount = minOf(remaining(ctx, next), free)
        buyingId = next.id
        buyingSeller = seller
        buying = ShopTask(listOf(sellers[seller]), listOf(ItemRef(next.name, listOf(next.id), held(ctx, next.id) + amount)))
        return TaskResult.Running
    }

    private fun bank(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (!game.isBankOpen) { ctx.actions.openNearestBank(); return TaskResult.Running }
        for ((id, n) in pending.entries.toList()) if (held(ctx, id) == 0) { stored.merge(id, n, Int::plus); pending.remove(id) }
        val next = wanted.firstOrNull { held(ctx, it.id) > 0 && it.id !in pending }
        if (next != null) {
            val h = held(ctx, next.id)
            if (ctx.actions.depositItem(next.id)) pending[next.id] = h
            return TaskResult.Running
        }
        if (pending.isNotEmpty()) return TaskResult.Running
        ctx.actions.closeInterface()
        banking = false
        return TaskResult.Running
    }

    private companion object {
        const val COINS = 995
    }
}

/**
 * Uses [first] on [second] and makes all of [product] from the window that opens, until [target] of it are held. For headless
 * arrows (feathers on arrow shafts), where each use makes a whole inventory's worth.
 */
class MakeAllTask(
    private val first: Int,
    private val second: Int,
    private val product: Int,
    private val target: Int,
    private val label: String,
) : BaseTask() {
    override val description = "Make $target $label"
    private var settling = 0
    private var idle = 0
    private var lastMade = -1L
    private var sinceChange = QUIET_TICKS
    private var started = false

    override fun canStopHere(ctx: TaskContext): Boolean = ctx.conditions.count(listOf(product)) >= target

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val made = ctx.conditions.count(listOf(product))
        if (made >= target) return TaskResult.Done
        if (made != lastMade) { lastMade = made; idle = 0; sinceChange = 0 } else {
            sinceChange++
            idle++
        }
        if (idle > STALL_TICKS) {
            return TaskResult.Failed("no progress making $label, stuck at $made of $target")
        }
        if (settling > 0) { settling--; return TaskResult.Running }
        if (game.isBusy || game.isBankOpen || game.isShopOpen) return TaskResult.Running
        if (game.showsMakeWindow()) {
            ctx.actions.makeAll()
            started = true
            sinceChange = 0
            settling = 3
            return TaskResult.Running
        }
        // Making goes on for a while after the click, and the animation is not always showing as busy: wait until the count
        // has not changed for a few ticks before using the items again, which would open the window over the work.
        if (started && sinceChange < QUIET_TICKS) return TaskResult.Running
        val have = game.inventory()
        if (first !in have || second !in have) return TaskResult.Failed("out of ${if (first !in have) first else second} with $made of $target $label made")
        ctx.actions.useItemOnItem(first, second)
        started = false
        settling = 3
        return TaskResult.Running
    }

    private companion object {
        const val STALL_TICKS = 150
        const val QUIET_TICKS = 8
    }
}

/**
 * Deposits into a deposit box: opens the one at [box], then uses "Deposit-All" on each of [deposit] in the bag, and "Deposit-1" on
 * [oneOf] until one is left. Done once the bag holds none of [deposit] and one or fewer of [oneOf].
 */
class DepositBoxTask(
    private val box: Tile,
    private val deposit: List<Int>,
    private val oneOf: Int? = null,
) : BaseTask() {
    override val description = "Deposit items in the deposit box"
    private val opener = InteractTask(InteractTask.Kind.OBJECT, emptyList(), listOf("Bank Deposit Box"), listOf(box), "Deposit", near = box)
    private var opened = false
    private var settling = 0
    private var closing = 0

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (game.isBusy) return TaskResult.Running
        val have = game.inventory()
        val held = deposit.filter { (have[it] ?: 0) > 0 }
        val extra = oneOf != null && (have[oneOf] ?: 0) > 1
        if (held.isEmpty() && !extra) {
            if (!opened) return TaskResult.Done
            return if (++closing > 2) TaskResult.Done else attempt(ctx.actions.closeInterface(), "close the deposit box")
        }
        if (!opened) {
            when (val result = opener.tick(ctx)) {
                TaskResult.Done -> { opened = true; settling = 3 }
                else -> return result
            }
            return TaskResult.Running
        }
        if (settling > 0) { settling--; return TaskResult.Running }
        held.firstOrNull()?.let { return attempt(ctx.actions.itemAction(it, "Deposit-All"), "deposit item $it").also { settling = 1 } }
        return attempt(ctx.actions.itemAction(oneOf!!, "Deposit-1"), "deposit one of item $oneOf").also { settling = 1 }
    }
}
