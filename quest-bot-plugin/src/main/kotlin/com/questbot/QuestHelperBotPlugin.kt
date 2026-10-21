package com.questbot

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
 * A standalone Microbot plugin: does a quest by following the Quest Helper plugin, and can buy what Quest Helper lists for the quest at
 * the Grand Exchange first. It is separate from the HCIM Guide Bot and does not use the B0aty guide.
 *
 * Microbot names a sideloaded plugin after its jar, so this jar must be called QuestHelperBotPlugin.jar to match this class.
 */
@PluginDescriptor(
    name = PluginDescriptor.Default + "Quest Helper Bot",
    description = "Does a quest by following the Quest Helper plugin, buying its items at the Grand Exchange first",
    tags = ["quest", "quest helper", "grand exchange", "microbot"],
    enabledByDefault = false,
    isExternal = true,
    version = QuestHelperBotPlugin.VERSION,
)
class QuestHelperBotPlugin : Plugin() {
    @field:Inject
    private lateinit var overlayManager: OverlayManager

    private var script: QuestHelperBotScript? = null
    private var overlay: QuestHelperBotOverlay? = null

    @Provides
    fun provideConfig(configManager: ConfigManager): QuestHelperBotConfig = configManager.getConfig(QuestHelperBotConfig::class.java)

    override fun startUp() {
        val config = Microbot.getConfigManager().getConfig(QuestHelperBotConfig::class.java)
        val bot = QuestHelperBotScript().also { script = it }
        bot.run(config)
        overlay = QuestHelperBotOverlay(bot::status).also { overlayManager.add(it) }
    }

    override fun shutDown() {
        overlay?.let { overlayManager.remove(it) }
        overlay = null
        script?.shutdown()
        script = null
    }

    @Subscribe
    fun onConfigChanged(event: ConfigChanged) {
        if (event.group == QuestHelperBotConfig.GROUP && event.key == QuestHelperBotConfig.PAUSED) script?.setPaused(event.newValue == "true")
    }

    companion object {
        const val VERSION = "1.0.0"
    }
}
