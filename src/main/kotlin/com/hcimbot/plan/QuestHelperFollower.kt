package com.hcimbot.plan

import com.hcimbot.game.HelperStep

/**
 * Follows the Quest Helper plugin for any quest, whatever the guide data says. Each tick it reads what Quest Helper is pointing at (its
 * instruction text, the NPC or object ids it highlights, the tile, the chat options it lists), works out the click from the words of the
 * instruction, and does it: talk to the NPC, click the object with the verb the text gives, walk to the tile. It carries on until Quest
 * Helper has nothing left to show, which is the quest done.
 *
 * It is the general version of what the hand written scripts do for particular quests, and keeps what they learned:
 *  - a book or window left open over the screen is closed before the next click,
 *  - the same instruction done three times without Quest Helper moving on pauses the bot, rather than looping,
 *  - fights are refused unless [allowCombat], and a boss well above the player's Hitpoints level is never started,
 *  - the Wilderness is never entered,
 *  - with [avoidNpc] (the witch), every move goes through short hops that stay out of that NPC's sight.
 *
 * What it cannot do it says: an instruction that needs an item used on something, or has nothing to click, pauses the bot with the
 * instruction's words so a hand written step can be added for it.
 */
class FollowQuestHelperTask(
    private val questKey: String,
    private val allowCombat: Boolean = false,
    private val avoidNpc: String? = null,
    /** Done as soon as this holds, for a step that covers only part of the quest. */
    private val stopWhen: ((TaskContext) -> Boolean)? = null,
    /** A safety limit on instructions followed, so a quest that never finishes cannot go on for ever. */
    private val maxInstructions: Int = MAX_INSTRUCTIONS,
) : Task {
    override val description = "Follow Quest Helper: $questKey"

    private var shownText = ""
    private var sub: Task? = null
    private var settling = 0
    private var closes = 0
    private var repeats = 0
    private var lastSignature = ""
    private var instructions = 0

    override fun tick(ctx0: TaskContext): TaskResult {
        val ctx = if (avoidNpc != null) TaskContext(ctx0.game, SneakActions(ctx0.actions, ctx0.game, avoidNpc), ctx0.conditions, ctx0.log) else ctx0
        val game = ctx.game
        if (stopWhen?.invoke(ctx) == true) return TaskResult.Done

        if (avoidNpc != null) {
            val sneak = ctx.actions as SneakActions
            if (!sneak.hereIsSafe()) {
                sneak.hide(ctx.log)
                return TaskResult.Running
            }
        }
        // A book, map or the like left open sends clicks to itself.
        val windowOpen = !game.isBankOpen && !game.isShopOpen && game.showsCloseableWindow
        if (windowOpen && closes < MAX_CLOSES) {
            closes++
            ctx.actions.closeWindow()
            return TaskResult.Running
        }
        if (!windowOpen) closes = 0
        if (settling > 0) {
            settling--
            return TaskResult.Running
        }

        val shown = game.helperStep(questKey)
        if (shown == null) {
            // Nothing to show is the quest finished, unless Quest Helper says why it has nothing.
            return game.helperProblem()?.let { TaskResult.Failed("Quest Helper: $it") } ?: TaskResult.Done
        }
        if (shown.text != shownText) {
            shownText = shown.text
            sub = null
            repeats = 0
            if (++instructions > maxInstructions) return TaskResult.Failed("followed $maxInstructions Quest Helper instructions and $questKey is still not done")
            ctx.log("Quest Helper: ${shown.text.take(90)} (npc ${shown.npcIds.take(3)}, obj ${shown.objectIds.take(3)})")
        }
        val task = sub ?: when (val made = plan(shown, ctx)) {
            is Made.Ready -> made.task.also { sub = it }
            is Made.Refused -> return TaskResult.Failed("${made.why}: ${shown.text}")
        }
        return when (val result = task.tick(ctx)) {
            TaskResult.Done -> {
                // Done, but Quest Helper still shows it: the quest takes a moment to move on, or it needs doing again (a talk in
                // several parts). The bag changing counts as moving on too.
                val signature = shown.text + "|" + game.inventory().entries.sumOf { it.key.toLong() * it.value }
                if (signature != lastSignature) {
                    lastSignature = signature
                    repeats = 0
                }
                if (++repeats >= MAX_REPEATS) {
                    TaskResult.Failed("did \"${shown.text.take(80)}\" $MAX_REPEATS times and Quest Helper did not move on")
                } else {
                    sub = null
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

    private fun plan(shown: HelperStep, ctx: TaskContext): Made {
        val text = shown.text
        if (text.contains("wilderness", ignoreCase = true)) return Made.Refused("this goes into the Wilderness")
        val verb = verbOf(text)
        val fight = verb == "Attack" || FIGHT.containsMatchIn(text)
        if (fight) {
            if (!allowCombat) return Made.Refused("combat is turned off for safety")
            tooStrong(text, ctx)?.let { return Made.Refused(it) }
        }
        val dialogue = DialoguePlan.Texts(emptyList()) { ctx.game.helperStep(questKey)?.choices.orEmpty() }
        return when {
            shown.npcIds.isNotEmpty() && fight ->
                Made.Ready(KillTask(shown.npcIds, emptyList(), emptyList(), maxKills = 4, lookFirst = shown.tile, killsNeeded = 1))
            shown.npcIds.isNotEmpty() ->
                Made.Ready(InteractTask(InteractTask.Kind.NPC, shown.npcIds, emptyList(), listOfNotNull(shown.tile), verb ?: "Talk-to", dialogue))
            shown.objectIds.isNotEmpty() ->
                Made.Ready(InteractTask(InteractTask.Kind.OBJECT, shown.objectIds, emptyList(), listOfNotNull(shown.tile), verb, dialogue, near = shown.tile))
            USE_ON.containsMatchIn(text) -> Made.Refused("this needs an item used on something, and Quest Helper does not say which item")
            shown.tile != null && (verb == null || MOVE.containsMatchIn(text)) -> Made.Ready(WalkTask(shown.tile, radius = 2))
            else -> Made.Refused("unclear what to click")
        }
    }

    /** A boss named with its level, well above the player's Hitpoints level, is left to the player. */
    private fun tooStrong(text: String, ctx: TaskContext): String? {
        val level = LEVEL.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val hitpoints = ctx.game.skillLevel("HITPOINTS")
        if (level <= hitpoints * 3 / 2 + LEVEL_MARGIN) return null
        return "a level $level fight is too risky to leave to the bot at $hitpoints Hitpoints"
    }

    companion object {
        const val MAX_REPEATS = 3
        const val SETTLE_TICKS = 3
        const val MAX_INSTRUCTIONS = 80
        private const val MAX_CLOSES = 4
        private const val LEVEL_MARGIN = 5
        private val LEVEL = Regex("\\(level[- ](\\d+)\\)", RegexOption.IGNORE_CASE)
        private val FIGHT = Regex("\\b(kill|attack|fight|defeat|slay)\\b", RegexOption.IGNORE_CASE)
        private val USE_ON = Regex("^use\\b.*\\bon\\b", RegexOption.IGNORE_CASE)
        private val MOVE = Regex("^(go|head|walk|run|travel|make your way|return to)\\b", RegexOption.IGNORE_CASE)

        /** The first words of an instruction and the menu option they mean, longest first. */
        private val VERBS = listOf(
            "talk to" to "Talk-to", "speak to" to "Talk-to", "speak with" to "Talk-to", "talk with" to "Talk-to",
            "ask" to "Talk-to", "give" to "Talk-to", "hand" to "Talk-to", "bring" to "Talk-to", "report" to "Talk-to",
            "return" to "Talk-to", "go back to" to "Talk-to", "tell" to "Talk-to",
            "climb up" to "Climb-up", "climb down" to "Climb-down", "climb over" to "Climb-over", "climb through" to "Climb-through",
            "climb" to "Climb", "go up" to "Climb-up", "go down" to "Climb-down", "go through" to "Open", "go into" to "Enter",
            "enter" to "Enter", "open" to "Open", "close" to "Close", "unlock" to "Unlock", "search" to "Search", "look in" to "Search",
            "look under" to "Search", "look at" to "Look-at", "inspect" to "Inspect", "read" to "Read", "pick up" to "Take",
            "pick" to "Pick", "take" to "Take", "grab" to "Take", "push" to "Push", "pull" to "Pull", "pray at" to "Pray-at",
            "pray" to "Pray-at", "cross" to "Cross", "jump" to "Jump", "squeeze" to "Squeeze-through", "board" to "Board",
            "operate" to "Operate", "turn" to "Turn", "pour" to "Pour", "drink from" to "Drink", "mine" to "Mine", "chop" to "Chop down",
            "fish" to "Fish", "kill" to "Attack", "attack" to "Attack", "fight" to "Attack", "defeat" to "Attack", "slay" to "Attack",
            "trade" to "Trade", "pickpocket" to "Pickpocket", "steal from" to "Steal-from", "dig" to "Dig", "light" to "Light",
        ).sortedByDescending { it.first.length }

        /** The menu option an instruction's opening words mean, or null. "Right-click" and "Click" prefixes are looked through. */
        fun verbOf(text: String): String? {
            val lowered = text.trim().lowercase().removePrefix("right-click ").removePrefix("click ").removePrefix("left-click ")
            return VERBS.firstOrNull { (phrase, _) -> lowered == phrase || lowered.startsWith("$phrase ") }?.second
        }
    }
}

/**
 * Runs [primary], the quest as the guide data lays it out, and when it stops because it has nothing to click or no data for this part
 * of the quest, carries on with [FollowQuestHelperTask], which reads Quest Helper itself. Other stops (a fight refused, an item missing)
 * are passed on, since following Quest Helper would meet the same thing.
 */
class QuestWithHelperFallback(
    private val primary: Task,
    private val questKey: String,
    private val allowCombat: Boolean,
) : Task {
    private var fallback: Task? = null
    override val description get() = fallback?.description ?: primary.description

    override fun tick(ctx: TaskContext): TaskResult {
        fallback?.let { return it.tick(ctx) }
        val result = primary.tick(ctx)
        if (result is TaskResult.Failed && STUCK.containsMatchIn(result.reason)) {
            ctx.log("The guide data cannot take this part of $questKey (${result.reason.take(80)}), following Quest Helper instead")
            return FollowQuestHelperTask(questKey, allowCombat).also { fallback = it }.tick(ctx)
        }
        return result
    }

    private companion object {
        val STUCK = Regex("unclear what to click|no object id|no NPC id|no location for|no Quest Helper instructions|unknown quest step", RegexOption.IGNORE_CASE)
    }
}
