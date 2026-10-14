package com.hcimbot.plan

import com.hcimbot.guide.Instruction
import com.hcimbot.guide.ItemRef
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
    /** Done as soon as this item is carried: the step's whole point, whatever the quest asks for next. */
    private val doneWithItem: Int? = null,
    /**
     * One talk is the whole step ("Talk to Old Man Yarlo to continue Daddy's Home (2)"): over once that talk has
     * moved the quest on. Not for a step that says to talk again, which is two talks to the same person.
     */
    private val endsAfterTalk: Boolean = false,
) : Task {
    private var startValue: Int? = null
    private var current: Instruction? = null
    private var currentTask: Task? = null
    private var repeats = 0
    private var lastSignature = ""
    private var valueWhenChosen = 0
    private var settling = 0
    override val description = "Quest: $questKey"

    override fun tick(ctx: TaskContext): TaskResult {
        val progress = helper.progressVar ?: return TaskResult.Failed("$questKey has no progress variable in guide.json")
        // Building in a house opens a menu of what can be built, which needs its piece picked (Yarlo's furniture).
        if (ctx.game.showsChoice(FURNITURE_MENU)) {
            ctx.actions.pickFurniture()
            return TaskResult.Running
        }
        val value = ctx.conditions.readVar(progress.kind, progress.id)
        val start = startValue ?: value.also { startValue = it }
        if (stopAtValue != null && value >= stopAtValue) return TaskResult.Done
        if (doneWithItem != null && doneWithItem in ctx.game.inventory()) return TaskResult.Done
        // The talk this step is about has moved the quest on: the step is over, even though the quest's
        // next instruction is a talk to the same person again (Yarlo, from furniture to rebuilding).
        val talking = current
        if (endsAfterTalk && boundedTo != null && boundedTo.isNotEmpty() && talking != null && value > valueWhenChosen &&
            talking.kind == "npc" && talking.ids.any { it in boundedTo }
        ) {
            return TaskResult.Done
        }
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
            // Items the guide has not asked for yet are not fetched: the guide has its own bank and shop steps.
            // A step that has already moved the quest on is over once the quest wants more than it was for.
            if (boundedTo != null && value > start) return TaskResult.Done
            return TaskResult.Failed("${chosen.text} needs ${missing.joinToString { "${it.count}x ${it.name}" }}")
        }

        if (chosen !== current) {
            current = chosen
            valueWhenChosen = value
            currentTask = null
            repeats = 0
            settling = 0
            ctx.log("Quest step: ${chosen.text}")
            val shown = ctx.game.helperStep(questKey)
            if (shown != null) {
                ctx.log("Quest Helper: ${shown.kind} \"${shown.text.take(40)}\" npc ${shown.npcIds.take(3)} obj ${shown.objectIds.take(3)}")
            } else {
                ctx.game.helperProblem()?.let { ctx.log("Quest Helper: $it") }
            }
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
                // The quest's own variable may not move while a part has several pieces (Yarlo's furniture: five builds
                // for one step). What Quest Helper shows, or what the bag holds, changing counts as moving on.
                val signature = ctx.game.helperStep(questKey)?.text.orEmpty() + "|" + ctx.game.inventory().entries.sumOf { it.key.toLong() * it.value }
                if (signature != lastSignature) {
                    lastSignature = signature
                    repeats = 0
                }
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
        val dialogue = DialoguePlan.Texts(step.dialogue + extra) { ctx.game.helperStep(questKey)?.choices.orEmpty() }
        return when (step.kind) {
            "dig" -> tile?.let { Made.Ready(DigTask(it)) } ?: Made.Refused("no location for this dig")
            "walk" -> tile?.let { Made.Ready(WalkTask(it, radius = 1)) } ?: Made.Refused("no location for this walk")
            "npc" -> {
                // "Follow the guard slowly behind them, hiding in the marked locations" (Children of the Sun).
                if (step.tiles.size >= 2 && step.text.startsWith("Follow the guard", ignoreCase = true)) {
                    val ids = (step.ids + listOfNotNull(step.constant?.let(ctx.game::npcIdNamed))).distinct()
                    if (ids.isEmpty()) return Made.Refused("no NPC id for the guard to follow")
                    return Made.Ready(FollowGuardTask(ids, step.tiles))
                }
                if (questKey == "childrenofthesun" && step.text.startsWith("Mark the guard", ignoreCase = true)) {
                    return Made.Ready(MarkGuardsTask())
                }
                if (step.ids.isEmpty()) return fromHelper(ctx, dialogue) ?: Made.Refused("no NPC id for this step")
                // Updates give NPCs new ids (Morgan is 16274 before Vampyre Slayer now, where
                // Quest Helper's data has only 3479). The guide step lists the current ones.
                val ids = if (boundedTo != null && step.ids.any { it in boundedTo }) (step.ids + boundedTo).distinct() else step.ids
                // A left click on a monster attacks it, so the option has to be known.
                val action = StepPlanner.verb(step.text)
                    ?: if (step.dialogue.isNotEmpty() || HANDING_IN.containsMatchIn(step.text)) "Talk-to" else null
                    ?: return fromHelper(ctx, dialogue) ?: Made.Refused("unclear what to click")
                if (action == "Attack" && !allowCombat) return Made.Refused("combat is turned off for safety")
                Made.Ready(InteractTask(InteractTask.Kind.NPC, ids, emptyList(), listOfNotNull(tile), action, dialogue))
            }
            "object" -> {
                // Quest Helper often says "use these stairs" without the stair's id. Its
                // branches say where they lead, so walk there: the walker climbs the stairs.
                stairBranch(step)?.tile?.let { return Made.Ready(WalkTask(it, radius = 3)) }
                // The guide data comes first. Only when it gives no verb to click (a left click on Daddy's Home's
                // removed furniture builds it again) is what Quest Helper shows used for the click.
                if (StepPlanner.verb(step.text) == null) {
                    ctx.game.helperStep(questKey)?.takeIf { it.objectIds.isNotEmpty() }?.let { shown ->
                        helperVerb(shown.text)?.let { verb ->
                            ctx.log("Guide data has no click for this, following Quest Helper: ${shown.text.take(80)} (object ${shown.objectIds}, $verb)")
                            return Made.Ready(InteractTask(InteractTask.Kind.OBJECT, shown.objectIds, emptyList(), listOfNotNull(shown.tile), verb, dialogue))
                        }
                    }
                }
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

    /** The click Quest Helper's text asks for: "Demolish the broken stool", "Right-click remove the carpet". */
    private fun helperVerb(text: String): String? = StepPlanner.verb(text) ?: when {
        text.startsWith("demolish", ignoreCase = true) -> "Demolish"
        text.contains("remove", ignoreCase = true) && text.length < 60 -> "Remove"
        // "Build the stool", "Right-click build the carpet", "Rebuild the furniture".
        text.contains("build", ignoreCase = true) && text.length < 60 -> "Build"
        else -> null
    }

    /**
     * The guide data has no usable target for this step, so do what the Quest Helper plugin is showing:
     * talk to, or interact with, the NPC it points at. Only for plain talking and handing in, never a fight.
     */
    private fun fromHelper(ctx: TaskContext, dialogue: DialoguePlan): Made? {
        val shown = ctx.game.helperStep(questKey) ?: return null
        if (shown.npcIds.isEmpty()) return null
        val action = StepPlanner.verb(shown.text)?.takeIf { it != "Attack" }
            ?: if (HELPER_TALK.containsMatchIn(shown.text)) "Talk-to" else return null
        ctx.log("Following Quest Helper: ${shown.text.take(80)} (npc ${shown.npcIds})")
        return Made.Ready(InteractTask(InteractTask.Kind.NPC, shown.npcIds, emptyList(), listOfNotNull(shown.tile), action, dialogue))
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
        const val FURNITURE_MENU = "Furniture Creation Menu"
        private val HELPER_TALK = Regex("^(talk|speak|report|return|go back|bring|give|ask)\\b", RegexOption.IGNORE_CASE)
        private val LEVEL = Regex("\\(level[- ](\\d+)\\)", RegexOption.IGNORE_CASE)
        private const val LEVEL_MARGIN = 5

        /** "Return to Gertrude", "Bring the notes to Sedridor", "Report back to Tobyn": all talks. */
        private val HANDING_IN = Regex("^(return|go back|bring|report back|give)\\b", RegexOption.IGNORE_CASE)

        /** Ticks to let the quest var catch up before doing an instruction again. */
        const val SETTLE_TICKS = 3
    }
}
