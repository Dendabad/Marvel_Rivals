package com.hcimbot.microbot

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Read only: describes the step the Quest Helper plugin is showing, so that what it exposes can be seen in
 * the bot's Recent list. Everything is found by name (Quest Helper is another plugin with no public interface
 * to this), so any failure just gives a short reason instead of an error.
 */
object QuestHelperProbe {
    /** Why the last [currentStep] gave nothing, or null. */
    @Volatile var problem: String? = null

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

    /** The quest Quest Helper has open, lower case letters and digits only (witchshouse), or null. */
    fun selectedName(): String? = runCatching {
        val plugin = loadedPlugins().firstOrNull { it.javaClass.name.endsWith("QuestHelperPlugin") } ?: return null
        call(plugin, "getSelectedQuest")?.javaClass?.simpleName?.let(::normal)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Lower case letters and digits only, as the bot names quests: Witch's House is witchshouse. */
    fun normalName(name: String): String = normal(name)

    /** The step Quest Helper shows, as the bot can use it: its text and the NPC ids it highlights. */
    fun currentStep(wanted: String? = null): com.hcimbot.game.HelperStep? = runCatching {
        problem = null
        val plugin = loadedPlugins().firstOrNull { it.javaClass.name.endsWith("QuestHelperPlugin") }
        if (plugin == null) { problem = "the Quest Helper plugin is not running"; return null }
        var selected = call(plugin, "getSelectedQuest")
        // The wrong quest open in Quest Helper: open the one being done, if it can be.
        if (wanted != null && (selected == null || !matches(selected.javaClass.simpleName, wanted))) {
            val shown = selected?.javaClass?.simpleName ?: "no quest"
            val helper = helperFor(plugin, wanted)
            if (helper != null && select(plugin, helper)) selected = call(plugin, "getSelectedQuest")
            if (selected == null || !matches(selected.javaClass.simpleName, wanted)) {
                problem = "Quest Helper has $shown open, not $wanted. Select $wanted in the Quest Helper panel"
            }
        }
        // Quest Helper follows one quest at a time. Its step for another quest is no use here, and acting on
        // it would be wrong (the Demon Slayer step while doing Daddy's Home).
        val quest = when {
            wanted == null -> selected
            selected != null && matches(selected.javaClass.simpleName, wanted) -> selected
            else -> helperFor(plugin, wanted)
        } ?: return null
        var step = call(quest, "getCurrentStep") ?: return null
        repeat(6) { call(step, "getActiveStep")?.takeIf { it !== step }?.let { step = it } }
        val text = (field(step, "text") as? Collection<*>)?.joinToString(" ") { it.toString() }.orEmpty()
        val ids = buildList {
            (field(step, "npcID") as? Number)?.toInt()?.takeIf { it > 0 }?.let(::add)
            (field(step, "alternateNpcIDs") as? Collection<*>)?.forEach { (it as? Number)?.toInt()?.let(::add) }
        }
        val point = field(step, "worldPoint") as? net.runelite.api.coords.WorldPoint
        val tile = point?.let { Instances.toTemplate(it) }?.let { com.hcimbot.guide.Tile(it.x, it.y, it.plane) }
        val objects = buildList {
            (field(step, "objectID") as? Number)?.toInt()?.takeIf { it > 0 }?.let(::add)
            (field(step, "alternateObjectIDs") as? Collection<*>)?.forEach { (it as? Number)?.toInt()?.let(::add) }
        }
        com.hcimbot.game.HelperStep(text, ids, tile, choices(step), objects, step.javaClass.simpleName)
    }.getOrNull()

    /**
     * The items Quest Helper lists for [wanted], the needed ones and the recommended ones. Each is an ItemRequirement, read by name: its
     * name, its quantity and every id it accepts. Null when Quest Helper is not running or shows no such quest.
     */
    fun items(wanted: String?): List<com.hcimbot.game.QuestItem>? = runCatching {
        problem = null
        val plugin = loadedPlugins().firstOrNull { it.javaClass.name.endsWith("QuestHelperPlugin") }
        if (plugin == null) { problem = "the Quest Helper plugin is not running"; return null }
        val selected = call(plugin, "getSelectedQuest")
        val quest = when {
            wanted == null -> selected
            selected != null && matches(selected.javaClass.simpleName, wanted) -> selected
            else -> helperFor(plugin, wanted)
        } ?: run { problem = "Quest Helper has no quest called $wanted"; return null }
        // A quest's lists are filled in when it is started up, so a quest not selected may be empty until it is.
        val result = mutableListOf<com.hcimbot.game.QuestItem>()
        for ((method, recommended) in listOf("getItemRequirements" to false, "getItemRecommended" to true)) {
            val list = call(quest, method) as? Collection<*> ?: continue
            for (requirement in list) {
                requirement ?: continue
                val name = (call(requirement, "getName") as? String)?.trim().orEmpty()
                val quantity = (call(requirement, "getQuantity") as? Number)?.toInt() ?: 1
                val ids = ((call(requirement, "getAllIds") as? Collection<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
                    .ifEmpty { listOfNotNull((call(requirement, "getId") as? Number)?.toInt()) }).filter { it > 0 }
                if (name.isNotEmpty() && quantity > 0 && ids.isNotEmpty()) result += com.hcimbot.game.QuestItem(name, ids, quantity, recommended)
            }
        }
        // The same item can be both needed and recommended: needed wins.
        result.groupBy { it.ids.first() }.map { (_, same) -> same.firstOrNull { !it.recommended } ?: same.first() }
    }.getOrElse { problem = "could not read Quest Helper's items: ${it.javaClass.simpleName}"; null }

    /** The chat options Quest Helper wants picked: its DialogChoiceSteps hold a list of steps with a `choice` text. */
    private fun choices(step: Any): List<String> = runCatching {
        val holder = field(step, "choices") ?: return emptyList()
        val list = (field(holder, "choices") as? Collection<*>) ?: (holder as? Collection<*>) ?: return emptyList()
        list.mapNotNull { item -> item?.let { field(it, "choice") as? String } }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** Asks Quest Helper to open [helper], through whichever of its start up calls this version has. */
    private fun select(plugin: Any, helper: Any): Boolean {
        val targets = listOfNotNull(plugin, call(plugin, "getQuestManager"))
        for (target in targets) {
            for (method in target.javaClass.methods.filter { it.name == "startUpQuest" }) {
                val args = method.parameterTypes.map { type ->
                    when {
                        type.isInstance(helper) -> helper
                        type == Boolean::class.javaPrimitiveType -> true
                        else -> null
                    }
                }
                if (args.any { it == null }) continue
                if (runCatching { method.invoke(target, *args.toTypedArray()) }.isSuccess) return true
            }
        }
        return false
    }

    private fun normal(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private fun matches(name: String, wanted: String): Boolean {
        val have = normal(name)
        return have.isNotEmpty() && (have == wanted || wanted.startsWith(have) || have.startsWith(wanted))
    }

    /** The helper for [wanted] from Quest Helper's list of quests, when it is not the one selected. */
    private fun helperFor(plugin: Any, wanted: String): Any? {
        val loader = plugin.javaClass.classLoader
        for (name in listOf("com.questhelper.questinfo.QuestHelperQuest", "com.questhelper.QuestHelperQuest")) {
            val type = runCatching { Class.forName(name, true, loader) }.getOrNull() ?: continue
            val constant = type.enumConstants?.firstOrNull { matches((it as Enum<*>).name, wanted) } ?: continue
            return call(constant, "getQuestHelper")
        }
        return null
    }

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
