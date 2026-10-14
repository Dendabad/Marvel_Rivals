package com.hcimbot.microbot

import javax.swing.SwingUtilities
import net.runelite.client.plugins.microbot.Microbot
import org.slf4j.LoggerFactory

/**
 * Ticks steps in the B0aty plugin's sidebar the way a click does. The plugin
 * keeps its ticks in memory and saves over its config, so writing the config
 * would be undone. Instead this calls the plugin's own click handler,
 * onStepToggled, which ticks the step, saves and moves its sidebar on.
 *
 * That handler is private to the plugin, so it is found by reflection. If the
 * plugin changes and it cannot be found, ticking stops quietly and the bot
 * carries on with its own record, as before.
 */
class B0atyGuideTicker(
    private val enabled: () -> Boolean,
    private val findPlugin: () -> Any? = { Microbot.getPluginManager().plugins.firstOrNull { it.javaClass.name == PLUGIN } },
) {
    private val log = LoggerFactory.getLogger(B0atyGuideTicker::class.java)
    @Volatile private var broken = false

    fun set(stepId: String, done: Boolean) {
        if (broken || !enabled()) return
        SwingUtilities.invokeLater {
            try {
                val plugin = findPlugin() ?: return@invokeLater
                val step = findStep(plugin, stepId) ?: return@invokeLater
                val toggle = plugin.javaClass.getDeclaredMethod("onStepToggled", step.javaClass, java.lang.Boolean.TYPE)
                toggle.isAccessible = true
                toggle.invoke(plugin, step, done)
            } catch (e: ReflectiveOperationException) {
                broken = true
                log.warn("Could not tick steps in the B0aty guide, so only the bot will remember them", e)
            } catch (e: RuntimeException) {
                broken = true
                log.warn("Could not tick steps in the B0aty guide, so only the bot will remember them", e)
            }
        }
    }

    private fun findStep(plugin: Any, stepId: String): Any? {
        val field = plugin.javaClass.getDeclaredField("guide").apply { isAccessible = true }
        val guide = field.get(plugin) ?: return null
        val sections = guide.javaClass.getMethod("getSections").invoke(guide) as List<*>
        for (section in sections) {
            val steps = section?.javaClass?.getMethod("getSteps")?.invoke(section) as? List<*> ?: continue
            steps.firstOrNull { step -> step?.javaClass?.getMethod("getId")?.invoke(step) == stepId }?.let { return it }
        }
        return null
    }

    companion object {
        private const val PLUGIN = "com.b0atyguide.B0atyGuidePlugin"
    }
}
