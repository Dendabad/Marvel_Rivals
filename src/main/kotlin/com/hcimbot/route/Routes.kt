package com.hcimbot.route

import com.hcimbot.guide.Tile

/**
 * Walking routes recorded by the player, for steps where the shortest path is
 * not safe ("Head West towards Draynor hugging the fence to avoid the Jail
 * Guards"). A route is the list of tiles the player walked, saved under a name
 * that a step script asks for.
 */
interface RouteStore {
    /** The route's tiles in walking order, or null when it has not been recorded. */
    fun load(name: String): List<Tile>?
    fun save(name: String, tiles: List<Tile>)
}

/** The route file format: one "x,y,plane" per line. Lines starting with # are comments. */
object RouteFormat {
    fun encode(tiles: List<Tile>): String = tiles.joinToString("\n", postfix = "\n") { "${it.x},${it.y},${it.plane}" }

    fun decode(text: String): List<Tile> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(',').map { it.trim().toIntOrNull() }
            if (parts.size == 3 && parts.all { it != null }) Tile(parts[0]!!, parts[1]!!, parts[2]!!) else null
        }
        .toList()
}

/**
 * Collects the tiles the player stands on while recording. Every tile is kept,
 * so the saved route follows the player's exact path; [waypoints] thins it for
 * walking.
 */
class RouteRecorder {
    private val tiles = mutableListOf<Tile>()

    val size: Int get() = tiles.size

    /** Adds the player's tile if it moved. Returns true when it did. */
    fun record(at: Tile?): Boolean {
        if (at == null || tiles.lastOrNull() == at) return false
        tiles += at
        return true
    }

    fun tiles(): List<Tile> = tiles.toList()

    fun clear() = tiles.clear()

    companion object {
        /**
         * Every tile a few steps apart, plus every change of floor and the last
         * tile. Short legs keep the walker on the recorded line: it only plans
         * its own path between two points this close.
         */
        fun waypoints(route: List<Tile>, spacing: Int = 4): List<Tile> {
            if (route.size <= 2) return route
            val kept = mutableListOf(route.first())
            for (i in 1 until route.size) {
                val tile = route[i]
                val last = kept.last()
                val floorChange = tile.plane != route[i - 1].plane
                if (floorChange || last.distanceTo(tile) >= spacing) kept += tile
            }
            if (kept.last() != route.last()) kept += route.last()
            return kept
        }
    }
}
