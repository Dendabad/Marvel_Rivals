package com.hcimbot.microbot

import com.hcimbot.engine.BotSettings
import com.hcimbot.engine.GuideRunner
import com.hcimbot.guide.Guide
import com.hcimbot.guide.GuideLoader
import com.hcimbot.progress.SharedProgressStore
import com.hcimbot.route.RouteRecorder
import java.io.File
import java.util.concurrent.TimeUnit
import net.runelite.api.Skill
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.Script
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.player.Rs2Player
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
    private val routes = FileRouteStore()
    private val recorder = RouteRecorder()
    @Volatile private var runner: GuideRunner? = null
    private var settings = BotSettings()
    private var shownState: GuideRunner.State? = null
    @Volatile private var config: HcimGuideBotConfig? = null
    private var wasPaused = false

    fun run(config: HcimGuideBotConfig): Boolean {
        this.config = config
        settings = settingsFrom(config)
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay({ tick(config) }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay({ watchdog() }, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS)
        return true
    }

    private fun gate(): Boolean = super.run()

    private fun tick(config: HcimGuideBotConfig) {
        try {
            if (!gate() || !Microbot.isLoggedIn()) return
            if (record(config)) return
            if (pausedByPlayer(config)) return
            val current = runner ?: start(config) ?: return
            restartIfAsked(config, current)
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

    /**
     * While Record route is on, the bot only records: each tile the player
     * stands on is added and the route saved, so closing the client loses
     * nothing. Turning it off ends the recording. Returns true while recording.
     */
    private fun record(config: HcimGuideBotConfig): Boolean {
        val name = config.routeName()
        if (!config.recordRoute()) {
            if (recorder.size > 0) {
                val file = routes.fileFor(name)
                Microbot.showMessage("HCIM Guide Bot saved route \"$name\" (${recorder.size} tiles) to\n$file", MESSAGE_MS)
                recorder.clear()
                // The plan for the current step may have been made before the route existed.
                runner?.resume()
            }
            return false
        }
        val at = MicrobotGameState.onClient {
            Microbot.getClient().localPlayer?.worldLocation?.let { com.hcimbot.guide.Tile(it.x, it.y, it.plane) }
        }
        if (recorder.record(at)) routes.save(name, recorder.tiles())
        Microbot.status = "HCIM bot RECORDING route \"$name\": ${recorder.size} tiles. Turn off Record route to finish."
        return true
    }

    /**
     * The Pause setting or hotkey. Pausing stops any walk at once (the
     * watchdog does that mid-walk) and the bot does nothing until unpaused.
     * Unpausing plans the current step afresh, since the player may have moved.
     */
    private fun pausedByPlayer(config: HcimGuideBotConfig): Boolean {
        if (config.paused()) {
            if (!wasPaused) {
                Rs2Walker.setTarget(null, "HCIM guide bot paused")
                wasPaused = true
            }
            Microbot.status = "HCIM bot PAUSED by you. Untick Pause or press the pause hotkey to carry on."
            return true
        }
        if (wasPaused) {
            wasPaused = false
            runner?.replan()
        }
        return false
    }

    /**
     * Start at step: restart there, then set the setting back to 0 so the next
     * start of the plugin does not wipe the same ticks again.
     */
    private fun restartIfAsked(config: HcimGuideBotConfig, runner: GuideRunner) {
        val number = config.startAtStep()
        if (number <= 0) return
        Microbot.getConfigManager().setConfiguration(HcimGuideBotConfig.GROUP, HcimGuideBotConfig.START_AT_STEP, 0)
        val stillTicked = runner.restartAt(number)
        val note = if (stillTicked.isEmpty()) "" else {
            val shown = stillTicked.take(5).joinToString("\n") { "  ${it.text}" }
            "\n\nThese are ticked in the B0aty guide, so untick them there if you want them redone too:\n$shown" +
                if (stillTicked.size > 5) "\n  and ${stillTicked.size - 5} more" else ""
        }
        Microbot.showMessage("HCIM Guide Bot is starting again at step $number.$note", MESSAGE_MS)
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
        return GuideRunner(guide, game, actions, progress, settings, { log.info(it) }, routes)
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
            GuideRunner.State.Running -> "HCIM bot ${number(runner)}${runner.currentStep?.text ?: "starting"}"
            is GuideRunner.State.Paused -> "HCIM bot PAUSED at ${number(runner)}${state.reason}"
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

    private fun number(runner: GuideRunner): String = runner.currentStepNumber?.let { "step $it: " } ?: ""

    /**
     * Hitpoints, checked every 300ms whatever the main loop is doing. At the
     * stop line it cancels any walk and halts the bot. During a walk, when the
     * main loop cannot eat, it eats.
     */
    private fun watchdog() {
        try {
            val current = runner ?: return
            if (config?.paused() == true) {
                // The main loop may be stuck inside the walker, ours or one Microbot started itself
                // (walking to a bank, or up to an NPC it cannot reach yet), so stop any walk from here.
                if (actions.walking || Rs2Player.isMoving()) Rs2Walker.setTarget(null, "HCIM guide bot paused")
                return
            }
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
