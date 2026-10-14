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
        val inside = game.objectsNear(DYING_TREE, at, TREE_RADIUS).any { game.canReachBeside(it.tile) == true }
        if (inside && fenceTries < MAX_FENCE_TRIES) {
            val fence = game.nearestObject(emptyList(), listOf("Broken fence"), at)
            if (fence != null) {
                fenceTries++
                ctx.log("Inside the pen, climbing back over the broken fence")
                ctx.actions.interactObject(fence, "Climb-over")
                settling = 4
                return TaskResult.Running
            }
        }
        // The nearest tree that can be reached from where the bot stands: one inside the pen cannot.
        val tree = game.objectsNear(TREES, at, TREE_RADIUS)
            .filter { game.canReachBeside(it.tile) != false }
            .minByOrNull { it.tile.distanceTo(at) }
        if (tree != null) {
            noTree = 0
            return attempt(ctx.actions.interactObject(tree, "Chop down"), "chop down the tree at ${tree.tile}").also { settling = 2 }
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
        return TaskResult.Running
    }

    companion object {
        const val KNIFE = 946
        const val LOGS = 1511
        const val ARROW_SHAFT = 52
        const val HANDFUL = 18
        const val TREE_RADIUS = 25
        val DYING_TREE = listOf("Dying tree")
        const val MAX_FENCE_TRIES = 2
        val TREES = listOf("Tree")
        const val NO_TREE_TICKS = 6
        const val HOP_TICKS = 12
        const val STALL_TICKS = 400
    }
}
