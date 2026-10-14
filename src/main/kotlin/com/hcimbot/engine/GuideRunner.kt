package com.hcimbot.engine

import com.hcimbot.game.GameActions
import com.hcimbot.game.GameState
import com.hcimbot.guide.Guide
import com.hcimbot.guide.Step
import com.hcimbot.plan.StepPlan
import com.hcimbot.plan.StepPlanner
import com.hcimbot.plan.Task
import com.hcimbot.plan.TaskContext
import com.hcimbot.plan.TaskResult
import com.hcimbot.progress.ProgressStore
import com.hcimbot.route.RouteStore

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
    routes: RouteStore? = null,
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

    private val conditions = Conditions(game)
    private val completion = StepCompletion(guide, game, conditions)
    private val planner = StepPlanner(guide, settings, routes)
    private val safety = SafetyMonitor(settings)
    private val context = TaskContext(game, actions, conditions, log)

    private class Active(val step: Step, val plan: StepPlan, val baseline: StepCompletion.Baseline) {
        var taskIndex = 0
        var ticks = 0
        var waitingAfterTasks = 0
        val combat: Boolean get() = (plan as? StepPlan.Auto)?.combat == true
    }

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

    /** Treat the paused step as done and move on. */
    fun skipCurrent() {
        val step = (state as? State.Paused)?.step ?: return
        markDone(step, "skipped by the player")
        state = State.Running
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

    fun clearHalt() {
        if (state is State.Halted) state = State.Stopped
    }

    fun onGameTick() {
        if (!game.isLoggedIn) return
        val paused = state as? State.Paused
        if (paused != null) {
            // Ticked in the plugin while we waited: carry on.
            if (paused.step.id in progress.completedIds()) state = State.Running else return
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

        if (completion.check(step, current.baseline) == StepCompletion.Verdict.Done) {
            // Never walk off with a bank or shop still open.
            if (game.isBankOpen || game.isShopOpen) {
                if (!game.isBusy) actions.closeInterface()
                return
            }
            return markDone(step, "the game shows it is done")
        }
        if (++current.ticks > settings.stepTimeoutTicks) return pause(step, "this step is taking too long")

        when (val plan = current.plan) {
            is StepPlan.Manual -> pause(step, plan.reason)
            is StepPlan.Skip -> markDone(step, plan.reason)
            is StepPlan.Auto -> runTasks(current, plan)
        }
    }

    private fun runTasks(current: Active, plan: StepPlan.Auto) {
        val step = current.step
        val task: Task? = plan.tasks.getOrNull(current.taskIndex)
        if (task != null) {
            when (val result = task.tick(context)) {
                TaskResult.Running -> Unit
                TaskResult.Done -> current.taskIndex++
                is TaskResult.Failed -> pause(step, "${task.description}: ${result.reason}")
            }
            return
        }

        // Every task finished.
        when (completion.check(step, current.baseline)) {
            StepCompletion.Verdict.Done -> markDone(step, "the game shows it is done")
            StepCompletion.Verdict.NoSignal ->
                if (plan.confirm) pause(step, "Check this was fully done, then tick it in the guide: ${step.text}")
                else markDone(step, "all actions finished")
            StepCompletion.Verdict.NotYet ->
                when {
                    plan.trustTasks -> markDone(step, "its part of the quest is done")
                    ++current.waitingAfterTasks >= SETTLE_TICKS ->
                        pause(step, "the actions finished but the game does not show the step as done")
                }
        }
    }

    private fun nextStep(): Step? {
        val done = progress.completedIds()
        return guide.steps.firstOrNull { it.id !in done }
    }

    private fun begin(step: Step): Active {
        val plan = planner.plan(step)
        log("Step: ${step.text}")
        return Active(step, plan, completion.baseline(step)).also { active = it }
    }

    private fun markDone(step: Step, why: String) {
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
    }
}
