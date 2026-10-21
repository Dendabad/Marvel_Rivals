package com.hcimbot.game

import com.hcimbot.guide.Tile

/** An NPC or object in the loaded scene. */
data class SceneEntity(
    val id: Int,
    val name: String,
    val tile: Tile,
    /** The game's index for this one NPC, to tell it from others of its kind. -1 when not known. */
    val index: Int = -1,
)

/** One item Quest Helper asks for, or recommends. [ids] are every id it accepts for it. */
data class QuestItem(val name: String, val ids: List<Int>, val quantity: Int, val recommended: Boolean)

/** What the Quest Helper plugin is pointing at: its instruction and the NPC ids it highlights. */
data class HelperStep(val text: String, val npcIds: List<Int>, val tile: Tile? = null,
    /** The chat options Quest Helper lists for this step, when it has any. */
    val choices: List<String> = emptyList(),
    /** The object ids Quest Helper highlights, for an object step. */
    val objectIds: List<Int> = emptyList(),
    /** The kind of step Quest Helper has, for working out what it exposes. */
    val kind: String = "",
)

/**
 * Everything the bot reads from the game. Each member maps onto RuneLite's
 * Client API (getVarbitValue, getVarpValue, getRealSkillLevel, the inventory
 * ItemContainer, getNpcs, the scene tiles, getWidget), so an implementation
 * needs nothing from the Bot Client SDK.
 */
interface GameState {
    val isLoggedIn: Boolean

    /** The world the player is on, or -1 when not known. */
    val world: Int get() = -1
    val playerName: String?
    val playerTile: Tile?

    /** True while walking, animating or otherwise busy with the last action. */
    /**
     * Whether the player is walking right now. Walking only waits on this: a fight plays a block
     * animation on nearly every tick, which would otherwise hold the next walking order back for
     * seconds at a time. Defaults to [isBusy] for games that cannot tell the two apart.
     */
    val isMoving: Boolean get() = isBusy

    val isBusy: Boolean
    val isInCombat: Boolean

    val hitpoints: Int
    val maxHitpoints: Int

    fun varbit(id: Int): Int
    fun varp(id: Int): Int

    /** Real (unboosted) level, by RuneLite Skill name, e.g. "THIEVING". */
    fun skillLevel(skill: String): Int

    /** Inventory item id to quantity. */
    fun inventory(): Map<Int, Int>
    fun equipment(): Map<Int, Int>

    /** Last known bank quantity, or 0 when the bank has not been seen. */
    fun bankCount(itemId: Int): Int
    val isBankOpen: Boolean
    val isShopOpen: Boolean

    /**
     * Nearest matching NPC by id, or by name when [ids] is empty. With [near],
     * nearest to that tile instead of the player, e.g. the south staircase of
     * a castle that has two.
     */
    fun nearestNpc(ids: Collection<Int>, names: Collection<String> = emptyList(), near: Tile? = null): SceneEntity?
    fun nearestObject(ids: Collection<Int>, names: Collection<String> = emptyList(), near: Tile? = null): SceneEntity?

    /**
     * Whether the player can walk to [tile] from here, counting shut doors as
     * walls. Null when the game cannot say.
     */
    fun canReach(tile: Tile): Boolean? = null

    /** True while [npc] (the very same one) is still alive. Null when the game cannot say. */
    fun npcAlive(npc: SceneEntity): Boolean? = null

    /** The right click options [npc] has now, e.g. Veos has no "Travel" until the first trip. Null when unknown. */
    fun npcActions(npc: SceneEntity): List<String>? = null

    /** Whether a game window shows an entry ending in [text], e.g. "B: Shayzien East" in the minecart menu. */
    fun showsChoice(text: String): Boolean = false

    /** Whether something standing on [from] can see [to], by the scene's collision data (what an NPC's line of sight uses). Null if unknown. */
    fun hasLineOfSight(from: Tile, to: Tile): Boolean? = null


    /** A window with a Close button is open: a book read for a quest step, say. Not asked of a bank or shop. */
    val showsCloseableWindow: Boolean get() = false

