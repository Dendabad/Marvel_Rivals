package com.hcimbot.game

import com.hcimbot.guide.Tile

/** An NPC or object in the loaded scene. */
data class SceneEntity(val id: Int, val name: String, val tile: Tile)

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

    fun useItemOnNpc(itemId: Int, npc: SceneEntity): Boolean

    /** Cast a combat spell on an NPC, e.g. "WIND_STRIKE". */
    fun castOnNpc(spellName: String, npc: SceneEntity): Boolean

    /** Change a game setting the guide asks for. The only one so far is [DISABLE_LEVEL_UP]. */
    fun applySetting(setting: String): Boolean

    companion object {
        const val DISABLE_LEVEL_UP = "disable-level-up"
    }
}
