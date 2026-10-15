package com.hcimbot.microbot

import com.hcimbot.game.GameActions
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile
import com.hcimbot.microbot.MicrobotGameState.Companion.onClient
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc
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
        // In an instance the walker's map does not apply, and the places are close: click along the canvas.
        if (Instances.inInstance()) return Rs2Walker.walkFastCanvas(tile.toWorldPoint())
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

    /**
     * Microbot's "nearest bank" is the end of the shortest route counting boats and teleports, so from
     * Port Sarim it picked Land's End in Kourend by way of Veos's ship. Choose by distance on the map
     * instead, among banks the player may use, and walk to that one.
     */
    override fun openNearestBank(): Boolean {
        if (Rs2Bank.openBank()) return true
        val chosen = onClient { Rs2Player.getWorldLocation()?.let { here -> closestBank(here) } }
        return if (chosen != null) Rs2Bank.walkToBankAndUseBank(chosen) else Rs2Bank.walkToBankAndUseBank()
    }

    private fun closestBank(here: WorldPoint): BankLocation? =
        BankLocation.values()
            .filter { it.worldPoint.plane == here.plane && it.hasRequirements() }
            .minByOrNull { it.worldPoint.distanceTo2D(here) }
            ?.takeIf { it.worldPoint.distanceTo2D(here) <= MAX_BANK_DISTANCE }

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
        if (Omnishop.isOpen()) return Omnishop.close()
        return true
    }

    /**
     * In the make window: clicks the All quantity, then the first item (the leftmost), by clicking them. Pressing
     * Space did nothing here, and the chat's own All tab has the same text, so the buttons are found beside the
     * quantity buttons.
     */
    override fun makeAll(): Boolean {
        // The window that asks for "sets" has no All: its biggest button is 10.
        val all = onClient {
            (MicrobotGameState.makeWindowButton("All", useParent = false) ?: MicrobotGameState.makeWindowButton("10", useParent = false))?.bounds
        }
        if (all != null) {
            Microbot.getMouse().click(all)
            Thread.sleep(MAKE_PAUSE_MS)
        }
        // The 1 key picks the first item, whatever the Space key is on at the moment.
        if (pressKey('1')) return true
        val first = onClient { MicrobotGameState.firstMakeItem() }
        if (first != null) {
            Microbot.getMouse().click(first)
            return true
        }
        // Not found: not the Space key, which makes whichever item was made last.
        return false
    }

    /** The Furniture Creation Menu's first piece: its cost line, which sits in its row, else the 1 key. */
    override fun pickFurniture(): Boolean {
        val row = onClient {
            MicrobotGameState.widgetWhere { it.startsWith("Plank:") || it.startsWith("Nails:") || it.startsWith("Bolt") || it.startsWith("Level") }?.bounds
        }
        if (row != null) {
            Microbot.getMouse().click(row)
            return true
        }
        return pressKey('1')
    }

    /** The quantity button, then the [item]th item from the left, both by clicking. */
    override fun makePick(quantity: String, item: Int): Boolean {
        val quantityButton = onClient { MicrobotGameState.makeWindowButton(quantity, useParent = false)?.bounds }
        if (quantityButton != null) {
            Microbot.getMouse().click(quantityButton)
            Thread.sleep(MAKE_PAUSE_MS)
        }
        val chosen = onClient { MicrobotGameState.nthMakeItem(item) } ?: return false
        Microbot.getMouse().click(chosen)
        return true
    }

    /** A key press through Microbot's keyboard helper, found by name since the stub used to build does not have it. */
    private fun pressKey(key: Char): Boolean = runCatching {
        val keyboard = Class.forName("net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard")
        val press = keyboard.methods.firstOrNull { it.name == "keyPress" && it.parameterTypes.singleOrNull() == Char::class.javaPrimitiveType }
            ?: return false
        press.invoke(null, key)
        true
    }.getOrDefault(false)

    /**
     * Clicks the Close button of the open window (a book, say), found as a visible widget whose action is
     * "Close". Falls back to Escape, by name since Microbot's keyboard helper is not in the stub used to build.
     */
    override fun closeWindow(): Boolean {
        val bounds = onClient { MicrobotGameState.widgetWithAction("Close")?.bounds }
        if (bounds != null) {
            Microbot.getMouse().click(bounds)
            return true
        }
        return runCatching {
            Class.forName("net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard")
                .getMethod("keyPress", Int::class.javaPrimitiveType).invoke(null, java.awt.event.KeyEvent.VK_ESCAPE)
            true
        }.getOrDefault(false)
    }

    override fun castSpell(spellName: String, widgetId: Int): Boolean {
        val spell = spellFor(spellName) ?: return false
        return Rs2Magic.cast(spell)
    }

    override fun clickChoice(text: String): Boolean {
        val bounds = onClient { MicrobotGameState.choiceWidget(text)?.bounds } ?: return false
        Microbot.getMouse().click(bounds)
        return true
    }

    /** Selects the item, then clicks the ground item: Microbot turns that click into "use on". */
    override fun useItemOnGroundItem(itemId: Int, item: SceneEntity): Boolean {
        val found = onClient {
            Microbot.getRs2TileItemCache().query().withId(item.id).nearest(item.tile.toWorldPoint(), RELOCATE)
        } ?: return false
        if (!Rs2Inventory.use(itemId)) return false
        val deadline = System.currentTimeMillis() + SELECT_MS
        while (onClient { Microbot.getClient().isWidgetSelected } != true) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(POLL_MS)
        }
        return found.click("")
    }

    override fun stepOnto(tile: Tile): Boolean = Rs2Walker.walkFastCanvas(tile.toWorldPoint())

    override fun itemAction(itemId: Int, action: String): Boolean =
        if (Rs2Inventory.get(itemId) != null) Rs2Inventory.interact(itemId, action) else Rs2Equipment.interact(itemId, action)

    /** The item's name is read by name, since the Rs2ItemModel in the stub used to build has no getName. */
    override fun itemActionByName(part: String, action: String): Boolean {
        val id = Rs2Inventory.items().collect(java.util.stream.Collectors.toList()).firstOrNull { item ->
            runCatching { (item.javaClass.getMethod("getName").invoke(item) as? String)?.contains(part, ignoreCase = true) }.getOrNull() == true
        }?.id ?: return false
        return Rs2Inventory.interact(id, action)
    }

    override fun useItemOnItem(itemId: Int, targetItemId: Int): Boolean = Rs2Inventory.combine(itemId, targetItemId)

    override fun useItemOnObject(itemId: Int, obj: SceneEntity): Boolean = Rs2Inventory.useItemOnObject(itemId, obj.id)

    /** Shops sell in lots of 1, 5, 10 and 50, so a larger or odd amount is made of several clicks. */
    override fun buy(itemId: Int, quantity: Int): Boolean {
        // The newer shop window sells one per click. The shop task asks again until it has enough.
        if (Omnishop.isOpen()) return Omnishop.buy(itemId)
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

    /** A random world, asked for again until it is one not in [avoid], up to 40 times, then whatever it gave last. */
    override fun hopWorldAvoiding(avoid: Set<Int>): Boolean {
        val here = onClient { Microbot.getClient().world } ?: -1
        var pick = LoginManager.getRandomWorld(Rs2Player.isMember())
        var tries = 0
        while ((pick == here || pick in avoid) && ++tries < 40) pick = LoginManager.getRandomWorld(Rs2Player.isMember())
        return Microbot.hopToWorld(pick)
    }

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
        // Rs2Magic.castOn only clicks the target when handed Microbot's NPC wrapper, and
        // otherwise leaves the spell selected and does nothing. So do its steps here.
        if (!Rs2Magic.cast(spell)) return false
        val deadline = System.currentTimeMillis() + SELECT_MS
        while (onClient { Microbot.getClient().isWidgetSelected } != true) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(POLL_MS)
        }
        // With a spell selected, the NPC's first option is "Cast <spell> -> <npc>".
        return Rs2Npc.interact(LegacyNpcModel(found.npc))
    }

    override fun applySetting(setting: String): Boolean = when (setting) {
        GameActions.DISABLE_LEVEL_UP -> Rs2Settings.disableLevelUpNotifications()
        else -> false
    }

    companion object {
        private const val ARRIVE_WITHIN = 2

        /** A bank further than this is left to Microbot's own choice, which may use transport. */
        private const val MAX_BANK_DISTANCE = 250
        private const val SELECT_MS = 1_500L
        private const val POLL_MS = 50L
        private const val MAKE_PAUSE_MS = 400L

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

        private fun Tile.toWorldPoint() = Instances.toInstance(WorldPoint(x, y, plane))
    }
}
