package com.hcimbot.plan

import com.hcimbot.engine.Conditions
import com.hcimbot.game.GameActions
import com.hcimbot.game.GameState
import com.hcimbot.game.canReachBeside
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.ItemRef
import com.hcimbot.guide.Tile

class TaskContext(
    val game: GameState,
    val actions: GameActions,
    val conditions: Conditions,
    val log: (String) -> Unit = {},
)

sealed interface TaskResult {
    object Running : TaskResult
    object Done : TaskResult

    /** The task cannot carry on. The bot pauses and shows [reason]. */
    data class Failed(val reason: String) : TaskResult

    /**
     * An earlier guide step was never really done (its quest has not started),
     * so this one cannot be. The bot goes back and does [stepId], then returns.
     */
    data class NeedsStep(val stepId: String, val reason: String) : TaskResult
}

/**
 * One unit of work, advanced once per game tick. A task issues at most one
 * action per tick and waits while the player is busy, so it never spams clicks.
 */
interface Task {
    val description: String
    fun tick(ctx: TaskContext): TaskResult
}

/** Shared retry bookkeeping: an action that keeps failing to start ends the task. */
abstract class BaseTask : Task {
    private var failures = 0

    protected fun attempt(started: Boolean, what: String): TaskResult {
        if (started) {
            failures = 0
            return TaskResult.Running
        }
        failures++
        return if (failures >= MAX_FAILED_ATTEMPTS) TaskResult.Failed("could not $what") else TaskResult.Running
    }

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5

        /** How long to wait for the game to react to an action before giving up. */
        const val ARRIVAL_TICKS = 8
    }
}

/** Runs [task] only if [needed] holds when it starts, e.g. take the stairs only when on the wrong floor. */
class OnlyIf(private val needed: (TaskContext) -> Boolean, private val task: Task) : Task {
    private var decided = false
    override val description get() = task.description

    override fun tick(ctx: TaskContext): TaskResult {
        if (!decided) {
            if (!needed(ctx)) return TaskResult.Done
            decided = true
        }
        return task.tick(ctx)
    }
}

/** [anyFloor]: being within [radius] on another floor counts, e.g. near a town whose bank is upstairs. */
class WalkTask(val tile: Tile, private val radius: Int = 3, private val anyFloor: Boolean = false) : BaseTask() {
    override val description = "Walk to $tile"

    override fun tick(ctx: TaskContext): TaskResult {
        val at = ctx.game.playerTile ?: return TaskResult.Running
        val near = if (anyFloor) at.copy(plane = tile.plane) else at
        if (near.distanceTo(tile) <= radius) return TaskResult.Done
        if (ctx.game.isBusy) return TaskResult.Running
        return attempt(ctx.actions.walkTo(tile), "walk to $tile")
    }
}

/** How to answer chat while talking to someone. */
sealed interface DialoguePlan {
    /** Pick the next one of these chat options each time options appear. Click continue otherwise. */
    fun choose(options: List<String>): Choice

    sealed interface Choice {
        data class ByIndex(val index: Int) : Choice
        data class ByText(val text: String) : Choice
        data class Stuck(val reason: String) : Choice
    }

    /** The numbers from the guide text, "(3,1)". Used in order, one per options screen. */
    class Numbered(private val sequence: List<Int>) : DialoguePlan {
        private var next = 0
        override fun choose(options: List<String>): Choice {
            if (next >= sequence.size) return Choice.Stuck("the guide gives no more chat options, but options are showing: $options")
            val index = sequence[next++]
            if (index !in 1..options.size) return Choice.Stuck("chat option $index does not exist in $options")
            return Choice.ByIndex(index)
        }
    }

    /** Quest Helper's option texts. Any matching option is chosen, in any order. */
    class Texts(private val wanted: List<String>) : DialoguePlan {
        override fun choose(options: List<String>): Choice {
            for (want in wanted) {
                options.firstOrNull { it.equals(want, ignoreCase = true) }?.let { return Choice.ByText(it) }
            }
            for (want in wanted) {
                options.firstOrNull { it.contains(want, ignoreCase = true) }?.let { return Choice.ByText(it) }
            }
            return Choice.Stuck("none of the expected chat options $wanted are in $options")
        }
    }

    object None : DialoguePlan {
        override fun choose(options: List<String>) = Choice.Stuck("unexpected chat options $options")
    }

    /** Clicks through "continue" lines, and treats any choice of options as a reason to stop. */
    object Continue : DialoguePlan {
        override fun choose(options: List<String>) = Choice.Stuck("asked to choose from $options")
    }
}

