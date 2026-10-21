package com.questbot

import com.hcimbot.engine.Conditions
import com.hcimbot.microbot.MicrobotActions
import com.hcimbot.microbot.MicrobotGameState
import com.hcimbot.microbot.QuestHelperProbe
import com.hcimbot.plan.FollowQuestHelperTask
import com.hcimbot.plan.GrandExchangeTask
import com.hcimbot.plan.Task
import com.hcimbot.plan.TaskContext
import com.hcimbot.plan.TaskResult
import java.awt.Color
import java.util.concurrent.TimeUnit
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.Script
import org.slf4j.LoggerFactory

/**
 * Does one quest by following Quest Helper: first, if asked, buying what Quest Helper lists for it at the Grand Exchange, then each
 * instruction Quest Helper shows until it has none left. One tick every game tick on the script thread. It stops with a reason when
 * it meets something it cannot do (an item to use on something, a fight it will not start), and carries on when Resume is pressed.
 */
class QuestHelperBotScript : Script() {
    private val log = LoggerFactory.getLogger(QuestHelperBotScript::class.java)
    private val game = MicrobotGameState()
    private val actions = MicrobotActions()

    @Volatile private var paused = false
    @Volatile private var task: Task? = null
    @Volatile private var finished = false
    @Volatile private var state = "Starting"
    @Volatile private var stateColor = IDLE
    @Volatile private var detail: String? = null
    @Volatile private var doing: String? = null
    private val recent = ArrayDeque<String>()
    private var config: QuestHelperBotConfig? = null
    private var quest = ""
    private val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    fun run(config: QuestHelperBotConfig): Boolean {
        this.config = config
        paused = config.paused()
        note("Started")
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay({ tick() }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        return true
    }

    fun setPaused(value: Boolean) {
        paused = value
        note(if (value) "Paused by you" else "Resumed by you")
    }

    /** The lines the on screen box shows. */
    fun status(): List<Pair<String, Color>> = buildList {
        add(state to stateColor)
        doing?.let { add("Doing: $it" to Color.LIGHT_GRAY) }
        detail?.let { add(it to stateColor) }
        synchronized(recent) { recent.take(RECENT_SHOWN) }.forEach { add(it to Color.GRAY) }
    }

    private fun note(line: String) {
        log.info("Quest helper bot: {}", line)
        synchronized(recent) {
            recent.addFirst("${java.time.LocalTime.now().format(clock)}  $line")
            while (recent.size > RECENT_KEPT) recent.removeLast()
        }
    }

    private fun gate(): Boolean = super.run()

    private fun tick() {
        try {
            if (!gate() || !Microbot.isLoggedIn()) return
            if (paused) { show("PAUSED", PAUSED_COLOR, detail ?: "Untick Pause to carry on."); return }
            if (finished) return
            val current = task ?: start() ?: return
            game.refresh()
            val ctx = TaskContext(game, actions, Conditions(game)) { note(it) }
            doing = current.description
            when (val result = current.tick(ctx)) {
                TaskResult.Running -> show("Running", RUNNING_COLOR, null)
                TaskResult.Done -> {
                    finished = true
                    note("Done: $quest")
                    show("DONE", RUNNING_COLOR, "The quest is finished.")
                }
                is TaskResult.Failed -> {
                    note("Paused: ${result.reason}")
                    // Resume tries again from a fresh task, so what was fixed by hand is picked up.
                    task = null
                    paused = true
                    show("PAUSED", PAUSED_COLOR, "${result.reason}. Fix it, then untick Pause to carry on.")
                }
                else -> show("Running", RUNNING_COLOR, null)
            }
        } catch (e: Exception) {
            if (e is InterruptedException || e.cause is InterruptedException) return
            log.error("Quest helper bot tick failed", e)
            note("Error: ${e.message}")
            paused = true
            show("PAUSED", PAUSED_COLOR, "Unexpected error: ${e.message}. Check the client log.")
        }
    }

    /** The task for the quest: the Grand Exchange first if asked, then Quest Helper's own steps. Null while Quest Helper shows nothing yet. */
    private fun start(): Task? {
        val cfg = config ?: return null
        val typed = cfg.quest().trim()
        quest = if (typed.isEmpty()) QuestHelperProbe.selectedName() ?: run {
            show("PAUSED", PAUSED_COLOR, "Open a quest in Quest Helper, or type its name in the Quest setting.")
            paused = true
            return null
        } else QuestHelperProbe.normalName(typed)
        note("Quest: $quest")
        val tasks = buildList<Task> {
            if (cfg.buyItems()) add(GrandExchangeTask(quest, includeRecommended = cfg.includeRecommended()))
            add(FollowQuestHelperTask(quest, allowCombat = cfg.allowCombat()))
        }
        return Sequence(tasks).also { task = it }
    }

    private fun show(newState: String, color: Color, why: String?) {
        state = newState
        stateColor = color
        detail = why
    }

    /** Runs its tasks one after the other. */
    private class Sequence(private val tasks: List<Task>) : Task {
        private var index = 0
        override val description get() = tasks.getOrNull(index)?.description ?: "Finished"

        override fun tick(ctx: TaskContext): TaskResult {
            val current = tasks.getOrNull(index) ?: return TaskResult.Done
            return when (val result = current.tick(ctx)) {
                TaskResult.Done -> { index++; if (index >= tasks.size) TaskResult.Done else TaskResult.Running }
                else -> result
            }
        }
    }

    override fun shutdown() {
        super.shutdown()
        task = null
    }

    private companion object {
        const val TICK_MS = 600L
        const val RECENT_KEPT = 12
        const val RECENT_SHOWN = 4
        val RUNNING_COLOR = Color(0x4C, 0xAF, 0x50)
        val PAUSED_COLOR = Color(0xFF, 0xB3, 0x00)
        val IDLE = Color(0xB0, 0xB0, 0xB0)
    }
}
