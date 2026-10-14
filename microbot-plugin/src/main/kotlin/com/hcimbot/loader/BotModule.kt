package com.hcimbot.loader

import com.hcimbot.microbot.HcimGuideBotConfig
import net.runelite.client.input.KeyManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.ui.ClientToolbar
import net.runelite.client.ui.overlay.OverlayManager

/**
 * The bot itself, loaded by [HcimGuideBotPlugin] from hcim-guide-bot-core.jar.
 * Only this interface, [BotHost] and the settings are shared between the two
 * jars, so everything else can be swapped while the game runs.
 */
interface BotModule {
    val version: String

    /** Called on the Swing thread. Starts the bot and adds its on screen box, sidebar and hotkey. */
    fun start(host: BotHost)

    /** Called on the Swing thread. Stops the bot and removes everything [start] added. */
    fun stop()

    /** A setting in the bot's group changed. */
    fun onConfigChanged(key: String, newValue: String?)
}

/** What the loader plugin was given by RuneLite, handed on to the bot. */
class BotHost(
    val plugin: Plugin,
    val config: HcimGuideBotConfig,
    val keyManager: KeyManager,
    val overlayManager: OverlayManager,
    val clientToolbar: ClientToolbar,
    val secrets: Secrets,
)