/**
 * Finds an NPC or object, walks to it if needed, interacts with it and then
 * works through any dialogue. Done once the interaction has happened and the
 * player is idle with no dialogue open.
 */
class InteractTask(
    private val kind: Kind,
    private val ids: List<Int>,
    private val names: List<String>,
    private val tiles: List<Tile>,
    private val action: String?,
    private val dialogue: DialoguePlan = DialoguePlan.None,
    /** For steps that may need several clicks, e.g. pickpocketing until a diary task ticks. */
    private val repeat: Boolean = false,
    /** Prefer the match nearest this tile, e.g. the south one of two staircases. */
    private val near: Tile? = null,
) : BaseTask() {
    enum class Kind { NPC, OBJECT }

    private var interacted = false
    private var walk: WalkTask? = null
    private var approaches = 0

    override val description: String =
        "${action ?: "Interact with"} ${names.firstOrNull() ?: ids.toString()}"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (game.canContinueDialog) return attempt(ctx.actions.continueDialog(), "continue the dialogue")
        val options = game.dialogOptions()
        if (options.isNotEmpty()) {
            return when (val choice = dialogue.choose(options)) {
                is DialoguePlan.Choice.ByIndex -> attempt(ctx.actions.chooseDialogOption(choice.index), "pick chat option ${choice.index}")
                is DialoguePlan.Choice.ByText -> attempt(ctx.actions.chooseDialogOption(choice.text), "pick chat option \"${choice.text}\"")
                is DialoguePlan.Choice.Stuck -> TaskResult.Failed(choice.reason)
            }
        }
        if (game.isBusy) return TaskResult.Running
        if (interacted && !repeat) return TaskResult.Done

        val entity = find(game)
        if (entity == null) {
            val at = game.playerTile ?: return TaskResult.Running
            val nearest = tiles.minByOrNull { at.distanceTo(it) }
                ?: return TaskResult.Failed("${names.firstOrNull() ?: "the target"} is not nearby and the guide has no location for it")
            val leg = walk?.takeIf { it.tile == nearest } ?: WalkTask(nearest).also { walk = it }
            return when (val walked = leg.tick(ctx)) {
                TaskResult.Done -> TaskResult.Failed("reached $nearest but ${names.firstOrNull() ?: "the target"} is not there")
                else -> walked
            }
        }
        // Clicking someone behind a shut door does nothing ("I can't reach that!"). Walk up to
        // them first: the walker opens doors on the way. Someone behind a counter also reads
        // as out of reach, so after a few tries click anyway, which works over a counter.
        if (kind == Kind.NPC && approaches < MAX_APPROACHES && game.canReach(entity.tile) == false) {
            approaches++
            ctx.log("Cannot reach ${entity.name} from here, walking over (the walker opens doors)")
            ctx.actions.walkTo(entity.tile)
            return TaskResult.Running
        }
        val started = when (kind) {
            Kind.NPC -> ctx.actions.interactNpc(entity, action)
            Kind.OBJECT -> ctx.actions.interactObject(entity, action)
        }
        if (started) interacted = true
        return attempt(started, "$description at ${entity.tile}")
    }

    private fun find(game: GameState): SceneEntity? = when (kind) {
        Kind.NPC -> game.nearestNpc(ids, names, near)
        Kind.OBJECT -> game.nearestObject(ids, names, near)
    }

    private companion object {
        const val MAX_APPROACHES = 3
    }
}

