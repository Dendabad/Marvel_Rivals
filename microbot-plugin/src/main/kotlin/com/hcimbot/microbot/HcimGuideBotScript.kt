package com.hcimbot.microbot

import com.hcimbot.engine.BotSettings
import com.hcimbot.engine.GuideRunner
import com.hcimbot.guide.Guide
import com.hcimbot.guide.GuideLoader
import com.hcimbot.progress.SharedProgressStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import net.runelite.api.Skill
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.Script
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.util.Text
import org.slf4j.LoggerFactory

/**
 * Runs the guide on Microbot: one runner tick every game tick on the script
 * thread, plus a hitpoint watchdog on its own thread. The walker blocks the
 * script thread for the whole walk, and on a hardcore account the hitpoint
 * checks cannot wait for it to finish.
 */
class HcimGuideBotScript(private val bankPin: () -> String? = { null }) : Script() {
    private val log = LoggerFactory.getLogger(HcimGuideBotScript::class.java)
    private val game = MicrobotGameState()
    private val actions = MicrobotActions()
    @Volatile private var runner: GuideRunner? = null
    private var settings = BotSettings()
    private var shownState: GuideRunner.State? = null
    private var wasPaused = false

    /** Set by the Pause button, hotkey or setting. Read directly, so pausing never waits on a setting being re read. */
    @Volatile private var paused = false
    private val skipRequested = AtomicBoolean(false)
    private val retryRequested = AtomicBoolean(false)
    @Volatile private var statusNow: BotStatus = BotStatus.NOT_STARTED

    private val walkerOverrides = WalkerOverrides()
    @Volatile private var lastDrink = 0L
    @Volatile private var forgetDoorsAt = 0L
    private var pinAttemptedAt = 0L
    private var pinWarned = false
    private var lastSeenAt: net.runelite.api.coords.WorldPoint? = null
    private var stillSince = 0L
    @Volatile private var lastWarning = 0L
    private val recent = ArrayDeque<String>()
    private val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    /** What the on screen box and the sidebar show. */
    fun status(): BotStatus = statusNow.copy(recent = synchronized(recent) { recent.toList() })

    /** Keeps the runner's log for the sidebar's Recent list, and the client log. */
    private fun note(line: String) {
        log.info(line)
        synchronized(recent) {
            recent.addFirst("${java.time.LocalTime.now().format(clock)}  $line")
            while (recent.size > RECENT_LINES) recent.removeLast()
        }
    }

    fun isPaused(): Boolean = paused

    fun setPaused(value: Boolean) {
        paused = value
        // Stop a walk in progress straight away, rather than at the end of it.
        if (value) Rs2Walker.setTarget(null, "HCIM guide bot paused")
        note(if (value) "Paused by you" else "Resumed by you")
    }

    /** The Skip and Retry buttons. Carried out on the bot's own thread at its next tick. */
    fun requestSkip() {
        skipRequested.set(true)
        note("Skip pressed")
    }

    fun requestRetry() {
        retryRequested.set(true)
        note("Retry pressed")
    }


