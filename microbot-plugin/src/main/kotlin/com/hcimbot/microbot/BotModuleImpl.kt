package com.hcimbot.microbot

import com.hcimbot.loader.BotHost
import com.hcimbot.loader.BotModule
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.ui.NavigationButton
import net.runelite.client.util.HotkeyListener

/**
 * The bot, as the loader plugin starts and stops it. Starting runs the bot
 * from the first step not yet ticked in the B0aty guide, and shows what it is
 * doing in a box on the game screen and in a sidebar panel (the green H), which
 * also has Pause, Skip and Retry buttons.
 *
 * This lives in hcim-guide-bot-core.jar, so a new build replaces it while the
 * game runs.
 */
class BotModuleImpl : BotModule {
    override val version: String = VERSION

    private var host: BotHost? = null
    private var script: HcimGuideBotScript? = null
    private var pauseKey: HotkeyListener? = null
    private var overlay: HcimGuideBotOverlay? = null
    private var panel: HcimGuideBotPanel? = null
    private var sidebarButton: NavigationButton? = null

    override fun start(host: BotHost) {
        this.host = host
        val config = host.config
        val bot = HcimGuideBotScript(host.secrets::bankPin).also { script = it }
        bot.onPinRefused = { host.secrets.forgetBankPin() }
        bot.run(config)

        overlay = HcimGuideBotOverlay(bot::status).also { host.overlayManager.add(it) }

        val sidePanel = HcimGuideBotPanel(
            status = bot::status,
            isPaused = bot::isPaused,
            setPaused = ::setPaused,
            skip = bot::requestSkip,
            retry = bot::requestRetry,
            setBankPin = { pin -> host.secrets.setBankPin(pin).also { if (it) bot.pinChanged() } },
            hasBankPin = host.secrets::hasBankPin,
            report = bot::report,
        ).also { panel = it }
        sidebarButton = NavigationButton.builder()
            .tooltip("HCIM Guide Bot")
            .icon(HcimGuideBotPanel.icon())
            .priority(SIDEBAR_PRIORITY)
            .panel(sidePanel)
            .build()
            .also { host.clientToolbar.addNavigation(it) }

        pauseKey = object : HotkeyListener({ config.pauseHotkey() }) {
            override fun hotkeyPressed() = setPaused(!bot.isPaused())
        }.also { host.keyManager.registerKeyListener(it) }
    }

    /** Pauses or resumes the bot at once, and keeps the Pause checkbox in step with it. */
    private fun setPaused(value: Boolean) {
        script?.setPaused(value)
        Microbot.getConfigManager().setConfiguration(HcimGuideBotConfig.GROUP, HcimGuideBotConfig.PAUSED, value)
    }

    /** The Pause checkbox in the settings. */
    override fun onConfigChanged(key: String, newValue: String?) {
        if (key == HcimGuideBotConfig.PAUSED) script?.setPaused(newValue == "true")
    }

    override fun stop() {
        val host = host ?: return
        pauseKey?.let { host.keyManager.unregisterKeyListener(it) }
        overlay?.let { host.overlayManager.remove(it) }
        panel?.stop()
        // Called on the Swing thread, so the sidebar button goes at once, before a new one is added.
        sidebarButton?.let { host.clientToolbar.removeNavigation(it) }
        pauseKey = null
        overlay = null
        panel = null
        sidebarButton = null
        script?.shutdown()
        script = null
        this.host = null
    }

    companion object {
        const val VERSION = "0.16.58"
        private const val SIDEBAR_PRIORITY = 8
    }
}