/** Opens the nearest bank, optionally deposits everything, then withdraws what the step lists. */
class BankTask(
    private val depositAll: Boolean,
    withdraw: List<ItemRef>,
) : BaseTask() {
    private val withdraw = withdraw.map(ItemSources::widen)
    private var deposited = !depositAll

    /** Item id to ticks spent waiting for a withdrawal to show up. */
    private val requested = mutableMapOf<Int, Int>()

    override val description =
        buildString {
            append("Bank")
            if (depositAll) append(", deposit all")
            if (withdraw.isNotEmpty()) append(", withdraw ${withdraw.joinToString { it.name }}")
        }

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        if (game.isBusy) return TaskResult.Running
        if (!game.isBankOpen) {
            if (deposited && missing(ctx).isEmpty()) return TaskResult.Done
            return attempt(ctx.actions.openNearestBank(), "open a bank")
        }
        if (!deposited) {
            deposited = ctx.actions.depositAll()
            return attempt(deposited, "deposit everything")
        }
        val missing = missing(ctx)
        if (missing.isEmpty()) return attempt(ctx.actions.closeInterface(), "close the bank")

        // Take out everything the bank has before dealing with what it lacks.
        val next = missing.firstOrNull { item -> item.ids.any { game.bankCount(it) > 0 } }
        if (next == null) {
            val lacking = missing.first()
            val source = ItemSources.whenNotYetGiven(lacking.ids, ctx)
            return if (source != null) {
                TaskResult.NeedsStep(source.stepId, "the bank has no ${lacking.name}: ${source.why}")
            } else {
                TaskResult.Failed("the bank has no ${missing.joinToString { it.name }}")
            }
        }
        val id = next.ids.first { game.bankCount(it) > 0 }
        val waited = requested[id]
        if (waited != null) {
            if (waited >= ARRIVAL_TICKS) return TaskResult.Failed("withdrew ${next.name} but it did not arrive in the inventory")
            requested[id] = waited + 1
            return TaskResult.Running
        }
        val started = ctx.actions.withdraw(id, next.count ?: WITHDRAW_DEFAULT)
        if (started) requested[id] = 0
        return attempt(started, "withdraw ${next.name}")
    }

    private fun missing(ctx: TaskContext): List<ItemRef> =
        withdraw.filter { ctx.conditions.count(it.ids) < (it.count ?: 1) }

    companion object {
        /** All of a stackable item, one of anything else. See [GameActions.withdraw]. */
        const val WITHDRAW_DEFAULT = 0
    }
}

class CastSpellTask(private val spellName: String, private val widgetId: Int) : BaseTask() {
    private var cast = false
    override val description = "Cast $spellName"

    override fun tick(ctx: TaskContext): TaskResult {
        if (ctx.game.isBusy) return TaskResult.Running
        if (cast) return TaskResult.Done
        cast = ctx.actions.castSpell(spellName, widgetId)
        return attempt(cast, "cast $spellName")
    }
}

/**
 * Teleports with an item to [destination]. The destination is matched against
 * the item's own menu options first ("Kandarin Monastery" on an Ardougne
 * cloak), then against the chat options a "Rub" or "Teleport" opens. Anything
 * the bot cannot match pauses it instead of guessing.
 */
class ItemTeleportTask(
    private val via: String,
    private val itemIds: List<Int>,
    private val destination: String,
) : BaseTask() {
    private var used = false
    override val description = "Teleport with $via to $destination"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val options = game.dialogOptions()
        if (options.isNotEmpty()) {
            val pick = options.firstOrNull { matches(it) }
                ?: return TaskResult.Failed("$via shows $options and none of them is $destination")
            return attempt(ctx.actions.chooseDialogOption(pick), "pick $pick")
        }
        if (game.isBusy) return TaskResult.Running
        if (used) return TaskResult.Done

        val held = game.inventory().keys + game.equipment().keys
        val id = itemIds.firstOrNull { it in held } ?: return TaskResult.Failed("$via is not in the inventory or worn")
        val itemOptions = game.itemOptions(id)
        val action = itemOptions.firstOrNull { matches(it) }
            ?: itemOptions.singleOrNull { it in OPENS_A_MENU }
            ?: return TaskResult.Failed("cannot tell which of $via's options $itemOptions goes to $destination")
        used = ctx.actions.itemAction(id, action)
        return attempt(used, "use $via")
    }

    private fun matches(option: String) =
        option.contains(destination, ignoreCase = true) ||
            (option.length > 3 && destination.contains(option, ignoreCase = true))

    companion object {
        /** Options that open a list of destinations rather than teleporting straight away. */
        val OPENS_A_MENU = setOf("Rub", "Teleport", "Teleports")
    }
}

/** Uses one inventory item on another, or on a scene object found by id or name, nearest [near] if given. */
class UseItemTask(
    private val itemId: Int,
    private val targetItemId: Int?,
    private val targetObjectIds: List<Int> = emptyList(),
    private val targetObjectNames: List<String> = emptyList(),
    private val near: Tile? = null,
) : BaseTask() {
    private var used = false
    override val description = "Use item $itemId on ${targetItemId ?: targetObjectNames.firstOrNull() ?: targetObjectIds}"

    override fun tick(ctx: TaskContext): TaskResult {
        if (ctx.game.isBusy) return TaskResult.Running
        if (used) return TaskResult.Done
        if (itemId !in ctx.game.inventory()) return TaskResult.Failed("item $itemId is not in the inventory")
        used = if (targetItemId != null) {
            ctx.actions.useItemOnItem(itemId, targetItemId)
        } else {
            val obj = ctx.game.nearestObject(targetObjectIds, targetObjectNames, near)
                ?: return TaskResult.Failed("no ${targetObjectNames.firstOrNull() ?: "object $targetObjectIds"} nearby")
            ctx.actions.useItemOnObject(itemId, obj)
        }
        return attempt(used, description)
    }
}

