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

/** What the Quest Helper plugin is pointing at: its instruction and the NPC ids it highlights. */
data class HelperStep(val text: String, val npcIds: List<Int>, val tile: Tile? = null,
    /** The chat options Quest Helper lists for this step, when it has any. */
    val choices: List<String> = emptyList(),
)

/**
 * Everything the bot reads from the game. Each member maps onto RuneLite's
 * Client API (getVarbitValue, getVarpValue, getRealSkillLevel, the inventory
 * ItemContainer, getNpcs, the scene tiles, getWidget), so an implementation
 * needs nothing from the Bot Client SDK.
 */
interface GameState {
    val isLoggedIn: Boolean
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

    /** What the open shop offers for [itemId], to explain a buy that did not work. Null when unknown. */
    fun shopOffers(itemId: Int): String? = null

    /** Where NPCs called [name] spawn, from Microbot's NPC location data. Empty when unknown. */
    fun npcSpawns(name: String): List<Tile> = emptyList()

    /** The object id RuneLite names [constant] (its gameval ObjectID), for guide data that gives only the name. */
    fun objectIdNamed(constant: String): Int? = null

    /** The NPC id RuneLite names [constant] (its gameval NpcID), for quest NPCs the guide data gives no id for. */
    fun npcIdNamed(constant: String): Int? = null

    /** The step the Quest Helper plugin shows now, or null when it is not running or has no quest selected. */
    fun helperStep(): HelperStep? = null

    /** Nearest item lying on the ground (or on a table) with one of these ids. */
    fun nearestGroundItem(ids: Collection<Int>): SceneEntity?

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

    /** Presses Escape, which closes an open book or other window. */
    fun pressEscape(): Boolean = false

    /** [spellName] is RuneLite's name from guide.json ("VARROCK_TELEPORT"), [widgetId] its packed spellbook widget. */
    fun castSpell(spellName: String, widgetId: Int): Boolean

    /** Click a menu option on an inventory or worn item, e.g. "Teleport" on a Chronicle. */
    fun itemAction(itemId: Int, action: String): Boolean
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

    /** Uses an inventory item on something lying on the ground, e.g. a tinderbox on logs. */
    fun useItemOnGroundItem(itemId: Int, item: SceneEntity): Boolean = false

    fun useItemOnNpc(itemId: Int, npc: SceneEntity): Boolean

    /** Cast a combat spell on an NPC, e.g. "WIND_STRIKE". */
    fun castOnNpc(spellName: String, npc: SceneEntity): Boolean

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
