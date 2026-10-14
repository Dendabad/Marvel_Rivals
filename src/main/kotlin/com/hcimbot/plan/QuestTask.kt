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
    /**
     * For the step that finishes a quest ("talk to Veos to complete X Marks the
     * Spot"): do any filled in part still owed on the way, like the last dig.
     * Any other bounded step leaves those to the steps the guide gives them.
     */
    private val finishesQuest: Boolean = false,
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
        val fill = QuestFills.at(questKey, value)
        val instruction = fill ?: helper.at(value) ?: return TaskResult.Done
        if (fill == null && helper.hasGapAt(value)) {
            return TaskResult.Failed("the guide data has no Quest Helper instructions for this part of $questKey")
        }

        instruction.whenIn.forEach { ctx.conditions.observe(it.condition) }
        val chosen = instruction.whenIn.firstOrNull { branch -> branch.condition?.let { ctx.conditions.holds(it) } == true }
            ?: instruction

        if (boundedTo != null) {
            // With no ids to go on, the step's part is over as soon as the quest advances.
            // Quest Helper often says "get there first" (climb down the ladder) with the step's
            // own NPC as a branch for once you are there (talk to Sedridor in the basement).
            // When any part of an instruction is about the step's NPC, all of it is the way there.
            val ours = (listOf(instruction) + instruction.whenIn).any { part -> part.ids.any { it in boundedTo } }
            if (value > start && (boundedTo.isEmpty() || !ours)) return TaskResult.Done
            if (fill != null && !ours && !finishesQuest) {
                // A dig or similar belongs to a later guide step, so this step's part is behind us.
                // Doing it here would run ahead of the guide, e.g. dig away the scroll step 14 banks.
                return TaskResult.Done
            }
            if (value == start && boundedTo.isNotEmpty() && !ours && current == null && fill == null) {
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
        val task = currentTask ?: when (val made = taskFor(chosen, ctx)) {
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

    private fun taskFor(step: Instruction, ctx: TaskContext): Made {
        // Also stops "climb down into the hole ... fight Sourhog (level 37)" before the fight.
        tooStrong(step.text, ctx)?.let { return Made.Refused(it) }
        val tile = step.tile
        val extra = QuestFills.extraDialogue(questKey)
        val dialogue = if (step.dialogue.isEmpty() && extra.isEmpty()) DialoguePlan.None else DialoguePlan.Texts(step.dialogue + extra)
        return when (step.kind) {
            "dig" -> tile?.let { Made.Ready(DigTask(it)) } ?: Made.Refused("no location for this dig")
            "walk" -> tile?.let { Made.Ready(WalkTask(it, radius = 1)) } ?: Made.Refused("no location for this walk")
            "npc" -> {
                if (step.ids.isEmpty()) return Made.Refused("no NPC id for this step")
                // Updates give NPCs new ids (Morgan is 16274 before Vampyre Slayer now, where
                // Quest Helper's data has only 3479). The guide step lists the current ones.
                val ids = if (boundedTo != null && step.ids.any { it in boundedTo }) (step.ids + boundedTo).distinct() else step.ids
                // A left click on a monster attacks it, so the option has to be known.
                val action = StepPlanner.verb(step.text)
                    ?: if (step.dialogue.isNotEmpty() || HANDING_IN.containsMatchIn(step.text)) "Talk-to" else null
                    ?: return Made.Refused("unclear what to click")
                if (action == "Attack" && !allowCombat) return Made.Refused("combat is turned off for safety")
                Made.Ready(InteractTask(InteractTask.Kind.NPC, ids, emptyList(), listOfNotNull(tile), action, dialogue))
            }
            "object" -> {
                // Quest Helper often says "use these stairs" without the stair's id. Its
                // branches say where they lead, so walk there: the walker climbs the stairs.
                stairBranch(step)?.tile?.let { return Made.Ready(WalkTask(it, radius = 3)) }
                // guide.json leaves some ids out but keeps RuneLite's name for the object.
                val ids = step.ids.ifEmpty { listOfNotNull(step.constant?.let(ctx.game::objectIdNamed)) }
                if (ids.isEmpty()) return Made.Refused("no object id for this step")
                // Quest Helper highlights objects for a left click, so that is the fallback here.
                val action = StepPlanner.verb(step.text)
                Made.Ready(InteractTask(InteractTask.Kind.OBJECT, ids, emptyList(), listOfNotNull(tile), action, dialogue))
            }
            else -> Made.Refused("unknown quest step kind ${step.kind}")
        }
    }

    /**
     * A hardcore account must not pick fights it may lose. Quest Helper names a
     * boss's level ("Be prepared to fight Sourhog (level 37)"): anything well above
     * the player's Hitpoints level is left to the player.
     */
    private fun tooStrong(text: String, ctx: TaskContext): String? {
        val level = LEVEL.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val hitpoints = ctx.game.skillLevel("HITPOINTS")
        if (level <= hitpoints * 3 / 2 + LEVEL_MARGIN) return null
        return "a level $level fight is too risky to leave to the bot at $hitpoints Hitpoints"
    }

    /** The branch an id-less stair or ladder step leads to: the first one with a place on another floor. */
    private fun stairBranch(step: Instruction): Instruction? {
        if (step.kind != "object" || step.ids.isNotEmpty()) return null
        val from = step.tile
        return step.whenIn.firstOrNull { branch -> branch.tile?.let { from == null || it.plane != from.plane } == true }
    }

    companion object {
        const val MAX_REPEATS = 3
        private val LEVEL = Regex("\\(level[- ](\\d+)\\)", RegexOption.IGNORE_CASE)
        private const val LEVEL_MARGIN = 5

        /** "Return to Gertrude", "Bring the notes to Sedridor", "Report back to Tobyn": all talks. */
        private val HANDING_IN = Regex("^(return|go back|bring|report back|give)\\b", RegexOption.IGNORE_CASE)

        /** Ticks to let the quest var catch up before doing an instruction again. */
        const val SETTLE_TICKS = 3
    }
}
