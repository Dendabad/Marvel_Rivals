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

    /**
     * Whether the step may end now, once the game shows it done. A task midway
     * through something that cannot be left (a lap of an agility course) says no,
     * and keeps being ticked until it can be.
     */
    fun canStopHere(ctx: TaskContext): Boolean = true
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
    private var announced = false
    override val description = "Walk to $tile"

    override fun tick(ctx: TaskContext): TaskResult {
        val at = ctx.game.playerTile ?: return TaskResult.Running
        val near = if (anyFloor) at.copy(plane = tile.plane) else at
        if (near.distanceTo(tile) <= radius) return TaskResult.Done
        // Only a walk in progress holds this back, not an animation: in a fight every hit plays one.
        if (ctx.game.isMoving) return TaskResult.Running
        if (!announced) {
            announced = true
            ctx.log("Walking from $at to $tile")
        }
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

    companion object {
        /**
         * Options that end a conversation, most certain first. A step that did not
         * expect chat (often a conversation the last step left open, like Morgan's
         * questions after Vampyre Slayer starts) picks one of these. Never "yes".
         */
        private val ENDINGS = listOf(
            "^(no,? )?(thanks|thank you)", "good ?bye|^bye", "nothing", "never ?mind", "i'll see about", "i'll be on my way",
            "i (have|need|must|should|'d better) (to )?go", "not (right )?now", "maybe later", "not interested",
            "^i'?m (good|fine|ok|okay|alright)", "^no\\b", "^alright", "^ok(ay)?\\b", "^i see", "^i'll get going",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }

        /** Agreeing to something, which ending a conversation must never do: "I'd love to come!", "Yes". */
        private val AGREEING = Regex("^(yes|yeah|sure|certainly|of course|i'd love|i would love|ok,? i'll|okay,? i'll)", RegexOption.IGNORE_CASE)

        fun endingOption(options: List<String>): String? {
            val safe = options.filterNot { AGREEING.containsMatchIn(it.trim()) }
            return ENDINGS.firstNotNullOfOrNull { ending -> safe.firstOrNull { ending.containsMatchIn(it.trim()) } }
        }

        /** Ends the conversation if an option does, otherwise stuck for [reason]. */
        fun endOr(options: List<String>, reason: String): Choice = endingOption(options)?.let { Choice.ByText(it) } ?: Choice.Stuck(reason)
    }

    /** The numbers from the guide text, "(3,1)". Used in order, one per options screen. */
    class Numbered(private val sequence: List<Int>) : DialoguePlan {
        private var next = 0
        override fun choose(options: List<String>): Choice {
            if (next >= sequence.size) return endOr(options, "the guide gives no more chat options, but options are showing: $options")
            val index = sequence[next++]
            if (index !in 1..options.size) return Choice.Stuck("chat option $index does not exist in $options")
            return Choice.ByIndex(index)
        }
    }

    /** Quest Helper's option texts. Any matching option is chosen, in any order. */
    class Texts(private val fixed: List<String>, private val live: () -> List<String> = { emptyList() }) : DialoguePlan {
        override fun choose(options: List<String>): Choice {
            // What Quest Helper lists for the step it is on counts as well, after the ones the guide gives.
            val wanted = fixed + live()
            for (want in wanted) {
                options.firstOrNull { it.equals(want, ignoreCase = true) }?.let { return Choice.ByText(it) }
            }
            for (want in wanted) {
                options.firstOrNull { it.contains(want, ignoreCase = true) }?.let { return Choice.ByText(it) }
            }
            return endOr(options, "none of the expected chat options $wanted are in $options")
        }
    }

    object None : DialoguePlan {
        override fun choose(options: List<String>) = endOr(options, "unexpected chat options $options")
    }

    /** Clicks through "continue" lines, and treats any choice of options as a reason to stop. */
    object Continue : DialoguePlan {
        override fun choose(options: List<String>) = endOr(options, "asked to choose from $options")
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
    /** Click without first walking up or opening doors: for someone served over a counter, who always reads as out of reach. */
    private val ignoreReach: Boolean = false,
) : BaseTask() {
    enum class Kind { NPC, OBJECT }

    private var interacted = false
    private var walk: WalkTask? = null
    private var approaches = 0
    private var doorTries = 0
    private val triedDoors = HashSet<Tile>()

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
        // Clicking someone or something behind a shut door does nothing ("I can't reach that!"):
        // the Restless Ghost's coffin, Father Urhney. Walk up first: the walker opens doors on the
        // way. An object's own tile is often solid (a coffin, a table), so a tile beside it counts.
        // Someone behind a counter also reads as out of reach, so after a few tries click anyway.
        val outOfReach = if (ignoreReach) false else when (kind) {
            Kind.NPC -> game.canReach(entity.tile) == false
            Kind.OBJECT -> game.canReachBeside(entity.tile) == false
        }
        // In range of someone who cannot be reached, after the walker has had a go (it knows the way round to a building's real
        // entrance): a shut door or gate in the way is opened, the nearest to the player first. Each door is tried once only,
        // so a locked one (a pub's front door, with another way in) is not tried over and over.
        val here = game.playerTile
        val inRange = here != null && here.distanceTo(entity.tile) <= CLOSE_RANGE
        if (outOfReach && inRange && approaches >= 1 && doorTries < MAX_DOOR_TRIES && here != null) {
            val door = game.objectsNear(DOORS, entity.tile, DOOR_RANGE).filter { it.tile !in triedDoors }.minByOrNull { it.tile.distanceTo(here) }
            if (door != null) {
                doorTries++
                triedDoors += door.tile
                ctx.log("In range of ${entity.name} but cannot reach, opening the ${door.name.ifBlank { "door" }} at ${door.tile}")
                ctx.actions.interactObject(door, "Open")
                return TaskResult.Running
            }
        }
        if (approaches < MAX_APPROACHES && outOfReach) {
            approaches++
            ctx.log("Cannot reach ${entity.name} from here, walking over (the walker opens doors)")
            ctx.actions.walkTo(entity.tile)
            return TaskResult.Running
        }
        // The walker did not get through either: open the shut door nearest the target ourselves, a few times.
        if (outOfReach && doorTries < MAX_DOOR_TRIES) {
            val door = game.nearestObject(emptyList(), DOORS, entity.tile)?.takeIf { it.tile.distanceTo(entity.tile) <= DOOR_RANGE && it.tile !in triedDoors }
            if (door != null) {
                doorTries++
                triedDoors += door.tile
                ctx.log("Still cannot reach ${entity.name}, opening the door at ${door.tile}")
                ctx.actions.interactObject(door, "Open")
                return TaskResult.Running
            }
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

    companion object {
        private const val MAX_APPROACHES = 3
        private const val MAX_DOOR_TRIES = 3
        private const val DOOR_RANGE = 8
        private const val CLOSE_RANGE = 14

        /** Objects that are a shut way through, tried when a target is out of reach. */
        val DOORS = listOf("Door", "Large door", "Gate")
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
            // Items the guide only hands over later (the Research Package) are not worth stopping for.
            // But if the quest that hands it over has not got that far, go back for it first.
            missing.firstNotNullOfOrNull { item -> ItemSources.whenNotYetGiven(item.ids, ctx)?.let { item to it } }?.let { (item, source) ->
                return TaskResult.NeedsStep(source.stepId, "the bank has no ${item.name}: ${source.why}")
            }
            val optional = missing.filter { item -> item.ids.all(ItemSources.LATER::contains) }
            if (optional.isNotEmpty()) {
                skipped += optional.flatMap { it.ids }
                ctx.log("The bank has no ${optional.joinToString { it.name }}, which the guide gives later. Carrying on")
                return TaskResult.Running
            }
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

    private val skipped = mutableSetOf<Int>()

    private fun missing(ctx: TaskContext): List<ItemRef> =
        withdraw.filter { item -> ctx.conditions.count(item.ids) < (item.count ?: 1) && !item.ids.all(skipped::contains) }

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
    private val heldWhenBought = mutableMapOf<Int, Long>()
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
        val have = ctx.conditions.count(next.ids)
        if (waited != null && have > (heldWhenBought[id] ?: 0)) {
            // Some arrived but not enough yet, e.g. a shop that sells one per click: buy again.
            requested.remove(id)
        } else if (waited != null) {
            if (waited >= ARRIVAL_TICKS) return TaskResult.Failed("bought ${next.name} but it did not arrive. The shop may be out of stock.")
            requested[id] = waited + 1
            return TaskResult.Running
        }
        val started = ctx.actions.buy(id, ((next.count ?: 1) - have).toInt().coerceAtLeast(1))
        if (started) {
            requested[id] = 0
            heldWhenBought[id] = have
        }
        val result = attempt(started, "buy ${next.name}")
        // Say what the shop offered, so a shop window the bot does not understand can be fixed.
        return if (result is TaskResult.Failed) {
            TaskResult.Failed(result.reason + (ctx.game.shopOffers(id)?.let { ". The shop shows: $it" } ?: ""))
        } else {
            result
        }
    }

    /** The guide brings the player near the right shop first, so the nearest seller is the one meant. */
    private fun opener(ctx: TaskContext): InteractTask? {
        val at = ctx.game.playerTile ?: return null
        val seller = sellers.minByOrNull { s -> s.tiles.minOfOrNull { at.distanceTo(it) } ?: Int.MAX_VALUE } ?: return null
        // A shopkeeper stands behind a counter, which the walking map reads as out of reach, so the bot would walk away to look
        // for a way round. Click them wherever they can be seen.
        return InteractTask(InteractTask.Kind.NPC, seller.ids, listOf(seller.name), seller.tiles, "Trade", ignoreReach = true)
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
    /** [alsoIds]: other ids the same transport goes by, e.g. Veos before and after his travel option. */
    class Departure(val origin: Tile, val id: Int, val menuEntry: String, val alsoIds: List<Int> = emptyList()) {
        val ids: List<Int> get() = listOf(id) + alsoIds
    }

    private var leg: Task? = null
    private var used = false
    private var waited = 0
    private var optionFailures = 0
    override val description = "Travel to $place"

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        val at = game.playerTile ?: return TaskResult.Running
        if (destination != null && at.distanceTo(destination) <= ARRIVED_TILES) return TaskResult.Done
        if (game.canContinueDialog) return attempt(ctx.actions.continueDialog(), "continue the dialogue")
        val options = game.dialogOptions()
        if (options.isNotEmpty()) {
            // The place by name, or else the "take me there" answer of a first trip ("I'd love to come!").
            val pick = options.firstOrNull { it.contains(place, ignoreCase = true) }
                ?: options.firstOrNull { GOING.containsMatchIn(it.trim()) }
                ?: return TaskResult.Failed("travel shows $options and none of them is $place")
            return attempt(ctx.actions.chooseDialogOption(pick), "pick $pick")
        }
        // A destination menu that is a window rather than chat, like the Lovakengj minecarts'.
        // Also when it is already open, say after a reload: the place's name is only shown when travelling.
        if (game.showsChoice(place)) {
            ctx.log("Choosing $place")
            used = true
            return attempt(ctx.actions.clickChoice(place), "choose $place")
        }
        if (game.isBusy) return TaskResult.Running
        if (used) {
            if (destination == null) return TaskResult.Done
            if (++waited < ARRIVAL_TICKS * 4) return TaskResult.Running
            return TaskResult.Failed("travelled but did not arrive at $place")
        }

        val departure = departures.minByOrNull { at.distanceTo(it.origin) }
            ?: return TaskResult.Failed("the guide lists no way to travel to $place")
        val transport = game.nearestObject(departure.ids)?.let { it to InteractTask.Kind.OBJECT }
            ?: game.nearestNpc(departure.ids)?.let { it to InteractTask.Kind.NPC }
        if (transport == null) {
            val walk = (leg as? WalkTask)?.takeIf { it.tile == departure.origin } ?: WalkTask(departure.origin).also { leg = it }
            return when (val walked = walk.tick(ctx)) {
                TaskResult.Done -> TaskResult.Failed("reached ${departure.origin} but the transport is not there")
                else -> walked
            }
        }
        val (entity, kind) = transport
        var option = menuOption(departure.menuEntry, entity.name)
        // A first trip has no Travel option yet (Veos, before Great Kourend is unlocked): ask instead.
        val actions = if (kind == InteractTask.Kind.NPC) game.npcActions(entity) else null
        val missing = actions != null && actions.none { it.equals(option, ignoreCase = true) } && actions.any { it.equals("Talk-to", true) }
        // Or the option is there but the click keeps failing: talk instead all the same.
        if (kind == InteractTask.Kind.NPC && (missing || optionFailures >= OPTION_TRIES) && !option.equals("Talk-to", true)) {
            if (optionFailures == OPTION_TRIES || missing) ctx.log("${entity.name}'s $option option does not work here, talking to them instead")
            option = "Talk-to"
        }
        val started = when (kind) {
            InteractTask.Kind.OBJECT -> ctx.actions.interactObject(entity, option)
            InteractTask.Kind.NPC -> ctx.actions.interactNpc(entity, option)
        }
        if (started) used = true else optionFailures++
        return attempt(started, "use ${entity.name}")
    }

    companion object {
        const val ARRIVED_TILES = 15
        private const val OPTION_TRIES = 2

        /** Answers that agree to be taken somewhere. Agreeing is the point of a travel step. */
        private val GOING = Regex("^(i'd love to|i would love to|yes|sure|take me|can you take me|let's go|travel|okay,? let's)", RegexOption.IGNORE_CASE)

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
 * Picks up items lying nearby ("Take the Beer", "Collect 3x Logs"). Items the
 * game shows, within [SCENE_REACH] tiles on the same floor and outside the
 * Wilderness, are walked to. guide.json's spawn lists cover the whole game, so
 * they are not used: walking to one could send the player across the map.
 * Items that respawn are waited for.
 */
class TakeItemTask(private val items: List<ItemRef>) : BaseTask() {
    private var waiting = 0
    private var approaches = 0
    private var far: WalkTask? = null
    override val description = "Take ${items.joinToString { it.name }}"

    override fun tick(ctx: TaskContext): TaskResult {
        val missing = items.filter { ctx.conditions.count(it.ids) < (it.count ?: 1) }
        val next = missing.firstOrNull() ?: return TaskResult.Done
        if (ctx.game.isBusy) return TaskResult.Running

        val at = ctx.game.playerTile ?: return TaskResult.Running
        val seen = ctx.game.nearestGroundItem(next.ids)?.takeIf { !it.tile.inWilderness && it.tile.distanceTo(at) <= SCENE_REACH }
        // Further than a few steps but in sight of the game: walk over to it ("Take the Beer" in Shayzien).
        if (seen != null && seen.tile.distanceTo(at) > REACH) {
            val going = far?.takeIf { it.tile == seen.tile } ?: WalkTask(seen.tile, radius = 2).also {
                far = it
                ctx.log("${next.name} is ${seen.tile.distanceTo(at)} tiles away, walking over")
            }
            return when (val walked = going.tick(ctx)) {
                TaskResult.Done -> { far = null; TaskResult.Running }
                else -> walked
            }
        }
        far = null
        val ground = seen
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
        // Through the doorway, one tile at a time, in a straight line to the item: the game's own path from outside goes
        // along the wall to a side instead of straight in. The tile outside the door, the door's tile, the tile inside.
        if (settleOpen > 0) { settleOpen--; return TaskResult.Running }
        if (through.isNotEmpty() && throughTicks < THROUGH_TICKS) {
            throughTicks++
            if (at == through.first()) through.removeFirst()
            if (through.isEmpty()) wentIn = true
            if (through.isNotEmpty()) {
                if (!ctx.game.isMoving) ctx.actions.stepOnto(through.first())
                return TaskResult.Running
            }
        }
        val held = ctx.conditions.count(next.ids)
        if (held != lastHeld) { lastHeld = held; clicks = 0; doorTries = 0; walkFailed = false; wentIn = false }
        // An item in a room: if the click does not bring it in, or the map says it cannot be reached, a door is the
        // likely reason. Open the one nearest the item (once), then walk through it and in to the item before clicking.
        // Once the bot has been through the doorway it clicks the item, whatever the map says: it is inside now.
        val blocked = !wentIn && (ctx.game.canReachBeside(ground.tile) == false || clicks >= CLICKS_BEFORE_DOOR)
        if (blocked && doorTries < MAX_DOOR_TRIES) {
            val door = ctx.game.nearestObject(emptyList(), InteractTask.DOORS, ground.tile)?.takeIf { it.tile.distanceTo(ground.tile) <= DOOR_RANGE }
            if (door != null) {
                doorTries++
                clicks = 0
                ctx.log("Cannot get the ${next.name}, opening the door at ${door.tile} and going in")
                ctx.actions.interactObject(door, "Open")
                // In a line from the door to the item, along whichever way the item is further.
                val ex = ground.tile.x - door.tile.x
                val ey = ground.tile.y - door.tile.y
                val dx = if (Math.abs(ex) >= Math.abs(ey)) Integer.signum(ex) else 0
                val dy = if (Math.abs(ex) >= Math.abs(ey)) 0 else Integer.signum(ey)
                if (dx != 0 || dy != 0) {
                    val plane = door.tile.plane
                    through.clear()
                    through += Tile(door.tile.x - dx, door.tile.y - dy, plane)
                    through += door.tile
                    through += Tile(door.tile.x + dx, door.tile.y + dy, plane)
                    throughTicks = 0
                }
                settleOpen = SETTLE_OPEN
                return TaskResult.Running
            }
        }
        if (wentIn && at.distanceTo(ground.tile) > CLOSE && !ctx.game.isMoving) {
            ctx.actions.stepOnto(ground.tile)
            return TaskResult.Running
        }
        clicks++
        return attempt(ctx.actions.takeGroundItem(ground), "take ${next.name}")
    }

    private var doorTries = 0
    private var clicks = 0
    private var lastHeld = -1L
    private var walkFailed = false
    private var settleOpen = 0
    private var wentIn = false
    private val through = ArrayDeque<Tile>()
    private var throughTicks = 0

    companion object {
        const val MAX_APPROACHES = 3
        const val CLICKS_BEFORE_DOOR = 3
        const val CLOSE = 2
        const val SETTLE_OPEN = 3
        const val THROUGH_TICKS = 20
        const val MAX_DOOR_TRIES = 3
        const val DOOR_RANGE = 8
        const val REACH = 15

        /** About as far as the game shows ground items: anything it shows is a short walk. */
        const val SCENE_REACH = 45
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