    /** The make window is open: "What would you like to make?", or the "How many sets of 15 do you wish to feather?" one. */
    fun showsMakeWindow(): Boolean = showsChoice("What would you like to make?")

    /** What the open shop offers for [itemId], to explain a buy that did not work. Null when unknown. */
    fun shopOffers(itemId: Int): String? = null

    /** Where NPCs called [name] spawn, from Microbot's NPC location data. Empty when unknown. */
    fun npcSpawns(name: String): List<Tile> = emptyList()

    /** The object id RuneLite names [constant] (its gameval ObjectID), for guide data that gives only the name. */
    fun objectIdNamed(constant: String): Int? = null

    /** The NPC id RuneLite names [constant] (its gameval NpcID), for quest NPCs the guide data gives no id for. */
    fun npcIdNamed(constant: String): Int? = null

    /**
     * The step the Quest Helper plugin shows now for [quest] (our key for it, like "daddyshome"), or null when it is
     * not running or is showing another quest. It only follows one quest at a time.
     */
    fun helperStep(quest: String? = null): HelperStep? = null

    /** The items Quest Helper lists for [quest], needed and recommended, or null when it cannot be read. */
    fun questItems(quest: String? = null): List<QuestItem>? = null

    /** Why the last [helperStep] gave nothing, for the log: another quest selected, plugin missing. Null when fine. */
    fun helperProblem(): String? = null

    /** Nearest item lying on the ground (or on a table) with one of these ids. */
    /** Every object named one of [names] within [within] tiles of [near]. */
    fun objectsNear(names: Collection<String>, near: Tile, within: Int): List<SceneEntity> = emptyList()

    /** Every ground item with one of [ids] within [within] tiles of [near]. */
    fun groundItemsNear(ids: Collection<Int>, near: Tile, within: Int): List<SceneEntity> = emptyList()

    /** The ground item with one of [ids] nearest to [near] (the player when null) and within [within] tiles of it. */
    fun nearestGroundItem(ids: Collection<Int>, near: Tile? = null, within: Int? = null): SceneEntity?

    /** Chat options currently on screen, top to bottom. Empty when none are shown. */
    fun dialogOptions(): List<String>

    /** True when a "Click here to continue" dialog is open. */
    val canContinueDialog: Boolean

    /** Text of the dialog on screen right now, as "Speaker|text", or null. */
    fun currentDialog(): String?

    /** Dialog lines seen since the bot started, as "Speaker|text". */
    fun dialogHistory(): List<String>

    /** Menu options on an inventory or worn item, e.g. ["Wear", "Kandarin Monastery", "Ardougne Farm"]. */
    fun itemOptions(itemId: Int): List<String>

    fun widgetText(group: Int, child: Int): String?
    fun isInterfaceOpen(packedWidgetId: Int): Boolean
}

/**
 * Everything the bot does in the game. This is the only part that needs the
 * Bot Client SDK. Each call starts one action and returns false if it could not
 * be started. The bot waits for [GameState.isBusy] to clear before the next one,
 * so implementations should not block.
 */
interface GameActions {
    /** Walk towards [tile], one leg at a time. Called again each tick until arrival. */
    fun walkTo(tile: Tile): Boolean

    /** Clicks the window entry [showsChoice] found for [text]. */
    fun clickChoice(text: String): Boolean = false

    /** Click onto [tile] itself, which must be close by. For spots where the exact tile matters, like a dig. */
    fun stepOnto(tile: Tile): Boolean

    /** [action] is the menu option, e.g. "Talk-to" or "Pickpocket". Null uses the default left click. */
    fun interactNpc(npc: SceneEntity, action: String?): Boolean
    fun interactObject(obj: SceneEntity, action: String?): Boolean

    fun continueDialog(): Boolean

    /** 1 based, matching the numbers in the guide text. */
    fun chooseDialogOption(index: Int): Boolean
    fun chooseDialogOption(text: String): Boolean