/** Opens the nearest of [sellers]' shops and buys each listed item. */
class ShopTask(
    private val sellers: List<Seller>,
    private val items: List<ItemRef>,
) : BaseTask() {
    class Seller(val name: String, val ids: List<Int>, val tiles: List<Tile>)

    private var open: InteractTask? = null
    private var waitingForShop = 0
    private val requested = mutableMapOf<Int, Int>()
    override val description = "Buy ${items.joinToString { it.name }}"

    override fun tick(ctx: TaskContext): TaskResult {
        val missing = items.filter { ctx.conditions.count(it.ids) < (it.count ?: 1) }
        if (missing.isEmpty()) {
            return if (ctx.game.isShopOpen) attempt(ctx.actions.closeInterface(), "close the shop") else TaskResult.Done
        }
        if (!ctx.game.isShopOpen) {
            val opener = open ?: (opener(ctx) ?: return TaskResult.Running).also { open = it }
            return when (val opening = opener.tick(ctx)) {
                TaskResult.Done -> {
                    if (++waitingForShop < ARRIVAL_TICKS) return TaskResult.Running
                    TaskResult.Failed("talked to the shopkeeper but no shop opened")
                }
                else -> opening
            }
        }
        waitingForShop = 0
        if (ctx.game.isBusy) return TaskResult.Running
        val next = missing.first()
        val id = next.ids.first()
        val waited = requested[id]
        if (waited != null) {
            if (waited >= ARRIVAL_TICKS) return TaskResult.Failed("bought ${next.name} but it did not arrive. The shop may be out of stock.")
            requested[id] = waited + 1
            return TaskResult.Running
        }
        val started = ctx.actions.buy(id, next.count ?: 1)
        if (started) requested[id] = 0
        return attempt(started, "buy ${next.name}")
    }

    /** The guide brings the player near the right shop first, so the nearest seller is the one meant. */
    private fun opener(ctx: TaskContext): InteractTask? {
        val at = ctx.game.playerTile ?: return null
        val seller = sellers.minByOrNull { s -> s.tiles.minOfOrNull { at.distanceTo(it) } ?: Int.MAX_VALUE } ?: return null
        return InteractTask(InteractTask.Kind.NPC, seller.ids, listOf(seller.name), seller.tiles, "Trade")
    }
}

/**
 * Takes a boat, minecart, carpet or similar. Each departure in guide.json is
 * an origin tile plus the transport's id and its menu entry ("Travel Minecart",
 * "Brimhaven Captain Barnaby"), which is the option followed by the target's
 * name. Done once the player is near [destination].
 */
class TravelTask(
    private val place: String,
    private val departures: List<Departure>,
    private val destination: Tile?,
) : BaseTask() {
    class Departure(val origin: Tile, val id: Int, val menuEntry: String)

    private var leg: Task? = null
    private var used = false
    private var waited = 0
    override val description = "Travel to $place"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val at = game.playerTile ?: return TaskResult.Running
        if (destination != null && at.distanceTo(destination) <= ARRIVED_TILES) return TaskResult.Done
        if (game.canContinueDialog) return attempt(ctx.actions.continueDialog(), "continue the dialogue")
        val options = game.dialogOptions()
        if (options.isNotEmpty()) {
            val pick = options.firstOrNull { it.contains(place, ignoreCase = true) }
                ?: return TaskResult.Failed("travel shows $options and none of them is $place")
            return attempt(ctx.actions.chooseDialogOption(pick), "pick $pick")
        }
        if (game.isBusy) return TaskResult.Running
        if (used) {
            if (destination == null) return TaskResult.Done
            if (++waited < ARRIVAL_TICKS * 4) return TaskResult.Running
            return TaskResult.Failed("travelled but did not arrive at $place")
        }

        val departure = departures.minByOrNull { at.distanceTo(it.origin) }
            ?: return TaskResult.Failed("the guide lists no way to travel to $place")
        val transport = game.nearestObject(listOf(departure.id))?.let { it to InteractTask.Kind.OBJECT }
            ?: game.nearestNpc(listOf(departure.id))?.let { it to InteractTask.Kind.NPC }
        if (transport == null) {
            val walk = (leg as? WalkTask)?.takeIf { it.tile == departure.origin } ?: WalkTask(departure.origin).also { leg = it }
            return when (val walked = walk.tick(ctx)) {
                TaskResult.Done -> TaskResult.Failed("reached ${departure.origin} but the transport is not there")
                else -> walked
            }
        }
        val (entity, kind) = transport
        val option = menuOption(departure.menuEntry, entity.name)
        val started = when (kind) {
            InteractTask.Kind.OBJECT -> ctx.actions.interactObject(entity, option)
            InteractTask.Kind.NPC -> ctx.actions.interactNpc(entity, option)
        }
        if (started) used = true
        return attempt(started, "use ${entity.name}")
    }

    companion object {
        const val ARRIVED_TILES = 15

        /** "Travel Minecart" on a Minecart is the option "Travel". */
        fun menuOption(menuEntry: String, targetName: String): String =
            if (targetName.isNotBlank() && menuEntry.endsWith(" $targetName", ignoreCase = true)) {
                menuEntry.dropLast(targetName.length + 1).trim()
            } else {
                menuEntry
            }
    }
}

