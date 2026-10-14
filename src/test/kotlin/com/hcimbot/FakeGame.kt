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

    /** Set to make the fake player animating (say, hit) but not walking. Unset, moving follows busy. */
    var movingOverride: Boolean? = null
    override val isMoving: Boolean get() = movingOverride ?: isBusy
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

    override fun nearestNpc(ids: Collection<Int>, names: Collection<String>, near: Tile?) = nearest(npcs, ids, names, near)
    override fun nearestObject(ids: Collection<Int>, names: Collection<String>, near: Tile?) = nearest(objects, ids, names, near)

    val groundItems = mutableListOf<SceneEntity>()
    override fun nearestGroundItem(ids: Collection<Int>) = nearest(groundItems, ids, emptyList(), null)

    private fun nearest(from: List<SceneEntity>, ids: Collection<Int>, names: Collection<String>, near: Tile?): SceneEntity? {
        val at = playerTile ?: return null
        val anchor = near ?: at
        return from
            .filter { if (ids.isNotEmpty()) it.id in ids else names.any { n -> n.equals(it.name, ignoreCase = true) } }
            .filter { it.tile.distanceTo(at) <= 20 }
            .minByOrNull { it.tile.distanceTo(anchor) }
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

    /** Lets a test shut doors: tiles listed here cannot be reached until removed. Empty, the game "cannot say". */
    val unreachable = mutableSetOf<Tile>()
    override fun canReach(tile: Tile): Boolean? = if (unreachable.isEmpty()) null else tile !in unreachable

    /** Lets a test say whether an NPC still lives. Unset, the game "cannot say", as some clients cannot. */
    var alive: ((SceneEntity) -> Boolean?)? = null
    override fun npcAlive(npc: SceneEntity): Boolean? = alive?.invoke(npc)

    /** Entries of an open window menu, like the minecart's destinations. */
    var windowChoices = listOf<String>()
    override fun showsChoice(text: String) = windowChoices.any { it.equals(text, true) || it.endsWith(": $text", true) }
    override fun clickChoice(text: String): Boolean {
        calls += "choose $text"
        windowChoices = emptyList()
        return true
    }

    val npcMenus = mutableMapOf<Int, List<String>>()
    override fun npcActions(npc: SceneEntity): List<String>? = npcMenus[npc.id]

    val spawns = mutableMapOf<String, List<Tile>>()
    override fun npcSpawns(name: String): List<Tile> = spawns[name.lowercase()].orEmpty()

    val objectNames = mutableMapOf<String, Int>()
    override fun objectIdNamed(constant: String): Int? = objectNames[constant]

    override fun stepOnto(tile: Tile): Boolean {
        calls += "step $tile"
        playerTile = tile
        return true
    }

    /** Lets a test make a click fail, as Microbot's does for an option it cannot find. */
    var npcInteractWorks: (SceneEntity, String?) -> Boolean = { _, _ -> true }

    override fun interactNpc(npc: SceneEntity, action: String?): Boolean {
        calls += "npc ${npc.name} $action"
        if (!npcInteractWorks(npc, action)) return false
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
    override fun castSpell(spellName: String, widgetId: Int): Boolean {
        calls += "cast $widgetId"
        onCast(widgetId)
        return true
    }

    var onItemAction: (Int, String) -> Unit = { _, _ -> }

    override fun itemAction(itemId: Int, action: String): Boolean {
        calls += "item $itemId $action"
        onItemAction(itemId, action)
        if (action == "Drop" || action == "Destroy") inv.remove(itemId)
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

    /** Replaces the default pick up of one item, e.g. to take a whole dropped stack. */
    var onTake: ((SceneEntity) -> Unit)? = null

    override fun takeGroundItem(item: SceneEntity): Boolean {
        calls += "take ${item.name}"
        groundItems.remove(item)
        inv.merge(item.id, 1, Int::plus)
        onTake?.invoke(item)
        return true
    }

    var onHop: () -> Unit = {}
    var onSetting: (String) -> Unit = {}

    override fun sell(itemId: Int, quantity: Int): Boolean {
        calls += "sell $itemId x$quantity"
        inv.computeIfPresent(itemId) { _, n -> if (n > quantity) n - quantity else null }
        return true
    }

    override fun depositItem(itemId: Int): Boolean {
        calls += "deposit $itemId"
        inv.remove(itemId)?.let { bank.merge(itemId, it, Int::plus) }
        return true
    }

    override fun hopWorld(): Boolean {
        calls += "hop"
        onHop()
        return true
    }

    override fun useItemOnNpc(itemId: Int, npc: SceneEntity): Boolean {
        calls += "use $itemId on ${npc.name}"
        onNpcInteract(npc, "Use")
        return true
    }

    override fun castOnNpc(spellName: String, npc: SceneEntity): Boolean {
        calls += "cast $spellName on ${npc.name}"
        onNpcInteract(npc, spellName)
        return true
    }

    override fun applySetting(setting: String): Boolean {
        calls += "setting $setting"
        onSetting(setting)
        return true
    }

    /** How many of a purchase the shop actually hands over. Unlimited by default. */
    var buyCap: (Int) -> Int = { it }

    override fun buy(itemId: Int, quantity: Int): Boolean {
        calls += "buy $itemId x$quantity"
        val got = buyCap(quantity)
        if (got > 0) inv.merge(itemId, got, Int::plus)
        return true
    }
}
