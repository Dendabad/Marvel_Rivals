package com.hcimbot.plan

import com.hcimbot.guide.Instruction
import com.hcimbot.guide.QuestHelper

/**
 * Plays a quest the way the B0aty plugin shows it: by reading the quest's
 * progress var, looking up the Quest Helper instruction for that value and
 * picking the branch whose condition holds. The runner decides when the guide
 * step is finished; this task just keeps doing whatever the instruction in force
 * says until then.
 *
 * A guide step such as "Talk to Father Aereck" is one part of a quest, with
 * other guide steps fitted in before the next part. [boundedTo] holds the NPC
 * or object ids that step is about: the task does only that part, finishing
 * once the quest has moved on to something else. Steps that say to carry a
 * quest on ("Complete Rune Mysteries") pass null and run until the runner sees
 * the step's goal reached.
 *
 * If the same instruction is carried out [MAX_REPEATS] times without the quest
 * moving on, the bot pauses rather than loop, since something in the game is
 * not what the guide expects.
 */
class QuestTask(
    private val questKey: String,
    private val helper: QuestHelper,
    private val allowCombat: Boolean,
    private val boundedTo: Set<Int>? = null,
    /** Done once the quest's progress value reaches this, for a step script that covers a known stretch. */
    private val stopAtValue: Int? = null,
) : Task {
    private var startValue: Int? = null
    private var current: Instruction? = null
    private var currentTask: Task? = null
    private var repeats = 0
    private var settling = 0
    override val description = "Quest: $questKey"

    override fun tick(ctx: TaskContext): TaskResult {
        val progress = helper.progressVar ?: return TaskResult.Failed("$questKey has no progress variable in guide.json")
        val value = ctx.conditions.readVar(progress.kind, progress.id)
        val start = startValue ?: value.also { startValue = it }
        if (stopAtValue != null && value >= stopAtValue) return TaskResult.Done
        val instruction = helper.at(value) ?: return TaskResult.Done
        if (helper.hasGapAt(value)) {
            return TaskResult.Failed("the guide data has no Quest Helper instructions for this part of $questKey")
        }

        instruction.whenIn.forEach { ctx.conditions.observe(it.condition) }
        val chosen = instruction.whenIn.firstOrNull { branch -> branch.condition?.let { ctx.conditions.holds(it) } == true }
            ?: instruction

        if (boundedTo != null) {
            // With no ids to go on, the step's part is over as soon as the quest advances.
            // Heading up to the step's NPC through a stair with no id counts as our part too.
            val ours = chosen.ids.any { it in boundedTo } ||
                (chosen === instruction && stairBranch(instruction)?.ids?.any { it in boundedTo } == true)
            if (value > start && (boundedTo.isEmpty() || !ours)) return TaskResult.Done
            if (value == start && boundedTo.isNotEmpty() && !ours && current == null) {
                return TaskResult.Failed("the guide step is about ids $boundedTo but the quest is at \"${chosen.text}\"")
            }
        }

        val missing = chosen.items.filterNot { ctx.conditions.has(it) }
        if (missing.isNotEmpty()) {
            return TaskResult.Failed("${chosen.text} needs ${missing.joinToString { "${it.count}x ${it.name}" }}")
        }

        if (chosen !== current) {
            current = chosen
            currentTask = null
            repeats = 0
            settling = 0
            ctx.log("Quest step: ${chosen.text}")
        }
        if (settling > 0) {
            settling--
            return TaskResult.Running
        }
        if (chosen.text.contains("wilderness", ignoreCase = true)) {
            return TaskResult.Failed("quest step goes into the Wilderness: ${chosen.text}")
        }
        val task = currentTask ?: when (val made = taskFor(chosen)) {
            is Made.Ready -> made.task.also { currentTask = it }
            is Made.Refused -> return TaskResult.Failed("${made.why}: ${chosen.text}")
        }

        return when (val result = task.tick(ctx)) {
            TaskResult.Done -> {
                // The instruction is done but the quest has not moved on yet. Run it
                // again, which covers multi-part talks and repeated searches.
                if (++repeats >= MAX_REPEATS) {
                    TaskResult.Failed("did \"${chosen.text}\" $MAX_REPEATS times and the quest did not advance")
                } else {
                    currentTask = null
                    settling = SETTLE_TICKS
                    TaskResult.Running
                }
            }
            else -> result
        }
    }

    private sealed interface Made {
        class Ready(val task: Task) : Made
        class Refused(val why: String) : Made
    }

    private fun taskFor(step: Instruction): Made {
        val tile = step.tile
        val dialogue = if (step.dialogue.isEmpty()) DialoguePlan.None else DialoguePlan.Texts(step.dialogue)
        return when (step.kind) {
            "walk" -> tile?.let { Made.Ready(WalkTask(it, radius = 1)) } ?: Made.Refused("no location for this walk")
            "npc" -> {
                if (step.ids.isEmpty()) return Made.Refused("no NPC id for this step")
                // A left click on a monster attacks it, so the option has to be known.
                val action = StepPlanner.verb(step.text) ?: if (step.dialogue.isNotEmpty()) "Talk-to" else null
                    ?: return Made.Refused("unclear what to click")
                if (action == "Attack" && !allowCombat) return Made.Refused("combat is turned off for safety")
                Made.Ready(InteractTask(InteractTask.Kind.NPC, step.ids, emptyList(), listOfNotNull(tile), action, dialogue))
            }
            "object" -> {
                if (step.ids.isEmpty()) {
                    // Quest Helper often says "use these stairs" without the stair's id. Its
                    // branches say where they lead, so walk there: the walker climbs the stairs.
                    val leadsTo = stairBranch(step)?.tile ?: return Made.Refused("no object id for this step")
                    return Made.Ready(WalkTask(leadsTo, radius = 3))
                }
                // Quest Helper highlights objects for a left click, so that is the fallback here.
                val action = StepPlanner.verb(step.text)
                Made.Ready(InteractTask(InteractTask.Kind.OBJECT, step.ids, emptyList(), listOfNotNull(tile), action, dialogue))
            }
            else -> Made.Refused("unknown quest step kind ${step.kind}")
        }
    }

    /** The branch an id-less stair or ladder step leads to: the first one with a place on another floor. */
    private fun stairBranch(step: Instruction): Instruction? {
        if (step.kind != "object" || step.ids.isNotEmpty()) return null
        val from = step.tile
        return step.whenIn.firstOrNull { branch -> branch.tile?.let { from == null || it.plane != from.plane } == true }
    }

    companion object {
        const val MAX_REPEATS = 3

        /** Ticks to let the quest var catch up before doing an instruction again. */
        const val SETTLE_TICKS = 3
    }
}
