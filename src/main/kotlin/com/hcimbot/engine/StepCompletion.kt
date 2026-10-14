package com.hcimbot.engine

import com.hcimbot.game.GameState
import com.hcimbot.guide.Completion
import com.hcimbot.guide.Guide
import com.hcimbot.guide.Step
import com.hcimbot.guide.toTile

/**
 * Decides when a guide step is finished, using the same signals the B0aty
 * plugin auto-ticks on (AutoTick, Completion, QuestProgress), so a step the bot
 * marks done is one the plugin would have marked done too.
 */
class StepCompletion(
    private val guide: Guide,
    private val game: GameState,
    private val conditions: Conditions,
) {
    /** What a step looked like when the bot started it. Quest steps compare against this. */
    class Baseline(val questValue: Int, val panel: Int?)

    sealed interface Verdict {
        /** The game shows the step is done. */
        object Done : Verdict

        /** The game shows the step is not done yet. */
        object NotYet : Verdict

        /** The step has no signal the game can check; it is done when its tasks finish. */
        object NoSignal : Verdict
    }

    fun baseline(step: Step): Baseline = Baseline(questValue(step), questPanel(step))

    fun check(step: Step, baseline: Baseline): Verdict {
        step.completion?.let { completion ->
            if (!step.acquires) return verdict(satisfied(completion))
        }
        if (step.questHelper != null && (step.questStep || step.questFollow)) {
            questVerdict(step, baseline)?.let { return it }
        }
        if (!step.questStep && !step.advice) {
            step.arrivesAt.toTile()?.let { where ->
                val at = game.playerTile ?: return Verdict.NotYet
                return verdict(at.distanceTo(where) <= ARRIVAL_TILES)
            }
        }
        if ((step.acquires || step.collects || step.withdraw) && step.items.isNotEmpty()) {
            // Quest items that turn into the next one count as the same item, as the bank step does.
            val wanted = step.items.filter { it.ids.isNotEmpty() && !it.ids.all(com.hcimbot.plan.ItemSources.LATER::contains) }.map(com.hcimbot.plan.ItemSources::widen)
            if (wanted.isNotEmpty()) {
                return verdict(wanted.all { conditions.count(it.ids) >= (it.count ?: 1) })
            }
        }
        return Verdict.NoSignal
    }

    /** Diary bits, varbits and skill levels: every condition present must hold. */
    fun satisfied(completion: Completion): Boolean {
        var any = false
        for (state in completion.varbits) {
            if (game.varbit(state.varbit) != state.value) return false
            any = true
        }
        val varplayer = completion.varplayer
        val bit = completion.bit
        if (varplayer != null && varplayer > 0 && bit != null && bit in 0..31) {
            if ((game.varp(varplayer) shr bit) and 1 != 1) return false
            any = true
        }
        val skill = completion.skill
        val level = completion.level
        if (skill != null && level != null && level in 1..99) {
            if (game.skillLevel(skill) < level) return false
            any = true
        }
        return any
    }

    /** Port of QuestProgress.hasAdvanced. Null when the step has no usable quest signal. */
    private fun questVerdict(step: Step, baseline: Baseline): Verdict? {
        val helper = guide.questHelperFor(step) ?: return null
        if (step.questStopUnresolved) return null

        step.questStopCondition?.let { return verdict(conditions.holds(it)) }
        step.questStopValue?.let { return verdict(questValue(step) >= it) }
        if (step.questStopItems.isNotEmpty()) return verdict(step.questStopItems.all { conditions.has(it) })

        val now = questValue(step)
        val doneAt = step.questDoneAt
        if (step.questStartOnly && doneAt != null) return verdict(now >= doneAt)

        step.questDoneAtPanel?.let { handOver ->
            val panel = questPanel(step) ?: return Verdict.NotYet
            return verdict((baseline.panel ?: Int.MIN_VALUE) < handOver && panel >= handOver)
        }
        if (doneAt != null) return verdict(baseline.questValue < doneAt && now >= doneAt)
        if (step.questCompletes) return verdict(now > 0 && helper.at(now) == null)
        return verdict(now > baseline.questValue)
    }

    fun questValue(step: Step): Int {
        val progress = guide.questHelperFor(step)?.progressVar ?: return 0
        return conditions.readVar(progress.kind, progress.id)
    }

    /** The Quest Helper panel position of the instruction in force right now. */
    private fun questPanel(step: Step): Int? {
        val helper = guide.questHelperFor(step) ?: return null
        val instruction = helper.at(questValue(step)) ?: return null
        val branch = instruction.whenIn.firstOrNull { branch -> branch.condition?.let { conditions.holds(it) } == true }
        return (branch ?: instruction).panel
    }

    private fun verdict(done: Boolean) = if (done) Verdict.Done else Verdict.NotYet

    companion object {
        /** The plugin's ARRIVAL_TILES. */
        const val ARRIVAL_TILES = 10
    }
}
