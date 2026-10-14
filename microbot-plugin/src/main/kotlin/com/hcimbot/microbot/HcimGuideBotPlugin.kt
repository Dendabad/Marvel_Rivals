package com.hcimbot.microbot

import com.google.inject.Provides
import javax.inject.Inject
import javax.swing.SwingUtilities
import net.runelite.client.config.ConfigManager
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.events.ConfigChanged
import net.runelite.client.input.KeyManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.ui.ClientToolbar
import net.runelite.client.ui.NavigationButton
import net.runelite.client.ui.overlay.OverlayManager
import net.runelite.client.util.HotkeyListener

/**
 * The plugin Microbot loads. Turning it on starts the bot from the first step
 * not yet ticked in the B0aty guide. Turning it off stops it.
 *
 * It shows what the bot is doing in a box on the game screen and in a sidebar
 * panel (the green H), which also has Pause, Skip and Retry buttons.
 *
 * Microbot names a sideloaded plugin after its jar, so the jar must be called
 * HcimGuideBotPlugin.jar to match this class.
 */
@PluginDescriptor(
    name = PluginDescriptor.Default + "HCIM Guide Bot",
    description = "Follows the B0aty HCIM Guide v3 plugin step by step, pausing on anything it cannot do safely",
    tags = ["hcim", "b0aty", "guide", "quest", "microbot"],
    enabledByDefault = false,
    isExternal = true,
    version = HcimGuideBotPlugin.VERSION,
)
class HcimGuideBotPlugin : Plugin() {
    @field:Inject
    private lateinit var keyManager: KeyManager

    @field:Inject
    private lateinit var overlayManager: OverlayManager

    @field:Inject
    private lateinit var clientToolbar: ClientToolbar

    private var script: HcimGuideBotScript? = null
    private var pauseKey: HotkeyListener? = null
    private var overlay: HcimGuideBotOverlay? = null
    private var panel: HcimGuideBotPanel? = null
    private var sidebarButton: NavigationButton? = null

    /** RuneLite finds a plugin's settings through a @Provides method like this one. */
    @Provides
    fun provideConfig(configManager: ConfigManager): HcimGuideBotConfig =
        configManager.getConfig(HcimGuideBotConfig::class.java)

    override fun startUp() {
        // Microbot loads every jar in microbot-plugins, so an old copy of the bot left
        // there runs alongside this one, and pausing this one does not pause it.
        if (jarName(this) != JAR && otherCopies().any { jarName(it) == JAR }) {
            Microbot.showMessage(
                "Two copies of HCIM Guide Bot are in your microbot-plugins folder. This one, ${jarName(this)}, " +
                    "will not run. Close Microbot and delete every jar there except $JAR.",
                MESSAGE_MS,
            )
            return
        }
        val configManager = Microbot.getConfigManager()
        val config = configManager.getConfig(HcimGuideBotConfig::class.java)
        val bot = HcimGuideBotScript().also { script = it }
        bot.everyFewTicks = ::stopOtherCopies
        bot.run(config)

        overlay = HcimGuideBotOverlay(bot::status).also { overlayManager.add(it) }

        val sidePanel = HcimGuideBotPanel(
            status = bot::status,
            isPaused = bot::isPaused,
            setPaused = ::setPaused,
            skip = bot::requestSkip,
            retry = bot::requestRetry,
        ).also { panel = it }
        sidebarButton = NavigationButton.builder()
            .tooltip("HCIM Guide Bot")
            .icon(HcimGuideBotPanel.icon())
            .priority(SIDEBAR_PRIORITY)
            .panel(sidePanel)
            .build()
            .also { clientToolbar.addNavigation(it) }

        pauseKey = object : HotkeyListener({ config.pauseHotkey() }) {
            override fun hotkeyPressed() = setPaused(!bot.isPaused())
        }.also { keyManager.registerKeyListener(it) }
    }

    private fun otherCopies(): List<Plugin> =
        Microbot.getPluginManager().plugins.filter { it !== this && it.javaClass.name == javaClass.name }

    /** Stops any other copy of the bot that is running, and says which jar it came from. */
    private fun stopOtherCopies() {
        val manager = Microbot.getPluginManager()
        for (other in otherCopies().filter { manager.isPluginActive(it) }) {
            val name = jarName(other) ?: "another jar"
            script?.warn("Stopped a second copy of the bot from $name")
            Microbot.showMessage(
                "HCIM Guide Bot found a second copy of itself running, from $name, and stopped it. " +
                    "It would keep playing while this one is paused. Close Microbot and delete $name from microbot-plugins.",
                MESSAGE_MS,
            )
            SwingUtilities.invokeLater { runCatching { manager.stopPlugin(other) } }
        }
    }

    /** Pauses or resumes the bot at once, and keeps the Pause checkbox in step with it. */
    private fun setPaused(value: Boolean) {
        script?.setPaused(value)
        Microbot.getConfigManager().setConfiguration(HcimGuideBotConfig.GROUP, HcimGuideBotConfig.PAUSED, value)
    }

    /** The Pause checkbox in the settings. */
    @Subscribe
    fun onConfigChanged(event: ConfigChanged) {
        if (event.group == HcimGuideBotConfig.GROUP && event.key == HcimGuideBotConfig.PAUSED) {
            script?.setPaused(event.newValue == "true")
        }
    }

    override fun shutDown() {
        pauseKey?.let { keyManager.unregisterKeyListener(it) }
        overlay?.let { overlayManager.remove(it) }
        panel?.stop()
        sidebarButton?.let { button -> SwingUtilities.invokeLater { clientToolbar.removeNavigation(button) } }
        pauseKey = null
        overlay = null
        panel = null
        sidebarButton = null
        script?.shutdown()
        script = null
    }

    companion object {
        const val VERSION = "0.4.2"

        /** The jar name Microbot needs for this plugin. Any other name is a stray copy. */
        private const val JAR = "HcimGuideBotPlugin.jar"
        private const val MESSAGE_MS = 120_000

        private fun jarName(plugin: Any): String? =
            runCatching { java.io.File(plugin.javaClass.protectionDomain.codeSource.location.toURI()).name }.getOrNull()
        private const val SIDEBAR_PRIORITY = 8
    }
}
