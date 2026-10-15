package com.hcimbot.plan

import com.hcimbot.game.GameActions
import com.hcimbot.game.GameState
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile

/**
 * The game actions for somewhere an NPC must not see the player (the witch of Witch's House), which catches anyone in her line of
 * sight. Walks and clicks on things further than a step away become short hops, each only onto a tile that none of the places she could
 * reach in the next moment can see, by the scene's collision data. If no such hop is there the player stays where it is, and the quest
 * step tries again next tick.
 */
class SneakActions(
    private val inner: GameActions,
    private val game: GameState,
    private val watchName: String,
) : GameActions by inner {
    /** The tiles the watcher could be on in a moment: the ones within [REACH] of where it is now. Null if it is not about. */
    private fun zone(): List<Tile>? {
        val at = game.playerTile ?: return null
        val watcher = game.nearestNpc(emptyList(), listOf(watchName)) ?: return null
        if (watcher.tile.plane != at.plane) return null
        return (-REACH..REACH).flatMap { dx -> (-REACH..REACH).map { dy -> Tile(watcher.tile.x + dx, watcher.tile.y + dy, watcher.tile.plane) } }
    }

    private fun safe(tile: Tile, zone: List<Tile>?): Boolean =
        zone == null || zone.none { w -> w.distanceTo(tile) <= SIGHT && game.hasLineOfSight(w, tile) != false }

    fun hereIsSafe(): Boolean {
        val at = game.playerTile ?: return true
        return safe(at, zone())
    }

    /** Steps to the nearest tile within [HIDE_RANGE] that is safe and can be reached. */
    fun hide(log: (String) -> Unit) {
        val at = game.playerTile ?: return
        if (game.isMoving) return
        val zone = zone()
        val spot = (-HIDE_RANGE..HIDE_RANGE).flatMap { dx -> (-HIDE_RANGE..HIDE_RANGE).map { dy -> Tile(at.x + dx, at.y + dy, at.plane) } }
            .filter { it != at && game.canReach(it) != false && safe(it, zone) }
            .minByOrNull { it.distanceTo(at) } ?: return
        log("$watchName could see this tile, stepping out of her sight to $spot")
        inner.walkTo(spot)
    }

    /** One short hop towards [target] onto a safe tile; none made if every one is in view. True either way: the move is under way. */
    private fun hopToward(target: Tile): Boolean {
        val at = game.playerTile ?: return true
        if (game.isMoving) return true
        val zone = zone()
        val here = at.distanceTo(target)
        val hop = (-HOP..HOP).flatMap { dx -> (-HOP..HOP).map { dy -> Tile(at.x + dx, at.y + dy, at.plane) } }
            .filter { it != at && it.distanceTo(target) < here && game.canReach(it) != false && safe(it, zone) }
            .minByOrNull { it.distanceTo(target) }
        if (hop != null) inner.walkTo(hop)
        return true
    }

    override fun walkTo(tile: Tile): Boolean {
        val at = game.playerTile ?: return inner.walkTo(tile)
        return if (at.distanceTo(tile) <= 1) inner.walkTo(tile) else hopToward(tile)
    }

    override fun interactObject(obj: SceneEntity, action: String?): Boolean {
        val at = game.playerTile ?: return inner.interactObject(obj, action)
        return if (at.distanceTo(obj.tile) <= 1) inner.interactObject(obj, action) else hopToward(obj.tile)
    }

    override fun interactNpc(npc: SceneEntity, action: String?): Boolean {
        val at = game.playerTile ?: return inner.interactNpc(npc, action)
        return if (at.distanceTo(npc.tile) <= 1) inner.interactNpc(npc, action) else hopToward(npc.tile)
    }

    private companion object {
        /** How far the watcher might move in the moment it takes to hop. */
        const val REACH = 2
        const val SIGHT = 10
        const val HOP = 2
        const val HIDE_RANGE = 6
    }
}