    fun openNearestBank(): Boolean
    fun depositAll(): Boolean
    /** A [quantity] of 0 or less means all of a stackable item and one of anything else. */
    fun withdraw(itemId: Int, quantity: Int): Boolean
    fun closeInterface(): Boolean

    /** In the Furniture Creation Menu (building in a house): picks its first piece. */
    fun pickFurniture(): Boolean = false

    /** In a make window (planks, shafts): picks the [quantity] button ("1", "5", "10", "X", "All"), then item number [item] from the left, 1 based. */
    fun makePick(quantity: String, item: Int): Boolean = false

    /** In a "What would you like to make?" window: picks the All quantity, then the first item (Space). */
    fun makeAll(): Boolean = false

    /** Closes an open book or other window: its Close button, or Escape. False when nothing could be done. */
    fun closeWindow(): Boolean = false

    /** [spellName] is RuneLite's name from guide.json ("VARROCK_TELEPORT"), [widgetId] its packed spellbook widget. */
    fun castSpell(spellName: String, widgetId: Int): Boolean

    /** Click a menu option on an inventory or worn item, e.g. "Teleport" on a Chronicle. */
    fun itemAction(itemId: Int, action: String): Boolean

    /** [action] on the first inventory item whose name contains [part] ("crate"), for items whose id is not known. */
    fun itemActionByName(part: String, action: String): Boolean = false
    fun useItemOnItem(itemId: Int, targetItemId: Int): Boolean
    fun useItemOnObject(itemId: Int, obj: SceneEntity): Boolean

    fun buy(itemId: Int, quantity: Int): Boolean

    /** "Take" on a ground item from [GameState.nearestGroundItem]. */
    fun takeGroundItem(item: SceneEntity): Boolean

    /** Sell from the inventory to the open shop. [quantity] is 1, 5, 10 or 50. */
    fun sell(itemId: Int, quantity: Int): Boolean

    /** Deposit every one of this item into the open bank. */
    fun depositItem(itemId: Int): Boolean

    /** Hop to another world of the same membership. */
    fun hopWorld(): Boolean

    /** Hops to a world not in [avoid], when one can be picked. The world the player is on now is never chosen. */
    fun hopWorldAvoiding(avoid: Set<Int>): Boolean = hopWorld()

    /** Uses an inventory item on something lying on the ground, e.g. a tinderbox on logs. */
    fun useItemOnGroundItem(itemId: Int, item: SceneEntity): Boolean = false

    fun useItemOnNpc(itemId: Int, npc: SceneEntity): Boolean

    /** Cast a combat spell on an NPC, e.g. "WIND_STRIKE". */
    fun castOnNpc(spellName: String, npc: SceneEntity): Boolean

    /** Opens the Grand Exchange window at a booth or clerk nearby. */
    fun openGrandExchange(): Boolean = false

    /** Whether the Grand Exchange window is open. */
    val isGrandExchangeOpen: Boolean get() = false

    /** Puts in an offer to buy [quantity] of the item [name] (id [itemId]), priced to fill quickly. False if it could not. */
    fun grandExchangeBuy(name: String, itemId: Int, quantity: Int): Boolean = false

    /** Collects whatever the Grand Exchange has for the player, into the bag. */
    fun collectGrandExchange(): Boolean = false

    /** Why the last Grand Exchange call failed, for the pause message. */
    val grandExchangeProblem: String? get() = null

    /** Change a game setting the guide asks for. The only one so far is [DISABLE_LEVEL_UP]. */
    fun applySetting(setting: String): Boolean

    companion object {
        const val DISABLE_LEVEL_UP = "disable-level-up"
    }
}

/**
 * Whether the player can get next to [tile]: the tile itself, or one beside
 * it, since an item on a table lies on a tile nobody can stand on. Null when
 * the game cannot say.
 */
fun GameState.canReachBeside(tile: Tile): Boolean? {
    val here = canReach(tile) ?: return null
    if (here) return true
    return listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dy) ->
        canReach(Tile(tile.x + dx, tile.y + dy, tile.plane)) == true
    }
}
