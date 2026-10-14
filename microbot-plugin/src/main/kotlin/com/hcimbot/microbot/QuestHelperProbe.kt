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
            val plugin = questHelperPlugin() ?: return "Quest Helper plugin not found"
            val quest = call(plugin, "getSelectedQuest") ?: return "Quest Helper has no quest selected"
            var step = call(quest, "getCurrentStep") ?: return "Quest Helper has no current step"
            // A conditional step holds the one actually shown.
            repeat(6) { call(step, "getActiveStep")?.takeIf { it !== step }?.let { step = it } }
            "${quest.javaClass.simpleName}: ${step.javaClass.simpleName} ${fields(step)}"
        }.getOrElse { "Quest Helper probe failed: ${it.javaClass.simpleName} ${it.message}" }
    }

    private fun questHelperPlugin(): Any? {
        val runelite = Class.forName("net.runelite.client.RuneLite")
        val injector = runelite.getMethod("getInjector").invoke(null)
        val managerClass = Class.forName("net.runelite.client.plugins.PluginManager")
        val manager = injector.javaClass.getMethod("getInstance", Class::class.java).invoke(injector, managerClass)
        val plugins = managerClass.getMethod("getPlugins").invoke(manager) as Collection<*>
        return plugins.firstOrNull { it?.javaClass?.name == "com.questhelper.QuestHelperPlugin" }
    }

    private fun call(target: Any, name: String): Any? =
        runCatching {
            val method = generateSequence(target.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == name && it.parameterCount == 0 } ?: return null
            method.isAccessible = true
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
