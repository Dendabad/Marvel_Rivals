package com.hcimbot.sdk

import com.hcimbot.game.GameActions
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile

/**
 * The one file to fill in once the Bot Client SDK arrives. Each function
 * should start the action through the SDK and return whether it started.
 *
 * Wiring, once the SDK's script entry point is known:
 *
 *  - Implement [com.hcimbot.game.GameState] on RuneLite's Client (varbits,
 *    varps, skills, inventory, NPCs, objects, widgets). No SDK is needed for it.
 *  - Load the guide with GuideLoader().loadFromPlugin().
 *  - Build a SharedProgressStore on RuneLite's ConfigManager
 *    (getRSProfileConfiguration / setRSProfileConfiguration).
 *  - Create a GuideRunner and call onGameTick() from the GameTick event.
 */
class BotClientActions : GameActions {
    override fun walkTo(tile: Tile): Boolean = sdk()
    override fun interactNpc(npc: SceneEntity, action: String?): Boolean = sdk()
    override fun interactObject(obj: SceneEntity, action: String?): Boolean = sdk()
    override fun continueDialog(): Boolean = sdk()
    override fun chooseDialogOption(index: Int): Boolean = sdk()
    override fun chooseDialogOption(text: String): Boolean = sdk()
    override fun openNearestBank(): Boolean = sdk()
    override fun depositAll(): Boolean = sdk()
    override fun withdraw(itemId: Int, quantity: Int): Boolean = sdk()
    override fun closeInterface(): Boolean = sdk()
    override fun castSpell(widgetId: Int): Boolean = sdk()
    override fun itemAction(itemId: Int, action: String): Boolean = sdk()
    override fun useItemOnItem(itemId: Int, targetItemId: Int): Boolean = sdk()
    override fun useItemOnObject(itemId: Int, obj: SceneEntity): Boolean = sdk()
    override fun buy(itemId: Int, quantity: Int): Boolean = sdk()
    override fun takeGroundItem(item: SceneEntity): Boolean = sdk()

    private fun sdk(): Nothing = TODO("Needs the Bot Client SDK")
}
