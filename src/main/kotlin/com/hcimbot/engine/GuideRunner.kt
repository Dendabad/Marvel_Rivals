package com.hcimbot.engine

import com.hcimbot.game.GameActions
import com.hcimbot.game.GameState
import com.hcimbot.guide.Guide
import com.hcimbot.guide.Step
import com.hcimbot.plan.AgilityCourse
import com.hcimbot.plan.AgilityCourseTask
import com.hcimbot.plan.DialoguePlan
import com.hcimbot.plan.StepPlan
import com.hcimbot.plan.StepPlanner
import com.hcimbot.plan.Task
import com.hcimbot.plan.TaskContext
import com.hcimbot.plan.TaskResult
import com.hcimbot.progress.ProgressStore

/**
 * Follows the B0aty guide one step at a time. Call [onGameTick] from the
 * client's GameTick event.
 *
 * The current step is always the first one in guide order that is not ticked,
 * in the plugin or by the bot, so the bot picks up wherever the player is. When
 * it pauses, ticking the step in the plugin's sidebar (or calling [resume])
 * carries it on.
 */
class GuideRunner(
    private val guide: Guide,
    private val game: GameState,
    private val actions: GameActions,
    private val progress: ProgressStore,
    private val settings: BotSettings = BotSettings(),
    private val log: (String) -> Unit = {},
) {
    sealed interface State {
        object Stopped : State
        object Running : State
        data class Paused(val step: Step, val reason: String) : State
        data class Halted(val reason: String) : State
        object Finished : State
    }

    /** Volatile because a safety watchdog on another thread may [halt] the runner. */
    @Volatile
    var state: State = State.Stopped
        private set

    val currentStep: Step? get() = active?.step

    /** What the bot is doing within the current step, e.g. "Walk to (3218, 3238, 0)", for the on screen display. */
    val currentTaskDescription: String?
        get() {
            val current = active ?: return null
            val plan = current.plan as? StepPlan.Auto ?: return null
            return plan.tasks.getOrNull(current.taskIndex)?.description
        }

    val stepCount: Int get() = guide.steps.size

    /** Walker settings the current step changes for itself. Applied by the Microbot side each tick. */
    val walkerOverrides: Map<String, Any> get() = (active?.plan as? StepPlan.Auto)?.walkerOverrides.orEmpty()

    /** Warning answers the current step may click. Read by the watchdog, which also runs mid-walk. */
    val acceptWarnings: List<String> get() = (active?.plan as? StepPlan.Auto)?.acceptWarnings.orEmpty()

    /** What the current step says to drink on any damage. Read by the hitpoint watchdog. */
    val drinkOnDamage: List<Int> get() = (active?.plan as? StepPlan.Auto)?.drinkOnDamage.orEmpty()

    /** The current step's 1 based place in the guide, matching docs/first-100-steps.md. */
    val currentStepNumber: Int? get() = active?.step?.let { step -> guide.steps.indexOf(step) + 1 }

    /** Steps before this index count as done for this session, see [restartAt]. */
    private var floor = 0

    /** Earlier steps found undone, run before carrying on. See [TaskResult.NeedsStep]. */
    private val detours = ArrayDeque<Step>()
    private val detoured = mutableSetOf<String>()

    /** The step [restartAt] began at. It runs even if it is ticked in the plugin, until the bot finishes it. */
    private var redo: String? = null

    private val conditions = Conditions(game)
    private val completion = StepCompletion(guide, game, conditions)
    private val planner = StepPlanner(guide, settings)
    private val safety = SafetyMonitor(settings)
    private val context = TaskContext(game, actions, conditions, log)

    private class Active(val step: Step, val plan: StepPlan, val baseline: StepCompletion.Baseline) {
        var taskIndex = 0
        var ticks = 0
        var waitingAfterTasks = 0
        var chatTicks = 0
        var finishing = false
        val combat: Boolean get() = (plan as? StepPlan.Auto)?.combat == true
    }

    /** Volatile because the on screen display reads it from the UI thread. */
    @Volatile
    private var active: Active? = null

    fun start() {
        if (state !is State.Halted) state = State.Running
    }

    /** Carry on after a pause, starting the step's actions again. A halt (low hitpoints) needs [clearHalt] first. */
    fun resume() {
        if (state is State.Paused) {
            active = null
            state = State.Running
        }
    }

    /** Drops the current step's half done actions so they are planned again from the start (Retry). */
    fun replan() {
        active = null
    }

    /**
     * After the player paused: the current action starts afresh, since the player may have moved,
     * but the actions already done stay done. Starting the step over would, in the Stronghold,
     * walk all the way back to floor 1's reward.
     */
    fun resumeWhereLeft() {
        val old = active ?: return
        active = Active(old.step, planner.plan(old.step), old.baseline).also {
            it.taskIndex = old.taskIndex
            it.ticks = old.ticks
        }
    }

    /** Treat the paused step as done and move on. */
    fun skipCurrent() {
        val step = (state as? State.Paused)?.step ?: return
        markDone(step, "skipped by the player")
        state = State.Running
    }

    /** The player's Skip button: tick the current step, paused or not, and move on. */
    fun skipStep() {
        val step = (state as? State.Paused)?.step ?: active?.step ?: nextStep() ?: return
        markDone(step, "skipped by the player")
        if (state is State.Paused) state = State.Running
    }

    fun stop() {
        state = State.Stopped
        active = null
    }

    /** Stop at once for a safety reason found outside the tick, e.g. by a watchdog during a long walk. */
    fun halt(reason: String) {
        state = State.Halted(reason)
        log("HALTED: $reason")
    }

    /**
     * Starts again at step [number] (1 based), to retest it: the bot forgets its
     * own ticks from that step on and treats earlier steps as done for now.
     * Returns the steps from there on that are still ticked in the plugin,
     * since those are the player's and only the player can untick them.
     */
    fun restartAt(number: Int): List<Step> {
        val from = (number - 1).coerceIn(0, guide.steps.size)
        val later = guide.steps.drop(from)
        progress.forget(later.map { it.id })
        floor = from
        redo = later.firstOrNull()?.id
        active = null
        if (state is State.Paused || state is State.Finished) state = State.Running
        log("Restarting at step $number")
        val stillTicked = progress.completedIds()
        return later.drop(1).filter { it.id in stillTicked }
    }

    fun clearHalt() {
        if (state is State.Halted) state = State.Stopped
    }

    fun onGameTick() {
        if (!game.isLoggedIn) return
        val paused = state as? State.Paused
        if (paused != null) {
            // Ticked in the plugin while we waited, or done by the player and the game shows it: carry on.
            val waiting = active?.takeIf { it.step.id == paused.step.id }
            when {
                paused.step.id in completedIds() -> state = State.Running
                waiting != null && completion.check(paused.step, waiting.baseline) == StepCompletion.Verdict.Done -> {
                    markDone(paused.step, "you did it while the bot waited")
                    state = State.Running
                }
                else -> return
            }
        }
        if (state != State.Running) return

        val step = nextStep()
        if (step == null) {
            state = State.Finished
            log("Guide finished")
            return
        }
        val current = active?.takeIf { it.step.id == step.id } ?: begin(step)

        when (val verdict = safety.check(game, actions, current.combat)) {
            SafetyMonitor.Verdict.Safe -> Unit
            SafetyMonitor.Verdict.Handled -> return
            is SafetyMonitor.Verdict.Pause -> return pause(step, verdict.reason)
            is SafetyMonitor.Verdict.Stop -> return halt(verdict.reason)
        }

        val ignoreSignal = (current.plan as? StepPlan.Auto)?.ignoreStartSignal == true
        if (!ignoreSignal && completion.check(step, current.baseline) == StepCompletion.Verdict.Done && !midway(current)) {
            if (endChat(current)) return
            // Never walk off with a bank or shop still open.
            if (game.isBankOpen || game.isShopOpen) {
                if (!game.isBusy) actions.closeInterface()
                return
            }
            return markDone(step, "the game shows it is done")
        }
        val limit = settings.stepTimeoutTicks * if ((current.plan as? StepPlan.Auto)?.patient == true) PATIENT_FACTOR else 1
        if (++current.ticks > limit) return pause(step, "this step is taking too long")

        when (val plan = current.plan) {
            is StepPlan.Manual -> pause(step, plan.reason)
            is StepPlan.Skip -> markDone(step, plan.reason)
            is StepPlan.Auto -> runTasks(current, plan)
        }
    }

    /** The goal is met, but a task cannot be left yet (on an agility course's rooftops). */
    private fun midway(current: Active): Boolean {
        val task = (current.plan as? StepPlan.Auto)?.tasks?.getOrNull(current.taskIndex) ?: return false
        val stuckIn = !task.canStopHere(context)
        if (stuckIn && !current.finishing) {
            current.finishing = true
            log("Step goal reached. Finishing first: ${task.description}")
        }
        return stuckIn
    }

    /** Set while getting the player down off an agility course before a step that is not about it. */
    private var gettingDown: AgilityCourseTask? = null

    /**
     * On an agility course's rooftops, no other step can do anything: the walker
     * cannot path down, and falls back on teleports. Finish the lap first. True
     * while that is still going on.
     */
    private fun offRooftopsFirst(step: Step, task: Task?): Boolean {
        val course = AgilityCourse.rooftopsAt(game.playerTile)
        if (course == null || task is AgilityCourseTask) {
            gettingDown = null
            return false
        }
        val down = gettingDown ?: AgilityCourseTask(course).also {
            gettingDown = it
            log("On the ${course.name} rooftops, finishing the lap to get down first")
        }
        if (down.canStopHere(context)) return false
        when (val result = down.tick(context)) {
            is TaskResult.Failed -> pause(step, "getting down off the ${course.name}: ${result.reason}")
            else -> Unit
        }
        return true
    }

    private fun runTasks(current: Active, plan: StepPlan.Auto) {
        val step = current.step
        val task: Task? = plan.tasks.getOrNull(current.taskIndex)
        if (offRooftopsFirst(step, task)) return
        if (task != null) {
            when (val result = task.tick(context)) {
                TaskResult.Running -> Unit
                TaskResult.Done -> current.taskIndex++
                is TaskResult.Failed -> pause(step, "${task.description}: ${result.reason}")
                is TaskResult.NeedsStep -> goBack(step, result)
            }
            return
        }

        // Every task finished. A chat the step left open (King Roald's after Priest in Peril starts) would
        // make the next step's clicks and options go to the wrong person, so end it first.
        if (endChat(current)) return
        when (completion.check(step, current.baseline)) {
            StepCompletion.Verdict.Done -> markDone(step, "the game shows it is done")
            StepCompletion.Verdict.NoSignal ->
                if (plan.confirm) pause(step, "Check this was fully done, then press Skip to mark it done: ${step.text}")
                else markDone(step, "all actions finished")
            StepCompletion.Verdict.NotYet ->
                when {
                    plan.trustTasks -> markDone(step, "its part of the quest is done")
                    ++current.waitingAfterTasks >= SETTLE_TICKS ->
                        pause(step, "the actions finished but the game does not show the step as done")
                }
        }
    }

    /**
     * Ends a chat still open when a step is finished: clicks continue, or an option that ends the
     * conversation and never one that agrees to something. True while it is still dealing with it.
     */
    private fun endChat(current: Active): Boolean {
        if (current.chatTicks >= MAX_CHAT_TICKS) return false
        val options = game.dialogOptions()
        if (options.isNotEmpty()) {
            val ending = DialoguePlan.endingOption(options) ?: return false
            current.chatTicks++
            log("Ending the chat the step left open: $ending")
            actions.chooseDialogOption(ending)
            return true
        }
        if (game.canContinueDialog) {
            current.chatTicks++
            actions.continueDialog()
            return true
        }
        return false
    }

    /** Does an earlier step first, then this one again. Only once per earlier step, so it cannot loop. */
    private fun goBack(step: Step, need: TaskResult.NeedsStep) {
        val earlier = guide.steps.firstOrNull { it.id == need.stepId }
        if (earlier == null || earlier.id == step.id || !detoured.add(earlier.id)) {
            return pause(step, need.reason)
        }
        detours.addFirst(earlier)
        active = null
        log("Going back to step ${guide.steps.indexOf(earlier) + 1} first, since ${need.reason}")
    }

    private fun nextStep(): Step? {
        detours.firstOrNull()?.let { return it }
        val done = completedIds()
        return guide.steps.drop(floor).firstOrNull { it.id !in done }
    }

    private fun completedIds(): Set<String> = progress.completedIds().let { done -> redo?.let { done - it } ?: done }

    private fun begin(step: Step): Active {
        val plan = planner.plan(step)
        log("Step: ${step.text}")
        return Active(step, plan, completion.baseline(step)).also { active = it }
    }

    private fun markDone(step: Step, why: String) {
        if (step.id == redo) redo = null
        detours.remove(step)
        progress.markComplete(step.id)
        active = null
        log("Done ($why): ${step.text}")
    }

    private fun pause(step: Step, reason: String) {
        state = State.Paused(step, reason)
        log("Paused: $reason")
    }

    companion object {
        /** Ticks to wait for the game to catch up after the last action of a step. */
        const val SETTLE_TICKS = 10
        const val MAX_CHAT_TICKS = 12

        /** How many times longer a training step may run, e.g. agility laps for a level. */
        const val PATIENT_FACTOR = 8
    }
}
