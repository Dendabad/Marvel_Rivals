package com.hcimbot.microbot

import com.hcimbot.engine.BotSettings
import com.hcimbot.engine.GuideRunner
import com.hcimbot.guide.Guide
import com.hcimbot.guide.GuideLoader
import com.hcimbot.progress.SharedProgressStore
import java.io.File
import java.util.concurrent.TimeUnit
import net.runelite.api.Skill
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.Script
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import org.slf4j.LoggerFactory

/**
 * Runs the guide on Microbot: one runner tick every game tick on the script
 * thread, plus a hitpoint watchdog on its own thread. The walker blocks the
 * script thread for the whole walk, and on a hardcore account the hitpoint
 * checks cannot wait for it to finish.
 */
class HcimGuideBotScript : Script() {
    private val log = LoggerFactory.getLogger(HcimGuideBotScript::class.java)
    private val game = MicrobotGameState()
    private val actions = MicrobotActions()
    @Volatile private var runner: GuideRunner? = null
    private var settings = BotSettings()
    private var shownState: GuideRunner.State? = null

    fun run(config: HcimGuideBotConfig): Boolean {
        settings = settingsFrom(config)
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay({ tick(config) }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay({ watchdog() }, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS)
        return true
    }

    private fun gate(): Boolean = super.run()

    private fun tick(config: HcimGuideBotConfig) {
        try {
            if (!gate() || !Microbot.isLoggedIn()) return
            val current = runner ?: start(config) ?: return
            game.refresh()
            current.onGameTick()
            report(current)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log.error("HCIM guide bot tick failed", e)
            runner?.halt("unexpected error: ${e.message}. Check the client log.")
            runner?.let(::report)
        }
    }

    /** Loads the guide on the first tick, since it needs the B0aty plugin to be running. */
    private fun start(config: HcimGuideBotConfig): GuideRunner? {
        val guide = try {
            loadGuide(config.guideJsonPath())
        } catch (e: GuideLoader.GuideLoadException) {
            Microbot.status = "HCIM bot: ${e.message}"
            Microbot.showMessage("HCIM Guide Bot: ${e.message}", MESSAGE_MS)
            shutdown()
            return null
        }
        val configManager = Microbot.getConfigManager()
        val progress = SharedProgressStore(
            readPlugin = { configManager.getRSProfileConfiguration(SharedProgressStore.PLUGIN_GROUP, SharedProgressStore.PLUGIN_KEY) },
            readBot = { configManager.getRSProfileConfiguration(SharedProgressStore.BOT_GROUP, SharedProgressStore.BOT_KEY) },
            writeBot = { configManager.setRSProfileConfiguration(SharedProgressStore.BOT_GROUP, SharedProgressStore.BOT_KEY, it) },
        )
        return GuideRunner(guide, game, actions, progress, settings) { log.info(it) }
            .also { it.start(); runner = it }
    }

    /**
     * The guide the B0aty plugin itself uses. Each plugin has its own class
     * loader, so the file is read through the running plugin's.
     */
    private fun loadGuide(path: String): Guide {
        if (path.isNotBlank()) return GuideLoader().loadFromFile(File(path))
        val plugin = Microbot.getPluginManager().plugins.firstOrNull { it.javaClass.name == B0ATY_PLUGIN }
            ?: throw GuideLoader.GuideLoadException(
                "install and enable the B0aty HCIM Guide v3 plugin from the Plugin Hub, or set the guide.json path"
            )
        val stream = plugin.javaClass.classLoader.getResourceAsStream(GuideLoader.PLUGIN_RESOURCE)
            ?: throw GuideLoader.GuideLoadException("the B0aty plugin's guide.json could not be read")
        return stream.use { GuideLoader().load(it) }
    }

    /** Puts the bot's state in Microbot's status line, and pops up a message when it pauses or halts. */
    private fun report(runner: GuideRunner) {
        val state = runner.state
        Microbot.status = when (state) {
            GuideRunner.State.Running -> "HCIM bot: ${runner.currentStep?.text ?: "starting"}"
            is GuideRunner.State.Paused -> "HCIM bot PAUSED: ${state.reason}"
            is GuideRunner.State.Halted -> "HCIM bot STOPPED: ${state.reason}"
            GuideRunner.State.Finished -> "HCIM bot: guide finished"
            GuideRunner.State.Stopped -> "HCIM bot: stopped"
        }
        if (state != shownState && (state is GuideRunner.State.Paused || state is GuideRunner.State.Halted)) {
            val why = (state as? GuideRunner.State.Paused)?.reason ?: (state as GuideRunner.State.Halted).reason
            // The timed message does not block, which matters when the watchdog thread reports.
            Microbot.showMessage("HCIM Guide Bot paused. Do this step yourself, then tick it in the B0aty guide:\n\n$why", MESSAGE_MS)
        }
        shownState = state
    }

    /**
     * Hitpoints, checked every 300ms whatever the main loop is doing. At the
     * stop line it cancels any walk and halts the bot. During a walk, when the
     * main loop cannot eat, it eats.
     */
    private fun watchdog() {
        try {
            val current = runner ?: return
            if (current.state != GuideRunner.State.Running || !Microbot.isLoggedIn()) return
            val (now, max) = MicrobotGameState.onClient {
                val client = Microbot.getClient()
                client.getBoostedSkillLevel(Skill.HITPOINTS) to client.getRealSkillLevel(Skill.HITPOINTS)
            } ?: return
            if (max <= 0) return
            val percent = now * 100 / max
            if (percent <= settings.stopAtPercent) {
                Rs2Walker.setTarget(null, "HCIM guide bot safety stop")
                current.halt("hitpoints are at $now/$max. Stopped to keep the account safe.")
                report(current)
            } else if (actions.walking && percent <= settings.eatAtPercent) {
                settings.foodIds.firstOrNull { Rs2Inventory.get(it) != null }?.let { Rs2Inventory.interact(it, "Eat") }
            }
        } catch (e: Exception) {
            log.error("HCIM guide bot watchdog failed", e)
        }
    }

    override fun shutdown() {
        super.shutdown()
        runner?.stop()
        runner = null
        shownState = null
    }

    companion object {
        private const val TICK_MS = 600L
        private const val WATCHDOG_MS = 300L
        private const val MESSAGE_MS = 60_000
        private const val B0ATY_PLUGIN = "com.b0atyguide.B0atyGuidePlugin"

        fun settingsFrom(config: HcimGuideBotConfig) = BotSettings(
            allowCombat = config.allowCombat(),
            pauseOnAdvice = config.pauseOnTips(),
            eatAtPercent = config.eatAtPercent(),
            stopAtPercent = config.stopAtPercent(),
            foodIds = config.foodIds().split(',').mapNotNull { it.trim().toIntOrNull() },
            pauseWhenAttacked = config.pauseWhenAttacked(),
        )
    }
}
