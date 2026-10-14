package com.hcimbot.microbot

import com.google.inject.Provides
import javax.inject.Inject
import net.runelite.client.config.ConfigManager
import net.runelite.client.input.KeyManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.util.HotkeyListener

/**
 * The plugin Microbot loads. Turning it on starts the bot from the first step
 * not yet ticked in the B0aty guide. Turning it off stops it.
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

    private var script: HcimGuideBotScript? = null
    private var pauseKey: HotkeyListener? = null

    /** RuneLite finds a plugin's settings through a @Provides method like this one. */
    @Provides
    fun provideConfig(configManager: ConfigManager): HcimGuideBotConfig =
        configManager.getConfig(HcimGuideBotConfig::class.java)

    override fun startUp() {
        val configManager = Microbot.getConfigManager()
        val config = configManager.getConfig(HcimGuideBotConfig::class.java)
        script = HcimGuideBotScript().also { it.run(config) }
        // The hotkey flips the Pause setting, so the checkbox always shows the real state.
        pauseKey = object : HotkeyListener({ config.pauseHotkey() }) {
            override fun hotkeyPressed() {
                configManager.setConfiguration(HcimGuideBotConfig.GROUP, HcimGuideBotConfig.PAUSED, !config.paused())
            }
        }.also { keyManager.registerKeyListener(it) }
    }

    override fun shutDown() {
        pauseKey?.let { keyManager.unregisterKeyListener(it) }
        pauseKey = null
        script?.shutdown()
        script = null
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}
