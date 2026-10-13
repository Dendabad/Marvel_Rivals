package com.hcimbot.progress

/** Which guide steps are done. */
interface ProgressStore {
    fun completedIds(): Set<String>
    fun markComplete(stepId: String)
}

/** The B0aty plugin saves progress as step ids joined with commas. */
object ProgressFormat {
    fun decode(stored: String?): Set<String> =
        stored.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

    fun encode(ids: Set<String>): String = ids.joinToString(",")
}

/**
 * Progress shared with the B0aty plugin without fighting it.
 *
 * The plugin keeps its ticks in memory and never reloads them when the config
 * changes underneath it, so writing into its save would be undone on its next
 * save. Instead the bot reads the plugin's ticks (group "b0atyguide", key
 * "progress", on the RuneScape profile) and keeps its own ticks under its own
 * key. A step is done when either side has ticked it, so ticking a step in the
 * plugin's sidebar moves the bot past it.
 *
 * The lambdas wrap RuneLite's ConfigManager.getRSProfileConfiguration and
 * setRSProfileConfiguration, which keeps this class free of RuneLite.
 */
class SharedProgressStore(
    private val readPlugin: () -> String?,
    private val readBot: () -> String?,
    private val writeBot: (String) -> Unit,
) : ProgressStore {
    private val bot: MutableSet<String> by lazy { ProgressFormat.decode(readBot()).toMutableSet() }

    override fun completedIds(): Set<String> = ProgressFormat.decode(readPlugin()) + bot

    override fun markComplete(stepId: String) {
        if (bot.add(stepId)) writeBot(ProgressFormat.encode(bot))
    }

    companion object {
        const val PLUGIN_GROUP = "b0atyguide"
        const val PLUGIN_KEY = "progress"
        const val BOT_GROUP = "hcimguidebot"
        const val BOT_KEY = "progress"
    }
}

class InMemoryProgressStore(initial: Set<String> = emptySet()) : ProgressStore {
    private val done = initial.toMutableSet()
    override fun completedIds(): Set<String> = done.toSet()
    override fun markComplete(stepId: String) {
        done += stepId
    }
}
