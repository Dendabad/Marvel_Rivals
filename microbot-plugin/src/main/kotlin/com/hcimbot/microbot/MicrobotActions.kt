package com.hcimbot.microbot

import com.hcimbot.game.GameActions
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile
import com.hcimbot.microbot.MicrobotGameState.Companion.onClient
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel as LegacyNpcModel
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.security.LoginManager
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.walker.WalkerState
import net.runelite.client.plugins.skillcalculator.skills.MagicAction

/**
 * [GameActions] on Microbot's helpers. Entities are looked up again by id
 * next to where the bot saw them, since Microbot's entity snapshots go stale.
 * Helpers that block (the walker, banking, buying) are called from the script
 * thread, never the client thread.
 */
class MicrobotActions : GameActions {
    /** True while the walker is running, so the safety watchdog knows the main loop is blocked. */
    @Volatile var walking = false
        private set

    override fun walkTo(tile: Tile): Boolean {
        walking = true
        try {
            return when (Rs2Walker.walkWithState(WorldPoint(tile.x, tile.y, tile.plane), ARRIVE_WITHIN)) {
                WalkerState.ARRIVED, WalkerState.MOVING -> true
                else -> false
            }
        } finally {
            walking = false
        }
    }

    override fun interactNpc(npc: SceneEntity, action: String?): Boolean {
        val found = onClient {
            Microbot.getRs2NpcCache().query().withId(npc.id).nearest(npc.tile.toWorldPoint(), RELOCATE)
        } ?: return false
        // click("") is the left click. The planner never asks for one on an NPC.
        return found.click(action.orEmpty())
    }

    override fun interactObject(obj: SceneEntity, action: String?): Boolean {
        val found = onClient {
            Microbot.getRs2TileObjectCache().query().withId(obj.id).nearest(obj.tile.toWorldPoint(), RELOCATE)
        } ?: return false
        return found.click(action.orEmpty())
    }

    override fun takeGroundItem(item: SceneEntity): Boolean {
        val found = onClient {
            Microbot.getRs2TileItemCache().query().withId(item.id).nearest(item.tile.toWorldPoint(), RELOCATE)
        } ?: return false
        return found.click("Take")
    }

    override fun continueDialog(): Boolean {
        if (!Rs2Dialogue.hasContinue()) return false
        Rs2Dialogue.clickContinue()
        return true
    }

    override fun chooseDialogOption(index: Int): Boolean = Rs2Dialogue.keyPressForDialogueOption(index)

    override fun chooseDialogOption(text: String): Boolean = Rs2Dialogue.keyPressForDialogueOption(text, true)

    override fun openNearestBank(): Boolean = Rs2Bank.openBank() || Rs2Bank.walkToBankAndUseBank()

    override fun depositAll(): Boolean = Rs2Bank.depositAll()

    override fun withdraw(itemId: Int, quantity: Int): Boolean = when {
        quantity == 1 -> Rs2Bank.withdrawOne(itemId)
        quantity > 1 -> Rs2Bank.withdrawX(itemId, quantity)
        // All of a stackable (coins, runes), one of anything else.
        Rs2Bank.getBankItemForSavedId(itemId)?.isStackable == true -> Rs2Bank.withdrawAll(itemId)
        else -> Rs2Bank.withdrawOne(itemId)
    }

    override fun closeInterface(): Boolean {
        if (Rs2Bank.isOpen()) return Rs2Bank.closeBank()
        if (Rs2Shop.isOpen()) Rs2Shop.closeShop()
        return true
    }

    override fun castSpell(spellName: String, widgetId: Int): Boolean {
        val spell = spellFor(spellName) ?: return false
        return Rs2Magic.cast(spell)
    }

    override fun itemAction(itemId: Int, action: String): Boolean =
        if (Rs2Inventory.get(itemId) != null) Rs2Inventory.interact(itemId, action) else Rs2Equipment.interact(itemId, action)

    override fun useItemOnItem(itemId: Int, targetItemId: Int): Boolean = Rs2Inventory.combine(itemId, targetItemId)

    override fun useItemOnObject(itemId: Int, obj: SceneEntity): Boolean = Rs2Inventory.useItemOnObject(itemId, obj.id)

    /** Shops sell in lots of 1, 5, 10 and 50, so a larger or odd amount is made of several clicks. */
    override fun buy(itemId: Int, quantity: Int): Boolean {
        var left = quantity
        var bought = false
        for (lot in SHOP_LOTS) {
            while (left >= lot) {
                if (!Rs2Shop.buyItem(itemId, lot.toString())) return bought
                bought = true
                left -= lot
                Thread.sleep(BUY_PAUSE_MS)
            }
        }
        return bought
    }

    override fun sell(itemId: Int, quantity: Int): Boolean {
        val lot = SHOP_LOTS.firstOrNull { it <= quantity } ?: 1
        return Rs2Inventory.sellItem(itemId, lot.toString())
    }

    override fun depositItem(itemId: Int): Boolean = Rs2Bank.depositAll(itemId)

    override fun hopWorld(): Boolean = Microbot.hopToWorld(LoginManager.getRandomWorld(Rs2Player.isMember()))

    override fun useItemOnNpc(itemId: Int, npc: SceneEntity): Boolean {
        val found = onClient {
            Microbot.getRs2NpcCache().query().withId(npc.id).nearest(npc.tile.toWorldPoint(), RELOCATE)
        } ?: return false
        // Rs2Inventory takes the older util.npc model, which wraps the same NPC.
        return Rs2Inventory.useItemOnNpc(itemId, LegacyNpcModel(found.npc))
    }

    override fun castOnNpc(spellName: String, npc: SceneEntity): Boolean {
        val spell = spellFor(spellName) ?: return false
        val found = onClient {
            Microbot.getRs2NpcCache().query().withId(npc.id).nearest(npc.tile.toWorldPoint(), RELOCATE)
        } ?: return false
        return Rs2Magic.castOn(spell, found.npc)
    }

    override fun applySetting(setting: String): Boolean = when (setting) {
        GameActions.DISABLE_LEVEL_UP -> Rs2Settings.disableLevelUpNotifications()
        else -> false
    }

    companion object {
        private const val ARRIVE_WITHIN = 2

        /** How far an entity may have moved from where the bot saw it a moment ago. */
        private const val RELOCATE = 8

        private val SHOP_LOTS = intArrayOf(50, 10, 5, 1)
        private const val BUY_PAUSE_MS = 700L

        /** guide.json uses RuneLite's spell names, which match Microbot's except for these. */
        private val RENAMED = mapOf(
            "TELEPORT_HOME_STANDARD" to "LUMBRIDGE_HOME_TELEPORT",
            "TELEPORT_TO_YOUR_HOUSE" to "TELEPORT_TO_HOUSE",
            "FORTIS_TELEPORT" to "CIVITAS_ILLA_FORTIS_TELEPORT",
            "KOUREND_TELEPORT" to "KOUREND_CASTLE_TELEPORT",
        )

        fun spellFor(spellName: String): MagicAction? =
            runCatching { MagicAction.valueOf(RENAMED[spellName] ?: spellName) }.getOrNull()

        private fun Tile.toWorldPoint() = WorldPoint(x, y, plane)
    }
}
