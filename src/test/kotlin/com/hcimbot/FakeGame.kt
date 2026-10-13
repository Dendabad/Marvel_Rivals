package com.hcimbot

import com.hcimbot.game.GameActions
import com.hcimbot.game.GameState
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile

/**
 * A tiny scripted game world. Actions take effect immediately, and tests hook
 * [onNpcInteract] and friends to make the world react (set a varp, open a
 * dialogue, move the player).
 */
class FakeGame : GameState, GameActions {
    override var isLoggedIn = true
    override var playerName: String? = "Tester"
    override var playerTile: Tile? = Tile(3222, 3218, 0)
    override var isBusy = false
    override var isInCombat = false
    override var hitpoints = 10
    override var maxHitpoints = 10
    override var isBankOpen = false
    override var isShopOpen = false
    override var canContinueDialog = false

    val varbits = mutableMapOf<Int, Int>()
    val varps = mutableMapOf<Int, Int>()
    val levels = mutableMapOf<String, Int>()
    val inv = mutableMapOf<Int, Int>()
    val worn = mutableMapOf<Int, Int>()
    val bank = mutableMapOf<Int, Int>()
    val npcs = mutableListOf<SceneEntity>()
    val objects = mutableListOf<SceneEntity>()
    var options = listOf<String>()
    val history = mutableListOf<String>()

    /** Every action the bot took, in order, for assertions. */
    val calls = mutableListOf<String>()

    var onNpcInteract: (SceneEntity, String?) -> Unit = { _, _ -> }
    var onObjectInteract: (SceneEntity, String?) -> Unit = { _, _ -> }
    var onOption: (String) -> Unit = {}
    var onContinue: () -> Unit = { canContinueDialog = false }

    override fun varbit(id: Int) = varbits[id] ?: 0
    override fun varp(id: Int) = varps[id] ?: 0
    override fun skillLevel(skill: String) = levels[skill] ?: 1
    override fun inventory(): Map<Int, Int> = inv
    override fun equipment(): Map<Int, Int> = worn
    override fun bankCount(itemId: Int) = bank[itemId] ?: 0

    override fun nearestNpc(ids: Collection<Int>, names: Collection<String>) = nearest(npcs, ids, names)
    override fun nearestObject(ids: Collection<Int>, names: Collection<String>) = nearest(objects, ids, names)

    val groundItems = mutableListOf<SceneEntity>()
    override fun nearestGroundItem(ids: Collection<Int>) = nearest(groundItems, ids, emptyList())

    private fun nearest(from: List<SceneEntity>, ids: Collection<Int>, names: Collection<String>): SceneEntity? {
        val at = playerTile ?: return null
        return from
            .filter { if (ids.isNotEmpty()) it.id in ids else names.any { n -> n.equals(it.name, ignoreCase = true) } }
            .filter { it.tile.distanceTo(at) <= 20 }
            .minByOrNull { it.tile.distanceTo(at) }
    }

    override fun dialogOptions() = options
    override fun currentDialog(): String? = null
    override fun dialogHistory(): List<String> = history
    val itemMenus = mutableMapOf<Int, List<String>>()
    override fun itemOptions(itemId: Int): List<String> = itemMenus[itemId].orEmpty()
    override fun widgetText(group: Int, child: Int): String? = null
    override fun isInterfaceOpen(packedWidgetId: Int) = false

    override fun walkTo(tile: Tile): Boolean {
        calls += "walk $tile"
        playerTile = tile
        return true
    }

    override fun interactNpc(npc: SceneEntity, action: String?): Boolean {
        calls += "npc ${npc.name} $action"
        onNpcInteract(npc, action)
        return true
    }

    override fun interactObject(obj: SceneEntity, action: String?): Boolean {
        calls += "object ${obj.name} $action"
        onObjectInteract(obj, action)
        return true
    }

    override fun continueDialog(): Boolean {
        calls += "continue"
        onContinue()
        return true
    }

    override fun chooseDialogOption(index: Int): Boolean = chooseDialogOption(options[index - 1])

    override fun chooseDialogOption(text: String): Boolean {
        calls += "option $text"
        options = emptyList()
        onOption(text)
        return true
    }

    override fun openNearestBank(): Boolean {
        calls += "open bank"
        isBankOpen = true
        return true
    }

    override fun depositAll(): Boolean {
        calls += "deposit all"
        inv.forEach { (id, n) -> bank.merge(id, n, Int::plus) }
        inv.clear()
        return true
    }

    override fun withdraw(itemId: Int, quantity: Int): Boolean {
        calls += "withdraw $itemId x$quantity"
        val have = bank[itemId] ?: 0
        val take = if (quantity <= 0) have else minOf(quantity, have)
        bank[itemId] = have - take
        inv.merge(itemId, take, Int::plus)
        return true
    }

    override fun closeInterface(): Boolean {
        calls += "close"
        isBankOpen = false
        isShopOpen = false
        return true
    }

    var onCast: (Int) -> Unit = {}
    override fun castSpell(widgetId: Int): Boolean {
        calls += "cast $widgetId"
        onCast(widgetId)
        return true
    }

    override fun itemAction(itemId: Int, action: String): Boolean {
        calls += "item $itemId $action"
        if (action in setOf("Wear", "Wield", "Equip")) {
            inv.remove(itemId)
            worn[itemId] = 1
        }
        if (action == "Eat") {
            inv.computeIfPresent(itemId) { _, n -> if (n > 1) n - 1 else null }
            hitpoints = maxHitpoints
        }
        return true
    }

    override fun useItemOnItem(itemId: Int, targetItemId: Int): Boolean {
        calls += "use $itemId on $targetItemId"
        return true
    }

    override fun useItemOnObject(itemId: Int, obj: SceneEntity): Boolean {
        calls += "use $itemId on ${obj.name}"
        return true
    }

    override fun takeGroundItem(item: SceneEntity): Boolean {
        calls += "take ${item.name}"
        groundItems.remove(item)
        inv.merge(item.id, 1, Int::plus)
        return true
    }

    override fun buy(itemId: Int, quantity: Int): Boolean {
        calls += "buy $itemId x$quantity"
        inv.merge(itemId, quantity, Int::plus)
        return true
    }
}
