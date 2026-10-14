package com.hcimbot.microbot

import org.slf4j.LoggerFactory

/**
 * Changes Microbot walker settings for one step without touching the player's
 * own settings. The walker's ShortestPathPlugin keeps a static map of overrides
 * that its path planner reads on every walk, meant for scripts. This puts the
 * step's entries in and takes them out again when the step changes.
 *
 * The map is private, so it is reached by reflection. If Microbot renames it,
 * overrides stop quietly and the walker uses the player's settings.
 */
class WalkerOverrides {
    private val log = LoggerFactory.getLogger(WalkerOverrides::class.java)
    private var applied: Map<String, Any> = emptyMap()
    @Volatile private var broken = false

    /** Makes [wanted] the bot's overrides, removing any earlier ones it set. */
    fun apply(wanted: Map<String, Any>) {
        if (wanted == applied || broken) return
        val map = overrides() ?: return
        synchronized(map) {
            applied.keys.forEach { map.remove(it) }
            map.putAll(wanted)
        }
        log.info("HCIM guide bot walker overrides now {}", wanted)
        applied = wanted
    }

    fun clear() = apply(emptyMap())

    @Suppress("UNCHECKED_CAST")
    private fun overrides(): MutableMap<String, Any>? = try {
        val field = Class.forName(PLUGIN).getDeclaredField("configOverride").apply { isAccessible = true }
        field.get(null) as MutableMap<String, Any>
    } catch (e: Exception) {
        broken = true
        log.warn("Could not change Microbot's walker settings for a step", e)
        null
    }

    private companion object {
        const val PLUGIN = "net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin"
    }
}