/**
 * Picks up items lying nearby ("Take the Beer", "Collect 3x Logs"). Only items
 * within [REACH] tiles count: the guide puts the player next to them, and
 * guide.json's spawn lists cover the whole game, so walking to one could send
 * the player across the map. Items that respawn are waited for.
 */
class TakeItemTask(private val items: List<ItemRef>) : BaseTask() {
    private var waiting = 0
    private var approaches = 0
    override val description = "Take ${items.joinToString { it.name }}"

    override fun tick(ctx: TaskContext): TaskResult {
        val missing = items.filter { ctx.conditions.count(it.ids) < (it.count ?: 1) }
        val next = missing.firstOrNull() ?: return TaskResult.Done
        if (ctx.game.isBusy) return TaskResult.Running

        val at = ctx.game.playerTile ?: return TaskResult.Running
        val ground = ctx.game.nearestGroundItem(next.ids)?.takeIf { it.tile.distanceTo(at) <= REACH }
        if (ground == null) {
            // Some already taken means this is a spawn to wait for. None at all means it is not here.
            val started = ctx.conditions.count(next.ids) > 0
            if (++waiting < if (started) RESPAWN_TICKS else ARRIVAL_TICKS) return TaskResult.Running
            return TaskResult.Failed("cannot see ${next.name} nearby")
        }
        waiting = 0
        // Behind a shut door, the click does nothing. Walk over first: the walker opens doors.
        if (approaches < MAX_APPROACHES && ctx.game.canReachBeside(ground.tile) == false) {
            approaches++
            ctx.log("Cannot reach the ${next.name} from here, walking over (the walker opens doors)")
            ctx.actions.walkTo(ground.tile)
            return TaskResult.Running
        }
        return attempt(ctx.actions.takeGroundItem(ground), "take ${next.name}")
    }

    companion object {
        const val MAX_APPROACHES = 3
        const val REACH = 15
        const val RESPAWN_TICKS = 100
    }
}

/** Wears or wields items from the inventory ("Take Leather Gloves & wield"). */
class EquipTask(private val items: List<ItemRef>) : BaseTask() {
    private val requested = mutableMapOf<Int, Int>()
    override val description = "Equip ${items.joinToString { it.name }}"

    override fun tick(ctx: TaskContext): TaskResult {
        val worn = ctx.game.equipment()
        val next = items.firstOrNull { item -> item.ids.none { (worn[it] ?: 0) > 0 } } ?: return TaskResult.Done
        if (ctx.game.isBusy) return TaskResult.Running

        val id = next.ids.firstOrNull { (ctx.game.inventory()[it] ?: 0) > 0 }
            ?: return TaskResult.Failed("${next.name} is not in the inventory")
        val waited = requested[id]
        if (waited != null) {
            if (waited >= ARRIVAL_TICKS) return TaskResult.Failed("tried to equip ${next.name} but it is not worn")
            requested[id] = waited + 1
            return TaskResult.Running
        }
        val option = ctx.game.itemOptions(id).firstOrNull { it in EQUIP_OPTIONS }
            ?: return TaskResult.Failed("${next.name} has no Wear or Wield option")
        val started = ctx.actions.itemAction(id, option)
        if (started) requested[id] = 0
        return attempt(started, "equip ${next.name}")
    }

    companion object {
        val EQUIP_OPTIONS = setOf("Wear", "Wield", "Equip")
    }
}
