package com.chinbot

import com.google.inject.Provides
import javax.inject.Inject
import net.runelite.client.config.ConfigManager
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.events.ConfigChanged
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.ui.overlay.OverlayManager

/**
 * A standalone Microbot plugin that traps black chinchompas with box traps in the Wilderness, around where it is started. Separate
 * from the HCIM Guide Bot and the Quest Helper Bot. Microbot names a sideloaded plugin after its jar, so this jar must be called
 * BlackChinBotPlugin.jar to match this class.
 */
@PluginDescriptor(
    name = PluginDescriptor.Default + "Black Chin Bot",
    description = "Traps black chinchompas with box traps around where you stand, hopping worlds if a player comes near",
    tags = ["hunter", "black chinchompa", "wilderness", "microbot"],
    enabledByDefault = false,
    isExternal = true,
    version = BlackChinBotPlugin.VERSION,
)
class BlackChinBotPlugin : Plugin() {
    @field:Inject
    private lateinit var overlayManager: OverlayManager

    private var script: BlackChinBotScript? = null
    private var overlay: BlackChinBotOverlay? = null

    @Provides
    fun provideConfig(configManager: ConfigManager): BlackChinBotConfig = configManager.getConfig(BlackChinBotConfig::class.java)

    override fun startUp() {
        val config = Microbot.getConfigManager().getConfig(BlackChinBotConfig::class.java)
        val bot = BlackChinBotScript().also { script = it }
        bot.run(config)
        overlay = BlackChinBotOverlay(bot::status).also { overlayManager.add(it) }
    }

    override fun shutDown() {
        overlay?.let { overlayManager.remove(it) }
        overlay = null
        script?.shutdown()
        script = null
    }

    @Subscribe
    fun onConfigChanged(event: ConfigChanged) {
        if (event.group == BlackChinBotConfig.GROUP && event.key == BlackChinBotConfig.PAUSED) script?.setPaused(event.newValue == "true")
    }

    companion object {
        const val VERSION = "1.0.0"
    }
}