    fun run(config: HcimGuideBotConfig): Boolean {
        settings = settingsFrom(config)
        paused = config.paused()
        note("Started version ${BotModuleImpl.VERSION}")
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay({ tick(config) }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay({ watchdog() }, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS)
        return true
    }

    private fun gate(): Boolean = super.run()

    private fun tick(config: HcimGuideBotConfig) {
        try {
            if (!gate() || !Microbot.isLoggedIn()) return
            val current = runner ?: start(config) ?: return
            // Before the pause check: a player who sets Start at step while the bot is paused (which it starts as)
            // would otherwise never have it applied, and the bot would carry on from where its own ticks say.
            restartIfAsked(config, current)
            if (pausedByPlayer()) return
            if (skipRequested.getAndSet(false)) current.skipStep()
            if (retryRequested.getAndSet(false)) {
                current.replan()
                current.resume()
            }
            game.refresh()
            walkerOverrides.apply(NO_SHIPS + current.walkerOverrides)
            if (answerBankPin()) return
            current.onGameTick()
            // A step that just ended takes its walker overrides with it, before the next one walks.
            walkerOverrides.apply(NO_SHIPS + current.walkerOverrides)
            report(current)
        } catch (e: Exception) {
            if (interrupted(e)) return
            log.error("HCIM guide bot tick failed", e)
            runner?.halt("unexpected error: ${e.message}. Check the client log.")
            runner?.let(::report)
        }
    }

    /**
     * Microbot's walker interrupts the thread that walks when it cancels a walk, and the
     * next wait for the client thread then throws. That is not a fault: clear the flag so
     * the next tick runs normally, rather than halting (or keeping the flag, which makes
     * every later wait fail too). When the bot is being stopped, the flag is left set.
     */
    private fun interrupted(e: Throwable): Boolean {
        val cause = generateSequence(e) { it.cause }.any { it is InterruptedException }
        if (!cause) return false
        if (isRunning) Thread.interrupted() else Thread.currentThread().interrupt()
        log.debug("HCIM guide bot tick interrupted, carrying on", e)
        return true
    }

    /**
     * Paused by the player. Pausing stops any walk at once (the watchdog does
     * that mid-walk) and the bot does nothing until unpaused. Unpausing plans
     * the current step afresh, since the player may have moved.
     */
    private fun pausedByPlayer(): Boolean {
        if (paused) {
            if (!wasPaused) {
                Rs2Walker.setTarget(null, "HCIM guide bot paused")
                wasPaused = true
            }
            val current = runner
            statusNow = BotStatus(
                "PAUSED by you", BotStatus.PAUSED,
                step = current?.let(::stepLine),
                detail = "Press Resume in the sidebar, the pause hotkey, or untick Pause to carry on.",
            )
            return true
        }
        if (wasPaused) {
            wasPaused = false
            runner?.resumeWhereLeft()
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
            statusNow = BotStatus("STOPPED", BotStatus.STOPPED, detail = e.message)
            Microbot.showMessage("HCIM Guide Bot: ${e.message}", MESSAGE_MS)
            shutdown()
            return null
        }
        val configManager = Microbot.getConfigManager()
        val ticker = B0atyGuideTicker({ config.tickB0atyGuide() })
        val progress = SharedProgressStore(
            readPlugin = { configManager.getRSProfileConfiguration(SharedProgressStore.PLUGIN_GROUP, SharedProgressStore.PLUGIN_KEY) },
            readBot = { configManager.getRSProfileConfiguration(SharedProgressStore.BOT_GROUP, SharedProgressStore.BOT_KEY) },
            writeBot = { configManager.setRSProfileConfiguration(SharedProgressStore.BOT_GROUP, SharedProgressStore.BOT_KEY, it) },
            tickInPlugin = { id, done -> ticker.set(id, done) },
        )
        return GuideRunner(guide, game, actions, progress, settings, ::note)
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

    /** Updates the on screen box and sidebar, and pops up a message when the bot pauses or stops itself. */
    private fun report(runner: GuideRunner) {
        val state = runner.state
        statusNow = when (state) {
            GuideRunner.State.Running ->
                BotStatus("Running", BotStatus.RUNNING, step = stepLine(runner), doing = runner.currentTaskDescription)
            is GuideRunner.State.Paused -> BotStatus(
                "PAUSED", BotStatus.PAUSED, step = stepLine(runner),
                detail = "${state.reason}. Do it yourself and the bot carries on once the game shows it done, or press Skip or Retry.",
            )
            is GuideRunner.State.Halted -> BotStatus("STOPPED", BotStatus.STOPPED, step = stepLine(runner), detail = state.reason)
            GuideRunner.State.Finished -> BotStatus("Guide finished", BotStatus.RUNNING)
            GuideRunner.State.Stopped -> BotStatus("Stopped", BotStatus.IDLE)
        }
        if (state != shownState && (state is GuideRunner.State.Paused || state is GuideRunner.State.Halted)) {
            val why = (state as? GuideRunner.State.Paused)?.reason ?: (state as GuideRunner.State.Halted).reason
            // The timed message does not block, which matters when the watchdog thread reports.
            Microbot.showMessage("HCIM Guide Bot paused at ${stepLine(runner)}\n\n$why\n\nDo it yourself and the bot carries on once the game shows it is done. If it does not notice, press Skip in the bot's sidebar (the green H) to mark it done, or Retry to have the bot try again.", MESSAGE_MS)
        }
        shownState = state
    }

    private fun stepLine(runner: GuideRunner): String {
        val step = runner.currentStep ?: return "starting"
        return "Step ${runner.currentStepNumber} of ${runner.stepCount}: ${step.text}"
    }

    /**
     * Hitpoints, checked every 300ms whatever the main loop is doing. At the
     * stop line it cancels any walk and halts the bot. During a walk, when the
     * main loop cannot eat, it eats.
     */
    private fun watchdog() {
        try {
            val current = runner ?: return
            if (paused) {
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
            logStalls(current.acceptWarnings.isNotEmpty())
            if (forgetDoorsAt != 0L && System.currentTimeMillis() >= forgetDoorsAt) {
                forgetDoorsAt = 0L
                WalkerDoors.forget()
            }
            answerWarning(current.acceptWarnings)
            // Where one more hit could be the last, the step says to drink on any damage. That comes
            // before stopping: standing still among monsters is worse than drinking and walking on.
            val drinks = current.drinkOnDamage
            if (now < max && drinks.isNotEmpty()) {
                val drink = drinks.firstOrNull { Rs2Inventory.get(it) != null }
                if (drink != null) {
                    val time = System.currentTimeMillis()
                    if (time - lastDrink >= DRINK_GAP_MS) {
                        lastDrink = time
                        Rs2Inventory.interact(drink, "Drink")
                        note("Took damage ($now/$max), drinking")
                        restartWalkAfterEating()
                    }
                    return
                }
            }
            val percent = now * 100 / max
            if (percent <= settings.stopAtPercent) {
                Rs2Walker.setTarget(null, "HCIM guide bot safety stop")
                current.halt("hitpoints are at $now/$max. Stopped to keep the account safe.")
                report(current)
            } else if (actions.walking && percent <= settings.eatAtPercent) {
                settings.foodIds.firstOrNull { Rs2Inventory.get(it) != null }?.let {
                    Rs2Inventory.interact(it, "Eat")
                    restartWalkAfterEating()
                }
            }
        } catch (e: Exception) {
            if (interrupted(e)) return
            log.error("HCIM guide bot watchdog failed", e)
        }
    }

    /** The PIN changed (set or cleared), so a fresh attempt is allowed. */
    fun pinChanged() {
        pinAttemptedAt = 0L
        pinWarned = false
    }

    /**
     * Types the bank PIN when the PIN screen shows, once per PIN. If the screen shows again soon after,
     * the PIN was refused: the PIN is forgotten, the bot paused and the player told, since a few wrong
     * tries lock the bank for a while. Returns true while the PIN screen needs attention, so the step
     * does not run on top of it.
     */
    private fun answerBankPin(): Boolean {
        val visible = MicrobotGameState.onClient { Rs2Bank.isBankPinWidgetVisible() } == true
        if (!visible) return false
        val pin = bankPin()
        if (pin == null) {
            if (!pinWarned) {
                pinWarned = true
                note("The bank is asking for its PIN: type it into the Bank PIN box in the sidebar")
                Microbot.showMessage("HCIM Guide Bot: the bank wants its PIN. Type it into the Bank PIN box in the bot's sidebar (the green H). It is kept in memory only.", MESSAGE_MS)
            }
            return true
        }
        val time = System.currentTimeMillis()
        if (pinAttemptedAt != 0L && time - pinAttemptedAt < PIN_RETRY_MS) {
            // Shown again straight after typing it: refused. Do not try again.
            pinAttemptedAt = 0L
            setPaused(true)
            note("The bank refused the PIN, so it was forgotten and the bot paused")
            Microbot.showMessage("HCIM Guide Bot: the bank refused the PIN, so the bot paused and forgot it. Enter the right PIN in the sidebar, then resume. Wrong tries can lock the bank for a while, so check it first.", MESSAGE_MS)
            pinRefused()
            return true
        }
        pinAttemptedAt = time
        note("Entering the bank PIN")
        Rs2Bank.handleBankPin(pin)
        return true
    }

    /** Called when the PIN is refused. The loader's copy is cleared by the owner of the secrets. */
    var onPinRefused: () -> Unit = {}

    private fun pinRefused() = onPinRefused()

    /**
     * Times how long the player stands still during a walk, so a slow stretch (a gate, a question, the
     * walker working out its next move) shows in the Recent list with what was on screen. Only logged
     * when it is long enough to notice, and only for steps that opt in.
     */
    private fun logStalls(wanted: Boolean) {
        if (!wanted || !actions.walking) {
            stillSince = 0L
            return
        }
        val at = MicrobotGameState.onClient { Microbot.getClient().localPlayer?.worldLocation } ?: return
        val time = System.currentTimeMillis()
        if (at != lastSeenAt) {
            val stood = if (stillSince == 0L) 0L else time - stillSince
            if (stood >= STALL_LOG_MS) {
                val chat = MicrobotGameState.onClient { Rs2Dialogue.isInDialogue() } == true
                note("Stood still ${"%.1f".format(stood / 1000.0)}s at (${lastSeenAt?.x}, ${lastSeenAt?.y}), chat open: $chat")
            }
            lastSeenAt = at
            stillSince = time
        }
    }

    /**
     * Eating or drinking mid-walk cuts short whatever the walker was doing, often a door, which it
     * then counts as failed and backs off from for minutes. The walk is left running (stopping it
     * would make the walker plan the route again every hit): the walker's door memory is cleared
     * now, and again a moment later once it has noted the cut-short crossing, so it simply clicks
     * the door again.
     */
    private fun restartWalkAfterEating() {
        if (!actions.walking) return
        WalkerDoors.forget()
        forgetDoorsAt = System.currentTimeMillis() + FORGET_AGAIN_MS
    }

    /**
     * Clicks a warning answer the current step allows, like the Stronghold ladders'. Done here
     * because the walker waits on such a warning without answering it, holding the main loop.
     */
    private fun answerWarning(allowed: List<String>) {
        if (allowed.isEmpty()) return
        val time = System.currentTimeMillis()
        if (time - lastWarning < WARNING_GAP_MS) return
        val found = MicrobotGameState.onClient {
            MicrobotGameState.widgetWhere { text -> allowed.any { text.startsWith(it, ignoreCase = true) } }
                ?.let { Text.removeTags(it.text.orEmpty().replace("<br>", " ")) to it.bounds }
        } ?: return
        lastWarning = time
        note("Answering the warning: ${found.first}")
        Microbot.getMouse().click(found.second)
    }

    override fun shutdown() {
        walkerOverrides.clear()
        super.shutdown()
        runner?.stop()
        runner = null
        shownState = null
    }

    companion object {
        private const val TICK_MS = 600L

        /**
         * The walker never takes a boat for the bot. Walking from Port Sarim to Varrock it went for Veos's
         * ship to Land's End (Kourend) instead. Where a boat is wanted (to Port Piscarilius, the minecarts)
         * a travel step does it on purpose.
         */
        private val NO_SHIPS: Map<String, Any> = mapOf(
            "useShips" to false, "useBoats" to false, "useCharterShips" to false, "useCanoes" to false,
        )
        private const val RECENT_LINES = 12
        private const val WATCHDOG_MS = 300L

        /** A drink takes a game tick or two to show in the hitpoints, so do not drink twice for one hit. */
        private const val DRINK_GAP_MS = 1_800L
        private const val WARNING_GAP_MS = 1_500L

        /** Long enough for the walker to have noted a door crossing the drink cut short. */
        private const val FORGET_AGAIN_MS = 1_200L
        private const val STALL_LOG_MS = 1_500L

        /** A refused PIN shows the screen again within this long of typing it. */
        private const val PIN_RETRY_MS = 15_000L
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
