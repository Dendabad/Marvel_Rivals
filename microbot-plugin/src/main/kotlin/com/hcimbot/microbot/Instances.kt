package com.hcimbot.microbot

import java.lang.reflect.Method
import net.runelite.api.coords.LocalPoint
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.microbot.Microbot

/**
 * Cutscenes and some quest scenes (Children of the Sun's guard following) run in an instance: a copy of
 * the map shown at far away coordinates, like (14014, 3974). The guide's coordinates are for the real
 * map, so what the bot reads from the game is turned into real map coordinates, and what it asks the
 * game to walk to or click is turned back.
 *
 * The RuneLite calls for this changed between versions (they take the Client or a WorldView), so they
 * are found by name. If anything is missing the point is left as it is, which is right outside
 * instances anyway.
 */
object Instances {
    fun inInstance(): Boolean = runCatching { Microbot.getClient().isInInstancedRegion }.getOrDefault(false)

    /** The real map point for a point the game reports. */
    fun toTemplate(point: WorldPoint): WorldPoint {
        if (!inInstance()) return point
        return runCatching {
            val client = Microbot.getClient()
            val local = call(LocalPoint::class.java, "fromWorld", client, point) as? LocalPoint ?: return point
            val real = call(WorldPoint::class.java, "fromLocalInstance", client, local) as? WorldPoint ?: return point
            // fromLocalInstance returns the point on the player's plane.
            if (real.plane != point.plane) WorldPoint(real.x, real.y, point.plane) else real
        }.getOrDefault(point)
    }

    /** The point in the instance the game shows for a real map point. */
    fun toInstance(point: WorldPoint): WorldPoint {
        if (!inInstance()) return point
        return runCatching {
            val client = Microbot.getClient()
            val found = call(WorldPoint::class.java, "toLocalInstance", client, point) as? Collection<*>
            found?.filterIsInstance<WorldPoint>()?.firstOrNull() ?: point
        }.getOrDefault(point)
    }

    /** Calls the static [name] taking (client or its world view, [second]), whichever this RuneLite has. */
    private fun call(owner: Class<*>, name: String, client: Any, second: Any): Any? {
        val view = runCatching { client.javaClass.getMethod("getTopLevelWorldView").invoke(client) }.getOrNull()
        val methods: List<Method> = owner.methods.filter { it.name == name && it.parameterCount == 2 }
        for (method in methods) {
            val (first, other) = method.parameterTypes
            if (!other.isInstance(second)) continue
            val firstArg = when {
                first.isInstance(client) -> client
                view != null && first.isInstance(view) -> view
                else -> continue
            }
            return method.invoke(null, firstArg, second)
        }
        return null
    }
}
