package com.hcimbot.microbot

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Read only: describes the step the Quest Helper plugin is showing, so that what it exposes can be seen in
 * the bot's Recent list. Everything is found by name (Quest Helper is another plugin with no public interface
 * to this), so any failure just gives a short reason instead of an error.
 */
object QuestHelperProbe {
    fun describe(): String {
        return runCatching {
            val plugins = loadedPlugins()
            val plugin = plugins.firstOrNull { it.javaClass.name.endsWith("QuestHelperPlugin") }
                ?: return "Quest Helper plugin not found among ${plugins.size} plugins, quest like: " +
                    plugins.map { it.javaClass.name }.filter { it.contains("quest", ignoreCase = true) }.take(6)
            val quest = call(plugin, "getSelectedQuest") ?: return "Quest Helper has no quest selected"
            var step = call(quest, "getCurrentStep") ?: return "Quest Helper has no current step"
            // A conditional step holds the one actually shown.
            repeat(6) { call(step, "getActiveStep")?.takeIf { it !== step }?.let { step = it } }
            "${quest.javaClass.simpleName}: ${step.javaClass.simpleName} choices=${choices(step)} ${fields(step)}"
        }.getOrElse { "Quest Helper probe failed: ${it.javaClass.simpleName} ${it.message}" }
    }

    /** The step Quest Helper shows, as the bot can use it: its text and the NPC ids it highlights. */
    fun currentStep(): com.hcimbot.game.HelperStep? = runCatching {
        val plugin = loadedPlugins().firstOrNull { it.javaClass.name.endsWith("QuestHelperPlugin") } ?: return null
        val quest = call(plugin, "getSelectedQuest") ?: return null
        var step = call(quest, "getCurrentStep") ?: return null
        repeat(6) { call(step, "getActiveStep")?.takeIf { it !== step }?.let { step = it } }
        val text = (field(step, "text") as? Collection<*>)?.joinToString(" ") { it.toString() }.orEmpty()
        val ids = buildList {
            (field(step, "npcID") as? Number)?.toInt()?.takeIf { it > 0 }?.let(::add)
            (field(step, "alternateNpcIDs") as? Collection<*>)?.forEach { (it as? Number)?.toInt()?.let(::add) }
        }
        val point = field(step, "worldPoint") as? net.runelite.api.coords.WorldPoint
        val tile = point?.let { Instances.toTemplate(it) }?.let { com.hcimbot.guide.Tile(it.x, it.y, it.plane) }
        com.hcimbot.game.HelperStep(text, ids, tile, choices(step))
    }.getOrNull()

    /** The chat options Quest Helper wants picked: its DialogChoiceSteps hold a list of steps with a `choice` text. */
    private fun choices(step: Any): List<String> = runCatching {
        val holder = field(step, "choices") ?: return emptyList()
        val list = (field(holder, "choices") as? Collection<*>) ?: (holder as? Collection<*>) ?: return emptyList()
        list.mapNotNull { item -> item?.let { field(it, "choice") as? String } }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val found = type.declaredFields.firstOrNull { it.name == name }
            if (found != null) return runCatching { found.isAccessible = true; found.get(target) }.getOrNull()
            type = type.superclass
        }
        return null
    }

    private fun loadedPlugins(): List<Any> {
        val runelite = Class.forName("net.runelite.client.RuneLite")
        val injector = runelite.getMethod("getInjector").invoke(null)
        val managerClass = Class.forName("net.runelite.client.plugins.PluginManager")
        val manager = Class.forName("com.google.inject.Injector").getMethod("getInstance", Class::class.java).invoke(injector, managerClass)
        val plugins = managerClass.getMethod("getPlugins").invoke(manager) as Collection<*>
        return plugins.filterNotNull()
    }

    private fun call(target: Any, name: String): Any? =
        runCatching {
            val method = generateSequence(target.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == name && it.parameterCount == 0 } ?: return null
            runCatching { method.isAccessible = true }
            method.invoke(target)
        }.getOrNull()

    /** The step's own fields, short: ids, points, text. */
    private fun fields(step: Any): String {
        val seen = mutableListOf<String>()
        var type: Class<*>? = step.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val value = read(field, step) ?: continue
                seen += "${field.name}=$value"
            }
            type = type.superclass
        }
        return seen.joinToString(" ").take(MAX)
    }

    private fun read(field: Field, step: Any): String? = runCatching {
        field.isAccessible = true
        when (val value = field.get(step)) {
            null -> null
            is Number, is Boolean, is String -> value.toString()
            is Collection<*> -> value.take(8).joinToString(prefix = "[", postfix = "]") { it.toString().take(60) }
            is IntArray -> value.take(8).toString()
            else -> if (value.javaClass.name.startsWith("net.runelite.api.coords")) value.toString() else null
        }
    }.getOrNull()

    private const val MAX = 500
}
